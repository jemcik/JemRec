package com.jemcik.jemrec.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer

/*
 * A codec's buffers are touched only to copy into them or out of them.
 *
 * WHY. On Android 12 and 13 a watchdog in our own process (CCodecWatchdog)
 * releases a codec whose queued work has waited 3 s, on a thread of its own,
 * and MediaCodec then frees every buffer we hold: the ByteBuffer
 * getInputBuffer and getOutputBuffer hand out is a bare pointer into that
 * memory, and nothing tells the thread writing to it (AOSP CCodec.cpp
 * initiateReleaseIfStuck, MediaCodec.cpp returnBuffersToCodec, 12 to 17).
 * Our process stopped 3 s or more mid-decode is enough to set it off: on the
 * Android 12 AVD, stopped 4 s, the watchdog fired in 6 of 10 (2026-11-25). A
 * thread inside a buffer when the free lands writes to unmapped memory, and
 * the process dies of SIGSEGV - twice on the 12 AVD, an emulator snapshot
 * standing in for the stop, in the waveform's input, which read its packets
 * from the file between taking a buffer and handing it back: 157 ms from the
 * codec's error to the crash (tombstone, 2026-11-25). Android 14 took the 3 s
 * test out (Gerrit 2587766), and 12 and 13 phones keep it: it is in
 * libsfplugin_ccodec.so, which no module update replaces (inferred from where
 * it lives).
 *
 * So everything that reads the file or works on the sound does it in our own
 * memory, and a codec's buffer is held for one bulk copy: input made in a
 * [Chunk] with no buffer in hand ([Feeder]), output copied out at once and
 * the buffer given back before anything reads it ([PcmCopy]). What is
 * left is a release that lands inside one copy of a few KB; the watchdog's
 * own line to MediaCodec's error, whose handler frees the buffers, took 9 and
 * 22 ms in the two crashes. Media3 reads samples from memory into its codec's
 * buffer the same way, and narrows the rest with a lock (androidx/media
 * c57f96c). The
 * one way to leave nothing is the block model (MediaCodec.LinearBlock), only
 * in async mode: a rewrite of every decode loop for those microseconds.
 */

/** The calls of MediaExtractor packets are read by: an interface, so what is
 *  read when is tested off the phone - this file uses nothing of Android
 *  (app/pure-tests). The two that call Android are in CodecAdapters.kt. */
internal interface Packets {
    /** When the next packet starts; below 0 at the end. */
    val sampleTime: Long

    /** Its size in bytes; below 0 at the end. */
    val sampleSize: Long

    /** The next packet copied into [buffer] at [offset]: its size, or below
     *  0 at the end. */
    fun readSampleData(buffer: ByteBuffer, offset: Int): Int

    fun advance(): Boolean
}

/** The calls of MediaCodec its input is handed over by. */
internal interface CodecInput {
    /** A free input buffer's index; below 0 for none now. */
    fun dequeue(): Int

    /** How many bytes the buffer [index] holds: read off it, nothing written
     *  and nothing kept. */
    fun capacity(index: Int): Int

    /** The buffer [index] - the codec's memory, until [queue] or [end]. */
    fun buffer(index: Int): ByteBuffer

    fun queue(index: Int, size: Int, atUs: Long)

    /** The end of the input, in buffer [index]. */
    fun end(index: Int)
}

/** What goes into one of the decoder's input buffers, made in our own
 *  memory: [size] bytes of [bytes], starting at [atUs] in the recording. */
internal class Chunk {
    var bytes = ByteArray(0)
        private set
    var size = 0
    var atUs = 0L

    /** [bytes] at least [size] long, grown when not. */
    fun room(size: Int): ByteArray {
        if (bytes.size < size) bytes = ByteArray(size)
        return bytes
    }
}

/** What goes into the decoder's input buffers. */
internal interface Input {
    /** The next chunk made in [chunk], at most [room] bytes where it joins
     *  packets; false at the end. */
    fun next(chunk: Chunk, room: Int): Boolean
}

/**
 * Hands a codec its input: a free buffer's index taken and its size read,
 * the chunk made in our own memory up to that size ([Input]), and only then
 * the buffer taken for one copy and queued. With an index alone in hand
 * nothing of the codec's is written: released meanwhile, the codec throws at
 * [CodecInput.buffer] - the exception a file that cannot be decoded gives.
 */
internal class Feeder(private val input: Input, private val codec: CodecInput) {
    private val chunk = Chunk()

    /** The end of the input has been handed over. */
    var done = false
        private set

    /** Up to [most] free buffers filled - every one there is, by default;
     *  whether any was. */
    fun feed(most: Int = Int.MAX_VALUE): Boolean {
        var queued = 0
        while (!done && queued < most) {
            val index = codec.dequeue()
            if (index < 0) break
            if (input.next(chunk, codec.capacity(index))) {
                val buffer = codec.buffer(index)
                buffer.clear()
                buffer.put(chunk.bytes, 0, chunk.size)
                codec.queue(index, chunk.size, chunk.atUs)
            } else {
                codec.end(index)
                done = true
            }
            queued++
        }
        return queued > 0
    }
}

