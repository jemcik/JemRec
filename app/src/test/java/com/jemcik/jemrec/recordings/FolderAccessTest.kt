package com.jemcik.jemrec.recordings

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import com.jemcik.jemrec.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File

/** The folder chosen for recordings lost - its access let go, or the
 *  folder gone - and said, not found out. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class FolderAccessTest {

    private val app: Context = RuntimeEnvironment.getApplication()
    private val notifications = app.getSystemService(NotificationManager::class.java)
    private lateinit var dir: File

    @Before fun setUp() {
        Prefs.of(app).edit().clear().commit()
        FakeStorage.forgetFileProviderFolders()
        FakeMediaStore.install(File(app.cacheDir, "media"))
        dir = FakeFolder.install(app)
    }

    private fun lettingGo() = app.contentResolver.releasePersistableUriPermission(
        RecordingStore.treeUri(app)!!,
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
    )

    private fun told() = shadowOf(notifications).allNotifications.count { it.extras.getString("android.title") == "Recording saved elsewhere" }

    @Test fun aFolderStillGrantedAndThereIsReached() {
        assertEquals(true, RecordingStore.folderReachable(app))
        assertFalse(FolderAccess.lost(app))
    }

    @Test fun noFolderChosenIsNothingLost() {
        RecordingStore.clearTree(app)
        assertEquals(null, RecordingStore.folderReachable(app))
        assertFalse(FolderAccess.lost(app))
    }

    @Test fun itsAccessTakenBackIsLost() {
        lettingGo()
        assertTrue(FolderAccess.lost(app))
    }

    @Test fun aSaveSentElsewhereIsSaidOnce() {
        lettingGo()
        val first = RecordingStore.open(app, "20261020_170000_in_+447700900123.ogg")!!
        // Not in the folder: in the standard one.
        assertNotEquals(FakeFolder.AUTHORITY, first.uri.authority)
        first.abandon()
        assertEquals(1, told())
        RecordingStore.open(app, "20261020_180000_in_+447700900123.ogg")!!.abandon()
        assertEquals(1, told())
    }

    @Test fun theFolderChosenAgainTakesWhatWasSaidAway() {
        lettingGo()
        RecordingStore.open(app, "20261020_170000_in_+447700900123.ogg")!!.abandon()
        assertEquals(1, told())
        assertTrue(RecordingStore.setTree(app, FakeFolder.tree))
        assertFalse(FolderAccess.lost(app))
        assertEquals(0, told())
        // And lost again later: said again.
        lettingGo()
        RecordingStore.open(app, "20261020_190000_in_+447700900123.ogg")!!.abandon()
        assertEquals(1, told())
    }

    /** Back without JemRec opened - a drive put back: the next save lands
     *  in the folder and takes what was said away, so its next loss is said
     *  again. Only an open of the app took it back, and a drive out, back and
     *  out again was said once (review, 2026-11-18). */
    @Test fun aSaveInTheFolderAgainTakesWhatWasSaidAway() {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        lettingGo()
        RecordingStore.open(app, "20261020_170000_in_+447700900123.ogg")!!.abandon()
        assertEquals(1, told())
        app.contentResolver.takePersistableUriPermission(FakeFolder.tree, flags)
        val back = RecordingStore.open(app, "20261020_180000_in_+447700900123.ogg")!!
        assertEquals(FakeFolder.AUTHORITY, back.uri.authority)
        back.abandon()
        assertEquals(0, told())
        lettingGo()
        RecordingStore.open(app, "20261020_190000_in_+447700900123.ogg")!!.abandon()
        assertEquals(1, told())
    }

    /** A drive taken out keeps its grant: only the folder's query fails.
     *  Lost, back and lost again, said each time - a check of the grant
     *  alone passed every test before this one (review, 2026-11-18). */
    @Test fun aDriveTakenOutAndBackIsSaidEachTime() {
        val away = File(dir.parentFile, "away")
        dir.renameTo(away)
        assertTrue(FolderAccess.lost(app))
        RecordingStore.open(app, "20261020_170000_in_+447700900123.ogg")!!.abandon()
        assertEquals(1, told())
        away.renameTo(dir)
        assertFalse(FolderAccess.lost(app))
        RecordingStore.open(app, "20261020_180000_in_+447700900123.ogg")!!.abandon()
        assertEquals(0, told())
        dir.renameTo(away)
        RecordingStore.open(app, "20261020_190000_in_+447700900123.ogg")!!.abandon()
        assertEquals(1, told())
        away.renameTo(dir)
    }

    @Test fun anotherFolderChosenLetsTheFirstGo() {
        val first = RecordingStore.treeUri(app)!!
        RecordingStore.clearTree(app)
        assertFalse(app.contentResolver.persistedUriPermissions.any { it.uri == first })
    }
}
