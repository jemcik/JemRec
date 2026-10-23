package com.jemcik.jemrec.capture

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.nio.ShortBuffer
import java.security.MessageDigest
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A recording's loudness, for its player to draw as a waveform: [LEVELS]
 * values from 0, silence, to 1, the recording's loudest - one per slice of
 * it, the slice's loudest twentieth of a second, so a word stands out of the
 * pause around it and a long silence reads as one.
 *
 * Worked out from the file itself, decoded by Android's own decoder
 * (PcmDecoder): no library, no native code, nothing new for the reproducible
 * build to match. As a call is saved (AfterCall), or the first time an older
 * recording is played, and then kept in the app's cache - a byte a level,
 * under a hash of the file's name, which can hold a caller's. Android may
 * clear the cache; it is worked out again then.
 *
 * FAST, because a long call's is waited for: packets joined six to one
 * (OpusPackets), and up to four decoders at once, each on a stretch of the
 * call. Measured on the Honor, 2026-10-23: 30 minutes took 24.5 s a packet
 * at a time with one decoder, 6.9 s joined, 2.5 s joined four at once; a
 * 58-second call 1.0 s, then 0.14 s. The stretches are lined up against
 * each other (Stretch, shift), and drawn, they match the one decoder's bars
 * within 10%, most within 5% - the few milliseconds the decoder trims at a
 * start, moving a frame's edge.
 */
internal object Waveforms {

    private const val TAG = "JemRec"

    /** Levels kept per recording: more than a phone's width has bars for,
     *  turned sideways too. */
    const val LEVELS = 800

    /** Quieter than this is drawn as silence: -55 dBFS, under the silence
     *  check's -45 (AudioCheck.ACTIVE_RMS), so its "no sound" looks it. */
    private const val FLOOR_DB = -55.0

    /** A recording whose loudest moment is quieter than this is drawn
     *  quiet, not stretched to the top: -20 dBFS. */
    private const val TOP_DB = -20.0

    /** The cache's folder; a new name for a new way of working them out,
     *  and the names before it, to clear. */
    private const val DIR = "waveforms-v2"
    private val OLD_DIRS = listOf("waveforms-v1")

    /**
     * Stand-in levels, for the README's screenshots: their recordings are
     * invented (Recordings.override), and an invented call has no sound to
     * draw. Filled only by the DEBUG-ONLY DemoRecordings; nothing in the main
     * source set ever writes to it, so a release build has no code that could.
     */
    @Volatile
    var override: ((name: String) -> List<Float>?)? = null

    /** Each "worked out in" line, for the debug build to keep where `run-as`
     *  can read it, as AudioCheck.onMeasured: on MagicOS 11 the app's own
     *  log lines are not readable. */
    @Volatile
    var onMeasured: ((String) -> Unit)? = null

    /** A frame of loudness: a twentieth of a second. */
    private const val FRAME_US = 20_000L

    /** How far before its share a stretch's decoder starts: a decoder
     *  started mid-file takes a moment to settle (Opus says 80 ms), and what
     *  it gives meanwhile falls where the stretch before wins (place); the
     *  rest of the second is what the stretch is lined up by (shift). */
    private const val SETTLE_US = 1_000_000L

    /**
     * The levels of the recording [name] at [uri]: from the cache, or worked
     * out now - a whole decode, so off the main thread. [keepGoing] is asked
     * as it decodes; a stop leaves nothing cached. Null for a file that
     * cannot be decoded. [workers] and [join] are for measuring the ways of
     * working it out against each other (0 and true: as the app does).
     */
    fun levels(
        context: Context,
        name: String,
        uri: Uri,
        keepGoing: () -> Boolean = { true },
        workers: Int = 0,
        join: Boolean = true,
        useCache: Boolean = true,
    ): List<Float>? {
        override?.invoke(name)?.let { return it }
        val cached = cacheFile(context, name)
        if (useCache) runCatching { fromBytes(cached.readBytes()) }.getOrNull()?.let { return it }
        val started = SystemClock.elapsedRealtime()
        val frames: FloatArray
        val used: Int
        try {
            val durationUs = PcmDecoder.probe(context, uri)?.durationUs ?: error("no audio track")
            used = if (workers > 0) workers else workersFor(durationUs, Runtime.getRuntime().availableProcessors())
            frames = decodeFrames(context, uri, durationUs, used, join, keepGoing)
        } catch (e: Exception) {
            Log.w(TAG, "waveform: ${FileNames.forLog(name)} could not be decoded: $e")
            return null
        }
        if (!keepGoing()) return null
        val levels = reduce(frames, LEVELS)
        val line = "waveform: ${FileNames.forLog(name)}, ${frames.size / 50} s of audio, " +
            "worked out in ${SystemClock.elapsedRealtime() - started} ms ($used at once${if (join) ", joined" else ""})"
        Log.i(TAG, line)
        onMeasured?.let { sink -> runCatching { sink(line) } }
        if (useCache) {
            runCatching {
                cached.parentFile?.mkdirs()
                cached.writeBytes(toBytes(levels))
            }.onFailure { Log.w(TAG, "waveform: could not keep it: $it") }
        }
        return levels
    }

