package com.example.nasa

import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray

/*
 * Live NASA space weather from DONKI (Space Weather Database Of Notifications, Knowledge,
 * Information; NASA CCMC via api.nasa.gov). Solar flares (FLR) and solar energetic particle
 * events (SEP) are pulled when the phone has connectivity and cached; the forecaster below turns
 * them into a simple, explainable 48 h SEP-risk estimate for the radiation tile.
 *
 * Caveat shown in the UI: DONKI events are observed from the Earth/L1 vantage point; Mars may be on
 * a different side of the Sun, so this is an advisory, not a measurement at the crew's location.
 */

enum class SpaceWeatherLevel(val label: String) {
  UNKNOWN("No data"),
  QUIET("Quiet"),
  ACTIVE("Active"),
  SEP("SEP event"),
}

data class SpaceWeatherEvent(val type: String, val timeMs: Long, val classType: String? = null)

data class SpaceWeatherState(
  val level: SpaceWeatherLevel = SpaceWeatherLevel.UNKNOWN,
  val sepRiskPercent: Int? = null,
  val flaresLast72h: Int = 0,
  val strongestFlare: String? = null,
  val sepLast7d: Int = 0,
  val fetchedAtMs: Long = 0L,
  val source: String = "NASA DONKI",
)

object SpaceWeatherForecaster {
  /**
   * Heuristic 48 h SEP probability: a quiet-Sun base rate plus increments for recent M/X-class
   * flares (larger flares are more often followed by proton events); an SEP already logged in
   * the last 48 h pins the estimate high. Not a validated operational forecast.
   */
  fun evaluate(events: List<SpaceWeatherEvent>, nowMs: Long, fetchedAtMs: Long): SpaceWeatherState {
    if (fetchedAtMs <= 0L) return SpaceWeatherState()
    val h72 = nowMs - 72 * 3_600_000L
    val h48 = nowMs - 48 * 3_600_000L
    val d7 = nowMs - 7 * 86_400_000L
    val flares = events.filter { it.type == "FLR" && it.timeMs >= h72 }
    val seps = events.filter { it.type == "SEP" && it.timeMs >= d7 }
    val recentSep = seps.any { it.timeMs >= h48 }
    var risk = 3.0
    var strongest: String? = null
    var strongestRank = -1.0
    for (f in flares) {
      val rank = flareRank(f.classType)
      if (rank > strongestRank) {
        strongestRank = rank
        strongest = f.classType
      }
      risk +=
        when {
          rank >= 10.0 -> 20.0 * (rank / 10.0).coerceAtMost(3.0) // X-class (X1 == 10)
          rank >= 1.0 -> 4.0 // M-class
          else -> 0.5
        }
    }
    if (seps.isNotEmpty()) risk += 10.0
    if (recentSep) risk = maxOf(risk, 90.0)
    val pct = risk.coerceIn(1.0, 95.0).toInt()
    val level =
      when {
        recentSep -> SpaceWeatherLevel.SEP
        strongestRank >= 1.0 || pct >= 20 -> SpaceWeatherLevel.ACTIVE
        else -> SpaceWeatherLevel.QUIET
      }
    return SpaceWeatherState(level, pct, flares.size, strongest, seps.size, fetchedAtMs)
  }

  /** GOES class to M-equivalents: C5 -> 0.5, M2 -> 2, X1 -> 10. */
  fun flareRank(classType: String?): Double {
    if (classType.isNullOrBlank()) return 0.0
    val mag = classType.drop(1).toDoubleOrNull() ?: 1.0
    return when (classType.first().uppercaseChar()) {
      'X' -> 10.0 * mag
      'M' -> mag
      'C' -> mag / 10.0
      else -> 0.0
    }
  }

  private val ISO =
    ThreadLocal.withInitial {
      SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }

  fun parseTime(s: String?): Long? =
    try {
      s?.let { ISO.get()!!.parse(it.take(16) + "Z")?.time }
    } catch (_: Throwable) {
      null
    }

  /** Parses DONKI FLR / SEP JSON arrays. */
  fun parse(type: String, json: String): List<SpaceWeatherEvent> {
    val arr = JSONArray(json)
    val out = ArrayList<SpaceWeatherEvent>(arr.length())
    for (i in 0 until arr.length()) {
      val o = arr.optJSONObject(i) ?: continue
      val time = parseTime(o.optString(if (type == "FLR") "peakTime" else "eventTime").ifBlank { o.optString("beginTime") }) ?: continue
      out.add(SpaceWeatherEvent(type, time, if (type == "FLR") o.optString("classType").ifBlank { null } else null))
    }
    return out
  }
}

/** Blocking DONKI fetch; call from a background dispatcher. */
class DonkiClient(private val apiKey: String) {
  fun fetch(nowMs: Long): List<SpaceWeatherEvent> {
    val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    val end = day.format(Date(nowMs))
    val start = day.format(Date(nowMs - 7 * 86_400_000L))
    return listOf("FLR", "SEP").flatMap { type ->
      val body = get("https://api.nasa.gov/DONKI/$type?startDate=$start&endDate=$end&api_key=$apiKey")
      if (body.isBlank()) emptyList() else SpaceWeatherForecaster.parse(type, body)
    }
  }

  private fun get(url: String): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    return try {
      conn.connectTimeout = 10_000
      conn.readTimeout = 15_000
      if (conn.responseCode != 200) "" else conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
      conn.disconnect()
    }
  }
}
