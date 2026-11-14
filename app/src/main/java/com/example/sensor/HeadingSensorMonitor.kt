package com.example.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class HeadingSensorMonitor(
  context: Context,
  private val onHeadingChanged: (Float) -> Unit,
) : SensorEventListener {

  private val sensorManager =
    context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

  private val rotationSensor: Sensor? =
    sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
      ?: sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

  private val accelSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
  private val magneticSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

  private val rotationMatrix = FloatArray(9)
  private val gravity = FloatArray(3)
  private val geomagnetic = FloatArray(3)
  private var hasGravity = false
  private var hasGeomagnetic = false

  private var smoothSin = 0f
  private var smoothCos = 1f
  private var hasSmoothedHeading = false

  fun start() {
    val rot = rotationSensor
    if (rot != null) {
      sensorManager.registerListener(this, rot, SensorManager.SENSOR_DELAY_GAME)
    } else {
      accelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
      magneticSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }
  }

  fun stop() {
    sensorManager.unregisterListener(this)
  }

  override fun onSensorChanged(event: SensorEvent) {
    when (event.sensor.type) {
      Sensor.TYPE_ROTATION_VECTOR,
      Sensor.TYPE_GAME_ROTATION_VECTOR -> {
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        emitHeading(computeCameraHeadingDegrees(rotationMatrix))
      }
      Sensor.TYPE_ACCELEROMETER -> {
        System.arraycopy(event.values, 0, gravity, 0, minOf(event.values.size, 3))
        hasGravity = true
        if (hasGeomagnetic && SensorManager.getRotationMatrix(rotationMatrix, null, gravity, geomagnetic)) {
          emitHeading(computeCameraHeadingDegrees(rotationMatrix))
        }
      }
      Sensor.TYPE_MAGNETIC_FIELD -> {
        System.arraycopy(event.values, 0, geomagnetic, 0, minOf(event.values.size, 3))
        hasGeomagnetic = true
        if (hasGravity && SensorManager.getRotationMatrix(rotationMatrix, null, gravity, geomagnetic)) {
          emitHeading(computeCameraHeadingDegrees(rotationMatrix))
        }
      }
    }
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

  private fun emitHeading(rawDegrees: Float) {
    val rad = Math.toRadians(rawDegrees.toDouble())
    val s = sin(rad).toFloat()
    val c = cos(rad).toFloat()

    if (!hasSmoothedHeading) {
      smoothSin = s
      smoothCos = c
      hasSmoothedHeading = true
    } else {
      smoothSin += SMOOTHING_ALPHA * (s - smoothSin)
      smoothCos += SMOOTHING_ALPHA * (c - smoothCos)
    }

    val smoothedDeg = Math.toDegrees(atan2(smoothSin.toDouble(), smoothCos.toDouble())).toFloat()
    onHeadingChanged(normalizeDegrees(smoothedDeg))
  }

  companion object {
    private const val SMOOTHING_ALPHA = 0.18f

    /**
     * Computes world azimuth in degrees [0, 360) for the rear camera's horizontal pointing vector
     * (-Z axis in device coordinates) when held upright in landscape, falling back smoothly to the
     * landscape top edge (-X axis) if the device is held nearly flat.
     */
    fun computeCameraHeadingDegrees(rotationMatrix: FloatArray): Float {
      if (rotationMatrix.size < 9) return 0f
      val rearEast = -rotationMatrix[2]
      val rearNorth = -rotationMatrix[5]
      val rearHorizontalMag = hypot(rearEast, rearNorth)

      val (east, north) =
        if (rearHorizontalMag >= 0.22f) {
          rearEast to rearNorth
        } else {
          // Device is nearly flat; project landscape-top edge (-X) onto East/North plane.
          -rotationMatrix[0] to -rotationMatrix[3]
        }

      val deg = Math.toDegrees(atan2(east.toDouble(), north.toDouble())).toFloat()
      return normalizeDegrees(deg)
    }

    fun normalizeDegrees(degrees: Float): Float {
      val mod = degrees % 360f
      return if (mod < 0f) mod + 360f else mod
    }
  }
}
