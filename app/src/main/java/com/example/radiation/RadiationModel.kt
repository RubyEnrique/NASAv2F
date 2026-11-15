package com.example.radiation

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sin

/*
 * Mars surface radiation + geographic protection model. A phone has no ionising-radiation
 * detector, so dose is MODELLED from published measurements rather than sensed:
 *  - Curiosity RAD at Gale crater: average GCR dose-equivalent rate ~0.67 mSv/day (SwRI / Hassler
 *    et al., Science 2014) -> ~27.9 uSv/h. The planet itself blocks roughly half the sky.
 *  - Terrain matters: RAD saw a ~10-12% dose drop driving through Paraitepuy Pass between cliff
 *    walls. A habitat / rover is assumed (ASSUMED_BASE_SHIELDING) to cut dose much further.
 *  - Solar particle events (drills E-03, E-10) multiply the dose rate.
 *  - Reference limit: NASA-STD-3001 career effective-dose limit of 600 mSv.
 *
 * Geography uses the real GPS track: the EVA origin is treated as base / shelter, giving a live
 * bearing, distance and ETA back to cover plus the extra dose the walk back would cost.
 */

enum class RadiationStatus(val label: String) {
  NOMINAL("NOMINAL"),
  ELEVATED("ELEVATED"),
  STORM("SPE STORM"),
}

enum class ShelterStatus(val label: String) {
  EXPOSED("OPEN TERRAIN"),
  SHELTERED("AT BASE"),
}

data class RadiationState(
  val doseRateUsvPerHour: Float = RadiationModel.GCR_BASELINE_USV_PER_H,
  val evaDoseUsv: Float = 0f,
  val careerLimitFraction: Float = 0f,
  val status: RadiationStatus = RadiationStatus.NOMINAL,
  val shelter: ShelterStatus = ShelterStatus.SHELTERED,
  val distanceToBaseMeters: Float = 0f,
  /** True-north bearing from the crew member to base, degrees [0, 360). */
  val bearingToBaseDeg: Float = 0f,
  val etaToBaseSec: Int = 0,
  val returnDoseUsv: Float = 0f,
  val advice: String = "Dose nominal. Shielding by terrain not required.",
)

class RadiationModel {
  private var evaDoseUsv = 0.0

  fun reset() {
    evaDoseUsv = 0.0
  }

  /**
   * @param dtSec seconds since the previous step (dose integration).
   * @param eastMeters/northMeters current position relative to EVA origin (base).
   * @param speedMps current walking speed; a 1.0 m/s return pace is assumed when stationary.
   * @param doseRateMultiplier solar-particle-event multiplier from the active drill.
   * @param tSec monotonic time used for a small natural variation of the GCR rate.
   */
  fun step(
    dtSec: Double,
    eastMeters: Float,
    northMeters: Float,
    speedMps: Float,
    doseRateMultiplier: Float,
    tSec: Double,
  ): RadiationState {
    val distance = hypot(eastMeters, northMeters)
    val sheltered = distance <= BASE_RADIUS_METERS
    val gcr = GCR_BASELINE_USV_PER_H * (1.0 + 0.03 * sin(tSec * 2.0 * Math.PI / 600.0))
    val shielding = if (sheltered) ASSUMED_BASE_SHIELDING else 1.0
    val rate = gcr * doseRateMultiplier.coerceAtLeast(0f) * shielding
    evaDoseUsv += rate * (dtSec.coerceAtLeast(0.0) / 3600.0)

    val bearing =
      if (distance < 0.5f) 0f
      else ((Math.toDegrees(atan2(-eastMeters.toDouble(), -northMeters.toDouble())) + 360.0) % 360.0).toFloat()
    val pace = if (speedMps >= 0.3f) speedMps else ASSUMED_RETURN_PACE_MPS
    val eta = if (sheltered) 0 else (distance / pace).toInt()
    val exposedRate = gcr * doseRateMultiplier.coerceAtLeast(0f)
    val returnDose = if (sheltered) 0.0 else exposedRate * eta / 3600.0

    val status =
      when {
        doseRateMultiplier >= STORM_MULTIPLIER -> RadiationStatus.STORM
        doseRateMultiplier >= ELEVATED_MULTIPLIER -> RadiationStatus.ELEVATED
        else -> RadiationStatus.NOMINAL
      }
    val advice =
      when {
        status == RadiationStatus.STORM && !sheltered -> "SPE in progress. Return to base now. Use terrain walls en route."
        status == RadiationStatus.STORM -> "SPE in progress. Stay inside the shielded base."
        status == RadiationStatus.ELEVATED && !sheltered -> "Dose elevated. Shorten EVA. Prefer cliff-side routes (~10% less dose)."
        status == RadiationStatus.ELEVATED -> "Dose elevated. Remain near base."
        else -> "Dose nominal. Shielding by terrain not required."
      }

    return RadiationState(
      doseRateUsvPerHour = rate.toFloat(),
      evaDoseUsv = evaDoseUsv.toFloat(),
      careerLimitFraction = (evaDoseUsv / (CAREER_LIMIT_MSV * 1000.0)).toFloat(),
      status = status,
      shelter = if (sheltered) ShelterStatus.SHELTERED else ShelterStatus.EXPOSED,
      distanceToBaseMeters = distance,
      bearingToBaseDeg = bearing,
      etaToBaseSec = eta,
      returnDoseUsv = returnDose.toFloat(),
      advice = advice,
    )
  }

  companion object {
    /** 0.67 mSv/day (Curiosity RAD, Gale crater) expressed per hour. */
    const val GCR_BASELINE_USV_PER_H = 670f / 24f
    const val CAREER_LIMIT_MSV = 600.0
    const val BASE_RADIUS_METERS = 15f
    /** Assumed habitat/rover shielding factor (not a measured value). */
    const val ASSUMED_BASE_SHIELDING = 0.4
    const val ASSUMED_RETURN_PACE_MPS = 1.0f
    const val ELEVATED_MULTIPLIER = 2f
    const val STORM_MULTIPLIER = 5f
  }
}
