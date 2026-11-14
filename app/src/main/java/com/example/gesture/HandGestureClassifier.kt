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

data class HandLandmarkPoint(val x: Float, val y: Float, val z: Float = 0f)

data class GestureUpdate(
  val palmHoldProgress: Float = 0f,
  val isTwoFistsReady: Boolean = false,
  val openMode: DashboardMode? = null,
  val shouldCloseDashboard: Boolean = false,
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

  private var cooldownUntilMs: Long = 0L

  fun reset() {
    onePalmHoldStartMs = null
    lastOnePalmSeenMs = null
    twoPalmHoldStartMs = null
    lastTwoPalmsSeenMs = null
    clearTwoFistsState()
    cooldownUntilMs = 0L
  }

  fun processFrames(
    hands: List<List<HandLandmarkPoint>>,
    timestampMs: Long,
    currentMode: DashboardMode,
  ): GestureUpdate {
    if (timestampMs < cooldownUntilMs) {
      return GestureUpdate()
    }

    val openPalmsCount = hands.count { it.size >= 21 && isOpenPalmUp(it) }
    val closedFists = hands.filter { it.size >= 21 && isClosedFist(it) }

    when (currentMode) {
      DashboardMode.CLOSED -> {
        clearTwoFistsState()
        return handleClosedState(openPalmsCount, timestampMs)
      }
      DashboardMode.QUICK_HOLD -> {
        clearTwoFistsState()
        // Allow upgrading seamlessly from 1-palm quick hold to 2-palm permanent lock.
        val twoPalmUpdate = updateTwoPalmHold(openPalmsCount, timestampMs)
        if (twoPalmUpdate.openMode == DashboardMode.PERMANENT) {
          onePalmHoldStartMs = null
          lastOnePalmSeenMs = null
          return twoPalmUpdate
        }

        if (openPalmsCount >= 1) {
          lastOnePalmSeenMs = timestampMs
          return GestureUpdate(palmHoldProgress = twoPalmUpdate.palmHoldProgress)
        } else {
          val lastSeen = lastOnePalmSeenMs
          if (lastSeen == null || timestampMs - lastSeen > PALM_RELEASE_GRACE_MS) {
            onePalmHoldStartMs = null
            lastOnePalmSeenMs = null
            twoPalmHoldStartMs = null
            lastTwoPalmsSeenMs = null
            return GestureUpdate(shouldCloseDashboard = true)
          }
          return GestureUpdate(palmHoldProgress = twoPalmUpdate.palmHoldProgress)
        }
      }
      DashboardMode.QUICK_VOICE -> {
        clearTwoFistsState()
        onePalmHoldStartMs = null
        lastOnePalmSeenMs = null
        // Two palms held 0.8s can also upgrade a voice-quick dashboard to permanent.
        return updateTwoPalmHold(openPalmsCount, timestampMs)
      }
      DashboardMode.PERMANENT -> {
        onePalmHoldStartMs = null
        lastOnePalmSeenMs = null
        twoPalmHoldStartMs = null
        lastTwoPalmsSeenMs = null
        return handlePermanentCloseCheck(hands, closedFists, timestampMs)
      }
    }
  }

  private fun handleClosedState(openPalmsCount: Int, timestampMs: Long): GestureUpdate {
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
        return GestureUpdate(palmHoldProgress = 0f, openMode = DashboardMode.QUICK_HOLD)
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
      // Continue tracking each fist independently if one hand exits or loosens slightly mid-descent.
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
    val middleMcp = landmarks[MIDDLE_MCP]
    val palmHeight = dist2D(wrist, middleMcp)
    val palmWidth = dist2D(landmarks[INDEX_MCP], landmarks[PINKY_MCP])
    val palmScale = maxOf(palmHeight, palmWidth)
    if (palmScale < MIN_PALM_SIZE) return false

    // Prevent classifying a clear upright open palm as a fist.
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
