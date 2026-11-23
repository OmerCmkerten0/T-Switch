package com.callagent.gateway.localsend

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Pure-Kotlin mDNS (Bonjour, RFC 6762) responder on `224.0.0.251:5353`.
 *
 * Advertises `tswitch.local` (and `gm8.local`) pointing to GM 8's current active
 * local IPv4 address — working seamlessly on BOTH GM 8's Hotspot AND a shared Wi-Fi
 * network without requiring router DNS changes or static IPs.
 */
object LocalMdnsResponder {
    private const val TAG = "LocalMdnsResponder"
    const val PRIMARY_HOSTNAME = "tswitch.local"
    private val SUPPORTED_HOSTNAMES = setOf("tswitch.local", "gm8.local")
    private const val MDNS_ADDR = "224.0.0.251"
    private const val MDNS_PORT = 5353
    private const val TTL_SECONDS = 10
    private const val IFACE_CHECK_INTERVAL_MS = 5_000L
    private val IGNORED_IFACE_PREFIXES = listOf("rmnet", "ccmni", "tun", "dummy", "lo", "p2p")

    private val running = AtomicBoolean(false)
    @Volatile private var socket: MulticastSocket? = null
    @Volatile private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var lastResolvedIp: String = ""
    @Volatile private var queriesAnswered: Long = 0L

