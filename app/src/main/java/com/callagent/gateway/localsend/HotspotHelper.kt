package com.callagent.gateway.localsend

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import com.callagent.gateway.RootShell
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Discovers the local Wi-Fi Hotspot (AP) / Tethering interface, its IPv4 address,
 * connected client devices on the hotspot subnet, and manages Mobile Data + NAT sharing
 * for the `!internet` terminal command.
 */
object HotspotHelper {
    private const val TAG = "HotspotHelper"

    data class HotspotInfo(
        val active: Boolean,
        val ifaceName: String,
        val ipAddress: String,
        val connectedClients: List<String>
    ) {
        val isActive: Boolean get() = active
    }

    @Volatile
    private var cachedInfo: HotspotInfo = HotspotInfo(false, "wlan0", "0.0.0.0", emptyList())

    @Volatile
    private var lastCacheTimeMs: Long = 0L

    @Volatile
    private var lastRootNeighProbeMs: Long = 0L

    private val refreshing = AtomicBoolean(false)

    /**
     * Inspects network interfaces to find an active Hotspot / SoftAP / RNDIS interface
     * even when mobile data and client Wi-Fi are completely off.
     *
     * Never blocks the main UI thread with RootShell calls: when invoked on the main
     * looper, returns a fast in-process snapshot immediately and schedules a background
     * refresh if the cache is older than 3 seconds.
     */
    fun detectHotspot(context: Context? = null): HotspotInfo {
        val now = System.currentTimeMillis()
        val isMainThread = Looper.myLooper() == Looper.getMainLooper()
        if (isMainThread) {
            if (now - lastCacheTimeMs > 3_000L) {
                refreshAsync(context)
                return detectHotspotFast(context)
            }
            return if (lastCacheTimeMs > 0L) cachedInfo else detectHotspotFast(context)
        }
        if (now - lastCacheTimeMs <= 2_000L && lastCacheTimeMs > 0L) {
            return cachedInfo
        }
        return detectHotspotInternal(context, allowRoot = true).also {
            cachedInfo = it
            lastCacheTimeMs = System.currentTimeMillis()
        }
    }

    fun refreshAsync(context: Context? = null) {
        if (!refreshing.compareAndSet(false, true)) return
        val appCtx = context?.applicationContext
        thread(name = "hotspot-refresh", isDaemon = true) {
            try {
                val info = detectHotspotInternal(appCtx, allowRoot = true)
                cachedInfo = info
                lastCacheTimeMs = System.currentTimeMillis()
            } catch (e: Exception) {
                Log.w(TAG, "refreshAsync failed: ${e.message}")
            } finally {
                refreshing.set(false)
            }
        }
    }

    /**
     * Fast, strictly non-blocking in-process hotspot check (reads NetworkInterface and
     * `/proc/net/arp` without spawning `su`, merging cached client list if ARP is empty).
     */
    fun detectHotspotFast(context: Context? = null): HotspotInfo {
        val fast = detectHotspotInternal(context, allowRoot = false)
        if (lastCacheTimeMs == 0L) {
            cachedInfo = fast
        }
        return fast
    }

