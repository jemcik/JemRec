package com.jemcik.jemrec.capture

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Did a saved recording actually catch anyone talking?
 *
 * WHY THE SAVE ITSELF CANNOT TELL
 *
 * The saver counts packets, and a packet is not a voice. On a phone whose
 * audio HAL mutes the call to third parties - always, or only on Wi-Fi
 * calling, or only on speaker - the capture still opens and the encoder still
 * produces twenty-millisecond packets for the whole call. They are packets of
 * silence. The file has the right length and plays, and the user finds out it
 * holds nothing on the day they need it. That is the most expensive way for a
 * call recorder to fail, and every recorder's issue tracker has it: "files
 * have bytes but play silent".
 *
 * So the finished file is decoded and measured, once, right after it is saved.
 *
 * WHAT IT MEASURES
 *
 * Twenty-millisecond frames, per channel, and whether each is louder than
 * [ACTIVE_RMS] - roughly -45 dBFS, well under speech and well over the
 * near-zero a muted capture produces. Then:
 *
 *   - SILENT: almost no frame on any channel had sound in it.
 *   - ONE_SIDE: the two channels carry DIFFERENT audio, one has sound and
 *     the other has none. MEASURED, NEVER REPORTED. It reads like "one side
 *     of the call is missing" and on the phone this was built on it is not:
 *     a 27-second call there had one channel at 10 active frames out of 1352
 *     and the other at 726 - and played back, both voices were in it. The
 *     Honor's capture can put the whole call in one channel. So a dead
 *     channel is logged, for the day the two sides are split on purpose,
 *     and the user is told only about a recording with no sound at all.
 *   - FINE, or UNKNOWN for a call too short to judge.
 *
 * It stops as soon as every channel has had a few seconds of sound, which for
 * an ordinary call is within its first minute, and never reads more than
 * [MAX_FRAMES] - ten minutes - so a silent hour-long file costs seconds, not a
 * minute of decoding.
 *
 * The thresholds are measured guesses, not facts about every phone, so every
 * verdict is logged with the numbers it came from.
 */
internal object AudioCheck {

    private const val TAG = "JemRec"
    private const val TIMEOUT_US = 10_000L

    /** About -45 dBFS in 16-bit samples. */
    internal const val ACTIVE_RMS = 180.0

    /** Three seconds of sound on every channel and the call is plainly fine. */
    internal const val ENOUGH_ACTIVE = 150

    /** Shorter than this and there is too little to judge. */
    internal const val MIN_FRAMES = 150

    /** Ten minutes. A file still silent after that is silent. */
    internal const val MAX_FRAMES = 30_000

    /** Half a second of sound in the whole call reads as none. */
    internal const val SILENT_FLOOR = 25

    /** A channel with a fiftieth of the other's sound is a dead side. */
    internal const val DEAD_RATIO = 50

    /** Channels whose difference carries this share of their energy are two
     *  different signals, not one mix copied twice. */
    internal const val SEPARATE_RATIO = 0.5

    enum class Verdict { FINE, SILENT, ONE_SIDE, UNKNOWN }

    /** What the decode found, before any judgement. */
    data class Levels(
        val frames: Int,
        val active: IntArray,
        val separate: Boolean,
    )

    /**
     * Where each measurement goes besides the log. Null in a shipped build;
     * the debug build points it at a file, because MagicOS 11 encrypts a
     * third-party app's log lines in logcat, and these numbers are the ones
     * worth reading on a real phone when tuning the thresholds above.
     */
    @Volatile
    var onMeasured: ((String) -> Unit)? = null

    fun check(context: Context, uri: Uri): Verdict {
        val levels = runCatching { measure(context, uri) }
            .onFailure { Log.w(TAG, "audiocheck: could not decode $uri", it) }
            .getOrNull() ?: return Verdict.UNKNOWN
        return classify(levels).also {
            val line = "audiocheck: frames=${levels.frames} active=${levels.active.joinToString("/")} " +
                "separate=${levels.separate} -> $it"
            Log.i(TAG, line)
            onMeasured?.let { sink -> runCatching { sink(line) } }
        }
    }