/** The file's packets, one to a chunk, as they are. */
internal class Plain(private val packets: Packets, private val untilUs: Long) : Input {
    private var into = ByteBuffer.allocate(0)

    override fun next(chunk: Chunk, room: Int): Boolean {
        val atUs = packets.sampleTime
        if (atUs < 0 || atUs >= untilUs) return false
        val bytes = chunk.room(packets.sampleSize.toInt())
        if (into.array() !== bytes) into = ByteBuffer.wrap(bytes)
        val size = packets.readSampleData(into, 0)
        if (size < 0) return false
        packets.advance()
        chunk.size = size
        chunk.atUs = atUs
        return true
    }
}

/**
 * The file's Opus packets, joined while they may share one (OpusPackets) and
 * the joined packet fits the decoder's buffer: read back to back into one
 * array, with their sizes, and joined into the chunk from there.
 */
internal class Joining(private val packets: Packets, private val untilUs: Long) : Input {
    private val read = ByteBuffer.allocate(64 * 1024)
    private val sizes = IntArray(OpusPackets.MAX_FRAMES)
    private var into = ByteBuffer.allocate(0)

    /** A packet read ahead that did not fit the one before, moved to the
     *  start of [read]: its size, or 0, and when it starts. */
    private var held = 0
    private var heldAtUs = 0L

    /** The next packet, read in at [at]: its size, or -1 at the end - or
     *  where there is no room for it after the others, to be read first
     *  the next time. */
    private fun readAt(at: Int): Int {
        val atUs = packets.sampleTime
        if (atUs < 0 || atUs >= untilUs) return -1
        if (at > 0 && packets.sampleSize > read.capacity() - at) return -1
        val size = packets.readSampleData(read, at)
        if (size < 0) return -1
        packets.advance()
        return size
    }

    override fun next(chunk: Chunk, room: Int): Boolean {
        val atUs: Long
        if (held > 0) {
            sizes[0] = held
            atUs = heldAtUs
            held = 0
        } else {
            atUs = packets.sampleTime
            val size = readAt(0)
            if (size < 0) return false
            sizes[0] = size
        }
        val bytes = read.array()
        val firstToc = bytes[0].toInt()
        var count = 1
        var end = sizes[0]
        var units = OpusPackets.frameUnits(firstToc)
        // What joinInto may write, kept as it goes: 2, and a byte more
        // than each packet.
        var joined = 2 + sizes[0] + 1
        var heldAt = 0
        if (OpusPackets.joinable(firstToc, sizes[0])) {
            while (true) {
                val nextAtUs = packets.sampleTime
                val size = readAt(end)
                if (size < 0) break
                val toc = bytes[end].toInt()
                if (!OpusPackets.fits(firstToc, count, units, toc, size) || joined + size + 1 > room) {
                    held = size
                    heldAtUs = nextAtUs
                    heldAt = end
                    break
                }
                sizes[count++] = size
                end += size
                units += OpusPackets.frameUnits(toc)
                joined += size + 1
            }
        }
        val out = chunk.room(joined)
        if (into.array() !== out) into = ByteBuffer.wrap(out)
        into.clear()
        OpusPackets.joinInto(bytes, sizes, count, into)
        chunk.size = into.position()
        chunk.atUs = atUs
        if (held > 0) System.arraycopy(bytes, heldAt, bytes, 0, held)
        return true
    }
}

/**
 * Each chunk of a decoder's output - interleaved 16-bit PCM - copied into our
 * own memory at once, so the buffer can be given back before anything reads
 * it. The copy is reused: good until the next.
 */
internal class PcmCopy {
    private var samples = ShortArray(0)

    /** How many samples the copy holds now: for the tests. */
    internal val room: Int get() = samples.size

    /** The [size] bytes from [offset] of the buffer [buffer] gives, as
     *  samples: room made before the buffer is taken. */
    fun of(offset: Int, size: Int, buffer: () -> ByteBuffer): ShortBuffer {
        val n = size / 2
        if (samples.size < n) samples = ShortArray(n)
        val from = buffer()
        from.position(offset)
        from.limit(offset + size)
        from.order(ByteOrder.nativeOrder()).asShortBuffer().get(samples, 0, n)
        return ShortBuffer.wrap(samples, 0, n)
    }
}

/** How many of [left] samples go into an encoder buffer of [roomBytes]:
 *  whole frames of [channels], so a mix never takes one frame's right for
 *  the next one's left - or what fits where not one frame does. A buffer
 *  that takes no sample at all is an error: fed nothing, the feed would wait
 *  on it for ever. */
internal fun encoderPiece(roomBytes: Int, channels: Int, left: Int): Int {
    val room = roomBytes / 2
    check(room > 0) { "an encoder buffer of $roomBytes bytes" }
    val whole = room / channels * channels
    return minOf(left, if (whole > 0) whole else room)
}
