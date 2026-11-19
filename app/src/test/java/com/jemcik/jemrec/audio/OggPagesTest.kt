package com.jemcik.jemrec.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A recording's audio pages joined about a second to a page: the same
 *  packets, in the same order, under a fiftieth of the headers - each page at
 *  the position Ogg Opus means (where its last packet ends, RFC 7845) and the
 *  last marked the stream's end, which the muxer's pages are not. */
class OggPagesTest {

    private val serial = 0x73483366

    /** A page, made here without OggPages: the test's own reading of RFC 3533. */
    private fun page(seq: Int, position: Long, body: ByteArray, type: Int = 0, lacing: ByteArray = lacingFor(body.size)): ByteArray {
        val header = ByteBuffer.allocate(27).order(ByteOrder.LITTLE_ENDIAN)
            .put("OggS".toByteArray()).put(0).put(type.toByte()).putLong(position).putInt(serial).putInt(seq).putInt(0)
            .put(lacing.size.toByte()).array()
        val page = header + lacing + body
        ByteBuffer.wrap(page, 22, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(crcOf(page))
        return page
    }

    private fun lacingFor(size: Int) =
        ByteArray(size / 255 + 1) { i -> if (i < size / 255) 255.toByte() else (size % 255).toByte() }

    private fun crcOf(page: ByteArray) = OggPages.crc(page.copyOf().also { for (i in 22 until 26) it[i] = 0 })

    /** A 20 ms packet, as the encoder makes them: its first byte a TOC for
     *  one 20 ms CELT frame (configuration 31, stereo, code 0), the rest made
     *  up. */
    private fun packet(i: Int, size: Int, toc: Int = TOC_20_MS) =
        ByteArray(size) { if (it == 0) toc.toByte() else (it * 7 + i).toByte() }

    /** [count] pages of one 20 ms packet each, as Android's muxer writes
     *  them: numbered from 2 (after the two header pages), each at where its
     *  packet STARTS, and none marked the stream's end (measured, 2026-11-17). */
    private fun muxed(count: Int, size: (Int) -> Int = { 60 + it % 37 }): Pair<ByteArray, List<ByteArray>> {
        val packets = (0 until count).map { packet(it, size(it)) }
        val file = packets.mapIndexed { i, p -> page(seq = 2 + i, position = 960L * i, body = p) }
            .reduce { a, b -> a + b }
        return file to packets
    }

    /** What RFC 7845 asks of a stream's audio pages, read here without
     *  OggPages: each page's position is every sample ended on it and before
     *  (4), a page on which nothing ends says -1, no position goes back, and
     *  the last page - and only it - ends the stream (3). And what libopusfile
     *  refuses a file over (op_find_initial_pcm_offset): a first audio page
     *  whose position is less than what plays on it (4.5). */
    private fun assertValidOggOpus(audio: List<Read>, samples: (ByteArray) -> Int = { 960 }) {
        var total = 0L
        val partial = ByteArrayOutputStream()
        var firstEnds = -1L
        var firstPlays = 0L
        audio.forEachIndexed { n, p ->
            var at = 27 + p.segments
            var ended = false
            for (s in 0 until p.segments) {
                val lace = p.bytes[27 + s].toInt() and 0xff
                partial.write(p.bytes, at, lace)
                at += lace
                if (lace < 255) {
                    val played = samples(partial.toByteArray())
                    total += played
                    if (firstEnds < 0) firstPlays += played
                    partial.reset()
                    ended = true
                }
            }
            assertEquals("page $n's position", if (ended) total else -1L, p.position)
            if (ended && firstEnds < 0) firstEnds = p.position
            assertEquals("page $n's end mark", if (n == audio.lastIndex) 4 else 0, p.type and 4)
        }
        assertTrue("the first audio page ends at $firstEnds, plays $firstPlays", firstEnds >= firstPlays)
    }

    private fun joined(file: ByteArray, joinSamples: Long = OggPages.JOIN_SAMPLES): ByteArray {
        val out = ByteArrayOutputStream()
        OggPages.join(file.inputStream(), out, joinSamples)
        return out.toByteArray()
    }

    private class Read(val bytes: ByteArray) {
        val type get() = bytes[5].toInt() and 0xff
        val position get() = ByteBuffer.wrap(bytes, 6, 8).order(ByteOrder.LITTLE_ENDIAN).long
        val sequence get() = ByteBuffer.wrap(bytes, 18, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val crc get() = ByteBuffer.wrap(bytes, 22, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val segments get() = bytes[26].toInt() and 0xff
    }

    private fun pages(file: ByteArray): List<Read> {
        val out = mutableListOf<Read>()
        var at = 0
        while (at < file.size) {
            assertEquals("OggS", String(file, at, 4, Charsets.US_ASCII))
            val segments = file[at + 26].toInt() and 0xff
            val body = (0 until segments).sumOf { file[at + 27 + it].toInt() and 0xff }
            val end = at + 27 + segments + body
            out += Read(file.copyOfRange(at, end))
            at = end
        }
        return out
    }

    /** Every packet, rebuilt from the lacing across pages, with the position
     *  of the page each one ended on. */
    private fun packets(pages: List<Read>): List<Pair<ByteArray, Long>> {
        val out = mutableListOf<Pair<ByteArray, Long>>()
        val partial = ByteArrayOutputStream()
        for (p in pages) {
            var at = 27 + p.segments
            for (s in 0 until p.segments) {
                val lace = p.bytes[27 + s].toInt() and 0xff
                partial.write(p.bytes, at, lace)
                at += lace
                if (lace < 255) {
                    out += partial.toByteArray() to p.position
                    partial.reset()
                }
            }
        }
        return out
    }

    @Test fun aboutASecondGoesToAPage() {
        val (file, sent) = muxed(250)
        val out = pages(joined(file))
        // The first alone, then 249 at 50 to a page.
        assertEquals(1 + 5, out.size)
        assertEquals(1, out[0].segments)
        assertEquals(50, out[1].segments)
        val got = packets(out)
        assertEquals(sent.size, got.size)
        sent.indices.forEach { assertArrayEquals("packet $it", sent[it], got[it].first) }
        out.forEach { assertTrue(it.segments <= 255) }
    }

    @Test fun eachPageIsAtWhereItsLastPacketEnds() {
        val (file, _) = muxed(120)
        val after = pages(joined(file))
        // 960 samples a packet, counted from the stream's start.
        var taken = 0
        after.forEach { page ->
            taken += page.segments
            assertEquals(960L * taken, page.position)
        }
        // And no page holds more than a second.
        var previous = 0L
        after.forEach {
            assertTrue("${it.position - previous}", it.position - previous <= OggPages.JOIN_SAMPLES)
            previous = it.position
        }
    }

    @Test fun theMuxersPagesBecomeAValidStream() {
        // As the muxer wrote them, its first audio page is at 0 with a packet
        // of 960 on it: libopusfile refused such files.
        val (file, _) = muxed(250)
        assertEquals(0L, pages(file).first().position)
        assertValidOggOpus(pages(joined(file)))
    }

    @Test fun packetsOfOtherLengthsAreCountedByTheirOwn() {
        // A 10 ms SILK frame (configuration 0, code 0: 480), two 20 ms CELT
        // frames (code 1: 1920), three in a code 3 packet (2880).
        val sizes = mapOf(0x00 to 480, TOC_20_MS or 1 to 1920, TOC_20_MS or 3 to 2880)
        val body = listOf(packet(1, 40, 0x00), packet(2, 90, TOC_20_MS or 1), packet(3, 120, TOC_20_MS or 3).also { it[1] = 3 })
        val file = page(seq = 2, position = 0, body = packet(0, 60)) +
            page(seq = 3, position = 960, body = body.reduce { a, b -> a + b },
                lacing = body.map { it.size.toByte() }.toByteArray())
        val out = pages(joined(file))
        assertValidOggOpus(out) { it -> sizes[it[0].toInt() and 0xff] ?: 960 }
        assertEquals(960L + 480 + 1920 + 2880, out.last().position)
    }

    @Test fun thePagesAreNumberedOnAndSealed() {
        val (file, _) = muxed(130)
        val after = pages(joined(file))
        after.forEachIndexed { i, p ->
            assertEquals(2 + i, p.sequence)
            assertEquals(p.crc, crcOf(p.bytes))
        }
    }

    @Test fun onlyTheLastPageEndsTheStream() {
        // None of the muxer's is marked; the last one out is.
        val (file, _) = muxed(130)
        val after = pages(joined(file))
        after.dropLast(1).forEach { assertEquals(0, it.type) }
        assertEquals(4, after.last().type)
    }

    @Test fun theHeadersGoAndTheLacingStays() {
        // 51 packets of 60 bytes: 51 pages of 27 + 1 + 60 bytes become the
        // first as it was and one of 27 + 50 + 3000.
        val (file, _) = muxed(51) { 60 }
        assertEquals(51 * 88, file.size)
        assertEquals(88 + 27 + 50 + 50 * 60, joined(file).size)
    }

    @Test fun theFirstPageStaysAloneWithItsPacket() {
        // Joined into it, Android decoded the stream's pre-skip as sound. Its
        // position alone changes: where its packet ends, not where it starts.
        val (file, _) = muxed(120)
        val before = pages(file).first()
        val after = pages(joined(file)).first()
        assertEquals(960L, after.position)
        assertEquals(before.segments, after.segments)
        assertArrayEquals(before.bytes.copyOfRange(27, before.bytes.size), after.bytes.copyOfRange(27, after.bytes.size))
        assertEquals(after.crc, crcOf(after.bytes))
    }

    @Test fun theLacingLimitEndsAPageSooner() {
        // 2550-byte packets take 11 lacing values: 23 of them fit in 255.
        val (file, sent) = muxed(60) { 2550 }
        val out = pages(joined(file))
        out.forEach { assertTrue(it.segments <= 255) }
        assertEquals(23 * 11, out[1].segments)
        val got = packets(out)
        sent.indices.forEach { assertArrayEquals(sent[it], got[it].first) }
    }

    /** The stream's first audio page, which stays alone: one packet, 960. */
    private val opening = page(seq = 1, position = 0, body = packet(0, 70))

    @Test fun aPacketRunningOnToTheNextPageStaysWhole() {
        val long = packet(9, 600)
        val a = page(seq = 2, position = 3840, body = packet(1, 80) + long.copyOfRange(0, 255),
            lacing = byteArrayOf(80, 255.toByte()))
        val b = page(seq = 3, position = -1, body = long.copyOfRange(255, 510), type = 1,
            lacing = byteArrayOf(255.toByte()))
        val c = page(seq = 4, position = 4800, body = long.copyOfRange(510, 600), type = 1 or 4,
            lacing = byteArrayOf(90))
        val out = pages(joined(opening + a + b + c))
        assertEquals(2, out.size)
        val got = packets(out)
        assertEquals(3, got.size)
        assertArrayEquals(packet(1, 80), got[1].first)
        assertArrayEquals(long, got[2].first)
        assertEquals(3 * 960L, out[1].position)
        // Not "continued": the joined page starts where a did; the stream's
        // last.
        assertEquals(4, out[1].type)
    }

    @Test fun aPageOnWhichNothingEndsKeepsTheLastPositionBeforeIt() {
        val long = packet(9, 600)
        val a = page(seq = 2, position = 3840, body = packet(1, 80) + long.copyOfRange(0, 255),
            lacing = byteArrayOf(80, 255.toByte()))
        val b = page(seq = 3, position = -1, body = long.copyOfRange(255, 510), type = 1,
            lacing = byteArrayOf(255.toByte()))
        val out = pages(joined(opening + a + b))
        assertEquals(2, out.size)
        assertEquals(2 * 960L, out[1].position)
    }

    @Test fun aJoinedPageThatStartsMidPacketSaysSo() {
        // 254 segments of one packet, then its last 520 bytes: the lacing
        // limit puts them on two pages, the second one "continued".
        val huge = packet(5, 254 * 255 + 520)
        val a = page(seq = 2, position = -1, body = huge.copyOfRange(0, 254 * 255),
            lacing = ByteArray(254) { 255.toByte() })
        val b = page(seq = 3, position = 3840, body = huge.copyOfRange(254 * 255, huge.size), type = 1,
            lacing = byteArrayOf(255.toByte(), 255.toByte(), 10))
        val out = pages(joined(opening + a + b))
        assertEquals(3, out.size)
        assertEquals(0, out[1].type and 1)
        assertEquals(1, out[2].type and 1)
        assertArrayEquals(huge, packets(out)[1].first)
    }

    @Test fun whatIsNotAPageIsCopiedAsItIs() {
        val (file, _) = muxed(10)
        val tail = "not a page at all".toByteArray()
        val out = joined(file + tail)
        assertArrayEquals(tail, out.copyOfRange(out.size - tail.size, out.size))
        assertEquals(10, packets(pages(out.copyOfRange(0, out.size - tail.size))).size)
    }

    @Test fun aLongTailThatIsNotAPageIsCopiedAsItIs() {
        // Longer than a header: read in full, found not to start "OggS".
        val (file, _) = muxed(10)
        val tail = ByteArray(300) { (it * 13 + 1).toByte() }
        val out = joined(file + tail)
        assertArrayEquals(tail, out.copyOfRange(out.size - tail.size, out.size))
    }

    @Test fun aPageCutInItsLacingIsCopiedAsItIs() {
        val (file, _) = muxed(10)
        val cut = page(seq = 12, position = 12_480, body = packet(9, 600)).copyOfRange(0, 28)
        val out = joined(file + cut)
        assertArrayEquals(cut, out.copyOfRange(out.size - cut.size, out.size))
        assertEquals(10, packets(pages(out.copyOfRange(0, out.size - cut.size))).size)
    }

    @Test fun aCutPageIsCopiedAsItIs() {
        val (file, _) = muxed(10)
        val cut = file.copyOfRange(0, file.size - 5)
        val out = joined(cut)
        val lastWhole = pages(file).dropLast(1).sumOf { it.bytes.size }
        assertArrayEquals(cut.copyOfRange(lastWhole, cut.size), out.copyOfRange(out.size - (cut.size - lastWhole), out.size))
    }

    @Test fun nothingInNothingOut() {
        assertEquals(0, joined(ByteArray(0)).size)
    }

    @Test fun onePageComesOutAtItsEndAndEndingTheStream() {
        val one = page(seq = 2, position = 0, body = packet(1, 120))
        val out = pages(joined(one))
        assertEquals(1, out.size)
        assertEquals(960L, out[0].position)
        assertEquals(4, out[0].type)
        assertArrayEquals(one.copyOfRange(27, one.size), out[0].bytes.copyOfRange(27, out[0].bytes.size))
    }

    @Test fun aStreamCutShortEndsWithItsLastWholePage() {
        val (file, _) = muxed(10)
        val out = joined(file.copyOfRange(0, file.size - 5))
        val whole = pages(out.copyOfRange(0, pagesUpTo(out)))
        assertEquals(9, whole.sumOf { it.segments })
        assertEquals(4, whole.last().type)
    }

    @Test fun fromAnotherStreamOnEverythingIsCopiedAsItIs() {
        val first = (0 until 3).map { page(seq = 2 + it, position = 2880L + 960L * it, body = packet(it, 60)) }
            .reduce { a, b -> a + b }
        val next = page(seq = 0, position = 0, body = "OpusHead".toByteArray(), type = 2) +
            page(seq = 1, position = 0, body = packet(7, 60)) + page(seq = 2, position = 960, body = packet(8, 60))
        val out = joined(first + next)
        // The first alone and two joined, the second its stream's last; then
        // the next stream's untouched.
        val mine = pages(out.copyOfRange(0, out.size - next.size))
        assertEquals(2, mine.size)
        assertEquals(4, mine.last().type)
        assertArrayEquals(next, out.copyOfRange(out.size - next.size, out.size))
    }

    /** How many bytes of [file] are whole pages, from its start. */
    private fun pagesUpTo(file: ByteArray): Int {
        var at = 0
        while (at + 27 <= file.size && String(file, at, 4, Charsets.US_ASCII) == "OggS") {
            val segments = file[at + 26].toInt() and 0xff
            if (at + 27 + segments > file.size) break
            val end = at + 27 + segments + (0 until segments).sumOf { file[at + 27 + it].toInt() and 0xff }
            if (end > file.size) break
            at = end
        }
        return at
    }

    // --- mend: the muxer's file given its positions where its pages lie -----

    /** A whole file as the muxer writes it: OpusHead, OpusTags, then [count]
     *  audio pages - and the [tags] packet as long as asked, over pages if
     *  it is longer than a page holds. */
    private fun file(count: Int, tags: Int = 40, size: (Int) -> Int = { 60 + it % 37 }): ByteArray {
        val head = page(seq = 0, position = 0, body = "OpusHead".toByteArray() + ByteArray(11), type = 2)
        val tagBody = "OpusTags".toByteArray() + ByteArray(tags - 8)
        val tagPages = if (tagBody.size < 255 * 255) {
            page(seq = 1, position = 0, body = tagBody)
        } else {
            val cut = 255 * 255
            page(seq = 1, position = -1, body = tagBody.copyOfRange(0, cut), lacing = ByteArray(255) { 255.toByte() }) +
                page(seq = 2, position = 0, body = tagBody.copyOfRange(cut, tagBody.size), type = 1,
                    lacing = lacingFor(tagBody.size - cut))
        }
        val first = if (tagBody.size < 255 * 255) 2 else 3
        val audio = (0 until count).map { page(seq = first + it, position = 960L * it, body = packet(it, size(it))) }
            .reduce { a, b -> a + b }
        return head + tagPages + audio
    }

    /** [file] mended in place: what came of it, and how many writes. */
    private fun mended(file: ByteArray): Triple<ByteArray, Boolean, Int> {
        val bytes = file.copyOf()
        var writes = 0
        val ok = OggPages.mend(
            read = { offset, into, at, length ->
                val n = minOf(length.toLong(), bytes.size - offset).toInt()
                if (n <= 0) -1 else { bytes.copyInto(into, at, offset.toInt(), offset.toInt() + n); n }
            },
            write = { offset, page -> page.copyInto(bytes, offset.toInt()); writes++ },
        )
        return Triple(bytes, ok, writes)
    }

    @Test fun mendingGivesTheMuxersPagesTheirPositionsWhereTheyLie() {
        val file = file(40)
        val (out, ok, _) = mended(file)
        assertTrue(ok)
        assertEquals(file.size, out.size)
        val all = pages(out)
        // The headers at 0, as they were.
        assertArrayEquals(pages(file)[0].bytes, all[0].bytes)
        assertArrayEquals(pages(file)[1].bytes, all[1].bytes)
        assertValidOggOpus(all.drop(2))
        all.forEach { assertEquals(it.crc, crcOf(it.bytes)) }
        // Every packet where it was.
        assertEquals(packets(pages(file)).map { it.first.toList() }, packets(all).map { it.first.toList() })
    }

    @Test fun aTagsPacketOverTwoPagesIsNoAudio() {
        val file = file(5, tags = 255 * 255 + 100)
        val all = pages(mended(file).first)
        assertEquals(-1L, all[1].position)
        assertEquals(0L, all[2].position)
        assertValidOggOpus(all.drop(3))
    }

    @Test fun aMendedFileIsLeftAlone() {
        val once = mended(file(30)).first
        val (twice, ok, writes) = mended(once)
        assertTrue(ok)
        assertEquals(0, writes)
        assertArrayEquals(once, twice)
    }

    @Test fun mendingStopsWhereThePagesDo() {
        val file = file(10)
        val tail = "not a page".toByteArray()
        val (out, ok, _) = mended(file + tail)
        assertTrue(ok)
        assertArrayEquals(tail, out.copyOfRange(out.size - tail.size, out.size))
        assertValidOggOpus(pages(out.copyOfRange(0, out.size - tail.size)).drop(2))
    }

    @Test fun nothingToMendIsSaid() {
        assertEquals(false, mended(ByteArray(0)).second)
        assertEquals(false, mended("not ogg".toByteArray()).second)
    }

    @Test fun anOggFileOfAnotherCodecIsLeftAlone() {
        // Its packets are not Opus: positions worked out from them would be
        // wrong.
        val vorbis = page(seq = 0, position = 0, body = "\u0001vorbis".toByteArray() + ByteArray(23), type = 2) +
            page(seq = 1, position = 0, body = packet(1, 60)) + page(seq = 2, position = 5000, body = packet(2, 60))
        val (out, ok, writes) = mended(vorbis)
        assertEquals(false, ok)
        assertEquals(0, writes)
        assertArrayEquals(vorbis, out)
    }

    @Test fun aMendStoppedPartWayLeavesNoPositionGoingBack() {
        // Stopped after each number of writes in turn - the app killed
        // during it - the positions still only go on.
        val file = file(30)
        for (stopAfter in 0..31) {
            val bytes = file.copyOf()
            var writes = 0
            runCatching {
                OggPages.mend(
                    read = { offset, into, at, length ->
                        val n = minOf(length.toLong(), bytes.size - offset).toInt()
                        if (n <= 0) -1 else { bytes.copyInto(into, at, offset.toInt(), offset.toInt() + n); n }
                    },
                    write = { offset, page ->
                        if (writes++ == stopAfter) error("killed")
                        page.copyInto(bytes, offset.toInt())
                    },
                )
            }
            val positions = pages(bytes).drop(2).map { it.position }
            assertEquals("stopped after $stopAfter", positions.sorted(), positions)
        }
    }

    private fun endsTheStream(file: ByteArray) = OggPages.endsTheStream(
        { offset, into, at, length ->
            val n = minOf(length.toLong(), file.size - offset).toInt()
            if (n <= 0) -1 else { file.copyInto(into, at, offset.toInt(), offset.toInt() + n); n }
        },
        file.size.toLong(),
    )

    @Test fun aLookAtTheLastPageSaysWhetherAFileIsMended() {
        val file = file(40)
        assertEquals(false, endsTheStream(file))
        assertEquals(true, endsTheStream(mended(file).first))
        assertEquals(true, endsTheStream(joined(muxed(130).first)))
        // Longer than a page's worth: still only the tail is looked at.
        val long = file(300) { 2550 }
        assertEquals(false, endsTheStream(long))
        assertEquals(true, endsTheStream(mended(long).first))
        assertEquals(false, endsTheStream(ByteArray(0)))
    }

    private companion object {
        const val TOC_20_MS = 0xFC
    }
}
