package com.jemcik.jemrec.capture

import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.jemcik.jemrec.capture.FakeStorage.appFile
import org.junit.After
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
import org.robolectric.util.ReflectionHelpers
import java.io.File

/**
 * Recently deleted as it works today, in the places JemRec trashes by
 * renaming - app storage and a folder the user picked - and what it reads of
 * MediaStore's own trash: what the list shows, a restore, and the purge of
 * what has waited its 30 days. Pinned before the 1.0 restructuring gives
 * each place a backend of its own.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class RecentlyDeletedTest {

    private val app = RuntimeEnvironment.getApplication()
    private val day = 24L * 60 * 60 * 1000
    private val sdk = Build.VERSION.SDK_INT

    @Before fun setUp() {
        FakeStorage.forgetFileProviderFolders()
    }

    @After fun backToThisSdk() {
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", sdk)
    }

    /** One recording in app storage, as the list has it. */
    private fun inAppStorage(name: String): Recording {
        appFile(app, name)
        return Recordings.list(app).single { it.name == name }
    }

    @Test fun aTrashedRecordingIsListedUnderItsOwnNameWithItsExpiry() {
        val name = "20261020_101010_in.ogg"
        val recording = inAppStorage(name)
        val before = System.currentTimeMillis()
        assertEquals(DeleteResult.Deleted, Trash.trash(app, recording.uri, recording.filePath, name))
        val after = System.currentTimeMillis()

        val trashed = Trash.list(app).single()
        assertEquals(name, trashed.name)
        // 30 days from the trash, to the second the name holds.
        val expires = trashed.expiresAtMillis!!
        assertEquals(0L, expires % 1000)
        assertTrue(expires in (before + 30 * day) / 1000 * 1000..after + 30 * day)
        // The file itself, renamed - which is the path a restore needs.
        val file = File(trashed.filePath!!)
        assertEquals(Trash.nameFor(name, expires), file.name)
        assertTrue(file.exists())
        assertFalse(File(FakeStorage.appFolder(app), name).exists())
        assertTrue(Recordings.list(app).isEmpty())
    }

    @Test fun aRestoreBringsItBackUnderItsName() {
        val name = "20261020_101010_in.ogg"
        val recording = inAppStorage(name)
        Trash.trash(app, recording.uri, recording.filePath, name)
        assertEquals(DeleteResult.Deleted, Trash.restore(app, Trash.list(app).single()))
        assertEquals(listOf(name), Recordings.list(app).map { it.name })
        assertTrue(Trash.list(app).isEmpty())
    }

    @Test fun theListIsSoonestToGoFirst() {
        val now = System.currentTimeMillis()
        appFile(app, Trash.nameFor("20261020_101010_in.ogg", now + 20 * day))
        appFile(app, Trash.nameFor("20261021_101010_in.ogg", now + 2 * day))
        appFile(app, Trash.nameFor("20261022_101010_in.ogg", now + 9 * day))
        assertEquals(
            listOf("20261021_101010_in.ogg", "20261022_101010_in.ogg", "20261020_101010_in.ogg"),
            Trash.list(app).map { it.name },
        )
    }

    @Test fun anHonorNameComesBackWithItsAtAndItsCaller() {
        val name = "Priya Shah@+447700900123_20261020223141.ogg"
        appFile(app, Trash.nameFor(name, System.currentTimeMillis() + day))
        val trashed = Trash.list(app).single()
        assertEquals(name, trashed.name)
        // Callers are added as the list adds them: here, from the name.
        assertEquals("Priya Shah", trashed.caller)
        assertEquals("+447700900123", trashed.number)
    }

    @Test fun mediaStoresTrashIsAskedForOnlyTrashedRowsAndReadBack() {
        FakeMediaStore.install()
        val name = "20261020_101010_in.ogg"
        // Android renames the file while it is trashed, and DISPLAY_NAME follows.
        FakeMediaStore.rows += FakeMediaStore.Row(
            id = 5, name = ".trashed-1795000000-$name",
            trashed = true, expiresSeconds = 1_795_000_000, durationMillis = 4_000,
        )
        FakeMediaStore.rows += FakeMediaStore.Row(id = 6, name = "20261021_101010_in.ogg")

        val trashed = Trash.list(app).single()
        assertEquals(name, trashed.name)
        assertEquals(1_795_000_000_000L, trashed.expiresAtMillis)
        assertEquals(FakeMediaStore.uriOf(5), trashed.uri)
        assertEquals(4_000L, trashed.durationMillis)
        val query = FakeMediaStore.queries.single()
        assertEquals(MediaStore.MATCH_ONLY, query.matchTrashed)
        assertEquals("${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", query.selection)
        assertEquals(listOf("%Recordings/JemRec%"), query.args)
    }

    @Test fun twoTrashedCopiesOfOneNameShowAsOneTheNewerFile() {
        val now = System.currentTimeMillis()
        val name = "20261020_101010_in.ogg"
        appFile(app, Trash.nameFor(name, now + 2 * day), modified = now - 60_000)
        val newer = appFile(app, Trash.nameFor(name, now + 9 * day), modified = now)
        // App storage is read newest file first, and the first of a name stays.
        assertEquals(listOf(newer.absolutePath), Trash.list(app).map { it.filePath })
    }

    @Test fun oneRowPerNameAndMediaStoreWins() {
        FakeMediaStore.install()
        val name = "20261020_101010_in.ogg"
        FakeMediaStore.rows += FakeMediaStore.Row(
            id = 5, name = Trash.nameFor(name, 1_795_000_000_000L), trashed = true, expiresSeconds = 1_795_000_000,
        )
        appFile(app, Trash.nameFor(name, System.currentTimeMillis() + day))
        val trashed = Trash.list(app).single()
        assertEquals(FakeMediaStore.uriOf(5), trashed.uri)
        assertNull(trashed.filePath)
    }

    @Test fun aChosenFoldersRecordingIsTrashedByRenameAndRestoredTheSameWay() {
        val dir = FakeFolder.install(app)
        val name = "20261020_101010_in.ogg"
        FakeFolder.file(name)
        val recording = Recordings.list(app).single()
        assertEquals(DeleteResult.Deleted, Trash.trash(app, recording.uri, recording.filePath, name))
        val onDisk = dir.listFiles()!!.single().name
        val trashed = Trash.list(app).single()
        assertEquals(name, trashed.name)
        assertEquals(Trash.nameFor(name, trashed.expiresAtMillis!!), onDisk)
        assertTrue(Recordings.list(app).isEmpty())

        assertEquals(DeleteResult.Deleted, Trash.restore(app, trashed))
        assertEquals(listOf(name), dir.listFiles()!!.map { it.name })
        assertEquals(listOf(name), Recordings.list(app).map { it.name })
    }

    /** Android 17's folder provider, as on the emulator and the Honor
     *  (2026-10-26, issue #51): a trashed name reported stripped and marked
     *  restorable, the name as given at the end of its ID - or, with
     *  [opaqueIds], an ID that does not say either, which leaves the expiry
     *  unknown. Robolectric here has no Android 17 to run on. */
    private fun asAndroid17(opaqueIds: Boolean = false) {
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 37)
        FakeFolder.shows = { file ->
            // The prefix stripped, a %40 left as it is.
            val stripped = file.name.replace(Regex("""^\.trashed-\d+-"""), "")
            FakeFolder.Shown(
                name = stripped,
                id = if (opaqueIds) "doc-${file.name.hashCode()}" else "primary:Calls/${file.name}",
                flags = if (stripped != file.name) DocumentsContract.Document.FLAG_SUPPORTS_RESTORE else 0,
            )
        }
    }

    private fun asAndroid17WithOpaqueIds() = asAndroid17(opaqueIds = true)

    @Test fun onAndroid17AStrippedNameIsStillTrashedByItsId() {
        FakeFolder.install(app)
        asAndroid17()
        val name = "Priya Shah@+447700900123_20261020223141.ogg"
        FakeFolder.file(Trash.nameFor(name, 1_795_000_000_000L))
        FakeFolder.file("20261021_101010_in.ogg")
        assertEquals(listOf("20261021_101010_in.ogg"), Recordings.list(app).map { it.name })
        val trashed = Trash.list(app).single()
        assertEquals(name, trashed.name)
        assertEquals(1_795_000_000_000L, trashed.expiresAtMillis)
    }

    @Test fun beforeAndroid17TheRestoreFlagMeansNothing() {
        FakeFolder.install(app)
        FakeFolder.shows = { FakeFolder.Shown(it.name, "doc-1", DocumentsContract.Document.FLAG_SUPPORTS_RESTORE) }
        FakeFolder.file("20261021_101010_in.ogg")
        assertEquals(listOf("20261021_101010_in.ogg"), Recordings.list(app).map { it.name })
        assertTrue(Trash.list(app).isEmpty())
    }

    @Test fun whatDoesNotSayWhenItGoesIsListedLast() {
        FakeFolder.install(app)
        asAndroid17WithOpaqueIds()
        val now = System.currentTimeMillis()
        FakeFolder.file(Trash.nameFor("20261020_101010_in.ogg", now + 2 * day))
        appFile(app, Trash.nameFor("20261021_101010_in.ogg", now + 20 * day))
        appFile(app, Trash.nameFor("20261022_101010_in.ogg", now + 9 * day))
        val trashed = Trash.list(app)
        assertEquals(
            listOf("20261022_101010_in.ogg", "20261021_101010_in.ogg", "20261020_101010_in.ogg"),
            trashed.map { it.name },
        )
        assertNull(trashed.last().expiresAtMillis)
        // And it is still out of the list.
        assertTrue(Recordings.list(app).isEmpty())
    }

    @Test fun thePurgeRemovesWhatHasExpiredAndKeepsTheRest() {
        val dir = FakeFolder.install(app)
        val now = System.currentTimeMillis()
        val folderExpired = FakeFolder.file(Trash.nameFor("20261020_101010_in.ogg", now - day))
        val folderWaiting = FakeFolder.file(Trash.nameFor("20261021_101010_in.ogg", now + day))
        val folderRecording = FakeFolder.file("20261022_101010_in.ogg")
        val appExpired = appFile(app, Trash.nameFor("20261023_101010_in.ogg", now - day))
        // Expiring this very second counts as expired.
        val appDueNow = appFile(app, Trash.nameFor("20261024_101010_in.ogg", now / 1000 * 1000))
        val appWaiting = appFile(app, Trash.nameFor("20261025_101010_in.ogg", now + day))
        val appRecording = appFile(app, "20261026_101010_in.ogg")

        assertEquals(3, Trash.purgeExpired(app, now))
        assertFalse(folderExpired.exists())
        assertFalse(appExpired.exists())
        assertFalse(appDueNow.exists())
        listOf(folderWaiting, folderRecording, appWaiting, appRecording).forEach { assertTrue(it.name, it.exists()) }
        assertEquals(2, dir.listFiles()!!.size)
    }

    @Test fun thePurgeKeepsWhatDoesNotSayWhenItGoes() {
        FakeFolder.install(app)
        asAndroid17WithOpaqueIds()
        val unknown = FakeFolder.file(Trash.nameFor("20261020_101010_in.ogg", System.currentTimeMillis() - day))
        // Its own name says it expired yesterday, but the provider does not
        // pass that on: it waits for the user.
        assertEquals(0, Trash.purgeExpired(app, System.currentTimeMillis()))
        assertTrue(unknown.exists())
        assertEquals(listOf(null), Trash.list(app).map { it.expiresAtMillis })
    }

    @Test fun thePurgeLeavesMediaStoreToAndroid() {
        FakeMediaStore.install()
        FakeMediaStore.rows += FakeMediaStore.Row(
            id = 5, name = Trash.nameFor("20261020_101010_in.ogg", 1L), trashed = true, expiresSeconds = 0,
        )
        assertEquals(0, Trash.purgeExpired(app, System.currentTimeMillis()))
        assertTrue(FakeMediaStore.queries.isEmpty())
    }
}
