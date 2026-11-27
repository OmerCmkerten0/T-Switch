package com.tnoktasi.tswitch.rtp

import android.content.Context
import android.os.Build
import android.util.Log
import com.tnoktasi.tswitch.RootShell
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

/**
 * Stateful linear resampler producing 20 ms 48 kHz mono 16-bit LE PCM frames (960 samples = 1920 bytes)
 * from 8 kHz or 16 kHz gateway RTP frames while preserving cross-frame sample continuity.
 */
class Pcm48kResampler {
    private var prevSample: Int = 0
    private val outBuf = ByteArray(FRAME_BYTES_48K_20MS)

    fun reset() {
        prevSample = 0
    }

    fun resampleTo48kMono(pcmLe16: ByteArray, inputRate: Int): ByteArray {
        val sampleCount = pcmLe16.size / 2
        if (sampleCount <= 0) return silence20ms()

        if (inputRate == TARGET_RATE_HZ) {
            val copyLen = minOf(pcmLe16.size, outBuf.size)
            System.arraycopy(pcmLe16, 0, outBuf, 0, copyLen)
            if (copyLen < outBuf.size) {
                java.util.Arrays.fill(outBuf, copyLen, outBuf.size, 0.toByte())
            }
            val lastIdx = (copyLen / 2 - 1) * 2
            prevSample = readLe16(outBuf, lastIdx)
            return outBuf
        }

        val ratio = when (inputRate) {
            16000 -> 3
            8000 -> 6
            else -> (TARGET_RATE_HZ / inputRate.coerceAtLeast(1)).coerceAtLeast(1)
        }

        val outBytesNeeded = sampleCount * ratio * 2
        val target = if (outBytesNeeded == outBuf.size) outBuf else ByteArray(outBytesNeeded)

        var prev = prevSample
        var outPos = 0
        for (i in 0 until sampleCount) {
            val curr = readLe16(pcmLe16, i * 2)
            for (k in 1..ratio) {
                val interp = if (k == ratio) {
                    curr
                } else {
                    (prev * (ratio - k) + curr * k) / ratio
                }.coerceIn(-32768, 32767)
                target[outPos++] = (interp and 0xFF).toByte()
                target[outPos++] = ((interp shr 8) and 0xFF).toByte()
            }
            prev = curr
        }
        prevSample = prev
        return target
    }

