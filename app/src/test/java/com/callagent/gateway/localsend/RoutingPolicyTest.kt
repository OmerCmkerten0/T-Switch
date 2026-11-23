package com.callagent.gateway.localsend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingPolicyTest {

    @Test
    fun blankOrLinphoneServerSelectsHybridLocalIp() {
        val inputs = listOf(null, "", "   ", "sip.linphone.org", "SIP.LINPHONE.ORG", "  sip.linphone.org  ")
        for (server in inputs) {
            val decision = RoutingPolicy.resolve(
                customSipServer = server,
                customSipPort = 5060,
                customSipTls = false
            )
            assertEquals("Expected HYBRID_LOCAL_IP for '$server'", RoutingMode.HYBRID_LOCAL_IP, decision.mode)
            assertFalse("Expected pushWakeOnly=false for '$server'", decision.pushWakeOnly)
            assertEquals("192.168.1.85", decision.pinnedLocalIp)
            assertEquals("", decision.customServer)
            assertFalse(decision.customUseTls)
        }
    }

    @Test
    fun customServerSelectsCustomSipWithWakeOnlyPush() {
        val decision = RoutingPolicy.resolve(
            customSipServer = "  sip.example.com  ",
            customSipPort = 5061,
            customSipTls = false
        )
        assertEquals(RoutingMode.CUSTOM_SIP_WITH_PUSH_WAKE, decision.mode)
        assertEquals("sip.example.com", decision.customServer)
        assertEquals(5061, decision.customPort)
        assertTrue(decision.customUseTls)
        assertTrue(decision.pushWakeOnly)
        assertEquals("192.168.1.85", decision.pinnedLocalIp)
    }

    @Test
    fun customServerOn5060RespectsExplicitTlsFlag() {
        val plain = RoutingPolicy.resolve(
            customSipServer = "10.20.30.40",
            customSipPort = 5060,
            customSipTls = false
        )
        assertEquals(RoutingMode.CUSTOM_SIP_WITH_PUSH_WAKE, plain.mode)
        assertEquals("10.20.30.40", plain.customServer)
        assertEquals(5060, plain.customPort)
        assertFalse(plain.customUseTls)
        assertTrue(plain.pushWakeOnly)

        val tls = RoutingPolicy.resolve(
            customSipServer = "10.20.30.40",
            customSipPort = 5060,
            customSipTls = true
        )
        assertEquals(RoutingMode.CUSTOM_SIP_WITH_PUSH_WAKE, tls.mode)
        assertEquals("10.20.30.40", tls.customServer)
        assertEquals(5060, tls.customPort)
        assertTrue(tls.customUseTls)
        assertTrue(tls.pushWakeOnly)
    }

    @Test
    fun invalidOrBlankPinnedLocalIpFallsBackToDefault() {
        val invalidInputs = listOf(null, "", "   ", "999.168.1.85", "192.168.1", "192.168.1.85;id", "0.0.0.0")
        for (pinned in invalidInputs) {
            val decision = RoutingPolicy.resolve(
                customSipServer = null,
                customSipPort = 5060,
                customSipTls = false,
                pinnedLocalIp = pinned
            )
            assertEquals("Expected fallback IP for '$pinned'", "192.168.1.85", decision.pinnedLocalIp)
        }

        val customPinned = RoutingPolicy.resolve(
            customSipServer = null,
            customSipPort = 5060,
            customSipTls = false,
            pinnedLocalIp = " 192.168.1.90 "
        )
        assertEquals("192.168.1.90", customPinned.pinnedLocalIp)
    }
}
