package com.jemcik.jemrec.recordings

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

/** Recordings saved for Honor's Phone app before 1.2 under a name it cannot
 *  read, renamed as the folder is read, with what the app remembers of each. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class HonorRenamesTest {

    private val app = RuntimeEnvironment.getApplication()
    private val old = "+380954388250@+380954388250_20261117155459.ogg"
    private val new = "+380954388250_20261117155459.ogg"
    private lateinit var dir: File

    @Before fun setUp() {
        FakeStorage.forgetFileProviderFolders()
        FakeMediaStore.install(File(app.cacheDir, "media"))
        dir = FakeFolder.install(app, id = "primary:Sounds/CallRecord")
    }

    @Test fun anOldNameIsRenamedAndKeepsWhatWasRememberedOfIt() {
        FakeFolder.file(old)
        RecordingMarks.Keep.set(app, listOf(old), kept = true)
        RecordingMarks.Messages.mark(app, old)
        RecordingMarks.Directions.note(app, old, incoming = true)
        val listed = RecordingLibrary.list(app)
        assertEquals(listOf(new), listed.map { it.name })
        assertTrue(File(dir, new).exists())
        assertFalse(File(dir, old).exists())
        val recording = listed.single()
        assertTrue(recording.kept)
        assertTrue(recording.message)
        assertEquals(true, recording.incoming)
        // Nothing is remembered by the old name any more.
        assertFalse(old in RecordingMarks.Keep.all(app))
        assertFalse(old in RecordingMarks.Messages.all(app))
        assertFalse(old in RecordingMarks.Directions.all(app))
    }

    @Test fun aNameThePhoneAppReadsIsLeftAlone() {
        FakeFolder.file("Priya Shah@+447700900123_20261020165705.ogg")
        FakeFolder.file("20261020_170000_in_+447700900123.ogg")
        assertEquals(
            setOf("Priya Shah@+447700900123_20261020165705.ogg", "20261020_170000_in_+447700900123.ogg"),
            RecordingLibrary.list(app).map { it.name }.toSet(),
        )
    }

    @Test fun aNameTakenIsLeftAsItIs() {
        FakeFolder.file(old)
        FakeFolder.file(new)
        RecordingLibrary.list(app)
        assertTrue(File(dir, old).exists())
        assertTrue(File(dir, new).exists())
    }

    /** The name taken between the listing and the rename - a save landing
     *  meanwhile: the provider makes one of its own, "(1)", as Android's
     *  does, and what JemRec remembers goes to the name it made, read back
     *  (review, 2026-11-18: the fake replaced the taken file, and this was
     *  never run). */
    @Test fun aNameTakenMeanwhileGetsTheProvidersOwnAndKeepsItsMarks() {
        FakeFolder.file(old)
        RecordingMarks.Keep.set(app, listOf(old), kept = true)
        val listedBefore = FolderRecordings.documents(app)
        FakeFolder.file(new)
        HonorRenames.fix(app, listedBefore)
        val made = "+380954388250_20261117155459 (1).ogg"
        assertTrue(File(dir, made).exists())
        assertTrue("the file that took the name is left alone", File(dir, new).exists())
        assertTrue(made in RecordingMarks.Keep.all(app))
        assertFalse(old in RecordingMarks.Keep.all(app))
    }

    /** Renamed, then listed again - and that listing failing: said as
     *  failed, not as an empty folder, which the pass over old recordings
     *  took for gone through (review, 2026-11-18). */
    @Test fun aFailedListingAfterTheRenamesIsSaidSo() {
        FakeFolder.file(old)
        val listedBefore = FolderRecordings.documents(app)
        FakeFolder.children = IllegalStateException("the provider failed")
        try {
            assertEquals(null, HonorRenames.fix(app, listedBefore))
        } finally {
            FakeFolder.children = null
        }
        assertTrue("renamed all the same", File(dir, new).exists())
    }

    @Test fun outsideHonorsFolderNothingIsRenamed() {
        dir = FakeFolder.install(app)
        FakeFolder.file(old)
        assertEquals(listOf(old), RecordingLibrary.list(app).map { it.name })
    }

    @Test fun aDeletedOneWaitsForItsRestore() {
        // Unplayable as it is (TrashNames); put right if it comes back.
        val trashed = TrashNames.nameFor(old, 1_795_000_000_000)
        FakeFolder.file(trashed)
        RecordingLibrary.list(app)
        assertTrue(File(dir, trashed).exists())
    }
}
