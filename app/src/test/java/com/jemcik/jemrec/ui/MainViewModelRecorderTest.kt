package com.jemcik.jemrec.ui

import android.os.Looper
import android.provider.Settings
import androidx.lifecycle.viewModelScope
import com.google.common.util.concurrent.SettableFuture
import com.jemcik.jemrec.capture.DebugNotificationCleaner
import com.jemcik.jemrec.capture.FakeRecorder
import com.jemcik.jemrec.capture.HeldWork
import com.jemcik.jemrec.capture.HeldWork.Companion.NOW
import com.jemcik.jemrec.capture.HeldWork.Companion.PERIODIC
import com.jemcik.jemrec.capture.HeldWork.Companion.USB_WATCH
import com.jemcik.jemrec.capture.TestPhone
import com.jemcik.jemrec.recorder.DebugSwitchesOff
import com.jemcik.jemrec.recorder.RecorderSwitch
import com.jemcik.jemrec.recorder.StartWithoutWifi
import com.jemcik.jemrec.setup.Setup
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowLog
import java.util.concurrent.atomic.AtomicInteger

/**
 * The home screen's part in the recorder's lifecycle (MainViewModel), as it
 * is: what its setup check arms and tells the recorder, Settings' Start
 * recorder, and setup's Open settings while the screen is busy. A real view
 * model under Robolectric, its coroutines run by turning the main looper;
 * off Wi-Fi, with or without a recorder that answers (FakeRecorder).
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class MainViewModelRecorderTest {

    private val context = RuntimeEnvironment.getApplication()
    private lateinit var work: HeldWork
    private lateinit var vm: MainViewModel
    private var recorder: FakeRecorder? = null

    @Before fun aPhoneSetUpOffWifiWithJemRecOn() {
        TestPhone.switchesOn(context)
        TestPhone.offWifi(context)
        DebugSwitchesOff.forget(context)
        RecorderSwitch.set(context, true)
        TestPhone.setUp(context)
        TestPhone.clearReviveLog(context)
        work = HeldWork(context)
        vm = MainViewModel(context, connectSession = { SettableFuture.create() })
    }

    /** Its work stopped and waited for, turning the main looper, before the
     *  app goes (as ScreenTest does). A connect's look is not cut short, and
     *  takes up to 6 s. */
    @After fun closeAll() {
        vm.viewModelScope.cancel()
        until("the view model's work to stop", seconds = 10) { vm.viewModelScope.coroutineContext.job.isCompleted }
        recorder?.close()
        work.close()
    }

    private fun until(what: String, seconds: Long = 15, done: () -> Boolean) {
        val main = shadowOf(Looper.getMainLooper())
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        while (true) {
            main.idle()
            if (done()) return
            if (System.nanoTime() > deadline) fail("never happened: $what")
            Thread.sleep(10)
        }
    }

    /** The main looper turned for a while: the view model's coroutines run
     *  only as it turns, so a sleep here would let none of them go on. */
    private fun turnFor(ms: Long) {
        val main = shadowOf(Looper.getMainLooper())
        val deadline = System.nanoTime() + ms * 1_000_000L
        while (System.nanoTime() < deadline) {
            main.idle()
            Thread.sleep(10)
        }
    }

    private val state get() = vm.state.value

    /** An action run to its end: busy, then free again. */
    private fun ran(start: () -> Unit) {
        start()
        until("the action to start") { state.busy }
        until("the action to end") { !state.busy }
    }

    private val wifiWatch = "the Wi-Fi watch"

    private fun armed(): Set<String> =
        HeldWork.ALL.filter { work.unique(it).isNotEmpty() }.toSet() +
            (if (TestPhone.wifiWatches(context).isNotEmpty()) setOf(wifiWatch) else emptySet())

    @Test fun theSetupCheckWithJemRecOnArmsEveryWatchAKickAndTellsTheRecorder() {
        recorder = FakeRecorder(context)
        ran { vm.refreshSetup() }
        // The Wi-Fi watch too, which opening the app (MainActivity) does not arm.
        assertEquals(setOf(PERIODIC, USB_WATCH, NOW, wifiWatch), armed())
        // The mode and the rest of pushMode, first thing, recorder up or not.
        assertEquals(listOf("E", "V", "T", "GQ", "N"), recorder!!.heard.map { it.key }.take(5))
    }

    @Test fun theSetupCheckWithJemRecOffArmsNothingAndStillTellsTheRecorder() {
        RecorderSwitch.set(context, false)
        recorder = FakeRecorder(context)
        ran { vm.refreshSetup() }
        assertEquals(emptySet<String>(), armed())
        assertEquals(listOf<Byte>(0), recorder!!.heard.first { it.key == "E" }.payload.toList())
    }

    @Test fun startRecorderWithARecorderUpSaysSo() {
        recorder = FakeRecorder(context)
        ran { vm.bootstrapDaemon() }
        assertTrue(state.log, "recorder running on 127.0.0.1:28472" in state.log)
    }

    @Test fun startRecorderWithTheRecorderDownRevivesItAndSaysWhyNot() {
        ran { vm.bootstrapDaemon() }
        // A revive the user asked for, which would connect and start it -
        // here it can only find there is no Wi-Fi - and its cause, in words.
        val log = TestPhone.reviveLog(context)
        assertEquals("revive: start", log.first())
        assertTrue(log.toString(), "revive: not on Wi-Fi" in log)
        assertEquals("revive: recorder down (connect NONE)", log.last())
        assertTrue(
            state.log,
            "could not start the recorder: it needs Wi-Fi for a few seconds, or Start without Wi-Fi's port" in state.log,
        )
    }

    @Test fun openSettingsWhileTheScreenIsBusyConnectsOnceItIsFree() {
        // Its looks for adbd counted, each finding nothing at once.
        val looks = AtomicInteger()
        vm.viewModelScope.cancel()
        vm = MainViewModel(context, connectSession = { SettableFuture.create() }, lookForAdb = {
            looks.incrementAndGet()
            false
        })
        // Start recorder holds busy while it asks for a recorder that is not
        // there (about 2.4 s), and arms nothing.
        vm.bootstrapDaemon()
        until("busy") { state.busy }
        // Setup's Open settings, meanwhile: its connect in the background.
        vm.reconnect()
        // It waits its turn, then looks, six times, and ends with the setup
        // check, which arms the watches. It was dropped: no look, nothing armed.
        until("the connect's six looks and its setup check") { looks.get() == 6 && armed().isNotEmpty() }
        assertEquals(setOf(PERIODIC, USB_WATCH, NOW, wifiWatch), armed())
    }

    @Test fun openSettingsWithTheScreenFreeStartsItsConnect() {
        vm.reconnect()
        // Its connect runs as an action, holding busy for as long as it looks.
        until("busy") { state.busy }
    }

    @Test fun everyFlipOfStartWithoutWifiHasARevive() {
        // Off Wi-Fi nothing is due, as nothing was for the flip that came while
        // a revive changed the port, the change not noted yet. Asked before
        // the lock, that flip asked for no revive, and 5555 stayed open under a
        // switch saying off (the Honor, 2026-11-05).
        vm.setStartWithoutWifi(false)
        until("its revive") { "revive: start" in TestPhone.reviveLog(context) }
    }

    @Test fun aCloseStillDueAfterAFlipIsTriedOnceMore() {
        recorder = FakeRecorder(context)
        vm.viewModelScope.cancel()
        vm = MainViewModel(context, connectSession = { SettableFuture.create() }, portCloseAgainMs = 0)
        // JemRec's port, the switch going off, and no revive able to close it:
        // here no Wi-Fi, on the Honor a connect that had only the port itself.
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 7)
        StartWithoutWifi.noteOpened(context, true)
        vm.setStartWithoutWifi(false)
        fun revives() = TestPhone.reviveLog(context).count { it == "revive: start" }
        until("the second revive") { revives() == 2 }
        // Once more, not over and over.
        turnFor(500)
        assertEquals(2, revives())
    }

    @Test fun aFlipWithNoCloseDueHasOneRevive() {
        recorder = FakeRecorder(context)
        vm.viewModelScope.cancel()
        vm = MainViewModel(context, connectSession = { SettableFuture.create() }, portCloseAgainMs = 0)
        vm.setStartWithoutWifi(false)
        until("its revive") { "revive: recorder up (connect NONE)" in TestPhone.reviveLog(context) }
        turnFor(500)
        assertEquals(1, TestPhone.reviveLog(context).count { it == "revive: start" })
    }

    @Test fun anActionCutShortByClosingTheScreenIsNoFailure() {
        val looks = AtomicInteger()
        vm.viewModelScope.cancel()
        vm = MainViewModel(context, connectSession = { SettableFuture.create() }, lookForAdb = {
            looks.incrementAndGet()
            awaitCancellation()
        })
        vm.reconnect()
        until("its look for adbd") { looks.get() == 1 }
        vm.viewModelScope.cancel()
        until("the view model's work to stop") { vm.viewModelScope.coroutineContext.job.isCompleted }
        assertFalse(state.log, "connect failed" in state.log)
    }

    @Test fun aReviveCutShortByClosingTheScreenIsNoFailure() {
        // Past setup's questions, so the check finds a phone set up with its
        // recorder down, and revives it in the background.
        shadowOf(context).grantPermissions(*Setup.missingRuntimePermissions(context).toTypedArray())
        DebugNotificationCleaner.choose(context, false)
        ShadowLog.clear()
        ran { vm.refreshSetup() }
        until("the revive") { state.starting }
        vm.viewModelScope.cancel()
        until("the view model's work to stop") { vm.viewModelScope.coroutineContext.job.isCompleted }
        val said = ShadowLog.getLogsForTag("JemRec").map { it.msg }
        assertFalse(said.toString(), "reviving the recorder failed" in said)
    }
}
