package com.example.mental

import com.example.eye.EyeMetrics
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Approximate cognitive-load index (0..100) fusing eye behaviour with physiology and the suit
 * environment. This is a heuristic decision aid, not a validated clinical instrument.
 *
 * Levels (requested): >= 70 CAUTION, >= 90 WARNING, >= 95 EMERGENCY.
 *
 * Component rationale:
 *  - OCULAR: blink suppression relative to the wearer's own resting baseline indicates sustained
 *    visual/cognitive demand (reading ~10/min vs ~17/min at rest); long blinks, elevated rate and
 *    PERCLOS indicate fatigue / drowsiness (FAA AM-94/17; PERCLOS 80% closure, 1 min window).
 *  - PHYSIOLOGICAL: heart rate above what the current workload explains (Readiness residual) and
 *    the stress estimate from MentalVitals.
 *  - ENVIRONMENT: helmet CO2 (cognitive effects reported from a few mmHg; scored from 3 mmHg up to
 *    8 mmHg) and core temperature above 37.5 C.
 *  - SLEEP: sleep quality debt from the sleep tracker.
 */

enum class CognitiveLevel(val label: String) {
  NOMINAL("NOMINAL"),
  CAUTION("CAUTION"),
  WARNING("WARNING"),
  EMERGENCY("EMERGENCY"),
}

data class CognitiveLoadInputs(
  val eye: EyeMetrics?,
  val heartRateBpm: Double?,
  val expectedHrBpm: Double?,
  val stressPercent: Int?,
  val helmetCo2MmHg: Double,
  val coreTempC: Double,
  val sleepQualityPercent: Int,
)

data class CognitiveLoadState(
  val index: Int = 8,
  val level: CognitiveLevel = CognitiveLevel.NOMINAL,
  val ocular: Int? = null,
  val physiological: Int? = 0,
  val environment: Int = 0,
  val sleep: Int = 0,
  val topDriver: String = "NOMINAL",
  val ocularPattern: String = "NO EYE DATA",
  val recommendation: String = "Steady. Keep your pace.",
  val usesEyeData: Boolean = false,
)

class CognitiveLoadModel(private val decayTauMs: Double = 8_000.0) {
  private var smoothed: Double? = null
  private var lastMs: Long? = null

  fun reset() {
    smoothed = null
    lastMs = null
  }

  fun evaluate(nowMs: Long, inputs: CognitiveLoadInputs): CognitiveLoadState {
    val (ocular, pattern) = ocularScore(inputs.eye)
    val physio = physiologicalScore(inputs)
    val env = environmentScore(inputs)
    val sleep = sleepScore(inputs.sleepQualityPercent)

    var weighted = 0.0
    var weightSum = 0.0
    fun add(score: Double?, weight: Double) {
      if (score != null) {
        weighted += score * weight
        weightSum += weight
      }
    }
    add(ocular, W_OCULAR)
    add(physio, W_PHYSIO)
    add(env, W_ENV)
    add(sleep, W_SLEEP)
    val mean = if (weightSum > 0) weighted / weightSum else 0.0
    val worst = listOfNotNull(ocular, physio, env, sleep).maxOrNull() ?: 0.0
    // Pull halfway toward the worst component so one critical factor is never averaged away.
    val raw = (mean + PEAK_PULL * (worst - mean)).coerceIn(0.0, 100.0)

    // Rise immediately (safety), decay slowly (avoid flicker as values recover).
    val prev = smoothed
    val prevMs = lastMs
    val value =
      if (prev == null || prevMs == null || raw >= prev) {
        raw
      } else {
        val dt = (nowMs - prevMs).coerceAtLeast(0L).toDouble()
        prev + (raw - prev) * min(dt / decayTauMs, 1.0)
      }
    smoothed = value
    lastMs = nowMs

    val index = value.roundToInt().coerceIn(0, 100)
    val level = levelFor(index)
    val driver =
      when (worst) {
        0.0 -> "NOMINAL"
        ocular -> if (pattern == "FATIGUE") "EYE FATIGUE" else "VISUAL LOAD"
        physio -> if ((inputs.stressPercent ?: 0) >= 58) "STRESS" else "HEART RATE"
        env -> if (inputs.helmetCo2MmHg >= 3.0) "HELMET CO2" else "THERMAL"
        else -> "SLEEP DEBT"
      }
    return CognitiveLoadState(
      index = index,
      level = level,
      ocular = ocular?.roundToInt(),
      physiological = physio?.roundToInt(),
      environment = env.roundToInt(),
      sleep = sleep.roundToInt(),
      topDriver = if (level == CognitiveLevel.NOMINAL && worst < 30.0) "NOMINAL" else driver,
      ocularPattern = pattern,
      recommendation = recommendationFor(level),
      usesEyeData = ocular != null,
    )
  }