    /**
     * Generates a 20 ms 48 kHz mono silence frame, smoothly ramping any non-zero
     * [prevSample] to 0 over the first 1 ms (48 samples) to avoid clicks.
     */
    fun silence20ms(): ByteArray {
        val start = prevSample
        if (start == 0) {
            java.util.Arrays.fill(outBuf, 0.toByte())
            return outBuf
        }
        val rampSamples = RAMP_TO_ZERO_SAMPLES
        for (i in 0 until TOTAL_SAMPLES_48K_20MS) {
            val sample = if (i < rampSamples) {
                (start * (rampSamples - 1 - i)) / rampSamples
            } else {
                0
            }
            outBuf[i * 2] = (sample and 0xFF).toByte()
            outBuf[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        prevSample = 0
        return outBuf
    }

    private fun readLe16(buf: ByteArray, offset: Int): Int {
        val lo = buf[offset].toInt() and 0xFF
        val hi = buf[offset + 1].toInt()
        return (hi shl 8) or lo
    }

    companion object {
        const val TARGET_RATE_HZ = 48000
        const val TOTAL_SAMPLES_48K_20MS = 960
        const val FRAME_BYTES_48K_20MS = TOTAL_SAMPLES_48K_20MS * 2
        private const val RAMP_TO_ZERO_SAMPLES = 48 // 1 ms @ 48 kHz
    }
}

/**
 * Root-backed direct ALSA PCM playback stream for devices whose stock Android
 * audio policy lacks an `incall_music_uplink` AudioTrack output (e.g., GM 8 MSM8937).
 *
 * Spawns bundled `libalsaplay.so` under `su` pointing at `/dev/snd/pcmC0D1p`
 * (`MultiMedia2`, SIM 1) or `/dev/snd/pcmC0D14p` (`MultiMedia9`, SIM 2), atomically
 * configures Qualcomm ADM/Q6ASM mixer controls (`Audio Stream App Type Cfg`,
 * `Playback Channel Map`, atomic voice mute arrays, `Incall_Music`), and writes
 * 48 kHz mono S16_LE frames over stdin.
 */
class AlsaPcmPlayback(private val context: Context) {

    private val resampler = Pcm48kResampler()
    private val writeLock = Any()
    private val closed = AtomicBoolean(false)

    @Volatile private var process: Process? = null
    @Volatile private var stdin: OutputStream? = null
    @Volatile private var activeDeviceId: Int = ALSA_DEV_SIM1_MULTIMEDIA2
    @Volatile private var periodBytes: Int = DEFAULT_PERIOD_BYTES_5MS
    @Volatile private var writeErrorLogged = false

    fun open(): Boolean {
        if (closed.get()) return false
        val binPath = resolveBinaryPath(context)
        if (binPath.isNullOrEmpty()) {
            Log.e(TAG, "libalsaplay.so not found in nativeLibraryDir or APK")
            return false
        }

        val pcm35Status = runCatching {
            RootShell.execForOutput("cat /proc/asound/card0/pcm35p/sub0/status 2>/dev/null", timeoutMs = 2000)
        }.getOrNull()
        val devId = selectDeviceId(pcm35Status)
        activeDeviceId = devId
        val (periodSize, periodCount) = periodConfigForDevice(devId)
        periodBytes = periodSize * 2

        val cmd = "exec \"$binPath\" -D 0 -d $devId -r 48000 -c 1 -p $periodSize -n $periodCount"
        Log.i(TAG, "Opening root ALSA uplink: $cmd")

        return try {
            val proc = ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(false)
                .start()
            process = proc
            stdin = proc.outputStream

            val readyLatch = CountDownLatch(1)
            val readyOk = AtomicBoolean(false)
            val errLines = mutableListOf<String>()

            Thread({
                try {
                    BufferedReader(InputStreamReader(proc.errorStream)).use { br ->
                        var line = br.readLine()
                        while (line != null) {
                            Log.w(TAG, "alsaplay stderr: $line")
                            synchronized(errLines) {
                                if (errLines.size < 10) errLines.add(line)
                            }
                            line = br.readLine()
                        }
                    }
                } catch (_: Exception) {}
            }, "alsaplay-err").apply { isDaemon = true }.start()

            Thread({
                try {
                    BufferedReader(InputStreamReader(proc.inputStream)).use { br ->
                        var line = br.readLine()
                        while (line != null) {
                            Log.i(TAG, "alsaplay: $line")
                            if (line.startsWith("READY")) {
                                readyOk.set(true)
                                readyLatch.countDown()
                            }
                            line = br.readLine()
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    readyLatch.countDown()
                }
            }, "alsaplay-out").apply { isDaemon = true }.start()

            val signaled = readyLatch.await(READY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!signaled || !readyOk.get() || !proc.isAlive) {
                val errSummary = synchronized(errLines) { errLines.joinToString(" | ") }
                Log.e(TAG, "alsaplay failed to start (signaled=$signaled ready=${readyOk.get()} alive=${proc.isAlive} err=$errSummary)")
                close()
                false
            } else {
                resampler.reset()
                Log.i(TAG, "Root ALSA uplink READY on card=0 dev=$devId (48kHz mono)")
                Thread({
                    try {
                        Thread.sleep(800)
                        if (!closed.get()) {
                            applyCallMutes(context)
                        }
                    } catch (_: InterruptedException) {
                    }
                }, "alsaplay-remute").apply { isDaemon = true }.start()
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to spawn alsaplay: ${e.message}", e)
            close()
            false
        }
    }

    fun writeFrame20ms(pcmLe16: ByteArray, inputRate: Int): Boolean {
        if (closed.get()) return false
        val frame48k = resampler.resampleTo48kMono(pcmLe16, inputRate)
        return writeRawBytes(frame48k)
    }

    fun writeSilence20ms(): Boolean {
        if (closed.get()) return false
        return writeRawBytes(resampler.silence20ms())
    }

    private fun writeRawBytes(data: ByteArray): Boolean {
        synchronized(writeLock) {
            if (closed.get()) return false
            val proc = process ?: return false
            val out = stdin ?: return false
            if (!proc.isAlive) {
                if (!writeErrorLogged) {
                    writeErrorLogged = true
                    Log.w(TAG, "alsaplay process exited unexpectedly")
                }
                return false
            }
            return try {
                val step = periodBytes.coerceAtLeast(DEFAULT_PERIOD_BYTES_5MS)
                var offset = 0
                while (offset < data.size) {
                    val len = minOf(step, data.size - offset)
                    out.write(data, offset, len)
                    offset += len
                }
                out.flush()
                true
            } catch (e: Exception) {
                if (!writeErrorLogged) {
                    writeErrorLogged = true
                    Log.w(TAG, "alsaplay write failed: ${e.message}")
                }
                false
            }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(writeLock) {
            try { stdin?.close() } catch (_: Exception) {}
            stdin = null
            val proc = process
            process = null
            if (proc != null) {
                try {
                    if (!proc.waitFor(150, TimeUnit.MILLISECONDS)) {
                        proc.destroyForcibly()
                    }
                } catch (_: Exception) {
                    try { proc.destroyForcibly() } catch (_: Exception) {}
                }
            }
        }
        Log.i(TAG, "Root ALSA uplink closed (dev=$activeDeviceId)")
    }

    companion object {
        private const val TAG = "AlsaPcmPlayback"
        private const val BINARY_NAME = "libalsaplay.so"
        private const val TMP_BINARY_PATH = "/data/local/tmp/alsaplay"
        private const val DEFAULT_PERIOD_BYTES_5MS = 480 // 240 mono 16-bit samples @ 48 kHz
        private const val READY_TIMEOUT_MS = 1500L

        const val ALSA_DEV_SIM1_MULTIMEDIA2 = 1
        const val ALSA_DEV_SIM2_MULTIMEDIA9 = 14

        fun resolveBinaryPath(context: Context): String? {
            val nativeDir = context.applicationInfo.nativeLibraryDir
            if (!nativeDir.isNullOrEmpty()) {
                val direct = File(nativeDir, BINARY_NAME)
                if (direct.exists() && direct.canExecute()) {
                    return direct.absolutePath
                }
            }
            return extractBinaryToTmp(context)
        }

        private fun extractBinaryToTmp(context: Context): String? {
            val apkPath = context.applicationInfo.sourceDir ?: return null
            val tmpCache = File(context.cacheDir, BINARY_NAME)
            val abis = (Build.SUPPORTED_ABIS?.toList().orEmpty() + listOf("arm64-v8a", "armeabi-v7a")).distinct()
            return try {
                ZipFile(apkPath).use { zip ->
                    val entry = abis.firstNotNullOfOrNull { abi ->
                        zip.getEntry("lib/$abi/$BINARY_NAME")
                    } ?: return null
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(tmpCache).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                RootShell.execForOutput(
                    "cp \"${tmpCache.absolutePath}\" $TMP_BINARY_PATH && chmod 755 $TMP_BINARY_PATH && ls -l $TMP_BINARY_PATH",
                    timeoutMs = 3000
                )
                tmpCache.delete()
                TMP_BINARY_PATH
            } catch (e: Exception) {
                Log.w(TAG, "APK binary extraction failed: ${e.message}")
                null
            }
        }

        fun applyCallMutes(context: Context) {
            val binPath = resolveBinaryPath(context) ?: return
            runCatching {
                val out = RootShell.execForOutput("\"$binPath\" -D 0 --mute-call", timeoutMs = 2000)
                Log.i(TAG, "Atomic call mutes: $out")
            }.onFailure { e ->
                Log.w(TAG, "Atomic call mutes failed: ${e.message}")
            }
        }

        fun restoreCallMutes(context: Context) {
            val binPath = resolveBinaryPath(context) ?: return
            runCatching {
                val out = RootShell.execForOutput("\"$binPath\" -D 0 --unmute-call", timeoutMs = 2000)
                Log.i(TAG, "Atomic call unmute: $out")
            }.onFailure { e ->
                Log.w(TAG, "Atomic call unmute failed: ${e.message}")
            }
        }

        fun selectDeviceId(pcm35Status: String?): Int {
            return if (pcm35Status?.contains("RUNNING", ignoreCase = true) == true) {
                ALSA_DEV_SIM2_MULTIMEDIA9
            } else {
                ALSA_DEV_SIM1_MULTIMEDIA2
            }
        }

        fun periodConfigForDevice(devId: Int): Pair<Int, Int> {
            return if (devId == ALSA_DEV_SIM2_MULTIMEDIA9) {
                960 to 2 // pcmC0D14p (MultiMedia9) period_size 960, period_count 2
            } else {
                240 to 2 // pcmC0D1p (MultiMedia2) matches vendor HAL pcm_config_incall_music
            }
        }
    }
}
