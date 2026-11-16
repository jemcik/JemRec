package com.jemcik.jemrec.ui.screens

import android.provider.Settings
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.jemcik.jemrec.capture.FakeRecorder
import com.jemcik.jemrec.recorder.KeepPaired
import com.jemcik.jemrec.setup.Setup
import com.jemcik.jemrec.setup.SetupStep
import com.jemcik.jemrec.ui.AppScaffold
import com.jemcik.jemrec.ui.Screen
import com.jemcik.jemrec.ui.keepPairedSummary
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
import org.robolectric.annotation.GraphicsMode

/**
 * Keep JemRec paired on the screen: the switch in Settings and setup's
 * question go to the recorder (a FakeRecorder answering COMMAND_KEEP_PAIRED
 * as the recorder does), and say when none answered or it could not; the
 * pairing step names the timeout when it is back for a lost pairing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36], qualifiers = ScreenTest.PHONE)
class KeepPairedScreenTest : ScreenTest() {

    private val app = RuntimeEnvironment.getApplication()
    private var recorder: FakeRecorder? = null

    private fun timeout(value: String?) = Settings.Global.putString(app.contentResolver, KeepPaired.SETTING, value)

    @Before fun androidsTimeoutOn() {
        timeout(null)
    }

    @After fun recorderGone() {
        recorder?.close()
    }

    /** A recorder that answers [reply], and turns the timeout off as it does
     *  when the reply is ok. */
    private fun recorder(reply: String = "PAIRING ok"): FakeRecorder = FakeRecorder(app).also { fake ->
        recorder = fake
        fake.replies["J"] = reply
        fake.stall = { key -> if (key == "J" && reply == "PAIRING ok") timeout("0") }
    }

    @Test fun theSwitchKeepsThePairingThroughTheRecorder() {
        Setup.markComplete(app)
        val fake = recorder()
        val vm = viewModel(Fixtures.ready().copy(screen = Screen.SETTINGS))

        vm.setKeepPaired(true)
        settle(vm)

        assertEquals(1, fake.heard.count { it.key == "J" && it.payload.contentEquals(byteArrayOf(1)) })
        assertTrue(vm.state.value.pairingKept)
        assertNull(vm.state.value.keepPairedWaiting)
        assertFalse(vm.state.value.keepPairedRefused)
    }

    @Test fun withNoRecorderTheChoiceWaitsAndTheSwitchSaysSo() {
        Setup.markComplete(app)
        val vm = viewModel(Fixtures.ready().copy(screen = Screen.SETTINGS))

        vm.setKeepPaired(true)
        settle(vm)

        assertFalse(vm.state.value.pairingKept)
        assertEquals(true, vm.state.value.keepPairedWaiting)
        assertEquals("Turns on when the recorder is running again", keepPairedSummary(vm.state.value))
    }

    @Test fun aRefusalIsSaid() {
        Setup.markComplete(app)
        recorder("PAIRING failed")
        val vm = viewModel(Fixtures.ready().copy(screen = Screen.SETTINGS))

        vm.setKeepPaired(true)
        settle(vm)

        assertFalse(vm.state.value.pairingKept)
        assertTrue(vm.state.value.keepPairedRefused)
        assertEquals("Could not change this: Android refused it", keepPairedSummary(vm.state.value))
    }

    @Test fun theSummarySaysWhatAndroidDoesAsTheSwitchStands() {
        val on = Fixtures.ready().copy(pairingKept = true)
        assertEquals(
            "No pairing is forgotten - nor a computer you allowed for USB debugging. " +
                "Turned off, one older than Android's limit goes at once",
            keepPairedSummary(on),
        )
        assertEquals(
            "Android forgets JemRec's pairing 7 days after it is made. Then JemRec has to pair again",
            keepPairedSummary(on.copy(pairingKept = false)),
        )
        assertEquals(
            "Android forgets JemRec's pairing 1 day after it is made. Then JemRec has to pair again",
            keepPairedSummary(on.copy(pairingKept = false, pairingDays = 1)),
        )
        assertEquals("Turns off when the recorder is running again", keepPairedSummary(on.copy(keepPairedWaiting = false)))
    }

    @Test fun theSwitchInSettingsIsTheOneTheRowShows() {
        val state = Fixtures.ready().copy(screen = Screen.SETTINGS)
        show { AppScaffold(state, viewModel(state)) }
        compose.onNodeWithText("Keep JemRec paired").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(keepPairedSummary(state)).assertIsDisplayed()
    }

    @Test fun setupAsksAndTheAnswerWaitsForTheRecorder() {
        val state = Fixtures.ready().copy(step = SetupStep.NEEDS_KEEP_PAIRED_CHOICE)
        val vm = viewModel(state)
        show { AppScaffold(state, vm) }
        compose.onNodeWithText("Keep it paired").performScrollTo().performClick()
        settle(vm)
        // Asked, and waiting: setup's recorder carries it out (pushMode).
        assertEquals(true, KeepPaired.waiting(app))
        assertFalse(KeepPaired.needsChoice(app))
    }

    @Test fun thePairingStepNamesTheTimeoutWhenItIsOn() {
        val lost = Fixtures.ready().copy(step = SetupStep.NEEDS_PAIRING, pairingLost = true)
        show { AppScaffold(lost, viewModel(lost)) }
        compose.onNodeWithText(
            "Android deletes it 7 days after it is made, unless Keep JemRec paired is on in Settings.",
            substring = true,
        ).assertIsDisplayed()
    }

    @Test fun thePairingStepDoesNotBlameTheTimeoutWhenItIsOff() {
        val lost = Fixtures.ready().copy(step = SetupStep.NEEDS_PAIRING, pairingLost = true, pairingKept = true)
        show { AppScaffold(lost, viewModel(lost)) }
        compose.onNodeWithText("Wireless debugging no longer lets JemRec in", substring = true).assertIsDisplayed()
        compose.onAllNodesWithText("Keep JemRec paired is on", substring = true).assertCountEquals(0)
    }
}
