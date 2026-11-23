package com.callagent.gateway.sip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TlsFrameBufferTest {

    @Test
    fun turkishUtf8BodyFramesByByteLengthAndNotCharLength() {
        val body1 = "Şeker, ğümüş, ışık, çiçek, ördek, üzüm 🔔"
        val body1Bytes = body1.toByteArray(Charsets.UTF_8)
        // Sanity check: UTF-8 byte length is strictly greater than UTF-16 char length
        assert(body1Bytes.size > body1.length)

        val msg1 = buildString {
            append("MESSAGE sip:receiver@sip.linphone.org SIP/2.0\r\n")
            append("Via: SIP/2.0/TLS 10.0.0.5:5061;branch=z9hG4bK111\r\n")
            append("Call-ID: sms-tr-1\r\n")
            append("CSeq: 1 MESSAGE\r\n")
            append("Content-Type: text/plain;charset=UTF-8\r\n")
            append("Content-Length: ${body1Bytes.size}\r\n\r\n")
            append(body1)
        }

        val body2 = "Second message OK"
        val msg2 = buildString {
            append("MESSAGE sip:receiver@sip.linphone.org SIP/2.0\r\n")
            append("Via: SIP/2.0/TLS 10.0.0.5:5061;branch=z9hG4bK222\r\n")
            append("Call-ID: sms-tr-2\r\n")
            append("CSeq: 2 MESSAGE\r\n")
            append("Content-Type: text/plain;charset=UTF-8\r\n")
            append("Content-Length: ${body2.toByteArray(Charsets.UTF_8).size}\r\n\r\n")
            append(body2)
        }

        val buffer = TlsFrameBuffer()
        buffer.append((msg1 + msg2).toByteArray(Charsets.UTF_8))

        assertEquals(msg1, buffer.takeMessage())
        assertEquals(msg2, buffer.takeMessage())
        assertNull(buffer.takeMessage())
    }

    @Test
    fun splitMultiByteUtf8AcrossTcpChunksDecodesIntact() {
        val body = "aşa"
        val bodyBytes = body.toByteArray(Charsets.UTF_8) // 'a' (0x61), 'ş' (0xC5, 0x9F), 'a' (0x61)
        assertEquals(4, bodyBytes.size)
        assertEquals(0xC5.toByte(), bodyBytes[1])
        assertEquals(0x9F.toByte(), bodyBytes[2])

        val headers = buildString {
            append("MESSAGE sip:u@example.com SIP/2.0\r\n")
            append("Call-ID: split-utf8\r\n")
            append("CSeq: 1 MESSAGE\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n\r\n")
        }
        val fullBytes = headers.toByteArray(Charsets.UTF_8) + bodyBytes
        // Split right between 0xC5 and 0x9F of 'ş'
        val splitPoint = headers.toByteArray(Charsets.UTF_8).size + 2

        val buffer = TlsFrameBuffer()
        buffer.append(fullBytes, 0, splitPoint)
        assertNull(buffer.takeMessage())

        buffer.append(fullBytes, splitPoint, fullBytes.size - splitPoint)
        assertEquals(headers + body, buffer.takeMessage())
        assertNull(buffer.takeMessage())
    }

    @Test
    fun leadingCrlfKeepalivesAreSkipped() {
        val sipMsg = "SIP/2.0 200 OK\r\n" +
            "Via: SIP/2.0/TLS 10.0.0.5:5061;branch=z9hG4bK333\r\n" +
            "Call-ID: keepalive-1\r\n" +
            "CSeq: 1 OPTIONS\r\n" +
            "Content-Length: 0\r\n\r\n"

        val buffer = TlsFrameBuffer()
        buffer.append("\r\n\r\n".toByteArray(Charsets.UTF_8))
        assertNull(buffer.takeMessage())

        buffer.append(("\r\n\r\n" + sipMsg + "\r\n\r\n").toByteArray(Charsets.UTF_8))
        assertEquals(sipMsg, buffer.takeMessage())
        assertNull(buffer.takeMessage())
    }

    @Test
    fun compactContentLengthHeaderAndClearWork() {
        val body = "İğne"
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val sipMsg = "MESSAGE sip:u@example.com SIP/2.0\r\n" +
            "l: ${bodyBytes.size}\r\n\r\n" +
            body

        val buffer = TlsFrameBuffer()
        val raw = sipMsg.toByteArray(Charsets.UTF_8)
        buffer.append(raw, 0, raw.size - 2)
        assertNull(buffer.takeMessage())

        buffer.clear()
        buffer.append(raw)
        assertEquals(sipMsg, buffer.takeMessage())
        assertNull(buffer.takeMessage())
    }
}
