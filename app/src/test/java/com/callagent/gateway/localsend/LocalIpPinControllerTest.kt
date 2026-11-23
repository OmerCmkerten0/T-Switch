package com.callagent.gateway.localsend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalIpPinControllerTest {

    @Test
    fun wifiPinAppliesOnlyOnMatching24Subnet() {
        assertEquals(
            "ip addr replace 192.168.1.85/24 dev wlan0",
            LocalIpPinController.planWifiPinCommand("wlan0", "192.168.1.42", "192.168.1.85")
        )
        assertNull(LocalIpPinController.planWifiPinCommand("wlan0", "192.168.1.85", "192.168.1.85"))
        assertNull(LocalIpPinController.planWifiPinCommand("wlan0", "192.168.0.15", "192.168.1.85"))
        assertNull(LocalIpPinController.planWifiPinCommand("wlan0", "10.0.0.5", "192.168.1.85"))
    }

    @Test
    fun hotspotPinAndCleanupCommandsAreIdempotent() {
        val pinCmds = LocalIpPinController.planHotspotPinCommands("ap0", "192.168.43.1", "192.168.1.85")
        assertTrue(pinCmds.any { it == "ip addr replace 192.168.1.85/32 dev ap0" })
        assertTrue(
            pinCmds.any {
                it.contains("iptables -t nat -C PREROUTING") &&
                    it.contains("-i ap0") &&
                    it.contains("-d 192.168.1.85") &&
                    it.contains("--to-destination 192.168.43.1")
            }
        )

        val cleanupCmds = LocalIpPinController.planHotspotCleanupCommands("ap0", "192.168.43.1", "192.168.1.85")
        assertTrue(
            cleanupCmds.any {
                it.contains("iptables -t nat -D PREROUTING") &&
                    it.contains("-i ap0") &&
                    it.contains("-d 192.168.1.85") &&
                    it.contains("--to-destination 192.168.43.1")
            }
        )
        assertTrue(cleanupCmds.any { it.contains("ip addr del 192.168.1.85/32 dev ap0") })
    }

    @Test
    fun rejectsInvalidIpv4AndUnsafeInterfaceNames() {
        val badIfaces = listOf("", "wlan0;rm -rf /", "ap0|id", "wlan 0", "ap0\nid", "\$(id)")
        for (iface in badIfaces) {
            assertNull("Expected null for iface '$iface'", LocalIpPinController.planWifiPinCommand(iface, "192.168.1.42", "192.168.1.85"))
            assertTrue("Expected empty pin cmds for iface '$iface'", LocalIpPinController.planHotspotPinCommands(iface, "192.168.43.1", "192.168.1.85").isEmpty())
            assertTrue("Expected empty cleanup cmds for iface '$iface'", LocalIpPinController.planHotspotCleanupCommands(iface, "192.168.43.1", "192.168.1.85").isEmpty())
        }

        val badIps = listOf("", "256.1.1.1", "192.168.1", "192.168.1.1;id", "0.0.0.0", "192.168.1.-1")
        for (ip in badIps) {
            assertNull("Expected null for currentIp '$ip'", LocalIpPinController.planWifiPinCommand("wlan0", ip, "192.168.1.85"))
            assertNull("Expected null for pinnedIp '$ip'", LocalIpPinController.planWifiPinCommand("wlan0", "192.168.1.42", ip))
            assertTrue("Expected empty pin cmds for apIp '$ip'", LocalIpPinController.planHotspotPinCommands("ap0", ip, "192.168.1.85").isEmpty())
            assertTrue("Expected empty pin cmds for pinnedIp '$ip'", LocalIpPinController.planHotspotPinCommands("ap0", "192.168.43.1", ip).isEmpty())
            assertTrue("Expected empty cleanup cmds for apIp '$ip'", LocalIpPinController.planHotspotCleanupCommands("ap0", ip, "192.168.1.85").isEmpty())
            assertTrue("Expected empty cleanup cmds for pinnedIp '$ip'", LocalIpPinController.planHotspotCleanupCommands("ap0", "192.168.43.1", ip).isEmpty())
        }
    }
}
