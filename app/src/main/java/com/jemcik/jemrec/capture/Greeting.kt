package com.jemcik.jemrec.capture

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The answering machine's greeting, from the app's side.
 *
 * The recorder records it - the app holds no microphone permission (see
 * docs/ARCHITECTURE.md) - and plays it into calls. The app trims the take,
 * evens its level, keeps the master copy here, and sends the result to the
 * recorder, again whenever a recorder is built that does not have it.
 *
 * Raw 16-bit little-endian mono PCM at 48 kHz, as the recorder plays it.
 */
object Greeting {

    const val RATE = 48_000
    private const val FILE = "greeting.pcm"

    /** Thirty seconds at most, as the recorder records it. */
    const val MAX_SECONDS = 30

    fun file(context: Context): File = File(context.filesDir, FILE)

    fun present(context: Context): Boolean = file(context).let { it.isFile && it.length() > 0 }

    fun seconds(context: Context): Double = file(context).length() / 2.0 / RATE

    fun save(context: Context, pcm: ByteArray) {
        val next = File(context.filesDir, "$FILE.new")
        next.writeBytes(pcm)
        check(next.renameTo(file(context))) { "could not keep the greeting" }
    }

    fun delete(context: Context) {
        file(context).delete()
    }

    /** What processing a take came to: a greeting, or why there is none. */
    sealed interface Take {
        data class Usable(val pcm: ByteArray, val seconds: Double) : Take
        data object Silent : Take
        data object TooShort : Take
    }

    /** Twenty milliseconds: the window levels are measured over. */
    private const val WINDOW = RATE / 50
    /** Kept before the first word and after the last. */
    private const val LEAD_MS = 150
    private const val TAIL_MS = 300
    /** Shorter than this is a cough, not a greeting. */
    private const val MIN_MS = 800
    /** Speech level: the call's uplink plays at full volume with no control
     *  over it, so the greeting brings its own level. */
    private const val TARGET_RMS_DB = -20.0
    private const val PEAK_DB = -3.0
    private const val MAX_GAIN = 8.0

    /**
     * Trim the silence at both ends and even the level. Pure, for the tests.
     *
     * Speech is what stands 10 dB above the take's own quiet - its tenth
     * percentile - and above -50 dBFS whatever that quiet is. The level is set
     * by the speech's RMS, then limited so no peak goes past -3 dBFS.
     */
    fun process(take: ByteArray): Take {
        val samples = toShorts(take)
        if (samples.size < WINDOW) return Take.Silent
        val windows = samples.size / WINDOW
        val levels = DoubleArray(windows) { w -> rms(samples, w * WINDOW, WINDOW) }
        val floor = levels.sorted()[windows / 10]
        val threshold = max(dbToLinear(-50.0) * 32768, floor * dbToLinear(10.0))
        val first = levels.indexOfFirst { it >= threshold }
        val last = levels.indexOfLast { it >= threshold }
        if (first < 0) return Take.Silent
        val from = max(0, first * WINDOW - RATE * LEAD_MS / 1000)
        val to = min(samples.size, (last + 1) * WINDOW + RATE * TAIL_MS / 1000)
        if ((to - from) * 1000L / RATE < MIN_MS) return Take.TooShort

        val speech = (first..last).map { levels[it] }.filter { it >= threshold }
        val speechRms = sqrt(speech.sumOf { it * it } / speech.size)
        var peak = 1
        for (i in from until to) peak = max(peak, abs(samples[i].toInt()))
        val gain = min(
            min(dbToLinear(TARGET_RMS_DB) * 32768 / speechRms, dbToLinear(PEAK_DB) * 32768 / peak),
            MAX_GAIN,
        )
        val out = ShortArray(to - from) { i ->
            (samples[from + i] * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return Take.Usable(toBytes(out), out.size.toDouble() / RATE)
    }

    /** dBFS of a 16-bit RMS, for logs and tests. */
    internal fun db(linear: Double): Double = if (linear <= 0) -120.0 else 20 * log10(linear / 32768)

    private fun dbToLinear(db: Double): Double = 10.0.pow(db / 20)

    private fun rms(samples: ShortArray, from: Int, count: Int): Double {
        var sum = 0.0
        for (i in from until from + count) sum += samples[i].toDouble() * samples[i]
        return sqrt(sum / count)
    }

    internal fun toShorts(pcm: ByteArray): ShortArray = ShortArray(pcm.size / 2) { i ->
        ((pcm[2 * i].toInt() and 0xff) or (pcm[2 * i + 1].toInt() shl 8)).toShort()
    }

    internal fun toBytes(samples: ShortArray): ByteArray = ByteArray(samples.size * 2).also { out ->
        samples.forEachIndexed { i, s ->
            out[2 * i] = s.toInt().toByte()
            out[2 * i + 1] = (s.toInt() shr 8).toByte()
        }
    }

    /** Play [pcm] to the user, the way the caller will hear it but on the
     *  phone's own speaker. Returns the track, for stopping; null if the
     *  phone would not make one. */
    fun preview(pcm: ByteArray): AudioTrack? = runCatching {
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size)
            .build()
            .also { track ->
                track.write(pcm, 0, pcm.size)
                track.play()
            }
    }.getOrNull()
}
