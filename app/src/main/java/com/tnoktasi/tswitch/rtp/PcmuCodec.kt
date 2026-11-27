package com.tnoktasi.tswitch.rtp

/**
 * ITU-T G.711 µ-law (PCMU, PT 0) narrowband 8 kHz codec.
 */
object PcmuCodec {
    /** Encoded µ-law representation of linear sample 0. */
    const val SILENCE_BYTE: Byte = 0xFF.toByte()

    private const val BIAS = 0x84
    private const val CLIP = 32635

    /**
     * Encode a single 16-bit linear PCM sample to 8-bit ITU-T G.711 µ-law.
     */
    fun encodeSample(pcm: Short): Byte {
        var sample = pcm.toInt()
        val mask: Int
        if (sample < 0) {
            sample = -sample
            mask = 0x7F
        } else {
            mask = 0xFF
        }
        if (sample > CLIP) sample = CLIP
        sample += BIAS

        var seg = 0
        var shifted = sample shr 8
        while (shifted > 0) {
            seg++
            shifted = shifted shr 1
        }
        val mantissa = (sample shr (seg + 3)) and 0x0F
        return (((seg shl 4) or mantissa) xor mask).toByte()
    }

    /**
     * Decode a single 8-bit ITU-T G.711 µ-law byte to 16-bit linear PCM.
     */
    fun decodeSample(ulaw: Byte): Short {
        val inv = (ulaw.toInt() and 0xFF) xor 0xFF
        val sign = inv and 0x80
        val exponent = (inv shr 4) and 0x07
        val mantissa = inv and 0x0F
        val sample = (((mantissa shl 3) + BIAS) shl exponent) - BIAS
        return (if (sign != 0) -sample else sample).toShort()
    }

    /**
     * Encode little-endian 16-bit PCM bytes (8 kHz) to 8-bit µ-law.
     */
    fun encode(pcm: ByteArray): ByteArray {
        val sampleCount = pcm.size / 2
        val out = ByteArray(sampleCount)
        for (i in 0 until sampleCount) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort()
            out[i] = encodeSample(sample)
        }
        return out
    }

    /**
     * Decode 8-bit µ-law bytes to little-endian 16-bit PCM (8 kHz).
     */
    fun decode(ulaw: ByteArray): ByteArray {
        val out = ByteArray(ulaw.size * 2)
        var outIdx = 0
        for (byte in ulaw) {
            val sample = decodeSample(byte).toInt()
            out[outIdx++] = (sample and 0xFF).toByte()
            out[outIdx++] = ((sample shr 8) and 0xFF).toByte()
        }
        return out
    }
}
