package com.jemcik.jemrec.audio

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Ogg's pages (RFC 3533): reading one, its CRC, and joining a recording's
 * audio pages about a second to a page.
 *
 * WHY JOIN THEM
 *
 * Android's Ogg muxer (AOSP's OggWriter, behind MediaMuxer) ends a page after
 * every packet - one Opus packet, 20 ms, to a page - and a page's header is 27
 * bytes, with a lacing byte more for each 255 bytes of packet. At 50 pages a
 * second the headers alone come to about 11 kbps, whatever the audio's
 * bitrate.
 * Measured on the Honor (MagicOS 11, Android 17), 2026-11-16: the pages read
 * of a 37.7 s recording each 960 samples on from the last and about 350
 * bytes, 29 of them header; and the 101 recordings there averaged 139.9 kbps
 * from an encoder set to 128. At 24 kbps those headers would be a third of the
 * file. A page may hold up to 255 packets and has no limit on how long it
 * plays (RFC 7845); at about a second to a page what is left is the lacing,
 * one byte a packet at these sizes, and one header a second.
 *
 * Joining keeps every packet as it was: the pages' lacing values follow one
 * another, and so does their data, so a packet that ran on from one page to
 * the next runs on inside the joined page just the same.
 *
 * POSITIONS AS OGG OPUS MEANS THEM. A page's position (granule) is where the
 * last packet ending on it ends, in 48 kHz samples from the stream's start,
 * and the stream's last page says it is the last (RFC 7845, 3 and 4). The
 * muxer writes neither: its positions are where that packet STARTS, and no
 * page is marked last - measured on an Android 17 emulator's test call,
 * 2026-11-17, the first audio page at 0 and the last at 412 x 960 for 413
 * packets, and again on the Android 12 AVD the same day, no page marked.
 * RFC 7845 4.5 calls such a stream invalid - its first audio page's position
 * is smaller than what plays on it - and libopusfile would refuse to open
 * it (op_find_initial_pcm_offset: OP_EBADTIMESTAMP; read in its source, not
 * run), so players built on it would have too; the Mac's CoreAudio and
 * AVFoundation, ffmpeg, Chromium and Android
 * played it, which is all that had been tried. So each position is worked
 * out here, from the packets themselves (OpusPackets.samples), for every
 * audio page, the first one too, and the last page is marked.
 *
 * Android reads them as meant, and heard the muxer's wrong: its extractor
 * puts the stream's start at the first audio page's position less what plays
 * on it (0 either way) and stamps each page with its position less its
 * samples (AOSP OggExtractor.cpp, MyOpusExtractor::readNextPacket) - with the
 * muxer's positions the first two packets both at 0, and its decode CUT 312
 * samples a channel (6.5 ms, the size of the pre-skip) at the start of the
 * second packet, 13.5 ms in: measured on the Android 17 AVD, 2026-11-17,
 * through MediaExtractor and MediaCodec, a 34 s test call - the sound jumped
 * from 15456 to -334 there. With the positions written here the same file
 * decoded without the cut, 1701 x 960 - 312 samples a channel, as RFC 7845
 * counts them, sample for sample what it was elsewhere, and its start as
 * libopus (ffmpeg) decoded it; MediaMetadataRetriever and MediaPlayer gave it
 * 20 ms more, its last packet. The same on the Android 12 AVD, six calls in
 * all. (A note here once read the 312 samples the other way: that joining
 * the first page made Android play the pre-skip as sound. It was this cut,
 * gone.)
 *
 * THE FIRST AUDIO PAGE IS NOT JOINED, as before; with its position right it
 * could be, for 28 bytes - not worth a page Android reads the stream's start
 * from. Measured on the Honor, 2026-11-16, joining the rest: 9,490,960 bytes
 * became 8,773,057; MediaPlayer and ExoPlayer landed on the same positions at
 * five seeks with SEEK_CLOSEST. On a Mac, CoreAudio and ffmpeg decoded the
 * same samples joined and one packet to a page, and AVFoundation and Chromium
 * as many.
 *
 * A save that has room for no work file (RecordingSaver) gets no join, but
 * the same positions and last mark, written into the muxer's pages where
 * they lie ([mend]): a page's header is the same length whatever its
 * position says.
 */
internal object OggPages {

    const val HEADER = 27

