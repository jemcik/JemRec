package com.jemcik.jemrec.recordings

import android.content.Context
import android.net.Uri
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * A recording's loudness, for its player to draw as a waveform: [LEVELS]
 * values from 0, silence, to 1, the recording's loudest - one per slice of
 * it, the slice's loudest twentieth of a second, so a word stands out of the
 * pause around it and a long silence reads as one.
 *
 * Worked out from the file itself, decoded by Android's own decoder
 * (PcmDecoder): no library, no native code, nothing new for the reproducible
 * build to match. As a call is saved (AfterCall), in the background for the
 * list's rows while the app is on screen (ui.WaveformFill),
 * or when one is played that has none yet; then kept in the app's cache - a
 * byte a level, under a hash of the file's name, which can hold a caller's -
 * until the recording is deleted (prune) or Android clears the cache.
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

    /** Levels kept per recording: more than a phone's width has bars for,
     *  turned sideways too. */
    const val LEVELS = 800

    /**
     * Stand-in levels, for the README's screenshots: their recordings are
     * invented (Recordings.override), and an invented call has no sound to
     * draw. Filled only by the DEBUG-ONLY DemoRecordings; nothing in the main
     * source set ever writes to it, so a release build has no code that could.
     */
    @Volatile
    var override: ((name: String) -> List<Float>?)? = null

    /**
     * An invented call's loudness: phrases of words and the pauses between
     * them, two people at their own levels, a quiet stretch in the middle -
     * the same for the same [seed], every time. For calls that never
     * happened: the Theme screen's preview, the debug build's demo list and
     * the screen tests, which each had a copy of it.
     */
    fun invented(seed: Long): List<Float> {
        var state = seed
        fun next(): Double {
            state = state * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
            return (state ushr 11).toDouble() / (1L shl 53)
        }
        val out = ArrayList<Float>()
        while (out.size < LEVELS) {
            val voice = 0.5 + next() * 0.45
            repeat(4 + (next() * 12).toInt()) { out += (voice * (0.35 + 0.65 * next())).toFloat() }
            repeat(1 + (next() * 5).toInt()) { out += (0.01 + next() * 0.06).toFloat() }
        }
        for (i in 470 until 540) out[i] = (0.01 + next() * 0.04).toFloat()
        return out.take(LEVELS)
    }

    /** Each "worked out in" line, for the debug build to keep where `run-as`
     *  can read it, as AudioCheck.onMeasured: on MagicOS 11 the app's own
     *  log lines are not readable. */
    @Volatile
    var onMeasured: ((String) -> Unit)? = null

    /**
     * A recording is worked out once at a time: just after a call the player,
     * the list's fill-in and AfterCall all asked for it, and three decodes of
     * it ran at once, four decoders each. The second to ask waits, then reads
     * what the first kept. Sixteen locks shared out by name, not one a
     * recording: those were kept for every recording ever decoded, for the
     * life of the process - and two recordings sharing a lock only take
     * turns.
     */
    private val decoding = Array(16) { Any() }

    private fun lockFor(name: String): Any = decoding[name.hashCode() and (decoding.size - 1)]

    fun writingStarted(name: String) = WaveformCache.writingStarted(name)

    fun writingEnded(context: Context, name: String) = WaveformCache.writingEnded(context, name)

    fun writingRenamed(from: String, to: String) = WaveformCache.writingRenamed(from, to)

    fun isBeingWritten(name: String): Boolean = WaveformCache.isBeingWritten(name)

    /** Moves each time levels are kept (WaveformCache.kept). */
    val kept: StateFlow<Int> get() = WaveformCache.kept

    /**
     * The levels of the recording [name] at [uri]: from the cache, or worked
     * out now - a whole decode, so off the main thread. [keepGoing] is asked
     * as it decodes; a stop leaves nothing cached. Null for a file that
     * cannot be decoded, or one still being written.
     */
    fun levels(context: Context, name: String, uri: Uri, keepGoing: () -> Boolean = { true }): List<Float>? {
        override?.invoke(name)?.let { return it }
        if (isBeingWritten(name)) return null
        WaveformCache.remembered(name)?.let { return it }
        synchronized(lockFor(name)) {
            WaveformCache.read(context, name)?.let {
                WaveformCache.remember(name, it)
                return it
            }
            val levels = decode(name, context, uri, keepGoing) ?: return null
            // Begun again under this name meanwhile: what was decoded is not it.
            if (isBeingWritten(name)) return levels
            WaveformCache.keep(context, name, levels)
            return levels
        }
    }

    /** The decode behind [levels]: the app's way of working them out. */
    @VisibleForTesting
    internal var decode: (String, Context, Uri, () -> Boolean) -> List<Float>? = { name, context, uri, keepGoing ->
        WaveformDecoder.workOut(name, context, uri, keepGoing, workers = 0, join = true)
    }

    /**
     * [name]'s levels worked out with [workers] decoders - 0, as many as
     * workersFor says - and its packets joined or not: the ways of working
     * them out, measured against each other (the debug build's "waveform"
     * broadcast; 0 and joined are the app's). Nothing is read, kept or
     * waited for, and no stand-in answers: it is the file that is measured.
     * These were levels()'s parameters, which the app never passed.
     */
    internal fun measure(context: Context, name: String, uri: Uri, workers: Int, join: Boolean): List<Float>? =
        WaveformDecoder.workOut(name, context, uri, { true }, workers, join)

    /** Whether [name]'s levels are kept, without reading them: the list's
     *  fill-in asks it of every recording, each time the list is read. */
    fun has(context: Context, name: String): Boolean =
        override?.invoke(name) != null || WaveformCache.has(context, name)

    fun prune(context: Context, names: Collection<String>, graceMs: Long = WaveformCache.GRACE_MS) =
        WaveformCache.prune(context, names, graceMs)

    /**
     * The levels already known for [name] - the stand-in's, or the cache's -
     * without working anything out: what a row of the list draws. A file
     * read, so off the main thread; null when there are none yet.
     */
    fun cached(context: Context, name: String): List<Float>? {
        override?.invoke(name)?.let { return it }
        WaveformCache.remembered(name)?.let { return it }
        return WaveformCache.read(context, name)?.also { WaveformCache.remember(name, it) }
    }

    /** The levels to be had without a file: the stand-in's (the screenshots,
     *  the debug build's invented calls), or those in memory. A row draws
     *  them at once, the first frame. */
    fun peek(name: String): List<Float>? = override?.invoke(name) ?: WaveformCache.remembered(name)

    // The parts' own names, as the rest of the app and the tests have them.
    internal typealias Part = WaveformDecoder.Part
    internal typealias Stretch = WaveformDecoder.Stretch
    internal typealias FrameLevels = WaveformDecoder.FrameLevels

    internal fun workersFor(durationUs: Long, cores: Int): Int = WaveformDecoder.workersFor(durationUs, cores)

    internal fun place(parts: List<Part>, frames: Int = 0): FloatArray = WaveformDecoder.place(parts, frames)

    internal fun shift(placed: FloatArray, until: Int, part: Part): Int = WaveformDecoder.shift(placed, until, part)

    internal fun reduce(frames: FloatArray, n: Int): List<Float> = WaveformDecoder.reduce(frames, n)

    internal fun slice(i: Int, n: Int, size: Int): IntRange = WaveformDecoder.slice(i, n, size)

    internal fun cacheFile(context: Context, name: String): File = WaveformCache.cacheFile(context, name)

    internal fun toBytes(levels: List<Float>): ByteArray = WaveformCache.toBytes(levels)

    internal fun fromBytes(bytes: ByteArray): List<Float>? = WaveformCache.fromBytes(bytes)
}