    private fun detectHotspotInternal(context: Context?, allowRoot: Boolean): HotspotInfo {
        try {
            val allIfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            // Priority 1: Dedicated AP / Tether interfaces (ap0, softap0, swlan0, wlan1, rndis0)
            // Priority 2: wlan0 when it holds a typical Hotspot gateway IP (e.g. 192.168.43.1, 192.168.x.1)
            // or when WifiApState is enabled.
            val apActiveInWifiManager = context?.let { isWifiApEnabled(it) } ?: false

            var bestIface = ""
            var bestIp = ""
            var bestScore = -1

            for (iface in allIfaces) {
                if (iface.isLoopback || !iface.isUp) continue
                val name = iface.name.lowercase()
                // Skip cellular / VPN / dummy interfaces
                if (name.startsWith("rmnet") || name.startsWith("ccmni") ||
                    name.startsWith("tun") || name.startsWith("dummy") ||
                    name.startsWith("lo") || name.startsWith("p2p")
                ) {
                    continue
                }

                val ipv4 = iface.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                    ?.hostAddress ?: continue

                val score = when {
                    name.startsWith("ap") || name.startsWith("softap") || name.startsWith("swlan") -> 100
                    name.startsWith("wlan1") || name.startsWith("wlan2") -> 90
                    name.startsWith("rndis") || name.startsWith("usb") -> 80
                    name == "wlan0" && (apActiveInWifiManager || ipv4.startsWith("192.168.43.") || ipv4.endsWith(".1")) -> 75
                    name.startsWith("wlan") -> 50
                    else -> 10
                }

                if (score > bestScore) {
                    bestScore = score
                    bestIface = iface.name
                    bestIp = ipv4
                }
            }

            if (bestIp.isNotEmpty() && bestIp != "0.0.0.0") {
                val isApCandidate = apActiveInWifiManager ||
                    bestIface.startsWith("ap") ||
                    bestIface.startsWith("softap") ||
                    bestIface.startsWith("swlan") ||
                    bestIface.startsWith("wlan1") ||
                    bestIface.startsWith("rndis") ||
                    bestIp.startsWith("192.168.43.") ||
                    bestIp.endsWith(".1") ||
                    bestScore >= 50
                val clients = if (isApCandidate) {
                    findConnectedClients(bestIface, bestIp, allowRoot = allowRoot)
                } else {
                    emptyList()
                }
                val isAp = isApCandidate || clients.isNotEmpty()
                return HotspotInfo(
                    active = isAp,
                    ifaceName = bestIface,
                    ipAddress = bestIp,
                    connectedClients = clients
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "detectHotspot failed: ${e.message}")
        }
        return HotspotInfo(active = false, ifaceName = "", ipAddress = "0.0.0.0", connectedClients = emptyList())
    }

    /**
     * Checks via reflection whether WifiManager reports SoftAP (Hotspot) enabled.
     * Works across Android 8–14 without special permissions.
     */
    fun isWifiApEnabled(context: Context): Boolean {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return false
            val method = wm.javaClass.getDeclaredMethod("getWifiApState")
            method.isAccessible = true
            val state = method.invoke(wm) as? Int ?: return false
            // WIFI_AP_STATE_ENABLED is 13 (or 3 on very old builds)
            state == 13 || state == 3
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Finds connected client IPv4 addresses on the Hotspot subnet using `/proc/net/arp`
     * and falling back to `ip neigh` via root if `/proc/net/arp` is restricted (Android 10+).
     * Never runs root commands on the main thread or when the hotspot interface is inactive.
     */
    fun findConnectedClients(
        ifaceName: String = "",
        gatewayIp: String = "",
        allowRoot: Boolean = Looper.myLooper() != Looper.getMainLooper()
    ): List<String> {
        val subnetPrefix = gatewayIp.substringBeforeLast('.', "").let {
            if (it.isNotEmpty() && gatewayIp != "0.0.0.0") "$it." else ""
        }
        val clients = linkedSetOf<String>()

        // 1. Try /proc/net/arp (fast, no root spawn needed on Android 8/9)
        try {
            val arpFile = File("/proc/net/arp")
            if (arpFile.canRead()) {
                arpFile.forEachLine { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 6 && parts[0].matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
                        val ip = parts[0]
                        val flags = parts[2]
                        val mac = parts[3]
                        val dev = parts[5]
                        if (ip != gatewayIp &&
                            flags != "0x0" &&
                            mac != "00:00:00:00:00:00" &&
                            (ifaceName.isEmpty() || dev == ifaceName || (subnetPrefix.isNotEmpty() && ip.startsWith(subnetPrefix)))
                        ) {
                            clients.add(ip)
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // 2. If empty (e.g. Android 10+ hides /proc/net/arp from apps), query `ip neigh` via RootShell
        // ONLY on background threads, ONLY when hotspot interface + IP are active, and at most once per 5s.
        if (clients.isEmpty()) {
            val isMainThread = Looper.myLooper() == Looper.getMainLooper()
            val hotspotActive = ifaceName.isNotEmpty() && gatewayIp.isNotEmpty() && gatewayIp != "0.0.0.0"
            val now = System.currentTimeMillis()
            if (allowRoot && !isMainThread && hotspotActive && now - lastRootNeighProbeMs >= 5_000L) {
                lastRootNeighProbeMs = now
                try {
                    val devFilter = " dev $ifaceName"
                    val out = RootShell.execForOutput("ip -4 neigh show$devFilter 2>/dev/null", timeoutMs = 1500)
                    for (line in out.lines()) {
                        val trimmed = line.trim()
                        if (trimmed.isEmpty() || trimmed.endsWith("FAILED") || trimmed.endsWith("INCOMPLETE")) continue
                        val ip = trimmed.substringBefore(' ').trim()
                        if (ip.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+")) && ip != gatewayIp) {
                            if (subnetPrefix.isEmpty() || ip.startsWith(subnetPrefix)) {
                                clients.add(ip)
                            }
                        }
                    }
                } catch (_: Exception) {}
            } else if (hotspotActive) {
                for (ip in cachedInfo.connectedClients) {
                    if (ip != gatewayIp && (subnetPrefix.isEmpty() || ip.startsWith(subnetPrefix))) {
                        clients.add(ip)
                    }
                }
            }
        }

        return clients.toList()
    }

    /**
     * Checks whether Mobile Data is currently enabled.
     */
    fun isMobileDataEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences("gateway", Context.MODE_PRIVATE)
        if (RootShell.rootState() != "ok" && prefs.contains("internet_sharing_override")) {
            return prefs.getBoolean("internet_sharing_override", false)
        }
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            if (tm != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                tm.isDataEnabled
            } else {
                android.provider.Settings.Global.getInt(
                    context.contentResolver,
                    "mobile_data",
                    0
                ) == 1
            }
        } catch (_: Throwable) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                false
            } else {
                val out = RootShell.execForOutput("settings get global mobile_data 2>/dev/null", timeoutMs = 1500).trim()
                out == "1"
            }
        }
    }

    /**
     * Toggles Mobile Data and Hotspot NAT forwarding for the `!internet` command.
     * Returns a formatted Turkish status report to send back to Zoiper as a Fake SMS.
     */
    fun toggleInternetSharing(context: Context): String {
        val prefs = context.getSharedPreferences("gateway", Context.MODE_PRIVATE)
        val currentlyOn = isMobileDataEnabled(context)
        val targetOn = !currentlyOn
        prefs.edit().putBoolean("internet_sharing_override", targetOn).apply()
        val hs = detectHotspot(context)
        val hsIface = hs.ifaceName.ifEmpty { "wlan0" }

        return if (targetOn) {
            RootShell.execForOutput(
                "svc data enable 2>/dev/null; " +
                    "settings put global mobile_data 1 2>/dev/null; " +
                    "sysctl -w net.ipv4.ip_forward=1 >/dev/null 2>&1; " +
                    "for up in rmnet_data0 rmnet_data1 rmnet_data2 rmnet0 ccmni0 ccmni1; do " +
                    "  iptables -t nat -C POSTROUTING -o \$up -j MASQUERADE 2>/dev/null || " +
                    "  iptables -t nat -A POSTROUTING -o \$up -j MASQUERADE 2>/dev/null; " +
                    "done; " +
                    "iptables -C FORWARD -i $hsIface -j ACCEPT 2>/dev/null || " +
                    "iptables -I FORWARD 1 -i $hsIface -j ACCEPT 2>/dev/null; " +
                    "iptables -C FORWARD -o $hsIface -j ACCEPT 2>/dev/null || " +
                    "iptables -I FORWARD 1 -o $hsIface -j ACCEPT 2>/dev/null; " +
                    "ip rule add from all lookup main pref 1 2>/dev/null || true",
                timeoutMs = 8000
            )
            // Wait briefly for cellular data to attach
            try { Thread.sleep(1500) } catch (_: InterruptedException) {}
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val carrier = tm?.networkOperatorName?.ifEmpty { "Bilinmiyor" } ?: "Bilinmiyor"
            "🌐 [T-Switch İnternet Paylaşımı: AÇILDI ✅]\n" +
                "• Mobil Veri: AKTİF ($carrier)\n" +
                "• Hotspot Arayüzü: $hsIface (${hs.ipAddress})\n" +
                "• NAT / IP Yönlendirme: Etkinleştirildi\n" +
                "(Kapatmak için tekrar !internet yazın)"
        } else {
            RootShell.execForOutput(
                "svc data disable 2>/dev/null; " +
                    "settings put global mobile_data 0 2>/dev/null",
                timeoutMs = 6000
            )
            "📴 [T-Switch İnternet Paylaşımı: KAPATILDI ❌]\n" +
                "• Mobil Veri: DEVRE DIŞI\n" +
                "• Local Send (${hs.ipAddress}): İnternetsiz yerel modda aktif kalmaya devam ediyor.\n" +
                "(Tekrar açmak için !internet yazın)"
        }
    }
}
