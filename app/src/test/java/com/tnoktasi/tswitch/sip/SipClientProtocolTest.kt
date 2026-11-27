package com.tnoktasi.tswitch.sip

import com.tnoktasi.tswitch.localsend.LocalSendController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SipClientProtocolTest {

    @After
    fun resetSipBuilderTransport() {
        SipBuilder.transport = "UDP"
        LocalSendController.pushWakeTriggerListenerForTest = null
    }

    @Test
    fun linphoneInvitePutsUsernameInFromUriAndCallerInDisplayName() {
        val (fromUserNoName, fromDisplayNoName) = SipClient.buildInviteFromParts(
            serverDomain = "sip.linphone.org",
            username = "t-switch_pusher",
            localSendMode = false,
            callerIdNumber = "+905551234567",
            callerIdName = null
        )
        assertEquals("t-switch_pusher", fromUserNoName)
        assertEquals("+905551234567", fromDisplayNoName)

        val (fromUserBlankName, fromDisplayBlankName) = SipClient.buildInviteFromParts(
            serverDomain = "SIP.LINPHONE.ORG",
            username = "t-switch_pusher",
            localSendMode = false,
            callerIdNumber = "+905551234567",
            callerIdName = "   "
        )
        assertEquals("t-switch_pusher", fromUserBlankName)
        assertEquals("+905551234567", fromDisplayBlankName)

        val (fromUserWithName, fromDisplayWithName) = SipClient.buildInviteFromParts(
            serverDomain = "sip.linphone.org",
            username = "t-switch_pusher",
            localSendMode = false,
            callerIdNumber = "+905551234567",
            callerIdName = "Alice"
        )
        assertEquals("t-switch_pusher", fromUserWithName)
        assertEquals("Alice", fromDisplayWithName)

        val invite = SipBuilder.invite(
            targetUri = "sip:receiver@sip.linphone.org",
            username = "t-switch_pusher",
            domain = "sip.linphone.org",
            localIp = "10.0.0.5",
            localPort = 45123,
            callId = "linphone-call-1",
            cseq = 1,
            localRtpPort = 10000,
            fromTag = "gw123",
            callerIdNumber = fromUserNoName,
            callerIdName = fromDisplayNoName
        )
        assertTrue(
            "Expected Linphone From header with username in URI and caller in display name, got:\n$invite",
            invite.contains("From: \"+905551234567\" <sip:t-switch_pusher@sip.linphone.org>;tag=gw123\r\n")
        )
    }

    @Test
    fun customAsteriskInvitePreservesCallerIdInFromUser() {
        val (fromUser, fromDisplay) = SipClient.buildInviteFromParts(
            serverDomain = "pbx.example.com",
            username = "gw-user",
            localSendMode = false,
            callerIdNumber = "+905551234567",
            callerIdName = "Alice"
        )
        assertEquals("+905551234567", fromUser)
        assertEquals("Alice", fromDisplay)

        val (localFromUser, localFromDisplay) = SipClient.buildInviteFromParts(
            serverDomain = "sip.linphone.org",
            username = "gw-user",
            localSendMode = true,
            callerIdNumber = "+905551234567",
            callerIdName = "Alice"
        )
        assertEquals("+905551234567", localFromUser)
        assertEquals("Alice", localFromDisplay)

        val (fallbackUser, fallbackDisplay) = SipClient.buildInviteFromParts(
            serverDomain = "pbx.example.com",
            username = "gw-user",
            localSendMode = false,
            callerIdNumber = null,
            callerIdName = null
        )
        assertEquals("gw-user", fallbackUser)
        assertEquals(null, fallbackDisplay)
    }

    @Test
    fun localPeerFreshnessExpiresAfter65Seconds() {
        assertTrue(SipClient.isPeerFresh(1000L, 65_999L))
        assertFalse(SipClient.isPeerFresh(1000L, 66_001L))
        assertFalse(SipClient.isPeerFresh(0L, 1000L))
        assertFalse(SipClient.isPeerFresh(-1L, 1000L))

        val client = SipClient(
            username = "gw",
            password = "pw",
            serverDomain = "192.168.1.85",
            localSendMode = true
        )
        assertEquals(0L, client.lastPeerSeenMs)
        assertFalse(client.isLocalPeerFresh(nowMs = 1000L))
        client.suppressLocalResendForCall("call-suppress-1")
    }

    @Test
    fun ackAndByeIncludeReversedRouteHeaders() {
        val routes = listOf("<sip:proxy2;lr>", "<sip:proxy1;lr>")

        val ackRaw = SipBuilder.ack(
            targetUri = "sip:receiver@10.0.0.2:5061;transport=tls",
            via = null,
            toHeader = "<sip:receiver@sip.linphone.org>;tag=remote1",
            fromHeader = "<sip:t-switch_pusher@sip.linphone.org>;tag=local1",
            callId = "route-call-1",
            cseq = 2,
            username = "t-switch_pusher",
            localIp = "10.0.0.5",
            localPort = 45123,
            routeHeaders = routes
        )
        val parsedAck = SipMessage.parse(ackRaw)!!
        assertEquals(routes, parsedAck.headers("route"))
        val idxAck2 = ackRaw.indexOf("Route: <sip:proxy2;lr>\r\n")
        val idxAck1 = ackRaw.indexOf("Route: <sip:proxy1;lr>\r\n")
        assertTrue("ACK missing Route: <sip:proxy2;lr>", idxAck2 >= 0)
        assertTrue("ACK missing Route: <sip:proxy1;lr>", idxAck1 > idxAck2)

        val byeRaw = SipBuilder.bye(
            targetUri = "sip:receiver@10.0.0.2:5061;transport=tls",
            fromHeader = "<sip:t-switch_pusher@sip.linphone.org>;tag=local1",
            toHeader = "<sip:receiver@sip.linphone.org>;tag=remote1",
            callId = "route-call-1",
            cseq = 3,
            username = "t-switch_pusher",
            localIp = "10.0.0.5",
            localPort = 45123,
            routeHeaders = routes
        )
        val parsedBye = SipMessage.parse(byeRaw)!!
        assertEquals(routes, parsedBye.headers("route"))
        val idxBye2 = byeRaw.indexOf("Route: <sip:proxy2;lr>\r\n")
        val idxBye1 = byeRaw.indexOf("Route: <sip:proxy1;lr>\r\n")
        assertTrue("BYE missing Route: <sip:proxy2;lr>", idxBye2 >= 0)
        assertTrue("BYE missing Route: <sip:proxy1;lr>", idxBye1 > idxBye2)

        // Default empty routeHeaders emits no Route header
        val plainBye = SipBuilder.bye(
            targetUri = "sip:receiver@pbx.example.com",
            fromHeader = "<sip:gw@pbx.example.com>;tag=local1",
            toHeader = "<sip:receiver@pbx.example.com>;tag=remote1",
            callId = "route-call-2",
            cseq = 3,
            username = "gw",
            localIp = "10.0.0.5",
            localPort = 5060
        )
        assertFalse(plainBye.contains("Route:"))
    }

    @Test
    fun localSendModeMakeCallNeverTriggersLegacyPushWake() {
        val client = SipClient(
            username = "tswitch",
            password = "",
            serverDomain = "192.168.43.1",
            localIp = "192.168.43.1",
            localPort = 5060,
            localSendMode = true
        )
        client.setRegisteredPeerForTest("192.168.43.2" to 5060)

        val wakeTriggered = CountDownLatch(1)
        LocalSendController.pushWakeTriggerListenerForTest = { wakeTriggered.countDown() }

        val call = client.makeCall(
            targetExtension = "+905551234567",
            localRtpPort = 10000,
            callerIdNumber = "+905551234567"
        )
        try {
            assertFalse(
                "SipClient.makeCall must not trigger legacy 500ms PushKit wake; CallOrchestrator owns failover",
                wakeTriggered.await(700, TimeUnit.MILLISECONDS)
            )
        } finally {
            client.removeCall(call.callId)
        }
    }

    @Test
    fun failedStartResetsRunningFlagSoClientCanRetry() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val client = SipClient(
            username = "t-switch_pusher",
            password = "secret",
            serverDomain = "127.0.0.1",
            serverPort = closedPort,
            localIp = "127.0.0.1",
            localPort = 5063,
            useTls = true,
            localSendMode = false
        )

        var threw = false
        try {
            client.start()
        } catch (_: Throwable) {
            threw = true
        }

        assertTrue("Expected initial start() against closed port to fail", threw)
        assertFalse(
            "Failed start() must reset running=false so subsequent recovery/start can retry",
            client.isRunning
        )
    }

    @Test
    fun inboundMessageDeduplicatesSameCallIdAndCSeq() {
        val seen = java.util.concurrent.ConcurrentHashMap<String, Long>()
        assertFalse(SipClient.isDuplicateInboundMessage(seen, "call-1", "1 MESSAGE", 1_000L))
        assertTrue(SipClient.isDuplicateInboundMessage(seen, "call-1", "1 MESSAGE", 2_000L))
        assertFalse(SipClient.isDuplicateInboundMessage(seen, "call-1", "2 MESSAGE", 2_500L))
        assertFalse(SipClient.isDuplicateInboundMessage(seen, "call-2", "1 MESSAGE", 2_500L))
        // After 60s TTL, same key is accepted again
        assertFalse(SipClient.isDuplicateInboundMessage(seen, "call-1", "1 MESSAGE", 62_000L))
    }

    @Test
    fun provisional1xxMessageStatusesIncluding110PushSentKeepWaiting() {
        assertTrue(SipClient.isProvisionalMessageStatus(100))
        assertTrue(SipClient.isProvisionalMessageStatus(110))
        assertTrue(SipClient.isProvisionalMessageStatus(180))
        assertTrue(SipClient.isProvisionalMessageStatus(199))
        assertFalse(SipClient.isProvisionalMessageStatus(200))
        assertFalse(SipClient.isProvisionalMessageStatus(202))
        assertFalse(SipClient.isProvisionalMessageStatus(401))
        assertFalse(SipClient.isProvisionalMessageStatus(407))
        assertFalse(SipClient.isProvisionalMessageStatus(486))
    }
}
