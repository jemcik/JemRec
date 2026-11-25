package com.jemcik.jemrec.ui.screens

import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.jemcik.jemrec.calls.CallButton
import com.jemcik.jemrec.calls.CallButton.Face
import com.jemcik.jemrec.calls.NotificationHealth
import com.jemcik.jemrec.calls.RecordPrompt
import com.jemcik.jemrec.capture.FakeRecorder
import com.jemcik.jemrec.capture.RecordingMode
import com.jemcik.jemrec.ui.AppScaffold
import com.jemcik.jemrec.ui.CallButtonPill
import com.jemcik.jemrec.ui.RecordThisCallDialog
import com.jemcik.jemrec.ui.Screen
import com.jemcik.jemrec.ui.UiState
import com.jemcik.jemrec.ui.theme.Look
import com.jemcik.jemrec.ui.theme.LookChoice
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSettings

/**
 * Ask first's button over calls: the pill as it is drawn over a call's screen
 * (CallButtonPill) in each look, the question as a dialog, and the switch in
 * Settings, whose permission goes through the recorder (a FakeRecorder
 * answering COMMAND_SET_OVERLAY as the recorder does) or Android's own page.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36], qualifiers = ScreenTest.PHONE)
class CallButtonScreenTest : ScreenTest() {

    private val app = RuntimeEnvironment.getApplication()
    private var recorder: FakeRecorder? = null

    @After fun recorderGone() {
        recorder?.close()
    }

    // --- the pill -------------------------------------------------------------

    private fun pills(look: LookChoice = LookChoice(), fontScale: Float = 1f) = show(fontScale, look) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Face.entries.forEach { CallButtonPill(it, onRecord = {}, onNotNow = {}) }
        }
    }

    @Test fun theButton() {
        pills()
        snap("call-button")
    }

    @Config(qualifiers = "+night")
    @Test fun theButtonDeckDark() {
        pills(LookChoice(Look.DECK))
        snap("call-button-deck-dark")
    }

    @Test fun theButtonDeck() {
        pills(LookChoice(Look.DECK))
        snap("call-button-deck")
    }

    @Test fun theButtonMultitrack() {
        pills(LookChoice(Look.MULTITRACK))
        snap("call-button-multitrack")
    }

    @Config(qualifiers = "+night")
    @Test fun theButtonMemoDarkLargeText() {
        pills(fontScale = 2f)
        snap("call-button-large-text-dark")
    }

    @Test fun recordAndItsCrossAreTwoButtonsNamed() {
        val tapped = mutableListOf<String>()
        show { CallButtonPill(Face.ASK, onRecord = { tapped += "record" }, onNotNow = { tapped += "not now" }) }
        compose.onNodeWithContentDescription("Record this call").performClick()
        compose.onNodeWithContentDescription("Not now").performClick()
        assertEquals(listOf("record", "not now"), tapped)
    }

    @Test fun recordingIsNotSaidBeforeTheRecorderSaysIt() {
        show { CallButtonPill(Face.STARTING, onRecord = {}, onNotNow = {}) }
        compose.onNodeWithText("Starting").assertIsDisplayed()
        compose.onAllNodesWithText("Recording").assertCountEquals(0)
        // Nothing to tap twice.
        compose.onAllNodesWithText("Record").assertCountEquals(0)
    }

    // --- the question as a window -----------------------------------------------

    @Test fun theQuestionAsADialog() {
        val tapped = mutableListOf<String>()
        show {
            RecordThisCallDialog("Incoming call in progress.", { tapped += "record" }, { tapped += "not now" }, { tapped += "close" })
        }
        snapWithDialog("record-this-call")
        compose.onNodeWithText("Record").performClick()
        compose.onNodeWithText("Not now").performClick()
        assertEquals(listOf("record", "not now"), tapped)
    }

    // --- the switch -------------------------------------------------------------

    private fun askFirst() = Fixtures.ready().copy(screen = Screen.SETTINGS, automatic = false)

    private fun settings(state: UiState, look: LookChoice = LookChoice()) = show(look = look) { AppScaffold(state, viewModel(state)) }

    private val switch get() = compose.onNode(isToggleable() and hasText("Record button over calls"))

    @Test fun theSwitchIsAskFirstsAlone() {
        settings(Fixtures.ready().copy(screen = Screen.SETTINGS))
        compose.onAllNodesWithText("Record button over calls").assertCountEquals(0)
    }

    /** Its permission is held until it is switched off: in Record every call
     *  too there is a switch to do it with. */
    @Test fun onItIsThereInRecordEveryCallToo() {
        settings(Fixtures.ready().copy(screen = Screen.SETTINGS, callButton = true, overlayAllowed = false))
        switch.assertIsOn()
        compose.onNodeWithText("Only while Record every call is off").assertIsDisplayed()
        compose.onAllNodesWithText("Not shown", substring = true).assertCountEquals(0)
        snap("settings-record-button-in-automatic")
    }

    @Test fun offItSaysWhatItWouldDo() {
        settings(askFirst())
        switch.assertIsOff()
        compose.onNodeWithText("Asks with a button on the call screen instead of a pop-up").assertIsDisplayed()
        snap("settings-ask-first")
    }

    @Test fun onItSaysHowLongItStays() {
        settings(askFirst().copy(callButton = true, overlayAllowed = true))
        switch.assertIsOn()
        compose.onNodeWithText("Stays on the call screen until you answer it or the call ends").assertIsDisplayed()
    }

    @Test fun onButNotAllowedItSaysWhyAndCanBeSwitchedOff() {
        settings(askFirst().copy(callButton = true, overlayAllowed = false))
        switch.assertIsOn()
        compose.onNodeWithText("Not shown: Display over other apps is off for JemRec", substring = true).assertIsDisplayed()
        snap("settings-ask-first-button-not-allowed")
    }

    @Test fun whileTheRecorderIsAskedItSaysNothingIsWrong() {
        settings(askFirst().copy(callButton = true, overlayAllowed = null))
        switch.assertIsOn()
        compose.onAllNodesWithText("Not shown", substring = true).assertCountEquals(0)
    }

    @Test fun allowedAndSwitchedOffItReadsOff() {
        settings(askFirst().copy(callButton = false, overlayAllowed = true))
        switch.assertIsOff()
    }

    /** A recorder that answers [reply], and allows or takes back the
     *  permission as it does when the reply is ok. */
    private fun recorder(reply: String = "OVERLAY ok") = FakeRecorder(app).also { fake ->
        recorder = fake
        fake.replies["M"] = reply
        fake.stall = { key ->
            if (key == "M" && reply == "OVERLAY ok") {
                ShadowSettings.setCanDrawOverlays(fake.heard.last { it.key == "M" }.payload.single().toInt() == 1)
            }
        }
    }

    private fun asked() = recorder!!.heard.filter { it.key == "M" }.map { it.payload.single().toInt() }

    @Test fun theRecorderAllowsItAndTakesItBack() {
        recorder()
        val vm = viewModel(askFirst())
        vm.setCallButton(true)
        settle(vm)
        assertEquals(listOf(1), asked())
        assertTrue(CallButton.enabled(app))
        assertTrue(vm.state.value.callButton)
        assertEquals(true, vm.state.value.overlayAllowed)
        assertNull("no page needed", shadowOf(app).nextStartedActivity)

        vm.setCallButton(false)
        settle(vm)
        assertEquals(listOf(1, 0), asked())
        assertFalse(CallButton.enabled(app))
        assertEquals(false, vm.state.value.overlayAllowed)
        assertNull("nothing to allow", shadowOf(app).nextStartedActivity)
    }

    @Test fun switchedOffWithNoRecorderSaysItIsStillAllowed() {
        ShadowSettings.setCanDrawOverlays(true)
        CallButton.setEnabled(app, true)
        val vm = viewModel(askFirst().copy(callButton = true, overlayAllowed = true))
        vm.setCallButton(false)
        settle(vm)
        assertFalse(CallButton.enabled(app))
        assertEquals("Android still lets it", true, vm.state.value.overlayAllowed)
        assertTrue("said", "Android still lets JemRec display over other apps" in vm.state.value.log)
        assertNull("no page opened for an off", shadowOf(app).nextStartedActivity)
    }

    /** The recorder slow over the first flip: the second's order, on a
     *  connection of its own, would overtake it. */
    @Test fun twoQuickFlipsEndAsTheLastOneSaid() {
        val fake = recorder()
        val allow = fake.stall
        val first = java.util.concurrent.atomic.AtomicBoolean(true)
        fake.stall = { key ->
            val mine = fake.heard.last { it.key == "M" }.payload.single().toInt()
            if (key == "M" && first.compareAndSet(true, false)) Thread.sleep(600)
            if (key == "M") ShadowSettings.setCanDrawOverlays(mine == 1) else allow(key)
        }
        val vm = viewModel(askFirst())
        vm.setCallButton(true)
        vm.setCallButton(false)
        settle(vm)
        assertFalse(CallButton.enabled(app))
        assertEquals("the last word", 0, asked().last())
        assertFalse("and Android's", CallButton.allowed(app))
        assertEquals(false, vm.state.value.overlayAllowed)
    }

    @Test fun aFlipTakenBackMeanwhileOpensNoPage() {
        val fake = recorder("OVERLAY failed")
        val failed = fake.stall
        fake.stall = { key ->
            if (key == "M") Thread.sleep(300)
            failed(key)
        }
        val vm = viewModel(askFirst())
        vm.setCallButton(true)
        vm.setCallButton(false)
        settle(vm)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    /** What the switch said of the permission while [run], from the flip on:
     *  a reading stalled on the recorder for [readingMs], the recorder's own
     *  answer to the flip [answerMs] on its way. */
    private fun said(readingMs: Long, answerMs: Long, run: (com.jemcik.jemrec.ui.MainViewModel, FakeRecorder) -> Unit): List<Boolean?> {
        val fake = recorder()
        val allow = fake.stall
        fake.replies["U"] = "USB default 0"
        val stalled = java.util.concurrent.atomic.AtomicBoolean(true)
        fake.stall = { key ->
            if (key == "U" && stalled.compareAndSet(true, false)) Thread.sleep(readingMs)
            if (key == "M") Thread.sleep(answerMs)
            allow(key)
        }
        val vm = viewModel(askFirst())
        val said = java.util.concurrent.CopyOnWriteArrayList<Boolean?>()
        val watching = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
            vm.state.collect { said += it.overlayAllowed }
        }
        run(vm, fake)
        settle(vm)
        watching.cancel()
        assertEquals(true, vm.state.value.overlayAllowed)
        return said
    }

    private fun awaitTheReading(fake: FakeRecorder) {
        val until = System.currentTimeMillis() + 5_000
        while (fake.heard.none { it.key == "U" } && System.currentTimeMillis() < until) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("the reading waits on the recorder", fake.heard.any { it.key == "U" })
    }

    /** A reading that found it off before the flip, landing while the
     *  recorder is asked: not for a moment said to be off, the red words of a
     *  refusal. */
    @Test fun aReadingBegunBeforeTheFlipSaysNothingOfIt() {
        val said = said(readingMs = 700, answerMs = 1_500) { vm, fake ->
            vm.refresh()
            awaitTheReading(fake)
            vm.setCallButton(true)
        }
        assertFalse("$said", false in said)
    }

    /** One begun after the flip, before the recorder's answer, landing after
     *  it: what it found was not yet allowed. */
    @Test fun aReadingBegunBeforeTheAnswerSaysNothingOfIt() {
        val said = said(readingMs = 2_000, answerMs = 1_000) { vm, fake ->
            vm.setCallButton(true)
            vm.refresh()
            awaitTheReading(fake)
        }
        assertFalse("$said", false in said)
    }

    @Test fun onOffAndOnAgainOpensAndroidsPageOnce() {
        recorder("OVERLAY failed")
        val vm = viewModel(askFirst())
        vm.setCallButton(true)
        vm.setCallButton(false)
        vm.setCallButton(true)
        settle(vm)
        assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, shadowOf(app).nextStartedActivity?.action)
        assertNull("once", shadowOf(app).nextStartedActivity)
    }

    @Test fun aRecorderThatCannotLeavesItToAndroidsPage() {
        recorder("OVERLAY failed")
        val vm = viewModel(askFirst())
        vm.setCallButton(true)
        settle(vm)
        assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, shadowOf(app).nextStartedActivity?.action)
        assertEquals("Choose JemRec and allow it", org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        assertTrue("wanted, once allowed there", CallButton.enabled(app))
        assertEquals(false, vm.state.value.overlayAllowed)
    }

    @Test fun withNoRecorderAndroidsPageOpens() {
        val vm = viewModel(askFirst())
        vm.setCallButton(true)
        settle(vm)
        assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, shadowOf(app).nextStartedActivity?.action)
    }

    @Test fun allowedAlreadyNoPageOpensWhateverTheRecorderSays() {
        ShadowSettings.setCanDrawOverlays(true)
        val vm = viewModel(askFirst())
        vm.setCallButton(true)
        settle(vm)
        assertNull(shadowOf(app).nextStartedActivity)
        assertEquals(true, vm.state.value.overlayAllowed)
    }

    @Test fun turnedOnItAnswersForTheQuestionsNotificationAtOnce() {
        recorder()
        RecordingMode.set(app, RecordingMode.ON_DEMAND)
        val off = NotificationHealth.Off.ASK_FIRST
        // Off in Android's settings too: a reading follows the flip.
        val manager = app.getSystemService(android.app.NotificationManager::class.java)
        RecordPrompt.ensureChannel(app)
        manager.getNotificationChannel(RecordPrompt.CHANNEL_ID).importance = android.app.NotificationManager.IMPORTANCE_NONE
        val vm = viewModel(
            askFirst().copy(
                notificationSwitches = NotificationHealth.Switches(all = false, askFirst = true, problems = false),
                notificationsOff = off,
            ),
        )
        vm.setCallButton(true)
        settle(vm)
        assertNull("no longer said", vm.state.value.notificationsOff)
        vm.setCallButton(false)
        settle(vm)
        assertEquals(off, vm.state.value.notificationsOff)
    }
}
