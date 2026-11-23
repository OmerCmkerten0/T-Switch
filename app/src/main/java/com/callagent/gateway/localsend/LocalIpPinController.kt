package com.callagent.gateway.localsend

import android.content.Context
import android.util.Log
import com.callagent.gateway.RootShell
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Plans and applies fixed local IP pinning for Wi-Fi client (`/24` address replace)
 * and Android Hotspot/SoftAP (`/32` alias + NAT PREROUTING DNAT) via [RootShell].
 */
object LocalIpPinController {
    private const val TAG = "LocalIpPinController"
    const val DEFAULT_PINNED_IP = "192.168.1.85"

    private val SAFE_IFACE_REGEX = Regex("^[a-zA-Z0-9_.-]+$")
    private val IPV4_REGEX = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

    private val pinLock = Any()
    @Volatile
    private var lastPinnedHotspot: PinnedHotspot? = null

    private data class PinnedHotspot(
        val iface: String,
        val apIp: String,
        val pinnedIp: String
    )

    internal fun isValidInterfaceName(iface: String): Boolean =
        iface.isNotEmpty() && SAFE_IFACE_REGEX.matches(iface)

    internal fun isValidRoutableIpv4(ip: String): Boolean {
        val match = IPV4_REGEX.matchEntire(ip) ?: return false
        if (ip == "0.0.0.0") return false
        for (i in 1..4) {
            val octet = match.groupValues[i].toIntOrNull() ?: return false
            if (octet !in 0..255) return false
        }
        return true
    }

    /**
     * Plans a Wi-Fi IP pin command when [currentIp] shares the same `/24` subnet as [pinnedIp].
     * Returns `null` if inputs are invalid, [currentIp] is already [pinnedIp], or subnets differ.
     */
    fun planWifiPinCommand(iface: String, currentIp: String, pinnedIp: String): String? {
        if (!isValidInterfaceName(iface) ||
            !isValidRoutableIpv4(currentIp) ||
            !isValidRoutableIpv4(pinnedIp)
        ) {
            return null
        }
        if (currentIp == pinnedIp) return null
        if (currentIp.substringBeforeLast('.') != pinnedIp.substringBeforeLast('.')) {
            return null
        }
        return "ip addr replace $pinnedIp/24 dev $iface"
    }

    /**
     * Plans idempotent root shell commands to pin [pinnedIp] on a hotspot interface [iface]
     * and DNAT incoming traffic from [pinnedIp] to the active hotspot gateway [apIp].
     */
    fun planHotspotPinCommands(iface: String, apIp: String, pinnedIp: String): List<String> {
        if (!isValidInterfaceName(iface) ||
            !isValidRoutableIpv4(apIp) ||
            !isValidRoutableIpv4(pinnedIp)
        ) {
            return emptyList()
        }
        if (apIp == pinnedIp) return emptyList()
        val dnatSpec = "-i $iface -d $pinnedIp -j DNAT --to-destination $apIp"
        return listOf(
            "ip addr replace $pinnedIp/32 dev $iface",
            "iptables -t nat -C PREROUTING $dnatSpec 2>/dev/null || " +
                "iptables -t nat -A PREROUTING $dnatSpec"
        )
    }

    /**
     * Plans idempotent cleanup commands to remove a previously applied hotspot pin and DNAT rule.
     */
    fun planHotspotCleanupCommands(iface: String, apIp: String, pinnedIp: String): List<String> {
        if (!isValidInterfaceName(iface) ||
            !isValidRoutableIpv4(apIp) ||
            !isValidRoutableIpv4(pinnedIp)
        ) {
            return emptyList()
        }
        if (apIp == pinnedIp) return emptyList()
        val dnatSpec = "-i $iface -d $pinnedIp -j DNAT --to-destination $apIp"
        return listOf(
            "iptables -t nat -D PREROUTING $dnatSpec 2>/dev/null || true",
            "ip addr del $pinnedIp/32 dev $iface 2>/dev/null || true"
        )
    }

