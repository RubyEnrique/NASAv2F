package com.example.mental

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * MENTAL VITALS - pure Kotlin readiness & workload simulation, no Android imports.
 */

enum class MentalBand {
  OK,
  CAUTION,
  WARNING,
  UNKNOWN,
}

enum class MentalScenario {
  BASELINE,
  WORKLOAD,
  STRESS,
  MILD_STRESS,
  SLOW_STRESS,
  CO2,
  STRESS_CO2,
  SENSOR_LOSS,
}

class MentalSim(seed: Long = 1L) {
  class Sample(val heartRate: Double?, val o2Use: Double, val helmetCo2MmHg: Double)

  private val rng = java.util.Random(seed)
  private var t0 = -1L
  private var last = 0L
  private var scenario = MentalScenario.BASELINE
  private var scenarioT0 = 0L
  private var hr = 78.0
  private var co2 = 1.2

  fun step(nowMs: Long, sc: MentalScenario): Sample {
    if (t0 < 0) {
      t0 = nowMs
      last = nowMs
      scenarioT0 = nowMs
    }
    val dt = min((nowMs - last) / 1000.0, 5.0)
    last = nowMs
    if (sc != scenario) {
      scenario = sc
      scenarioT0 = nowMs
    }
    val t = (nowMs - t0) / 1000.0
    val ts = (nowMs - scenarioT0) / 1000.0
    fun ramp(seconds: Double) = min(ts / seconds, 1.0)

    val activity = 0.5 + 0.5 * sin(2 * PI * t / 120.0)
    var o2Use = 0.09 + 0.05 * activity
    if (sc == MentalScenario.WORKLOAD) o2Use += ramp(60.0) * 0.08
    o2Use += rng.nextGaussian() * 0.002

    var target = 72 + 350 * (o2Use - 0.09)
    when (sc) {
      MentalScenario.STRESS -> target += ramp(60.0) * 35
      MentalScenario.MILD_STRESS,
      MentalScenario.STRESS_CO2 -> target += ramp(60.0) * 20
      MentalScenario.SLOW_STRESS -> target += ramp(600.0) * 35
      else -> Unit
    }
    hr += (target - hr) * min(dt / 30.0, 1.0)

    val co2Target =
      if (sc == MentalScenario.CO2 || sc == MentalScenario.STRESS_CO2) 8.0 else 0.8 + 1.0 * activity
    co2 += (co2Target - co2) * min(dt / 40.0, 1.0)

    val shownHr =
      if (sc == MentalScenario.SENSOR_LOSS) null else max(hr + rng.nextGaussian() * 1.5, 0.0)
    return Sample(shownHr, o2Use, co2)
  }
}

data class ReadinessState(
  val score: Int?,
  val calibrated: Boolean,
  val calibration: Float,
  val needsWorkloadChange: Boolean,
  val expectedHr: Double?,
  val residual: Double?,
  val hrPenalty: Int,
  val co2Penalty: Int,
  val lowForMs: Long,
)

