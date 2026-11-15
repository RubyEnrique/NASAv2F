package com.example.crew

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt

/*
 * Crew link data model (pure Kotlin, unit-tested). Each HUD broadcasts a tiny status packet to the
 * other HUDs nearby (phone-to-phone, no internet). Receivers compute distance/bearing from their
 * own GPS fix, show crew on the compass and map, and raise an alert when a crewmate is in
 * emergency.
 */

data class CrewStatus(
  val id: String,
  val name: String,
  val lat: Double?,
  val lon: Double?,
  val headingDeg: Float,
  val heartRateBpm: Int?,
  val cognitiveLoad: Int,
  val emergency: Boolean,
  val reason: String,
  val sentAtMs: Long,
)

/** A crewmate as seen from this HUD. */
data class CrewMember(
  val id: String,
  val name: String,
  val distanceMeters: Float?,
  val bearingDeg: Float?,
  /** Position relative to this HUD (east/north metres) for the map; null without both GPS fixes. */
  val eastMeters: Float?,
  val northMeters: Float?,
  val heartRateBpm: Int?,
  val cognitiveLoad: Int,
  val emergency: Boolean,
  val reason: String,
  val lastSeenMs: Long,
  /** True when the crewmate sent a GPS position (distance still needs this HUD's own fix too). */
  val sentPosition: Boolean = false,
)

object CrewPacket {
  private const val VERSION = "C1"
  private const val SEP = "|"

  fun encode(s: CrewStatus): String =
    listOf(
        VERSION,
        s.id.clean(),
        s.name.clean(),
        s.lat?.let { "%.6f".format(java.util.Locale.US, it) } ?: "",
        s.lon?.let { "%.6f".format(java.util.Locale.US, it) } ?: "",
        "%.0f".format(java.util.Locale.US, s.headingDeg),
        s.heartRateBpm?.toString() ?: "",
        s.cognitiveLoad.toString(),
        if (s.emergency) "1" else "0",
        s.reason.clean(),
        s.sentAtMs.toString(),
      )
      .joinToString(SEP)

  fun decode(raw: String): CrewStatus? {
    val p = raw.split(SEP)
    if (p.size != 11 || p[0] != VERSION) return null
    return try {
      CrewStatus(
        id = p[1],
        name = p[2],
        lat = p[3].toDoubleOrNull(),
        lon = p[4].toDoubleOrNull(),
        headingDeg = p[5].toFloatOrNull() ?: 0f,
        heartRateBpm = p[6].toIntOrNull(),
        cognitiveLoad = p[7].toInt(),
        emergency = p[8] == "1",
        reason = p[9],
        sentAtMs = p[10].toLong(),
      )
    } catch (_: Throwable) {
      null
    }
  }

  private fun String.clean() = replace(SEP, "/").take(40)
}

object Geo {
  private const val EARTH_RADIUS_M = 6_371_000.0

  /** Local east/north offset in metres from (lat1, lon1) to (lat2, lon2); fine for crew ranges. */
  fun offsetMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Pair<Double, Double> {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val meanLat = Math.toRadians((lat1 + lat2) / 2.0)
    return (dLon * cos(meanLat) * EARTH_RADIUS_M) to (dLat * EARTH_RADIUS_M)
  }

  fun distanceMeters(east: Double, north: Double): Double = sqrt(east * east + north * north)

  fun bearingDeg(east: Double, north: Double): Double = (Math.toDegrees(atan2(east, north)) + 360.0) % 360.0
}

/** Keeps the latest packet per crewmate and forgets anyone silent for [staleMs]. */
class CrewRoster(private val selfId: String, private val staleMs: Long = 20_000L) {
  private val latest = LinkedHashMap<String, Pair<CrewStatus, Long>>()

  fun onPacket(status: CrewStatus, receivedAtMs: Long) {
    if (status.id == selfId) return
    latest[status.id] = status to receivedAtMs
  }

  fun members(nowMs: Long, selfLat: Double?, selfLon: Double?): List<CrewMember> {
    latest.entries.removeAll { nowMs - it.value.second > staleMs }
    return latest.values
      .map { (s, seen) ->
        val offset =
          if (selfLat != null && selfLon != null && s.lat != null && s.lon != null) Geo.offsetMeters(selfLat, selfLon, s.lat, s.lon)
          else null
        CrewMember(
          id = s.id,
          name = s.name,
          distanceMeters = offset?.let { Geo.distanceMeters(it.first, it.second).toFloat() },
          bearingDeg = offset?.let { Geo.bearingDeg(it.first, it.second).toFloat() },
          eastMeters = offset?.first?.toFloat(),
          northMeters = offset?.second?.toFloat(),
          heartRateBpm = s.heartRateBpm,
          cognitiveLoad = s.cognitiveLoad,
          emergency = s.emergency,
          reason = s.reason,
          lastSeenMs = seen,
          sentPosition = s.lat != null && s.lon != null,
        )
      }
      .sortedWith(compareByDescending<CrewMember> { it.emergency }.thenBy { it.distanceMeters ?: Float.MAX_VALUE })
  }
}
