package com.jemcik.jemrec.recordings

import com.jemcik.jemrec.recordings.FakeStorage.appFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File
import java.util.Date

/**
 * The recordings and Recently deleted read together: the same rows as the two
 * lists read apart, and a chosen folder listed once for both.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class RecordingLibraryTest {

    private val app = RuntimeEnvironment.getApplication()
    private val day = 24L * 60 * 60 * 1000

    @Before fun setUp() {
        FakeStorage.forgetFileProviderFolders()
        FakeMediaStore.install(File(app.cacheDir, "media"))
    }

    @After fun noDebugList() {
        Recordings.override = null
    }

    private fun onePerPlace() {
        val soon = System.currentTimeMillis() + day
        FakeFolder.install(app)
        FakeMediaStore.rows += FakeMediaStore.Row(1, "20261020_101010_in.ogg")
        FakeMediaStore.rows += FakeMediaStore.Row(
            2, ".trashed-1795000000-20261021_101010_in.ogg", trashed = true, expiresSeconds = 1_795_000_000,
        )
        FakeFolder.file("20261022_101010_in.ogg")
        FakeFolder.file(Trash.nameFor("20261023_101010_in.ogg", soon))
        appFile(app, "20261024_101010_in.ogg")
        appFile(app, Trash.nameFor("20261025_101010_in.ogg", soon))
    }

    @Test fun itReadsWhatTheTwoListsReadApart() {
        onePerPlace()
        val apart = Recordings.list(app) to Trash.list(app)
        val library = RecordingLibrary.read(app)
        assertEquals(3, library.recordings.size)
        assertEquals(3, library.trashed.size)
        assertEquals(apart.first, library.recordings)
        assertEquals(apart.second, library.trashed)
    }

    @Test fun aChosenFolderIsListedOnceForBoth() {
        onePerPlace()
        RecordingLibrary.read(app)
        assertEquals(1, FakeFolder.listed)
    }

    @Test fun aPassOfAutoDeleteListsAChosenFolderOnce() {
        FakeFolder.install(app)
        val old = RecordingStore.nameFor(Date(System.currentTimeMillis() - 40 * day), incoming = true)
        FakeFolder.file(old)
        val expired = FakeFolder.file(Trash.nameFor("20261001_101010_in.ogg", System.currentTimeMillis() - day))
        Retention.setMaxAgeDays(app, 30)
        val result = Retention.run(app)
        assertEquals(1, result.deleted)
        assertEquals(1, FakeFolder.listed)
        assertTrue(!expired.exists())
    }

    @Test fun theDebugBuildsInventedListHasNoTrashAndReadsNoPlace() {
        onePerPlace()
        val invented = Recordings.describe("20261021_101010_out.ogg", FakeMediaStore.uriOf(1), 10, 10)
        Recordings.override = { listOf(invented) }
        val library = RecordingLibrary.read(app)
        assertEquals(listOf(invented), library.recordings)
        assertTrue(library.trashed.isEmpty())
        assertEquals(0, FakeFolder.listed)
    }
}
