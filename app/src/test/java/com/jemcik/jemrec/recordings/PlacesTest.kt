package com.jemcik.jemrec.recordings

import android.provider.MediaStore
import com.jemcik.jemrec.recordings.OggSamples.recording
import com.jemcik.jemrec.recordings.OggSamples.splice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File

/**
 * What the list and the marks read from a place that is not plain files:
 * rows and tags through MediaStore and a chosen folder's provider, the
 * dates a row shows, and what a place that cannot be asked leaves.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class PlacesTest {

    private val app = RuntimeEnvironment.getApplication()
    private val name = "20261020_101010_in.ogg"
    private val later = "20261021_101010_in.ogg"
    private val media get() = File(app.cacheDir, "media")

    @Before fun setUp() {
        FakeStorage.forgetFileProviderFolders()
        FakeMediaStore.install(media)
    }

    private fun tagged(facts: RecordingTags.Facts) = splice(recording(), facts)

    private fun row(id: Long, name: String, bytes: ByteArray, notOurs: Boolean = false): FakeMediaStore.Row {
        val file = File(media, "row-$id").apply { writeBytes(bytes) }
        return FakeMediaStore.Row(id, name, file = file, notOurs = notOurs).also { FakeMediaStore.rows += it }
    }

    @Test fun aReinstallReadsTheMarksInMediaStoresFilesAndAFoldersToo() {
        val dir = FakeFolder.install(app)
        row(1, name, tagged(RecordingTags.Facts(message = true, incoming = true)))
        File(dir, later).writeBytes(tagged(RecordingTags.Facts(incoming = true, kept = true)))
        val rows = Recordings.list(app).associateBy { it.name }
        assertTrue(rows.getValue(name).message)
        assertTrue(rows.getValue(later).kept)
        assertEquals(setOf(name, later), RecordingTags.readNames(app))
    }

    @Test fun theKeepMarkIsWrittenThroughEachProvider() {
        val dir = FakeFolder.install(app)
        val inMedia = row(1, name, tagged(RecordingTags.Facts(incoming = true)))
        File(dir, later).writeBytes(tagged(RecordingTags.Facts(incoming = true)))
        val rows = Recordings.list(app)
        KeepMarks.set(app, listOf(name, later), kept = true)
        assertEquals(2, RecordingTags.writeKept(app, rows))
        assertTrue(RecordingTags.read(inMedia.file!!.inputStream())!!.kept)
        assertTrue(RecordingTags.read(File(dir, later).inputStream())!!.kept)
    }

    @Test fun aFileThisInstallDoesNotOwnKeepsItsMarkInTheAppAlone() {
        val inMedia = row(1, name, tagged(RecordingTags.Facts(incoming = true)), notOurs = true)
        val rows = Recordings.list(app)
        KeepMarks.set(app, listOf(name), kept = true)
        assertEquals(0, RecordingTags.writeKept(app, rows))
        assertFalse(RecordingTags.read(inMedia.file!!.inputStream())!!.kept)
        assertTrue(Recordings.list(app).single().kept)
    }

    @Test fun aRowsPlaceIsItsUriAndOnlyAppStorageHasAPath() {
        val dir = FakeFolder.install(app)
        File(dir, later).writeBytes(recording())
        FakeMediaStore.rows += FakeMediaStore.Row(1, name)
        val rows = Recordings.list(app).associateBy { it.name }
        assertEquals(MediaStore.AUTHORITY, rows.getValue(name).uri.authority)
        assertEquals(FakeFolder.AUTHORITY, rows.getValue(later).uri.authority)
        assertNull(rows.getValue(name).filePath)
        assertNull(rows.getValue(later).filePath)
    }

    @Test fun aFoldersFileThatIsNotOursShowsItsOwnDate() {
        FakeFolder.install(app)
        val at = 1_790_000_000_000L
        FakeFolder.file("voice-note.ogg", modified = at)
        assertEquals(Recordings.whenLabel(at), Recordings.list(app).single().whenLabel)
    }

    @Test fun aTrashedRowDatesItselfAsTheListDoes() {
        FakeMediaStore.rows += FakeMediaStore.Row(
            1, ".trashed-1795000000-voice-note.ogg", trashed = true, expiresSeconds = 1_795_000_000, modifiedSeconds = 1_790_000_000,
        )
        val trashed = Trash.list(app).single()
        assertEquals("voice-note.ogg", trashed.name)
        assertEquals(Recordings.whenLabel(1_790_000_000_000L), trashed.whenLabel)
    }

    @Test fun aPlaceThatCannotBeAskedLeavesTheOthers() {
        val dir = FakeFolder.install(app)
        File(dir, later).writeBytes(recording())
        FakeMediaStore.rows += FakeMediaStore.Row(1, name)
        FakeMediaStore.listing = IllegalStateException("no")
        assertEquals(listOf(later), Recordings.list(app).map { it.name })
        assertTrue(Trash.list(app).isEmpty())
        FakeMediaStore.listing = null
        FakeFolder.listing = IllegalStateException("no")
        assertEquals(listOf(name), Recordings.list(app).map { it.name })
        assertTrue(Trash.list(app).isEmpty())
        assertEquals(0, Trash.purgeExpired(app))
    }

    @Test fun aFolderThatIsNotThereAnyMoreListsNothing() {
        RecordingStore.setTree(app, FakeFolder.tree)
        FakeFolder.listing = IllegalStateException("gone")
        assertTrue(Recordings.list(app).isEmpty())
        RecordingStore.clearTree(app)
    }
}
