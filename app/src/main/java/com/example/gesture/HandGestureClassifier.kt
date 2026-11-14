package com.example.gesture

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

enum class DashboardMode {
  CLOSED,
  QUICK_HOLD,
  QUICK_VOICE,
  PERMANENT,
}

enum class DashboardPage {
  VITALS,
  MAP,
  SUIT_STATUS,
  COGNITIVE,
}

enum class SwipeDirection {
  PREVIOUS,
  NEXT,
}

data class HandLandmarkPoint(val x: Float, val y: Float, val z: Float = 0f)

data class GestureUpdate(
  val palmHoldProgress: Float = 0f,
  val isTwoFistsReady: Boolean = false,
  val openMode: DashboardMode? = null,
  val shouldCloseDashboard: Boolean = false,
  val swipeDirection: SwipeDirection? = null,
  val mapPinchActive: Boolean = false,
  val mapPinchForwardDelta: Float = 0f,
  val mapPinchPanDeltaX: Float = 0f,
  val mapPinchPanDeltaY: Float = 0f,
)

class HandGestureClassifier {

  private var onePalmHoldStartMs: Long? = null
  private var lastOnePalmSeenMs: Long? = null

  private var twoPalmHoldStartMs: Long? = null
  private var lastTwoPalmsSeenMs: Long? = null

