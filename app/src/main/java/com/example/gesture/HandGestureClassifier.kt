package com.example.gesture

import kotlin.math.abs
import kotlin.math.hypot

enum class DashboardMode {
  CLOSED,
  QUICK_VOICE,
  PERMANENT,
}

/**
 * Spatial dashboard tiles. [ring] is the head-turn loop
 * MENTAL -> VITALS -> RADIATION -> MAP -> (back to) MENTAL. DRILLS sits below the ring.
 */
enum class DashboardPage(val title: String) {
  MENTAL("MENTAL"),
  VITALS("VITALS"),
  RADIATION("RADIATION"),
  MAP("MAP"),
  DRILLS("DRILLS");

  companion object {
    val ring: List<DashboardPage> = listOf(MENTAL, VITALS, RADIATION, MAP)

    fun ringNext(page: DashboardPage): DashboardPage {
      val idx = ring.indexOf(page)
      return if (idx < 0) MENTAL else ring[(idx + 1) % ring.size]
    }

    fun ringPrevious(page: DashboardPage): DashboardPage {
      val idx = ring.indexOf(page)
      return if (idx < 0) MAP else ring[(idx - 1 + ring.size) % ring.size]
    }
  }
}

enum class SwipeDirection {
  PREVIOUS,
  NEXT,
}

data class HandLandmarkPoint(val x: Float, val y: Float, val z: Float = 0f)

/** Hand poses from the MediaPipe Gesture Recognizer canned classifier. */
enum class HandPose(val label: String) {
  NONE("—"),
  OPEN_PALM("PALM"),
  CLOSED_FIST("FIST"),
  POINTING_UP("POINT"),
  THUMB_UP("THUMB UP"),
  THUMB_DOWN("THUMB DOWN"),
  VICTORY("V SIGN"),
  I_LOVE_YOU("ROCK");

  companion object {
    fun fromCategory(name: String?): HandPose =
      when (name) {
        "Open_Palm" -> OPEN_PALM
        "Closed_Fist" -> CLOSED_FIST
        "Pointing_Up" -> POINTING_UP
        "Thumb_Up" -> THUMB_UP
        "Thumb_Down" -> THUMB_DOWN
        "Victory" -> VICTORY
        "ILoveYou" -> I_LOVE_YOU
        else -> NONE
      }
  }
}

data class HandObservation(
  val landmarks: List<HandLandmarkPoint>,
  val pose: HandPose = HandPose.NONE,
  val poseScore: Float = 0f,
)

enum class GestureAction(val label: String) {
  OPEN_DASHBOARD("OPEN"),
  CLOSE_DASHBOARD("CLOSE"),
  NEXT_TILE("NEXT"),
  PREVIOUS_TILE("BACK"),
  RECENTER("RECENTER"),
  ACKNOWLEDGE("ACK ALERT"),
  TOGGLE_EYE_PREVIEW("EYE CAM"),
  CLEAR_DRILL("CLEAR DRILL"),
}

data class GestureUpdate(
  val action: GestureAction? = null,
  val pendingAction: GestureAction? = null,
  val holdProgress: Float = 0f,
  val activePose: HandPose = HandPose.NONE,
  val mapPinchActive: Boolean = false,
  val mapPinchForwardDelta: Float = 0f,
  val mapPinchPanDeltaX: Float = 0f,
  val mapPinchPanDeltaY: Float = 0f,
)

/**
 * Gesture vocabulary (one hand is enough for everything):
 *  - Open palm, hold 0.6 s ............ open dashboard (when closed)
 *  - Open hand swipe left / right ...... previous / next tile (wraps around the ring)
 *  - Closed fist, hold 0.6 s ........... close dashboard
 *  - Pointing up, hold 0.5 s ........... recenter tiles in front of you
 *  - Thumb up, hold 0.4 s .............. acknowledge / silence alerts
 *  - Victory (V), hold 0.6 s ........... toggle eye-camera preview
 *  - Thumb down, hold 0.9 s ............ clear the active drill
 *  - Thumb-index pinch on the MAP tile . zoom (push / pull) and pan (drag)
 *
 * A held pose must stay steady (no large hand travel) and fires once; the pose must be released
 * before it can fire again. Poses come from the ML classifier when confident, otherwise from
 * geometric landmark heuristics.
 */
class HandGestureClassifier {

  private var heldPose: HandPose = HandPose.NONE
  private var holdStartMs: Long = 0L
  private var holdLastSeenMs: Long = 0L
  private var holdStartX: Float = 0f
  private var holdStartY: Float = 0f
  private var awaitingReleaseOf: HandPose = HandPose.NONE

