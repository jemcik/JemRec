package com.jemcik.jemrec.capture

import android.provider.MediaStore
import com.jemcik.jemrec.capture.FakeStorage.appFile
import com.jemcik.jemrec.capture.FakeStorage.appUri
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
import java.io.File

/**
 * The recordings list as it reads its three places today - MediaStore's
 * Recordings/JemRec, a folder the user picked, app storage - before the 1.0
 * restructuring reads them through one library: which rows, in what order,
 * which copy when a name is in two places, and what a row says when its file
 * cannot be measured.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class RecordingsListTest {

    private val app = RuntimeEnvironment.getApplication()

    @Before fun setUp() {
        FakeStorage.forgetFileProviderFolders()
    }

    @After fun noDebugList() {
        Recordings.override = null
    }

    private fun listed() = Recordings.list(app)

    @Test fun theNewestCallComesFirstThenTheNameDecides() {
        val now = System.currentTimeMillis()
        // File dates in the opposite order: the name's time is the call's.
        listOf(
            "aaa.ogg",
            "voice-note.ogg",
            "20261020_101010_in.ogg",
            "Priya Shah@+447700900123_20261021120000.ogg",
            "20261022_090000_in.ogg",
            "20261022_090000_out.ogg",
        ).forEachIndexed { i, name -> appFile(app, name, modified = now - (6 - i) * 60_000L) }
        assertEquals(
            listOf(
                // Same start: by name, descending.
                "20261022_090000_out.ogg",
                "20261022_090000_in.ogg",
                "Priya Shah@+447700900123_20261021120000.ogg",
                "20261020_101010_in.ogg",
                // No time in the name counts as 0: last, by name descending.
                "voice-note.ogg",
                "aaa.ogg",
            ),
            listed().map { it.name },
        )
    }

    @Test fun oneRowPerNameAndTheFirstPlaceWins() {
        FakeMediaStore.install()
        FakeFolder.install(app)
        val everywhere = "20261020_101010_in.ogg"
        val folderAndApp = "20261021_101010_out.ogg"
        val appOnly = "20261022_101010_out.ogg"
        FakeMediaStore.rows += FakeMediaStore.Row(id = 7, name = everywhere, durationMillis = 61_000)
        FakeFolder.file(everywhere)
        FakeFolder.file(folderAndApp)
        appFile(app, everywhere)
        appFile(app, folderAndApp)
        val appFile = appFile(app, appOnly)

        val rows = listed()
        assertEquals(listOf(appOnly, folderAndApp, everywhere), rows.map { it.name })
        val byName = rows.associateBy { it.name }
        // MediaStore, then the chosen folder, then app storage.
        assertEquals(FakeMediaStore.uriOf(7), byName.getValue(everywhere).uri)
        assertNull(byName.getValue(everywhere).filePath)
        assertEquals(FakeFolder.AUTHORITY, byName.getValue(folderAndApp).uri.authority)
        assertNull(byName.getValue(folderAndApp).filePath)
        // Only app storage's rows carry a path: a FileProvider uri cannot be deleted.
        assertEquals(appFile.absolutePath, byName.getValue(appOnly).filePath)
        assertEquals(appUri(app, appFile), byName.getValue(appOnly).uri)
    }

    @Test fun anEmptyFileOrATrashedOneInAppStorageIsLeftOut() {
        appFile(app, "20261020_101010_in.ogg", bytes = 0)
        appFile(app, Trash.nameFor("20261021_101010_in.ogg", System.currentTimeMillis() + 86_400_000L))
        File(FakeStorage.appFolder(app), "20261023_101010_in.ogg").mkdirs()
        appFile(app, "20261022_101010_in.ogg", bytes = 1)
        assertEquals(listOf("20261022_101010_in.ogg"), listed().map { it.name })
    }

    @Test fun anEmptyFileInTheChosenFolderIsListed() {
        FakeFolder.install(app)
        FakeFolder.file("20261020_101010_in.ogg", bytes = 0)
        // Pins today's behaviour, which looks like a bug: a chosen folder's
        // file is listed at 0 bytes, unlike app storage's - and a save into
        // it creates the document at the first packet but writes its bytes
        // only at the copy, so a save in progress or killed half way shows
        // as an empty row (to be fixed in its own commit).
        val row = listed().single()
        assertEquals("20261020_101010_in.ogg", row.name)
        assertEquals("0 B", row.sizeLabel)
    }

    @Test fun theChosenFolderListsItsOggFilesLessItsTrash() {
        FakeFolder.install(app)
        FakeFolder.file("20261020_101010_in.ogg")
        FakeFolder.file("20261021_101010_in.m4a")
        FakeFolder.file(Trash.nameFor("20261022_101010_in.ogg", System.currentTimeMillis() + 86_400_000L))
        assertEquals(listOf("20261020_101010_in.ogg"), listed().map { it.name })
    }

    @Test fun aLengthThatCannotBeReadIsZeroAndTheRecordingIsStillListed() {
        val broken = appFile(app, "20261020_101010_in.ogg")
        val fine = appFile(app, "20261021_101010_in.ogg")
        FakeStorage.unreadable(app, appUri(app, broken))
        FakeStorage.lasts(app, appUri(app, fine), 65_000)
        val byName = listed().associateBy { it.name }
        assertEquals(0L, byName.getValue(broken.name).durationMillis)
        assertEquals("--:--", byName.getValue(broken.name).durationLabel)
        assertEquals(65_000L, byName.getValue(fine.name).durationMillis)
        assertEquals("1:05", byName.getValue(fine.name).durationLabel)
    }

    @Test fun mediaStoreIsAskedForRecordingsJemRecAndSaysTheLengthItself() {
        FakeMediaStore.install()
        FakeMediaStore.rows += FakeMediaStore.Row(id = 1, name = "20261020_101010_in.ogg", durationMillis = 3_000, sizeBytes = 2048)
        FakeMediaStore.rows += FakeMediaStore.Row(id = 2, name = "20261021_101010_in.ogg", relativePath = "Music/")
        // DATE_MODIFIED is in seconds; a name with no time shows the file's.
        FakeMediaStore.rows += FakeMediaStore.Row(id = 3, name = "voice-note.ogg", modifiedSeconds = 1_795_000_000)
        FakeMediaStore.rows += FakeMediaStore.Row(id = 4, name = "20261019_101010_in.ogg", trashed = true)

        val byName = listed().associateBy { it.name }
        assertEquals(setOf("20261020_101010_in.ogg", "voice-note.ogg"), byName.keys)
        val query = FakeMediaStore.queries.single()
        assertEquals("${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", query.selection)
        assertEquals(listOf("%Recordings/JemRec%"), query.args)
        assertEquals(MediaStore.MATCH_EXCLUDE, query.matchTrashed)
        assertEquals(3_000L, byName.getValue("20261020_101010_in.ogg").durationMillis)
        assertEquals("0:03", byName.getValue("20261020_101010_in.ogg").durationLabel)
        assertEquals("2 KB", byName.getValue("20261020_101010_in.ogg").sizeLabel)
        assertEquals(Recordings.whenLabel(1_795_000_000_000L), byName.getValue("voice-note.ogg").whenLabel)
    }

    @Test fun keptAndMessageComeFromThePreferences() {
        appFile(app, "20261020_101010_in.ogg")
        appFile(app, "20261021_101010_in.ogg")
        appFile(app, "20261022_101010_in.ogg")
        KeepMarks.set(app, listOf("20261020_101010_in.ogg"), kept = true)
        MessageMarks.mark(app, "20261021_101010_in.ogg")
        val byName = listed().associateBy { it.name }
        assertTrue(byName.getValue("20261020_101010_in.ogg").kept)
        assertFalse(byName.getValue("20261020_101010_in.ogg").message)
        assertTrue(byName.getValue("20261021_101010_in.ogg").message)
        assertFalse(byName.getValue("20261021_101010_in.ogg").kept)
        assertFalse(byName.getValue("20261022_101010_in.ogg").let { it.kept || it.message })
    }

    @Test fun whatARowSays() {
        val uri = FakeMediaStore.uriOf(1)
        val now = System.currentTimeMillis()
        val ours = Recordings.describe("20261020_165705_out.ogg", uri, 1_572_864, 3_723_000, whenMillis = now)
        // The name's time, not the file's.
        assertEquals("20 Oct, 16:57", ours.whenLabel)
        assertEquals("62:03", ours.durationLabel)
        assertEquals("1.5 MB", ours.sizeLabel)
        assertFalse(ours.incoming)
        val foreign = Recordings.describe("voice_in.ogg", uri, 1023, -5, whenMillis = now)
        // A name that is not ours: the file's time, and "_in." still read as incoming.
        assertEquals(Recordings.whenLabel(now), foreign.whenLabel)
        assertTrue(foreign.whenLabel.startsWith("Today "))
        assertEquals("--:--", foreign.durationLabel)
        assertEquals(0L, foreign.durationMillis)
        assertEquals("1023 B", foreign.sizeLabel)
        assertTrue(foreign.incoming)
        // Honor's names do not say which way: outgoing until something says.
        assertFalse(Recordings.describe("Priya Shah@+447700900123_20261020223141.ogg", uri, 1024, 0).incoming)
        assertEquals("1 KB", Recordings.describe("x.ogg", uri, 1024, 0).sizeLabel)
    }

    @Test fun theDebugBuildsListWinsOverTheFiles() {
        appFile(app, "20261020_101010_in.ogg")
        val invented = Recordings.describe("20261021_101010_out.ogg", FakeMediaStore.uriOf(1), 10, 10)
        Recordings.override = { listOf(invented) }
        assertEquals(listOf(invented), listed())
    }
}
