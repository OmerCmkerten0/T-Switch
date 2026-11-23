package com.callagent.gateway.service

import com.callagent.gateway.sip.SipBuilder
import com.callagent.gateway.sip.SipClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayPortResolutionTest {

    @Test
    fun missingIntentPortPreservesSavedPort5061() {
        assertEquals(
            5061,
            GatewayService.resolveEffectivePort(intentPort = -1, savedPort = 5061)
        )
    }

    @Test
    fun explicitIntentPortOverridesSavedPort() {
        assertEquals(
            5061,
            GatewayService.resolveEffectivePort(intentPort = 5061, savedPort = 5060)
        )
    }

    @Test
    fun invalidPortsFallBackToDefault5060() {
        assertEquals(
            5060,
            GatewayService.resolveEffectivePort(intentPort = -1, savedPort = 0)
        )
        assertEquals(
            5060,
            GatewayService.resolveEffectivePort(intentPort = 0, savedPort = -5)
        )
        assertEquals(
            5060,
            GatewayService.resolveEffectivePort(intentPort = 70000, savedPort = 70000)
        )
    }

    @Test
    fun dualStackClientsKeepIndependentViaAndContactTransports() {
        val localUdp = SipClient(
            username = "tswitch",
            password = "",
            serverDomain = "192.168.1.85",
            serverPort = 5060,
            localIp = "192.168.1.85",
            localPort = 5060,
            useTls = false,
            localSendMode = true
        )
        val cloudTls = SipClient(
            username = "t-switch_pusher",
            password = "secret",
            serverDomain = "sip.linphone.org",
            serverPort = 5061,
            localIp = "10.0.0.5",
            localPort = 5063,
            useTls = true,
            localSendMode = false
        )

        assertEquals("UDP", localUdp.viaTransport)
        assertEquals("TLS", cloudTls.viaTransport)

        val tlsRegister = SipBuilder.register(
            username = cloudTls.username,
            domain = cloudTls.serverDomain,
            serverPort = cloudTls.serverPort,
            localIp = cloudTls.localIp,
            localPort = cloudTls.localPort,
            callId = "dual-tls-1",
            cseq = 1,
            transportOverride = cloudTls.viaTransport
        )
        val udpInvite = SipBuilder.invite(
            targetUri = "sip:zoiper@192.168.1.50:5060",
            username = localUdp.username,
            domain = localUdp.localIp,
            localIp = localUdp.localIp,
            localPort = localUdp.localPort,
            callId = "dual-udp-1",
            cseq = 1,
            localRtpPort = 30000,
            transportOverride = localUdp.viaTransport
        )

        assertTrue(tlsRegister.contains("Via: SIP/2.0/TLS "))
        assertTrue(tlsRegister.contains(";transport=tls>"))
        assertTrue(udpInvite.contains("Via: SIP/2.0/UDP "))
        assertFalse(udpInvite.contains(";transport=tls"))
    }
}
