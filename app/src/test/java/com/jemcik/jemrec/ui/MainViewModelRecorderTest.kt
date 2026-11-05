package com.jemcik.jemrec.ui

import android.os.Looper
import androidx.lifecycle.viewModelScope
import com.google.common.util.concurrent.SettableFuture
import com.jemcik.jemrec.capture.FakeRecorder
import com.jemcik.jemrec.capture.HeldWork
import com.jemcik.jemrec.capture.HeldWork.Companion.NOW
import com.jemcik.jemrec.capture.HeldWork.Companion.PERIODIC
import com.jemcik.jemrec.capture.HeldWork.Companion.USB_WATCH
import com.jemcik.jemrec.capture.TestPhone
import com.jemcik.jemrec.recorder.DebugSwitchesOff
import com.jemcik.jemrec.recorder.RecorderSwitch
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
     *  app goes (as ScreenTest does). */
    @After fun closeAll() {
        vm.viewModelScope.cancel()
        until("the view model's work to stop", seconds = 5) { vm.viewModelScope.coroutineContext.job.isCompleted }
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

    @Test fun openSettingsWhileTheScreenIsBusyIsDropped() {
        // Start recorder holds busy while it asks for a recorder that is not
        // there (about 2.4 s).
        vm.bootstrapDaemon()
        until("busy") { state.busy }
        // Setup's Open settings, meanwhile: its connect in the background.
        vm.reconnect()
        until("the first action's end") { !state.busy }
        repeat(30) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        // Pins today's behaviour, which looks like a bug: the connect is
        // dropped, not queued - nothing runs once the screen is free (to be
        // fixed in its own commit)
        assertFalse(state.busy)
    }

    @Test fun openSettingsWithTheScreenFreeStartsItsConnect() {
        vm.reconnect()
        // Its connect runs as an action, holding busy for as long as it looks.
        until("busy") { state.busy }
    }
}
