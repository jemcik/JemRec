package com.jemcik.jemrec.capture

import androidx.work.ListenableWorker.Result
import androidx.work.WorkInfo
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.capture.HeldWork.Companion.KEEP_ALIVE
import com.jemcik.jemrec.capture.HeldWork.Companion.NOW
import com.jemcik.jemrec.capture.HeldWork.Companion.PERIODIC
import com.jemcik.jemrec.capture.HeldWork.Companion.USB_WATCH
import com.jemcik.jemrec.capture.HeldWork.Companion.WATCH
import com.jemcik.jemrec.capture.HeldWork.Companion.WIFI
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.concurrent.TimeUnit

/**
 * The keep-alive's run (RecorderKeepAlive.doWork) and what schedules it, as
 * they are: the gates in their order, what a run arms, and when it tries
 * again. Off Wi-Fi with no recorder to answer, so a run that gets as far as
 * the revive takes about 2.5 s and finds the recorder down (TestPhone).
 * What a run does with the recorder up - the reconcile, then the missed
 * calls - needs a recorder, or a seam in front of the revive.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class RecorderKeepAliveWorkTest {

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

    private fun run(forWifi: Boolean = false, attemptsBefore: Int = 0): Result = runBlocking {
        TestListenableWorkerBuilder<RecorderKeepAlive>(context)
            .setInputData(workDataOf("for_wifi" to forWifi))
            .setRunAttemptCount(attemptsBefore)
            .build()
            .doWork()
    }

    /** Retention.runIfDue ran: the day's pass stamps when it last ran. */
    private fun retentionRan() = Prefs.of(context).getLong("auto_delete_last_run", 0L) > 0L

    private fun nothingEnqueued() = HeldWork.ALL.forEach { assertEquals(it, emptyList<WorkInfo>(), work.unique(it)) }

    private fun offWithAllDebugging() = runBlocking {
        DebugSwitchesOff.setMode(context, OffMode.ALL_DEBUGGING_OFF)
        RecorderSwitch.set(context, false)
        DebugSwitchesOff.switchOff(context)
        TestPhone.clearReviveLog(context)
    }

    @Test fun notSetUpItDoesNothingAtAll() {
        assertEquals(Result.success(), run())
        assertFalse(retentionRan())
        assertEquals(emptyList<String>(), TestPhone.reviveLog(context))
        nothingEnqueued()
        assertTrue(TestPhone.wifiWatches(context).isEmpty())
    }

    @Test fun setUpIsAskedBeforeStayingOff() {
        // A record without setup: the first gate answers, so not even the
        // day's auto-delete, which staying off still runs.
        offWithAllDebugging()
        assertEquals(Result.success(), run())
        assertFalse(retentionRan())
        assertEquals(emptyList<String>(), TestPhone.reviveLog(context))
    }

    @Test fun stayingOffItStandsDownAndOnlyTheAutoDeleteRuns() {
        TestPhone.setUp(context)
        offWithAllDebugging()
        assertEquals(Result.success(), run())
        assertTrue(retentionRan())
        // No run logged, no revive, no watch armed, USB debugging left off.
        assertEquals(emptyList<String>(), TestPhone.reviveLog(context))
        nothingEnqueued()
        assertTrue(TestPhone.wifiWatches(context).isEmpty())
        assertEquals(0, TestPhone.get(context, TestPhone.USB))
    }

    @Test fun aKickOffWifiRevivesArmsBothWatchesAndTriesAgain() {
        TestPhone.setUp(context)
        // A watch already waiting is left alone (KEEP).
        UsbDebuggingWatch.arm(context)
        val watch = work.unique(USB_WATCH).single().id
        TestPhone.put(context, TestPhone.USB, 0)

        // Down and not revivable off Wi-Fi: a kick tries again with backoff.
        assertEquals(Result.retry(), run())

        val log = TestPhone.reviveLog(context)
        assertEquals(listOf("keepalive: run (attempt 1)", "revive: start"), log.take(2))
        assertTrue("revive: not on Wi-Fi" in log)
        assertEquals("revive: recorder down (connect NONE)", log.last())
        assertEquals(listOf(watch), work.unique(USB_WATCH).map { it.id })
        // Off Wi-Fi is when the Wi-Fi watch is armed.
        assertEquals(1, TestPhone.wifiWatches(context).size)
        assertTrue(retentionRan())
        // The revive's armShield, set up: USB debugging back on.
        assertEquals(1, TestPhone.get(context, TestPhone.USB))
        // And RecorderHealth heard of it.
        assertEquals(RecorderHealth.Problem.NEEDS_WIFI, RecorderHealth.cause(context))
        // A run arms no other run.
        listOf(PERIODIC, NOW, WIFI).forEach { assertEquals(it, emptyList<WorkInfo>(), work.unique(it)) }
    }

    @Test fun theWifiRunOffWifiDoesNotTryAgain() {
        TestPhone.setUp(context)
        // The watch armed again wakes the next one (triesAgain).
        assertEquals(Result.success(), run(forWifi = true, attemptsBefore = 2))
        val log = TestPhone.reviveLog(context)
        assertEquals("keepalive: Wi-Fi run (attempt 3)", log.first())
        assertEquals("revive: recorder down (connect NONE)", log.last())
        assertEquals(1, TestPhone.wifiWatches(context).size)
    }

    @Test fun scheduleArmsTheWatchAndAQuarterHourHeartbeatOnce() {
        RecorderKeepAlive.schedule(context)
        val beat = work.unique(PERIODIC).single()
        val watch = work.unique(USB_WATCH).single()
        // Every app open schedules: KEEP, or the period would start over
        // each time and never elapse.
        RecorderKeepAlive.schedule(context)
        assertEquals(listOf(beat.id), work.unique(PERIODIC).map { it.id })
        assertEquals(listOf(watch.id), work.unique(USB_WATCH).map { it.id })
        assertEquals(TimeUnit.MINUTES.toMillis(15), beat.periodicityInfo?.repeatIntervalMillis)
        assertTrue(KEEP_ALIVE in beat.tags)
        assertEquals(emptyMap<String, Any?>(), work.inputOf(beat.id))
        assertTrue(WATCH in watch.tags)
        assertEquals(WorkInfo.State.ENQUEUED, watch.state)
        assertEquals(UsbDebuggingWatch.constraints(), watch.constraints)
        listOf(NOW, WIFI).forEach { assertEquals(it, emptyList<WorkInfo>(), work.unique(it)) }
    }

    @Test fun aKickUnderWayTakesTheNextKick() {
        RecorderKeepAlive.kickNow(context)
        val kick = work.unique(NOW).single()
        assertEquals(WorkInfo.State.RUNNING, kick.state)
        assertEquals(mapOf("for_wifi" to false), work.inputOf(kick.id))
        // KEEP, not REPLACE: replacing cancelled a run mid-way.
        RecorderKeepAlive.kickNow(context)
        assertEquals(listOf(kick.id), work.unique(NOW).map { it.id })
        assertTrue(KEEP_ALIVE in kick.tags)
        // A kick arms nothing else.
        listOf(PERIODIC, WIFI, USB_WATCH).forEach { assertEquals(it, emptyList<WorkInfo>(), work.unique(it)) }
    }

    @Test fun theWifiRunHasANameOfItsOwnSoAWaitingKickCannotTakeItsPlace() {
        RecorderKeepAlive.kickNow(context)
        RecorderKeepAlive.kickForWifi(context)
        val kick = work.unique(NOW).single()
        val wifiRun = work.unique(WIFI).single()
        assertNotEquals(kick.id, wifiRun.id)
        assertEquals(mapOf("for_wifi" to true), work.inputOf(wifiRun.id))
        assertTrue(KEEP_ALIVE in wifiRun.tags)
        // Only an earlier Wi-Fi run is taken for it.
        RecorderKeepAlive.kickForWifi(context)
        assertEquals(listOf(wifiRun.id), work.unique(WIFI).map { it.id })
    }
}
