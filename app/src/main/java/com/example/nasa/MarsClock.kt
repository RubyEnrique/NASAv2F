package com.example.nasa

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * NASA-published algorithms, implemented offline (no network needed on the surface):
 *
 *  - Mars24 (NASA Goddard Institute for Space Studies; Allison & McEwen 2000): Mars Sol Date,
 *    Coordinated Mars Time, Local Mean / True Solar Time and solar longitude Ls.
 *  - JPL Solar System Dynamics "Keplerian Elements for Approximate Positions of the Major
 *    Planets" (Standish, Table 1, valid 1800-2050): heliocentric Earth and Mars positions, used
 *    for Earth-Mars distance and one-way signal (light-time) delay.
 */

data class MarsTime(
  val marsSolDate: Double,
  val solarLongitudeDeg: Double,
  val coordinatedMarsTimeHours: Double,
  val localMeanSolarTimeHours: Double,
  val localTrueSolarTimeHours: Double,
)

object Mars24 {
  /** Jezero crater, planetocentric east longitude. */
  const val JEZERO_EAST_LON_DEG = 77.45
  const val SOL_SECONDS = 88_775.244
  private const val TT_MINUS_UTC_SEC = 69.184 // 37 leap seconds + 32.184 s

  private val PBS = arrayOf(
    doubleArrayOf(0.0071, 2.2353, 49.409),
    doubleArrayOf(0.0057, 2.7543, 168.173),
    doubleArrayOf(0.0039, 1.1177, 191.837),
    doubleArrayOf(0.0037, 15.7866, 21.736),
    doubleArrayOf(0.0021, 2.1354, 15.704),
    doubleArrayOf(0.0020, 2.4694, 95.528),
    doubleArrayOf(0.0018, 32.8493, 49.095),
  )

  fun compute(utcMillis: Long, eastLongitudeDeg: Double = JEZERO_EAST_LON_DEG): MarsTime {
    val jdTt = 2440587.5 + utcMillis / 86_400_000.0 + TT_MINUS_UTC_SEC / 86_400.0
    val dt = jdTt - 2451545.0
    val m = Math.toRadians(19.3871 + 0.52402073 * dt)
    val alphaFms = 270.3871 + 0.524038496 * dt
    var pbs = 0.0
    for (p in PBS) pbs += p[0] * cos(Math.toRadians(0.985626 * dt / p[1] + p[2]))
    val vMinusM =
      (10.691 + 3.0e-7 * dt) * sin(m) + 0.623 * sin(2 * m) + 0.050 * sin(3 * m) + 0.005 * sin(4 * m) +
        0.0005 * sin(5 * m) + pbs
    val ls = mod(alphaFms + vMinusM, 360.0)
    val lsRad = Math.toRadians(ls)
    val eotDeg = 2.861 * sin(2 * lsRad) - 0.071 * sin(4 * lsRad) + 0.002 * sin(6 * lsRad) - vMinusM
    val msd = (jdTt - 2451549.5) / 1.0274912517 + 44796.0 - 0.0009626
    val mtc = mod(24.0 * msd, 24.0)
    val westLon = mod(360.0 - eastLongitudeDeg, 360.0)
    val lmst = mod(mtc - westLon / 15.0, 24.0)
    val ltst = mod(lmst + eotDeg / 15.0, 24.0)
    return MarsTime(msd, ls, mtc, lmst, ltst)
  }

  fun formatHours(h: Double): String {
    val totalMin = (h * 60.0).toInt()
    return String.format(java.util.Locale.US, "%02d:%02d", (totalMin / 60) % 24, totalMin % 60)
  }

  /** Northern-hemisphere season name for a solar longitude. */
  fun season(lsDeg: Double): String =
    when (mod(lsDeg, 360.0)) {
      in 0.0..<90.0 -> "Spring"
      in 90.0..<180.0 -> "Summer"
      in 180.0..<270.0 -> "Autumn"
      else -> "Winter"
    }