  private var twoFistsArmedAtMs: Long? = null
  private var lastTwoFistsSeenMs: Long? = null
  private var minLeftFistY: Float = 1f
  private var minRightFistY: Float = 1f
  private var lastLeftFistX: Float = 0.3f
  private var lastRightFistX: Float = 0.7f
  private var lastLeftFistY: Float = 0f
  private var lastRightFistY: Float = 0f

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
    onePalmHoldStartMs = null
    lastOnePalmSeenMs = null
    twoPalmHoldStartMs = null
    lastTwoPalmsSeenMs = null
    clearTwoFistsState()
    clearSwipeState()
    clearPinchState()
    swipeCooldownUntilMs = 0L
    cooldownUntilMs = 0L
  }

  fun processFrames(
    hands: List<List<HandLandmarkPoint>>,
    timestampMs: Long,
    currentMode: DashboardMode,
    currentPage: DashboardPage = DashboardPage.MAP,
  ): GestureUpdate {
    if (timestampMs < cooldownUntilMs) {
      return GestureUpdate()
    }

    val openPalms = hands.filter { it.size >= 21 && isOpenPalmUp(it) }
    val openPalmsCount = openPalms.size
    val closedFists = hands.filter { it.size >= 21 && isClosedFist(it) }

    when (currentMode) {
      DashboardMode.CLOSED -> {
        clearTwoFistsState()
        clearSwipeState()
        clearPinchState()
        return handleClosedState(openPalmsCount, timestampMs)
      }
      DashboardMode.QUICK_HOLD -> {
        clearTwoFistsState()
        clearSwipeState()
        val twoPalmUpdate = updateTwoPalmHold(openPalmsCount, timestampMs)
        if (twoPalmUpdate.openMode == DashboardMode.PERMANENT) {
          onePalmHoldStartMs = null
          lastOnePalmSeenMs = null
          clearPinchState()
          return twoPalmUpdate
        }

        val pinchUpdate =
          if (currentPage == DashboardPage.MAP) {
            detectMapPinch(hands, timestampMs)
          } else {
            clearPinchState()
            GestureUpdate()
          }

        val handRaised =
          openPalmsCount >= 1 ||
            pinchUpdate.mapPinchActive ||
            hands.any { it.size >= 21 && isSwipeOpenHand(it) }

        return if (handRaised) {
          lastOnePalmSeenMs = timestampMs
          pinchUpdate.copy(palmHoldProgress = twoPalmUpdate.palmHoldProgress)
        } else {
          clearPinchState()
          val lastSeen = lastOnePalmSeenMs
          if (lastSeen == null || timestampMs - lastSeen > PALM_RELEASE_GRACE_MS) {
            onePalmHoldStartMs = null
            lastOnePalmSeenMs = null
            twoPalmHoldStartMs = null
            lastTwoPalmsSeenMs = null
            GestureUpdate(shouldCloseDashboard = true)
          } else {
            GestureUpdate(palmHoldProgress = twoPalmUpdate.palmHoldProgress)
          }
        }
      }
      DashboardMode.QUICK_VOICE -> {
        clearTwoFistsState()
        clearSwipeState()
        onePalmHoldStartMs = null
        lastOnePalmSeenMs = null
        val twoPalmUpdate = updateTwoPalmHold(openPalmsCount, timestampMs)
        if (twoPalmUpdate.openMode == DashboardMode.PERMANENT) {
          clearPinchState()
          return twoPalmUpdate
        }
        val pinchUpdate =
          if (currentPage == DashboardPage.MAP) {
            detectMapPinch(hands, timestampMs)
          } else {
            clearPinchState()
            GestureUpdate()
          }
        return pinchUpdate.copy(palmHoldProgress = twoPalmUpdate.palmHoldProgress)
      }
      DashboardMode.PERMANENT -> {
        onePalmHoldStartMs = null
        lastOnePalmSeenMs = null
        twoPalmHoldStartMs = null
        lastTwoPalmsSeenMs = null
        val closeUpdate = handlePermanentCloseCheck(hands, closedFists, timestampMs)
        if (closeUpdate.shouldCloseDashboard || closeUpdate.isTwoFistsReady) {
          clearSwipeState()
          clearPinchState()
          return closeUpdate
        }
        if (currentPage == DashboardPage.MAP) {
          val pinchUpdate = detectMapPinch(hands, timestampMs)
          if (pinchUpdate.mapPinchActive) {
            clearSwipeState()
            return pinchUpdate
          }
        } else {
          clearPinchState()
        }
        val swipeDirection = detectOpenHandSwipe(hands, timestampMs)
        return if (swipeDirection != null) {
          closeUpdate.copy(swipeDirection = swipeDirection)
        } else {
          closeUpdate
        }
      }
    }
  }

  private fun handleClosedState(
    openPalmsCount: Int,
    timestampMs: Long,
  ): GestureUpdate {
    val twoPalmUpdate = updateTwoPalmHold(openPalmsCount, timestampMs)
    if (twoPalmUpdate.openMode == DashboardMode.PERMANENT) {
      onePalmHoldStartMs = null
      lastOnePalmSeenMs = null
      return twoPalmUpdate
    }

    if (twoPalmHoldStartMs != null) {
      onePalmHoldStartMs = null
      return twoPalmUpdate
    }

    if (openPalmsCount == 1) {
      if (onePalmHoldStartMs == null) {
        onePalmHoldStartMs = timestampMs
      }
      lastOnePalmSeenMs = timestampMs
      val elapsed = (timestampMs - (onePalmHoldStartMs ?: timestampMs)).coerceAtLeast(0L)
      val progress = (elapsed.toFloat() / PALM_HOLD_DURATION_MS.toFloat()).coerceIn(0f, 1f)
      if (elapsed >= PALM_HOLD_DURATION_MS) {
        onePalmHoldStartMs = null
        return GestureUpdate(
          palmHoldProgress = 0f,
          openMode = DashboardMode.QUICK_HOLD,
        )
      }
      return GestureUpdate(palmHoldProgress = progress)
    } else {
      val lastSeen = lastOnePalmSeenMs
      if (lastSeen == null || timestampMs - lastSeen > PALM_RELEASE_GRACE_MS) {
        onePalmHoldStartMs = null
        lastOnePalmSeenMs = null
        return GestureUpdate(palmHoldProgress = 0f)
      }
      val start = onePalmHoldStartMs ?: return GestureUpdate(palmHoldProgress = 0f)
      val progress =
        ((timestampMs - start).coerceAtLeast(0L).toFloat() / PALM_HOLD_DURATION_MS.toFloat())
          .coerceIn(0f, 1f)
      return GestureUpdate(palmHoldProgress = progress)
    }
  }

  private fun detectMapPinch(
    hands: List<List<HandLandmarkPoint>>,
    timestampMs: Long,
  ): GestureUpdate {
    val pinchHands = hands.filter { it.size >= 21 && isThumbIndexPinch(it) }
    val activePinch =
      when {
        pinchHands.size == 1 -> pinchHands[0]
        pinchHands.size > 1 && pinchLastSeenMs != null ->
          pinchHands.minByOrNull { abs(pinchPointX(it) - pinchLastX) }
        else -> null
      }

    if (activePinch == null) {
      val lastSeen = pinchLastSeenMs
      if (lastSeen == null || timestampMs - lastSeen > PINCH_GAP_RESET_MS) {
        clearPinchState()
      }
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

  private fun updateTwoPalmHold(openPalmsCount: Int, timestampMs: Long): GestureUpdate {
    if (openPalmsCount >= 2) {
      if (twoPalmHoldStartMs == null) {
        twoPalmHoldStartMs = timestampMs
      }
      lastTwoPalmsSeenMs = timestampMs
      val elapsed = (timestampMs - (twoPalmHoldStartMs ?: timestampMs)).coerceAtLeast(0L)
      val progress = (elapsed.toFloat() / PALM_HOLD_DURATION_MS.toFloat()).coerceIn(0f, 1f)
      if (elapsed >= PALM_HOLD_DURATION_MS) {
        twoPalmHoldStartMs = null
        lastTwoPalmsSeenMs = null
        cooldownUntilMs = timestampMs + TRANSITION_COOLDOWN_MS
        return GestureUpdate(palmHoldProgress = 0f, openMode = DashboardMode.PERMANENT)
      }
      return GestureUpdate(palmHoldProgress = progress)
    } else {
      val lastSeen = lastTwoPalmsSeenMs
      if (lastSeen == null || timestampMs - lastSeen > PALM_RELEASE_GRACE_MS) {
        twoPalmHoldStartMs = null
        lastTwoPalmsSeenMs = null
        return GestureUpdate(palmHoldProgress = 0f)
      }
      val start = twoPalmHoldStartMs ?: return GestureUpdate(palmHoldProgress = 0f)
      val progress =
        ((timestampMs - start).coerceAtLeast(0L).toFloat() / PALM_HOLD_DURATION_MS.toFloat())
          .coerceIn(0f, 1f)
      return GestureUpdate(palmHoldProgress = progress)
    }
  }

  private fun handlePermanentCloseCheck(
    allHands: List<List<HandLandmarkPoint>>,
    closedFists: List<List<HandLandmarkPoint>>,
    timestampMs: Long,
  ): GestureUpdate {
    if (closedFists.size >= 2) {
      val sorted = closedFists.sortedBy { it[WRIST].x }
      val leftWrist = sorted.first()[WRIST]
      val rightWrist = sorted.last()[WRIST]

      if (twoFistsArmedAtMs == null) {
        twoFistsArmedAtMs = timestampMs
        minLeftFistY = leftWrist.y
        minRightFistY = rightWrist.y
      } else {
        minLeftFistY = min(minLeftFistY, leftWrist.y)
        minRightFistY = min(minRightFistY, rightWrist.y)
      }
      lastTwoFistsSeenMs = timestampMs
      lastLeftFistX = leftWrist.x
      lastLeftFistY = leftWrist.y
      lastRightFistX = rightWrist.x
      lastRightFistY = rightWrist.y

      if (checkTwoFistsCloseReady(timestampMs)) {
        clearTwoFistsState()
        cooldownUntilMs = timestampMs + TRANSITION_COOLDOWN_MS
        return GestureUpdate(isTwoFistsReady = false, shouldCloseDashboard = true)
      }
      return GestureUpdate(isTwoFistsReady = true)
    }

    val lastSeen = lastTwoFistsSeenMs
    if (twoFistsArmedAtMs != null && lastSeen != null && timestampMs - lastSeen <= FIST_DROP_GRACE_MS) {
      val candidates =
        if (closedFists.isNotEmpty()) {
          closedFists
        } else {
          allHands.filter { it.size >= 21 && !isOpenPalmUp(it) }
        }

      for (hand in candidates) {
        val wrist = hand[WRIST]
        val distToLeft = abs(wrist.x - lastLeftFistX)
        val distToRight = abs(wrist.x - lastRightFistX)
        if (distToLeft <= distToRight) {
          lastLeftFistX = wrist.x
          lastLeftFistY = wrist.y
          minLeftFistY = min(minLeftFistY, wrist.y)
        } else {
          lastRightFistX = wrist.x
          lastRightFistY = wrist.y
          minRightFistY = min(minRightFistY, wrist.y)
        }
      }

      if (checkTwoFistsCloseReady(timestampMs, allowBottomExit = true)) {
        clearTwoFistsState()
        cooldownUntilMs = timestampMs + TRANSITION_COOLDOWN_MS
        return GestureUpdate(isTwoFistsReady = false, shouldCloseDashboard = true)
      }

      return GestureUpdate(isTwoFistsReady = true)
    }

    clearTwoFistsState()
    return GestureUpdate(isTwoFistsReady = false)
  }

  private fun checkTwoFistsCloseReady(
    timestampMs: Long,
    allowBottomExit: Boolean = false,
  ): Boolean {
    val armedAt = twoFistsArmedAtMs ?: return false
    if (timestampMs - armedAt < MIN_FISTS_ARMED_MS) return false

    val leftDrop = lastLeftFistY - minLeftFistY
    val rightDrop = lastRightFistY - minRightFistY
    if (leftDrop >= FIST_LOWER_THRESHOLD_Y && rightDrop >= FIST_LOWER_THRESHOLD_Y) {
      return true
    }

    if (allowBottomExit) {
      val exitedBottomTogether =
        leftDrop >= FIST_EXIT_MIN_DROP_Y &&
          rightDrop >= FIST_EXIT_MIN_DROP_Y &&
          lastLeftFistY >= FIST_BOTTOM_EXIT_Y &&
          lastRightFistY >= FIST_BOTTOM_EXIT_Y
      if (exitedBottomTogether) return true
    }

    return false
  }

  private fun clearTwoFistsState() {
    twoFistsArmedAtMs = null
    lastTwoFistsSeenMs = null
    minLeftFistY = 1f
    minRightFistY = 1f
    lastLeftFistX = 0.3f
    lastRightFistX = 0.7f
    lastLeftFistY = 0f
    lastRightFistY = 0f
  }

  private fun detectOpenHandSwipe(
    hands: List<List<HandLandmarkPoint>>,
    timestampMs: Long,
  ): SwipeDirection? {
    if (timestampMs < swipeCooldownUntilMs) {
      clearSwipeState()
      return null
    }

    val openHands = hands.filter { it.size >= 21 && isSwipeOpenHand(it) }
    val activeHand =
      when {
        openHands.size == 1 -> openHands[0]
        openHands.size > 1 && swipeStartMs != null ->
          openHands.minByOrNull { abs(handCenterX(it) - swipeLastX) }
        else -> null
      }

    if (activeHand == null) {
      val lastSeen = swipeLastSeenMs
      if (lastSeen == null || timestampMs - lastSeen > SWIPE_GAP_RESET_MS) {
        clearSwipeState()
      }
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
    if (
      abs(overallDelta) >= SWIPE_REVERSAL_ARM_X &&
        overallDelta * stepDelta < 0f &&
        abs(stepDelta) >= 0.012f
    ) {
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

  private fun handCenterX(landmarks: List<HandLandmarkPoint>): Float {
    return (landmarks[WRIST].x + landmarks[MIDDLE_MCP].x) * 0.5f
  }

  private fun handCenterY(landmarks: List<HandLandmarkPoint>): Float {
    return (landmarks[WRIST].y + landmarks[MIDDLE_MCP].y) * 0.5f
  }

  private fun pinchPointX(landmarks: List<HandLandmarkPoint>): Float {
    return (landmarks[THUMB_TIP].x + landmarks[INDEX_TIP].x) * 0.5f
  }

  private fun pinchPointY(landmarks: List<HandLandmarkPoint>): Float {
    return (landmarks[THUMB_TIP].y + landmarks[INDEX_TIP].y) * 0.5f
  }

  private fun pinchPointZ(landmarks: List<HandLandmarkPoint>): Float {
    return (landmarks[THUMB_TIP].z + landmarks[INDEX_TIP].z) * 0.5f
  }

  private fun handPalmScale(landmarks: List<HandLandmarkPoint>): Float {
    val wrist = landmarks[WRIST]
    val palmHeight = dist2D(wrist, landmarks[MIDDLE_MCP])
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
    val palmScale = handPalmScale(landmarks)
    if (palmScale < MIN_PALM_SIZE) return false
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
    val wristToPip = dist2D(wrist, pip)
    val wristToMcp = dist2D(wrist, mcp)
    return wristToTip > wristToMcp * 1.15f && wristToTip > wristToPip * 1.02f
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
    val wristToPip = dist2D(wrist, pip)
    val wristToMcp = dist2D(wrist, mcp)
    return wristToTip > wristToPip * 1.10f && wristToTip > wristToMcp * 1.28f && tip.y < mcp.y
  }

  private fun isFingerCurledForgiving(
    wrist: HandLandmarkPoint,
    mcp: HandLandmarkPoint,
    pip: HandLandmarkPoint,
    tip: HandLandmarkPoint,
    palmScale: Float,
  ): Boolean {
    val wristToTip = dist2D(wrist, tip)
    val wristToPip = dist2D(wrist, pip)
    val mcpToTip = dist2D(mcp, tip)
    return (wristToTip <= wristToPip * 1.22f || mcpToTip <= palmScale * 1.15f) &&
      wristToTip < palmScale * 1.85f
  }

  private fun dist2D(a: HandLandmarkPoint, b: HandLandmarkPoint): Float {
    return hypot(a.x - b.x, a.y - b.y)
  }

  companion object {
    const val PALM_HOLD_DURATION_MS = 800L
    const val PALM_RELEASE_GRACE_MS = 150L
    const val FIST_DROP_GRACE_MS = 650L
    const val MIN_FISTS_ARMED_MS = 80L
    const val TRANSITION_COOLDOWN_MS = 600L

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
    private const val FIST_LOWER_THRESHOLD_Y = 0.08f
    private const val FIST_EXIT_MIN_DROP_Y = 0.05f
    private const val FIST_BOTTOM_EXIT_Y = 0.62f

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