    fun start(context: Context) {
        if (!running.compareAndSet(false, true)) {
            announceNow(context)
            return
        }
        val appCtx = context.applicationContext
        acquireMulticastLock(appCtx)

        thread(name = "tswitch-mdns", isDaemon = true) {
            val mdnsGroup = InetAddress.getByName(MDNS_ADDR)
            while (running.get()) {
                var sock: MulticastSocket? = null
                try {
                    sock = MulticastSocket(null).apply {
                        reuseAddress = true
                        bind(InetSocketAddress(MDNS_PORT))
                        timeToLive = 255
                        soTimeout = 5_000
                    }
                    joinActiveInterface(appCtx, sock, mdnsGroup)
                    socket = sock
                    announceNow(appCtx)
                    Log.i(TAG, "mDNS responder listening on $MDNS_ADDR:$MDNS_PORT for $PRIMARY_HOSTNAME")

                    val buf = ByteArray(1500)
                    val packet = DatagramPacket(buf, buf.size)
                    var lastIfaceCheckMs = System.currentTimeMillis()
                    while (running.get()) {
                        try {
                            packet.length = buf.size
                            sock.receive(packet)
                            handlePacket(appCtx, sock, mdnsGroup, packet)
                        } catch (_: java.net.SocketTimeoutException) {
                            // Interface refresh runs below on a 5s timer regardless of receive traffic
                        }
                        val now = System.currentTimeMillis()
                        if (now - lastIfaceCheckMs >= IFACE_CHECK_INTERVAL_MS) {
                            lastIfaceCheckMs = now
                            val current = resolveCurrentIp(appCtx)
                            if (current != null && current.first != lastResolvedIp) {
                                joinActiveInterface(appCtx, sock, mdnsGroup)
                                announceNow(appCtx)
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (running.get()) {
                        Log.w(TAG, "mDNS loop error: ${e.message}")
                        try { Thread.sleep(2_000) } catch (_: InterruptedException) { break }
                    }
                } finally {
                    try { sock?.close() } catch (_: Exception) {}
                    if (socket === sock) socket = null
                }
            }
        }
    }

    fun stop() {
        running.set(false)
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        releaseMulticastLock()
    }

    fun announceNow(context: Context) {
        val sock = socket ?: return
        val ipPair = resolveCurrentIp(context) ?: return
        lastResolvedIp = ipPair.first
        thread(name = "tswitch-mdns-adv", isDaemon = true) {
            try {
                val mdnsGroup = InetAddress.getByName(MDNS_ADDR)
                for (host in SUPPORTED_HOSTNAMES) {
                    val resp = buildAResponse(host, ipPair.second)
                    sock.send(DatagramPacket(resp, resp.size, mdnsGroup, MDNS_PORT))
                }
            } catch (e: Exception) {
                Log.d(TAG, "mDNS announce failed: ${e.message}")
            }
        }
    }

    fun statusSummary(context: Context): String {
        val ip = resolveCurrentIp(context)?.first ?: "0.0.0.0"
        val state = if (running.get() && socket != null) "Aktif ✅" else "Kapalı ❌"
        return "$PRIMARY_HOSTNAME → $ip ($state, yanıt=$queriesAnswered)"
    }

    private fun handlePacket(
        context: Context,
        sock: MulticastSocket,
        mdnsGroup: InetAddress,
        packet: DatagramPacket
    ) {
        val len = packet.length
        if (len < 12) return
        val data = packet.data
        val flags = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        // QR bit (0x8000): 0 = query, 1 = response
        if ((flags and 0x8000) != 0) return

        val qdCount = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
        if (qdCount <= 0 || qdCount > 32) return

        var offset = 12
        var matchedHost: String? = null
        var unicastReply = false

        for (i in 0 until qdCount) {
            val (name, nextOffset) = readDnsName(data, offset, len) ?: break
            offset = nextOffset
            if (offset + 4 > len) break
            val qtype = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            val qclass = ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
            offset += 4

            val lower = name.lowercase().trimEnd('.')
            if (lower in SUPPORTED_HOSTNAMES && (qtype == 1 || qtype == 255)) {
                matchedHost = lower
                unicastReply = (qclass and 0x8000) != 0
                break
            }
        }

        val host = matchedHost ?: return
        val ipPair = resolveCurrentIp(context) ?: return
        lastResolvedIp = ipPair.first
        val resp = buildAResponse(host, ipPair.second)
        queriesAnswered++

        if (unicastReply && packet.address != null) {
            runCatching {
                sock.send(DatagramPacket(resp, resp.size, packet.address, packet.port))
            }
        }
        runCatching {
            sock.send(DatagramPacket(resp, resp.size, mdnsGroup, MDNS_PORT))
        }
        Log.d(TAG, "mDNS answered $host -> ${ipPair.first} (for ${packet.address?.hostAddress})")
    }

    private fun joinActiveInterface(context: Context, sock: MulticastSocket, group: InetAddress) {
        val hs = HotspotHelper.detectHotspot(context)
        val targetIfaceName = when {
            hs.active && hs.ifaceName.isNotEmpty() -> hs.ifaceName
            hs.wifiIfaceName.isNotEmpty() -> hs.wifiIfaceName
            else -> hs.ifaceName
        }
        val iface = if (targetIfaceName.isNotEmpty()) {
            runCatching { NetworkInterface.getByName(targetIfaceName) }.getOrNull()
        } else null

        if (iface != null) {
            runCatching { sock.networkInterface = iface }
            runCatching { sock.joinGroup(InetSocketAddress(group, MDNS_PORT), iface) }
        } else {
            runCatching { sock.joinGroup(group) }
        }
    }

    private fun resolveCurrentIp(context: Context): Pair<String, ByteArray>? {
        val hs = HotspotHelper.detectHotspot(context)
        val preferredIp = when {
            hs.active && hs.ipAddress.isNotEmpty() && hs.ipAddress != "0.0.0.0" -> hs.ipAddress
            hs.wifiIpAddress.isNotEmpty() && hs.wifiIpAddress != "0.0.0.0" -> hs.wifiIpAddress
            else -> ""
        }
        if (preferredIp.isNotEmpty()) {
            val addr = runCatching { InetAddress.getByName(preferredIp) as? Inet4Address }.getOrNull()
            if (addr != null) return preferredIp to addr.address
        }
        val fallback = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { iface ->
                    if (iface.isLoopback || !iface.isUp) return@filter false
                    val lower = iface.name.lowercase()
                    IGNORED_IFACE_PREFIXES.none { lower.startsWith(it) }
                }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
        }.getOrNull() ?: return null
        return (fallback.hostAddress ?: return null) to fallback.address
    }

    private fun readDnsName(data: ByteArray, start: Int, limit: Int): Pair<String, Int>? {
        val sb = StringBuilder()
        var pos = start
        var jumped = false
        var endPos = -1
        var safety = 0

        while (pos < limit && safety++ < 64) {
            val len = data[pos].toInt() and 0xFF
            if (len == 0) {
                if (!jumped) endPos = pos + 1
                break
            }
            if ((len and 0xC0) == 0xC0) {
                if (pos + 1 >= limit) return null
                if (!jumped) endPos = pos + 2
                val ptr = ((len and 0x3F) shl 8) or (data[pos + 1].toInt() and 0xFF)
                pos = ptr
                jumped = true
                continue
            }
            pos++
            if (pos + len > limit) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(data, pos, len, Charsets.UTF_8))
            pos += len
        }
        if (endPos < 0) endPos = pos + 1
        return sb.toString() to endPos
    }

    private fun buildAResponse(hostname: String, ipv4: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        // Header: ID=0, Flags=0x8400 (Response + Authoritative), QD=0, AN=1, NS=0, AR=0
        out.write(byteArrayOf(0x00, 0x00, 0x84.toByte(), 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00))
        // Name labels
        for (label in hostname.trimEnd('.').split('.')) {
            val bytes = label.toByteArray(Charsets.UTF_8)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0x00)
        // TYPE A (0x0001)
        out.write(byteArrayOf(0x00, 0x01))
        // CLASS IN + Cache-Flush (0x8001)
        out.write(byteArrayOf(0x80.toByte(), 0x01))
        // TTL (4 bytes)
        out.write((TTL_SECONDS ushr 24) and 0xFF)
        out.write((TTL_SECONDS ushr 16) and 0xFF)
        out.write((TTL_SECONDS ushr 8) and 0xFF)
        out.write(TTL_SECONDS and 0xFF)
        // RDLENGTH = 4 + IPv4 bytes
        out.write(byteArrayOf(0x00, 0x04))
        out.write(ipv4)
        return out.toByteArray()
    }

    private fun acquireMulticastLock(context: Context) {
        try {
            val wm = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            val lock = wm.createMulticastLock("tswitch:mdns").apply {
                setReferenceCounted(false)
                acquire()
            }
            multicastLock = lock
        } catch (e: Exception) {
            Log.w(TAG, "MulticastLock acquire failed: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
        multicastLock = null
    }
}
