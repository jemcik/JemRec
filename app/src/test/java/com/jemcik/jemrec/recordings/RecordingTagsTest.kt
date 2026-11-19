package com.jemcik.jemrec.recordings

import com.jemcik.jemrec.recordings.OggSamples.applied
import com.jemcik.jemrec.recordings.OggSamples.crcOf
import com.jemcik.jemrec.recordings.OggSamples.honorHeaders
import com.jemcik.jemrec.recordings.OggSamples.pages
import com.jemcik.jemrec.recordings.OggSamples.recording
import com.jemcik.jemrec.recordings.OggSamples.splice
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A recording's own tags: written into its second page, every other page
 *  left as the muxer wrote it, and read back - the marks a reinstall used to
 *  lose. */
class RecordingTagsTest {

    @Test fun theCrcIsOggsAsTheHonorsFilesHaveIt() {
        val pages = pages(honorHeaders)
        assertEquals(2, pages.size)
        val stored = pages.map { ByteBuffer.wrap(it, 22, 4).order(ByteOrder.LITTLE_ENDIAN).int }
        assertEquals(listOf(0x11d2b0c2, 0xa97f59fc.toInt()), stored)
        pages.forEachIndexed { i, page -> assertEquals(stored[i], crcOf(page)) }
    }

    /**
     * RFC 7845's positions, read from the file here, not by OggPages: the
     * header pages at 0; each audio page at the end of the last packet that
     * ends on it - every packet here one 20 ms frame, 960 samples - or -1
     * where none ends; the last page alone marked the stream's end; every
     * checksum good.
     */
    private fun assertSpecPositions(file: ByteArray) {
        val pages = pages(file)
        var samples = 0L
        pages.forEachIndexed { i, page ->
            val granule = ByteBuffer.wrap(page, 6, 8).order(ByteOrder.LITTLE_ENDIAN).long
            val segments = page[26].toInt() and 0xff
            val ended = (0 until segments).count { (page[27 + it].toInt() and 0xff) < 255 }
            if (i < 2) {
                assertEquals("header page $i", 0L, granule)
            } else {
                samples += 960L * ended
                assertEquals("page $i", if (ended == 0) -1L else samples, granule)
            }
            assertEquals("page $i: the stream's end", i == pages.size - 1, (page[5].toInt() and 0x04) != 0)
            assertEquals("page $i: its checksum", ByteBuffer.wrap(page, 22, 4).order(ByteOrder.LITTLE_ENDIAN).int, crcOf(page))
        }
    }

    private fun tempFile(bytes: ByteArray) = java.io.File.createTempFile("recording", ".ogg").apply {
        deleteOnExit()
        writeBytes(bytes)
    }

    /** The muxer's file written straight into the recording - a save with
     *  no room for a work file - is given its positions where it lies, and
     *  nothing else changes. Until the review of 2026-11-18 no test held
     *  this path; the saver's tests write raw packets, not Ogg. */
    @Test fun aRecordingSavedWithoutAWorkFileHasItsPositions() {
        val before = recording()
        val file = tempFile(before)
        java.io.RandomAccessFile(file, "rw").use { RecordingTags.mendInPlace(it.fd) }
        val after = file.readBytes()
        assertEquals(before.size, after.size)
        assertSpecPositions(after)
        // The pages' lacing and audio are as the muxer wrote them.
        pages(before).zip(pages(after)).forEach { (b, a) -> assertArrayEquals(b.copyOfRange(26, b.size), a.copyOfRange(26, a.size)) }
    }

    /** A save with a work file: the copy has its tags and its positions. */
    @Test fun aTaggedCopyHasItsTagsAndItsPositions() {
        val from = tempFile(recording())
        val to = tempFile(ByteArray(0))
        java.io.RandomAccessFile(to, "rw").use { RecordingTags.copyTagged(from, it.fd, RecordingTags.Facts(message = true, incoming = true)) }
        val out = to.readBytes()
        assertSpecPositions(out)
        assertEquals(true, RecordingTags.read(out.inputStream())?.message)
    }

    @Test fun aFileAsTheMuxerWroteItHasNothingOfOurs() {
        assertNull(RecordingTags.read(recording().inputStream()))
    }

    @Test fun spliceReplacesTheTagsPageAndJoinsTheAudio() {
        val before = recording()
        val after = splice(before, RecordingTags.Facts(message = true, incoming = true))
        val a = pages(before)
        val b = pages(after)
        // OpusHead as it was; the tags page new; the first audio page alone,
        // the other three one (OggPages) - each at where its last packet
        // ends, the last marked the stream's end (RFC 7845).
        assertEquals(6, a.size)
        assertEquals(4, b.size)
        assertArrayEquals(a[0], b[0])
        assertFalse(a[1].contentEquals(b[1]))
        assertArrayEquals(a[2].copyOfRange(26, a[2].size), b[2].copyOfRange(26, b[2].size))
        assertArrayEquals(packets(a.drop(2)).toTypedArray(), packets(b.drop(2)).toTypedArray())
        assertEquals(960L, granule(b[2]))
        assertEquals(4 * 960L, granule(b[3]))
        assertEquals(0, b[2][5].toInt() and 4)
        assertEquals(4, b[3][5].toInt() and 4)
        // Every page's CRC holds, the new ones' included.
        b.forEach { page -> assertEquals(ByteBuffer.wrap(page, 22, 4).order(ByteOrder.LITTLE_ENDIAN).int, crcOf(page)) }
        // Numbered as the page it replaced: the same stream, the same place.
        assertArrayEquals(a[1].copyOfRange(4, 22), b[1].copyOfRange(4, 22))
    }