class Readiness(
  private val lagS: Double = 30.0,
  private val minSamples: Int = 120,
  private val settleSamples: Int = 60,
  private val window: Int = 300,
  private val minR2: Double = 0.30,
  private val minSpread: Double = 0.10,
  private val learnGate: Double = 12.0,
  private val upTolerance: Double = 8.0,
  private val downTolerance: Double = 15.0,
  private val pointsPerBpm: Double = 3.0,
  private val maxHrPenalty: Double = 60.0,
  private val co2Free: Double = 4.0,
  private val co2Full: Double = 10.0,
  private val maxCo2Penalty: Double = 50.0,
  private val lowBelow: Int = 50,
) {
  private var lagged: Double? = null
  private var lastMs = 0L
  private val xs = ArrayDeque<Double>()
  private val ys = ArrayDeque<Double>()
  private var a = 0.0
  private var b = 0.0
  private var fitted = false
  private var sinceFit = 0
  private var frozen = false
  private var shown: Double? = null
  private var lowSince = -1L

  fun recalibrate() {
    lagged = null
    lastMs = 0L
    xs.clear()
    ys.clear()
    fitted = false
    sinceFit = 0
    frozen = false
    shown = null
    lowSince = -1L
  }

  fun update(tMs: Long, heartRate: Double?, workload: Double?, helmetCo2MmHg: Double?): ReadinessState {
    val gapS = if (lastMs == 0L) 1.0 else (tMs - lastMs) / 1000.0
    lastMs = tMs
    if (heartRate == null || workload == null) {
      shown = null
      lowSince = -1L
      return state(null, null, null, 0.0, 0.0, tMs)
    }
    val dt = gapS.coerceIn(0.0, 5.0)
    val prev = lagged
    val x = if (prev == null || gapS > 30.0) workload else prev + (workload - prev) * min(dt / lagS, 1.0)
    lagged = x
    if (!frozen) learn(x, heartRate)
    if (!fitted) return state(null, null, null, 0.0, 0.0, tMs)

    val expected = a + b * x
    val residual = heartRate - expected
    val off = max(0.0, max(residual - upTolerance, -residual - downTolerance))
    val hrPenalty = min(off * pointsPerBpm, maxHrPenalty)
    val co2Penalty =
      (((helmetCo2MmHg ?: co2Free) - co2Free) / (co2Full - co2Free) * maxCo2Penalty).coerceIn(
        0.0,
        maxCo2Penalty,
      )
    val raw = (100.0 - hrPenalty - co2Penalty).coerceIn(0.0, 100.0)
    val s = shown?.let { it + (raw - it) * min(dt / 5.0, 1.0) } ?: raw
    shown = s
    val score = Math.round(s).toInt()
    if (score < lowBelow) {
      if (lowSince < 0) lowSince = tMs
    } else {
      lowSince = -1L
    }
    return state(score, expected, residual, hrPenalty, co2Penalty, tMs)
  }

  private fun learn(x: Double, heartRate: Double) {
    if (fitted && abs(heartRate - (a + b * x)) > learnGate) return
    xs.addLast(x)
    ys.addLast(heartRate)
    if (xs.size > window) {
      xs.removeFirst()
      ys.removeFirst()
    }
    if (fitted) sinceFit++
    if (xs.size >= minSamples) fit()
    if (fitted && sinceFit >= settleSamples) {
      frozen = true
      xs.clear()
      ys.clear()
    }
  }

  private fun fit() {
    val n = xs.size.toDouble()
    var sx = 0.0
    var sy = 0.0
    var sxx = 0.0
    var sxy = 0.0
    var syy = 0.0
    var lo = Double.MAX_VALUE
    var hi = -Double.MAX_VALUE
    for (k in xs.indices) {
      val x = xs[k]
      val y = ys[k]
      sx += x
      sy += y
      sxx += x * x
      sxy += x * y
      syy += y * y
      lo = min(lo, x)
      hi = max(hi, x)
    }
    val vx = n * sxx - sx * sx
    val vy = n * syy - sy * sy
    if (vx <= 1e-12 || vy <= 1e-12) return
    if (hi - lo < minSpread * abs(sx / n)) return
    val cov = n * sxy - sx * sy
    val slope = cov / vx
    val r2 = cov * cov / (vx * vy)
    if (r2 >= minR2 && slope > 0) {
      b = slope
      a = (sy - slope * sx) / n
      fitted = true
    }
  }

  private fun state(
    score: Int?,
    expected: Double?,
    residual: Double?,
    hrPenalty: Double,
    co2Penalty: Double,
    tMs: Long,
  ): ReadinessState {
    val progress =
      when {
        frozen -> 1f
        fitted -> (minSamples + sinceFit).toFloat() / (minSamples + settleSamples)
        else -> min(xs.size, minSamples - 1).toFloat() / (minSamples + settleSamples)
      }
    return ReadinessState(
      score = score,
      calibrated = frozen,
      calibration = progress.coerceIn(0f, 1f),
      needsWorkloadChange = !fitted && xs.size >= minSamples,
      expectedHr = expected,
      residual = residual,
      hrPenalty = Math.round(hrPenalty).toInt(),
      co2Penalty = Math.round(co2Penalty).toInt(),
      lowForMs = if (lowSince >= 0) tMs - lowSince else 0L,
    )
  }
}

