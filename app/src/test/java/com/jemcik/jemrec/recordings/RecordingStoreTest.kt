package com.jemcik.jemrec.recordings

import android.provider.MediaStore
import androidx.core.net.toUri
import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.recordings.FakeStorage.appUri
import org.junit.Assert.assertArrayEquals
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
import java.io.FileOutputStream

/**
 * Where a recording goes as it is saved, in each of the three places:
 * a folder the user picked, then Recordings/JemRec through MediaStore, then
 * app storage, each falling back to the next; what finishing one does, and
 * what giving it up does. What Android itself makes of it is the emulator
 * checks': these hold what JemRec asks for.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class RecordingStoreTest {

    private val app = RuntimeEnvironment.getApplication()
    private val name = "20261101_120000_in.ogg"
    private val bytes = byteArrayOf(1, 2, 3)

    @Before fun setUp() {
        FakeStorage.forgetFileProviderFolders()
        FakeMediaStore.install(File(app.cacheDir, "media"))
    }

    private fun RecordingStore.Target.write() =
        FileOutputStream(descriptor.fileDescriptor).run { write(bytes); flush() }

    @Test fun withNoFolderChosenItGoesToRecordingsJemRecHiddenUntilItIsFinished() {
        val target = RecordingStore.open(app, name)!!
        val asked = FakeMediaStore.inserted.single()
        assertEquals(name, asked[MediaStore.MediaColumns.DISPLAY_NAME])
        assertEquals("audio/ogg", asked[MediaStore.MediaColumns.MIME_TYPE])
        assertEquals("Recordings/JemRec", asked[MediaStore.MediaColumns.RELATIVE_PATH])
        assertEquals(1, asked[MediaStore.MediaColumns.IS_PENDING])
        assertEquals(name, target.name)
        assertEquals(FakeMediaStore.uriOf(1), target.uri)
        assertNull(target.filePath)
        val row = FakeMediaStore.rows.single()
        assertTrue(row.pending)

        target.write()
        assertTrue(target.publish())
        assertFalse(row.pending)
        assertArrayEquals(bytes, row.file!!.readBytes())
    }

    @Test fun givenUpInRecordingsItLeavesNoRow() {
        RecordingStore.open(app, name)!!.abandon()
        assertTrue(FakeMediaStore.rows.isEmpty())
    }

    @Test fun aRowThatWillNotBeShownIsNoSavedRecording() {
        val target = RecordingStore.open(app, name)!!
        FakeMediaStore.refuses = IllegalStateException("no")
        assertFalse(target.publish())
    }

    @Test fun aChosenFolderComesFirst() {
        val dir = FakeFolder.install(app)
        val target = RecordingStore.open(app, name)!!
        assertEquals(FakeFolder.AUTHORITY, target.uri.authority)
        assertNull(target.filePath)
        assertEquals(name, target.name)
        assertTrue(FakeMediaStore.inserted.isEmpty())
        // Read and write: the muxer seeks back to finish its headers.
        target.write()
        assertTrue(target.publish())
        assertArrayEquals(bytes, File(dir, name).readBytes())
    }

    @Test fun givenUpInAChosenFolderItLeavesNoFile() {
        val dir = FakeFolder.install(app)
        RecordingStore.open(app, name)!!.abandon()
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun aFolderThatWillNotTakeItFallsBackToRecordings() {
        FakeFolder.install(app)
        FakeFolder.refuses = SecurityException("access taken back")
        val refused = RecordingStore.open(app, name)!!
        assertEquals(MediaStore.AUTHORITY, refused.uri.authority)
        refused.abandon()

        FakeFolder.refuses = null
        FakeFolder.creates = { null }
        val noDocument = RecordingStore.open(app, name)!!
        assertEquals(MediaStore.AUTHORITY, noDocument.uri.authority)
        noDocument.abandon()
    }

    @Test fun recordingsThatWillNotTakeItFallsBackToAppStorage() {
        FakeMediaStore.install()
        val target = RecordingStore.open(app, name)!!
        val file = File(FakeStorage.appFolder(app), name)
        assertEquals(file.absolutePath, target.filePath)
        assertEquals(appUri(app, file), target.uri)
        assertEquals(name, target.name)
        target.write()
        assertTrue(target.publish())
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun givenUpInAppStorageItLeavesNoFile() {
        FakeMediaStore.install()
        RecordingStore.open(app, name)!!.abandon()
        assertFalse(File(FakeStorage.appFolder(app), name).exists())
    }

    @Test fun nowhereAtAllIsNoTargetAndNothingBeingWritten() {
        FakeMediaStore.install()
        val inTheWay = File(app.getExternalFilesDir(null), "recordings").apply { deleteRecursively(); writeText("a file") }
        assertNull(RecordingStore.open(app, name))
        assertFalse(Waveforms.isBeingWritten(name))
        inTheWay.delete()
    }

    @Test fun itIsBeingWrittenFromOpenUntilItIsFinished() {
        val level = keptLevels()
        val target = RecordingStore.open(app, name)!!
        assertTrue(Waveforms.isBeingWritten(name))
        target.publish()
        assertFalse(Waveforms.isBeingWritten(name))
        // What was kept under the name was another file's.
        assertFalse(level.exists())
    }

    @Test fun itIsBeingWrittenFromOpenUntilItIsGivenUp() {
        val level = keptLevels()
        val target = RecordingStore.open(app, name)!!
        target.abandon()
        assertFalse(Waveforms.isBeingWritten(name))
        assertFalse(level.exists())
    }

    @Test fun itEndsOnce() {
        val target = RecordingStore.open(app, name)!!
        target.publish()
        // A publish that failed is abandoned after it (the saver): levels
        // worked out in between are not the file's to lose a second time.
        val level = keptLevels()
        target.abandon()
        assertTrue(level.exists())
        level.delete()
    }

    private val taken = "20261101_120000_in (1).ogg"

    @Test fun aFolderThatFindsTheNameTakenGivesAnotherAndThatIsTheOneBeingWritten() {
        FakeFolder.install(app)
        FakeFolder.creates = { taken }
        val theOther = keptLevels()
        val target = RecordingStore.open(app, name)!!
        assertEquals(taken, target.name)
        assertTrue(Waveforms.isBeingWritten(taken))
        assertFalse(Waveforms.isBeingWritten(name))
        assertTrue(target.publish())
        assertEquals(taken, target.name)
        assertFalse(Waveforms.isBeingWritten(taken))
        // The file that has the name is another recording, levels and all.
        assertTrue(theOther.exists())
        theOther.delete()
    }

    @Test fun mediaStoreMayNameItAsItFinishes() {
        val theOther = keptLevels()
        FakeMediaStore.finishesAs = { taken }
        val target = RecordingStore.open(app, name)!!
        assertEquals(name, target.name)
        assertTrue(target.publish())
        assertEquals(taken, target.name)
        assertFalse(Waveforms.isBeingWritten(name))
        assertFalse(Waveforms.isBeingWritten(taken))
        assertTrue(theOther.exists())
        theOther.delete()
    }

    @Test fun aNameThatCannotBeReadBackStaysTheOneAsked() {
        FakeMediaStore.finishesAs = { taken }
        val target = RecordingStore.open(app, name)!!
        FakeMediaStore.listing = IllegalStateException("no")
        assertTrue(target.publish())
        assertEquals(name, target.name)
    }

    private fun keptLevels() = Waveforms.cacheFile(app, name).apply {
        parentFile?.mkdirs()
        writeBytes(ByteArray(Waveforms.LEVELS))
    }

    @Test fun whatTheUserIsToldOfTheFolder() {
        assertEquals("Recordings/JemRec", RecordingStore.describe(app))
        assertNull(RecordingStore.treeUri(app))

        FakeFolder.install(app)
        assertEquals(FakeFolder.tree, RecordingStore.treeUri(app))
        assertEquals("Calls", RecordingStore.describe(app))

        // A volume's root has nothing after the colon; a tree it cannot read
        // has no id at all.
        RecordingStore.setTree(app, "content://${FakeFolder.AUTHORITY}/tree/primary%3A".toUri())
        assertEquals("the folder you chose", RecordingStore.describe(app))
        RecordingStore.setTree(app, "content://${FakeFolder.AUTHORITY}/document/1".toUri())
        assertEquals("the folder you chose", RecordingStore.describe(app))

        RecordingStore.clearTree(app)
        assertNull(RecordingStore.treeUri(app))
        assertEquals("Recordings/JemRec", RecordingStore.describe(app))
        assertFalse(Prefs.of(app).contains("recordings_tree_uri"))
    }

    @Test fun aNameIsWhenAndWhichWay() {
        val at = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).parse("20261101_120000")!!
        assertEquals("20261101_120000_in.ogg", RecordingStore.nameFor(at, incoming = true))
        assertEquals("20261101_120000_out.ogg", RecordingStore.nameFor(at, incoming = false))
    }
}
