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
)

class MentalVitals(seed: Long = 1L, private val askAfterMs: Long = 30_000) {
  private val sim = MentalSim(seed)
  private val readiness = Readiness()
  @Volatile var scenario: MentalScenario = MentalScenario.BASELINE

  fun recalibrate() = readiness.recalibrate()

  fun tick(nowMs: Long): MentalState {
    val sc = scenario
    val s = sim.step(nowMs, sc)
    val r = readiness.update(nowMs, s.heartRate, s.o2Use, s.helmetCo2MmHg)
    val band =
      when {
        r.score == null -> MentalBand.UNKNOWN
        r.score < 50 -> MentalBand.WARNING
        r.score < 70 -> MentalBand.CAUTION
        else -> MentalBand.OK
      }
    return MentalState(
      heartRate = s.heartRate,
      o2Use = s.o2Use,
      helmetCo2MmHg = s.helmetCo2MmHg,
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
      scenario = sc,
    )
  }
}