    /**
     * The levels already known for [name] - the stand-in's, or the cache's -
     * without working anything out: what a row of the list draws. A file
     * read, so off the main thread; null when there are none yet.
     */
    fun cached(context: Context, name: String): List<Float>? {
        override?.invoke(name)?.let { return it }
        return runCatching { fromBytes(cacheFile(context, name).readBytes()) }.getOrNull()
    }

    /** The stand-in's levels, which need no file: a row draws them at once,
     *  the first frame (the screenshots, the debug build's invented calls). */
    fun peek(name: String): List<Float>? = override?.invoke(name)

    /**
     * How many decoders at once for a recording [durationUs] long, on a
     * phone with [cores]: each starts slower than it runs, so one for a short
     * call; up to four for a long one, each on a stretch of it, never more
     * than half the cores.
     */
    internal fun workersFor(durationUs: Long, cores: Int): Int =
        if (durationUs < 30_000_000L) 1 else minOf(4, max(1, cores / 2), (durationUs / 15_000_000L).toInt())

    /** The loudness of every frame of the recording, its stretches decoded
     *  [workers] at once, each on a thread of its own. */
    private fun decodeFrames(
        context: Context,
        uri: Uri,
        durationUs: Long,
        workers: Int,
        join: Boolean,
        keepGoing: () -> Boolean,
    ): FloatArray {
        val frames = ((durationUs + FRAME_US - 1) / FRAME_US).toInt()
        if (workers <= 1 || durationUs <= 0) return place(listOf(decodePart(context, uri, 0, Long.MAX_VALUE, join, keepGoing)), frames)
        val stretch = durationUs / workers
        val parts = arrayOfNulls<Part>(workers)
        val failures = arrayOfNulls<Throwable>(workers)
        val threads = List(workers) { k ->
            Thread({
                try {
                    val untilUs = if (k == workers - 1) Long.MAX_VALUE else (k + 1) * stretch
                    val fromUs = if (k == 0) 0L else max(0L, k * stretch - SETTLE_US)
                    parts[k] = decodePart(context, uri, fromUs, untilUs, join, keepGoing)
                } catch (t: Throwable) {
                    failures[k] = t
                }
            }, "jemrec-waveform-$k").apply { start() }
        }
        threads.forEach { it.join() }
        failures.firstOrNull { it != null }?.let { throw it }
        return place(parts.map { it!! }, frames)
    }

    /** A stretch of a recording decoded: each frame's loudness, the first
     *  being the recording's frame [firstFrame]. */
    internal class Part(val firstFrame: Int, val frames: FloatArray)

    private fun decodePart(
        context: Context,
        uri: Uri,
        fromUs: Long,
        untilUs: Long,
        join: Boolean,
        keepGoing: () -> Boolean,
    ): Part {
        val stretch = Stretch()
        PcmDecoder.decode(
            context, uri,
            onFormat = stretch::format,
            onPcm = { pcm, atUs ->
                stretch.chunk(pcm, atUs)
                keepGoing()
            },
            fromUs = fromUs,
            untilUs = untilUs,
            join = join,
        )
        return stretch.finish()
    }

