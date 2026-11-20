package com.jemcik.jemrec.audio

import java.io.DataInputStream
import java.io.EOFException
import java.net.Socket

/**
 * Reader for the daemon's stream. The format is scrcpy's, unchanged, because
 * the fork left Streamer unpatched:
 *
 *     4 bytes   codec id, ASCII ("opus", "raw ")
 *     repeating:
 *       8 bytes pts, big endian, with flags in the top three bits
 *       4 bytes payload length
 *       n bytes payload
 *
 * A fetch from a recorder of 1.2 or later starts with the file's length, 8
 * bytes big-endian (the recorder's Wire.fetchLength), so a transfer cut
 * short - the recorder ended in the middle of it - says so ([cutShort])
 * instead of reading as a whole, shorter recording. A length's first four
 * bytes are 0 for any file under 4 GiB. An older reply's are a codec id, or
 * GONE for a file the recorder no longer has - or 0 too: scrcpy's mark of a
 * capture that would not start (Streamer.writeDisableStream), which a
 * messenger call's failed start leaves as its whole file.
 *
 * The two are told apart by what follows: a length is eight bytes, the file
 * after it; the mark is four, and nothing after. From a recorder known to be
 * older (the question, [lengthFirst]), 0 and nothing after is that mark - an
 * empty recording; from any other, a length cut short. And other first
 * bytes are an older reply's, whatever the question found - a recorder
 * replaced between it and the fetch. A reply with nothing in it at all is an older
 * recorder's empty file, an empty recording; from one that sends lengths -
 * or one not known to - the recorder ended before its first byte, a
 * transfer cut short, to be fetched again. Read as empty, it was noted as an
 * empty call, and the next collection took the whole recording for that
 * call's copy and deleted it (review, 2026-11-18).
 */
class AudioStream(private val socket: Socket, lengthFirst: Boolean? = false) : AutoCloseable {

    /** A fetch's socket, and whether its recorder sends the length first:
     *  null when that is not known (the question failed). */
    class Fetched(val socket: Socket, val lengthFirst: Boolean?)

    /** Counts what the reader takes, so the end can be held to the length. */
    private class Counting(source: java.io.InputStream) : java.io.FilterInputStream(source) {
        var taken = 0L
        override fun read(): Int = super.read().also { if (it >= 0) taken++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) taken += it }
        override fun skip(n: Long): Long = super.skip(n).also { taken += it }
    }

    private val counting = Counting(socket.getInputStream().buffered())
    private val input: DataInputStream = DataInputStream(counting)

    /** The file's length, where the recorder said it. */
    private val expected: Long?

    /** Sent once, before any packet. */
    val codecId: String

    /** Reads into [into] until it is full or the stream ends: how many. */
    private fun readUpTo(into: ByteArray): Int {
        var got = 0
        while (got < into.size) {
            val n = input.read(into, got, into.size - got)
            if (n < 0) break
            got += n
        }
        return got
    }

    init {
        val first = ByteArray(4)
        val got = readUpTo(first)
        when {
            // An older recorder's empty file: an empty recording, as ever.
            got == 0 && lengthFirst == false -> throw EOFException("nothing to read")
            got == 0 -> throw java.io.IOException("the recorder's reply was cut off before its first byte")
            got < first.size -> throw java.io.IOException("the recorder's reply was cut short in its first word")
        }
        val rest = ByteArray(4)
        val more = if (first.any { it != 0.toByte() }) -1 else readUpTo(rest)
        if (more < 0 || (more == 0 && lengthFirst == false)) {
            // An older recorder's reply - a codec id, GONE, or 0 and nothing
            // after it: a stream it disabled.
            expected = null
            codecId = first.toString(Charsets.US_ASCII).trim { it <= ' ' || it == ' ' }
        } else {
            // A length: eight bytes, and the file after them, whatever the
            // question found - its recorder may have been replaced since.
            if (more < rest.size) throw java.io.IOException("the recorder's reply was cut short in its length")
            expected = java.nio.ByteBuffer.wrap(first + rest).long
            counting.taken = 0
            val codec = ByteArray(4)
            try {
                input.readFully(codec)
            } catch (e: EOFException) {
                // An empty file says 0, and has no codec id: an empty
                // recording, as before. Anything longer, cut short.
                if (expected == 0L) throw e
                throw java.io.IOException("the recording was cut short before its codec id")
            }
            codecId = codec.toString(Charsets.US_ASCII).trim { it <= ' ' || it == ' ' }
        }
    }

    /** Whether the recorder said a length and fewer bytes came: the
     *  transfer cut short, to be fetched again. Read once [readPacket] has
     *  given null. */
    val cutShort: Boolean get() = expected != null && counting.taken < expected

    data class Packet(
        val pts: Long,
        val isConfig: Boolean,
        val isKeyFrame: Boolean,
        val data: ByteArray,
    ) {
        // Data class equality on a ByteArray compares references, which is
        // never what anyone means. These exist only to stop that being a trap.
        override fun equals(other: Any?): Boolean =
            this === other || (other is Packet && pts == other.pts && data.contentEquals(other.data))

        override fun hashCode(): Int = 31 * pts.hashCode() + data.contentHashCode()
    }

    /** Next packet, or null once the daemon closes the stream. */
    fun readPacket(): Packet? {
        val raw = try {
            input.readLong()
        } catch (_: EOFException) {
            return null
        }
        val length = try {
            input.readInt()
        } catch (_: EOFException) {
            return null
        }
        if (length < 0 || length > MAX_PACKET) {
            error("implausible packet length $length; the stream is out of sync")
        }
        val data = ByteArray(length)
        try {
            input.readFully(data)
        } catch (_: EOFException) {
            return null
        }
        return Packet(
            pts = raw and PTS_MASK,
            isConfig = raw and FLAG_CONFIG != 0L,
            isKeyFrame = raw and FLAG_KEY_FRAME != 0L,
            data = data,
        )
    }

    override fun close() {
        runCatching { input.close() }
        runCatching { socket.close() }
    }

    private companion object {
        const val FLAG_SESSION = 1L shl 63
        const val FLAG_CONFIG = 1L shl 62
        const val FLAG_KEY_FRAME = 1L shl 61
        const val PTS_MASK = (FLAG_SESSION or FLAG_CONFIG or FLAG_KEY_FRAME).inv()

        /** A sane ceiling. Opus frames are tens of bytes; a raw PCM chunk is a
         *  few KB. Anything near this means the stream desynchronised, and
         *  failing loudly beats allocating whatever a corrupt length asked for. */
        const val MAX_PACKET = 1 shl 20
    }
}
