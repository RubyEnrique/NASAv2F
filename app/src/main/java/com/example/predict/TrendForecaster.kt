package com.example.predict

/**
 * Minimal predictive model: least-squares linear trend over a sliding time window.
 * Used for (a) cognitive-load forecasting and (b) EVA consumables depletion forecasting.
 * Deliberately simple and explainable: slope, projected value and time-to-threshold.
 */
class TrendForecaster(private val windowMs: Long, private val minSamples: Int = 10) {
  private val ts = ArrayDeque<Long>()
  private val vs = ArrayDeque<Double>()

  data class Forecast(
    val slopePerMin: Double,
    val projected: Double,
    val horizonMin: Double,
    /** Minutes until [threshold] is crossed in the trend direction, null if never / not trending. */
    val minutesToThreshold: Double?,
  )

  fun reset() {
    ts.clear()
    vs.clear()
  }

  fun add(timeMs: Long, value: Double) {
    if (ts.isNotEmpty() && timeMs <= ts.last()) return
    ts.addLast(timeMs)
    vs.addLast(value)
    while (ts.isNotEmpty() && timeMs - ts.first() > windowMs) {
      ts.removeFirst()
      vs.removeFirst()
    }
  }

  val size: Int
    get() = ts.size

  fun history(): List<Double> = vs.toList()

  /** @param rising true when the threshold is approached from below (load), false from above (O2). */
  fun forecast(horizonMin: Double, threshold: Double, rising: Boolean): Forecast? {
    if (ts.size < minSamples) return null
    val t0 = ts.first()
    val n = ts.size.toDouble()
    var sx = 0.0
    var sy = 0.0
    var sxx = 0.0
    var sxy = 0.0
    for (i in ts.indices) {
      val x = (ts[i] - t0) / 60_000.0
      val y = vs[i]
      sx += x
      sy += y
      sxx += x * x
      sxy += x * y
    }
    val den = n * sxx - sx * sx
    val slope = if (den <= 1e-12) 0.0 else (n * sxy - sx * sy) / den
    val intercept = (sy - slope * sx) / n
    val nowX = (ts.last() - t0) / 60_000.0
    val now = intercept + slope * nowX
    val projected = now + slope * horizonMin
    val toThreshold =
      when {
        rising && now >= threshold -> 0.0
        !rising && now <= threshold -> 0.0
        rising && slope > 1e-6 -> (threshold - now) / slope
        !rising && slope < -1e-6 -> (now - threshold) / -slope
        else -> null
      }
    return Forecast(slope, projected, horizonMin, toThreshold)
  }
}
