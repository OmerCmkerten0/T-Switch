package com.callagent.gateway.sip

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater

/**
 * Byte-accurate SIP stream framer for TLS/TCP transports (RFC 3261 §18 & RFC 5626).
 *
 * Accumulates raw bytes rather than decoded characters so that:
 * 1. `Content-Length` is always measured in raw bytes (preventing deadlocks on
 *    multi-byte UTF-8 bodies or compressed binary bodies such as Linphone's
 *    `Content-Encoding: deflate` `message/cpim` chats).
 * 2. Multi-byte UTF-8 sequences split across TCP chunks are buffered intact and
 *    decoded only after a complete message has arrived.
 * 3. Leading CRLF keepalives (RFC 5626) between messages are skipped cleanly.
 */
class TlsFrameBuffer {

    private var buffer = ByteArray(INITIAL_CAPACITY)
    private var size = 0

    fun append(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        if (length <= 0) return
        require(offset >= 0 && offset <= bytes.size && length <= bytes.size - offset) {
            "Invalid range: offset=$offset, length=$length, arraySize=${bytes.size}"
        }
        ensureCapacity(size + length)
        System.arraycopy(bytes, offset, buffer, size, length)
        size += length
    }

    fun takeMessage(): String? {
        while (true) {
            // RFC 5626 keepalives are bare CRLFs between messages. They are
            // not messages and must not be parsed as one.
            var start = 0
            while (start < size && (buffer[start] == CR || buffer[start] == LF)) {
                start++
            }
            if (start > 0) {
                consume(start)
            }
            if (size == 0) return null

            val headerEnd = findHeaderEnd()
            if (headerEnd < 0) return null

            val headers = String(buffer, 0, headerEnd, Charsets.UTF_8)
            val bodyLen = CONTENT_LENGTH.find(headers)
                ?.groupValues?.get(1)?.trim()?.toIntOrNull() ?: 0
            if (bodyLen < 0) return null

            val headerAndSep = headerEnd + 4
            if (size - headerAndSep < bodyLen) return null

            val totalBytes = headerAndSep + bodyLen
            val message = if (bodyLen == 0) {
                String(buffer, 0, totalBytes, Charsets.UTF_8)
            } else {
                val bodyBytes = buffer.copyOfRange(headerAndSep, totalBytes)
                val bodyText = decodeBodyBytes(headers, bodyBytes)
                "$headers\r\n\r\n$bodyText"
            }
            consume(totalBytes)
            return message
        }
    }

    fun clear() {
        size = 0
        if (buffer.size > MAX_RETAINED_CAPACITY) {
            buffer = ByteArray(INITIAL_CAPACITY)
        }
    }

    private fun findHeaderEnd(): Int {
        val limit = size - 4
        var i = 0
        while (i <= limit) {
            if (buffer[i] == CR &&
                buffer[i + 1] == LF &&
                buffer[i + 2] == CR &&
                buffer[i + 3] == LF
            ) {
                return i
            }
            i++
        }
        return -1
    }

    private fun consume(count: Int) {
        val remaining = size - count
        if (remaining > 0) {
            System.arraycopy(buffer, count, buffer, 0, remaining)
        }
        size = remaining.coerceAtLeast(0)
        if (size == 0 && buffer.size > MAX_RETAINED_CAPACITY) {
            buffer = ByteArray(INITIAL_CAPACITY)
        }
    }

    private fun ensureCapacity(needed: Int) {
        if (needed <= buffer.size) return
        var newCapacity = buffer.size.coerceAtLeast(INITIAL_CAPACITY)
        while (newCapacity < needed) {
            newCapacity *= 2
        }
        buffer = buffer.copyOf(newCapacity)
    }

