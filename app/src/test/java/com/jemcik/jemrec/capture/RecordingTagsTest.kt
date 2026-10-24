package com.jemcik.jemrec.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.jemcik.jemrec.capture.OggSamples.applied
import com.jemcik.jemrec.capture.OggSamples.crcOf
import com.jemcik.jemrec.capture.OggSamples.honorHeaders
import com.jemcik.jemrec.capture.OggSamples.pages
import com.jemcik.jemrec.capture.OggSamples.recording
import com.jemcik.jemrec.capture.OggSamples.splice
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

    @Test fun aFileAsTheMuxerWroteItHasNothingOfOurs() {
        assertNull(RecordingTags.read(recording().inputStream()))
    }

    @Test fun spliceReplacesTheTagsPageAndNothingElse() {
        val before = recording()
        val after = splice(before, RecordingTags.Facts(message = true, incoming = true))
        val a = pages(before)
        val b = pages(after)
        assertEquals(a.size, b.size)
        for (i in a.indices) if (i != 1) assertArrayEquals("page $i", a[i], b[i])
        assertFalse(a[1].contentEquals(b[1]))
        // Every page's CRC holds, the new one's included.
        b.forEach { page -> assertEquals(ByteBuffer.wrap(page, 22, 4).order(ByteOrder.LITTLE_ENDIAN).int, crcOf(page)) }
        // Numbered as the page it replaced: the same stream, the same place.
        assertArrayEquals(a[1].copyOfRange(4, 22), b[1].copyOfRange(4, 22))
    }

    @Test fun whatGoesInComesBackOut() {
        val every = listOf(
            RecordingTags.Facts(),
            RecordingTags.Facts(message = true, incoming = true),
            RecordingTags.Facts(incoming = false, caller = "Priya Raman"),
            RecordingTags.Facts(incoming = null, caller = "Олена Коваль", kept = true),
            RecordingTags.Facts(caller = "Ann = Bob 👋"),
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
