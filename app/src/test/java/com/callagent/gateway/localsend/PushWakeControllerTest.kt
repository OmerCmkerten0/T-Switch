package com.callagent.gateway.localsend

import com.callagent.gateway.sip.SipMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PushWakeControllerTest {

    @Test
    fun extractReversedRoutesHandlesMultiLineAndCommaSeparated() {
        val raw = buildString {
            append("SIP/2.0 200 OK\r\n")
            append("Via: SIP/2.0/TLS 192.168.1.85:45123;branch=z9hG4bKabc\r\n")
            append("Record-Route: <sip:edge1.linphone.org:5061;transport=tls;lr>\r\n")
            append("Record-Route: <sip:core.linphone.org;lr>, <sip:edge2.linphone.org:5061;transport=tls;lr>\r\n")
            append("From: <sip:t-switch_pusher@sip.linphone.org>;tag=f1\r\n")
            append("To: <sip:t-switch_receiver@sip.linphone.org>;tag=t1\r\n")
            append("Call-ID: rr-test-1\r\n")
            append("CSeq: 2 INVITE\r\n")
            append("Content-Length: 0\r\n\r\n")
        }

        val msg = SipMessage.parse(raw)
        assertNotNull(msg)
        val routes = PushWakeController.extractReversedRoutes(msg!!)

        assertEquals(
            listOf(
                "<sip:edge2.linphone.org:5061;transport=tls;lr>",
                "<sip:core.linphone.org;lr>",
                "<sip:edge1.linphone.org:5061;transport=tls;lr>"
            ),
            routes
        )
    }

    @Test
    fun selectPreferredIpv4SkipsCellularAndVpnInterfaces() {
        val candidates = listOf(
            "lo" to "127.0.0.1",
            "rmnet_data0" to "100.64.12.34",
            "ccmni0" to "10.200.1.5",
            "tun0" to "10.8.0.2",
            "dummy0" to "192.0.2.1",
            "p2p-wlan0-0" to "192.168.49.1",
            "wlan0" to "192.168.1.85"
        )
        assertEquals("192.168.1.85", PushWakeController.selectPreferredIpv4(candidates))

        val hotspotCandidates = listOf(
            "rmnet0" to "10.10.10.2",
            "tun1" to "10.9.0.2",
            "ap0" to "192.168.43.1"
        )
        assertEquals("192.168.43.1", PushWakeController.selectPreferredIpv4(hotspotCandidates))

        val onlySkipped = listOf(
            "lo" to "127.0.0.1",
            "rmnet_data0" to "100.64.12.34",
            "tun0" to "10.8.0.2",
            "dummy0" to "192.0.2.1",
            "p2p0" to "192.168.49.1"
        )
        assertEquals("127.0.0.1", PushWakeController.selectPreferredIpv4(onlySkipped))
    }
}