    /** The longest a page can be: 255 segments of 255 bytes. */
    const val LONGEST_PAGE = HEADER + 255 + 255 * 255

    /** How much a joined page plays at most: a second, in the 48 kHz
     *  samples an Ogg Opus position is always counted in. */
    const val JOIN_SAMPLES = 48_000L

    private val OGGS = "OggS".toByteArray(Charsets.US_ASCII)
    private val OPUS_HEAD = "OpusHead".toByteArray(Charsets.US_ASCII)
    private const val CONTINUED = 1
    private const val FIRST = 2
    private const val LAST = 4

    /** A page's position when no packet ends on it. */
    private const val NO_POSITION = -1L

    /** One page, header and all. */
    class Page(val bytes: ByteArray) {
        val headerType: Int get() = bytes[5].toInt() and 0xff
        val position: Long get() = ByteBuffer.wrap(bytes, 6, 8).order(ByteOrder.LITTLE_ENDIAN).long
        val serial: Int get() = ByteBuffer.wrap(bytes, 14, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val segments: Int get() = bytes[26].toInt() and 0xff
        val bodyStart: Int get() = HEADER + segments
        fun lace(i: Int): Int = bytes[HEADER + i].toInt() and 0xff

        /** Whether the page is one packet, ending on it: every lacing value
         *  255 but the last. */
        val onePacket: Boolean get() = segments > 0 && (0 until segments - 1).all { lace(it) == 255 } && lace(segments - 1) < 255
        fun body(): ByteArray = bytes.copyOfRange(bodyStart, bytes.size)
        fun startsWith(magic: ByteArray): Boolean =
            bytes.size - bodyStart >= magic.size && magic.indices.all { bytes[bodyStart + it] == magic[it] }
    }

    /** Ogg's CRC-32: polynomial 0x04c11db7, not reflected, no final xor -
     *  not java.util.zip's. */
    private val CRC_TABLE = IntArray(256) { i ->
        var r = i shl 24
        repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04c11db7 else r shl 1 }
        r
    }

    fun crc(bytes: ByteArray, from: Int = 0, until: Int = bytes.size): Int {
        var c = 0
        for (i in from until until) {
            c = (c shl 8) xor CRC_TABLE[((c ushr 24) xor (bytes[i].toInt() and 0xff)) and 0xff]
        }
        return c
    }