    internal fun classify(levels: Levels): Verdict {
        if (levels.frames < MIN_FRAMES) return Verdict.UNKNOWN
        val most = levels.active.maxOrNull() ?: 0
        if (most < maxOf(SILENT_FLOOR, levels.frames / 200)) return Verdict.SILENT
        if (levels.active.size == 2 && levels.separate) {
            val least = levels.active.min()
            if (most >= ENOUGH_ACTIVE && least < maxOf(SILENT_FLOOR / 2, most / DEAD_RATIO)) {
                return Verdict.ONE_SIDE
            }
        }
        return Verdict.FINE
    }

    private fun measure(context: Context, uri: Uri): Levels {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: error("no audio track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val dec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                .apply { configure(format, null, null, 0); start() }
            decoder = dec

            val meter = Meter(
                sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
            )
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            // A decoder that has taken the end of the input and then goes
            // quiet must not hold this loop for ever.
            var idleAfterInput = 0
            while (!meter.done && idleAfterInput < 500) {
                if (!inputDone) {
                    val idx = dec.dequeueInputBuffer(TIMEOUT_US)
                    if (idx >= 0) {
                        val buffer = dec.getInputBuffer(idx)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            dec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(idx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val out = dec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (inputDone && out == MediaCodec.INFO_TRY_AGAIN_LATER) idleAfterInput++
                when {
                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val actual = dec.outputFormat
                        meter.reshape(
                            actual.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                            actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                        )
                    }
                    out >= 0 -> {
                        if (info.size > 0) {
                            val pcm = dec.getOutputBuffer(out)!!
                            pcm.position(info.offset)
                            pcm.limit(info.offset + info.size)
                            meter.feed(pcm.order(ByteOrder.nativeOrder()).asShortBuffer())
                        }
                        dec.releaseOutputBuffer(out, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            return meter.levels()
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * Frame-by-frame loudness per channel, plus how different the two
     * channels are. Fed interleaved 16-bit PCM in whatever chunks the decoder
     * hands out; frames straddle chunk boundaries.
     */
    private class Meter(sampleRate: Int, channels: Int) {
        private var perFrame = sampleRate / 50
        private var channels = channels.coerceIn(1, 2)
        private var squares = DoubleArray(this.channels)
        private var filled = 0
        private var difference = 0.0
        private var energy = 0.0
        var frames = 0
            private set
        private var active = IntArray(this.channels)

        val done: Boolean
            get() = frames >= MAX_FRAMES || active.all { it >= ENOUGH_ACTIVE }

        fun reshape(sampleRate: Int, channels: Int) {
            if (frames > 0 || filled > 0) return
            perFrame = sampleRate / 50
            this.channels = channels.coerceIn(1, 2)
            squares = DoubleArray(this.channels)
            active = IntArray(this.channels)
        }

        fun feed(pcm: java.nio.ShortBuffer) {
            while (pcm.remaining() >= channels && !done) {
                var previous = 0.0
                for (c in 0 until channels) {
                    val s = pcm.get().toDouble()
                    squares[c] += s * s
                    if (c == 1) difference += (s - previous) * (s - previous)
                    previous = s
                }
                if (++filled == perFrame) endFrame()
            }
        }

        private fun endFrame() {
            for (c in 0 until channels) {
                if (sqrt(squares[c] / filled) >= ACTIVE_RMS) active[c]++
                energy += squares[c] / channels
                squares[c] = 0.0
            }
            filled = 0
            frames++
        }

        fun levels() = Levels(
            frames = frames,
            active = active.copyOf(),
            separate = channels == 2 && energy > 0 && difference >= SEPARATE_RATIO * energy,
        )
    }
}
