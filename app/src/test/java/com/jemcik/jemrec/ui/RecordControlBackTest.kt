package com.jemcik.jemrec.ui

import android.os.Looper
import androidx.lifecycle.viewModelScope
import com.google.common.util.concurrent.SettableFuture
import com.jemcik.jemrec.capture.HeldWork
import com.jemcik.jemrec.capture.TestPhone
import com.jemcik.jemrec.recorder.DebugSwitch
import com.jemcik.jemrec.recorder.DebugSwitchesOff
import com.jemcik.jemrec.recorder.OffMode
import com.jemcik.jemrec.recorder.RecorderSwitch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * The record control's Off and On, flipped while the screen is busy and
 * then left with Back - the activity finishing, and with it the view
 * model's scope (MainViewModel.setEnabled). The switch is saved at once;
 * what it switches has to follow even so, as the tile's does.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class RecordControlBackTest {

    private val context = RuntimeEnvironment.getApplication()
    private lateinit var work: HeldWork
    private lateinit var vm: MainViewModel

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

    @After fun closeAll() {
        vm.viewModelScope.cancel()
        until("the view model's work to stop", seconds = 5) { vm.viewModelScope.coroutineContext.job.isCompleted }
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

    /** Busy for a few seconds: Start recorder's revive, off Wi-Fi with no
     *  recorder to answer (about 2.5 s) - with JemRec on, or off with the
     *  recorder kept: off with all debugging, a revive stands down at once. */
    private fun busy() {
        vm.bootstrapDaemon()
        until("busy") { vm.state.value.busy }
        // Past its own gate, which reads the switch: flipped before it, the
        // revive would put debugging back itself.
        until("its revive past its gate") { "revive: start" in TestPhone.reviveLog(context) }
    }

    @Test fun anOffFlippedWhileBusyStillSwitchesDebuggingOffAfterBack() {
        DebugSwitchesOff.setMode(context, OffMode.ALL_DEBUGGING_OFF)
        busy()
        vm.setEnabled(false)
        assertFalse(RecorderSwitch.isOn(context))

        // Back.
        vm.viewModelScope.cancel()

        // The Off follows all the same: all debugging off, and recorded.
        until("all debugging off") { listOf(TestPhone.DEV, TestPhone.WIFI, TestPhone.USB).all { TestPhone.get(context, it) == 0 } }
        assertEquals(DebugSwitch.entries.toSet(), DebugSwitchesOff.switchedOff(context))
    }

    @Test fun anOnFlippedWhileBusyStillPutsDebuggingBackAfterBack() = kotlinx.coroutines.runBlocking {
        DebugSwitchesOff.setMode(context, OffMode.HIDE_DEVELOPER_OPTIONS)
        RecorderSwitch.set(context, false)
        DebugSwitchesOff.switchOff(context)
        assertEquals(listOf(0, 0), listOf(TestPhone.DEV, TestPhone.WIFI).map { TestPhone.get(context, it) })
        busy()
        vm.setEnabled(true)

        vm.viewModelScope.cancel()

        until("Developer options and Wireless debugging back on") {
            listOf(TestPhone.DEV, TestPhone.WIFI).all { TestPhone.get(context, it) == 1 }
        }
        assertEquals(emptySet<DebugSwitch>(), DebugSwitchesOff.switchedOff(context))
    }
}