    /**
     * Detects the active Hotspot or Wi-Fi network and applies [pinnedIp] via [RootShell].
     * Returns `true` if the pin is active (or already applied on the current network).
     */
    fun applyForCurrentNetwork(
        context: Context,
        pinnedIp: String = DEFAULT_PINNED_IP
    ): Boolean {
        val targetIp = pinnedIp.trim()
        if (!isValidRoutableIpv4(targetIp)) return false

        synchronized(pinLock) {
            val hs = HotspotHelper.detectHotspotFast(context)
            val isHotspot = hs.active &&
                isValidInterfaceName(hs.ifaceName) &&
                isValidRoutableIpv4(hs.ipAddress) &&
                (HotspotHelper.isWifiApEnabled(context) || isDedicatedHotspotInterface(hs.ifaceName, hs.ipAddress))

            if (isHotspot) {
                val previous = lastPinnedHotspot
                if (previous != null &&
                    (previous.iface != hs.ifaceName || previous.apIp != hs.ipAddress || previous.pinnedIp != targetIp)
                ) {
                    runCommands(planHotspotCleanupCommands(previous.iface, previous.apIp, previous.pinnedIp))
                    lastPinnedHotspot = null
                }

                if (hs.ipAddress == targetIp) return true

                val commands = planHotspotPinCommands(hs.ifaceName, hs.ipAddress, targetIp)
                if (commands.isEmpty()) return false
                lastPinnedHotspot = PinnedHotspot(hs.ifaceName, hs.ipAddress, targetIp)
                val ok = runCommands(commands)
                if (!ok) {
                    Log.w(TAG, "Failed to apply hotspot pin $targetIp on ${hs.ifaceName} (${hs.ipAddress})")
                }
                return ok
            }

            // Not in hotspot mode: clear any stale hotspot pin before checking Wi-Fi.
            lastPinnedHotspot?.let { previous ->
                runCommands(planHotspotCleanupCommands(previous.iface, previous.apIp, previous.pinnedIp))
                lastPinnedHotspot = null
            }

            val (wifiIface, wifiIp) = findActiveWifiInterface(hs) ?: return false
            if (wifiIp == targetIp) return true

            val cmd = planWifiPinCommand(wifiIface, wifiIp, targetIp) ?: return false
            val ok = RootShell.exec(cmd) == 0
            if (!ok) {
                Log.w(TAG, "Failed to apply Wi-Fi pin $targetIp on $wifiIface (was $wifiIp)")
            }
            return ok
        }
    }

    /**
     * Removes any hotspot pin previously applied by [applyForCurrentNetwork].
     */
    fun clearPin(pinnedIp: String = DEFAULT_PINNED_IP) {
        val targetIp = pinnedIp.trim()
        synchronized(pinLock) {
            val previous = lastPinnedHotspot ?: return
            val ipsToClean = linkedSetOf(previous.pinnedIp)
            if (isValidRoutableIpv4(targetIp)) {
                ipsToClean.add(targetIp)
            }
            for (ip in ipsToClean) {
                runCommands(planHotspotCleanupCommands(previous.iface, previous.apIp, ip))
            }
            lastPinnedHotspot = null
        }
    }

    private fun runCommands(commands: List<String>): Boolean {
        var allOk = true
        for (cmd in commands) {
            if (RootShell.exec(cmd) != 0) {
                allOk = false
            }
        }
        return allOk
    }

    private fun isDedicatedHotspotInterface(iface: String, ip: String): Boolean {
        val lower = iface.lowercase()
        return lower.startsWith("ap") ||
            lower.startsWith("softap") ||
            lower.startsWith("swlan") ||
            lower.startsWith("wlan1") ||
            lower.startsWith("wlan2") ||
            lower.startsWith("rndis") ||
            lower.startsWith("usb") ||
            ip.startsWith("192.168.43.") ||
            (lower == "wlan0" && ip.endsWith(".1"))
    }

    private fun findActiveWifiInterface(hs: HotspotHelper.HotspotInfo): Pair<String, String>? {
        if (hs.active &&
            hs.ifaceName.lowercase().startsWith("wlan") &&
            isValidInterfaceName(hs.ifaceName) &&
            isValidRoutableIpv4(hs.ipAddress)
        ) {
            return hs.ifaceName to hs.ipAddress
        }
        return try {
            val ifaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (iface in ifaces) {
                if (iface.isLoopback || !iface.isUp) continue
                val name = iface.name
                val lower = name.lowercase()
                if (!lower.startsWith("wlan") && !lower.startsWith("eth")) continue
                if (!isValidInterfaceName(name)) continue
                val ipv4 = iface.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                    ?.hostAddress ?: continue
                if (isValidRoutableIpv4(ipv4)) {
                    return name to ipv4
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enumerate network interfaces: ${e.message}")
            null
        }
    }
}
