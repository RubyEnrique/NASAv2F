package com.example.crew

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import java.util.concurrent.ConcurrentHashMap

/**
 * Phone-to-phone crew link over Google Nearby Connections (Bluetooth / BLE / Wi-Fi, no internet).
 * Every HUD advertises and discovers at the same time (P2P_CLUSTER mesh). To avoid both sides
 * dialling each other, only the HUD with the lexicographically smaller crew id requests the
 * connection; the other simply accepts.
 */
class CrewLink(
  context: Context,
  private val selfId: String,
  private val selfName: String,
  private val onStatus: (CrewStatus) -> Unit,
  private val onLinkChanged: (connected: Int, active: Boolean) -> Unit,
) {
  private val client = Nearby.getConnectionsClient(context.applicationContext)
  private val connected = ConcurrentHashMap.newKeySet<String>()
  private val endpointName = "$selfId:$selfName"
  @Volatile private var running = false

  private val payloadCallback =
    object : PayloadCallback() {
      override fun onPayloadReceived(endpointId: String, payload: Payload) {
        val bytes = payload.asBytes() ?: return
        CrewPacket.decode(String(bytes, Charsets.UTF_8))?.let(onStatus)
      }

      override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

  private val lifecycle =
    object : ConnectionLifecycleCallback() {
      override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
        // Crew HUDs auto-accept each other: hands-free, no pairing dialog mid-EVA.
        if (info.endpointName.substringBefore(':').isNotBlank()) client.acceptConnection(endpointId, payloadCallback)
      }

      override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
        if (result.status.isSuccess) connected.add(endpointId)
        report()
      }

      override fun onDisconnected(endpointId: String) {
        connected.remove(endpointId)
        report()
      }
    }

  private val discovery =
    object : EndpointDiscoveryCallback() {
      override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
        val otherId = info.endpointName.substringBefore(':')
        if (info.serviceId == SERVICE_ID && otherId != selfId && selfId < otherId && endpointId !in connected) {
          client.requestConnection(endpointName, endpointId, lifecycle).addOnFailureListener { Log.w(TAG, "request failed", it) }
        }
      }

      override fun onEndpointLost(endpointId: String) = Unit
    }

  fun start() {
    if (running) return
    running = true
    val strategy = Strategy.P2P_CLUSTER
    try {
      client.startAdvertising(endpointName, SERVICE_ID, lifecycle, AdvertisingOptions.Builder().setStrategy(strategy).build())
        .addOnFailureListener { Log.w(TAG, "advertising failed", it) }
      client.startDiscovery(SERVICE_ID, discovery, DiscoveryOptions.Builder().setStrategy(strategy).build())
        .addOnFailureListener { Log.w(TAG, "discovery failed", it) }
    } catch (t: Throwable) {
      Log.w(TAG, "Crew link unavailable", t)
      running = false
    }
    report()
  }

  fun stop() {
    if (!running) return
    running = false
    try {
      client.stopAdvertising()
      client.stopDiscovery()
      client.stopAllEndpoints()
    } catch (_: Throwable) {}
    connected.clear()
    report()
  }

  fun broadcast(status: CrewStatus) {
    if (!running || connected.isEmpty()) return
    try {
      client.sendPayload(connected.toList(), Payload.fromBytes(CrewPacket.encode(status).toByteArray(Charsets.UTF_8)))
    } catch (t: Throwable) {
      Log.w(TAG, "send failed", t)
    }
  }

  private fun report() = onLinkChanged(connected.size, running)

  companion object {
    private const val TAG = "CrewLink"
    const val SERVICE_ID = "com.example.spatialhud.crew"
  }
}
