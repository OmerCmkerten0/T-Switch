package com.callagent.gateway.rtp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PcmuCodecTest {

    /**
     * ITU-T G.711 reference µ-law encoder (Sun's g711.c linear2ulaw with
     * BIAS = 0x84, CLIP = 32635), kept independent of PcmuCodec.
     */
    private fun referenceEncode(pcm: Int): Int {
        val bias = 0x84
        val clip = 32635
        val segUend = intArrayOf(0xFF, 0x1FF, 0x3FF, 0x7FF, 0xFFF, 0x1FFF, 0x3FFF, 0x7FFF)
        var sample = pcm
        val mask: Int
        if (sample < 0) {
            sample = -sample
            mask = 0x7F
        } else {
            mask = 0xFF
        }
        if (sample > clip) sample = clip
        sample += bias
        val seg = segUend.indexOfFirst { sample <= it }
        if (seg < 0) return 0x7F xor mask
        val uval = (seg shl 4) or ((sample shr (seg + 3)) and 0x0F)
        return uval xor mask
    }

    @Test
    fun encoderMatchesItuReferenceForEverySample() {
        for (s in Short.MIN_VALUE..Short.MAX_VALUE) {
            val got = PcmuCodec.encodeSample(s.toShort()).toInt() and 0xFF
            assertEquals("sample $s", referenceEncode(s), got)
        }
    }

    @Test
    fun zeroEncodesToSilenceByte0xFF() {
        assertEquals(0xFF.toByte(), PcmuCodec.SILENCE_BYTE)
        assertEquals(0xFF.toByte(), PcmuCodec.encodeSample(0))
        assertTrue(abs(PcmuCodec.decodeSample(0xFF.toByte()).toInt()) <= 8)
    }

    @Test
    fun quietSamplesStayQuiet() {
        for (s in 16..255) {
            val back = PcmuCodec.decodeSample(PcmuCodec.encodeSample(s.toShort())).toInt()
            assertTrue("sample $s decoded to $back", abs(back - s) <= 8)
        }
    }

    @Test
    fun roundTripErrorBoundedEverywhere() {
        // Segment 7 has a step of 1024, so within the unclipped [-32635..32635]
        // range the error never exceeds 512. Above CLIP (32635), clipping adds
        // at most BIAS (132), bounding the full Short range within 644.
        for (s in Short.MIN_VALUE..Short.MAX_VALUE) {
            val back = PcmuCodec.decodeSample(PcmuCodec.encodeSample(s.toShort())).toInt()
            val limit = if (s in -32635..32635) 512 else 644
            assertTrue("sample $s decoded to $back (limit=$limit)", abs(back - s) <= limit)
        }
    }

    @Test
    fun batchEncodeDecodePreservesLengthAndLittleEndianOrder() {
        val sampleCount = 160
        val pcm = ByteArray(sampleCount * 2)
        for (i in 0 until sampleCount) {
            val sample = when (i % 4) {
                0 -> 0
                1 -> (i * 173) - 16000
                2 -> 16000 - (i * 151)
                else -> if (i % 2 == 0) 200 else -200
            }.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            pcm[i * 2] = (sample and 0xFF).toByte()
            pcm[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }

        val ulaw = PcmuCodec.encode(pcm)
        assertEquals(160, ulaw.size)

        val decoded = PcmuCodec.decode(ulaw)
        assertEquals(320, decoded.size)

        for (i in 0 until sampleCount) {
            val origLo = pcm[i * 2].toInt() and 0xFF
            val origHi = pcm[i * 2 + 1].toInt()
            val origSample = ((origHi shl 8) or origLo).toShort()
            assertEquals("encoded byte $i", PcmuCodec.encodeSample(origSample), ulaw[i])

            val decLo = decoded[i * 2].toInt() and 0xFF
            val decHi = decoded[i * 2 + 1].toInt()
            val decSample = ((decHi shl 8) or decLo).toShort()
            assertEquals("decoded sample $i", PcmuCodec.decodeSample(ulaw[i]), decSample)
        }

        val silencePcm = ByteArray(320)
        val silenceUlaw = PcmuCodec.encode(silencePcm)
        assertArrayEquals(ByteArray(160) { PcmuCodec.SILENCE_BYTE }, silenceUlaw)
        assertArrayEquals(ByteArray(320), PcmuCodec.decode(silenceUlaw))
    }
}
