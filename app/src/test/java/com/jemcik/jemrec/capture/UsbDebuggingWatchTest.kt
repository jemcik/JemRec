package com.jemcik.jemrec.capture

import android.net.Uri
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.WorkInfo
import androidx.work.testing.TestListenableWorkerBuilder
import com.jemcik.jemrec.capture.HeldWork.Companion.NOW
import com.jemcik.jemrec.capture.HeldWork.Companion.PERIODIC
import com.jemcik.jemrec.capture.HeldWork.Companion.USB_WATCH
import com.jemcik.jemrec.capture.HeldWork.Companion.WIFI
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * What wakes the app when the phone switches USB debugging off (see
 * UsbDebuggingWatch). Measured on the Honor: the switch-off kills the recorder,
 * and this trigger is the only thing that tells the app.
 *
 * Then what a run does, as it is: arm the next watch, ask setup, ask
 * whether JemRec is staying off, revive, and fall back on the Wi-Fi watch
 * and a kick. Off Wi-Fi with no recorder to answer, so a run that revives
 * takes about 2.5 s and finds the recorder down (TestPhone).
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class UsbDebuggingWatchTest {

    private val context = RuntimeEnvironment.getApplication()
    private lateinit var work: HeldWork

    @Before fun aPhoneOffWifiWithJemRecOn() {
        TestPhone.switchesOn(context)
        TestPhone.offWifi(context)
        DebugSwitchesOff.forget(context)
        RecorderSwitch.set(context, true)
        TestPhone.clearReviveLog(context)
        work = HeldWork(context)
    }

    @After fun closeWorkManager() = work.close()

    @Test fun itWatchesUsbDebuggingAndNothingElse() {
        val triggers = UsbDebuggingWatch.constraints().contentUriTriggers
        assertEquals(1, triggers.size)
        val trigger = triggers.single()
        assertEquals(Uri.parse("content://settings/global/adb_enabled"), trigger.uri)
        // Not Wireless debugging: Android writes that off at every Wi-Fi drop,
        // and with USB debugging on it stops nothing.
        assertFalse(trigger.isTriggeredForDescendants)
    }

    @Test fun itRunsAsSoonAsJobSchedulerAllows() {
        val c = UsbDebuggingWatch.constraints()
        // JobScheduler's floors (JobStatus.MIN_TRIGGER_UPDATE_DELAY and
        // MIN_TRIGGER_MAX_DELAY): anything lower is raised to them.
        assertEquals(500L, c.contentTriggerUpdateDelayMillis)
        assertEquals(1_000L, c.contentTriggerMaxDelayMillis)
        // Unset, they would be 10 s and 2 min - the time a call rings through.
        assertTrue(c.contentTriggerUpdateDelayMillis < 10_000L)
        assertTrue(c.contentTriggerMaxDelayMillis < 120_000L)
    }

    /** One run, as a change of USB debugging would start it. */
    private fun run(): Result = runBlocking {
        TestListenableWorkerBuilder<UsbDebuggingWatch>(context).build().doWork()
    }

    /** A watch armed and waiting on its trigger, as one is before a run. */
    private fun armed(): WorkInfo {
        UsbDebuggingWatch.arm(context)
        return work.unique(USB_WATCH).single()
    }

    /** The run armed the next watch behind the waiting one: APPEND_OR_REPLACE,
     *  where KEEP would have armed nothing and REPLACE cancelled it. */
    private fun assertArmedNextBehind(waiting: WorkInfo) {
        val now = work.unique(USB_WATCH)
        assertEquals(2, now.size)
        assertEquals(WorkInfo.State.ENQUEUED, now.single { it.id == waiting.id }.state)
        val next = now.single { it.id != waiting.id }
        assertEquals(WorkInfo.State.BLOCKED, next.state)
        assertEquals(UsbDebuggingWatch.constraints(), next.constraints)
    }

    private fun noOtherWork(vararg names: String) =
        names.forEach { assertEquals(it, emptyList<WorkInfo>(), work.unique(it)) }

    @Test fun armingIsKeepByDefault() {
        val first = armed()
        UsbDebuggingWatch.arm(context)
        assertEquals(listOf(first.id), work.unique(USB_WATCH).map { it.id })
        assertEquals(WorkInfo.State.ENQUEUED, first.state)
        // The policy its run re-arms with puts the next one in behind.
        UsbDebuggingWatch.arm(context, ExistingWorkPolicy.APPEND_OR_REPLACE)
        assertArmedNextBehind(first)
    }

    @Test fun notSetUpItOnlyArmsTheNextWatch() {
        val waiting = armed()
        assertEquals(Result.success(), run())
        // The next watch first, before setup is asked.
        assertArmedNextBehind(waiting)
        assertEquals(emptyList<String>(), TestPhone.reviveLog(context))
        noOtherWork(PERIODIC, NOW, WIFI)
        assertTrue(TestPhone.wifiWatches(context).isEmpty())
    }

    @Test fun stayingOffItArmsTheNextWatchAndStandsDown() = runBlocking {
        TestPhone.setUp(context)
        DebugSwitchesOff.setMode(context, OffMode.ALL_DEBUGGING_OFF)
        RecorderSwitch.set(context, false)
        DebugSwitchesOff.switchOff(context)
        TestPhone.clearReviveLog(context)
        val waiting = armed()

        assertEquals(Result.success(), run())

        assertArmedNextBehind(waiting)
        // No revive, so USB debugging stays off; no kick, no Wi-Fi watch.
        assertEquals(emptyList<String>(), TestPhone.reviveLog(context))
        assertEquals(0, TestPhone.get(context, TestPhone.USB))
        noOtherWork(PERIODIC, NOW, WIFI)
        assertTrue(TestPhone.wifiWatches(context).isEmpty())
    }

    @Test fun aFailedReviveOffWifiArmsTheWifiWatchAndKicksTheKeepAlive() {
        TestPhone.setUp(context)
        val waiting = armed()
        TestPhone.put(context, TestPhone.USB, 0)

        // Success whatever the revive found: the keep-alive owns the retrying.
        assertEquals(Result.success(), run())

        assertArmedNextBehind(waiting)
        val log = TestPhone.reviveLog(context)
        assertEquals(listOf("usbwatch: USB debugging changed", "revive: start"), log.take(2))
        assertEquals("revive: recorder down (connect NONE)", log.last())
        // The revive itself, not a kick: its armShield has USB debugging back.
        assertEquals(1, TestPhone.get(context, TestPhone.USB))
        assertEquals(1, TestPhone.wifiWatches(context).size)
        val kick = work.unique(NOW).single()
        assertEquals(mapOf("for_wifi" to false), work.inputOf(kick.id))
        noOtherWork(PERIODIC, WIFI)
    }
}
