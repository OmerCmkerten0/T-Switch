package com.callagent.gateway.sip

/**
 * Byte-accurate SIP stream framer for TLS/TCP transports (RFC 3261 §18 & RFC 5626).
 *
 * Accumulates raw bytes rather than decoded characters so that:
 * 1. `Content-Length` is always measured in UTF-8 bytes (preventing deadlocks on
 *    multi-byte UTF-8 bodies such as Turkish characters or emoji).
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
            val message = String(buffer, 0, totalBytes, Charsets.UTF_8)
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

    private companion object {
        const val INITIAL_CAPACITY = 4096
        const val MAX_RETAINED_CAPACITY = 65_536
        const val CR: Byte = 0x0D
        const val LF: Byte = 0x0A

        /** Both spellings are legal; `l` is the compact form (RFC 3261 §7.3.3). */
        val CONTENT_LENGTH = Regex(
            "^(?:Content-Length|l)\\s*:\\s*(\\d+)\\s*$",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
        )
    }
}