  internal fun mod(v: Double, m: Double): Double = ((v % m) + m) % m
}

object PlanetEphemeris {
  const val AU_KM = 149_597_870.7
  const val LIGHT_KM_PER_SEC = 299_792.458

  private class Elements(
    val a0: Double, val aR: Double,
    val e0: Double, val eR: Double,
    val i0: Double, val iR: Double,
    val l0: Double, val lR: Double,
    val w0: Double, val wR: Double,
    val n0: Double, val nR: Double,
  )

  // JPL SSD approximate positions, Table 1 (a in AU, angles in degrees, rates per Julian century).
  private val EARTH_MOON_BARYCENTER =
    Elements(1.00000261, 0.00000562, 0.01671123, -0.00004392, -0.00001531, -0.01294668,
      100.46457166, 35999.37244981, 102.93768193, 0.32327364, 0.0, 0.0)
  private val MARS =
    Elements(1.52371034, 0.00001847, 0.09339410, 0.00007882, 1.84969142, -0.00813131,
      -4.55343205, 19140.30268499, -23.94362959, 0.44441088, 49.55953891, -0.29257343)

  private fun position(el: Elements, utcMillis: Long): DoubleArray {
    val jd = 2440587.5 + utcMillis / 86_400_000.0
    val t = (jd - 2451545.0) / 36525.0
    val a = el.a0 + el.aR * t
    val e = el.e0 + el.eR * t
    val inc = Math.toRadians(el.i0 + el.iR * t)
    val l = el.l0 + el.lR * t
    val varpi = el.w0 + el.wR * t
    val node = Math.toRadians(el.n0 + el.nR * t)
    val omega = Math.toRadians(varpi) - node
    var mDeg = Mars24.mod(l - varpi, 360.0)
    if (mDeg > 180.0) mDeg -= 360.0
    val mRad = Math.toRadians(mDeg)
    var ecc = mRad + e * sin(mRad)
    repeat(8) { ecc -= (ecc - e * sin(ecc) - mRad) / (1 - e * cos(ecc)) }
    val xp = a * (cos(ecc) - e)
    val yp = a * sqrt(1 - e * e) * sin(ecc)
    val co = cos(omega)
    val so = sin(omega)
    val cn = cos(node)
    val sn = sin(node)
    val ci = cos(inc)
    val si = sin(inc)
    return doubleArrayOf(
      (co * cn - so * sn * ci) * xp + (-so * cn - co * sn * ci) * yp,
      (co * sn + so * cn * ci) * xp + (-so * sn + co * cn * ci) * yp,
      (so * si) * xp + (co * si) * yp,
    )
  }

  fun earthMarsDistanceAu(utcMillis: Long): Double {
    val e = position(EARTH_MOON_BARYCENTER, utcMillis)
    val m = position(MARS, utcMillis)
    val dx = m[0] - e[0]
    val dy = m[1] - e[1]
    val dz = m[2] - e[2]
    return sqrt(dx * dx + dy * dy + dz * dz)
  }

  fun oneWayLightTimeSec(utcMillis: Long): Double = earthMarsDistanceAu(utcMillis) * AU_KM / LIGHT_KM_PER_SEC

  /** Sun-Earth-Mars elongation proxy: true when Mars is within ~5 deg of the Sun (comms blackout). */
  fun isNearSolarConjunction(utcMillis: Long): Boolean {
    val e = position(EARTH_MOON_BARYCENTER, utcMillis)
    val m = position(MARS, utcMillis)
    val toSun = atan2(-e[1], -e[0])
    val toMars = atan2(m[1] - e[1], m[0] - e[0])
    var d = Math.toDegrees(toMars - toSun) % 360.0
    if (d > 180) d -= 360.0
    if (d < -180) d += 360.0
    return kotlin.math.abs(d) < 5.0
  }

  @Suppress("unused") private const val TWO_PI = 2 * PI
}