enum class StressLevel {
  LOW,
  MODERATE,
  HIGH,
  CRITICAL,
  UNKNOWN,
}

data class SleepTrackerState(
  val durationHours: Float = 7.4f,
  val qualityPercent: Int = 88,
  val deepMinutes: Int = 112,
  val remMinutes: Int = 104,
  val sleepStatus: String = "RESTED",
)

enum class EmergencyDrill(
  val number: Int,
  val code: String,
  val title: String,
  val impactSummary: String,
  val isCritical: Boolean,
  val hrOffsetBpm: Int,
  val coreTempOffsetC: Float,
  val hydrationPercentOverride: Int?,
  val suitPressureOverridePsi: Float?,
  val oxygenPercentOverride: Int?,
  val oxygenHoursOverride: Float?,
  val suitPowerPercentOverride: Int?,
  val suitPowerHoursOverride: Float?,
  val ppCo2OverrideMmHg: Float?,
  val doseRateMultiplier: Float,
  val windSpeedOverrideMps: Float?,
  val stressBoost: Int,
  val stressDriverLabel: String,
  val sensorDropout: Boolean = false,
  val fanPriRpmOverride: Float? = null,
) {
  NONE(
    number = 0,
    code = "NOMINAL",
    title = "Nominal EVA",
    impactSummary = "Baseline telemetry",
    isCritical = false,
    hrOffsetBpm = 0,
    coreTempOffsetC = 0f,
    hydrationPercentOverride = null,
    suitPressureOverridePsi = null,
    oxygenPercentOverride = null,
    oxygenHoursOverride = null,
    suitPowerPercentOverride = null,
    suitPowerHoursOverride = null,
    ppCo2OverrideMmHg = null,
    doseRateMultiplier = 1f,
    windSpeedOverrideMps = null,
    stressBoost = 0,
    stressDriverLabel = "NOMINAL",
  ),
  EMERGENCY_1(
    number = 1,
    code = "E-01",
    title = "Pressure Leak",
    impactSummary = "2.9 psi · +38 BPM",
    isCritical = true,
    hrOffsetBpm = 38,
    coreTempOffsetC = 0.2f,
    hydrationPercentOverride = null,
    suitPressureOverridePsi = 2.9f,
    oxygenPercentOverride = 72,
    oxygenHoursOverride = 2.6f,
    suitPowerPercentOverride = null,
    suitPowerHoursOverride = null,
    ppCo2OverrideMmHg = null,
    doseRateMultiplier = 1f,
    windSpeedOverrideMps = null,
    stressBoost = 24,
    stressDriverLabel = "PRESSURE",
  ),
  EMERGENCY_2(
    number = 2,
    code = "E-02",
    title = "O2 Depletion",
    impactSummary = "14% O2 · +28 BPM",
    isCritical = true,
    hrOffsetBpm = 28,
    coreTempOffsetC = 0.1f,
    hydrationPercentOverride = null,
    suitPressureOverridePsi = null,
    oxygenPercentOverride = 14,
    oxygenHoursOverride = 0.4f,
    suitPowerPercentOverride = null,
    suitPowerHoursOverride = null,
    ppCo2OverrideMmHg = null,
    doseRateMultiplier = 1f,
    windSpeedOverrideMps = null,
    stressBoost = 22,
    stressDriverLabel = "HYPOXIA",
  ),
  EMERGENCY_3(
    number = 3,
    code = "E-03",
    title = "Solar Rad Storm",
    impactSummary = "18x dose · +16 BPM",
    isCritical = true,
    hrOffsetBpm = 16,
    coreTempOffsetC = 0.3f,
    hydrationPercentOverride = null,
    suitPressureOverridePsi = null,
    oxygenPercentOverride = null,
    oxygenHoursOverride = null,
    suitPowerPercentOverride = null,
    suitPowerHoursOverride = null,
    ppCo2OverrideMmHg = null,
    doseRateMultiplier = 18f,
    windSpeedOverrideMps = 13.8f,
    stressBoost = 16,
    stressDriverLabel = "RAD LOAD",
  ),
  EMERGENCY_4(
    number = 4,
    code = "E-04",
    title = "CO2 Scrubber",
    impactSummary = "8.4 mmHg · +22 BPM",
    isCritical = true,
    hrOffsetBpm = 22,
    coreTempOffsetC = 0.2f,
    hydrationPercentOverride = null,
    suitPressureOverridePsi = null,
    oxygenPercentOverride = null,
    oxygenHoursOverride = null,
    suitPowerPercentOverride = null,
    suitPowerHoursOverride = null,
    ppCo2OverrideMmHg = 8.4f,
    doseRateMultiplier = 1f,
    windSpeedOverrideMps = null,
    stressBoost = 18,
    stressDriverLabel = "CO2 LOAD",
    fanPriRpmOverride = 9000f,
  ),
  EMERGENCY_5(
    number = 5,
    code = "E-05",
    title = "Low Suit Power",
    impactSummary = "14% PWR · +14 BPM",
    isCritical = true,
    hrOffsetBpm = 14,
    coreTempOffsetC = -0.3f,
    hydrationPercentOverride = null,
    suitPressureOverridePsi = null,
    oxygenPercentOverride = null,
    oxygenHoursOverride = null,
    suitPowerPercentOverride = 14,
    suitPowerHoursOverride = 0.4f,
    ppCo2OverrideMmHg = 3.2f,
    doseRateMultiplier = 1f,
    windSpeedOverrideMps = null,
    stressBoost = 14,
    stressDriverLabel = "POWER FAULT",
  ),
  EMERGENCY_6(
    number = 6,
    code = "E-06",
    title = "Acute Stress",
    impactSummary = "+46 BPM · 5.8h sleep",
    isCritical = true,
    hrOffsetBpm = 46,
    coreTempOffsetC = 0.4f,
    hydrationPercentOverride = 64,
    suitPressureOverridePsi = null,
    oxygenPercentOverride = null,
    oxygenHoursOverride = null,
    suitPowerPercentOverride = null,
    suitPowerHoursOverride = null,
    ppCo2OverrideMmHg = 2.6f,
    doseRateMultiplier = 1f,
    windSpeedOverrideMps = null,
    stressBoost = 32,
    stressDriverLabel = "ACUTE STRESS",
  ),
  EMERGENCY_7(
    number = 7,
    code = "E-07",
    title = "Dust Gale Load",
    impactSummary = "24.8 m/s · 38.6 C",
    isCritical = false,
    hrOffsetBpm = 26,
    coreTempOffsetC = 1.5f,
    hydrationPercentOverride = 34,
    suitPressureOverridePsi = 4.1f,
    oxygenPercentOverride = null,
    oxygenHoursOverride = null,
    suitPowerPercentOverride = 76,
    suitPowerHoursOverride = 3.6f,
    ppCo2OverrideMmHg = 3.4f,
    doseRateMultiplier = 1f,
    windSpeedOverrideMps = 24.8f,
    stressBoost = 16,
    stressDriverLabel = "THERMAL",
  ),
  EMERGENCY_8(
    number = 8,
    code = "E-08",
    title = "Dehydration",
    impactSummary = "22% H2O · 38.1 C",
    isCritical = false,
    hrOffsetBpm = 18,
    coreTempOffsetC = 1.0f,
    hydrationPercentOverride = 22,
    suitPressureOverridePsi = null,
    oxygenPercentOverride = null,
    oxygenHoursOverride = null,
    suitPowerPercentOverride = null,
    suitPowerHoursOverride = null,
    ppCo2OverrideMmHg = null,
    doseRateMultiplier = 1f,
    windSpeedOverrideMps = null,
    stressBoost = 14,
    stressDriverLabel = "DEHYDRATED",
  ),
  EMERGENCY_9(
    number = 9,
    code = "E-09",
    title = "Sensor Dropout",
    impactSummary = "HR/SpO2/BP --",
    isCritical = true,
    hrOffsetBpm = 0,
    coreTempOffsetC = 0f,
    hydrationPercentOverride = null,
    suitPressureOverridePsi = null,
    oxygenPercentOverride = null,
    oxygenHoursOverride = null,
    suitPowerPercentOverride = null,
    suitPowerHoursOverride = null,
    ppCo2OverrideMmHg = null,
    doseRateMultiplier = 1f,
    windSpeedOverrideMps = null,
    stressBoost = 0,
    stressDriverLabel = "SENSOR LOSS",
    sensorDropout = true,
  ),
  EMERGENCY_10(
    number = 10,
    code = "E-10",
    title = "Compound Decomp",
    impactSummary = "Multi-fault · +52 BPM",
    isCritical = true,
    hrOffsetBpm = 52,
    coreTempOffsetC = 1.1f,
    hydrationPercentOverride = 38,
    suitPressureOverridePsi = 3.1f,
    oxygenPercentOverride = 19,
    oxygenHoursOverride = 0.6f,
    suitPowerPercentOverride = 18,
    suitPowerHoursOverride = 0.5f,
    ppCo2OverrideMmHg = 7.6f,
    doseRateMultiplier = 9f,
    windSpeedOverrideMps = 19.4f,
    stressBoost = 38,
    stressDriverLabel = "MULTI-FAULT",
  );

  companion object {
    val activeDrills: List<EmergencyDrill> = entries.filter { it != NONE }

    fun fromNumber(number: Int): EmergencyDrill? = entries.firstOrNull { it.number == number }
  }
}