  companion object {
    const val CAUTION_AT = 70
    const val WARNING_AT = 90
    const val EMERGENCY_AT = 95

    private const val W_OCULAR = 0.35
    private const val W_PHYSIO = 0.40
    private const val W_ENV = 0.15
    private const val W_SLEEP = 0.10
    private const val PEAK_PULL = 0.5

    fun levelFor(index: Int): CognitiveLevel =
      when {
        index >= EMERGENCY_AT -> CognitiveLevel.EMERGENCY
        index >= WARNING_AT -> CognitiveLevel.WARNING
        index >= CAUTION_AT -> CognitiveLevel.CAUTION
        else -> CognitiveLevel.NOMINAL
      }

    fun recommendationFor(level: CognitiveLevel): String =
      when (level) {
        CognitiveLevel.NOMINAL -> "Steady. Keep your pace."
        CognitiveLevel.CAUTION -> "Slow down. Box-breathe 4-4-4-4."
        CognitiveLevel.WARNING -> "Pause the task. Check in with CAPCOM."
        CognitiveLevel.EMERGENCY -> "Stop. Stabilise posture. Crew alerted."
      }

    private fun clamp01(v: Double) = v.coerceIn(0.0, 1.0)

    /** Returns (score 0..100 or null when eye data is unreliable, pattern label). */
    internal fun ocularScore(eye: EyeMetrics?): Pair<Double?, String> {
      if (eye == null || !eye.isReliable) return null to "NO EYE DATA"
      val rate = eye.blinkRatePerMin ?: return null to "NO EYE DATA"
      val ratio = rate / eye.effectiveBaseline
      // Suppression: ratio 1.0 -> 0, ratio 0.4 -> 100.
      val suppression = clamp01((1.0 - ratio) / 0.6) * 100.0
      // Fatigue signals.
      val elevated = clamp01((ratio - 1.3) / 0.7) * 100.0
      val perclos = eye.perclos?.let { clamp01((it - 0.04) / (0.15 - 0.04)) * 100.0 } ?: 0.0
      val duration = eye.meanBlinkDurationMs?.let { clamp01((it - 150.0) / (400.0 - 150.0)) * 100.0 } ?: 0.0
      val fatigue = max(max(perclos, duration), elevated * 0.7)
      return if (fatigue > suppression) {
        fatigue to (if (fatigue >= 25.0) "FATIGUE" else "NORMAL")
      } else {
        suppression to (if (suppression >= 25.0) "BLINK SUPPRESSION" else "NORMAL")
      }
    }

    internal fun physiologicalScore(inputs: CognitiveLoadInputs): Double? {
      val hr = inputs.heartRateBpm ?: return null
      val expected = inputs.expectedHrBpm ?: 86.0
      val hrScore = clamp01((hr - expected) / 40.0) * 100.0
      val stress = (inputs.stressPercent ?: 0).toDouble().coerceIn(0.0, 100.0)
      return 0.55 * stress + 0.45 * hrScore
    }

    internal fun environmentScore(inputs: CognitiveLoadInputs): Double {
      val co2 = clamp01((inputs.helmetCo2MmHg - 3.0) / 5.0) * 100.0
      val heat = clamp01((inputs.coreTempC - 37.5) / 1.5) * 100.0
      return max(co2, heat)
    }

    internal fun sleepScore(qualityPercent: Int): Double = clamp01((85.0 - qualityPercent) / 40.0) * 100.0
  }
}
