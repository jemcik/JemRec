package com.jemcik.jemrec.recorder

import android.app.NotificationManager
import androidx.work.ListenableWorker.Result
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.adb.AdbTransport
import com.jemcik.jemrec.capture.CaptureDaemon
import com.jemcik.jemrec.capture.HeldWork
import com.jemcik.jemrec.capture.RecorderKeepAlive
import com.jemcik.jemrec.capture.TestPhone
import com.jemcik.jemrec.capture.UsbDebuggingWatch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * The recorder-down alert, up when JemRec goes off with all debugging: each
 * way the lifecycle stands down then - a keep-alive run, the USB watch (which
 * USB debugging's going off wakes), a revive - takes it down, and its cause
 * with it, for a recorder down is no problem while JemRec stands down. None
 * of them reaches RecorderHealth.onRevive, which used to be the only way out.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class StandDownHealthTest {

    private val context = RuntimeEnvironment.getApplication()
    private val alerts = shadowOf(context.getSystemService(NotificationManager::class.java))
    private lateinit var work: HeldWork

    @Before fun theAlertUpThenOffWithAllDebugging() = runBlocking {
        TestPhone.switchesOn(context)
        TestPhone.offWifi(context)
        DebugSwitchesOff.forget(context)
        RecorderSwitch.set(context, true)
        TestPhone.setUp(context)
        work = HeldWork(context)
        // Down past the grace, the alert up.
        Prefs.of(context).edit().putLong("recorder_down_since", System.currentTimeMillis() - 3 * 60_000L).apply()
        RecorderHealth.onRevive(context, alive = false, connect = AdbTransport.ConnectOutcome.NONE)
        assertNotNull(alerts.getNotification(34))
        DebugSwitchesOff.setMode(context, OffMode.ALL_DEBUGGING_OFF)
        RecorderSwitch.set(context, false)
        DebugSwitchesOff.switchOff(context)
        // Off alone does not take it down: what stands down next does.
        assertNotNull(alerts.getNotification(34))
        TestPhone.clearReviveLog(context)
    }

    @After fun closeWorkManager() = work.close()

    private fun gone() {
        assertNull(alerts.getNotification(34))
        assertNull(RecorderHealth.cause(context))
        assertFalse(Prefs.of(context).contains("recorder_down_since"))
        // Nothing was revived: the recorder is gone by choice.
        assertEquals(emptyList<String>(), TestPhone.reviveLog(context).filter { it == "revive: start" })
    }

    @Test fun aKeepAliveRunTakesItDown() {
        val result = runBlocking {
            TestListenableWorkerBuilder<RecorderKeepAlive>(context)
                .setInputData(workDataOf("for_wifi" to false))
                .setRunAttemptCount(0)
                .build()
                .doWork()
        }
        assertEquals(Result.success(), result)
        gone()
    }

    @Test fun theUsbWatchTakesItDown() {
        val result = runBlocking { TestListenableWorkerBuilder<UsbDebuggingWatch>(context).build().doWork() }
        assertEquals(Result.success(), result)
        gone()
    }

    @Test fun aReviveTakesItDown() = runBlocking {
        assertFalse(CaptureDaemon.revive(context))
        gone()
    }
}
