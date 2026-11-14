package com.example.sensor

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

data class FilteredGpsSample(
  val deltaMeters: Float,
  val speedMps: Float,
  val acceptedAsAnchor: Boolean,
)

class GpsDistanceMonitor(
  context: Context,
  private val onLocationSample: (deltaMeters: Float, speedMps: Float, timestampMs: Long) -> Unit,
) {

  private val fusedClient = LocationServices.getFusedLocationProviderClient(context.applicationContext)
  private var isStarted = false
  private var lastAnchorLocation: Location? = null
  private var lastAnchorTimeMs: Long = 0L

  private val locationCallback =
    object : LocationCallback() {
      override fun onLocationResult(result: LocationResult) {
        val nowMs = SystemClock.uptimeMillis()
        for (location in result.locations) {
          handleLocation(location, nowMs)
        }
      }
    }

  @SuppressLint("MissingPermission")
  fun start() {
    if (isStarted) return
    val request =
      LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
        .setMinUpdateIntervalMillis(FASTEST_INTERVAL_MS)
        .setWaitForAccurateLocation(false)
        .build()
    try {
      fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
      isStarted = true
    } catch (t: Throwable) {
      Log.w(TAG, "Unable to start GPS location updates", t)
    }
  }

  fun stop() {
    if (!isStarted) return
    try {
      fusedClient.removeLocationUpdates(locationCallback)
    } catch (t: Throwable) {
      Log.w(TAG, "Error stopping GPS updates", t)
    } finally {
      isStarted = false
    }
  }

  private fun handleLocation(location: Location, nowMs: Long) {
    val accuracy = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE
    val rawSpeed = if (location.hasSpeed()) location.speed else -1f
    val anchor = lastAnchorLocation

    if (anchor == null) {
      if (accuracy <= MAX_ACCEPTED_ACCURACY_METERS) {
        lastAnchorLocation = location
        lastAnchorTimeMs = nowMs
        val initialSpeed = if (rawSpeed >= MIN_MOVING_SPEED_MPS) rawSpeed else 0f
        onLocationSample(0f, initialSpeed, nowMs)
      }
      return
    }

    val rawDistance = anchor.distanceTo(location)
    val elapsedSec = ((nowMs - lastAnchorTimeMs).coerceAtLeast(250L)) / 1000f
    val filtered =
      filterLocationDelta(
        rawDistanceMeters = rawDistance,
        elapsedSec = elapsedSec,
        accuracyMeters = accuracy,
        reportedSpeedMps = rawSpeed,
      )

    if (filtered.acceptedAsAnchor) {
      lastAnchorLocation = location
      lastAnchorTimeMs = nowMs
      onLocationSample(filtered.deltaMeters, filtered.speedMps, nowMs)
    } else if (accuracy <= MAX_ACCEPTED_ACCURACY_METERS) {
      // Stationary sample within jitter threshold: refresh speed to zero smoothly.
      onLocationSample(0f, filtered.speedMps, nowMs)
    }
  }

  companion object {
    private const val TAG = "GpsDistanceMonitor"
    private const val UPDATE_INTERVAL_MS = 1500L
    private const val FASTEST_INTERVAL_MS = 800L

    const val MAX_ACCEPTED_ACCURACY_METERS = 25f
    const val MIN_MOVEMENT_DELTA_METERS = 2.5f
    const val MAX_PLAUSIBLE_SPEED_MPS = 35f
    const val MIN_MOVING_SPEED_MPS = 0.4f

    internal fun filterLocationDelta(
      rawDistanceMeters: Float,
      elapsedSec: Float,
      accuracyMeters: Float,
      reportedSpeedMps: Float,
    ): FilteredGpsSample {
      if (accuracyMeters > MAX_ACCEPTED_ACCURACY_METERS || elapsedSec <= 0f) {
        return FilteredGpsSample(deltaMeters = 0f, speedMps = 0f, acceptedAsAnchor = false)
      }

      val impliedSpeed = rawDistanceMeters / elapsedSec
      if (impliedSpeed > MAX_PLAUSIBLE_SPEED_MPS) {
        return FilteredGpsSample(deltaMeters = 0f, speedMps = 0f, acceptedAsAnchor = false)
      }

      val jitterFloor = maxOf(MIN_MOVEMENT_DELTA_METERS, accuracyMeters * 0.45f)
      if (rawDistanceMeters < jitterFloor) {
        val speed =
          if (reportedSpeedMps >= MIN_MOVING_SPEED_MPS) reportedSpeedMps else 0f
        return FilteredGpsSample(deltaMeters = 0f, speedMps = speed, acceptedAsAnchor = false)
      }

      val resolvedSpeed =
        if (reportedSpeedMps >= MIN_MOVING_SPEED_MPS) reportedSpeedMps else impliedSpeed
      return FilteredGpsSample(
        deltaMeters = rawDistanceMeters,
        speedMps = resolvedSpeed.coerceAtMost(MAX_PLAUSIBLE_SPEED_MPS),
        acceptedAsAnchor = true,
      )
    }
  }
}
