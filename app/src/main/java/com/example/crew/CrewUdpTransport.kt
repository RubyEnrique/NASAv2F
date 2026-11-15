package com.example.crew

import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Live counters shown on screen so link problems are diagnosable in the field. */
data class CrewLinkStats(
  val active: Boolean = false,
  val localIp: String = "",
  val sent: Long = 0,
  val heardSelf: Long = 0,
  val heardOthers: Long = 0,
  val lastPeerIp: String? = null,
  val error: String? = null,
)

/**
 * Same-network crew link over UDP, using three delivery paths at once because routers differ in
 * what they forward between phones:
 *  1. Broadcast to every local subnet (and 255.255.255.255).
 *  2. Multicast group [GROUP] (often forwarded where broadcast is filtered).
 *  3. Unicast: a sweep of the local /24 every [SWEEP_EVERY] sends, plus direct sends every time to
 *     every peer already heard (unicast gets through most "smart" Wi-Fi filtering).
 * Plain java.net so it is tested on the JVM; Android Wi-Fi locks live in [LanCrewLink].
 */
class CrewUdpTransport(
  private val selfId: String = "",
  private val port: Int = DEFAULT_PORT,
  private val onPacket: (CrewStatus, fromHost: String) -> Unit,
  private val extraTargets: () -> List<InetAddress> = { emptyList() },
  private val subnetSweep: Boolean = true,
) {
  @Volatile private var socket: MulticastSocket? = null
  private val sender = Executors.newSingleThreadExecutor()
  private val knownPeers = ConcurrentHashMap.newKeySet<InetAddress>()
  private val sentCount = AtomicLong()
  private val heardSelfCount = AtomicLong()
  private val heardOthersCount = AtomicLong()
  @Volatile private var lastPeerIp: String? = null
  @Volatile var lastError: String? = null
    private set
  private var sendCycle = 0L

  val isRunning: Boolean
    get() = socket?.isClosed == false

  fun stats(): CrewLinkStats =
    CrewLinkStats(isRunning, localAddresses().firstOrNull() ?: "no network", sentCount.get(), heardSelfCount.get(), heardOthersCount.get(), lastPeerIp, lastError)

  @Synchronized
  fun start() {
    if (isRunning) return
    val s =
      try {
        MulticastSocket(null).apply {
          reuseAddress = true
          broadcast = true
          bind(InetSocketAddress(this@CrewUdpTransport.port))
        }
      } catch (e: Throwable) {
        lastError = "bind: ${e.message}"
        return
      }
    joinGroupEverywhere(s)
    socket = s
    lastError = null
    Thread({
        val buf = ByteArray(MAX_PACKET)
        while (!s.isClosed) {
          try {
            val p = DatagramPacket(buf, buf.size)
            s.receive(p)
            val status = CrewPacket.decode(String(p.data, p.offset, p.length, Charsets.UTF_8)) ?: continue
            if (status.id == selfId) {
              heardSelfCount.incrementAndGet()
            } else {
              heardOthersCount.incrementAndGet()
              p.address?.let {
                knownPeers.add(it)
                lastPeerIp = it.hostAddress
              }
            }
            onPacket(status, p.address?.hostAddress ?: "?")
          } catch (_: SocketException) {
            break
          } catch (t: Throwable) {
            lastError = "rx: ${t.message}"
          }
        }
      }, "crew-udp-rx")
      .apply {
        isDaemon = true
        start()
      }
  }

  @Synchronized
  fun stop() {
    socket?.close()
    socket = null
  }

  fun send(status: CrewStatus) {
    val bytes = CrewPacket.encode(status).toByteArray(Charsets.UTF_8)
    sender.execute {
      if (!isRunning) start()
      val s = socket ?: return@execute
      val cycle = sendCycle++
      val targets = LinkedHashSet<InetAddress>()
      targets += broadcastAddresses()
      targets += InetAddress.getByName(GROUP)
      targets += knownPeers
      targets += extraTargets()
      if (subnetSweep && cycle % SWEEP_EVERY == 0L) targets += sweepTargets()
      var ok = false
      for (addr in targets) {
        try {
          s.send(DatagramPacket(bytes, bytes.size, addr, port))
          ok = true
        } catch (t: Throwable) {
          lastError = "tx ${addr.hostAddress}: ${t.message}"
        }
      }
      if (ok) sentCount.incrementAndGet()
    }
  }

  private fun joinGroupEverywhere(s: MulticastSocket) {
    val group = InetSocketAddress(InetAddress.getByName(GROUP), port)
    for (ni in upInterfaces()) {
      try {
        if (ni.supportsMulticast()) s.joinGroup(group, ni)
      } catch (_: Throwable) {}
    }
  }

  companion object {
    const val DEFAULT_PORT = 47811
    const val GROUP = "239.255.47.11"
    const val SWEEP_EVERY = 3L
    private const val MAX_PACKET = 1024

    private fun upInterfaces(): List<NetworkInterface> =
      try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { it.isUp && !it.isLoopback }
      } catch (_: Throwable) {
        emptyList()
      }

    /** Subnet broadcast address of every active IPv4 interface, plus the limited broadcast. */
    fun broadcastAddresses(): List<InetAddress> {
      val out = ArrayList<InetAddress>()
      for (ni in upInterfaces()) for (ia in ni.interfaceAddresses) ia.broadcast?.let(out::add)
      out.add(InetAddress.getByName("255.255.255.255"))
      return out
    }

    /** Every host address of each local IPv4 network, capped at a /24 so it stays small. */
    fun sweepTargets(): List<InetAddress> {
      val out = ArrayList<InetAddress>()
      for (ni in upInterfaces().filter { isLocalLan(it.name) }) {
        for (ia in ni.interfaceAddresses) {
          val a = ia.address as? Inet4Address ?: continue
          if (!a.isSiteLocalAddress) continue
          val own = a.address
          for (host in 1..254) {
            if (host == (own[3].toInt() and 0xFF)) continue
            out.add(InetAddress.getByAddress(byteArrayOf(own[0], own[1], own[2], host.toByte())))
          }
        }
      }
      return out
    }

    /** Wi-Fi, hotspot and ethernet interfaces only, never mobile data (rmnet/ccmni/...). */
    fun isLocalLan(name: String): Boolean = Regex("^(wlan|swlan|ap|softap|eth|en|wl)").containsMatchIn(name)

    fun localAddresses(): List<String> =
      upInterfaces().flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress } }
  }
}
