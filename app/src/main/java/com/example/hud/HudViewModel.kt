package com.example.hud

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.body.BodyMaintenanceState
import com.example.crew.CrewLinkStats
import com.example.crew.CrewMember
import com.example.crew.CrewRoster
import com.example.crew.CrewStatus
import com.example.body.GravityLoadModel
import com.example.nasa.Mars24
import com.example.nasa.MarsTime
import com.example.nasa.PlanetEphemeris
import com.example.nasa.SpaceWeatherEvent
import com.example.nasa.SpaceWeatherForecaster
import com.example.nasa.SpaceWeatherState
import com.example.predict.TrendForecaster
import com.example.suit.AxemuProfile
import com.example.suit.HabitabilityModel
import com.example.suit.HabitabilityState
import com.example.suit.JezeroWeatherModel
import com.example.eye.BlinkAnalyzer
import com.example.eye.EyeMetrics
import com.example.eye.EyeSample
import com.example.eye.EyeTrackingStatus
import com.example.gesture.DashboardMode
import com.example.gesture.DashboardPage
import com.example.gesture.GestureAction
import com.example.gesture.HandGestureClassifier
import com.example.gesture.HandLandmarkPoint
import com.example.gesture.HandObservation
import com.example.gesture.HandPose
import com.example.gesture.SwipeDirection
import com.example.mental.CognitiveLevel
import com.example.mental.CognitiveLoadInputs
import com.example.mental.CognitiveLoadModel
import com.example.mental.CognitiveLoadState
import com.example.mental.EmergencyDrill
import com.example.mental.MentalBand
import com.example.mental.MentalScenario
import com.example.mental.MentalState
import com.example.mental.MentalVitals
import com.example.mental.SleepTrackerState
import com.example.mental.StressLevel
import com.example.radiation.RadiationModel
import com.example.radiation.RadiationState
import com.example.radiation.RadiationStatus
import com.example.suit.SuitTelemetry
import com.example.suit.SuitTelemetryModel
import com.example.voice.MicStatus
import com.example.voice.VoiceCommand
import kotlin.math.abs
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

data class GestureFeedback(
  val activePose: HandPose = HandPose.NONE,
  val pendingAction: GestureAction? = null,
  val holdProgress: Float = 0f,
  val lastAction: GestureAction? = null,
  val lastActionId: Long = 0L,
)

/** Predictive outputs (simple linear trends, see TrendForecaster). */
data class CognitiveForecast(
  val projectedIn10Min: Int? = null,
  val slopePerMin: Double = 0.0,
  val minutesToCaution: Double? = null,
  val history: List<Int> = emptyList(),
)

data class ConsumablesForecast(
  val oxygenMinutesLeft: Double = 0.0,
  val batteryMinutesLeft: Double = 0.0,
  val ratedEvaMinutesLeft: Double = AxemuProfile.RATED_EVA_HOURS * 60.0,
  val turnBackInMinutes: Double = 0.0,
  val limitingFactor: String = "O\u2082",
  val usesObservedTrend: Boolean = false,
)

data class EarthLinkState(
  val oneWayDelaySec: Double = 0.0,
  val distanceAu: Double = 0.0,
  val solarConjunction: Boolean = false,
)

data class HudUiState(
  val hasCameraPermission: Boolean = false,
  val hasLocationPermission: Boolean = false,
  val hasGpsFix: Boolean = false,
  val headingDegrees: Float = 0f,
  val pitchDegrees: Float = 0f,
  val batteryPercent: Int = 100,
  val distanceMeters: Float = 0f,
  val speedMps: Float = 0f,
  val distanceGoalMeters: Float = DEFAULT_DISTANCE_GOAL_METERS,
  // 2. Vitals (crew biomedical, as sensed by the suit)
  val heartRateBpm: Int = BASE_HR_BPM.roundToInt(),
  val systolicBp: Int = BASE_SYSTOLIC_BP,
  val diastolicBp: Int = BASE_DIASTOLIC_BP,
  val spo2Percent: Int = 98,
  val coreTempCelsius: Float = 37.1f,
  val hydrationPercent: Int = 74,
  val hydrationLiters: Float = 1.4f,
  val isBiometricSensorLost: Boolean = false,
  val suit: SuitTelemetry = SuitTelemetry(),
  // 3. Radiation & geographic protection
  val radiation: RadiationState = RadiationState(),
  val windSpeedMps: Float = 6.4f,
  val evaSeconds: Int = INITIAL_EVA_SECONDS,
  // 4. Visualization (map)
  val marsTrackPoints: List<MarsTrackPoint> = listOf(MarsTrackPoint(0f, 0f)),
  val mapZoom: Float = 1.0f,
  val mapTiltDegrees: Float = 0f,
  val mapPanEastDeg: Float = 0f,
  val mapPanNorthDeg: Float = 0f,
  val isMapPinching: Boolean = false,
  // 1. Mental health
  val mentalState: MentalState = DEFAULT_MENTAL_STATE,
  val cognitive: CognitiveLoadState = CognitiveLoadState(),
  val eye: EyeMetrics = EyeMetrics(),
  val eyeTrackingStatus: EyeTrackingStatus = EyeTrackingStatus.OFF,
  val eyePreviewEnabled: Boolean = true,
  val alertAcknowledged: Boolean = false,
  // NASA-derived context and predictions
  val marsTime: MarsTime? = null,
  val earthLink: EarthLinkState = EarthLinkState(),
  val spaceWeather: SpaceWeatherState = SpaceWeatherState(),
  val cognitiveForecast: CognitiveForecast = CognitiveForecast(),
  val consumables: ConsumablesForecast = ConsumablesForecast(),
  val body: BodyMaintenanceState = BodyMaintenanceState(),
  val habitability: HabitabilityState = HabitabilityState(),
  val motorSteadiness: Int? = null,
  // Drills
  val activeDrill: EmergencyDrill = EmergencyDrill.NONE,
  // Controls
  val mic: MicStatus = MicStatus(),
  val gesture: GestureFeedback = GestureFeedback(),
  val dashboardMode: DashboardMode = DashboardMode.CLOSED,
  val currentPage: DashboardPage = DashboardPage.HOME,
  val anchorYawDegrees: Float = 0f,
  val anchorPitchDegrees: Float = 0f,
  /** Center lock: the window follows the head instead of hanging in space. */
  val centerLocked: Boolean = false,
  // Crew link (phone-to-phone)
  val crew: List<CrewMember> = emptyList(),
  val crewLink: CrewLinkStats = CrewLinkStats(),
  val crewSelfName: String = "",
  val hasAbsoluteFix: Boolean = false,
  /** Accuracy (m) of the last position shared with the crew; null = never had one. */
  val crewFixAccuracyMeters: Float? = null,
  val lastSwipeDirection: SwipeDirection? = null,
  val swipeCueId: Long = 0L,
) {
  val isDashboardOpen: Boolean
    get() = dashboardMode != DashboardMode.CLOSED

  val isSuitLowPower: Boolean
    get() = suit.isLowPower

  companion object {
    const val DEFAULT_DISTANCE_GOAL_METERS = 5000f
    const val BASE_HR_BPM = 86f
    const val BASE_SYSTOLIC_BP = 118
    const val BASE_DIASTOLIC_BP = 76
    const val INITIAL_EVA_SECONDS = 3 * 60 + 42
    const val MAX_TRACK_POINTS = 200

    val DEFAULT_MENTAL_STATE =
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
      )
  }
}

