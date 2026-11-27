package com.callagent.gateway.rtp

import com.callagent.gateway.DeviceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class AlsaPcmPlaybackTest {

    private fun shortsToLeBytes(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val s = samples[i].toInt()
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun leBytesToShorts(bytes: ByteArray): ShortArray {
        val out = ShortArray(bytes.size / 2)
        for (i in out.indices) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt()
            out[i] = ((hi shl 8) or lo).toShort()
        }
        return out
    }

    @Test
    fun resample16kTo48kProduces960ContinuousSamplesAcrossFrameBoundaries() {
        val resampler = Pcm48kResampler()

        // Frame 1: 320 samples (20 ms @ 16 kHz) of a 1 kHz sine wave
        val in1 = ShortArray(320) { i ->
            (12000.0 * sin(2.0 * PI * 1000.0 * i / 16000.0)).toInt().toShort()
        }
        val outBytes1 = resampler.resampleTo48kMono(shortsToLeBytes(in1), 16000)
        assertEquals(1920, outBytes1.size)
        val out1 = leBytesToShorts(outBytes1).copyOf()

        // Frame 2: next 320 samples continuing the 1 kHz sine wave
        val in2 = ShortArray(320) { i ->
            (12000.0 * sin(2.0 * PI * 1000.0 * (i + 320) / 16000.0)).toInt().toShort()
        }
        val outBytes2 = resampler.resampleTo48kMono(shortsToLeBytes(in2), 16000)
        assertEquals(1920, outBytes2.size)
        val out2 = leBytesToShorts(outBytes2).copyOf()

        // Continuity across frame boundary: diff between out1.last() and out2.first()
        // must be within the per-sample sine step (~1570 at 48 kHz), never a step jump
        val boundaryDiff = abs(out2[0].toInt() - out1[out1.size - 1].toInt())
        assertTrue("Expected smooth boundary transition, got diff=$boundaryDiff", boundaryDiff < 2200)

        // DC test + reset(): feeding non-zero DC, resetting, and feeding zero should not use old state
        val dcIn = ShortArray(320) { 6000 }
        val dcOut = leBytesToShorts(resampler.resampleTo48kMono(shortsToLeBytes(dcIn), 16000))
        assertEquals(6000, dcOut[dcOut.size - 1].toInt())

        resampler.reset()
        val zeroIn = ShortArray(320) { 0 }
        val afterReset = leBytesToShorts(resampler.resampleTo48kMono(shortsToLeBytes(zeroIn), 16000))
        assertEquals(0, afterReset[0].toInt())
        assertEquals(0, afterReset[1].toInt())
    }

    @Test
    fun resample8kTo48kProduces960ContinuousSamplesAcrossFrameBoundaries() {
        val resampler = Pcm48kResampler()

        // Frame 1: 160 samples (20 ms @ 8 kHz) of a 500 Hz sine wave
        val in1 = ShortArray(160) { i ->
            (10000.0 * sin(2.0 * PI * 500.0 * i / 8000.0)).toInt().toShort()
        }
        val outBytes1 = resampler.resampleTo48kMono(shortsToLeBytes(in1), 8000)
        assertEquals(1920, outBytes1.size)
        val out1 = leBytesToShorts(outBytes1).copyOf()

        // Frame 2: next 160 samples
        val in2 = ShortArray(160) { i ->
            (10000.0 * sin(2.0 * PI * 500.0 * (i + 160) / 8000.0)).toInt().toShort()
        }
        val outBytes2 = resampler.resampleTo48kMono(shortsToLeBytes(in2), 8000)
        assertEquals(1920, outBytes2.size)
        val out2 = leBytesToShorts(outBytes2).copyOf()

        val boundaryDiff = abs(out2[0].toInt() - out1[out1.size - 1].toInt())
        assertTrue("Expected smooth 8k->48k boundary, got diff=$boundaryDiff", boundaryDiff < 1200)
    }

    @Test
    fun selectIncallMusicPcmDeviceChoosesSim2WhenPcm35RunningElseSim1() {
        assertEquals(14, AlsaPcmPlayback.selectDeviceId("state: RUNNING\nhw_ptr: 123"))
        assertEquals(14, AlsaPcmPlayback.selectDeviceId("RUNNING"))
        assertEquals(1, AlsaPcmPlayback.selectDeviceId("closed"))
        assertEquals(1, AlsaPcmPlayback.selectDeviceId(""))
        assertEquals(1, AlsaPcmPlayback.selectDeviceId(null))
        assertEquals(240 to 2, AlsaPcmPlayback.periodConfigForDevice(1))
        assertEquals(960 to 2, AlsaPcmPlayback.periodConfigForDevice(14))
    }

    @Test
    fun msm8937ProfileKeepsVoiceRoutesAliveAndAvoidsBrokenTinymixMutes() {
        val profile = DeviceProfile.msm8937()
        assertTrue(profile.useRootAlsaPlayback)
        assertTrue(profile.primeCaptureBeforePlayback)
        val combined = listOf(profile.mixerSetupCmd, profile.mixerIncallMusicCmd, profile.mixerCaptureCmd)
        for (cmd in combined) {
            assertFalse("Must not disable VoiceMMode1 RX (triggers voc_disable_cvp)", cmd.contains("VoiceMMode1' 0"))
            assertFalse("Must not disable VoiceMMode1 TX (triggers voc_disable_cvp)", cmd.contains("TERT_MI2S_TX_MMode1' 0"))
            assertFalse("Must not write 3-arg Voice Tx Device Mute via tinymix", cmd.contains("Voice Tx Device Mute"))
            assertFalse("Must not write Incall_Music MM1", cmd.contains("Incall_Music Audio Mixer MultiMedia1' 1"))
            assertFalse("Must not write Incall_Music MM5", cmd.contains("Incall_Music Audio Mixer MultiMedia5' 1"))
        }
    }

    @Test
    fun silence20msRampsPreviousSampleSmoothlyToZero() {
        val resampler = Pcm48kResampler()
        val dcIn = ShortArray(320) { 10000 }
        resampler.resampleTo48kMono(shortsToLeBytes(dcIn), 16000)

        val silence = leBytesToShorts(resampler.silence20ms()).copyOf()
        assertEquals(960, silence.size)
        assertTrue("Expected ramp down from non-zero prevSample", silence[0] in 9000..9999)
        assertTrue("Expected monotonic decay during 1ms ramp", silence[10] < silence[0] && silence[10] > 0)
        assertEquals(0, silence[47].toInt())
        assertEquals(0, silence[959].toInt())

        // Subsequent silence20ms() should be all zeros
        val zeroSilence = leBytesToShorts(resampler.silence20ms()).copyOf()
        assertEquals(0, zeroSilence[0].toInt())
        assertEquals(0, zeroSilence[959].toInt())
    }
}
