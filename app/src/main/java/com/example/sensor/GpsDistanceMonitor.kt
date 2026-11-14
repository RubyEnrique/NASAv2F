package com.example.sensor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
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
  private val onFixStatusChanged: (Boolean) -> Unit = {},
  private val onVectorDelta: (deltaEastMeters: Float, deltaNorthMeters: Float) -> Unit = { _, _ -> },
) {

  private val appContext = context.applicationContext
  private val fusedClient = LocationServices.getFusedLocationProviderClient(appContext)
  private val locationManager =
    appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

  private var isStarted = false
  private var lastAnchorLocation: Location? = null
  private var lastAnchorTimeMs: Long = 0L
  private var lastAnchorElapsedNanos: Long = 0L
  private var hasReportedFix = false

  private val locationCallback =
    object : LocationCallback() {
      override fun onLocationResult(result: LocationResult) {
        val nowMs = SystemClock.uptimeMillis()
        for (location in result.locations) {
          handleLocation(location, nowMs)
        }
      }
    }

  private val platformLocationListener =
    object : LocationListener {
      override fun onLocationChanged(location: Location) {
        handleLocation(location, SystemClock.uptimeMillis())
      }

      @Deprecated("Deprecated in Android API 29")
      override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

      override fun onProviderEnabled(provider: String) {}

      override fun onProviderDisabled(provider: String) {}
    }

  @SuppressLint("MissingPermission")
  fun start() {
    if (isStarted) return
    val hasFine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    val hasCoarse = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (!hasFine && !hasCoarse) {
      return
    }

    var startedAny = false

    val request =
      LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
        .setMinUpdateIntervalMillis(FASTEST_INTERVAL_MS)
        .setWaitForAccurateLocation(false)
        .build()

    try {
      fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
      startedAny = true
      fusedClient.lastLocation.addOnSuccessListener { location ->
        if (location != null) {
          seedAnchorIfFresh(location)
        }
      }
      fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
        .addOnSuccessListener { location ->
          if (location != null) {
            handleLocation(location, SystemClock.uptimeMillis())
          }
        }
    } catch (t: Throwable) {
      Log.w(TAG, "Fused location provider unavailable, relying on LocationManager", t)
    }

    try {
      val mgr = locationManager
      if (mgr != null) {
        val providers = buildList {
          if (hasFine && mgr.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            add(LocationManager.GPS_PROVIDER)
          }
          if (mgr.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            add(LocationManager.NETWORK_PROVIDER)
          }
        }

        for (provider in providers) {
          mgr.requestLocationUpdates(
            provider,
            UPDATE_INTERVAL_MS,
            0f,
            platformLocationListener,
            Looper.getMainLooper(),
          )
          startedAny = true
          mgr.getLastKnownLocation(provider)?.let { seedAnchorIfFresh(it) }
        }
      }
    } catch (t: Throwable) {
      Log.w(TAG, "Unable to register LocationManager fallback updates", t)
    }

    isStarted = startedAny
  }

  fun stop() {
    if (!isStarted) return
    try {
      fusedClient.removeLocationUpdates(locationCallback)
    } catch (t: Throwable) {
      Log.w(TAG, "Error stopping Fused location updates", t)
    }
    try {
      locationManager?.removeUpdates(platformLocationListener)
    } catch (t: Throwable) {
      Log.w(TAG, "Error stopping LocationManager updates", t)
    }
    isStarted = false
  }

  private fun hasPermission(permission: String): Boolean {
    return ContextCompat.checkSelfPermission(appContext, permission) ==
      PackageManager.PERMISSION_GRANTED
  }

  private fun seedAnchorIfFresh(location: Location) {
    if (lastAnchorLocation != null) return
    val accuracy = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE
    if (accuracy > MAX_INDOOR_ACCURACY_METERS) return

    val ageMs =
      (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
    if (location.elapsedRealtimeNanos > 0L && ageMs > MAX_SEED_AGE_MS) return

    val nowMs = SystemClock.uptimeMillis()
    lastAnchorLocation = location
    lastAnchorTimeMs = nowMs
    lastAnchorElapsedNanos = location.elapsedRealtimeNanos
    markFixActive()
    val initialSpeed =
      if (location.hasSpeed() && location.speed >= MIN_MOVING_SPEED_MPS) location.speed else 0f
    onLocationSample(0f, initialSpeed, nowMs)
  }

  private fun markFixActive() {
    if (!hasReportedFix) {
      hasReportedFix = true
      onFixStatusChanged(true)
    }
  }

  private fun handleLocation(location: Location, nowMs: Long) {
    val accuracy = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE
    val rawSpeed = if (location.hasSpeed()) location.speed else -1f

    // Ignore out-of-order fixes older than the current anchor.
    if (
      lastAnchorElapsedNanos > 0L &&
        location.elapsedRealtimeNanos > 0L &&
        location.elapsedRealtimeNanos + STALE_FIX_TOLERANCE_NANOS < lastAnchorElapsedNanos
    ) {
      return
    }

    val anchor = lastAnchorLocation
    if (anchor == null) {
      if (accuracy <= MAX_INDOOR_ACCURACY_METERS) {
        lastAnchorLocation = location
        lastAnchorTimeMs = nowMs
        lastAnchorElapsedNanos = location.elapsedRealtimeNanos
        markFixActive()
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
        allowIndoorFallback = true,
      )

    if (filtered.acceptedAsAnchor) {
      val bearingRad = Math.toRadians(anchor.bearingTo(location).toDouble())
      val deltaEast = (filtered.deltaMeters * kotlin.math.sin(bearingRad)).toFloat()
      val deltaNorth = (filtered.deltaMeters * kotlin.math.cos(bearingRad)).toFloat()
      lastAnchorLocation = location
      lastAnchorTimeMs = nowMs
      lastAnchorElapsedNanos = location.elapsedRealtimeNanos
      markFixActive()
      onVectorDelta(deltaEast, deltaNorth)
      onLocationSample(filtered.deltaMeters, filtered.speedMps, nowMs)
    } else if (accuracy <= MAX_INDOOR_ACCURACY_METERS) {
      markFixActive()
      // Stationary sample within jitter threshold: refresh speed smoothly.
      onLocationSample(0f, filtered.speedMps, nowMs)
    }
  }

  companion object {
    private const val TAG = "GpsDistanceMonitor"
    private const val UPDATE_INTERVAL_MS = 1500L
    private const val FASTEST_INTERVAL_MS = 800L
    private const val MAX_SEED_AGE_MS = 60_000L
    private const val STALE_FIX_TOLERANCE_NANOS = 1_000_000_000L

    const val MAX_ACCEPTED_ACCURACY_METERS = 25f
    const val MAX_INDOOR_ACCURACY_METERS = 65f
    const val MIN_MOVEMENT_DELTA_METERS = 2.5f
    const val MAX_PLAUSIBLE_SPEED_MPS = 35f
    const val MIN_MOVING_SPEED_MPS = 0.4f

    internal fun filterLocationDelta(
      rawDistanceMeters: Float,
      elapsedSec: Float,
      accuracyMeters: Float,
      reportedSpeedMps: Float,
      allowIndoorFallback: Boolean = false,
    ): FilteredGpsSample {
      val accuracyCeiling =
        if (allowIndoorFallback) MAX_INDOOR_ACCURACY_METERS else MAX_ACCEPTED_ACCURACY_METERS
      if (accuracyMeters > accuracyCeiling || elapsedSec <= 0f) {
        return FilteredGpsSample(deltaMeters = 0f, speedMps = 0f, acceptedAsAnchor = false)
      }

      val impliedSpeed = rawDistanceMeters / elapsedSec
      if (impliedSpeed > MAX_PLAUSIBLE_SPEED_MPS) {
        return FilteredGpsSample(deltaMeters = 0f, speedMps = 0f, acceptedAsAnchor = false)
      }

      val accuracyScale = if (accuracyMeters > MAX_ACCEPTED_ACCURACY_METERS) 0.55f else 0.45f
      val jitterFloor = maxOf(MIN_MOVEMENT_DELTA_METERS, accuracyMeters * accuracyScale)
      if (rawDistanceMeters < jitterFloor) {
        val speed = if (reportedSpeedMps >= MIN_MOVING_SPEED_MPS) reportedSpeedMps else 0f
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
