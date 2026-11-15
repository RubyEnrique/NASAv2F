package com.example.crew

import android.content.Context
import android.net.wifi.WifiManager

/**
 * Android wrapper around [CrewUdpTransport]: holds a Wi-Fi multicast lock (many phones drop
 * broadcast packets without it) and a low-latency Wi-Fi lock so the radio keeps listening while
 * the HUD is idle. Runs for the whole life of the activity, not just while it is resumed.
 */
class LanCrewLink(
  context: Context,
  onStatus: (CrewStatus) -> Unit,
  private val onLinkChanged: (active: Boolean, localIp: String, error: String?) -> Unit,
) {
  private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
  private val multicastLock = wifi?.createMulticastLock("crew-link")?.apply { setReferenceCounted(false) }
  @Suppress("DEPRECATION")
  private val wifiLock =
    wifi?.createWifiLock(
        if (android.os.Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF,
        "crew-link",
      )
      ?.apply { setReferenceCounted(false) }
  private val transport = CrewUdpTransport(onPacket = { status, _ -> onStatus(status) })

  fun start() {
    try {
      multicastLock?.acquire()
      wifiLock?.acquire()
    } catch (_: Throwable) {}
    transport.start()
    report()
  }

  fun stop() {
    transport.stop()
    try {
      multicastLock?.release()
      wifiLock?.release()
    } catch (_: Throwable) {}
    report()
  }

  fun broadcast(status: CrewStatus) {
    if (!transport.isRunning) transport.start()
    transport.send(status)
    report()
  }

  private fun report() =
    onLinkChanged(transport.isRunning, CrewUdpTransport.localAddresses().firstOrNull() ?: "no network", transport.lastError)
}