    companion object {
        private const val INITIAL_CAPACITY = 4096
        private const val MAX_RETAINED_CAPACITY = 65_536
        private const val MAX_DECOMPRESSED_BYTES = 256 * 1024
        private const val CR: Byte = 0x0D
        private const val LF: Byte = 0x0A

        /** Both spellings are legal; `l` is the compact form (RFC 3261 §7.3.3). */
        private val CONTENT_LENGTH = Regex(
            "^(?:Content-Length|l)\\s*:\\s*(\\d+)\\s*$",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
        )

        /** Compact form `e` per RFC 3261 §7.3.3. */
        private val CONTENT_ENCODING = Regex(
            "^(?:Content-Encoding|e)\\s*:\\s*([^\\r\\n]+)$",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
        )

        /**
         * Decodes raw SIP body bytes to UTF-8, transparently inflating
         * `Content-Encoding: deflate` / `gzip` (used by Linphone iOS / Flexisip
         * for `message/cpim` chats) before UTF-8 decoding can corrupt binary bytes.
         */
        fun decodeBodyBytes(headers: String, bodyBytes: ByteArray): String {
            if (bodyBytes.isEmpty()) return ""
            val encoding = CONTENT_ENCODING.find(headers)
                ?.groupValues?.get(1)?.trim()?.lowercase().orEmpty()
            val looksLikeZlib = bodyBytes.size >= 2 &&
                (bodyBytes[0].toInt() and 0xFF) == 0x78 &&
                (bodyBytes[1].toInt() and 0xFF).let { it == 0x01 || it == 0x5E || it == 0x9C || it == 0xDA }
            val looksLikeGzip = bodyBytes.size >= 2 &&
                (bodyBytes[0].toInt() and 0xFF) == 0x1F &&
                (bodyBytes[1].toInt() and 0xFF) == 0x8B

            if (encoding.contains("gzip") || looksLikeGzip) {
                runCatching { return gunzip(bodyBytes) }
            }
            if (encoding.contains("deflate") || looksLikeZlib) {
                runCatching { return inflateZlib(bodyBytes, nowrap = false) }
                runCatching { return inflateZlib(bodyBytes, nowrap = true) }
            }
            return String(bodyBytes, Charsets.UTF_8)
        }

        /** Decodes a complete UDP datagram (headers + optional compressed body). */
        fun decodeDatagram(bytes: ByteArray, length: Int): String {
            var headerEnd = -1
            val limit = length - 4
            var i = 0
            while (i <= limit) {
                if (bytes[i] == CR && bytes[i + 1] == LF && bytes[i + 2] == CR && bytes[i + 3] == LF) {
                    headerEnd = i
                    break
                }
                i++
            }
            if (headerEnd < 0) return String(bytes, 0, length, Charsets.UTF_8)
            val headers = String(bytes, 0, headerEnd, Charsets.UTF_8)
            val bodyStart = headerEnd + 4
            if (bodyStart >= length) return "$headers\r\n\r\n"
            val bodyText = decodeBodyBytes(headers, bytes.copyOfRange(bodyStart, length))
            return "$headers\r\n\r\n$bodyText"
        }

        private fun inflateZlib(input: ByteArray, nowrap: Boolean): String {
            val inflater = Inflater(nowrap)
            try {
                inflater.setInput(input)
                val out = ByteArrayOutputStream(input.size * 2)
                val chunk = ByteArray(1024)
                while (!inflater.finished() && out.size() < MAX_DECOMPRESSED_BYTES) {
                    val count = inflater.inflate(chunk)
                    if (count == 0) {
                        if (inflater.needsInput() || inflater.needsDictionary()) break
                    } else {
                        val toWrite = minOf(count, MAX_DECOMPRESSED_BYTES - out.size())
                        out.write(chunk, 0, toWrite)
                    }
                }
                if (out.size() == 0) error("Empty inflate output")
                return out.toString(Charsets.UTF_8.name())
            } finally {
                inflater.end()
            }
        }

        private fun gunzip(input: ByteArray): String {
            GZIPInputStream(ByteArrayInputStream(input)).use { gis ->
                val out = ByteArrayOutputStream(input.size * 2)
                val chunk = ByteArray(1024)
                while (out.size() < MAX_DECOMPRESSED_BYTES) {
                    val count = gis.read(chunk)
                    if (count <= 0) break
                    val toWrite = minOf(count, MAX_DECOMPRESSED_BYTES - out.size())
                    out.write(chunk, 0, toWrite)
                }
                return out.toString(Charsets.UTF_8.name())
            }
        }
    }
}
