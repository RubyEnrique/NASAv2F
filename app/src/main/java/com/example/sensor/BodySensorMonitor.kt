package com.example.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.example.body.StepDetector

/** Accelerometer step counting (no extra permission needed) and ambient light for helmet lights. */
class BodySensorMonitor(
  context: Context,
  private val onStep: () -> Unit,
  private val onLightLux: (Float) -> Unit,
) : SensorEventListener {
  private val sensorManager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
  private val accel: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
  private val light: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
  private val detector = StepDetector()
  private var lastLuxMs = 0L

  fun start() {
    accel?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    light?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
  }

  fun stop() = sensorManager.unregisterListener(this)

  override fun onSensorChanged(event: SensorEvent) {
    val nowMs = event.timestamp / 1_000_000L
    when (event.sensor.type) {
      Sensor.TYPE_ACCELEROMETER -> if (detector.onAccel(event.values[0], event.values[1], event.values[2], nowMs)) onStep()
      Sensor.TYPE_LIGHT ->
        if (nowMs - lastLuxMs > 1_000L) {
          lastLuxMs = nowMs
          onLightLux(event.values[0])
        }
    }
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