  private var swipeStartMs: Long? = null
  private var swipeStartX: Float = 0f
  private var swipeStartY: Float = 0f
  private var swipeLastX: Float = 0f
  private var swipeLastSeenMs: Long? = null
  private var swipeCooldownUntilMs: Long = 0L

  private var pinchAnchorScale: Float = 0f
  private var pinchLastScaleRatio: Float = 1f
  private var pinchSmoothedScale: Float = 0f
  private var pinchSmoothedZ: Float = 0f
  private var pinchSmoothedX: Float = 0f
  private var pinchSmoothedY: Float = 0f
  private var pinchLastZ: Float = 0f
  private var pinchLastX: Float = 0f
  private var pinchLastY: Float = 0f
  private var pinchLastSeenMs: Long? = null

  private var cooldownUntilMs: Long = 0L

  fun reset() {
    clearHold()
    awaitingReleaseOf = HandPose.NONE
    clearSwipeState()
    clearPinchState()
    swipeCooldownUntilMs = 0L
    cooldownUntilMs = 0L
  }

  /** Back-compat entry point for raw landmarks without classifier output. */
  fun processFrames(
    hands: List<List<HandLandmarkPoint>>,
    timestampMs: Long,
    currentMode: DashboardMode,
    currentPage: DashboardPage = DashboardPage.MAP,
  ): GestureUpdate = process(hands.map { HandObservation(it) }, timestampMs, currentMode, currentPage)

  fun process(
    observations: List<HandObservation>,
    timestampMs: Long,
    currentMode: DashboardMode,
    currentPage: DashboardPage = DashboardPage.MAP,
  ): GestureUpdate {
    val hands = observations.filter { it.landmarks.size >= 21 }
    val resolved = hands.map { it to resolvePose(it) }
    val dominant = resolved.maxByOrNull { (obs, pose) -> if (pose == HandPose.NONE) -1f else obs.poseScore + 1f }
    val dominantPose = dominant?.second ?: HandPose.NONE

    if (awaitingReleaseOf != HandPose.NONE && resolved.none { it.second == awaitingReleaseOf }) {
      awaitingReleaseOf = HandPose.NONE
    }

    if (timestampMs < cooldownUntilMs) {
      return GestureUpdate(activePose = dominantPose)
    }

    val dashboardOpen = currentMode != DashboardMode.CLOSED

    // 1. Map pinch (only on the MAP tile, and only for hands not showing a named pose).
    if (dashboardOpen && currentPage == DashboardPage.MAP) {
      val pinchCandidates = resolved.filter { it.second == HandPose.NONE }.map { it.first.landmarks }
      val pinch = detectMapPinch(pinchCandidates, timestampMs)
      if (pinch.mapPinchActive) {
        clearHold()
        clearSwipeState()
        return pinch.copy(activePose = HandPose.NONE)
      }
    } else {
      clearPinchState()
    }

    // 2. Swipe between tiles with an open hand.
    if (dashboardOpen) {
      val swipeHands =
        resolved
          .filter { (obs, pose) -> pose == HandPose.OPEN_PALM || isSwipeOpenHand(obs.landmarks) }
          .map { it.first.landmarks }
      val direction = detectOpenHandSwipe(swipeHands, timestampMs)
      if (direction != null) {
        clearHold()
        val action = if (direction == SwipeDirection.NEXT) GestureAction.NEXT_TILE else GestureAction.PREVIOUS_TILE
        return GestureUpdate(action = action, activePose = dominantPose)
      }
    } else {
      clearSwipeState()
    }

    // 3. Held poses.
    val action = actionFor(dominantPose, currentMode)
    if (dominant == null || action == null || dominantPose == awaitingReleaseOf) {
      if (timestampMs - holdLastSeenMs > HOLD_GAP_GRACE_MS) clearHold()
      return GestureUpdate(activePose = dominantPose)
    }

    val cx = handCenterX(dominant.first.landmarks)
    val cy = handCenterY(dominant.first.landmarks)
    val sameHold =
      heldPose == dominantPose &&
        timestampMs - holdLastSeenMs <= HOLD_GAP_GRACE_MS &&
        hypot(cx - holdStartX, cy - holdStartY) <= HOLD_MAX_TRAVEL
    if (!sameHold) {
      heldPose = dominantPose
      holdStartMs = timestampMs
      holdStartX = cx
      holdStartY = cy
    }
    holdLastSeenMs = timestampMs

    val needed = holdDurationFor(action)
    val progress = ((timestampMs - holdStartMs).toFloat() / needed).coerceIn(0f, 1f)
    if (progress >= 1f) {
      awaitingReleaseOf = dominantPose
      clearHold()
      cooldownUntilMs = timestampMs + ACTION_COOLDOWN_MS
      return GestureUpdate(action = action, activePose = dominantPose)
    }
    return GestureUpdate(pendingAction = action, holdProgress = progress, activePose = dominantPose)
  }

