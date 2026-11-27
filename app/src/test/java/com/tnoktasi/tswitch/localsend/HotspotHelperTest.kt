package com.tnoktasi.tswitch.localsend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HotspotHelperTest {

    @Test
    fun clientWifiIsNotClassifiedAsHotspot() {
        val info = HotspotHelper.classifyNetworkCandidates(
            candidates = listOf("wlan0" to "192.168.1.42"),
            wifiApEnabled = false
        )

        assertFalse("Client Wi-Fi should not be classified as active hotspot", info.active)
        assertEquals("0.0.0.0", info.ipAddress)
        assertEquals("wlan0", info.wifiIfaceName)
        assertEquals("192.168.1.42", info.wifiIpAddress)
    }

    @Test
    fun dedicatedApOrTetherIpIsClassifiedAsHotspot() {
        val dedicatedAp = HotspotHelper.classifyNetworkCandidates(
            candidates = listOf("ap0" to "192.168.43.1"),
            wifiApEnabled = false
        )
        assertTrue("Dedicated ap0 interface should be classified as hotspot", dedicatedAp.active)
        assertEquals("ap0", dedicatedAp.ifaceName)
        assertEquals("192.168.43.1", dedicatedAp.ipAddress)

        val wlan0WithApFlag = HotspotHelper.classifyNetworkCandidates(
            candidates = listOf("wlan0" to "192.168.43.1"),
            wifiApEnabled = true
        )
        assertTrue("wlan0 with wifiApEnabled=true should be classified as hotspot", wlan0WithApFlag.active)
        assertEquals("wlan0", wlan0WithApFlag.ifaceName)
        assertEquals("192.168.43.1", wlan0WithApFlag.ipAddress)

        val wlan0WithTetherIp = HotspotHelper.classifyNetworkCandidates(
            candidates = listOf("wlan0" to "192.168.43.1"),
            wifiApEnabled = false
        )
        assertTrue("wlan0 with tether gateway IP should be classified as hotspot", wlan0WithTetherIp.active)
        assertEquals("wlan0", wlan0WithTetherIp.ifaceName)
        assertEquals("192.168.43.1", wlan0WithTetherIp.ipAddress)

        val wlan0NonTetherWithApFlag = HotspotHelper.classifyNetworkCandidates(
            candidates = listOf("wlan0" to "192.168.100.1"),
            wifiApEnabled = true
        )
        assertTrue("wlan0 with non-tether IP and wifiApEnabled=true should be classified as hotspot", wlan0NonTetherWithApFlag.active)
        assertEquals("wlan0", wlan0NonTetherWithApFlag.ifaceName)
        assertEquals("192.168.100.1", wlan0NonTetherWithApFlag.ipAddress)

        val usbTether = HotspotHelper.classifyNetworkCandidates(
            candidates = listOf("rndis0" to "192.168.42.129"),
            wifiApEnabled = false
        )
        assertTrue("rndis0 USB tether should be classified as hotspot", usbTether.active)
        assertEquals("rndis0", usbTether.ifaceName)
        assertEquals("192.168.42.129", usbTether.ipAddress)
    }

    @Test
    fun cellularAndVpnInterfacesAreIgnored() {
        val info = HotspotHelper.classifyNetworkCandidates(
            candidates = listOf(
                "rmnet_data2" to "10.142.26.215",
                "ccmni0" to "10.20.30.40",
                "tun0" to "10.0.0.1",
                "dummy0" to "192.168.99.1",
                "lo" to "127.0.0.1",
                "p2p0" to "192.168.49.1"
            ),
            wifiApEnabled = false
        )

        assertFalse("Cellular/VPN/dummy/lo/p2p interfaces must not be classified as hotspot", info.active)
        assertEquals("0.0.0.0", info.ipAddress)
        assertEquals("", info.wifiIpAddress)
        assertEquals("", info.wifiIfaceName)
    }

    @Test
    fun internetSharingScriptsUseAndroidIpRuleFormatAndDrainDuplicates() {
        val enableCmd = HotspotHelper.buildInternetSharingEnableCommand("wlan0")
        assertTrue(
            "Expected Android ip rule regex '(^1:|pref 1 )' in enable command, got: $enableCmd",
            enableCmd.contains("grep -qE '(^1:|pref 1 )'")
        )
        assertFalse(
            "Must not use broken literal 'grep -q \\'pref 1 \\''",
            enableCmd.contains("grep -q 'pref 1 '")
        )

        val disableCmd = HotspotHelper.buildInternetSharingDisableCommand()
        assertTrue(
            "Expected loop draining duplicate pref 1 rules on disable, got: $disableCmd",
            disableCmd.contains("while ip rule del pref 1")
        )
    }
}
