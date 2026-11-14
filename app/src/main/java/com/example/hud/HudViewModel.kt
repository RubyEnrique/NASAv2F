package com.example.hud

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.gesture.DashboardMode
import com.example.gesture.HandGestureClassifier
import com.example.gesture.HandLandmarkPoint
import com.example.voice.VoiceCommand
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HudUiState(
  val hasCameraPermission: Boolean = false,
  val headingDegrees: Float = 0f,
  val batteryPercent: Int = 100,
  val distanceMeters: Float = 0f,
  val speedMps: Float = 0f,
  val distanceGoalMeters: Float = DEFAULT_DISTANCE_GOAL_METERS,
  val heartRateBpm: Int = BASE_HR_BPM.roundToInt(),
  val palmHoldProgress: Float = 0f,
  val isTwoFistsReady: Boolean = false,
  val dashboardMode: DashboardMode = DashboardMode.CLOSED,
) {
  val isDashboardOpen: Boolean
    get() = dashboardMode != DashboardMode.CLOSED

  companion object {
    const val DEFAULT_DISTANCE_GOAL_METERS = 5000f
    const val BASE_HR_BPM = 86f
  }
}

sealed interface HudEvent {
  data object ExitApp : HudEvent
}

class HudViewModel(
  private val classifier: HandGestureClassifier = HandGestureClassifier(),
  enablePeriodicDecay: Boolean = true,
) : ViewModel() {

  private val _uiState = MutableStateFlow(HudUiState())
  val uiState: StateFlow<HudUiState> = _uiState.asStateFlow()

  private val _events = MutableSharedFlow<HudEvent>(extraBufferCapacity = 4)
  val events: SharedFlow<HudEvent> = _events.asSharedFlow()

  private val gestureLock = Any()
  private val locationLock = Any()
  private val exitTapTimestampsMs = ArrayDeque<Long>()

  private var lastLocationSampleMs = 0L
  private var totalDistanceMeters = 0f
  private var smoothedSpeedMps = 0f
  private var averagedHrBpm = HudUiState.BASE_HR_BPM

  init {
    if (enablePeriodicDecay) {
      viewModelScope.launch {
        while (true) {
          delay(1500L)
          refreshLocationDerivedMetrics(SystemClock.uptimeMillis())
        }
      }
    }
  }

  fun onPermissionResult(granted: Boolean) {
    if (!granted) {
      synchronized(gestureLock) { classifier.reset() }
    }
    _uiState.update { state ->
      state.copy(
        hasCameraPermission = granted,
        palmHoldProgress = if (granted) state.palmHoldProgress else 0f,
        isTwoFistsReady = if (granted) state.isTwoFistsReady else false,
        dashboardMode = if (granted) state.dashboardMode else DashboardMode.CLOSED,
      )
    }
  }

  fun onHeadingChanged(degrees: Float) {
    _uiState.update { state ->
      if (state.headingDegrees == degrees) state else state.copy(headingDegrees = degrees)
    }
  }

  fun onBatteryPercentChanged(percent: Int) {
    val clamped = percent.coerceIn(0, 100)
    _uiState.update { state ->
      if (state.batteryPercent == clamped) state else state.copy(batteryPercent = clamped)
    }
  }

  fun onLocationSample(
    deltaMeters: Float,
    measuredSpeedMps: Float,
    timestampMs: Long = SystemClock.uptimeMillis(),
  ) {
    val (nextDist, nextSpeed, nextHr) =
      synchronized(locationLock) {
        if (deltaMeters > 0f) {
          totalDistanceMeters += deltaMeters
        }
        lastLocationSampleMs = timestampMs
        val clampedSpeed = measuredSpeedMps.coerceIn(0f, MAX_SPEED_MPS)
        smoothedSpeedMps += SPEED_SMOOTH_ALPHA * (clampedSpeed - smoothedSpeedMps)
        if (clampedSpeed == 0f && smoothedSpeedMps < 0.05f) {
          smoothedSpeedMps = 0f
        }

        val targetHr = computeTargetHeartRate(smoothedSpeedMps)
        averagedHrBpm += HR_SMOOTH_ALPHA * (targetHr - averagedHrBpm)

        Triple(totalDistanceMeters, smoothedSpeedMps, averagedHrBpm.roundToInt())
      }

    _uiState.update { state ->
      state.copy(
        distanceMeters = nextDist,
        speedMps = nextSpeed,
        heartRateBpm = nextHr,
      )
    }
  }

  fun refreshLocationDerivedMetrics(nowMs: Long = SystemClock.uptimeMillis()) {
    val (nextSpeed, nextHr) =
      synchronized(locationLock) {
        val stale = lastLocationSampleMs == 0L || nowMs - lastLocationSampleMs > SPEED_STALE_MS
        if (stale) {
          smoothedSpeedMps += SPEED_SMOOTH_ALPHA * (0f - smoothedSpeedMps)
          if (smoothedSpeedMps < 0.05f) {
            smoothedSpeedMps = 0f
          }
        }
        val targetHr = computeTargetHeartRate(smoothedSpeedMps)
        averagedHrBpm += HR_SMOOTH_ALPHA * (targetHr - averagedHrBpm)
        smoothedSpeedMps to averagedHrBpm.roundToInt()
      }

    _uiState.update { state ->
      if (state.speedMps == nextSpeed && state.heartRateBpm == nextHr) {
        state
      } else {
        state.copy(speedMps = nextSpeed, heartRateBpm = nextHr)
      }
    }
  }

  fun onHandsDetected(hands: List<List<HandLandmarkPoint>>, timestampMs: Long) {
    synchronized(gestureLock) {
      val currentMode = _uiState.value.dashboardMode
      val update = classifier.processFrames(hands, timestampMs, currentMode)
      when {
        update.openMode != null -> {
          _uiState.update {
            it.copy(
              dashboardMode = update.openMode,
              palmHoldProgress = 0f,
              isTwoFistsReady = false,
            )
          }
        }
        update.shouldCloseDashboard && currentMode != DashboardMode.CLOSED -> {
          _uiState.update {
            it.copy(
              dashboardMode = DashboardMode.CLOSED,
              palmHoldProgress = 0f,
              isTwoFistsReady = false,
            )
          }
        }
        else -> {
          val targetProgress =
            if (currentMode == DashboardMode.PERMANENT) 0f else update.palmHoldProgress
          val targetFists =
            if (currentMode == DashboardMode.PERMANENT) update.isTwoFistsReady else false
          if (
            _uiState.value.palmHoldProgress != targetProgress ||
              _uiState.value.isTwoFistsReady != targetFists
          ) {
            _uiState.update {
              it.copy(palmHoldProgress = targetProgress, isTwoFistsReady = targetFists)
            }
          }
        }
      }
    }
  }

  fun onVoiceCommand(command: VoiceCommand) {
    synchronized(gestureLock) {
      val currentMode = _uiState.value.dashboardMode
      when (command) {
        VoiceCommand.TOGGLE_QUICK -> {
          classifier.reset()
          val nextMode =
            when (currentMode) {
              DashboardMode.CLOSED -> DashboardMode.QUICK_VOICE
              DashboardMode.QUICK_VOICE,
              DashboardMode.QUICK_HOLD -> DashboardMode.CLOSED
              DashboardMode.PERMANENT -> DashboardMode.PERMANENT
            }
          _uiState.update {
            it.copy(dashboardMode = nextMode, palmHoldProgress = 0f, isTwoFistsReady = false)
          }
        }
        VoiceCommand.OPEN_PERMANENT -> {
          classifier.reset()
          _uiState.update {
            it.copy(
              dashboardMode = DashboardMode.PERMANENT,
              palmHoldProgress = 0f,
              isTwoFistsReady = false,
            )
          }
        }
        VoiceCommand.CLOSE_PERMANENT -> {
          classifier.reset()
          _uiState.update {
            it.copy(
              dashboardMode = DashboardMode.CLOSED,
              palmHoldProgress = 0f,
              isTwoFistsReady = false,
            )
          }
        }
      }
    }
  }

  fun onInvisibleExitZoneTap(nowMs: Long = SystemClock.uptimeMillis()) {
    synchronized(exitTapTimestampsMs) {
      while (
        exitTapTimestampsMs.isNotEmpty() &&
          nowMs - exitTapTimestampsMs.first() > EXIT_TAP_WINDOW_MS
      ) {
        exitTapTimestampsMs.removeFirst()
      }
      exitTapTimestampsMs.addLast(nowMs)
      if (exitTapTimestampsMs.size >= REQUIRED_EXIT_TAPS) {
        exitTapTimestampsMs.clear()
        _events.tryEmit(HudEvent.ExitApp)
      }
    }
  }

  companion object {
    const val REQUIRED_EXIT_TAPS = 5
    const val EXIT_TAP_WINDOW_MS = 2000L

    private const val SPEED_STALE_MS = 4_500L
    private const val SPEED_SMOOTH_ALPHA = 0.45f
    private const val HR_SMOOTH_ALPHA = 0.30f
    private const val HR_SPEED_MULTIPLIER = 14f
    private const val MAX_SPEED_MPS = 35f
    private const val MAX_HR_BPM = 165f

    internal fun computeTargetHeartRate(speedMps: Float): Float {
      return (HudUiState.BASE_HR_BPM + speedMps.coerceAtLeast(0f) * HR_SPEED_MULTIPLIER)
        .coerceIn(HudUiState.BASE_HR_BPM, MAX_HR_BPM)
    }
  }
}