  private fun clearHold() {
    heldPose = HandPose.NONE
    holdStartMs = 0L
    holdLastSeenMs = 0L
  }

  internal fun resolvePose(obs: HandObservation): HandPose {
    if (obs.pose != HandPose.NONE && obs.poseScore >= MIN_POSE_SCORE) return obs.pose
    val lm = obs.landmarks
    return when {
      isOpenPalmUp(lm) -> HandPose.OPEN_PALM
      isClosedFist(lm) -> HandPose.CLOSED_FIST
      else -> HandPose.NONE
    }
  }

  private fun actionFor(pose: HandPose, mode: DashboardMode): GestureAction? {
    val open = mode != DashboardMode.CLOSED
    return when (pose) {
      HandPose.OPEN_PALM -> if (!open) GestureAction.OPEN_DASHBOARD else null
      HandPose.CLOSED_FIST -> if (open) GestureAction.CLOSE_DASHBOARD else null
      HandPose.POINTING_UP -> if (open) GestureAction.RECENTER else null
      HandPose.THUMB_UP -> GestureAction.ACKNOWLEDGE
      HandPose.VICTORY -> if (open) GestureAction.TOGGLE_EYE_PREVIEW else null
      HandPose.THUMB_DOWN -> GestureAction.CLEAR_DRILL
      HandPose.I_LOVE_YOU,
      HandPose.NONE -> null
    }
  }

  private fun holdDurationFor(action: GestureAction): Float =
    when (action) {
      GestureAction.ACKNOWLEDGE -> 400f
      GestureAction.RECENTER -> 500f
      GestureAction.CLEAR_DRILL -> 900f
      else -> 600f
    }

  private fun detectMapPinch(hands: List<List<HandLandmarkPoint>>, timestampMs: Long): GestureUpdate {
    val pinchHands = hands.filter { it.size >= 21 && isThumbIndexPinch(it) }
    val activePinch =
      when {
        pinchHands.size == 1 -> pinchHands[0]
        pinchHands.size > 1 && pinchLastSeenMs != null -> pinchHands.minByOrNull { abs(pinchPointX(it) - pinchLastX) }
        else -> null
      }

    if (activePinch == null) {
      val lastSeen = pinchLastSeenMs
      if (lastSeen == null || timestampMs - lastSeen > PINCH_GAP_RESET_MS) clearPinchState()
      return GestureUpdate()
    }

    val rawPalmScale = handPalmScale(activePinch)
    val rawX = pinchPointX(activePinch)
    val rawY = pinchPointY(activePinch)
    val rawZ = pinchPointZ(activePinch)

    val lastSeen = pinchLastSeenMs
    if (lastSeen == null || timestampMs - lastSeen > PINCH_GAP_RESET_MS || pinchAnchorScale <= 0f) {
      pinchAnchorScale = rawPalmScale
      pinchSmoothedScale = rawPalmScale
      pinchSmoothedZ = rawZ
      pinchSmoothedX = rawX
      pinchSmoothedY = rawY
      pinchLastScaleRatio = 1f
      pinchLastZ = rawZ
      pinchLastX = rawX
      pinchLastY = rawY
      pinchLastSeenMs = timestampMs
      return GestureUpdate(mapPinchActive = true)
    }

    pinchSmoothedScale += PINCH_FILTER_ALPHA * (rawPalmScale - pinchSmoothedScale)
    pinchSmoothedZ += PINCH_FILTER_ALPHA * (rawZ - pinchSmoothedZ)
    pinchSmoothedX += PINCH_POSITION_ALPHA * (rawX - pinchSmoothedX)
    pinchSmoothedY += PINCH_POSITION_ALPHA * (rawY - pinchSmoothedY)

    val scaleRatio = (pinchSmoothedScale / pinchAnchorScale).coerceIn(0.42f, 2.8f)
    val deltaScale = scaleRatio - pinchLastScaleRatio
    val deltaZ = pinchLastZ - pinchSmoothedZ
    val rawForwardDelta = (deltaScale * 2.15f + deltaZ * 3.1f).coerceIn(-0.55f, 0.55f)
    val forwardDelta = if (abs(rawForwardDelta) < PINCH_FORWARD_DEADZONE) 0f else rawForwardDelta

    val rawPanDx = (pinchSmoothedX - pinchLastX).coerceIn(-0.22f, 0.22f)
    val rawPanDy = (pinchSmoothedY - pinchLastY).coerceIn(-0.22f, 0.22f)
    val panDx = if (abs(rawPanDx) < PINCH_PAN_DEADZONE) 0f else rawPanDx
    val panDy = if (abs(rawPanDy) < PINCH_PAN_DEADZONE) 0f else rawPanDy

    pinchLastScaleRatio = scaleRatio
    pinchLastZ = pinchSmoothedZ
    pinchLastX = pinchSmoothedX
    pinchLastY = pinchSmoothedY
    pinchLastSeenMs = timestampMs

    return GestureUpdate(
      mapPinchActive = true,
      mapPinchForwardDelta = forwardDelta,
      mapPinchPanDeltaX = panDx,
      mapPinchPanDeltaY = panDy,
    )
  }