sealed interface HudEvent {
  data object ExitApp : HudEvent

  data class Speak(val text: String) : HudEvent

  data object StopSpeaking : HudEvent

  data object RecalibrateMic : HudEvent
}

class HudViewModel(
  private val classifier: HandGestureClassifier = HandGestureClassifier(),
  enablePeriodicDecay: Boolean = true,
) : ViewModel() {

  private val _uiState = MutableStateFlow(HudUiState())
  val uiState: StateFlow<HudUiState> = _uiState.asStateFlow()

  private val _events = MutableSharedFlow<HudEvent>(extraBufferCapacity = 8)
  val events: SharedFlow<HudEvent> = _events.asSharedFlow()

  private val gestureLock = Any()
  private val locationLock = Any()
  private val eyeLock = Any()
  private val modelLock = Any()
  private val mentalVitals = MentalVitals()
  private val cognitiveModel = CognitiveLoadModel()
  private val suitModel = SuitTelemetryModel()
  private val radiationModel = RadiationModel()
  private val blinkAnalyzer = BlinkAnalyzer()
  private val exitTapTimestampsMs = ArrayDeque<Long>()

  private var lastLocationSampleMs = 0L
  private var totalDistanceMeters = 0f
  private var smoothedSpeedMps = 0f
  private var averagedHrBpm = HudUiState.BASE_HR_BPM
  private var baseWindSpeedMps = 6.4f
  private var baseSpo2Percent = 98
  private var baseEvaSeconds = HudUiState.INITIAL_EVA_SECONDS
  private var lastEvalMs = 1_000L
  private var lastEyePublishMs = 0L

  // Alert scheduling
  private var lastAlertLevel = CognitiveLevel.NOMINAL
  private var lastEmergencySpokenMs: Long? = null
  private var acknowledgedUntilMs = 0L
  private var lastRadiationStatus = RadiationStatus.NOMINAL

  // Predictive / NASA-context state
  private val cognitiveTrend = TrendForecaster(windowMs = 5 * 60_000L, minSamples = 10)
  private val oxygenTrend = TrendForecaster(windowMs = 10 * 60_000L, minSamples = 30)
  private val batteryTrend = TrendForecaster(windowMs = 10 * 60_000L, minSamples = 30)
  private val cognitiveHistory = ArrayDeque<Int>()
  private var spaceWeatherEvents: List<SpaceWeatherEvent> = emptyList()
  private var spaceWeatherFetchedAtMs = 0L
  private var steps = 0
  private var exerciseSeconds = 0f
  private var lightLux: Float? = null
  private var coldSoakMinutes = 0.0
  private val handTrack = ArrayDeque<Pair<Float, Float>>()

  // Crew link
  private var crewSelfId = "self"
  private var crewRoster = CrewRoster(crewSelfId)
  private val crewLock = Any()
  private var selfLat: Double? = null
  private var selfLon: Double? = null
  private val crewEmergencyAnnouncedMs = HashMap<String, Long>()
  private var motorSteadinessSmoothed: Double? = null

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

  private fun safeUptimeMs(): Long =
    try {
      SystemClock.uptimeMillis()
    } catch (_: Throwable) {
      0L
    }

  init {
    _uiState.update { state -> applyTelemetrySnapshot(state, nowMs = 0L, dtSec = 0.0) }
    _uiState.update { state -> applyPredictions(state, 0L, wallClockMs(), dtSec = 0.0) }
    if (enablePeriodicDecay) {
      viewModelScope.launch {
        while (true) {
          delay(1000L)
          tick(safeElapsedRealtimeMs())
        }
      }
    }
  }

  private var tickCount = 0

  /** One simulation second: advance EVA clock, suit consumables, dose, cognitive load and alerts. */
  internal fun tick(nowMs: Long) {
    tickCount++
    if (tickCount % 3 == 0) refreshLocationDerivedMetrics(safeUptimeMs())
    synchronized(locationLock) {
      baseEvaSeconds++
      if (tickCount % 3 == 0) {
        baseWindSpeedMps = WIND_CYCLE[(tickCount / 3) % WIND_CYCLE.size]
      }
      baseSpo2Percent = if (tickCount % 12 == 6) 99 else 98
    }
    _uiState.update { state -> applyTelemetrySnapshot(state, nowMs, dtSec = 1.0) }
    _uiState.update { state -> applyPredictions(state, nowMs, wallClockMs(), dtSec = 1.0) }
    evaluateAlerts(nowMs)
    refreshCrew(wallClockMs())
  }

  private fun wallClockMs(): Long = System.currentTimeMillis()

  /**
   * NASA-context + predictive layer, advanced once per simulated second:
   *  - Mars24 clock and JPL Earth-Mars light delay (H2 circadian, H3 comms delay)
   *  - DONKI space-weather SEP risk (H1)
   *  - cognitive-load 10-min trend forecast (H2)
   *  - O2 / battery depletion from the observed trend + walk-back ETA -> turn-back time (H3)
   *  - Mars-gravity loading and exercise minutes (H4); habitability + cold soak (H5)
   */
  internal fun applyPredictions(state: HudUiState, nowMs: Long, wallMs: Long, dtSec: Double): HudUiState {
    val mars = Mars24.compute(wallMs)
    val outside = JezeroWeatherModel.predict(mars.localTrueSolarTimeHours)
    val link =
      EarthLinkState(
        oneWayDelaySec = PlanetEphemeris.oneWayLightTimeSec(wallMs),
        distanceAu = PlanetEphemeris.earthMarsDistanceAu(wallMs),
        solarConjunction = PlanetEphemeris.isNearSolarConjunction(wallMs),
      )

    // Cognitive forecast
    cognitiveTrend.add(nowMs, state.cognitive.index.toDouble())
    if (dtSec > 0 && (tickCount % HISTORY_EVERY_TICKS == 0 || cognitiveHistory.isEmpty())) {
      cognitiveHistory.addLast(state.cognitive.index)
      while (cognitiveHistory.size > HISTORY_POINTS) cognitiveHistory.removeFirst()
    }
    val cf = cognitiveTrend.forecast(10.0, CognitiveLoadModel.CAUTION_AT.toDouble(), rising = true)
    val cognitiveForecast =
      CognitiveForecast(
        projectedIn10Min = cf?.projected?.roundToInt()?.coerceIn(0, 100),
        slopePerMin = cf?.slopePerMin ?: 0.0,
        minutesToCaution = cf?.minutesToThreshold,
        history = cognitiveHistory.toList(),
      )

    // Consumables forecast (observed trend when available, model rate otherwise)
    val suit = state.suit
    oxygenTrend.add(nowMs, suit.oxyPriStoragePct.toDouble())
    batteryTrend.add(nowMs, (suit.primaryBatteryPct + suit.secondaryBatteryPct).toDouble())
    val o2Obs = oxygenTrend.forecast(0.0, OXYGEN_RESERVE_PCT, rising = false)?.minutesToThreshold
    val batObs = batteryTrend.forecast(0.0, 0.0, rising = false)?.minutesToThreshold
    val o2Min = o2Obs ?: (suit.oxyTimeLeftSec / 60.0)
    val batMin = batObs ?: (suit.batteryTimeLeftSec / 60.0)
    val ratedMin = (AxemuProfile.RATED_EVA_HOURS * 3600.0 - state.evaSeconds).coerceAtLeast(0.0) / 60.0
    val etaMin = state.radiation.etaToBaseSec / 60.0
    val limiting = listOf("O\u2082" to o2Min, "Battery" to batMin, "AxEMU 8 h rating" to ratedMin).minBy { it.second }
    val consumables =
      ConsumablesForecast(
        oxygenMinutesLeft = o2Min,
        batteryMinutesLeft = batMin,
        ratedEvaMinutesLeft = ratedMin,
        turnBackInMinutes = limiting.second - etaMin - AxemuProfile.RETURN_RESERVE_MIN,
        limitingFactor = limiting.first,
        usesObservedTrend = o2Obs != null || batObs != null,
      )

    // Gravity / atrophy
    if (dtSec > 0 && !state.isBiometricSensorLost && state.heartRateBpm >= EXERCISE_HR_BPM) exerciseSeconds += dtSec.toFloat()
    val body = GravityLoadModel.evaluate(steps, exerciseSeconds)

    // Habitability
    if (dtSec > 0 && outside.airTempC < AxemuProfile.COLD_SOAK_BELOW_C) coldSoakMinutes += dtSec / 60.0
    val noise = if (state.mic.calibrating || state.mic.levelDb <= -89f) null else state.mic.noiseFloorDb
    val habitability =
      HabitabilityState(
        suitTempC = suit.suitTempC,
        suitPressurePsi = suit.suitPressureTotalPsi,
        helmetCo2MmHg = suit.helmetCo2MmHg,
        lightLux = lightLux,
        helmetLightsAdvised = (lightLux ?: 1000f) < AxemuProfile.HELMET_LIGHT_BELOW_LUX || !outside.isDaylight,
        noiseDbfs = noise,
        noiseLabel = HabitabilityModel.noiseLabel(noise),
        distanceFromBaseM = state.radiation.distanceToBaseMeters,
        outside = outside,
        coldSoakMinutes = coldSoakMinutes,
        evaHoursUsedFraction = (state.evaSeconds / (AxemuProfile.RATED_EVA_HOURS * 3600.0)).toFloat().coerceIn(0f, 1f),
      )

    return state.copy(
      marsTime = mars,
      earthLink = link,
      spaceWeather = SpaceWeatherForecaster.evaluate(spaceWeatherEvents, wallMs, spaceWeatherFetchedAtMs),
      cognitiveForecast = cognitiveForecast,
      consumables = consumables,
      body = body,
      habitability = habitability,
    )
  }

  fun onSpaceWeatherEvents(events: List<SpaceWeatherEvent>, fetchedAtMs: Long) {
    spaceWeatherEvents = events
    spaceWeatherFetchedAtMs = fetchedAtMs
    _uiState.update { it.copy(spaceWeather = SpaceWeatherForecaster.evaluate(events, wallClockMs(), fetchedAtMs)) }
  }

  // ---------------------------------------------------------------- Crew link

  fun setCrewIdentity(id: String, name: String) {
    synchronized(crewLock) {
      crewSelfId = id
      crewRoster = CrewRoster(id)
    }
    _uiState.update { it.copy(crewSelfName = name) }
  }

  fun onAbsoluteFix(lat: Double, lon: Double, accuracyMeters: Float = 0f) {
    synchronized(crewLock) {
      selfLat = lat
      selfLon = lon
    }
    val acc = accuracyMeters.roundToInt().toFloat()
    _uiState.update { if (it.hasAbsoluteFix && it.crewFixAccuracyMeters == acc) it else it.copy(hasAbsoluteFix = true, crewFixAccuracyMeters = acc) }
  }

  fun onCrewLinkChanged(stats: CrewLinkStats) {
    _uiState.update { if (it.crewLink == stats) it else it.copy(crewLink = stats) }
  }

  fun onCrewPacket(status: CrewStatus, nowMs: Long = wallClockMs()) {
    synchronized(crewLock) { crewRoster.onPacket(status, nowMs) }
    refreshCrew(nowMs)
  }

  /** True when this wearer should appear to the crew as needing help. */
  private fun selfEmergency(s: HudUiState): Pair<Boolean, String> =
    when {
      s.activeDrill != EmergencyDrill.NONE -> true to "${s.activeDrill.code} ${s.activeDrill.title}"
      s.cognitive.level == CognitiveLevel.EMERGENCY && !s.alertAcknowledged -> true to "Cognitive load ${s.cognitive.index}"
      else -> false to ""
    }

  fun crewOutbound(nowMs: Long = wallClockMs()): CrewStatus {
    val s = _uiState.value
    val (emergency, reason) = selfEmergency(s)
    val (lat, lon) = synchronized(crewLock) { selfLat to selfLon }
    return CrewStatus(
      id = crewSelfId,
      name = s.crewSelfName.ifBlank { crewSelfId },
      lat = lat,
      lon = lon,
      headingDeg = s.headingDegrees,
      heartRateBpm = if (s.isBiometricSensorLost) null else s.heartRateBpm,
      cognitiveLoad = s.cognitive.index,
      emergency = emergency,
      reason = reason,
      sentAtMs = nowMs,
    )
  }

  /** Recomputes crew distances/bearings and announces any crewmate who newly needs help. */
  internal fun refreshCrew(nowMs: Long) {
    // Called from the UDP receive thread and the 1 s tick: the whole method holds crewLock.
    synchronized(crewLock) { refreshCrewLocked(nowMs) }
  }

  private fun refreshCrewLocked(nowMs: Long) {
    val members = crewRoster.members(nowMs, selfLat, selfLon)
    _uiState.update { it.copy(crew = members) }
    for (m in members.filter { it.emergency }) {
      val last = crewEmergencyAnnouncedMs[m.id]
      if (last == null || nowMs - last >= CREW_ALERT_REPEAT_MS) {
        crewEmergencyAnnouncedMs[m.id] = nowMs
        val where =
          if (m.distanceMeters != null && m.bearingDeg != null) ", ${m.distanceMeters.roundToInt()} meters, bearing ${m.bearingDeg.roundToInt()}"
          else ", location unknown"
        _events.tryEmit(HudEvent.Speak("Crew ${m.name} needs help$where. ${m.reason}."))
      }
    }
    crewEmergencyAnnouncedMs.keys.retainAll(members.filter { it.emergency }.map { it.id }.toSet())
  }

  fun onStep() {
    steps++
  }

  fun onLightLux(lux: Float) {
    lightLux = lux
  }

  /** Hand micro-motion (second difference of the palm centre) -> 0..100 steadiness. */
  private fun updateMotorSteadiness(hands: List<HandObservation>) {
    val lm = hands.firstOrNull { it.landmarks.size >= 21 }?.landmarks
    if (lm == null) {
      handTrack.clear()
      return
    }
    handTrack.addLast(((lm[0].x + lm[9].x) / 2f) to ((lm[0].y + lm[9].y) / 2f))
    while (handTrack.size > 24) handTrack.removeFirst()
    if (handTrack.size < 12) return
    var sum = 0.0
    for (i in 2 until handTrack.size) {
      val jx = handTrack[i].first - 2 * handTrack[i - 1].first + handTrack[i - 2].first
      val jy = handTrack[i].second - 2 * handTrack[i - 1].second + handTrack[i - 2].second
      sum += (jx * jx + jy * jy).toDouble()
    }
    val rms = kotlin.math.sqrt(sum / (handTrack.size - 2))
    val score = (100.0 - rms * 1000.0 * 6.0).coerceIn(0.0, 100.0)
    val prev = motorSteadinessSmoothed
    val next = if (prev == null) score else prev + 0.1 * (score - prev)
    motorSteadinessSmoothed = next
    _uiState.update { it.copy(motorSteadiness = next.roundToInt()) }
  }

  internal fun stepMentalVitals(nowMs: Long = SystemClock.elapsedRealtime()) {
    _uiState.update { state -> applyTelemetrySnapshot(state, nowMs, dtSec = 0.0) }
  }

  private fun applyTelemetrySnapshot(state: HudUiState, nowMs: Long, dtSec: Double): HudUiState {
    val (baseHr, speed, evaSecs, windBase, spo2Base) =
      synchronized(locationLock) {
        Baseline(averagedHrBpm, smoothedSpeedMps, baseEvaSeconds, baseWindSpeedMps, baseSpo2Percent)
      }

    val drill = state.activeDrill
    val sensorLost = drill.sensorDropout
    val effectiveHr = (baseHr + drill.hrOffsetBpm).roundToInt().coerceIn(50, 195)
    val (effectiveSys, effectiveDia) = computeBloodPressure(effectiveHr.toFloat())
    val effectiveSpo2 =
      when {
        sensorLost -> 0
        drill == EmergencyDrill.EMERGENCY_1 || drill == EmergencyDrill.EMERGENCY_4 || drill == EmergencyDrill.EMERGENCY_10 -> 91
        drill == EmergencyDrill.EMERGENCY_2 -> 88
        else -> spo2Base
      }
    val effectiveTemp = (37.1f + drill.coreTempOffsetC).coerceIn(35.5f, 40.5f)
    val effectiveHydrationPct = drill.hydrationPercentOverride ?: 74
    val effectiveHydrationL = ((effectiveHydrationPct / 74f) * 1.4f).coerceIn(0.2f, 2.0f)
    val effectiveWind = drill.windSpeedOverrideMps ?: windBase
    val tSec = evaSecs.toDouble()
    val position = state.marsTrackPoints.lastOrNull() ?: MarsTrackPoint(0f, 0f)

    val (suit, radiation) =
      synchronized(modelLock) {
        suitModel.step(dtSec, effectiveHr.toDouble(), drill, tSec) to
          radiationModel.step(dtSec, position.eastMeters, position.northMeters, speed, drill.doseRateMultiplier, tSec)
      }
    val helmetCo2 = suit.helmetCo2MmHg.toDouble()

    val workload =
      (0.09 + (speed.coerceAtLeast(0f) / 10.0) * 0.08 +
          if (drill == EmergencyDrill.EMERGENCY_7 || drill == EmergencyDrill.EMERGENCY_10) 0.03 else 0.0)
        .coerceIn(0.05, 0.30)

    val eye = synchronized(eyeLock) { blinkAnalyzer.metrics() }
    val (mental, cognitive) =
      synchronized(modelLock) {
        val m =
          mentalVitals.evaluate(
            nowMs = nowMs,
            syncedHeartRateBpm = if (sensorLost) null else effectiveHr.toDouble(),
            workload = workload,
            helmetCo2MmHg = helmetCo2,
            activeDrill = drill,
            evaSeconds = evaSecs,
          )
        val c =
          cognitiveModel.evaluate(
            nowMs,
            CognitiveLoadInputs(
              eye = if (state.eyeTrackingStatus == EyeTrackingStatus.ACTIVE) eye else null,
              heartRateBpm = m.heartRate,
              expectedHrBpm = m.expectedHr,
              stressPercent = if (m.stressLevel == StressLevel.UNKNOWN) null else m.stressPercent,
              helmetCo2MmHg = helmetCo2,
              coreTempC = effectiveTemp.toDouble(),
              sleepQualityPercent = m.sleepTracker.qualityPercent,
            ),
          )
        m to c
      }

    return state.copy(
      heartRateBpm = effectiveHr,
      systolicBp = effectiveSys,
      diastolicBp = effectiveDia,
      spo2Percent = effectiveSpo2,
      coreTempCelsius = effectiveTemp,
      hydrationPercent = effectiveHydrationPct,
      hydrationLiters = effectiveHydrationL,
      isBiometricSensorLost = sensorLost,
      suit = suit,
      radiation = radiation,
      windSpeedMps = effectiveWind,
      evaSeconds = evaSecs,
      mentalState = mental,
      cognitive = cognitive,
      eye = eye,
      alertAcknowledged = nowMs < acknowledgedUntilMs,
    )
  }

  private data class Baseline(
    val baseHrBpm: Float,
    val speedMps: Float,
    val evaSeconds: Int,
    val windMps: Float,
    val spo2Percent: Int,
  )

  // ---------------------------------------------------------------- Alerts

  /**
   * Cognitive-load alerting. CAUTION (>=70) is visual only; WARNING (>=90) is spoken once on entry;
   * EMERGENCY (>=95) announces the load and the crew member's pulse every [ALERT_CYCLE_MS] until
   * acknowledged (thumb up / "acknowledge"), which snoozes for [ACK_SNOOZE_MS].
   */
  internal fun evaluateAlerts(nowMs: Long) {
    val state = _uiState.value
    val level = state.cognitive.level
    val acked = nowMs < acknowledgedUntilMs
    val pulse =
      if (state.isBiometricSensorLost) "Pulse signal lost" else "Pulse ${state.heartRateBpm} beats per minute"
    when (level) {
      CognitiveLevel.EMERGENCY -> {
        val last = lastEmergencySpokenMs
        if (!acked && (last == null || nowMs - last >= ALERT_CYCLE_MS)) {
          lastEmergencySpokenMs = nowMs
          _events.tryEmit(
            HudEvent.Speak(
              "Emergency. Cognitive load ${state.cognitive.index}. $pulse. ${state.cognitive.topDriver.lowercase()}. Thumbs up to acknowledge."
            )
          )
        }
      }
      CognitiveLevel.WARNING -> {
        lastEmergencySpokenMs = null
        if (!acked && lastAlertLevel.ordinal < CognitiveLevel.WARNING.ordinal) {
          _events.tryEmit(HudEvent.Speak("Warning. Cognitive load ${state.cognitive.index}. $pulse. Pause and slow your breathing."))
        }
      }
      else -> lastEmergencySpokenMs = null
    }
    lastAlertLevel = level

    val rad = state.radiation
    if (rad.status == RadiationStatus.STORM && lastRadiationStatus != RadiationStatus.STORM) {
      val heading = rad.bearingToBaseDeg.roundToInt()
      _events.tryEmit(
        HudEvent.Speak(
          if (rad.shelter == com.example.radiation.ShelterStatus.SHELTERED) "Solar particle event. Stay inside the base."
          else "Solar particle event. Return to base, bearing $heading, ${rad.distanceToBaseMeters.roundToInt()} meters."
        )
      )
    }
    lastRadiationStatus = rad.status
  }

  fun acknowledgeAlerts(nowMs: Long = safeElapsedRealtimeMs()) {
    acknowledgedUntilMs = nowMs + ACK_SNOOZE_MS
    _events.tryEmit(HudEvent.StopSpeaking)
    _uiState.update { it.copy(alertAcknowledged = true) }
  }

  // ---------------------------------------------------------------- Eye tracking

  fun onEyeTrackingStatus(status: EyeTrackingStatus) {
    _uiState.update { if (it.eyeTrackingStatus == status) it else it.copy(eyeTrackingStatus = status) }
  }

  fun onEyeSample(sample: EyeSample) {
    val metrics =
      synchronized(eyeLock) {
        blinkAnalyzer.process(sample)
        if (sample.timestampMs - lastEyePublishMs < EYE_PUBLISH_INTERVAL_MS) return
        lastEyePublishMs = sample.timestampMs
        blinkAnalyzer.metrics()
      }
    _uiState.update {
      it.copy(
        eye = metrics,
        eyeTrackingStatus = if (it.eyeTrackingStatus == EyeTrackingStatus.STARTING) EyeTrackingStatus.ACTIVE else it.eyeTrackingStatus,
      )
    }
  }

  fun toggleEyePreview() {
    _uiState.update { it.copy(eyePreviewEnabled = !it.eyePreviewEnabled) }
  }

  fun onMicStatus(status: MicStatus) {
    _uiState.update { if (it.mic == status) it else it.copy(mic = status) }
  }

  // ---------------------------------------------------------------- Permissions / sensors

  fun onPermissionResult(granted: Boolean) {
    if (!granted) synchronized(gestureLock) { classifier.reset() }
    _uiState.update { state ->
      state.copy(
        hasCameraPermission = granted,
        gesture = if (granted) state.gesture else GestureFeedback(),
        dashboardMode = if (granted) state.dashboardMode else DashboardMode.CLOSED,
      )
    }
  }

  fun onLocationPermissionResult(granted: Boolean) {
    _uiState.update { state ->
      state.copy(hasLocationPermission = granted, hasGpsFix = if (granted) state.hasGpsFix else false)
    }
  }

  fun onGpsFixStatusChanged(hasFix: Boolean) {
    _uiState.update { state -> if (state.hasGpsFix == hasFix) state else state.copy(hasGpsFix = hasFix) }
  }

  fun onHeadingChanged(degrees: Float) {
    _uiState.update { state ->
      if (abs(SpatialLayout.angleDelta(state.headingDegrees, degrees)) < HEAD_EPSILON_DEG) state
      else withFocusFromHead(state.copy(headingDegrees = degrees))
    }
  }

  fun onPitchChanged(degrees: Float) {
    _uiState.update { state ->
      if (abs(state.pitchDegrees - degrees) < HEAD_EPSILON_DEG) state
      else withFocusFromHead(state.copy(pitchDegrees = degrees))
    }
  }

  private fun withFocusFromHead(state: HudUiState): HudUiState {
    if (!state.isDashboardOpen || state.isMapPinching) return state
    if (state.centerLocked) {
      // Keep the current window straight ahead: re-anchor to the head instead of changing focus.
      return state.copy(
        anchorYawDegrees =
          if (state.currentPage == DashboardPage.DRILLS) state.anchorYawDegrees else SpatialLayout.anchorYawFor(state.currentPage, state.headingDegrees),
        anchorPitchDegrees =
          if (state.currentPage == DashboardPage.DRILLS) state.pitchDegrees - SpatialLayout.DRILLS_PITCH_OFFSET_DEG else state.pitchDegrees,
      )
    }
    val focused =
      SpatialLayout.focusedPage(state.headingDegrees, state.pitchDegrees, state.anchorYawDegrees, state.anchorPitchDegrees)
    return if (focused == state.currentPage) state else state.copy(currentPage = focused)
  }

  fun onBatteryPercentChanged(percent: Int) {
    val clamped = percent.coerceIn(0, 100)
    _uiState.update { state -> if (state.batteryPercent == clamped) state else state.copy(batteryPercent = clamped) }
  }

  fun onGpsVectorDelta(deltaEastMeters: Float, deltaNorthMeters: Float) {
    if (deltaEastMeters == 0f && deltaNorthMeters == 0f) return
    _uiState.update { state ->
      val last = state.marsTrackPoints.lastOrNull() ?: MarsTrackPoint(0f, 0f)
      val next = MarsTrackPoint(last.eastMeters + deltaEastMeters, last.northMeters + deltaNorthMeters)
      state.copy(marsTrackPoints = (state.marsTrackPoints + next).takeLast(HudUiState.MAX_TRACK_POINTS))
    }
  }

  fun onLocationSample(deltaMeters: Float, measuredSpeedMps: Float, timestampMs: Long = SystemClock.uptimeMillis()) {
    val (nextDist, nextSpeed) =
      synchronized(locationLock) {
        if (deltaMeters > 0f) totalDistanceMeters += deltaMeters
        lastLocationSampleMs = timestampMs
        val clampedSpeed = measuredSpeedMps.coerceIn(0f, MAX_SPEED_MPS)
        smoothedSpeedMps += SPEED_SMOOTH_ALPHA * (clampedSpeed - smoothedSpeedMps)
        if (clampedSpeed == 0f && smoothedSpeedMps < 0.05f) smoothedSpeedMps = 0f
        averagedHrBpm += HR_SMOOTH_ALPHA * (computeTargetHeartRate(smoothedSpeedMps) - averagedHrBpm)
        totalDistanceMeters to smoothedSpeedMps
      }
    _uiState.update { state ->
      applyTelemetrySnapshot(
        state.copy(hasGpsFix = true, distanceMeters = nextDist, speedMps = nextSpeed),
        nowMs = timestampMs,
        dtSec = 0.0,
      )
    }
  }

  fun refreshLocationDerivedMetrics(nowMs: Long = SystemClock.uptimeMillis()) {
    val (nextSpeed, baseHr) =
      synchronized(locationLock) {
        val stale = lastLocationSampleMs == 0L || nowMs - lastLocationSampleMs > SPEED_STALE_MS
        if (stale) {
          smoothedSpeedMps += SPEED_SMOOTH_ALPHA * (0f - smoothedSpeedMps)
          if (smoothedSpeedMps < 0.05f) smoothedSpeedMps = 0f
        }
        averagedHrBpm += HR_SMOOTH_ALPHA * (computeTargetHeartRate(smoothedSpeedMps) - averagedHrBpm)
        smoothedSpeedMps to averagedHrBpm.roundToInt()
      }
    _uiState.update { state ->
      val expectedHr = (baseHr + state.activeDrill.hrOffsetBpm).coerceIn(50, 195)
      if (state.speedMps == nextSpeed && state.heartRateBpm == expectedHr) state
      else applyTelemetrySnapshot(state.copy(speedMps = nextSpeed), nowMs, dtSec = 0.0)
    }
  }

  // ---------------------------------------------------------------- Dashboard navigation

  private fun openDashboard(mode: DashboardMode) {
    _uiState.update { state ->
      val page = if (state.isDashboardOpen) state.currentPage else state.currentPage
      state.copy(
        dashboardMode = mode,
        currentPage = page,
        anchorYawDegrees = SpatialLayout.anchorYawFor(page, state.headingDegrees),
        anchorPitchDegrees = if (page == DashboardPage.DRILLS) state.pitchDegrees - SpatialLayout.DRILLS_PITCH_OFFSET_DEG else state.pitchDegrees,
        isMapPinching = false,
      )
    }
  }

  private fun closeDashboard() {
    _uiState.update { it.copy(dashboardMode = DashboardMode.CLOSED, isMapPinching = false) }
  }

  /** Brings [page] directly in front of the current head direction. */
  fun selectDashboardPage(page: DashboardPage) {
    _uiState.update { state ->
      state.copy(
        currentPage = page,
        anchorYawDegrees = if (page == DashboardPage.DRILLS) state.anchorYawDegrees else SpatialLayout.anchorYawFor(page, state.headingDegrees),
        anchorPitchDegrees =
          if (page == DashboardPage.DRILLS) state.pitchDegrees - SpatialLayout.DRILLS_PITCH_OFFSET_DEG else state.pitchDegrees,
      )
    }
  }

  fun stepDashboardPage(direction: SwipeDirection) {
    val current = _uiState.value.currentPage
    val next =
      when (direction) {
        SwipeDirection.NEXT -> DashboardPage.ringNext(current)
        SwipeDirection.PREVIOUS -> DashboardPage.ringPrevious(current)
      }
    selectDashboardPage(next)
    _uiState.update { it.copy(lastSwipeDirection = direction, swipeCueId = it.swipeCueId + 1L) }
  }

  fun toggleCenterLock() {
    _uiState.update { it.copy(centerLocked = !it.centerLocked) }
    recenterView()
  }

  /** Voice "end all emergencies": clear any drill, silence alerts and the crew emergency beacon. */
  fun endAllEmergencies() {
    setDrill(EmergencyDrill.NONE)
    acknowledgeAlerts()
  }

  fun recenterView() {
    selectDashboardPage(_uiState.value.currentPage)
  }

  private fun setDrill(drill: EmergencyDrill) {
    val evalTimeMs = safeElapsedRealtimeMs()
    _uiState.update { state -> applyTelemetrySnapshot(state.copy(activeDrill = drill), evalTimeMs, dtSec = 0.0) }
    evaluateAlerts(evalTimeMs)
  }

  // ---------------------------------------------------------------- Gestures

  fun onHandsDetected(hands: List<HandObservation>, timestampMs: Long) {
    synchronized(gestureLock) {
      val current = _uiState.value
      val update = classifier.process(hands, timestampMs, current.dashboardMode, current.currentPage)
      updateMotorSteadiness(hands)

      update.action?.let { action ->
        when (action) {
          GestureAction.OPEN_DASHBOARD -> openDashboard(DashboardMode.PERMANENT)
          GestureAction.CLOSE_DASHBOARD -> closeDashboard()
          GestureAction.NEXT_TILE -> stepDashboardPage(SwipeDirection.NEXT)
          GestureAction.PREVIOUS_TILE -> stepDashboardPage(SwipeDirection.PREVIOUS)
          GestureAction.TOGGLE_CENTER_LOCK -> toggleCenterLock()
          GestureAction.ACKNOWLEDGE -> acknowledgeAlerts()
          GestureAction.TOGGLE_EYE_PREVIEW -> toggleEyePreview()
          GestureAction.CLEAR_DRILL -> setDrill(EmergencyDrill.NONE)
        }
      }

      _uiState.update { state ->
        val feedback =
          GestureFeedback(
            activePose = update.activePose,
            pendingAction = update.pendingAction,
            holdProgress = update.holdProgress,
            lastAction = update.action ?: state.gesture.lastAction,
            lastActionId = if (update.action != null) state.gesture.lastActionId + 1 else state.gesture.lastActionId,
          )
        if (update.mapPinchActive && state.isDashboardOpen && state.currentPage == DashboardPage.ENVIRONMENT) {
          val nextZoom =
            (state.mapZoom + update.mapPinchForwardDelta * 3.2f).coerceIn(MarsMolaTileCoords.MIN_MAP_ZOOM, MarsMolaTileCoords.MAX_MAP_ZOOM)
          val panSensitivity = 16f / nextZoom
          state.copy(
            gesture = feedback,
            isMapPinching = true,
            mapZoom = nextZoom,
            mapTiltDegrees = computeTiltForZoom(nextZoom),
            mapPanEastDeg =
              (state.mapPanEastDeg + update.mapPinchPanDeltaX * panSensitivity)
                .coerceIn(-MarsMolaTileCoords.MAX_PAN_EAST_DEG, MarsMolaTileCoords.MAX_PAN_EAST_DEG),
            mapPanNorthDeg =
              (state.mapPanNorthDeg - update.mapPinchPanDeltaY * panSensitivity)
                .coerceIn(-MarsMolaTileCoords.MAX_PAN_NORTH_DEG, MarsMolaTileCoords.MAX_PAN_NORTH_DEG),
          )
        } else {
          state.copy(gesture = feedback, isMapPinching = false)
        }
      }
    }
  }

  /** Raw-landmark overload (no classifier categories), used by tests and the fallback model. */
  fun onHandLandmarks(hands: List<List<HandLandmarkPoint>>, timestampMs: Long) =
    onHandsDetected(hands.map { HandObservation(it) }, timestampMs)

  private fun adjustMapZoom(deltaZoom: Float) {
    _uiState.update { state ->
      val nextZoom = (state.mapZoom + deltaZoom).coerceIn(MarsMolaTileCoords.MIN_MAP_ZOOM, MarsMolaTileCoords.MAX_MAP_ZOOM)
      state.copy(mapZoom = nextZoom, mapTiltDegrees = computeTiltForZoom(nextZoom))
    }
  }

  private fun recenterMap() {
    _uiState.update {
      it.copy(
        mapZoom = MarsMolaTileCoords.MIN_MAP_ZOOM,
        mapTiltDegrees = 0f,
        mapPanEastDeg = 0f,
        mapPanNorthDeg = 0f,
        isMapPinching = false,
      )
    }
  }

  // ---------------------------------------------------------------- Voice

  fun onVoiceCommand(command: VoiceCommand) {
    synchronized(gestureLock) {
      val state = _uiState.value
      val open = state.isDashboardOpen
      when (command) {
        VoiceCommand.TOGGLE_QUICK -> {
          classifier.reset()
          when (state.dashboardMode) {
            DashboardMode.CLOSED -> openDashboard(DashboardMode.QUICK_VOICE)
            DashboardMode.QUICK_VOICE -> closeDashboard()
            DashboardMode.PERMANENT -> Unit
          }
        }
        VoiceCommand.OPEN_PERMANENT -> {
          classifier.reset()
          openDashboard(DashboardMode.PERMANENT)
        }
        VoiceCommand.CLOSE_PERMANENT -> {
          classifier.reset()
          closeDashboard()
        }
        VoiceCommand.NAVIGATE_RADIATION -> if (open) selectDashboardPage(DashboardPage.RADIATION)
        VoiceCommand.NAVIGATE_ISOLATION -> if (open) selectDashboardPage(DashboardPage.ISOLATION)
        VoiceCommand.NAVIGATE_DISTANCE -> if (open) selectDashboardPage(DashboardPage.DISTANCE)
        VoiceCommand.NAVIGATE_GRAVITY -> if (open) selectDashboardPage(DashboardPage.GRAVITY)
        VoiceCommand.NAVIGATE_ENVIRONMENT -> if (open) selectDashboardPage(DashboardPage.ENVIRONMENT)
        VoiceCommand.NAVIGATE_DRILLS -> if (open) selectDashboardPage(DashboardPage.DRILLS)
        is VoiceCommand.RunEmergency -> EmergencyDrill.fromNumber(command.number)?.let { setDrill(it) }
        VoiceCommand.MAP_ZOOM_IN -> if (open && state.currentPage == DashboardPage.ENVIRONMENT) adjustMapZoom(VOICE_ZOOM_STEP)
        VoiceCommand.MAP_ZOOM_OUT -> if (open && state.currentPage == DashboardPage.ENVIRONMENT) adjustMapZoom(-VOICE_ZOOM_STEP)
        VoiceCommand.MAP_RECENTER -> if (open && state.currentPage == DashboardPage.ENVIRONMENT) recenterMap()
        VoiceCommand.RECENTER_VIEW -> if (open) recenterView()
        VoiceCommand.CENTER_LOCK -> toggleCenterLock()
        VoiceCommand.END_ALL_EMERGENCIES -> endAllEmergencies()
        VoiceCommand.ACKNOWLEDGE -> acknowledgeAlerts()
        VoiceCommand.TOGGLE_EYE_PREVIEW -> toggleEyePreview()
        VoiceCommand.CALIBRATE_MIC -> _events.tryEmit(HudEvent.RecalibrateMic)
        VoiceCommand.SENSITIVITY_UP,
        VoiceCommand.SENSITIVITY_DOWN -> Unit // handled inside the recogniser
      }
    }
  }

  fun onInvisibleExitZoneTap(nowMs: Long = SystemClock.uptimeMillis()) {
    synchronized(exitTapTimestampsMs) {
      while (exitTapTimestampsMs.isNotEmpty() && nowMs - exitTapTimestampsMs.first() > EXIT_TAP_WINDOW_MS) {
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
    const val ALERT_CYCLE_MS = 20_000L
    const val ACK_SNOOZE_MS = 60_000L
    private const val HISTORY_EVERY_TICKS = 5
    private const val HISTORY_POINTS = 120
    private const val OXYGEN_RESERVE_PCT = 5.0
    private const val EXERCISE_HR_BPM = 105
    const val CREW_ALERT_REPEAT_MS = 60_000L

    private val WIND_CYCLE = floatArrayOf(6.4f, 6.5f, 6.4f, 6.3f, 6.4f, 6.6f, 6.5f, 6.4f)
    private const val EYE_PUBLISH_INTERVAL_MS = 250L
    private const val HEAD_EPSILON_DEG = 0.25f
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
      return (((zoom - MarsMolaTileCoords.MIN_MAP_ZOOM) / span) * MAX_TILT_DEGREES).coerceIn(0f, MAX_TILT_DEGREES)
    }

    internal fun computeTargetHeartRate(speedMps: Float): Float =
      (HudUiState.BASE_HR_BPM + speedMps.coerceAtLeast(0f) * HR_SPEED_MULTIPLIER).coerceIn(HudUiState.BASE_HR_BPM, MAX_HR_BPM)

    internal fun computeBloodPressure(heartRateBpm: Float): Pair<Int, Int> {
      val exertionDelta = (heartRateBpm - HudUiState.BASE_HR_BPM).coerceAtLeast(0f)
      val systolic = (HudUiState.BASE_SYSTOLIC_BP + exertionDelta * 0.45f).roundToInt().coerceIn(110, 162)
      val diastolic = (HudUiState.BASE_DIASTOLIC_BP + exertionDelta * 0.18f).roundToInt().coerceIn(70, 96)
      return systolic to diastolic
    }
  }
}
