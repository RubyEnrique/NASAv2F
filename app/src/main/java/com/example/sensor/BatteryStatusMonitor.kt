package com.example.sensor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat

class BatteryStatusMonitor(
  context: Context,
  private val onBatteryChanged: (Int) -> Unit,
) {

  private val appContext = context.applicationContext
  private var isRegistered = false

  private val receiver =
    object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null) return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level >= 0 && scale > 0) {
          val percent = ((level * 100f) / scale.toFloat()).toInt().coerceIn(0, 100)
          onBatteryChanged(percent)
        }
      }
    }

  fun start() {
    readCurrentCapacity()
    if (!isRegistered) {
      val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
      val sticky =
        ContextCompat.registerReceiver(
          appContext,
          receiver,
          filter,
          ContextCompat.RECEIVER_NOT_EXPORTED,
        )
      isRegistered = true
      if (sticky != null) {
        val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level >= 0 && scale > 0) {
          val percent = ((level * 100f) / scale.toFloat()).toInt().coerceIn(0, 100)
          onBatteryChanged(percent)
        }
      }
    }
  }

  fun stop() {
    if (isRegistered) {
      try {
        appContext.unregisterReceiver(receiver)
      } catch (_: Throwable) {}
      isRegistered = false
    }
  }

  private fun readCurrentCapacity() {
    val manager = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return
    val capacity = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    if (capacity in 0..100) {
      onBatteryChanged(capacity)
    }
  }
}
