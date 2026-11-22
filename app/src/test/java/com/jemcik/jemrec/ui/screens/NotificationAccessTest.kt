package com.jemcik.jemrec.ui.screens

import android.app.NotificationManager
import android.content.ComponentName
import androidx.lifecycle.viewModelScope
import com.jemcik.jemrec.capture.DebugNotificationCleaner
import com.jemcik.jemrec.capture.FakeRecorder
import com.jemcik.jemrec.setup.Setup
import com.jemcik.jemrec.ui.grantProblem
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
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

/**
 * Notification access for the app's listener, after setup: what Show who
 * called and Hide the debugging notification need
 * (Setup.grantNotificationAccess).
 * The recorder grants it - the shell, with no ADB session - and where none
 * answers, one is started and asked (1.5). Until then a session's way was
 * left for no answer, and on the OnePlus, on Wi-Fi, Show who called said
 * "it needs Wi-Fi": the grant opened a session, which needs Wireless
 * debugging, and LineageOS had switched that off by itself (2026-11-12);
 * since 1.5 JemRec switches it off between starts itself. The recorder is
 * a FakeRecorder, and the start a stand-in (Setup.reviveForGrant), except
 * where a test says: no adbd is reached.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36], qualifiers = ScreenTest.PHONE)
class NotificationAccessTest : ScreenTest() {

    private val app = RuntimeEnvironment.getApplication()
    private var recorder: FakeRecorder? = null
    private val realRevive = Setup.reviveForGrant

    /** What the stand-in start does - brings a recorder up, replaces one -
     *  and whether a recorder is up after it; by default none can be started. */
    private var start: () -> Boolean = { false }
    private val starts = java.util.concurrent.atomic.AtomicInteger()

    @Before fun aStartStandIn() {
        Setup.reviveForGrant = {
            starts.incrementAndGet()
            start()
        }
    }

    @After fun recorderGone() {
        recorder?.close()
        Setup.reviveForGrant = realRevive
    }

    private fun grantAndroidsSide() {
        shadowOf(app.getSystemService(NotificationManager::class.java))
            .setNotificationListenerAccessGranted(ComponentName(app, DebugNotificationCleaner::class.java), true)
    }

    /** A recorder that grants it as the recorder does: Android then lists
     *  the listener as allowed. */
    private fun grantingRecorder(): FakeRecorder = FakeRecorder(app).also { fake ->
        recorder = fake
        fake.replies["B"] = "LISTENER ok"
        fake.stall = { key -> if (key == "B") grantAndroidsSide() }
    }

    @Test fun theRecorderGrantsItWithNoAdbSession() = runBlocking {
        val fake = grantingRecorder()

        assertNull(Setup.grantNotificationAccess(app))

        assertEquals(listOf("B"), fake.heard.map { it.key })
        assertTrue(DebugNotificationCleaner.accessGranted(app))
        assertEquals("a start made", 0, starts.get())
    }

    /** The recorder read Android's list before Android wrote it - the list
     *  is saved a moment after the grant - and said failed; Android has
     *  allowed it, and this process's own read says so: granted. */
    @Test fun androidsOwnWordDecidesOverTheRecordersReadOfItsList() = runBlocking {
        recorder = FakeRecorder(app).also { fake ->
            fake.replies["B"] = "LISTENER failed"
            fake.stall = { key -> if (key == "B") grantAndroidsSide() }
        }

        assertNull(Setup.grantNotificationAccess(app))
        assertEquals("a start made", 0, starts.get())
    }

    /** The recorder said ok, and this process's read says Android has not
     *  allowed it - another user or profile than the one the shell granted
     *  it for, say: refused, and no start made. */
    @Test fun aRecordersOkIsNotTakenWithoutAndroidsWord() = runBlocking {
        recorder = FakeRecorder(app).also { it.replies["B"] = "LISTENER ok" }

        assertEquals(Setup.GrantProblem.REFUSED, Setup.grantNotificationAccess(app))
        assertEquals("a start made", 0, starts.get())
    }

    /** Asked, and Android said no to the recorder: that is the answer. A
     *  session's shell would be told the same, so nothing more is tried. */
    @Test fun aRecorderTurnedDownIsTheAnswer() = runBlocking {
        recorder = FakeRecorder(app).also { it.replies["B"] = "LISTENER failed" }

        assertEquals(Setup.GrantProblem.REFUSED, Setup.grantNotificationAccess(app))
        assertEquals("a start made", 0, starts.get())
    }

    /** No recorder: one is started, as Tap to start again starts it, and
     *  the recorder then up grants it. */
    @Test fun noRecorderIsStartedAndThenAsked() = runBlocking {
        start = {
            grantingRecorder()
            true
        }

        assertNull(Setup.grantNotificationAccess(app))
        assertEquals(1, starts.get())
        assertEquals(listOf("B"), recorder!!.heard.map { it.key })
    }

    /** ...whose ok, where Android has not allowed it, is refused: this
     *  process's read decides. */
    @Test fun aStartedRecordersOkIsNotTakenWithoutAndroidsWord() = runBlocking {
        start = {
            recorder = FakeRecorder(app).also { it.replies["B"] = "LISTENER ok" }
            true
        }

        assertEquals(Setup.GrantProblem.REFUSED, Setup.grantNotificationAccess(app))
    }

    /** ...and whose failed is a refusal. */
    @Test fun aStartedRecorderTurnedDownIsARefusal() = runBlocking {
        start = {
            recorder = FakeRecorder(app).also { it.replies["B"] = "LISTENER failed" }
            true
        }

        assertEquals(Setup.GrantProblem.REFUSED, Setup.grantNotificationAccess(app))
    }

    /** The recorder granted it and its reply was lost - one slow to answer,
     *  say: the start finds it up, it is asked again, and Android has
     *  allowed it, so it is granted, not "did not answer". */
    @Test fun aLostReplyAfterTheGrantIsGranted() = runBlocking {
        val fake = FakeRecorder(app).also { fake ->
            recorder = fake
            fake.replies.remove("B")
            fake.stall = { key -> if (key == "B") grantAndroidsSide() }
        }
        start = { true }

        assertNull(Setup.grantNotificationAccess(app))
        assertEquals(listOf("B", "B"), fake.heard.map { it.key })
    }

    /** No recorder, and none could be started - no Wi-Fi and no port, JemRec
     *  off with all debugging: no answer. */
    @Test fun noRecorderAndNoStartIsNoAnswer() = runBlocking {
        assertEquals(Setup.GrantProblem.NO_ANSWER, Setup.grantNotificationAccess(app))
        assertEquals(1, starts.get())
    }

    /** A recorder before 0.31 - up, recording - hangs up on the command: the
     *  start replaces it, and the new one grants it. */
    @Test fun anOlderRecorderIsReplacedAndTheNewOneAsked() = runBlocking {
        val fake = FakeRecorder(app).also { recorder = it }
        fake.replies.remove("B")
        start = {
            fake.replies["B"] = "LISTENER ok"
            fake.stall = { key -> if (key == "B") grantAndroidsSide() }
            true
        }

        assertNull(Setup.grantNotificationAccess(app))
        assertEquals(listOf("B", "B"), fake.heard.map { it.key })
    }

    /** ...and where it cannot be replaced, no answer. Not "the recorder is
     *  not running": it is. */
    @Test fun anOlderRecorderThatCannotBeReplacedIsNoAnswer() = runBlocking {
        val fake = FakeRecorder(app).also { recorder = it }
        fake.replies.remove("B")

        assertEquals(Setup.GrantProblem.NO_ANSWER, Setup.grantNotificationAccess(app))
        assertEquals(listOf("B"), fake.heard.map { it.key })
        assertEquals(1, starts.get())
    }

    /** The real start, on a phone set up and off Wi-Fi with no port: it
     *  cannot start one, and says no answer. */
    @Test fun offWifiWithNoPortTheRealStartCannotAndItIsNoAnswer() = runBlocking {
        Setup.reviveForGrant = realRevive
        com.jemcik.jemrec.capture.TestPhone.switchesOn(app)
        com.jemcik.jemrec.capture.TestPhone.setUp(app)
        com.jemcik.jemrec.capture.TestPhone.offWifi(app)

        assertEquals(Setup.GrantProblem.NO_ANSWER, Setup.grantNotificationAccess(app))
    }

    /** The real start, JemRec off with all debugging: it stands down, and
     *  it is no answer, as with adbd stopped before 1.5. */
    @Test fun withAllDebuggingOffTheRealStartStandsDownAndItIsNoAnswer() = runBlocking {
        Setup.reviveForGrant = realRevive
        com.jemcik.jemrec.capture.TestPhone.switchesOn(app)
        com.jemcik.jemrec.capture.TestPhone.setUp(app)
        com.jemcik.jemrec.recorder.DebugSwitchesOff.setMode(app, com.jemcik.jemrec.recorder.OffMode.ALL_DEBUGGING_OFF)
        com.jemcik.jemrec.recorder.RecorderSwitch.set(app, false)
        com.jemcik.jemrec.recorder.DebugSwitchesOff.switchOff(app)
        assertTrue(com.jemcik.jemrec.recorder.DebugSwitchesOff.standingDown(app))

        assertEquals(Setup.GrantProblem.NO_ANSWER, Setup.grantNotificationAccess(app))
        assertTrue(
            "the revive did not stand down",
            "revive: debugging is off with JemRec; nothing to do until it is on" in
                java.io.File(app.filesDir, "revive.log").readText(),
        )
    }

    /** The screen closed from Recents while the recorder grants it: Android
     *  allows it, and the switch is saved as on - not left off over an
     *  access Android gave. */
    @Test fun showWhoCalledIsSavedWhenTheScreenClosesInTheGrant() {
        val inGrant = java.util.concurrent.CountDownLatch(1)
        val grantGoes = java.util.concurrent.CountDownLatch(1)
        recorder = FakeRecorder(app).also { fake ->
            fake.replies["B"] = "LISTENER ok"
            fake.stall = { key ->
                if (key == "B") {
                    inGrant.countDown()
                    grantGoes.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    grantAndroidsSide()
                }
            }
        }
        val vm = viewModel()

        vm.setVoipNames(true)
        awaitMain("the grant asked") { inGrant.count == 0L }
        vm.viewModelScope.cancel()
        grantGoes.countDown()

        awaitMain("Show who called saved", timeoutMs = 5_000) { com.jemcik.jemrec.calls.VoipCallNotes.enabled(app) }
    }

    /** A recorder whose grant waits for [goes]; [asked] once it has the
     *  command. */
    private fun heldGrant(asked: java.util.concurrent.CountDownLatch, goes: java.util.concurrent.CountDownLatch) {
        recorder = FakeRecorder(app).also { fake ->
            fake.replies["B"] = "LISTENER ok"
            fake.stall = { key ->
                if (key == "B") {
                    asked.countDown()
                    goes.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    grantAndroidsSide()
                }
            }
        }
    }

    /** Until [vm]'s own work is done - not the process's, where a grant
     *  that outlives its screen is held. */
    private fun ownWorkDone(vm: com.jemcik.jemrec.ui.MainViewModel) =
        awaitMain("the new screen's own work") {
            vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]!!.children.all { it.isCompleted }
        }

    /** JemRec closed from Recents in the grant and opened again: the new
     *  screen read the switch off, and shows it on once the grant saves it,
     *  not at its next reading. */
    @Test fun aScreenOpenedInTheGrantShowsShowWhoCalledOnOnceItIsSaved() {
        val inGrant = java.util.concurrent.CountDownLatch(1)
        val grantGoes = java.util.concurrent.CountDownLatch(1)
        heldGrant(inGrant, grantGoes)
        val first = viewModel()
        first.setVoipNames(true)
        awaitMain("the grant asked") { inGrant.count == 0L }
        first.viewModelScope.cancel()

        val again = viewModel()
        ownWorkDone(again)
        assertFalse(again.state.value.voipNames)
        grantGoes.countDown()

        awaitMain("Show who called on, on the new screen", timeoutMs = 5_000) { again.state.value.voipNames }
        settle(again)
    }

    /** The same for Hide the debugging notification, whose grant has
     *  outlived its screen since 0.30. */
    @Test fun aScreenOpenedInTheGrantShowsTheNotificationHiddenOnceItIsSaved() {
        Setup.markComplete(app)
        val inGrant = java.util.concurrent.CountDownLatch(1)
        val grantGoes = java.util.concurrent.CountDownLatch(1)
        heldGrant(inGrant, grantGoes)
        val first = viewModel()
        first.setHideDebugNotification(true)
        awaitMain("the grant asked") { inGrant.count == 0L }
        first.viewModelScope.cancel()

        val again = viewModel()
        ownWorkDone(again)
        assertFalse(again.state.value.debugNotificationHidden)
        grantGoes.countDown()

        awaitMain("hidden, on the new screen", timeoutMs = 5_000) { again.state.value.debugNotificationHidden }
        // The first screen's answer runs on in the process's scope - its setup
        // look after the grant asks the recorder - and is waited for here,
        // while this test's recorder is up, not left to the next test's.
        settle(again)
    }

    @Test fun showWhoCalledIsTurnedOnThroughTheRecorder() {
        grantingRecorder()
        val vm = viewModel()

        vm.setVoipNames(true)
        settle(vm)

        assertTrue(vm.state.value.voipNames)
        assertNull(vm.state.value.voipNamesProblem)
    }

    @Test fun showWhoCalledSaysTheRecorderDidNotAnswer() {
        val vm = viewModel()

        vm.setVoipNames(true)
        settle(vm)

        assertFalse(vm.state.value.voipNames)
        assertEquals(Setup.GrantProblem.NO_ANSWER, vm.state.value.voipNamesProblem)
    }

    @Test fun hidingTheDebuggingNotificationIsTurnedOnThroughTheRecorder() {
        Setup.markComplete(app)
        grantingRecorder()
        val vm = viewModel()

        vm.setHideDebugNotification(true)
        settle(vm)

        assertTrue(vm.state.value.debugNotificationHidden)
        assertNull(vm.state.value.debugNotificationProblem)
    }

    @Test fun hidingTheDebuggingNotificationSaysTheRecorderDidNotAnswer() {
        Setup.markComplete(app)
        val vm = viewModel()

        vm.setHideDebugNotification(true)
        settle(vm)

        assertFalse(vm.state.value.debugNotificationHidden)
        assertEquals(Setup.GrantProblem.NO_ANSWER, vm.state.value.debugNotificationProblem)
    }

    /** Under both switches: what is missing, and never Wi-Fi - the recorder
     *  needs none, and Wi-Fi was there when it said so. */
    @Test fun theWordsSayWhatIsMissing() {
        assertEquals(
            "Could not turn this on: the recorder did not answer. Try again once JemRec is ready to record.",
            grantProblem(Setup.GrantProblem.NO_ANSWER),
        )
        assertEquals("Could not turn this on: Android refused it.", grantProblem(Setup.GrantProblem.REFUSED))
        assertFalse(Setup.GrantProblem.entries.any { "Wi-Fi" in grantProblem(it) })
    }
}
