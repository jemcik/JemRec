package com.jemcik.jemrec.ui.screens

import android.net.Uri
import android.os.Looper
import androidx.core.content.edit
import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.calls.CallRules
import com.jemcik.jemrec.calls.VoipCallNotes
import com.jemcik.jemrec.capture.HeldWork
import com.jemcik.jemrec.recordings.Recording
import com.jemcik.jemrec.recordings.Recordings
import com.jemcik.jemrec.recordings.Retention
import com.jemcik.jemrec.recordings.Waveforms
import com.jemcik.jemrec.setup.Setup
import com.jemcik.jemrec.setup.SetupStep
import com.jemcik.jemrec.ui.MainViewModel
import com.jemcik.jemrec.ui.Screen
import com.jemcik.jemrec.ui.UiState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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

    // A setup check arms the recorder's work.
    private lateinit var work: HeldWork

    @Before fun aWorkManager() {
        work = HeldWork(app)
    }

    @After fun noWorkManager() = work.close()

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
     * until the app is free, as empty-trash does, and deletes what the
     * dialog counted, read as it was answered. A row picked during the wait
     * stays where it was; the selection ends with the delete.
     */
    @Test fun deleteSelectedSaidYesToWhileBusyTakesWhatTheDialogCounted() {
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
        assertFalse(File(olivia.filePath!!).exists())
        assertTrue("picked after the question, not deleted", File(marcus.filePath!!).exists())
        assertEquals(1, dir.list()!!.count { it.startsWith(".trashed-") })
        assertTrue(log(vm), log(vm).contains("moved 1 to Recently deleted"))
        assertFalse(vm.state.value.selecting)
        assertEquals(emptySet<String>(), vm.state.value.selected)
    }

    /**
     * The rest of what a busy screen drops: each action whose control is
     * greyed while busy, tapped while auto-delete runs. None runs when the
     * screen is free again - and once free, each does (the test after).
     */
    @Test fun whileBusyEveryGreyedActionIsDropped() {
        val inTrash = file(".trashed-1795000000-20261021_160200_in.ogg")
        val trashed = recording(inTrash, "20261021_160200_in.ogg")
        VoipCallNotes.setEnabled(app, true)
        val vm = viewModel(UiState(step = SetupStep.READY, screen = Screen.SETTINGS, trashed = listOf(trashed)))
        val busy = HeldBusy(emptyList())
        busy.hold(vm)

        vm.refreshSetup()
        vm.confirmDeveloperOptions()
        vm.confirmBackgroundChoice()
        vm.startPairingNotification()
        vm.retryFinish()
        vm.bootstrapDaemon()
        vm.restoreAll()
        vm.setAutoDelete(afterDays = 30)
        vm.setVoipNames(false)
        vm.useDefaultFolder()
        busy.release()
        settle(vm)

        val s = vm.state.value
        assertEquals("no setup check", SetupStep.READY, s.step)
        assertNull("no finish", s.finishFailed)
        assertFalse(Prefs.of(app).getBoolean("setup_dev_options_confirmed", false))
        assertFalse(Setup.backgroundChoiceMade(app))
        assertTrue("still in the trash", inTrash.exists())
        assertEquals(0, Retention.maxAgeDays(app))
        assertTrue(VoipCallNotes.enabled(app))
        for (line in listOf("check your notifications", "finish setup", "recorder running", "could not start the recorder", "restored", "standard Recordings folder")) {
            assertFalse(line, log(vm).contains(line))
        }
        assertFalse(s.busy)
    }

    /** ...and the same actions, the screen free, each run: the checks
     *  above look at what each would have changed. */
    @Test fun freeEveryGreyedActionRuns() {
        val inTrash = file(".trashed-1795000000-20261021_160200_in.ogg")
        val trashed = recording(inTrash, "20261021_160200_in.ogg")
        Recordings.override = { emptyList() }
        val vm = viewModel(UiState(step = SetupStep.READY, screen = Screen.SETTINGS, trashed = listOf(trashed)))
        fun ran(action: () -> Unit) {
            action()
            settle(vm)
        }

        // First: the next reading has no trash (the stand-in list has none).
        ran { vm.restoreAll() }
        assertFalse(inTrash.exists())
        assertTrue(log(vm), log(vm).contains("restored 1"))
        ran { vm.confirmDeveloperOptions() }
        assertTrue(Prefs.of(app).getBoolean("setup_dev_options_confirmed", false))
        assertNotEquals(log(vm), SetupStep.READY, vm.state.value.step)
        ran { vm.confirmBackgroundChoice() }
        assertTrue(Setup.backgroundChoiceMade(app))
        ran { vm.startPairingNotification() }
        assertTrue(log(vm), log(vm).contains("check your notifications - you can type the code there"))
        ran { vm.setAutoDelete(afterDays = 30) }
        assertEquals(30, Retention.maxAgeDays(app))
        VoipCallNotes.setEnabled(app, true)
        ran { vm.setVoipNames(false) }
        assertFalse(VoipCallNotes.enabled(app))
        ran { vm.useDefaultFolder() }
        assertTrue(log(vm), log(vm).contains("recordings will go to the standard Recordings folder"))
    }

    /**
     * Answers already given wait their turn, as Delete all for good's does:
     * Start fresh's yes, and the debugging question's. Neither runs while
     * busy; both run once free.
     */
    @Test fun whileBusyAnAnswerAlreadyGivenWaitsItsTurn() {
        val vm = viewModel(UiState(step = SetupStep.NEEDS_DEBUGGING_CHOICE, confirmingReset = true))
        val busy = HeldBusy(emptyList())
        busy.hold(vm)

        vm.setHideDebugNotification(true)
        vm.confirmReset()
        assertFalse("the dialog closes at once", vm.state.value.confirmingReset)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(vm.state.value.debugNotificationHidden)
        assertFalse(vm.state.value.resetDone)

        busy.release()
        settle(vm)
        assertTrue(vm.state.value.debugNotificationHidden)
        assertTrue(vm.state.value.resetDone)
    }

    /**
     * What a picker brings back, and what the recorder is told, does not
     * wait for busy: coming back from the picker resumes the screen, whose
     * check holds busy then (measured on an emulator: a contact picked and
     * nothing added). Each is done while auto-delete still runs.
     */
    @Test fun whileBusyAPickersAnswerIsTakenAtOnce() {
        CallRules.setContacts(app, CallRules.Contacts.ONLY)
        Prefs.of(app).edit { putStringSet("rules_chosen", setOf("lookup-a", "lookup-b")) }
        val vm = viewModel(UiState(step = SetupStep.READY, screen = Screen.SETTINGS))
        val busy = HeldBusy(emptyList())
        busy.hold(vm)

        vm.addRuleContact(Uri.parse("content://com.android.contacts/contacts/404"))
        vm.removeRuleContact("lookup-a")
        vm.onFolderChosen(Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ACalls"))
        awaitMain("the three to be done") {
            log(vm).contains("could not add that contact") &&
                log(vm).contains("recordings will be saved to the folder you chose") &&
                Prefs.of(app).getStringSet("rules_chosen", null) == setOf("lookup-b")
        }
        assertTrue("still busy", vm.state.value.busy)
        busy.release()
        settle(vm)
    }

    /**
     * A tap that waits for the screen to be free, then goes on without
     * holding busy: setup's pairing again (a deep link's, or the header's).
     * Home with the pairing step's flag at once; the setup check once free.
     */
    @Test fun pairingAgainWaitsForTheScreenThenChecksSetup() {
        val vm = viewModel(UiState(step = SetupStep.READY, screen = Screen.SETTINGS))
        val busy = HeldBusy(emptyList())
        busy.hold(vm)

        vm.pairAgain()
        assertEquals(Screen.HOME, vm.state.value.screen)
        assertTrue(vm.state.value.pairingLost)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("no setup check while busy", SetupStep.READY, vm.state.value.step)

        busy.release()
        settle(vm)
        assertNotEquals(log(vm), SetupStep.READY, vm.state.value.step)
    }

    /**
     * An action that fails says which and how, in the log, and leaves the
     * screen free; the connection's flag is read again after every action,
     * failed or not.
     */
    @Test fun aFailedActionSaysWhatFailedAndTheConnectionIsReadAgain() {
        Recordings.override = { throw IllegalStateException("boom") }
        val vm = viewModel(UiState(step = SetupStep.READY, screen = Screen.SETTINGS, connected = true))
        vm.setAutoDelete(afterDays = 30)
        settle(vm)
        assertTrue(log(vm), log(vm).contains("auto-delete failed: IllegalStateException: boom"))
        assertFalse(vm.state.value.busy)
        assertFalse("no ADB session here", vm.state.value.connected)
    }
}
