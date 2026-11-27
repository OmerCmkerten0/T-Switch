package com.tnoktasi.tswitch.sip

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SipMessageTest {

    @After
    fun resetSipBuilderTransport() {
        SipBuilder.transport = "UDP"
    }

    @Test
    fun parsePreservesMultipleViaAndRecordRouteHeaders() {
        val raw = buildString {
            append("INVITE sip:t-switch_pusher@10.0.0.5:45123;transport=tls SIP/2.0\r\n")
            append("Via: SIP/2.0/TLS proxy1.linphone.org:5061;branch=z9hG4bKfirst\r\n")
            append("Via: SIP/2.0/TLS proxy2.linphone.org:5061;branch=z9hG4bKsecond\r\n")
            append("Record-Route: <sip:proxy1.linphone.org:5061;transport=tls;lr>\r\n")
            append("Record-Route: <sip:proxy2.linphone.org;lr>,<sip:edge.linphone.org:5061;transport=tls;lr>\r\n")
            append("From: <sip:caller@sip.linphone.org>;tag=from123\r\n")
            append("To: <sip:t-switch_pusher@sip.linphone.org>\r\n")
            append("Call-ID: multi-hdr-1\r\n")
            append("CSeq: 1 INVITE\r\n")
            append("Content-Length: 0\r\n\r\n")
        }

        val req = SipMessage.parse(raw)
        assertNotNull(req)
        req!!

        assertEquals(2, req.viaHeaders.size)
        assertEquals("SIP/2.0/TLS proxy1.linphone.org:5061;branch=z9hG4bKfirst", req.viaHeaders[0])
        assertEquals("SIP/2.0/TLS proxy2.linphone.org:5061;branch=z9hG4bKsecond", req.viaHeaders[1])
        // Backwards compatible single header() returns first value
        assertEquals(req.viaHeaders[0], req.via)

        assertEquals(3, req.recordRouteHeaders.size)
        assertEquals("<sip:proxy1.linphone.org:5061;transport=tls;lr>", req.recordRouteHeaders[0])
        assertEquals("<sip:proxy2.linphone.org;lr>", req.recordRouteHeaders[1])
        assertEquals("<sip:edge.linphone.org:5061;transport=tls;lr>", req.recordRouteHeaders[2])

        // Global transport is UDP, but transportOverride = "TLS" forces ;transport=tls in Contact
        SipBuilder.transport = "UDP"
        val ok = SipBuilder.ok200(
            msg = req,
            username = "t-switch_pusher",
            localIp = "10.0.0.5",
            localPort = 45123,
            toTag = "tag999",
            transportOverride = "TLS"
        )

        val parsedOk = SipMessage.parse(ok)
        assertNotNull(parsedOk)
        parsedOk!!
        assertEquals(req.viaHeaders, parsedOk.viaHeaders)
        assertTrue(
            "Expected Contact to contain ;transport=tls but got: ${parsedOk.contact}",
            parsedOk.contact?.contains(";transport=tls") == true
        )
    }

    @Test
    fun registerUsesDomainNotLocalIpInFromHeader() {
        val reg = SipBuilder.register(
            username = "t-switch_pusher",
            domain = "sip.linphone.org",
            serverPort = 5061,
            localIp = "10.0.0.5",
            localPort = 45123,
            callId = "cid",
            cseq = 1
        )

        assertTrue(
            "Expected From header to use @sip.linphone.org, got:\n$reg",
            reg.contains("From: <sip:t-switch_pusher@sip.linphone.org>;tag=")
        )
        assertFalse(
            "From header must not use @localIp",
            reg.contains("From: <sip:t-switch_pusher@10.0.0.5>")
        )
    }

    @Test
    fun responseBuildersEchoAllViaHeadersInOrder() {
        val raw = buildString {
            append("REGISTER sip:192.168.1.85:5060 SIP/2.0\r\n")
            append("Via: SIP/2.0/UDP 192.168.1.20:5060;branch=z9hG4bKtop;rport\r\n")
            append("Via: SIP/2.0/UDP 192.168.1.30:5060;branch=z9hG4bKbottom\r\n")
            append("From: <sip:zoiper@192.168.1.85>;tag=f1\r\n")
            append("To: <sip:zoiper@192.168.1.85>\r\n")
            append("Call-ID: via-echo-1\r\n")
            append("CSeq: 1 REGISTER\r\n")
            append("Content-Length: 0\r\n\r\n")
        }
        val req = SipMessage.parse(raw)!!

        val trying = SipMessage.parse(SipBuilder.trying100(req))!!
        assertEquals(req.viaHeaders, trying.viaHeaders)

        val ringing = SipMessage.parse(SipBuilder.ringing180(req, toTag = "t1"))!!
        assertEquals(req.viaHeaders, ringing.viaHeaders)

        val rejected = SipMessage.parse(SipBuilder.reject(req, 486, "Busy Here", toTag = "t1"))!!
        assertEquals(req.viaHeaders, rejected.viaHeaders)

        val status = SipMessage.parse(SipBuilder.statusResponse(req, 202, "Accepted", toTag = "t1"))!!
        assertEquals(req.viaHeaders, status.viaHeaders)

        val regOk = SipMessage.parse(SipBuilder.registerOk200(req, "zoiper", "192.168.1.20", 51234, toTag = "t1"))!!
        assertEquals(2, regOk.viaHeaders.size)
        assertTrue(regOk.viaHeaders[0].contains("received=192.168.1.20;rport=51234"))
        assertEquals(req.viaHeaders[1], regOk.viaHeaders[1])
    }

    @Test
    fun recordRouteDoesNotSplitCommasInsideAngleBracketsOrQuotes() {
        val raw = buildString {
            append("SIP/2.0 200 OK\r\n")
            append("Record-Route: \"Edge, Primary\" <sip:edge1.example.com;lr;x=1,2>, <sip:core.example.com;lr>\r\n")
            append("Content-Length: 0\r\n\r\n")
        }
        val msg = SipMessage.parse(raw)!!
        assertEquals(2, msg.recordRouteHeaders.size)
        assertEquals("\"Edge, Primary\" <sip:edge1.example.com;lr;x=1,2>", msg.recordRouteHeaders[0])
        assertEquals("<sip:core.example.com;lr>", msg.recordRouteHeaders[1])
    }
}
