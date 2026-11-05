package com.jemcik.jemrec.ui

import android.os.Looper
import androidx.lifecycle.viewModelScope
import com.google.common.util.concurrent.SettableFuture
import com.jemcik.jemrec.capture.HeldWork
import com.jemcik.jemrec.capture.TestPhone
import com.jemcik.jemrec.recorder.DebugSwitchesOff
import com.jemcik.jemrec.recorder.RecorderSwitch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import org.junit.After
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
import org.robolectric.shadows.ShadowLog

/**
 * Start without Wi-Fi flipped, and JemRec closed from Recents while its revive
 * runs: the revive goes on to its end (Revive.reviveLocked), so the screen
 * ending under it is no failure. On Wi-Fi to begin with, which Robolectric's
 * phone is by default, as a change of the port asks; off it once the revive is
 * asking for the recorder, so that it ends there and connects to nothing.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class PortChangeClosedTest {

    private val context = RuntimeEnvironment.getApplication()
    private lateinit var work: HeldWork
    private lateinit var vm: MainViewModel

    @Before fun aPhoneSetUpOnWifiWithJemRecOn() {
        TestPhone.switchesOn(context)
        DebugSwitchesOff.forget(context)
        RecorderSwitch.set(context, true)
        TestPhone.setUp(context)
        TestPhone.clearReviveLog(context)
        work = HeldWork(context)
        vm = MainViewModel(context, connectSession = { SettableFuture.create() })
    }

    /** Its work stopped and waited for, as MainViewModelRecorderTest's. */
    @After fun closeAll() {
        vm.viewModelScope.cancel()
        until("the view model's work to stop") { vm.viewModelScope.coroutineContext.job.isCompleted }
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

    @Test fun aPortChangeCutShortByClosingTheScreenIsNoFailure() {
        ShadowLog.clear()
        vm.setStartWithoutWifi(true)
        until("the revive") { "revive: start" in TestPhone.reviveLog(context) }
        TestPhone.offWifi(context)
        vm.viewModelScope.cancel()
        until("the view model's work to stop") { vm.viewModelScope.coroutineContext.job.isCompleted }
        val said = ShadowLog.getLogsForTag("JemRec").map { it.msg }
        assertFalse(said.toString(), "the port change's revive failed" in said)
    }
}
