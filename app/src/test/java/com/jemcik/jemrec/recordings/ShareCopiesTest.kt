package com.jemcik.jemrec.recordings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Share's .m4a copies of calls go once they are stale, and only then. */
class ShareCopiesTest {

    @get:Rule val temp = TemporaryFolder()

    private val now = 1_795_000_000_000L

    private fun copy(dir: File, name: String, age: Long) =
        File(dir, name).apply { writeText("aac"); setLastModified(now - age) }

    @Test fun aStaleCopyGoesAndAFreshOneStays() {
        val dir = temp.newFolder("shares")
        val stale = copy(dir, "20261020_101010_in.m4a", ShareCopies.STALE_MS + 1_000)
        // A share in flight: the receiving app may still be reading it.
        val fresh = copy(dir, "20261021_111111_out.m4a", 60_000)
        assertEquals(1, ShareCopies.sweep(dir, now))
        assertFalse(stale.exists())
        assertTrue(fresh.exists())
    }

    @Test fun aFolderInTheSharesIsLeftAlone() {
        val dir = temp.newFolder("shares")
        val sub = File(dir, "odd").apply { mkdirs(); setLastModified(now - 2 * ShareCopies.STALE_MS) }
        assertEquals(0, ShareCopies.sweep(dir, now))
        assertTrue(sub.exists())
    }

    @Test fun noFolderYetIsNothingToSweep() {
        assertEquals(0, ShareCopies.sweep(File(temp.root, "never-made"), now))
    }

    /** A recording leaving the list takes its copy, unless the copy may
     *  still be being read: shared and deleted at once. */
    @Test fun aCopyStillBeingReadStaysWhenItsRecordingGoes() {
        val dir = temp.newFolder("shares")
        val read = copy(dir, "20261020_101010_in.m4a", ShareCopies.IN_FLIGHT_MS + 1_000)
        val reading = copy(dir, "20261021_111111_out.m4a", 30_000)
        assertTrue(ShareCopies.forget(dir, "20261020_101010_in.ogg", now))
        assertFalse(read.exists())
        assertFalse(ShareCopies.forget(dir, "20261021_111111_out.ogg", now))
        assertTrue(reading.exists())
        assertFalse(ShareCopies.forget(dir, "never_shared.ogg", now))
    }

    @Test fun aCopyIsNamedAfterItsRecording() {
        val dir = temp.root
        assertEquals("20261020_101010_in.m4a", ShareCopies.of(dir, "20261020_101010_in.ogg").name)
        // Honor's names have dots and an @ in them: only the extension goes.
        assertEquals(
            "Priya Shah@+447700900123_20261020165705.m4a",
            ShareCopies.of(dir, "Priya Shah@+447700900123_20261020165705.ogg").name,
        )
        assertEquals("J. R. Smith_20261020_101010.m4a", ShareCopies.of(dir, "J. R. Smith_20261020_101010.ogg").name)
    }
}