data class MentalState(
  val heartRate: Double?,
  val o2Use: Double,
  val helmetCo2MmHg: Double,
  val score: Int?,
  val band: MentalBand,
  val label: String?,
  val askOk: Boolean,
  val calibrated: Boolean,
  val calibration: Float,
  val needsWorkloadChange: Boolean,
  val expectedHr: Double?,
  val additionalHr: Double?,
  val hrPenalty: Int,
  val co2Penalty: Int,
  val scenario: MentalScenario,
  val stressPercent: Int = 10,
  val stressLevel: StressLevel = StressLevel.LOW,
  val stressDriver: String = "NOMINAL",
  val sleepTracker: SleepTrackerState = SleepTrackerState(),
)

class MentalVitals(
  seed: Long = 1L,
  private val askAfterMs: Long = 30_000,
  preSeedBaseline: Boolean = true,
) {
  private val sim = MentalSim(seed)
  private val readiness = Readiness()
  @Volatile var scenario: MentalScenario = MentalScenario.BASELINE

  init {
    if (preSeedBaseline) {
      seedCalmBaseline()
    }
  }

  private fun seedCalmBaseline() {
    var tMs = -200_000L
    for (i in 0 until 190) {
      val phase = 0.5 + 0.5 * sin(2 * PI * i / 60.0)
      val o2 = 0.09 + 0.05 * phase
      val calmHr = 84.0 + 350.0 * (o2 - 0.09)
      readiness.update(tMs, calmHr, o2, 1.8)
      tMs += 1_000L
    }
  }

  fun recalibrate() = readiness.recalibrate()

  fun evaluate(
    nowMs: Long,
    syncedHeartRateBpm: Double?,
    workload: Double,
    helmetCo2MmHg: Double,
    activeDrill: EmergencyDrill = EmergencyDrill.NONE,
    evaSeconds: Int = 0,
  ): MentalState {
    val r = readiness.update(nowMs, syncedHeartRateBpm, workload, helmetCo2MmHg)
    val sleepTracker = computeSleepTracker(activeDrill, evaSeconds)

    if (syncedHeartRateBpm == null || activeDrill.sensorDropout) {
      return MentalState(
        heartRate = null,
        o2Use = workload,
        helmetCo2MmHg = helmetCo2MmHg,
        score = null,
        band = MentalBand.UNKNOWN,
        label = null,
        askOk = false,
        calibrated = r.calibrated,
        calibration = r.calibration,
        needsWorkloadChange = r.needsWorkloadChange,
        expectedHr = r.expectedHr,
        additionalHr = null,
        hrPenalty = 0,
        co2Penalty = 0,
        scenario = scenario,
        stressPercent = 0,
        stressLevel = StressLevel.UNKNOWN,
        stressDriver = "SENSOR LOSS",
        sleepTracker = sleepTracker,
      )
    }

    val excessHr = max(0.0, syncedHeartRateBpm - 86.0) * 1.10
    val co2Load = max(0.0, helmetCo2MmHg - 2.0) * 6.5
    val readinessPenalty = (r.hrPenalty + r.co2Penalty) * 0.60
    val fatigueLoad = ((100 - sleepTracker.qualityPercent) * 0.25)
    val rawStress = 8.0 + max(readinessPenalty, excessHr * 0.72) + co2Load + activeDrill.stressBoost + fatigueLoad
    val stressPercent = Math.round(rawStress).toInt().coerceIn(5, 99)

    val stressLevel =
      when {
        stressPercent >= 80 -> StressLevel.CRITICAL
        stressPercent >= 58 -> StressLevel.HIGH
        stressPercent >= 32 -> StressLevel.MODERATE
        else -> StressLevel.LOW
      }

    val stressDriver =
      when {
        activeDrill != EmergencyDrill.NONE -> activeDrill.stressDriverLabel
        helmetCo2MmHg >= 3.5 -> "CO2 LOAD"
        syncedHeartRateBpm >= 115.0 -> "EXERTION"
        else -> "NOMINAL"
      }

    val band =
      when {
        r.score == null -> MentalBand.UNKNOWN
        r.score < 50 || stressLevel == StressLevel.CRITICAL -> MentalBand.WARNING
        r.score < 70 || stressLevel == StressLevel.HIGH -> MentalBand.CAUTION
        else -> MentalBand.OK
      }

    return MentalState(
      heartRate = syncedHeartRateBpm,
      o2Use = workload,
      helmetCo2MmHg = helmetCo2MmHg,
      score = r.score,
      band = band,
      label = if (band == MentalBand.CAUTION || band == MentalBand.WARNING) "[Readiness Low]" else null,
      askOk = r.lowForMs >= askAfterMs,
      calibrated = r.calibrated,
      calibration = r.calibration,
      needsWorkloadChange = r.needsWorkloadChange,
      expectedHr = r.expectedHr,
      additionalHr = r.residual,
      hrPenalty = r.hrPenalty,
      co2Penalty = r.co2Penalty,
      scenario = scenario,
      stressPercent = stressPercent,
      stressLevel = stressLevel,
      stressDriver = stressDriver,
      sleepTracker = sleepTracker,
    )
  }

  fun tick(nowMs: Long): MentalState {
    val sc = scenario
    val s = sim.step(nowMs, sc)
    return evaluate(
      nowMs = nowMs,
      syncedHeartRateBpm = s.heartRate,
      workload = s.o2Use,
      helmetCo2MmHg = s.helmetCo2MmHg,
    )
  }

  private fun computeSleepTracker(activeDrill: EmergencyDrill, evaSeconds: Int): SleepTrackerState {
    val evaHours = evaSeconds / 3600f
    val baseDuration = (7.4f - evaHours * 0.15f).coerceIn(5.5f, 8.0f)
    val baseQuality = (88 - (evaHours * 2f).toInt()).coerceIn(65, 95)

    return when (activeDrill) {
      EmergencyDrill.EMERGENCY_6 ->
        SleepTrackerState(
          durationHours = 5.8f,
          qualityPercent = 62,
          deepMinutes = 74,
          remMinutes = 68,
          sleepStatus = "FATIGUED",
        )
      EmergencyDrill.EMERGENCY_10 ->
        SleepTrackerState(
          durationHours = 4.6f,
          qualityPercent = 48,
          deepMinutes = 52,
          remMinutes = 50,
          sleepStatus = "DEPRIVED",
        )
      EmergencyDrill.EMERGENCY_4,
      EmergencyDrill.EMERGENCY_7,
      EmergencyDrill.EMERGENCY_8 ->
        SleepTrackerState(
          durationHours = 6.3f,
          qualityPercent = 71,
          deepMinutes = 86,
          remMinutes = 82,
          sleepStatus = "FATIGUED",
        )
      else ->
        SleepTrackerState(
          durationHours = baseDuration,
          qualityPercent = baseQuality,
          deepMinutes = 112,
          remMinutes = 104,
          sleepStatus = if (baseQuality >= 78) "RESTED" else "FATIGUED",
        )
    }
  }
}