    private fun granule(page: ByteArray) = ByteBuffer.wrap(page, 6, 8).order(ByteOrder.LITTLE_ENDIAN).long

    /** The packets on [pages], rebuilt from their lacing. */
    private fun packets(pages: List<ByteArray>): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        val partial = ByteArrayOutputStream()
        for (p in pages) {
            val segments = p[26].toInt() and 0xff
            var at = 27 + segments
            for (s in 0 until segments) {
                val lace = p[27 + s].toInt() and 0xff
                partial.write(p, at, lace)
                at += lace
                if (lace < 255) {
                    out += partial.toByteArray()
                    partial.reset()
                }
            }
        }
        return out
    }

    @Test fun whatGoesInComesBackOut() {
        val every = listOf(
            RecordingTags.Facts(),
            RecordingTags.Facts(message = true, incoming = true),
            RecordingTags.Facts(incoming = false, caller = "Priya Raman"),
            RecordingTags.Facts(incoming = null, caller = "Олена Коваль", kept = true),
            RecordingTags.Facts(caller = "Ann = Bob 👋"),
            // The start no zone moves, and a call's part (review round 13).
            RecordingTags.Facts(incoming = true, startedAt = 1_795_000_000_123L, part = true),
            // A joined part's call start (review round 14).
            RecordingTags.Facts(incoming = false, startedAt = 1_795_000_300_000L, part = true, callAt = 1_795_000_000_000L),
        )
        for (facts in every) assertEquals(facts, RecordingTags.read(splice(recording(), facts).inputStream()))
    }

    @Test fun theVendorIsKept() {
        val after = pages(splice(recording(), RecordingTags.Facts(message = true)))[1]
        assertTrue(String(after, Charsets.ISO_8859_1).contains("libopus"))
    }

    @Test fun aLongNameIsCutWithoutHalvingAnEmoji() {
        val long = "a".repeat(119) + "👋" + "b".repeat(50)
        val caller = RecordingTags.read(splice(recording(), RecordingTags.Facts(caller = long)).inputStream())!!.caller!!
        assertEquals("a".repeat(119), caller)
    }

    @Test fun theKeepMarkIsRewrittenInPlaceAtTheSameLength() {
        val saved = splice(recording(), RecordingTags.Facts(message = true, incoming = false, caller = "Priya Raman"))
        val kept = applied(saved, RecordingTags.rewrite(saved.inputStream(), kept = true)!!)
        assertEquals(saved.size, kept.size)
        assertEquals(RecordingTags.Facts(message = true, incoming = false, caller = "Priya Raman", kept = true), RecordingTags.read(kept.inputStream()))
        // Only the tags page changed.
        val a = pages(saved)
        val b = pages(kept)
        for (i in a.indices) if (i != 1) assertArrayEquals("page $i", a[i], b[i])
        b.forEach { page -> assertEquals(ByteBuffer.wrap(page, 22, 4).order(ByteOrder.LITTLE_ENDIAN).int, crcOf(page)) }
        // And back.
        val unkept = applied(kept, RecordingTags.rewrite(kept.inputStream(), kept = false)!!)
        assertArrayEquals(saved, unkept)
    }

    @Test fun theLongestNameStillLeavesRoomToKeep() {
        val name = "Щ".repeat(200) // three bytes each, cut to 120
        val saved = splice(recording(), RecordingTags.Facts(message = true, incoming = true, caller = name))
        val rewritten = RecordingTags.rewrite(saved.inputStream(), kept = true)
        assertNotNull(rewritten)
        assertTrue(RecordingTags.read(applied(saved, rewritten!!).inputStream())!!.kept)
    }

    @Test fun aFileWithoutOurTagsIsNotRewritten() {
        assertNull(RecordingTags.rewrite(recording().inputStream(), kept = true))
    }

    @Test fun notAnOggFileIsCopiedUntouched() {
        val junk = ByteArray(5000) { (it * 31).toByte() }
        val out = ByteArrayOutputStream()
        assertFalse(RecordingTags.splice(junk.inputStream(), out, RecordingTags.Facts(message = true)))
        assertArrayEquals(junk, out.toByteArray())
    }

    @Test fun fieldNamesAreReadInAnyCase() {
        val facts = RecordingTags.facts(listOf("jemrec=1", "Jemrec_Message=1", "jemrec_direction=in"))
        assertEquals(RecordingTags.Facts(message = true, incoming = true), facts)
        assertNull(RecordingTags.facts(listOf("JEMREC_MESSAGE=1")))
    }

    @Test fun theKeepMarkLeavesOtherFieldsAlone() {
        assertEquals(
            listOf("JEMREC=1", "JEMREC_LATER=x"),
            RecordingTags.withKept(listOf("JEMREC=1", "JEMREC_KEEP=1", "JEMREC_LATER=x"), kept = false),
        )
        assertEquals(
            listOf("JEMREC=1", "JEMREC_LATER=x", "JEMREC_KEEP=1"),
            RecordingTags.withKept(listOf("JEMREC=1", "JEMREC_LATER=x"), kept = true),
        )
    }
}
