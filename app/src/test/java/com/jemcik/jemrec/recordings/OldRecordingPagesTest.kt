package com.jemcik.jemrec.recordings

import android.content.Context
import com.jemcik.jemrec.Prefs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Recordings saved before 1.2, with the muxer's page positions, given the
 *  ones Ogg Opus means where they lie - JemRec's own, once. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class OldRecordingPagesTest {

    private val app: Context = RuntimeEnvironment.getApplication()
    private val ours = "20261020_170000_in_+447700900123.ogg"
    private lateinit var dir: File

    @Before fun setUp() {
        Prefs.of(app).edit().clear().commit()
        FakeStorage.forgetFileProviderFolders()
        FakeMediaStore.install(File(app.cacheDir, "media"))
        dir = FakeFolder.install(app)
    }

    private fun positions(file: File) = OggSamples.pages(file.readBytes()).drop(2)
        .map { ByteBuffer.wrap(it, 6, 8).order(ByteOrder.LITTLE_ENDIAN).long to (it[5].toInt() and 4) }

    @Test fun anOldRecordingIsMendedWhereItLies() {
        val file = File(dir, ours).apply { writeBytes(OggSamples.recording()) }
        val size = file.length()
        OldRecordingPages.runIfDue(app)
        // Each page where its packet ends, the last ending the stream; the
        // file as long as it was.
        assertEquals(listOf(960L to 0, 1920L to 0, 2880L to 0, 3840L to 4), positions(file))
        assertEquals(size, file.length())
    }

    @Test fun aFileThatIsNotOneOfOursIsLeftAlone() {
        val other = File(dir, "voice note.ogg").apply { writeBytes(OggSamples.recording()) }
        val before = other.readBytes()
        OldRecordingPages.runIfDue(app)
        assertArrayEquals(before, other.readBytes())
    }

    @Test fun itRunsUntilItHasBeenThroughThemAll() {
        File(dir, ours).writeBytes(OggSamples.recording())
        OldRecordingPages.runIfDue(app)
        // Once through: a recording put back as it was after that is not
        // looked at again - every one saved since is written right.
        val again = File(dir, ours).apply { writeBytes(OggSamples.recording()) }
        val before = again.readBytes()
        OldRecordingPages.runIfDue(app)
        assertArrayEquals(before, again.readBytes())
    }

    /** Gone through for one folder, it is gone through again for another
     *  chosen after: marked done once for good, the second folder's old
     *  recordings were never mended (review, 2026-11-18). */
    @Test fun aFolderChosenAfterIsGoneThroughToo() {
        OldRecordingPages.runIfDue(app)
        val second = FakeFolder.install(app, "primary:Other")
        val file = File(second, ours).apply { writeBytes(OggSamples.recording()) }
        OldRecordingPages.runIfDue(app)
        assertTrue("the second folder's recording mended", positions(file).last().second != 0)
    }

    /** A folder chosen and out of reach - its grant gone, as Android lets it
     *  go when the folder moves - is not marked gone through: its recordings
     *  are mended once it is back (review, 2026-11-18). */
    @Test fun aFolderOutOfReachIsGoneThroughOnceItIsBack() {
        val file = File(dir, ours).apply { writeBytes(OggSamples.recording()) }
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        app.contentResolver.releasePersistableUriPermission(FakeFolder.tree, flags)
        OldRecordingPages.runIfDue(app)
        assertFalse("not marked through", OldRecordingPages.goneThrough(app))
        // Chosen again: reached, and gone through.
        app.contentResolver.takePersistableUriPermission(FakeFolder.tree, flags)
        OldRecordingPages.runIfDue(app)
        assertTrue(OldRecordingPages.goneThrough(app))
        assertTrue("mended", positions(file).last().second != 0)
    }

    /** While the folder chosen is out of reach nothing is gone through: a
     *  pass then opened every other recording - app storage's, the
     *  standard folder's - at every keep-alive run, for as long as the
     *  folder stayed lost (review, 2026-11-18). */
    @Test fun nothingIsGoneThroughWhileTheFolderIsOutOfReach() {
        val elsewhere = File(app.getExternalFilesDir(null), "recordings/$ours")
            .apply { parentFile!!.mkdirs(); writeBytes(OggSamples.recording()) }
        val before = elsewhere.readBytes()
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        app.contentResolver.releasePersistableUriPermission(FakeFolder.tree, flags)
        OldRecordingPages.runIfDue(app)
        assertArrayEquals("app storage's recording not opened", before, elsewhere.readBytes())
        // Back: gone through, that one with the rest.
        app.contentResolver.takePersistableUriPermission(FakeFolder.tree, flags)
        OldRecordingPages.runIfDue(app)
        assertTrue("mended once the folder is back", positions(elsewhere).last().second != 0)
    }

    /** Lost while the pass ran - its access let go as it was listed: not
     *  marked gone through, so it is once it is back. */
    @Test fun aFolderLostWhileThePassRanIsNotMarkedThrough() {
        File(dir, ours).writeBytes(OggSamples.recording())
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val shown = FakeFolder.shows
        FakeFolder.shows = { file ->
            runCatching { app.contentResolver.releasePersistableUriPermission(FakeFolder.tree, flags) }
            shown(file)
        }
        try {
            OldRecordingPages.runIfDue(app)
        } finally {
            FakeFolder.shows = shown
        }
        assertFalse("not marked through", OldRecordingPages.goneThrough(app))
    }

    /** A listing of the folder that failed lists nothing: not taken for
     *  the folder gone through. The folder itself still answers - failed
     *  whole, the pass stopped at the folder unreachable, before this was
     *  asked, and the test passed without it (reviews, 2026-11-18). */
    @Test fun aFolderWhoseListingFailedIsNotMarkedThrough() {
        File(dir, ours).writeBytes(OggSamples.recording())
        FakeFolder.children = IllegalStateException("the provider failed")
        try {
            OldRecordingPages.runIfDue(app)
        } finally {
            FakeFolder.children = null
        }
        assertFalse("not marked through", OldRecordingPages.goneThrough(app))
        OldRecordingPages.runIfDue(app)
        assertTrue(OldRecordingPages.goneThrough(app))
    }

    /** The standard folder, MediaStore not answering - busy, restarting
     *  after a module update: not taken for gone through, so it is when it
     *  answers (review, 2026-11-18). */
    @Test fun theStandardFolderUnansweredIsNotMarkedThrough() {
        Prefs.of(app).edit().remove("recordings_tree_uri").commit()
        FakeMediaStore.listing = IllegalStateException("MediaProvider restarting")
        try {
            OldRecordingPages.runIfDue(app)
        } finally {
            FakeMediaStore.listing = null
        }
        assertFalse("not marked through", OldRecordingPages.goneThrough(app))
        OldRecordingPages.runIfDue(app)
        assertTrue(OldRecordingPages.goneThrough(app))
    }

    @Test fun aMendedRecordingIsNotWrittenAgain() {
        val file = File(dir, ours).apply { writeBytes(OggSamples.recording()) }
        OldRecordingPages.runIfDue(app)
        val mended = file.readBytes()
        file.setLastModified(1_000_000_000_000)
        Prefs.of(app).edit().clear().commit()
        OldRecordingPages.runIfDue(app)
        assertArrayEquals(mended, file.readBytes())
        assertEquals(1_000_000_000_000, file.lastModified())
        assertFalse(positions(file).isEmpty())
    }
}