  private fun clearPinchState() {
    pinchAnchorScale = 0f
    pinchLastScaleRatio = 1f
    pinchSmoothedScale = 0f
    pinchSmoothedZ = 0f
    pinchSmoothedX = 0f
    pinchSmoothedY = 0f
    pinchLastZ = 0f
    pinchLastX = 0f
    pinchLastY = 0f
    pinchLastSeenMs = null
  }

  private fun detectOpenHandSwipe(hands: List<List<HandLandmarkPoint>>, timestampMs: Long): SwipeDirection? {
    if (timestampMs < swipeCooldownUntilMs) {
      clearSwipeState()
      return null
    }

    val activeHand =
      when {
        hands.size == 1 -> hands[0]
        hands.size > 1 && swipeStartMs != null -> hands.minByOrNull { abs(handCenterX(it) - swipeLastX) }
        else -> null
      }

    if (activeHand == null) {
      val lastSeen = swipeLastSeenMs
      if (lastSeen == null || timestampMs - lastSeen > SWIPE_GAP_RESET_MS) clearSwipeState()
      return null
    }

    val palmX = handCenterX(activeHand)
    val palmY = handCenterY(activeHand)
    val startMs = swipeStartMs
    val lastSeen = swipeLastSeenMs

    if (
      startMs == null ||
        lastSeen == null ||
        timestampMs - lastSeen > SWIPE_GAP_RESET_MS ||
        timestampMs - startMs > SWIPE_MAX_WINDOW_MS
    ) {
      swipeStartMs = timestampMs
      swipeStartX = palmX
      swipeStartY = palmY
      swipeLastX = palmX
      swipeLastSeenMs = timestampMs
      return null
    }

    val overallDelta = swipeLastX - swipeStartX
    val stepDelta = palmX - swipeLastX
    if (abs(overallDelta) >= SWIPE_REVERSAL_ARM_X && overallDelta * stepDelta < 0f && abs(stepDelta) >= 0.012f) {
      swipeStartMs = lastSeen
      swipeStartX = swipeLastX
      swipeStartY = palmY
    }

    swipeLastX = palmX
    swipeLastSeenMs = timestampMs

    val deltaX = palmX - swipeStartX
    val deltaY = palmY - swipeStartY
    if (abs(deltaX) >= SWIPE_MIN_DELTA_X && abs(deltaX) >= abs(deltaY) * SWIPE_HORIZONTAL_RATIO) {
      clearSwipeState()
      swipeCooldownUntilMs = timestampMs + SWIPE_COOLDOWN_MS
      return if (deltaX < 0f) SwipeDirection.PREVIOUS else SwipeDirection.NEXT
    }
    return null
  }

  private fun clearSwipeState() {
    swipeStartMs = null
    swipeStartX = 0f
    swipeStartY = 0f
    swipeLastX = 0f
    swipeLastSeenMs = null
  }

  private fun handCenterX(landmarks: List<HandLandmarkPoint>): Float = (landmarks[WRIST].x + landmarks[MIDDLE_MCP].x) * 0.5f

  private fun handCenterY(landmarks: List<HandLandmarkPoint>): Float = (landmarks[WRIST].y + landmarks[MIDDLE_MCP].y) * 0.5f