    /**
     * A stretch's sound, made frames on the recording's own grid of
     * twentieths of a second - so stretches decoded apart fit together, and
     * a recording decoded in four is drawn as in one. Where a stretch starts
     * is known to the sample from its second chunk: the decoder trims its
     * first by an amount it does not say (the file's pre-skip, or twice it:
     * measured on the Honor, 2026-10-23), but every chunk after starts where
     * its packet does. The samples before the next twentieth are left out;
     * they fall where the stretch before is drawn instead (place).
     */
    internal class Stretch(private var sampleRate: Int = 48_000, private var channels: Int = 2) {
        private val frames = FrameLevels(sampleRate, channels)
        /** The first chunk, until the second says where it ended. */
        private var held: ShortArray? = null
        private var heldAtUs = 0L
        private var firstFrame = -1
        /** Samples (of every channel) still to leave out, to the grid. */
        private var drop = 0

        /** What the decoder says it gives - before any sound came. */
        fun format(sampleRate: Int, channels: Int) {
            if (held != null || firstFrame >= 0) return
            this.sampleRate = sampleRate
            this.channels = channels.coerceIn(1, 8)
            frames.reshape(sampleRate, channels)
        }

        fun chunk(pcm: ShortBuffer, atUs: Long) {
            val first = held
            when {
                firstFrame >= 0 -> feed(pcm)
                first == null -> {
                    held = ShortArray(pcm.remaining()).also { pcm.get(it) }
                    heldAtUs = atUs
                }
                else -> {
                    val perFrame = sampleRate / 50
                    // In samples of one channel from the recording's start:
                    // this chunk's, less the first chunk's length.
                    val start = max(0L, atUs * sampleRate / 1_000_000 - first.size / channels)
                    firstFrame = ((start + perFrame - 1) / perFrame).toInt()
                    drop = ((firstFrame.toLong() * perFrame - start) * channels).toInt()
                    held = null
                    feed(ShortBuffer.wrap(first))
                    feed(pcm)
                }
            }
        }

        private fun feed(pcm: ShortBuffer) {
            if (drop > 0) {
                val n = min(drop, pcm.remaining())
                pcm.position(pcm.position() + n)
                drop -= n
            }
            if (pcm.hasRemaining()) frames.feed(pcm)
        }

        /** The stretch's frames, and where they fall. A stretch of one chunk
         *  has no second to go by: its own time it is. */
        fun finish(): Part {
            held?.let { first ->
                firstFrame = (heldAtUs / FRAME_US).toInt()
                held = null
                frames.feed(ShortBuffer.wrap(first))
            }
            return Part(max(0, firstFrame), frames.peaks())
        }
    }

    /**
     * The stretches' frames put where each falls in a recording [frames]
     * long - its length as the file gives it, so the levels' slices fall in
     * the same places however many stretches it was decoded in; 0 when the
     * file does not say, and it is as long as what was decoded. A stretch
     * starts before its share, so it overlaps the end of the one before:
     * there the earlier wins - it was decoded straight through, and a
     * decoder takes a moment to settle. Where a later stretch falls is
     * checked against the earlier one over that overlap ([shift]).
     */
    internal fun place(parts: List<Part>, frames: Int = 0): FloatArray {
        val decoded = parts.maxOf { it.firstFrame + it.frames.size }
        val out = FloatArray(max(if (frames > 0) frames else decoded, 0))
        var until = 0
        for ((k, part) in parts.withIndex()) {
            val at = part.firstFrame + if (k == 0) 0 else shift(out, until, part)
            for (j in part.frames.indices) {
                val i = at + j
                if (i < until) continue
                if (i >= out.size) break
                out[i] = part.frames[j]
            }
            until = max(until, min(out.size, at + part.frames.size))
        }
        return out
    }

    /**
     * How many frames off a stretch's own time puts it, found by sliding it
     * over the frames before it, [placed] up to [until], where the two
     * overlap: once its decoder has settled, the same sound gives the same
     * frames. The time Android gives after a jump into a file is not to be
     * trusted to the frame: in the app's recordings, written by Android's
     * own Ogg muxer, it was a 20 ms packet early (the Honor, 2026-10-23:
     * the sound matched to the sample 960 samples later). 0 when the
     * overlap is too short, or too quiet, to tell.
     */
    internal fun shift(placed: FloatArray, until: Int, part: Part): Int {
        val from = part.firstFrame + SETTLE_FRAMES + MAX_SHIFT
        val to = until - MAX_SHIFT
        if (to - from < MIN_OVERLAP) return 0
        var quiet = true
        for (i in from until to) if (placed[i] > QUIET_RMS) quiet = false
        if (quiet) return 0
        fun error(s: Int): Double {
            var e = 0.0
            for (i in from until to) {
                val j = i - part.firstFrame - s
                e += if (j in part.frames.indices) kotlin.math.abs(placed[i] - part.frames[j]).toDouble() else placed[i].toDouble()
            }
            return e
        }
        val best = (-MAX_SHIFT..MAX_SHIFT).minByOrNull(::error)!!
        // Only a clear match: within a tenth of where it says, it says right.
        return if (error(best) < error(0) * 0.9) best else 0
    }

