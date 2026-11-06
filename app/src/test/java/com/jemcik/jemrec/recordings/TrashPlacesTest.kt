package com.jemcik.jemrec.recordings

import android.provider.MediaStore
import com.jemcik.jemrec.recordings.FakeStorage.appFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * What each of the three places is asked when a recording goes to the trash,
 * comes back, or goes for good, and what comes of each answer - Android's own
 * yes included, which no list test reaches. A recording cannot be made again,
 * so every arm is here. What Android itself does with them is the emulator
 * checks' (recently_deleted, recently_deleted_folder, name_taken): these hold
 * what JemRec asks for.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class TrashPlacesTest {

    private val app = RuntimeEnvironment.getApplication()
    private val a = "20261020_101010_in.ogg"
    private val b = "20261021_101010_in.ogg"
    private val c = "20261022_101010_in.ogg"
    private val day = 24L * 60 * 60 * 1000

    @Before fun setUp() {
        FakeStorage.forgetFileProviderFolders()
        FakeMediaStore.install(File(app.cacheDir, "media"))
    }

    // ---- MediaStore ---------------------------------------------------------

    private fun row(id: Long, name: String = a, trashed: Boolean = false, notOurs: Boolean = false): Recording {
        FakeMediaStore.rows += FakeMediaStore.Row(id, name, trashed = trashed, notOurs = notOurs)
        return Recordings.describe(name, FakeMediaStore.uriOf(id), 1000, 1000)
    }

    /** Gone, as a row Android's provider no longer has. */
    private fun missing(id: Long, name: String = a) = Recordings.describe(name, FakeMediaStore.uriOf(id), 1000, 1000)

    @Test fun mediaStoreTrashesByItsOwnFlagWhateverIsInItsTrash() {
        val recording = row(1)
        assertEquals(DeleteResult.Deleted, Trash.trash(app, recording.uri, recording.filePath, a))
        val change = FakeMediaStore.changes.single()
        assertEquals(mapOf(MediaStore.MediaColumns.IS_TRASHED to 1), change.values)
        assertEquals(MediaStore.MATCH_INCLUDE, change.matchTrashed)
        assertTrue(FakeMediaStore.rows.single().trashed)
    }

    @Test fun aRowMediaStoreDoesNotChangeIsAFailure() {
        val recording = row(1)
        FakeMediaStore.changesNothing = true
        assertTrue(Trash.trash(app, recording.uri, recording.filePath, a) is DeleteResult.Failed)
    }

    @Test fun aRowMediaStoreNoLongerHasIsAFailure() {
        val gone = missing(9)
        assertTrue(Trash.trash(app, gone.uri, gone.filePath, a) is DeleteResult.Failed)
        assertTrue(Trash.restore(app, gone) is DeleteResult.Failed)
    }

    @Test fun aRecordingFromBeforeAReinstallAsksTheUser() {
        val recording = row(1, notOurs = true)
        val result = Trash.trash(app, recording.uri, recording.filePath, a)
        assertTrue(result is DeleteResult.NeedsConsent)
        val asked = FakeMediaStore.asked.single()
        assertEquals("create_trash_request", asked.method)
        assertEquals(listOf(recording.uri), asked.uris)
        assertEquals(true, asked.trashed)
        assertFalse(FakeMediaStore.rows.single().trashed)
    }

    @Test fun whenTheUserCannotBeAskedItFails() {
        val recording = row(1, notOurs = true)
        FakeMediaStore.asks = false
        val result = Trash.trash(app, recording.uri, recording.filePath, a)
        assertTrue((result as DeleteResult.Failed).error is SecurityException)
    }

    @Test fun anythingElseMediaStoreThrowsIsAFailure() {
        val recording = row(1)
        val thrown = IllegalStateException("no")
        FakeMediaStore.refuses = thrown
        assertEquals(DeleteResult.Failed(thrown), Trash.trash(app, recording.uri, recording.filePath, a))
    }

    @Test fun mediaStoreRestoresByTheSameFlag() {
        val recording = row(1, trashed = true)
        assertEquals(DeleteResult.Deleted, Trash.restore(app, recording))
        val change = FakeMediaStore.changes.single()
        assertEquals(mapOf(MediaStore.MediaColumns.IS_TRASHED to 0), change.values)
        assertEquals(MediaStore.MATCH_INCLUDE, change.matchTrashed)
        assertFalse(FakeMediaStore.rows.single().trashed)
    }

    @Test fun aRestoreFromBeforeAReinstallAsksTheUser() {
        val recording = row(1, trashed = true, notOurs = true)
        assertTrue(Trash.restore(app, recording) is DeleteResult.NeedsConsent)
        val asked = FakeMediaStore.asked.single()
        assertEquals("create_trash_request", asked.method)
        assertEquals(false, asked.trashed)
    }

    @Test fun manyAskOnceForAllTheDisputedOnes() {
        val ours = row(1, a)
        val first = row(2, b, notOurs = true)
        val second = row(3, c, notOurs = true)
        val gone = missing(9, "20261023_101010_in.ogg")
        val result = Trash.trashAll(app, listOf(ours, first, gone, second))
        assertEquals(1, result.deleted)
        assertEquals(1, result.failed)
        assertNotNull(result.needsConsent)
        // One each as it is found, then the one that is shown, for them all.
        val all = FakeMediaStore.asked.last()
        assertEquals(listOf(first.uri, second.uri), all.uris)
        assertEquals(true, all.trashed)
    }

    @Test fun manyDisputedCountAsFailedWhenTheUserCannotBeAsked() {
        val ours = row(1, a)
        val disputed = row(2, b, notOurs = true)
        FakeMediaStore.asks = false
        val result = Trash.trashAll(app, listOf(ours, disputed))
        assertEquals(1, result.deleted)
        assertEquals(1, result.failed)
        assertNull(result.needsConsent)
    }

    @Test fun restoreAllAsksOnceToo() {
        val ours = row(1, a, trashed = true)
        val disputed = row(2, b, trashed = true, notOurs = true)
        val result = Trash.restoreAll(app, listOf(ours, disputed))
        assertEquals(1, result.deleted)
        assertEquals(0, result.failed)
        assertNotNull(result.needsConsent)
        assertEquals(false, FakeMediaStore.asked.last().trashed)
    }

    @Test fun mediaStoreDeletesForGoodWhatIsInItsTrash() {
        val recording = row(1, trashed = true)
        val result = Trash.deleteForGood(app, listOf(recording))
        assertEquals(BulkDeleteResult(1, 0, null), result)
        assertEquals(MediaStore.MATCH_INCLUDE, FakeMediaStore.deleted.single().matchTrashed)
        assertTrue(FakeMediaStore.rows.isEmpty())
    }

    @Test fun deletingForGoodAsksOnceForWhatIsNotOurs() {
        val ours = row(1, a, trashed = true)
        val first = row(2, b, trashed = true, notOurs = true)
        val second = row(3, c, trashed = true, notOurs = true)
        val result = Trash.deleteForGood(app, listOf(first, ours, second))
        assertEquals(1, result.deleted)
        assertEquals(0, result.failed)
        assertNotNull(result.needsConsent)
        val asked = FakeMediaStore.asked.single()
        assertEquals("create_delete_request", asked.method)
        assertEquals(listOf(first.uri, second.uri), asked.uris)
    }

    @Test fun deletingForGoodWhatCannotBeAskedAboutCountsAsFailed() {
        val disputed = row(1, trashed = true, notOurs = true)
        FakeMediaStore.asks = false
        assertEquals(BulkDeleteResult(0, 1, null), Trash.deleteForGood(app, listOf(disputed)))
    }

    @Test fun aRowThatIsNotDeletedForGoodIsCounted() {
        val nothing = missing(9)
        assertEquals(BulkDeleteResult(0, 1, null), Trash.deleteForGood(app, listOf(nothing)))
        val refused = row(1, trashed = true)
        FakeMediaStore.refuses = IllegalStateException("no")
        assertEquals(BulkDeleteResult(0, 1, null), Trash.deleteForGood(app, listOf(refused)))
    }

    // ---- a chosen folder ----------------------------------------------------

    private fun inFolder(name: String = a): Recording {
        FakeFolder.file(name)
        return Recordings.list(app).single { it.name == name }
    }

    @Test fun aFolderThatWillNotRenameIsAFailure() {
        FakeFolder.install(app)
        val recording = inFolder()
        val refused = IllegalStateException("no")
        FakeFolder.refuses = refused
        assertEquals(DeleteResult.Failed(refused), Trash.trash(app, recording.uri, recording.filePath, a))
        assertEquals(DeleteResult.Failed(refused), Trash.restore(app, recording))
        assertTrue(FakeFolder.dir.resolve(a).exists())
    }

    @Test fun aFolderDeletesForGood() {
        val dir = FakeFolder.install(app)
        val recording = inFolder()
        assertEquals(BulkDeleteResult(1, 0, null), Trash.deleteForGood(app, listOf(recording)))
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun aFolderWhoseAccessIsGoneIsAFailureAndNotAQuestion() {
        FakeFolder.install(app)
        val recording = inFolder()
        FakeFolder.refuses = SecurityException("access taken back")
        assertEquals(BulkDeleteResult(0, 1, null), Trash.deleteForGood(app, listOf(recording)))
        assertTrue(FakeMediaStore.asked.isEmpty())
        FakeFolder.refuses = IllegalStateException("no")
        assertEquals(BulkDeleteResult(0, 1, null), Trash.deleteForGood(app, listOf(recording)))
    }

    @Test fun aPurgeTheFolderRefusesIsNotCounted() {
        FakeFolder.install(app)
        val expired = FakeFolder.file(Trash.nameFor(a, System.currentTimeMillis() - day))
        // Listing still works: it is the delete that is refused.
        assertEquals(1, Trash.list(app).size)
        FakeFolder.refuses = IllegalStateException("no")
        assertEquals(0, Trash.purgeExpired(app))
        assertTrue(expired.exists())
    }

    // ---- app storage --------------------------------------------------------

    private fun inAppStorage(name: String = a): Recording {
        appFile(app, name)
        return Recordings.list(app).single { it.name == name }
    }

    @Test fun aRestoreThatWillNotRenameIsAFailure() {
        val file = appFile(app, Trash.nameFor(a, System.currentTimeMillis() + day))
        val trashed = Trash.list(app).single()
        file.delete()
        assertTrue(Trash.restore(app, trashed) is DeleteResult.Failed)
    }

    @Test fun aFileThatWillNotGoIsAFailure() {
        val inTheWay = File(FakeStorage.appFolder(app), "$a/in-the-way").apply { mkdirs() }
        val recording = Recording(
            name = a, uri = android.net.Uri.fromFile(inTheWay), whenLabel = "", incoming = true,
            durationLabel = "", sizeLabel = "", filePath = inTheWay.parentFile!!.absolutePath,
        )
        assertEquals(BulkDeleteResult(0, 1, null), Trash.deleteForGood(app, listOf(recording)))
    }

    @Test fun aRestoreOntoANameTakenOverwritesNothing() {
        val here = appFile(app, a, bytes = 10)
        val trashed = appFile(app, Trash.nameFor(a, System.currentTimeMillis() + day), bytes = 20)
        assertEquals(DeleteResult.Deleted, Trash.restore(app, Trash.list(app).single()))
        assertEquals(10L, here.length())
        assertFalse(trashed.exists())
        // As Android's own providers do with a name that is taken.
        assertEquals(20L, File(FakeStorage.appFolder(app), "20261020_101010_in (1).ogg").length())
    }

    @Test fun aTrashedNameTakenOverwritesNothing() {
        val expires = System.currentTimeMillis() + day
        val here = appFile(app, Trash.nameFor(a, expires), bytes = 10)
        val file = appFile(app, a, bytes = 20)
        val location = RecordingLocation.of(FakeStorage.appUri(app, file), file.absolutePath)
        assertEquals(DeleteResult.Deleted, location.trash(app, a, expires))
        assertEquals(10L, here.length())
        assertEquals(setOf(10L, 20L), FakeStorage.appFolder(app).listFiles()!!.map { it.length() }.toSet())
    }

    // ---- two copies of a name ------------------------------------------------

    @Test fun restoreAllBringsBackEveryCopyAndLosesNone() {
        val now = System.currentTimeMillis()
        appFile(app, Trash.nameFor(a, now + 2 * day), bytes = 10)
        appFile(app, Trash.nameFor(a, now + 9 * day), bytes = 20)
        assertEquals(2, Trash.list(app).size)
        val back = Trash.restoreAll(app, Trash.list(app))
        assertEquals(BulkDeleteResult(2, 0, null), back)
        assertTrue(Trash.list(app).isEmpty())
        val folder = FakeStorage.appFolder(app)
        assertEquals(setOf(10L, 20L), folder.listFiles()!!.map { it.length() }.toSet())
    }

    @Test fun deleteAllForGoodLeavesNoCopyBehind() {
        FakeFolder.install(app)
        val soon = System.currentTimeMillis() + day
        FakeMediaStore.rows += FakeMediaStore.Row(5, Trash.nameFor(a, 1_795_000_000_000L), trashed = true, expiresSeconds = 1_795_000_000)
        FakeFolder.file(Trash.nameFor(a, soon))
        appFile(app, Trash.nameFor(a, soon))
        val all = Trash.list(app)
        assertEquals(3, all.size)
        assertEquals(BulkDeleteResult(3, 0, null), Trash.deleteForGood(app, all))
        assertTrue(Trash.list(app).isEmpty())
    }

    // ---- all three ----------------------------------------------------------

    @Test fun oneRecordingInEachPlaceGoesItsOwnWay() {
        val dir = FakeFolder.install(app)
        val media = row(1, a)
        val folder = inFolder(b)
        val appFile = inAppStorage(c)
        val moved = Trash.trashAll(app, listOf(media, folder, appFile))
        assertEquals(BulkDeleteResult(3, 0, null), moved)
        assertEquals(setOf(a, b, c), Trash.list(app).map { it.name }.toSet())
        assertTrue(Recordings.list(app).isEmpty())

        val back = Trash.restoreAll(app, Trash.list(app))
        assertEquals(BulkDeleteResult(3, 0, null), back)
        assertEquals(setOf(a, b, c), Recordings.list(app).map { it.name }.toSet())
        assertTrue(dir.resolve(b).exists())
        assertTrue(File(FakeStorage.appFolder(app), c).exists())

        Trash.trashAll(app, Recordings.list(app))
        assertEquals(BulkDeleteResult(3, 0, null), Trash.deleteForGood(app, Trash.list(app)))
        assertTrue(Trash.list(app).isEmpty())
        assertTrue(FakeMediaStore.rows.isEmpty())
        assertTrue(dir.listFiles()!!.isEmpty())
        assertTrue(FakeStorage.appFolder(app).listFiles()!!.isEmpty())
    }
}
