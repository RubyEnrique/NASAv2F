package com.example.body

import kotlin.math.sqrt

/*
 * Hazard 4 - Gravity (or lack thereof): maintenance to avoid atrophy.
 *
 * On Mars the skeleton carries 0.38 of its Earth weight, so each step loads bone and muscle less.
 * References used for goals (display only, not clinical):
 *  - ISS crews exercise ~2.5 h/day, 6 days/week (Space.com, citing NASA officials) and still lose
 *    bone: ~1 %/month at the lumbar spine (Springer review), 1-1.5 %/month at the hip.
 *  - Exercise time here counts minutes with heart rate in an aerobic zone (>= 105 bpm, matching
 *    the vitals zone used elsewhere in the HUD).
 */

/** Simple accelerometer step detector (peak on magnitude with refractory period). */
class StepDetector(private val thresholdMs2: Float = 1.15f, private val minIntervalMs: Long = 280L) {
  private var mean = 9.81f
  private var above = false
  private var lastStepMs = Long.MIN_VALUE / 2
  var steps: Int = 0
    private set

  /** @return true when a step was counted. */
  fun onAccel(x: Float, y: Float, z: Float, timeMs: Long): Boolean {
    val mag = sqrt(x * x + y * y + z * z)
    mean += 0.02f * (mag - mean)
    val dev = mag - mean
    if (!above && dev > thresholdMs2 && timeMs - lastStepMs >= minIntervalMs) {
      above = true
      lastStepMs = timeMs
      steps++
      return true
    }
    if (above && dev < thresholdMs2 * 0.3f) above = false
    return false
  }
}

data class BodyMaintenanceState(
  val steps: Int = 0,
  val earthEquivalentLoadSteps: Int = 0,
  val exerciseMinutes: Float = 0f,
  val loadGoalFraction: Float = 0f,
  val exerciseGoalFraction: Float = 0f,
  val atrophyRisk: String = "Build load",
  val advice: String = "Walk with purpose: every Mars step carries only 38% of Earth load.",
)

object GravityLoadModel {
  const val MARS_G_FRACTION = 0.38f
  /** Earth-equivalent loaded steps per day used as a simple target. */
  const val LOAD_STEP_GOAL = 7_500
  /** ISS countermeasure reference: ~2.5 h/day. */
  const val EXERCISE_GOAL_MIN = 150f

  fun evaluate(steps: Int, exerciseSeconds: Float): BodyMaintenanceState {
    val load = (steps * MARS_G_FRACTION).toInt()
    val exerciseMin = exerciseSeconds / 60f
    val loadFrac = (load.toFloat() / LOAD_STEP_GOAL).coerceIn(0f, 1f)
    val exFrac = (exerciseMin / EXERCISE_GOAL_MIN).coerceIn(0f, 1f)
    val combined = (loadFrac + exFrac) / 2f
    val (risk, advice) =
      when {
        combined >= 0.9f -> "On track" to "Countermeasure goal met for this sol. Keep loading tomorrow."
        combined >= 0.5f -> "Moderate" to "Add a loaded carry or squats to close today's gap."
        steps < 2_000 -> "High" to "Low loading today. Plan resistance exercise before sleep."
        else -> "Elevated" to "Keep walking at an aerobic pace to protect bone and muscle."
      }
    return BodyMaintenanceState(steps, load, exerciseMin, loadFrac, exFrac, risk, advice)
  }
}
