package com.callagent.gateway.service

import com.callagent.gateway.localsend.RoutingMode
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
            username = "gateway_user",
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

    @Test
    fun cloudSmsSenderAuthorizationRequiresBothPairedUserAndAllowedDomain() {
        val allowed = setOf("sip.linphone.org")

        assertTrue(
            "Exact paired user on allowed domain must be accepted",
            GatewayService.isAuthorizedCloudSmsSender(
                fromUser = "receiver_user",
                fromDomain = "sip.linphone.org",
                pairedReceiverUser = "receiver_user",
                allowedDomains = allowed
            )
        )
        assertTrue(
            "Case-insensitive user and domain match must be accepted",
            GatewayService.isAuthorizedCloudSmsSender(
                fromUser = "Receiver_User",
                fromDomain = "SIP.LINPHONE.ORG",
                pairedReceiverUser = "receiver_user",
                allowedDomains = allowed
            )
        )
        assertFalse(
            "Federated spoofed domain receiver_user@evil.com must be rejected",
            GatewayService.isAuthorizedCloudSmsSender(
                fromUser = "receiver_user",
                fromDomain = "evil.com",
                pairedReceiverUser = "receiver_user",
                allowedDomains = allowed
            )
        )
        assertFalse(
            "Subdomain spoofing must be rejected",
            GatewayService.isAuthorizedCloudSmsSender(
                fromUser = "receiver_user",
                fromDomain = "sip.linphone.org.evil.com",
                pairedReceiverUser = "receiver_user",
                allowedDomains = allowed
            )
        )
        assertFalse(
            "Wrong user on allowed domain must be rejected",
            GatewayService.isAuthorizedCloudSmsSender(
                fromUser = "attacker",
                fromDomain = "sip.linphone.org",
                pairedReceiverUser = "receiver_user",
                allowedDomains = allowed
            )
        )
        assertFalse(
            "Missing domain or user must be rejected",
            GatewayService.isAuthorizedCloudSmsSender(
                fromUser = "receiver_user",
                fromDomain = null,
                pairedReceiverUser = "receiver_user",
                allowedDomains = allowed
            )
        )
    }

    @Test
    fun hybridCloudRecoveryStartsMissingOrUnregisteredClientOnlyWhenOnline() {
        assertEquals(
            GatewayService.CloudRecoveryAction.START_NEW,
            GatewayService.decideCloudSipRecovery(
                routingMode = RoutingMode.HYBRID_LOCAL_IP,
                pushConfigured = true,
                hasInternet = true,
                hasPrimaryLocalClient = true,
                cloudExists = false,
                cloudRunning = false,
                cloudRegistered = false,
                nowMs = 20_000L,
                lastAttemptMs = 0L
            )
        )
        assertEquals(
            GatewayService.CloudRecoveryAction.START_NEW,
            GatewayService.decideCloudSipRecovery(
                routingMode = RoutingMode.HYBRID_LOCAL_IP,
                pushConfigured = true,
                hasInternet = true,
                hasPrimaryLocalClient = true,
                cloudExists = true,
                cloudRunning = false,
                cloudRegistered = false,
                nowMs = 20_000L,
                lastAttemptMs = 19_500L
            )
        )
        assertEquals(
            GatewayService.CloudRecoveryAction.NONE,
            GatewayService.decideCloudSipRecovery(
                routingMode = RoutingMode.HYBRID_LOCAL_IP,
                pushConfigured = true,
                hasInternet = true,
                hasPrimaryLocalClient = true,
                cloudExists = true,
                cloudRunning = true,
                cloudRegistered = false,
                nowMs = 20_000L,
                lastAttemptMs = 15_000L
            )
        )
        assertEquals(
            GatewayService.CloudRecoveryAction.RESTART_STALE,
            GatewayService.decideCloudSipRecovery(
                routingMode = RoutingMode.HYBRID_LOCAL_IP,
                pushConfigured = true,
                hasInternet = true,
                hasPrimaryLocalClient = true,
                cloudExists = true,
                cloudRunning = true,
                cloudRegistered = false,
                nowMs = 30_000L,
                lastAttemptMs = 15_000L
            )
        )
        assertEquals(
            GatewayService.CloudRecoveryAction.NONE,
            GatewayService.decideCloudSipRecovery(
                routingMode = RoutingMode.HYBRID_LOCAL_IP,
                pushConfigured = true,
                hasInternet = false,
                hasPrimaryLocalClient = true,
                cloudExists = false,
                cloudRunning = false,
                cloudRegistered = false,
                nowMs = 30_000L,
                lastAttemptMs = 0L
            )
        )
        assertEquals(
            GatewayService.CloudRecoveryAction.NONE,
            GatewayService.decideCloudSipRecovery(
                routingMode = RoutingMode.CUSTOM_SIP_WITH_PUSH_WAKE,
                pushConfigured = true,
                hasInternet = true,
                hasPrimaryLocalClient = true,
                cloudExists = false,
                cloudRunning = false,
                cloudRegistered = false,
                nowMs = 30_000L,
                lastAttemptMs = 0L
            )
        )
        assertEquals(
            GatewayService.CloudRecoveryAction.NONE,
            GatewayService.decideCloudSipRecovery(
                routingMode = RoutingMode.HYBRID_LOCAL_IP,
                pushConfigured = true,
                hasInternet = true,
                hasPrimaryLocalClient = true,
                cloudExists = true,
                cloudRunning = true,
                cloudRegistered = true,
                nowMs = 30_000L,
                lastAttemptMs = 0L
            )
        )
    }
}
