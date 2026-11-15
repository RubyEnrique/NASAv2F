package com.example.crew

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.util.concurrent.Executors

/**
 * Same-network crew link: every HUD sends its status as a small UDP datagram to the broadcast
 * address of each local network (Wi-Fi router or phone hotspot, no internet needed) and listens
 * on the same port. Plain java.net so it runs and is tested on the JVM; Android-specific Wi-Fi
 * locks live in [LanCrewLink].
 */
class CrewUdpTransport(
  private val port: Int = DEFAULT_PORT,
  private val onPacket: (CrewStatus, fromHost: String) -> Unit,
  private val extraTargets: () -> List<InetAddress> = { emptyList() },
) {
  @Volatile private var socket: DatagramSocket? = null
  private var receiver: Thread? = null
  private val sender = Executors.newSingleThreadExecutor()
  @Volatile var lastError: String? = null
    private set
  @Volatile var packetsReceived: Long = 0
    private set

  val isRunning: Boolean
    get() = socket?.isClosed == false

  fun start() {
    if (isRunning) return
    val s =
      try {
        DatagramSocket(null).apply {
          reuseAddress = true
          broadcast = true
          bind(InetSocketAddress(this@CrewUdpTransport.port))
        }
      } catch (e: SocketException) {
        lastError = "bind ${e.message}"
        return
      }
    socket = s
    lastError = null
    receiver =
      Thread({
          val buf = ByteArray(MAX_PACKET)
          while (!s.isClosed) {
            try {
              val p = DatagramPacket(buf, buf.size)
              s.receive(p)
              val text = String(p.data, p.offset, p.length, Charsets.UTF_8)
              CrewPacket.decode(text)?.let {
                packetsReceived++
                onPacket(it, p.address?.hostAddress ?: "?")
              }
            } catch (_: SocketException) {
              break // closed
            } catch (t: Throwable) {
              lastError = "rx ${t.message}"
            }
          }
        }, "crew-udp-rx")
        .apply {
          isDaemon = true
          start()
        }
  }

  fun stop() {
    socket?.close()
    socket = null
    receiver = null
  }

  fun send(status: CrewStatus) {
    val s = socket ?: return
    val bytes = CrewPacket.encode(status).toByteArray(Charsets.UTF_8)
    sender.execute {
      val targets = (broadcastAddresses() + extraTargets()).distinct()
      for (addr in targets) {
        try {
          s.send(DatagramPacket(bytes, bytes.size, addr, port))
        } catch (t: Throwable) {
          lastError = "tx ${addr.hostAddress}: ${t.message}"
        }
      }
    }
  }

  companion object {
    const val DEFAULT_PORT = 47811
    private const val MAX_PACKET = 1024

    /** Subnet broadcast address of every active IPv4 interface, plus the limited broadcast. */
    fun broadcastAddresses(): List<InetAddress> {
      val out = ArrayList<InetAddress>()
      try {
        for (ni in NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) {
          if (!ni.isUp || ni.isLoopback) continue
          for (ia in ni.interfaceAddresses) ia.broadcast?.let(out::add)
        }
      } catch (_: Throwable) {}
      out.add(InetAddress.getByName("255.255.255.255"))
      return out
    }

    /** Local IPv4 addresses, for the on-screen link status. */
    fun localAddresses(): List<String> =
      try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
          .filter { it.isUp && !it.isLoopback }
          .flatMap { ni -> ni.inetAddresses.toList().filter { it.address.size == 4 }.map { it.hostAddress ?: "" } }
          .filter { it.isNotBlank() }
      } catch (_: Throwable) {
        emptyList()
      }
  }
}
