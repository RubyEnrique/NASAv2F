package com.example.suit

import kotlin.math.PI
import kotlin.math.cos

/*
 * Hazard 5 - Hostile / closed environments, using:
 *
 *  - Axiom Space AxEMU (NASA's Artemis partner suit, derived from NASA's xEMU): rated for EVAs of
 *    at least 8 h and for >= 2 h in the coldest permanently-shadowed lunar regions; helmet carries
 *    lights and an HD camera. Axiom has not published an operating pressure, so the NASA EMU/xEMU
 *    nominal 4.3 psid remains the pressure reference.
 *  - NASA Perseverance MEDA at Jezero crater: seasonal mean air temperature ~ -55 C with large
 *    day/night swings; surface pressure ~718 Pa on landing day, diurnal amplitude ~2-5 %.
 *    The diurnal curve below is a smooth approximation around those published values (warmest
 *    early afternoon, coldest before dawn), not a MEDA reading.
 *  - Habitability factors from NASA's "5 hazards": temperature, pressure, lighting, noise, space.
 */

object AxemuProfile {
  const val NAME = "AxEMU"
  const val RATED_EVA_HOURS = 8.0
  const val RATED_COLD_SOAK_MIN = 120.0
  const val NOMINAL_PRESSURE_PSI = 4.3f
  /** Assumed margin kept in hand when planning the walk back (not an Axiom figure). */
  const val RETURN_RESERVE_MIN = 30.0
  const val COLD_SOAK_BELOW_C = -70.0
  const val HELMET_LIGHT_BELOW_LUX = 40f
}

data class MarsOutside(val airTempC: Float, val pressurePa: Float, val isDaylight: Boolean)

object JezeroWeatherModel {
  private const val MEAN_C = -55.0
  private const val AMPLITUDE_C = 30.0
  private const val MEAN_PA = 718.0

  /** @param ltstHours local true solar time at Jezero (from Mars24). */
  fun predict(ltstHours: Double): MarsOutside {
    val temp = MEAN_C + AMPLITUDE_C * cos(2 * PI * (ltstHours - 14.0) / 24.0)
    // Thermal tide: pressure minimum near late afternoon, ~3 % amplitude.
    val pressure = MEAN_PA * (1.0 + 0.03 * cos(2 * PI * (ltstHours - 6.0) / 24.0))
    return MarsOutside(temp.toFloat(), pressure.toFloat(), ltstHours in 6.0..18.0)
  }
}

data class HabitabilityState(
  val suitTempC: Float = 21.1f,
  val suitPressurePsi: Float = 4.3f,
  val helmetCo2MmHg: Float = 1.8f,
  val lightLux: Float? = null,
  val helmetLightsAdvised: Boolean = false,
  val noiseDbfs: Float? = null,
  val noiseLabel: String = "—",
  val distanceFromBaseM: Float = 0f,
  val outside: MarsOutside = MarsOutside(-55f, 718f, true),
  val coldSoakMinutes: Double = 0.0,
  val evaHoursUsedFraction: Float = 0f,
)

object HabitabilityModel {
  fun noiseLabel(dbfs: Float?): String =
    when {
      dbfs == null -> "—"
      dbfs < -55f -> "Quiet"
      dbfs < -40f -> "Moderate"
      else -> "Loud"
    }
}
