package com.jemcik.jemrec.capture

import androidx.work.ListenableWorker.Result
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.capture.HeldWork.Companion.KEEP_ALIVE
import com.jemcik.jemrec.capture.HeldWork.Companion.NOW
import com.jemcik.jemrec.capture.HeldWork.Companion.PERIODIC
import com.jemcik.jemrec.capture.HeldWork.Companion.USB_WATCH
import com.jemcik.jemrec.capture.HeldWork.Companion.WATCH
import com.jemcik.jemrec.capture.HeldWork.Companion.WIFI
import com.jemcik.jemrec.recorder.DebugSwitchesOff
import com.jemcik.jemrec.recorder.OffMode
import com.jemcik.jemrec.recorder.RecorderHealth
import com.jemcik.jemrec.recorder.RecorderSwitch
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

    private fun run(forWifi: Boolean = false, attemptsBefore: Int = 0, periodic: Boolean = false): Result = runBlocking {
        TestListenableWorkerBuilder<RecorderKeepAlive>(context)
            .setInputData(workDataOf("for_wifi" to forWifi))
            .setRunAttemptCount(attemptsBefore)
            .setTags(if (periodic) listOf(PERIODIC) else emptyList())
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
        // A run arms no other run (every name, the call's end's too).
        (HeldWork.ALL - HeldWork.USB_WATCH).forEach { assertEquals(it, emptyList<WorkInfo>(), work.unique(it)) }
    }

    /** The heartbeat down and not revivable: no retry, which WorkManager
     *  would run by its backoff - up to five hours off - not its 15 minutes. */
    @Test fun theHeartbeatOffWifiDoesNotTryAgain() {
        TestPhone.setUp(context)
        assertEquals(Result.success(), run(periodic = true, attemptsBefore = 6))
        assertEquals("revive: recorder down (connect NONE)", TestPhone.reviveLog(context).last())
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
        // Its runs told from the kicks' by its tag.
        assertTrue(PERIODIC in beat.tags)
        assertEquals(emptyMap<String, Any?>(), work.inputOf(beat.id))
        assertTrue(WATCH in watch.tags)
        assertEquals(WorkInfo.State.ENQUEUED, watch.state)
        assertEquals(UsbDebuggingWatch.constraints(context), watch.constraints)
        listOf(NOW, WIFI).forEach { assertEquals(it, emptyList<WorkInfo>(), work.unique(it)) }
    }

    /** A heartbeat queued before 1.2 - no tag, maybe hours into a backoff -
     *  is put anew once, and kept from then on. */
    @Test fun aHeartbeatFromBeforeIsPutAnewOnce() {
        val old = androidx.work.PeriodicWorkRequestBuilder<RecorderKeepAlive>(15, TimeUnit.MINUTES).build()
        androidx.work.WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC, androidx.work.ExistingPeriodicWorkPolicy.KEEP, old)
        RecorderKeepAlive.schedule(context)
        val beat = work.unique(PERIODIC).single { it.state != WorkInfo.State.CANCELLED }
        assertTrue(beat.id != old.id)
        assertTrue(PERIODIC in beat.tags)
        RecorderKeepAlive.schedule(context)
        assertEquals(beat.id, work.unique(PERIODIC).single { it.state != WorkInfo.State.CANCELLED }.id)
    }

    /** And without the app being opened: at the process's start - a run of
     *  the old heartbeat starts it - on a phone set up; on one not set up,
     *  nothing is queued. */
    @Test fun aHeartbeatFromBeforeIsPutAnewAtTheProcesssStart() {
        RecorderKeepAlive.renewOldHeartbeat(context)
        assertEquals(emptyList<WorkInfo>(), work.unique(PERIODIC))

        TestPhone.setUp(context)
        val old = androidx.work.PeriodicWorkRequestBuilder<RecorderKeepAlive>(15, TimeUnit.MINUTES).build()
        androidx.work.WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC, androidx.work.ExistingPeriodicWorkPolicy.KEEP, old)
        RecorderKeepAlive.renewOldHeartbeat(context)
        val beat = work.unique(PERIODIC).single { it.state != WorkInfo.State.CANCELLED }
        assertTrue(beat.id != old.id)
        assertTrue(PERIODIC in beat.tags)
        RecorderKeepAlive.renewOldHeartbeat(context)
        assertEquals(beat.id, work.unique(PERIODIC).single { it.state != WorkInfo.State.CANCELLED }.id)
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

    /** A kick waiting out a retry's backoff - hours off by its fifth, and
     *  kept through a restart - is replaced by the next kick, which runs:
     *  kept, it took the boot's, an open's and the cable's kicks for itself
     *  (review, 2026-11-18). One running is still never replaced (above). */
    @Test fun aKickWaitingOutItsBackoffIsReplaced() {
        val retry = androidx.work.OneTimeWorkRequestBuilder<RecorderKeepAlive>()
            .setInitialRunAttemptCount(4)
            .setInitialDelay(2, java.util.concurrent.TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, androidx.work.ExistingWorkPolicy.KEEP, retry).result.get()
        assertEquals(WorkInfo.State.ENQUEUED, work.unique(NOW).single().state)
        RecorderKeepAlive.kickNow(context)
        val now = work.unique(NOW).single { it.state != WorkInfo.State.CANCELLED }
        assertNotEquals(retry.id, now.id)
        assertEquals(WorkInfo.State.RUNNING, now.state)
        // A fresh kick waiting on nothing but its start is kept, as before.
        RecorderKeepAlive.kickNow(context)
        assertEquals(now.id, work.unique(NOW).single { it.state != WorkInfo.State.CANCELLED }.id)
    }

    /** A kick queued and not yet run - waiting on its network, or a delay -
     *  is kept: only one waiting out a retry's backoff is replaced. A
     *  REPLACE of every one waiting passed every test (review, 2026-11-18). */
    @Test fun aKickNotYetRunIsKept() {
        // Asking as a kick now asks: one whose ask no longer fits is replaced.
        val waiting = androidx.work.OneTimeWorkRequestBuilder<RecorderKeepAlive>()
            .setConstraints(RecorderKeepAlive.runConstraints(context, forWifi = false))
            .setInitialDelay(2, java.util.concurrent.TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, androidx.work.ExistingWorkPolicy.KEEP, waiting).result.get()
        RecorderKeepAlive.kickNow(context)
        assertEquals(listOf(waiting.id), work.unique(NOW).map { it.id })
    }

    /** Each run asks for a network: Android takes it - 127.0.0.1 with it -
     *  from an app idle in the rare standby bucket or below, and gives it
     *  back to a job only if it asks for one. Without it, in the restricted
     *  bucket on battery, the run started and its revive timed out at every
     *  step; with it, the recorder was back in 4 s (Android 12 and 17 AVDs,
     *  2026-11-18). */
    @Test fun everyRunAsksForANetwork() {
        RecorderKeepAlive.schedule(context)
        RecorderKeepAlive.kickNow(context)
        RecorderKeepAlive.kickForWifi(context)
        for (name in listOf(PERIODIC, NOW, WIFI, USB_WATCH)) {
            val each = work.unique(name).single()
            val request = each.constraints.requiredNetworkRequest
            assertTrue(name, request != null)
            // Any network: a Wi-Fi without internet is never validated, and
            // NetworkType.CONNECTED alone becomes JobScheduler's "any", which
            // asks for validated internet - the keep-alive never ran there
            // (the Android 12 AVD's dump, 2026-11-18).
            assertFalse(name, request!!.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            assertFalse(name, request.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
        }
    }

    /** Start without Wi-Fi on: no network asked, as its port needs none - a
     *  traveller with no Wi-Fi and mobile data off still gets calls, and a
     *  run waiting for a network never came. Switched, what is queued
     *  follows it (reviews, 2026-11-18). */
    @Test fun withStartWithoutWifiNoNetworkIsAsked() {
        com.jemcik.jemrec.recorder.StartWithoutWifi.setEnabled(context, true)
        try {
            RecorderKeepAlive.schedule(context)
            RecorderKeepAlive.kickNow(context)
            RecorderKeepAlive.kickForWifi(context)
            for (name in listOf(PERIODIC, USB_WATCH)) {
                assertEquals(name, null, work.unique(name).single().constraints.requiredNetworkRequest)
                assertEquals(name, androidx.work.NetworkType.NOT_REQUIRED, work.unique(name).single().constraints.requiredNetworkType)
            }
            // The Wi-Fi run always asks, and a kick with the port not open -
            // a revive needs a Wi-Fi then anyway.
            for (name in listOf(NOW, WIFI)) {
                assertTrue(name, work.unique(name).single().constraints.requiredNetworkRequest != null)
            }
            val beat = work.unique(PERIODIC).single().id
            // Switched off - in Settings, or by Start fresh: the heartbeat put
            // right in place (UPDATE: the same work, its period going on),
            // at the next open's heartbeat() too.
            com.jemcik.jemrec.recorder.StartWithoutWifi.setEnabled(context, false)
            RecorderKeepAlive.heartbeat(context)
            val now = work.unique(PERIODIC).single { it.state != WorkInfo.State.CANCELLED }
            assertEquals("updated, not put anew", beat, now.id)
            assertTrue(now.constraints.requiredNetworkRequest != null)
            RecorderKeepAlive.startWithoutWifiChanged(context)
            assertTrue(work.unique(USB_WATCH).single { it.state != WorkInfo.State.CANCELLED }.constraints.requiredNetworkRequest != null)
        } finally {
            com.jemcik.jemrec.recorder.StartWithoutWifi.setEnabled(context, false)
        }
    }

    /** A kick with Start without Wi-Fi's port open: none asked with no
     *  network there - only the port can be had - and a network asked with
     *  one, keeping its exemption (review, 2026-11-18). */
    @Test fun aKickAsksForNoNetworkOnlyWhereThePortAloneCanBeHad() {
        com.jemcik.jemrec.recorder.StartWithoutWifi.setEnabled(context, true)
        android.provider.Settings.Global.putInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 7)
        com.jemcik.jemrec.recorder.StartWithoutWifi.ensureOwnPort(context) { false }
        com.jemcik.jemrec.recorder.StartWithoutWifi.noteArmed(context)
        try {
            // A Wi-Fi as a phone's: not restricted, trusted - what the
            // request asks of any (the test phone starts with none).
            val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
            val wifi = org.robolectric.shadows.ShadowNetwork.newInstance(1)
            @Suppress("DEPRECATION")
            org.robolectric.Shadows.shadowOf(cm).addNetwork(
                wifi,
                org.robolectric.shadows.ShadowNetworkInfo.newInstance(
                    android.net.NetworkInfo.DetailedState.CONNECTED, android.net.ConnectivityManager.TYPE_WIFI, 0, true,
                    android.net.NetworkInfo.State.CONNECTED,
                ),
            )
            val caps = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
            for (c in RecorderKeepAlive.anyNetwork().capabilities) org.robolectric.Shadows.shadowOf(caps).addCapability(c)
            org.robolectric.Shadows.shadowOf(cm).setNetworkCapabilities(wifi, caps)
            assertTrue("a network there", RecorderKeepAlive.runConstraints(context, forWifi = false).requiredNetworkRequest != null)
            org.robolectric.Shadows.shadowOf(cm).clearAllNetworks()
            assertEquals(null, RecorderKeepAlive.runConstraints(context, forWifi = false).requiredNetworkRequest)
            assertTrue("the Wi-Fi run always asks", RecorderKeepAlive.runConstraints(context, forWifi = true).requiredNetworkRequest != null)
        } finally {
            com.jemcik.jemrec.recorder.StartWithoutWifi.forget(context, stillOpen = false)
        }
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

    /** JemRec opened with no network at all: its kick asks for none -
     *  loopback is never taken from an app in front - where one that waited
     *  for a network held back the reconcile and auto-delete (review round
     *  9). Any other kick asks, Start without Wi-Fi off. */
    @Test fun theOpensKickWithNoNetworkAsksForNone() {
        com.jemcik.jemrec.recorder.StartWithoutWifi.setEnabled(context, false)
        assertFalse(RecorderKeepAlive.asksNetwork(RecorderKeepAlive.runConstraints(context, forWifi = false, inFront = true)))
        assertTrue(RecorderKeepAlive.asksNetwork(RecorderKeepAlive.runConstraints(context, forWifi = false)))
        RecorderKeepAlive.kickNow(context, inFront = true)
        assertFalse(RecorderKeepAlive.asksNetwork(work.unique(NOW).single().constraints))
    }

    /** Any kick made while JemRec is in front asks for none with no
     *  network - the resume's check's too, not the open's alone: it asked,
     *  and replaced the open's (review round 10). */
    @Test fun anyKickMadeInFrontWithNoNetworkAsksForNone() {
        com.jemcik.jemrec.recorder.StartWithoutWifi.setEnabled(context, false)
        val was = RecorderKeepAlive.inFrontNow
        try {
            RecorderKeepAlive.inFrontNow = { true }  // HeldWork's default is the background
            com.jemcik.jemrec.recorder.RecorderLifecycle.arm(context, com.jemcik.jemrec.recorder.Trigger.SETUP_CHECK)
            assertFalse(RecorderKeepAlive.asksNetwork(work.unique(NOW).single().constraints))
        } finally {
            RecorderKeepAlive.inFrontNow = was
        }
    }

    /** Whether JemRec is in front is read where the run's ask is made, on
     *  the kicks' own thread, once - not on the caller's, where it is a
     *  binder call on the main thread (review round 11; round 12: no test
     *  held it, HeldWork runs the kicks' thread inline). */
    @Test fun whetherInFrontIsReadOnTheKicksThreadOnce() {
        val held = java.util.concurrent.LinkedBlockingQueue<Runnable>()
        val wasLooks = RecorderKeepAlive.looks
        val wasFront = RecorderKeepAlive.inFrontNow
        var asked = 0
        try {
            RecorderKeepAlive.looks = java.util.concurrent.Executor { held += it }
            RecorderKeepAlive.inFrontNow = { asked++; false }
            RecorderKeepAlive.kickNow(context)
            assertEquals("not on the caller's thread", 0, asked)
            val until = System.currentTimeMillis() + 5_000
            while (asked == 0 && System.currentTimeMillis() < until) {
                held.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS)?.run()
            }
            while (true) held.poll()?.run() ?: break
            assertEquals("once", 1, asked)
        } finally {
            RecorderKeepAlive.looks = wasLooks
            RecorderKeepAlive.inFrontNow = wasFront
        }
    }

    /** A kick waiting on a network that went is replaced by one that asks
     *  for none: kept, it absorbed every kick after it (review round 9). */
    @Test fun aKickWhoseNetworkAskNoLongerFitsIsReplaced() {
        com.jemcik.jemrec.recorder.StartWithoutWifi.setEnabled(context, false)
        val waiting = androidx.work.OneTimeWorkRequestBuilder<RecorderKeepAlive>()
            .setConstraints(RecorderKeepAlive.runConstraints(context, forWifi = false))
            .setInitialDelay(2, java.util.concurrent.TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, androidx.work.ExistingWorkPolicy.KEEP, waiting).result.get()
        RecorderKeepAlive.kickNow(context, inFront = true)
        val now = work.unique(NOW).single { it.state != WorkInfo.State.CANCELLED }
        assertNotEquals(waiting.id, now.id)
        assertFalse(RecorderKeepAlive.asksNetwork(now.constraints))
    }

    /** Two kicks close together over a retry waiting out its backoff end as
     *  one fresh kick: the second waits on the first's look, not replacing
     *  the first's fresh kick (`deciding`; review, 2026-11-18). */
    @Test fun twoKicksOverARetryEndAsOneFreshKick() {
        val retry = androidx.work.OneTimeWorkRequestBuilder<RecorderKeepAlive>()
            .setInitialRunAttemptCount(4)
            .setInitialDelay(2, java.util.concurrent.TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, androidx.work.ExistingWorkPolicy.KEEP, retry).result.get()
        val held = java.util.ArrayDeque<Runnable>()
        val direct = RecorderKeepAlive.looks
        RecorderKeepAlive.looks = java.util.concurrent.Executor { held.add(it) }
        try {
            RecorderKeepAlive.kickNow(context)
            RecorderKeepAlive.kickNow(context)
            while (held.isNotEmpty()) held.poll().run()
        } finally {
            RecorderKeepAlive.looks = direct
        }
        val live = work.unique(NOW).filter { it.state != WorkInfo.State.CANCELLED }
        assertEquals(1, live.size)
        assertNotEquals(retry.id, live.single().id)
    }
}