  private fun pinchPointX(landmarks: List<HandLandmarkPoint>): Float = (landmarks[THUMB_TIP].x + landmarks[INDEX_TIP].x) * 0.5f

  private fun pinchPointY(landmarks: List<HandLandmarkPoint>): Float = (landmarks[THUMB_TIP].y + landmarks[INDEX_TIP].y) * 0.5f

  private fun pinchPointZ(landmarks: List<HandLandmarkPoint>): Float = (landmarks[THUMB_TIP].z + landmarks[INDEX_TIP].z) * 0.5f

  private fun handPalmScale(landmarks: List<HandLandmarkPoint>): Float {
    val palmHeight = dist2D(landmarks[WRIST], landmarks[MIDDLE_MCP])
    val palmWidth = dist2D(landmarks[INDEX_MCP], landmarks[PINKY_MCP])
    return maxOf(palmHeight, palmWidth)
  }

  internal fun isThumbIndexPinch(landmarks: List<HandLandmarkPoint>): Boolean {
    if (landmarks.size < 21) return false
    val palmScale = handPalmScale(landmarks)
    if (palmScale < MIN_PALM_SIZE) return false
    if (isClosedFist(landmarks) || isOpenPalmUp(landmarks)) return false
    val pinchGap = dist2D(landmarks[THUMB_TIP], landmarks[INDEX_TIP])
    if (pinchGap > palmScale * PINCH_MAX_GAP_RATIO) return false
    val wrist = landmarks[WRIST]
    val otherCurledCount =
      listOf(
        isFingerCurledForgiving(wrist, landmarks[MIDDLE_MCP], landmarks[MIDDLE_PIP], landmarks[MIDDLE_TIP], palmScale),
        isFingerCurledForgiving(wrist, landmarks[RING_MCP], landmarks[RING_PIP], landmarks[RING_TIP], palmScale),
        isFingerCurledForgiving(wrist, landmarks[PINKY_MCP], landmarks[PINKY_PIP], landmarks[PINKY_TIP], palmScale),
      ).count { it }
    return otherCurledCount <= 2
  }

  internal fun isSwipeOpenHand(landmarks: List<HandLandmarkPoint>): Boolean {
    if (landmarks.size < 21) return false
    val wrist = landmarks[WRIST]
    if (handPalmScale(landmarks) < MIN_PALM_SIZE) return false
    if (isClosedFist(landmarks)) return false
    val extendedCount =
      listOf(
        isFingerExtendedAnyAngle(wrist, landmarks[INDEX_MCP], landmarks[INDEX_PIP], landmarks[INDEX_TIP]),
        isFingerExtendedAnyAngle(wrist, landmarks[MIDDLE_MCP], landmarks[MIDDLE_PIP], landmarks[MIDDLE_TIP]),
        isFingerExtendedAnyAngle(wrist, landmarks[RING_MCP], landmarks[RING_PIP], landmarks[RING_TIP]),
        isFingerExtendedAnyAngle(wrist, landmarks[PINKY_MCP], landmarks[PINKY_PIP], landmarks[PINKY_TIP]),
      ).count { it }
    return extendedCount >= 3
  }

  private fun isFingerExtendedAnyAngle(
    wrist: HandLandmarkPoint,
    mcp: HandLandmarkPoint,
    pip: HandLandmarkPoint,
    tip: HandLandmarkPoint,
  ): Boolean {
    val wristToTip = dist2D(wrist, tip)
    return wristToTip > dist2D(wrist, mcp) * 1.15f && wristToTip > dist2D(wrist, pip) * 1.02f
  }

  internal fun isOpenPalmUp(landmarks: List<HandLandmarkPoint>): Boolean {
    if (landmarks.size < 21) return false
    val wrist = landmarks[WRIST]
    val middleMcp = landmarks[MIDDLE_MCP]
    val palmSize = dist2D(wrist, middleMcp)
    if (palmSize < MIN_PALM_SIZE) return false
    if (middleMcp.y - wrist.y > -0.28f * palmSize) return false
    val fingersExtended =
      isFingerExtendedUpright(wrist, landmarks[INDEX_MCP], landmarks[INDEX_PIP], landmarks[INDEX_TIP]) &&
        isFingerExtendedUpright(wrist, landmarks[MIDDLE_MCP], landmarks[MIDDLE_PIP], landmarks[MIDDLE_TIP]) &&
        isFingerExtendedUpright(wrist, landmarks[RING_MCP], landmarks[RING_PIP], landmarks[RING_TIP]) &&
        isFingerExtendedUpright(wrist, landmarks[PINKY_MCP], landmarks[PINKY_PIP], landmarks[PINKY_TIP])
    if (!fingersExtended) return false
    val thumbToPinkyMcp = dist2D(landmarks[THUMB_TIP], landmarks[PINKY_MCP])
    val thumbToIndexMcp = dist2D(landmarks[THUMB_TIP], landmarks[INDEX_MCP])
    return thumbToPinkyMcp > palmSize * 0.85f && thumbToIndexMcp > palmSize * 0.40f
  }

