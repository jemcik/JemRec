package com.jemcik.jemrec.ui.screens

import android.net.Uri
import android.os.Looper
import com.jemcik.jemrec.capture.Recording
import com.jemcik.jemrec.capture.Recordings
import com.jemcik.jemrec.capture.Waveforms
import com.jemcik.jemrec.ui.MainViewModel
import com.jemcik.jemrec.ui.Screen
import com.jemcik.jemrec.ui.UiState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * What an action does when the app is busy - each opening's setup check
 * holds busy for seconds - pinned before the busy policy moves into one
 * table (the 1.0 restructuring). Some actions are dropped, their
 * buttons greyed meanwhile; an answer already confirmed in a dialog waits
 * its turn instead.
 *
 * Busy is held here by a real action: Settings' auto-delete, whose closing
 * refresh is stopped in its reading of the list (Recordings.override) for
 * as long as a test needs. The recordings are files in app storage, as
 * recordings from before MediaStore are, so a delete or a restore is a
 * rename the test can see.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36], qualifiers = ScreenTest.PHONE)
class BusyPolicyTest : ScreenTest() {

    private val app get() = RuntimeEnvironment.getApplication()

    private val dir get() = File(app.filesDir, "calls").apply { mkdirs() }

    // The fill-in after each refresh would decode the files: invented
    // levels instead, as the screenshots have.
    @Before fun inventedWaveforms() {
        Waveforms.override = { name -> Fixtures.waveform(name.hashCode().toLong()) }
    }

    @After fun noStandIns() {
        Recordings.override = null
        Waveforms.override = null
    }

    private fun file(name: String) = File(dir, name).apply { writeBytes(ByteArray(64)) }

    private fun recording(file: File, name: String = file.name) = Recording(
        name = name,
        uri = Uri.fromFile(file),
        whenLabel = "",
        incoming = name.contains("_in"),
        durationLabel = "1:00",
        sizeLabel = "1 KB",
        filePath = file.path,
    )

    /** Busy until [release]: auto-delete's refresh held reading the list,
     *  which then reads as [list]. */
    private inner class HeldBusy(list: List<Recording>) {
        private val reading = CountDownLatch(1)
        private val gate = CountDownLatch(1)

        init {
            Recordings.override = {
                reading.countDown()
                gate.await(30, TimeUnit.SECONDS)
                list
            }
        }

        fun hold(vm: MainViewModel) {
            vm.setAutoDelete()
            awaitMain("auto-delete's refresh to be reading") { reading.count == 0L }
            assertTrue("busy while auto-delete runs", vm.state.value.busy)
        }

        fun release() = gate.countDown()
    }

    private fun log(vm: MainViewModel) = vm.state.value.log

    /** While busy, the self-test and a restore are dropped - not run once it
     *  clears - and once free, each runs. */
    @Test fun whileBusySelfTestAndRestoreAreDropped() {
        val name = "20261021_160200_in.ogg"
        val inTrash = file(".trashed-1795000000-$name")
        val trashed = recording(inTrash, name)
        val vm = viewModel(UiState(screen = Screen.TRASH, trashed = listOf(trashed)))
        val busy = HeldBusy(emptyList())
        busy.hold(vm)

        vm.selfTest()
        vm.restore(trashed)
        busy.release()
        settle(vm)

        assertFalse(vm.state.value.busy)
        assertEquals("no self-test ran", 0L, vm.state.value.selfTestShown)
        assertFalse(log(vm), log(vm).contains("not answering"))
        assertTrue("still in the trash", inTrash.exists())
        assertFalse(File(dir, name).exists())
        assertFalse(log(vm), log(vm).contains("restore"))

        // Not busy: the same two taps run.
        vm.selfTest()
        settle(vm)
        assertTrue(vm.state.value.selfTestShown > 0L)
        assertTrue(log(vm), log(vm).contains("FAIL - the recorder is not answering"))
        vm.restore(trashed)
        settle(vm)
        assertFalse(inTrash.exists())
        assertTrue(File(dir, name).exists())
        assertTrue(log(vm), log(vm).contains("restored $name"))
    }

    /**
     * Delete all for good, said yes to while busy: the dialog closes at
     * once, and the deleting waits until the app is free - then deletes what
     * the question counted, read as it was answered. The refresh that held
     * busy emptied Recently deleted meanwhile (the stand-in list has no
     * trash), and the recording counted still went.
     */
    @Test fun emptyTrashSaidYesToWhileBusyRunsOnceFreeWithWhatItCounted() {
        val inTrash = file(".trashed-1795000000-20261021_160200_in.ogg")
        val trashed = recording(inTrash, "20261021_160200_in.ogg")
        val vm = viewModel(UiState(screen = Screen.TRASH, trashed = listOf(trashed), confirmingEmptyTrash = true))
        val busy = HeldBusy(emptyList())
        busy.hold(vm)

        vm.confirmEmptyTrash()
        assertFalse("the dialog closes at once", vm.state.value.confirmingEmptyTrash)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("waits while busy", inTrash.exists())

        busy.release()
        settle(vm)
        assertFalse("deleted once free", inTrash.exists())
        assertTrue(log(vm), log(vm).contains("deleted for good 1"))
        assertEquals(emptyList<Recording>(), vm.state.value.trashed)
    }

    /**
     * Delete, said yes to in the selection's dialog while busy: it waits
     * until the app is free, as empty-trash does - but reads the selection
     * when it runs, not as it was answered. A row picked during the wait
     * goes to Recently deleted too, though the dialog counted one.
     */
    @Test fun deleteSelectedSaidYesToWhileBusyTakesWhatIsSelectedWhenItRuns() {
        val olivia = recording(file("20261021_160200_in.ogg"))
        val marcus = recording(file("20261021_152400_out.ogg"))
        val vm = viewModel(UiState(recordings = listOf(olivia, marcus), selecting = true, selected = setOf(olivia.name)))
        val busy = HeldBusy(listOf(olivia, marcus))
        busy.hold(vm)

        vm.askDeleteSelected()
        assertEquals("the dialog counts one", 1, vm.state.value.selected.size)
        vm.confirmDeleteSelected()
        assertFalse("the dialog closes at once", vm.state.value.confirmingDeleteSelected)
        // While it waits its turn, another row is picked.
        vm.toggleSelected(marcus)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("waits while busy", File(olivia.filePath!!).exists())

        busy.release()
        settle(vm)
        // Pins today's behaviour, which looks like a bug: confirmDeleteSelected
        // reads the selection inside launchActionWhenFree, after the wait for
        // busy, where confirmEmptyTrash reads what its question counted before
        // it, so the row picked after the dialog went too (to be fixed in its
        // own commit)
        assertFalse(File(olivia.filePath!!).exists())
        assertFalse(File(marcus.filePath!!).exists())
        assertEquals(2, dir.list()!!.count { it.startsWith(".trashed-") })
        assertTrue(log(vm), log(vm).contains("moved 2 to Recently deleted"))
        assertFalse(vm.state.value.selecting)
        assertEquals(emptySet<String>(), vm.state.value.selected)
    }
}