    /** [page] with its CRC worked out and written in, in place. */
    fun sealed(page: ByteArray): ByteArray {
        for (i in 22 until 26) page[i] = 0
        ByteBuffer.wrap(page, 22, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(crc(page))
        return page
    }

    /** The next page of [input]; null at its end, or where what follows is
     *  not a page. */
    fun readPage(input: InputStream): Page? = next(input).page

    /** What reading the next page of [input] came to: the page - or, at its
     *  end or where what follows is not a page, none, and every byte read
     *  looking for one. */
    class Next(val page: Page?, val read: ByteArray)

    /** The next page, read into one array of its own size: the copy of an
     *  hour's call made in the save pass copies each byte once. */
    fun next(input: InputStream): Next {
        val header = ByteArray(HEADER)
        val gotHeader = readUpTo(input, header, 0)
        if (gotHeader < HEADER || !OGGS.indices.all { header[it] == OGGS[it] }) return Next(null, header.copyOf(gotHeader))
        val segments = header[26].toInt() and 0xff
        val lacing = header.copyOf(HEADER + segments)
        val gotLacing = readUpTo(input, lacing, HEADER)
        if (gotLacing < lacing.size) return Next(null, lacing.copyOf(gotLacing))
        val page = lacing.copyOf(HEADER + segments + (0 until segments).sumOf { lacing[HEADER + it].toInt() and 0xff })
        val gotPage = readUpTo(input, page, lacing.size)
        if (gotPage < page.size) return Next(null, page.copyOf(gotPage))
        return Next(Page(page), ByteArray(0))
    }

    /** [into] filled from [input], from [from] on: how far it got. */
    private fun readUpTo(input: InputStream, into: ByteArray, from: Int): Int {
        var at = from
        while (at < into.size) {
            val n = input.read(into, at, into.size - at)
            if (n < 0) break
            at += n
        }
        return at
    }

    /**
     * Where each audio page's last packet ends (RFC 7845's position), worked
     * out from the packets as their pages go by: the 48 kHz samples of every
     * packet ended so far, after the stream's [headers] header packets.
     */
    class Positions(private var headers: Int = 0) {
        private var total = 0L

        /** The first two bytes of the packet under way, which say how long
         *  it plays; a packet may run on over pages. */
        private val start = IntArray(2)
        private var started = 0

        /** [page]'s position: the samples of every audio packet ended on it
         *  and before it - or [NO_POSITION] when none ends on it. A header
         *  page's is 0, as RFC 7845 has it. */
        fun of(page: Page): Long {
            var at = page.bodyStart
            var ended = false
            for (i in 0 until page.segments) {
                val lace = page.lace(i)
                var k = 0
                while (started < 2 && k < lace) start[started++] = page.bytes[at + k++].toInt() and 0xff
                at += lace
                if (lace == 255) continue
                if (headers > 0) {
                    headers--
                } else if (started > 0) {
                    total += OpusPackets.samples(start[0], if (started > 1) start[1] else 0)
                }
                started = 0
                ended = true
            }
            return if (ended) total else NO_POSITION
        }
    }

    /**
     * Copy the pages of [input] to [output], the first as it is but for its
     * position and the rest joined about [joinSamples] to a page, numbered on
     * from the first one's sequence number, each with its position worked out
     * ([Positions]) and the last marked the stream's end. [input] starts
     * after the header pages, which the caller has copied. From where what
     * follows is not a page of this one stream - not a page at all, a page
     * cut short, another stream's - the rest is copied as it is.
     */
    fun join(input: InputStream, output: OutputStream, joinSamples: Long = JOIN_SAMPLES) {
        val positions = Positions()
        val group = mutableListOf<Pair<Page, Long>>()
        var serial: Int? = null
        var sequence = -1
        // Each page out is held until the next: only then is it known whether
        // it is the stream's last.
        var held: ByteArray? = null
        fun emit(page: ByteArray) {
            held?.let(output::write)
            held = page
        }
        fun flush() {
            if (group.isEmpty()) return
            if (sequence < 0) sequence = ByteBuffer.wrap(group[0].first.bytes, 18, 4).order(ByteOrder.LITTLE_ENDIAN).int
            emit(joined(group, sequence++))
            group.clear()
        }
        while (true) {
            val next = next(input)
            val page = next.page
            if (page == null || page.headerType and FIRST != 0 || page.serial != (serial ?: page.serial)) {
                flush()
                held?.let { output.write(endingStream(it)) }
                output.write(page?.bytes ?: next.read)
                input.copyTo(output)
                return
            }
            val first = serial == null
            serial = page.serial
            val position = positions.of(page)
            if (group.isNotEmpty() && !fits(group, page, position, joinSamples)) flush()
            group += page to position
            // The first page stays alone (above), and a page marked the
            // stream's last ends the join.
            if (first || page.headerType and LAST != 0) flush()
        }
    }

    /** [page] marked the stream's last, and sealed again. */
    private fun endingStream(page: ByteArray): ByteArray {
        if (page[5].toInt() and LAST != 0) return page
        page[5] = (page[5].toInt() or LAST).toByte()
        return sealed(page)
    }

    /** Whether [page], ending at [position], can join the pages in [group]. */
    private fun fits(group: List<Pair<Page, Long>>, page: Page, position: Long, joinSamples: Long): Boolean {
        if (group.sumOf { it.first.segments } + page.segments > 255) return false
        val start = group.firstOrNull { it.second != NO_POSITION }?.second ?: return true
        return position == NO_POSITION || position - start < joinSamples
    }

    /**
     * The positions and the last mark [join] writes, written into a whole
     * file's pages where they lie - headers and all, the header pages left
     * at 0 - read and written at offsets in the file ([read]: into the array
     * from an index, at most a length; how many it read, under 1 at the end):
     * the muxer's file, saved where no work file fit. Each page is rewritten
     * at its own length, so nothing moves, and one stopped part way leaves
     * no position going back: each mended page ends where the next's last
     * packet starts, at the most. Stops at the first thing that is not a
     * whole page of the stream; false, writing nothing, for a file that is
     * not Ogg Opus.
     */
    fun mend(
        read: (offset: Long, into: ByteArray, at: Int, length: Int) -> Int,
        write: (offset: Long, page: ByteArray) -> Unit,
    ): Boolean {
        // An Opus stream's alone: the positions are worked out from Opus
        // packets, and another codec's would be written wrong.
        if (readAt(read, 0)?.startsWith(OPUS_HEAD) != true) return false
        val positions = Positions(headers = 2)
        var offset = 0L
        var serial: Int? = null
        var last: Pair<Long, ByteArray>? = null
        while (true) {
            val page = readAt(read, offset) ?: break
            if (serial != null && (page.serial != serial || page.headerType and FIRST != 0)) break
            serial = page.serial
            val position = positions.of(page)
            val bytes = page.bytes
            if (page.position != position) {
                ByteBuffer.wrap(bytes, 6, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(position)
                write(offset, sealed(bytes))
            }
            last = offset to bytes
            offset += bytes.size
        }
        val (at, bytes) = last ?: return false
        if (bytes[5].toInt() and LAST == 0) write(at, endingStream(bytes))
        return true
    }

    /**
     * Whether the last page of a file [size] bytes long, read at offsets
     * through [read], is marked the stream's end: one [mend] has been
     * through, or one written so to begin with. A look at its last page's
     * worth of bytes, not the file: the last page is the one starting at an
     * "OggS" whose page ends where the file does.
     */
    fun endsTheStream(read: (offset: Long, into: ByteArray, at: Int, length: Int) -> Int, size: Long): Boolean {
        val tail = minOf(size, LONGEST_PAGE.toLong()).toInt()
        val bytes = ByteArray(tail)
        if (readFully(read, size - tail, bytes, 0) < tail) return false
        for (i in tail - HEADER downTo 0) {
            if (!OGGS.indices.all { bytes[i + it] == OGGS[it] }) continue
            val segments = bytes[i + 26].toInt() and 0xff
            if (i + HEADER + segments > tail) continue
            val end = i + HEADER + segments + (0 until segments).sumOf { bytes[i + HEADER + it].toInt() and 0xff }
            if (end == tail) return bytes[i + 5].toInt() and LAST != 0
        }
        return false
    }

    /** The page at [offset]; null if there is no whole page there. */
    private fun readAt(read: (Long, ByteArray, Int, Int) -> Int, offset: Long): Page? {
        val header = ByteArray(HEADER)
        if (readFully(read, offset, header, 0) < HEADER || !OGGS.indices.all { header[it] == OGGS[it] }) return null
        val lacing = header.copyOf(HEADER + (header[26].toInt() and 0xff))
        if (readFully(read, offset, lacing, HEADER) < lacing.size) return null
        val page = lacing.copyOf(lacing.size + (HEADER until lacing.size).sumOf { lacing[it].toInt() and 0xff })
        if (readFully(read, offset, page, lacing.size) < page.size) return null
        return Page(page)
    }

    /** [into] filled from [from] on with the bytes at [offset] + [from] on:
     *  how far it got. */
    private fun readFully(read: (Long, ByteArray, Int, Int) -> Int, offset: Long, into: ByteArray, from: Int): Int {
        var at = from
        while (at < into.size) {
            val n = read(offset + at, into, at, into.size - at)
            if (n <= 0) break
            at += n
        }
        return at
    }

    /** [group] as one page, numbered [sequence], at the position its last
     *  page with one ends. */
    private fun joined(group: List<Pair<Page, Long>>, sequence: Int): ByteArray {
        val first = group.first().first
        val segments = group.sumOf { it.first.segments }
        val out = ByteArrayOutputStream(group.sumOf { it.first.bytes.size })
        out.write(first.bytes, 0, HEADER)
        group.forEach { out.write(it.first.bytes, HEADER, it.first.segments) }
        group.forEach { (page, _) -> out.write(page.bytes, page.bodyStart, page.bytes.size - page.bodyStart) }
        val page = out.toByteArray()
        page[5] = ((first.headerType and CONTINUED) or (group.last().first.headerType and LAST)).toByte()
        val position = group.lastOrNull { it.second != NO_POSITION }?.second ?: NO_POSITION
        ByteBuffer.wrap(page, 6, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(position)
        ByteBuffer.wrap(page, 18, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(sequence)
        page[26] = segments.toByte()
        return sealed(page)
    }
}
