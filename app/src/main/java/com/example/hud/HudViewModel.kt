package com.example.hud

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.gesture.DashboardMode
import com.example.gesture.DashboardPage
import com.example.gesture.HandGestureClassifier
import com.example.gesture.HandLandmarkPoint
import com.example.gesture.SwipeDirection
import com.example.mental.EmergencyDrill
import com.example.mental.MentalBand
import com.example.mental.MentalScenario
import com.example.mental.MentalState
import com.example.mental.MentalVitals
import com.example.mental.SleepTrackerState
import com.example.mental.StressLevel
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
  val hasLocationPermission: Boolean = false,
  val hasGpsFix: Boolean = false,
  val headingDegrees: Float = 0f,
  val batteryPercent: Int = 100,
  val distanceMeters: Float = 0f,
  val speedMps: Float = 0f,
  val distanceGoalMeters: Float = DEFAULT_DISTANCE_GOAL_METERS,
  // Crew Vitals (Lower-Left + Inferred Vitals Page)
  val heartRateBpm: Int = BASE_HR_BPM.roundToInt(),
  val systolicBp: Int = BASE_SYSTOLIC_BP,
  val diastolicBp: Int = BASE_DIASTOLIC_BP,
  val spo2Percent: Int = 98,
  val coreTempCelsius: Float = 37.1f,
  val hydrationPercent: Int = 74,
  val hydrationLiters: Float = 1.4f,
  val isBiometricSensorLost: Boolean = false,
  // Suit Systems (Lower-Right + Inferred Suit Status Page)
  val suitPressurePsi: Float = 4.3f,
  val oxygenPercent: Int = 88,
  val oxygenHours: Float = 3.8f,
  val suitPowerPercent: Int = 92,
  val suitPowerHours: Float = 4.5f,
  val ppCo2MmHg: Float = 1.8f,
  val radiationMSv: Float = 0.24f,
  val windSpeedMps: Float = 6.4f,
  val evaSeconds: Int = INITIAL_EVA_SECONDS,
  // Mars Map Traverse Track + Pinch Camera State
  val marsTrackPoints: List<MarsTrackPoint> = listOf(MarsTrackPoint(0f, 0f)),
  val mapZoom: Float = 1.0f,
  val mapTiltDegrees: Float = 0f,
  val mapPanEastDeg: Float = 0f,
  val mapPanNorthDeg: Float = 0f,
  val isMapPinching: Boolean = false,
  // Cognitive (4th Page - Synced HR, Stress Level & Sleep Tracker)
  val mentalState: MentalState =
    MentalState(
      heartRate = BASE_HR_BPM.toDouble(),
      o2Use = 0.09,
      helmetCo2MmHg = 1.8,
      score = 96,
      band = MentalBand.OK,
      label = null,
      askOk = false,
      calibrated = true,
      calibration = 1.0f,
      needsWorkloadChange = false,
      expectedHr = BASE_HR_BPM.toDouble(),
      additionalHr = 0.0,
      hrPenalty = 0,
      co2Penalty = 0,
      scenario = MentalScenario.BASELINE,
      stressPercent = 12,
      stressLevel = StressLevel.LOW,
      stressDriver = "NOMINAL",
      sleepTracker = SleepTrackerState(),
    ),
  // Emergency Drills (5th Page)
  val activeDrill: EmergencyDrill = EmergencyDrill.NONE,
  // Gesture Controls
  val palmHoldProgress: Float = 0f,
  val isTwoFistsReady: Boolean = false,
  val dashboardMode: DashboardMode = DashboardMode.CLOSED,
  val currentPage: DashboardPage = DashboardPage.MAP,
  val lastSwipeDirection: SwipeDirection? = null,
  val swipeCueId: Long = 0L,
) {
  val isDashboardOpen: Boolean
    get() =
      dashboardMode == DashboardMode.QUICK_HOLD ||
        dashboardMode == DashboardMode.QUICK_VOICE ||
        dashboardMode == DashboardMode.PERMANENT

  val isSuitLowPower: Boolean
    get() = suitPowerPercent < 20

  companion object {
    const val DEFAULT_DISTANCE_GOAL_METERS = 5000f
    const val BASE_HR_BPM = 86f
    const val BASE_SYSTOLIC_BP = 118
    const val BASE_DIASTOLIC_BP = 76
    const val INITIAL_EVA_SECONDS = 3 * 60 + 42
    const val MAX_TRACK_POINTS = 200
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
  private val mentalVitals = MentalVitals()
  private val exitTapTimestampsMs = ArrayDeque<Long>()

  private var lastLocationSampleMs = 0L
  private var totalDistanceMeters = 0f
  private var smoothedSpeedMps = 0f
  private var averagedHrBpm = HudUiState.BASE_HR_BPM
  private var baseWindSpeedMps = 6.4f
  private var baseRadiationMSv = 0.24f
  private var baseSpo2Percent = 98
  private var baseEvaSeconds = HudUiState.INITIAL_EVA_SECONDS
  private var lastEvalMs = 1_000L

  private fun safeElapsedRealtimeMs(): Long {
    val raw =
      try {
        SystemClock.elapsedRealtime()
      } catch (_: Throwable) {
        0L
      }
    val next = if (raw > lastEvalMs) raw else lastEvalMs + 1_000L
    lastEvalMs = next
    return next
  }

  init {
    _uiState.update { state -> applyTelemetrySnapshot(state, nowMs = 0L) }

    if (enablePeriodicDecay) {
      viewModelScope.launch {
        val windCycle = floatArrayOf(6.4f, 6.5f, 6.4f, 6.3f, 6.4f, 6.6f, 6.5f, 6.4f)
        val radCycle = floatArrayOf(0.24f, 0.24f, 0.25f, 0.24f, 0.23f, 0.24f, 0.25f, 0.24f)
        var tick = 0
        while (true) {
          delay(1000L)
          tick++
          if (tick % 3 == 0) {
            refreshLocationDerivedMetrics(SystemClock.uptimeMillis())
          }
          synchronized(locationLock) {
            baseEvaSeconds++
            if (tick % 3 == 0) {
              val cycleIdx = (tick / 3) % windCycle.size
              baseWindSpeedMps = windCycle[cycleIdx]
              baseRadiationMSv = radCycle[cycleIdx]
            }
            baseSpo2Percent = if (tick % 12 == 6) 99 else 98
          }
          val nowMs = SystemClock.elapsedRealtime()
          _uiState.update { state -> applyTelemetrySnapshot(state, nowMs) }
        }
      }
    }
  }

  internal fun stepMentalVitals(nowMs: Long = SystemClock.elapsedRealtime()) {
    _uiState.update { state -> applyTelemetrySnapshot(state, nowMs) }
  }

  private fun applyTelemetrySnapshot(state: HudUiState, nowMs: Long): HudUiState {
    val (baseHr, speed, evaSecs, windBase, radBase, spo2Base) =
      synchronized(locationLock) {
        TelemetryBaselineSnapshot(
          baseHrBpm = averagedHrBpm,
          speedMps = smoothedSpeedMps,
          evaSeconds = baseEvaSeconds,
          windMps = baseWindSpeedMps,
          radMSv = baseRadiationMSv,
          spo2Percent = baseSpo2Percent,
        )
      }

    val drill = state.activeDrill
    val sensorLost = drill.sensorDropout
    val effectiveHr = (baseHr + drill.hrOffsetBpm).roundToInt().coerceIn(50, 195)
    val (effectiveSys, effectiveDia) = computeBloodPressure(effectiveHr.toFloat())
    val effectiveSpo2 =
      when {
        sensorLost -> 0
        drill == EmergencyDrill.EMERGENCY_1 ||
          drill == EmergencyDrill.EMERGENCY_4 ||
          drill == EmergencyDrill.EMERGENCY_10 -> 91
        drill == EmergencyDrill.EMERGENCY_2 -> 88
        else -> spo2Base
      }
    val effectiveTemp = (37.1f + drill.coreTempOffsetC).coerceIn(35.5f, 40.5f)
    val effectiveHydrationPct = drill.hydrationPercentOverride ?: 74
    val effectiveHydrationL = ((effectiveHydrationPct / 74f) * 1.4f).coerceIn(0.2f, 2.0f)

    val effectivePressure = drill.suitPressureOverridePsi ?: 4.3f
    val effectiveO2Pct = drill.oxygenPercentOverride ?: 88
    val effectiveO2Hours = drill.oxygenHoursOverride ?: 3.8f
    val effectivePwrPct = drill.suitPowerPercentOverride ?: 92
    val effectivePwrHours = drill.suitPowerHoursOverride ?: 4.5f
    val effectiveCo2 = drill.ppCo2OverrideMmHg ?: 1.8f
    val effectiveRad = drill.radiationOverrideMSv ?: radBase
    val effectiveWind = drill.windSpeedOverrideMps ?: windBase

    val workload =
      (0.09 +
          (speed.coerceAtLeast(0f) / 10.0) * 0.08 +
          if (drill == EmergencyDrill.EMERGENCY_7 || drill == EmergencyDrill.EMERGENCY_10) 0.03 else 0.0)
        .coerceIn(0.05, 0.30)

    val syncedMental =
      mentalVitals.evaluate(
        nowMs = nowMs,
        syncedHeartRateBpm = if (sensorLost) null else effectiveHr.toDouble(),
        workload = workload,
        helmetCo2MmHg = effectiveCo2.toDouble(),
        activeDrill = drill,
        evaSeconds = evaSecs,
      )

    return state.copy(
      heartRateBpm = effectiveHr,
      systolicBp = effectiveSys,
      diastolicBp = effectiveDia,
      spo2Percent = effectiveSpo2,
      coreTempCelsius = effectiveTemp,
      hydrationPercent = effectiveHydrationPct,
      hydrationLiters = effectiveHydrationL,
      isBiometricSensorLost = sensorLost,
      suitPressurePsi = effectivePressure,
      oxygenPercent = effectiveO2Pct,
      oxygenHours = effectiveO2Hours,
      suitPowerPercent = effectivePwrPct,
      suitPowerHours = effectivePwrHours,
      ppCo2MmHg = effectiveCo2,
      radiationMSv = effectiveRad,
      windSpeedMps = effectiveWind,
      evaSeconds = evaSecs,
      mentalState = syncedMental,
    )
  }

  private data class TelemetryBaselineSnapshot(
    val baseHrBpm: Float,
    val speedMps: Float,
    val evaSeconds: Int,
    val windMps: Float,
    val radMSv: Float,
    val spo2Percent: Int,
  )

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

  fun onLocationPermissionResult(granted: Boolean) {
    _uiState.update { state ->
      state.copy(
        hasLocationPermission = granted,
        hasGpsFix = if (granted) state.hasGpsFix else false,
      )
    }
  }

  fun onGpsFixStatusChanged(hasFix: Boolean) {
    _uiState.update { state ->
      if (state.hasGpsFix == hasFix) state else state.copy(hasGpsFix = hasFix)
    }
  }

  fun selectDashboardPage(page: DashboardPage) {
    _uiState.update { state ->
      if (state.currentPage == page) state else state.copy(currentPage = page)
    }
  }

  fun stepDashboardPage(direction: SwipeDirection) {
    _uiState.update { state ->
      val pages = DashboardPage.entries
      val nextIndex =
        when (direction) {
          SwipeDirection.PREVIOUS -> (state.currentPage.ordinal - 1).coerceAtLeast(0)
          SwipeDirection.NEXT -> (state.currentPage.ordinal + 1).coerceAtMost(pages.lastIndex)
        }
      val nextPage = pages[nextIndex]
      state.copy(
        currentPage = nextPage,
        lastSwipeDirection = direction,
        swipeCueId = state.swipeCueId + 1L,
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

  fun onGpsVectorDelta(deltaEastMeters: Float, deltaNorthMeters: Float) {
    if (deltaEastMeters == 0f && deltaNorthMeters == 0f) return
    _uiState.update { state ->
      val last = state.marsTrackPoints.lastOrNull() ?: MarsTrackPoint(0f, 0f)
      val nextPoint =
        MarsTrackPoint(
          eastMeters = last.eastMeters + deltaEastMeters,
          northMeters = last.northMeters + deltaNorthMeters,
        )
      val updated = (state.marsTrackPoints + nextPoint).takeLast(HudUiState.MAX_TRACK_POINTS)
      state.copy(marsTrackPoints = updated)
    }
  }

  fun onLocationSample(
    deltaMeters: Float,
    measuredSpeedMps: Float,
    timestampMs: Long = SystemClock.uptimeMillis(),
  ) {
    val (nextDist, nextSpeed) =
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

        totalDistanceMeters to smoothedSpeedMps
      }

    _uiState.update { state ->
      applyTelemetrySnapshot(
        state =
          state.copy(
            hasGpsFix = true,
            distanceMeters = nextDist,
            speedMps = nextSpeed,
          ),
        nowMs = timestampMs,
      )
    }
  }

  fun refreshLocationDerivedMetrics(nowMs: Long = SystemClock.uptimeMillis()) {
    val (nextSpeed, baseHr) =
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
      val expectedEffectiveHr = (baseHr + state.activeDrill.hrOffsetBpm).coerceIn(50, 195)
      if (state.speedMps == nextSpeed && state.heartRateBpm == expectedEffectiveHr) {
        state
      } else {
        applyTelemetrySnapshot(
          state = state.copy(speedMps = nextSpeed),
          nowMs = nowMs,
        )
      }
    }
  }

  fun onHandsDetected(hands: List<List<HandLandmarkPoint>>, timestampMs: Long) {
    synchronized(gestureLock) {
      val currentState = _uiState.value
      val currentMode = currentState.dashboardMode
      val update = classifier.processFrames(hands, timestampMs, currentMode, currentState.currentPage)

      when {
        update.openMode != null -> {
          val nextMode = update.openMode
          _uiState.update {
            it.copy(
              dashboardMode = nextMode,
              palmHoldProgress = 0f,
              isTwoFistsReady = false,
              isMapPinching = false,
            )
          }
        }
        update.shouldCloseDashboard -> {
          _uiState.update {
            it.copy(
              dashboardMode = DashboardMode.CLOSED,
              palmHoldProgress = 0f,
              isTwoFistsReady = false,
              isMapPinching = false,
            )
          }
        }
        else -> {
          if (currentMode == DashboardMode.PERMANENT && update.swipeDirection != null) {
            stepDashboardPage(update.swipeDirection)
          }
          _uiState.update { state ->
            val targetProgress =
              if (currentMode == DashboardMode.PERMANENT) 0f else update.palmHoldProgress
            val targetFists =
              if (currentMode == DashboardMode.PERMANENT) update.isTwoFistsReady else false

            val nextZoom: Float
            val nextTilt: Float
            val nextPanEast: Float
            val nextPanNorth: Float
            if (update.mapPinchActive && state.isDashboardOpen && state.currentPage == DashboardPage.MAP) {
              nextZoom =
                (state.mapZoom + update.mapPinchForwardDelta * 3.2f).coerceIn(
                  MarsMolaTileCoords.MIN_MAP_ZOOM,
                  MarsMolaTileCoords.MAX_MAP_ZOOM,
                )
              nextTilt = computeTiltForZoom(nextZoom)
              val panSensitivity = 16f / nextZoom
              nextPanEast =
                (state.mapPanEastDeg + update.mapPinchPanDeltaX * panSensitivity).coerceIn(
                  -MarsMolaTileCoords.MAX_PAN_EAST_DEG,
                  MarsMolaTileCoords.MAX_PAN_EAST_DEG,
                )
              nextPanNorth =
                (state.mapPanNorthDeg - update.mapPinchPanDeltaY * panSensitivity).coerceIn(
                  -MarsMolaTileCoords.MAX_PAN_NORTH_DEG,
                  MarsMolaTileCoords.MAX_PAN_NORTH_DEG,
                )
            } else {
              nextZoom = state.mapZoom
              nextTilt = state.mapTiltDegrees
              nextPanEast = state.mapPanEastDeg
              nextPanNorth = state.mapPanNorthDeg
            }

            state.copy(
              palmHoldProgress = targetProgress,
              isTwoFistsReady = targetFists,
              isMapPinching = update.mapPinchActive,
              mapZoom = nextZoom,
              mapTiltDegrees = nextTilt,
              mapPanEastDeg = nextPanEast,
              mapPanNorthDeg = nextPanNorth,
            )
          }
        }
      }
    }
  }

  private fun adjustMapZoom(deltaZoom: Float) {
    _uiState.update { state ->
      val nextZoom =
        (state.mapZoom + deltaZoom).coerceIn(
          MarsMolaTileCoords.MIN_MAP_ZOOM,
          MarsMolaTileCoords.MAX_MAP_ZOOM,
        )
      state.copy(
        mapZoom = nextZoom,
        mapTiltDegrees = computeTiltForZoom(nextZoom),
      )
    }
  }

  private fun recenterMap() {
    _uiState.update { state ->
      state.copy(
        mapZoom = MarsMolaTileCoords.MIN_MAP_ZOOM,
        mapTiltDegrees = 0f,
        mapPanEastDeg = 0f,
        mapPanNorthDeg = 0f,
        isMapPinching = false,
      )
    }
  }

  fun onVoiceCommand(command: VoiceCommand) {
    synchronized(gestureLock) {
      val currentState = _uiState.value
      val currentMode = currentState.dashboardMode
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
            it.copy(
              dashboardMode = nextMode,
              palmHoldProgress = 0f,
              isTwoFistsReady = false,
            )
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
        VoiceCommand.NAVIGATE_VITALS -> {
          if (currentMode != DashboardMode.CLOSED) {
            selectDashboardPage(DashboardPage.VITALS)
          }
        }
        VoiceCommand.NAVIGATE_MAP -> {
          if (currentMode != DashboardMode.CLOSED) {
            selectDashboardPage(DashboardPage.MAP)
          }
        }
        VoiceCommand.NAVIGATE_SUIT -> {
          if (currentMode != DashboardMode.CLOSED) {
            selectDashboardPage(DashboardPage.SUIT_STATUS)
          }
        }
        VoiceCommand.NAVIGATE_COGNITIVE -> {
          if (currentMode != DashboardMode.CLOSED) {
            selectDashboardPage(DashboardPage.COGNITIVE)
          }
        }
        VoiceCommand.NAVIGATE_DRILLS -> {
          if (currentMode != DashboardMode.CLOSED) {
            selectDashboardPage(DashboardPage.DRILLS)
          }
        }
        is VoiceCommand.RunEmergency -> {
          val drill = EmergencyDrill.fromNumber(command.number) ?: return
          val evalTimeMs = safeElapsedRealtimeMs()
          _uiState.update { state ->
            applyTelemetrySnapshot(
              state = state.copy(activeDrill = drill),
              nowMs = evalTimeMs,
            )
          }
        }
        VoiceCommand.MAP_ZOOM_IN -> {
          if (currentMode != DashboardMode.CLOSED && currentState.currentPage == DashboardPage.MAP) {
            adjustMapZoom(VOICE_ZOOM_STEP)
          }
        }
        VoiceCommand.MAP_ZOOM_OUT -> {
          if (currentMode != DashboardMode.CLOSED && currentState.currentPage == DashboardPage.MAP) {
            adjustMapZoom(-VOICE_ZOOM_STEP)
          }
        }
        VoiceCommand.MAP_RECENTER -> {
          if (currentMode != DashboardMode.CLOSED && currentState.currentPage == DashboardPage.MAP) {
            recenterMap()
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
    private const val VOICE_ZOOM_STEP = 0.85f
    private const val MAX_TILT_DEGREES = 42f

    internal fun computeTiltForZoom(zoom: Float): Float {
      val span = (MarsMolaTileCoords.MAX_MAP_ZOOM - MarsMolaTileCoords.MIN_MAP_ZOOM).coerceAtLeast(1f)
      return (((zoom - MarsMolaTileCoords.MIN_MAP_ZOOM) / span) * MAX_TILT_DEGREES).coerceIn(
        0f,
        MAX_TILT_DEGREES,
      )
    }

    internal fun computeTargetHeartRate(speedMps: Float): Float {
      return (HudUiState.BASE_HR_BPM + speedMps.coerceAtLeast(0f) * HR_SPEED_MULTIPLIER)
        .coerceIn(HudUiState.BASE_HR_BPM, MAX_HR_BPM)
    }

    internal fun computeBloodPressure(heartRateBpm: Float): Pair<Int, Int> {
      val exertionDelta = (heartRateBpm - HudUiState.BASE_HR_BPM).coerceAtLeast(0f)
      val systolic = (HudUiState.BASE_SYSTOLIC_BP + exertionDelta * 0.45f).roundToInt().coerceIn(110, 162)
      val diastolic = (HudUiState.BASE_DIASTOLIC_BP + exertionDelta * 0.18f).roundToInt().coerceIn(70, 96)
      return systolic to diastolic
    }
  }
}