    /** A decoder started mid-file is not compared until this many frames in. */
    private const val SETTLE_FRAMES = 10
    private const val MAX_SHIFT = 3
    /** Frames the comparison needs at least. */
    private const val MIN_OVERLAP = 6
    /** Below this, frames are too quiet to line up by: about -55 dBFS. */
    private const val QUIET_RMS = 60f

    private fun cacheFile(context: Context, name: String): File {
        for (old in OLD_DIRS) {
            val dir = File(context.cacheDir, old)
            if (dir.exists()) runCatching { dir.deleteRecursively() }
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(name.toByteArray())
            .take(12).joinToString("") { "%02x".format(it) }
        return File(File(context.cacheDir, DIR), "$hash.lvl")
    }

    /**
     * Each twentieth of a second's loudness - its RMS, in 16-bit units, over
     * every channel: either side of the call talking is a sound. Fed
     * interleaved PCM in whatever chunks the decoder hands out.
     */
    internal class FrameLevels(sampleRate: Int = 48_000, channels: Int = 2) {
        private var perFrame = samplesPerFrame(sampleRate, channels)
        private var squares = 0.0
        private var filled = 0
        private var rms = FloatArray(1024)
        private var count = 0

        /** What the decoder says it gives - before any sound came. */
        fun reshape(sampleRate: Int, channels: Int) {
            if (count == 0 && filled == 0) perFrame = samplesPerFrame(sampleRate, channels)
        }

        /** Each chunk copied out whole: a sample at a time from the decoder's
         *  buffer was a call per sample, 96,000 of them a second of a call. */
        private var chunk = ShortArray(0)

        fun feed(pcm: ShortBuffer) {
            val n = pcm.remaining()
            if (chunk.size < n) chunk = ShortArray(n)
            pcm.get(chunk, 0, n)
            for (i in 0 until n) {
                val s = chunk[i].toDouble()
                squares += s * s
                if (++filled == perFrame) endFrame()
            }
        }

        /** Every frame so far, a last part-frame included. */
        fun peaks(): FloatArray {
            if (filled > 0) endFrame()
            return rms.copyOf(count)
        }

        private fun endFrame() {
            if (count == rms.size) rms = rms.copyOf(count * 2)
            rms[count++] = sqrt(squares / filled).toFloat()
            squares = 0.0
            filled = 0
        }

        private fun samplesPerFrame(sampleRate: Int, channels: Int) =
            max(1, sampleRate / 50 * channels.coerceIn(1, 8))
    }

    /** [frames] of loudness made [n] levels: each the loudest frame in its
     *  slice, in decibels, from [FLOOR_DB] up to the recording's loudest - or
     *  [TOP_DB], if it never got that loud. */
    internal fun reduce(frames: FloatArray, n: Int): List<Float> {
        if (frames.isEmpty()) return List(n) { 0f }
        val db = DoubleArray(n) { i ->
            val from = (i.toLong() * frames.size / n).toInt()
            val to = min(frames.size, max(from + 1, ((i + 1).toLong() * frames.size / n).toInt()))
            var peak = 0f
            for (f in from until to) peak = max(peak, frames[f])
            if (peak <= 0f) -120.0 else 20 * log10(peak / 32768.0)
        }
        val top = max(db.max(), TOP_DB)
        return db.map { ((it - FLOOR_DB) / (top - FLOOR_DB)).coerceIn(0.0, 1.0).toFloat() }
    }

    /** A byte a level: what the cache keeps. */
    internal fun toBytes(levels: List<Float>): ByteArray =
        ByteArray(levels.size) { (levels[it] * 255).roundToInt().coerceIn(0, 255).toByte() }

    /** The cache's bytes as levels; null for a file that is not one. */
    internal fun fromBytes(bytes: ByteArray): List<Float>? =
        if (bytes.size != LEVELS) null else bytes.map { (it.toInt() and 0xff) / 255f }
}