  internal fun isClosedFist(landmarks: List<HandLandmarkPoint>): Boolean {
    if (landmarks.size < 21) return false
    val wrist = landmarks[WRIST]
    val palmScale = handPalmScale(landmarks)
    if (palmScale < MIN_PALM_SIZE) return false
    if (isOpenPalmUp(landmarks)) return false
    val curledFingers =
      listOf(
        isFingerCurledForgiving(wrist, landmarks[INDEX_MCP], landmarks[INDEX_PIP], landmarks[INDEX_TIP], palmScale),
        isFingerCurledForgiving(wrist, landmarks[MIDDLE_MCP], landmarks[MIDDLE_PIP], landmarks[MIDDLE_TIP], palmScale),
        isFingerCurledForgiving(wrist, landmarks[RING_MCP], landmarks[RING_PIP], landmarks[RING_TIP], palmScale),
        isFingerCurledForgiving(wrist, landmarks[PINKY_MCP], landmarks[PINKY_PIP], landmarks[PINKY_TIP], palmScale),
      ).count { it }
    return curledFingers >= 3
  }

  private fun isFingerExtendedUpright(
    wrist: HandLandmarkPoint,
    mcp: HandLandmarkPoint,
    pip: HandLandmarkPoint,
    tip: HandLandmarkPoint,
  ): Boolean {
    val wristToTip = dist2D(wrist, tip)
    return wristToTip > dist2D(wrist, pip) * 1.10f && wristToTip > dist2D(wrist, mcp) * 1.28f && tip.y < mcp.y
  }

  private fun isFingerCurledForgiving(
    wrist: HandLandmarkPoint,
    mcp: HandLandmarkPoint,
    pip: HandLandmarkPoint,
    tip: HandLandmarkPoint,
    palmScale: Float,
  ): Boolean {
    val wristToTip = dist2D(wrist, tip)
    return (wristToTip <= dist2D(wrist, pip) * 1.22f || dist2D(mcp, tip) <= palmScale * 1.15f) &&
      wristToTip < palmScale * 1.85f
  }

  private fun dist2D(a: HandLandmarkPoint, b: HandLandmarkPoint): Float = hypot(a.x - b.x, a.y - b.y)

  companion object {
    const val MIN_POSE_SCORE = 0.55f
    const val HOLD_GAP_GRACE_MS = 350L
    const val HOLD_MAX_TRAVEL = 0.09f
    const val ACTION_COOLDOWN_MS = 650L

    const val PINCH_MAX_GAP_RATIO = 0.45f
    const val PINCH_GAP_RESET_MS = 260L
    private const val PINCH_FILTER_ALPHA = 0.55f
    private const val PINCH_POSITION_ALPHA = 0.65f
    private const val PINCH_FORWARD_DEADZONE = 0.004f
    private const val PINCH_PAN_DEADZONE = 0.002f

    const val SWIPE_MIN_DELTA_X = 0.11f
    const val SWIPE_MAX_WINDOW_MS = 750L
    const val SWIPE_GAP_RESET_MS = 280L
    const val SWIPE_COOLDOWN_MS = 650L
    private const val SWIPE_REVERSAL_ARM_X = 0.04f
    private const val SWIPE_HORIZONTAL_RATIO = 1.15f

    private const val MIN_PALM_SIZE = 0.030f

    private const val WRIST = 0
    private const val THUMB_TIP = 4
    private const val INDEX_MCP = 5
    private const val INDEX_PIP = 6
    private const val INDEX_TIP = 8
    private const val MIDDLE_MCP = 9
    private const val MIDDLE_PIP = 10
    private const val MIDDLE_TIP = 12
    private const val RING_MCP = 13
    private const val RING_PIP = 14
    private const val RING_TIP = 16
    private const val PINKY_MCP = 17
    private const val PINKY_PIP = 18
    private const val PINKY_TIP = 20
  }
}
