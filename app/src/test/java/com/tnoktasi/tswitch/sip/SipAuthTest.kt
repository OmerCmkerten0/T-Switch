package com.tnoktasi.tswitch.sip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class SipAuthTest {

    @Test
    fun proxyAuthenticateProducesProxyAuthorizationHeader() {
        val raw407 = buildString {
            append("SIP/2.0 407 Proxy Authentication Required\r\n")
            append("Via: SIP/2.0/TLS 10.0.0.5:45123;branch=z9hG4bK123\r\n")
            append("From: <sip:t-switch_pusher@sip.linphone.org>;tag=from1\r\n")
            append("To: <sip:receiver@sip.linphone.org>;tag=to1\r\n")
            append("Call-ID: auth-407\r\n")
            append("CSeq: 1 INVITE\r\n")
            append("Proxy-Authenticate: Digest realm=\"sip.linphone.org\", nonce=\"n12345\", qop=\"auth\"\r\n")
            append("Content-Length: 0\r\n\r\n")
        }

        val msg = SipMessage.parse(raw407)!!
        val params = SipAuth.parseChallenge(msg)
        assertNotNull(params)
        params!!

        assertTrue(params.isProxy)
        assertEquals("MD5", params.algorithm)
        assertEquals("sip.linphone.org", params.realm)
        assertEquals("n12345", params.nonce)

        val header = SipAuth.buildAuthHeader(
            method = "INVITE",
            uri = "sip:receiver@sip.linphone.org",
            username = "t-switch_pusher",
            password = "secret",
            params = params
        )
        assertTrue(
            "Expected Proxy-Authorization header, got: $header",
            header.startsWith("Proxy-Authorization: Digest ")
        )
        assertTrue(header.contains("algorithm=MD5"))
    }

    @Test
    fun wwwAuthenticateProducesAuthorizationHeaderAndSupportsSha256() {
        val raw401 = buildString {
            append("SIP/2.0 401 Unauthorized\r\n")
            append("Via: SIP/2.0/TLS 10.0.0.5:45123;branch=z9hG4bK456\r\n")
            append("From: <sip:t-switch_pusher@sip.linphone.org>;tag=from2\r\n")
            append("To: <sip:t-switch_pusher@sip.linphone.org>;tag=to2\r\n")
            append("Call-ID: auth-401-sha256\r\n")
            append("CSeq: 1 REGISTER\r\n")
            append("WWW-Authenticate: Digest realm=\"sip.linphone.org\", nonce=\"shaNonce99\", algorithm=SHA-256, qop=\"auth\"\r\n")
            append("Content-Length: 0\r\n\r\n")
        }

        val msg = SipMessage.parse(raw401)!!
        val params = SipAuth.parseChallenge(msg)
        assertNotNull(params)
        params!!

        assertFalse(params.isProxy)
        assertEquals("SHA-256", params.algorithm)

        val header = SipAuth.buildAuthHeader(
            method = "REGISTER",
            uri = "sip:sip.linphone.org:5061",
            username = "t-switch_pusher",
            password = "secret",
            params = params
        )
        assertTrue(
            "Expected Authorization header, got: $header",
            header.startsWith("Authorization: Digest ")
        )
        assertTrue(header.contains("algorithm=SHA-256"))

        val responseHex = Regex("""response="([0-9a-f]+)"""").find(header)?.groupValues?.get(1)
        assertNotNull("Expected response hex in header: $header", responseHex)
        assertEquals(64, responseHex!!.length)
    }

    @Test
    fun sha256ResponseMatchesExpectedDigestWithoutQop() {
        val params = SipAuth.AuthParams(
            realm = "sip.linphone.org",
            nonce = "fixednonce",
            opaque = null,
            qop = null,
            isProxy = false,
            algorithm = "SHA-256"
        )
        val header = SipAuth.buildAuthHeader(
            method = "REGISTER",
            uri = "sip:sip.linphone.org:5061",
            username = "alice",
            password = "pw",
            params = params
        )

        fun sha256(s: String): String = MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        val ha1 = sha256("alice:sip.linphone.org:pw")
        val ha2 = sha256("REGISTER:sip:sip.linphone.org:5061")
        val expected = sha256("$ha1:fixednonce:$ha2")

        assertTrue(header.contains("response=\"$expected\""))
    }
}
