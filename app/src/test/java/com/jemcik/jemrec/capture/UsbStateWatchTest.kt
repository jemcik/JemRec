package com.jemcik.jemrec.capture

import android.content.Intent
import android.os.Looper
import androidx.work.ListenableWorker.Result
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import com.jemcik.jemrec.capture.HeldWork.Companion.NOW
import com.jemcik.jemrec.capture.HeldWork.Companion.PERIODIC
import com.jemcik.jemrec.capture.HeldWork.Companion.STATE_WATCH
import com.jemcik.jemrec.capture.HeldWork.Companion.USB_STATE
import com.jemcik.jemrec.capture.HeldWork.Companion.USB_WATCH
import com.jemcik.jemrec.capture.HeldWork.Companion.WIFI
import com.jemcik.jemrec.recorder.CableWatch
import com.jemcik.jemrec.recorder.DebugSwitchesOff
import com.jemcik.jemrec.recorder.OffMode
import com.jemcik.jemrec.recorder.RecorderSwitch
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Android's USB_STATE, as JemRec's manifest receives it, and the cable's
 * watch it runs as a job (UsbStateReceiver, UsbStateWatch): what a USB mode
 * chosen with a computer's cable in now wakes. Measured on the four
 * emulators and the Honor, 2026-11-22 (tmp/usb-mode-change/0a and 0b): the
 * broadcast reaches JemRec with its process killed, three to a change on a
 * real gadget.
 *
 * Sent through Robolectric's broadcasts, so the manifest's own entry is what
 * receives it. Off Wi-Fi with no recorder to answer (TestPhone), as in
 * UsbDebuggingWatchTest: a run that looks finds the recorder gone, and its
 * revives fail as a phone's would with no Wi-Fi.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class UsbStateWatchTest {

    private val context = RuntimeEnvironment.getApplication()
    private lateinit var work: HeldWork

    @Before fun aPhoneOffWifiWithJemRecOn() {
        TestPhone.switchesOn(context)
        TestPhone.offWifi(context)
        DebugSwitchesOff.forget(context)
        RecorderSwitch.set(context, true)
        TestPhone.clearReviveLog(context)
        work = HeldWork(context)
        UsbStateWatch.unreadAppends = 0
    }

    @After fun closeWorkManager() = work.close()

    /** One USB_STATE, as Android sends it, delivered on the main looper. */
    private fun broadcast() {
        context.sendBroadcast(Intent(UsbStateReceiver.ACTION_USB_STATE).setPackage(context.packageName))
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** What is under the run's name, the network not reported met (a read
     *  through HeldWork would report it, and start what waits). */
    private fun waitingOrRunning(): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(USB_STATE).get()

    private fun noOtherWork(vararg names: String) =
        names.forEach { assertEquals(it, emptyList<WorkInfo>(), work.unique(it)) }

    @Test fun theManifestTakesTheBroadcastAndNothingElse() {
        TestPhone.setUp(context)
        context.sendBroadcast(Intent("android.hardware.usb.action.USB_DEVICE_ATTACHED").setPackage(context.packageName))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(emptyList<WorkInfo>(), waitingOrRunning())
        broadcast()
        assertEquals(1, waitingOrRunning().size)
    }

    @Test fun notSetUpABroadcastQueuesNothing() {
        broadcast()
        assertEquals(emptyList<WorkInfo>(), work.unique(USB_STATE))
        noOtherWork(PERIODIC, NOW, WIFI, USB_WATCH)
    }

    @Test fun setUpABroadcastQueuesTheCableWatchAskingTheKicksNetwork() {
        TestPhone.setUp(context)
        broadcast()
        val run = work.unique(USB_STATE).single()
        assertEquals(RecorderKeepAlive.kickConstraints(context), run.constraints)
        assertTrue(RecorderKeepAlive.asksNetwork(run.constraints))
        assertEquals(STATE_WATCH, work.started.single { it.id == run.id }.worker)
        // A broadcast arms nothing else: the run's hand-over is its own.
        noOtherWork(PERIODIC, NOW, WIFI, USB_WATCH)
    }

    @Test fun threeBroadcastsToAChangeQueueOneRunAndOneBehindIt() {
        TestPhone.setUp(context)
        // The Honor's three: configured, not configured, configured again.
        broadcast()
        val first = work.unique(USB_STATE).single()
        assertEquals(WorkInfo.State.RUNNING, first.state)
        broadcast()
        broadcast()
        val now = waitingOrRunning()
        // One behind the running one, not two: a chain is no queue.
        assertEquals(2, now.size)
        assertEquals(WorkInfo.State.RUNNING, now.single { it.id == first.id }.state)
        assertEquals(WorkInfo.State.BLOCKED, now.single { it.id != first.id }.state)
    }

    @Test fun aRunWaitingForItsNetworkTakesTheBroadcastsAfterIt() {
        TestPhone.setUp(context)
        repeat(3) { broadcast() }
        val waiting = waitingOrRunning()
        assertEquals(1, waiting.size)
        assertEquals(WorkInfo.State.ENQUEUED, waiting.single().state)
    }

    @Test fun withNoTimeLeftABroadcastAppendsOneRunNotOnePerBroadcast() {
        TestPhone.setUp(context)
        // A run going, as after the change's first broadcast.
        broadcast()
        val running = work.unique(USB_STATE).single()
        assertEquals(WorkInfo.State.RUNNING, running.state)
        // Broadcasts whose deadline has passed - a busy executor, a slow
        // process start on Android 12 and 13 - cannot read what is under the
        // name. The first appends behind the running run, where KEEP read from
        // nothing would have dropped it (review round 1); the rest keep, not
        // a run each (review round 2).
        repeat(3) { UsbStateWatch.queue(context, budgetMs = 0) }
        val now = waitingOrRunning()
        assertEquals(2, now.size)
        assertEquals(WorkInfo.State.BLOCKED, now.single { it.id != running.id }.state)
        // A read starts the count again.
        broadcast()
        assertEquals(0, UsbStateWatch.unreadAppends)
    }

    @Test fun aQueueingHeldUpLetsTheBroadcastGoAtTheDeadlineAndOnce() {
        TestPhone.setUp(context)
        // The keep-alive's executor busy past the deadline - a kick's getprop,
        // say: the broadcast is let go at the deadline, not when the queueing
        // gets its turn, which past ten seconds would be an ANR (review
        // round 2); and the queueing, when it comes, does not let it go twice.
        val held = mutableListOf<Runnable>()
        RecorderKeepAlive.looks = java.util.concurrent.Executor { held += it }
        var letGo = 0
        UsbStateWatch.queue(context, budgetMs = 50) { letGo++ }
        assertEquals(0, letGo)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(60))
        assertEquals(1, letGo)
        held.forEach { it.run() }
        assertEquals(1, letGo)
        // Queued all the same, the broadcast already let go.
        assertEquals(1, waitingOrRunning().size)
    }

    @Test fun aQueueingThatCannotBeHandedOverLetsTheBroadcastGoAtOnce() {
        TestPhone.setUp(context)
        RecorderKeepAlive.looks = java.util.concurrent.Executor { throw java.util.concurrent.RejectedExecutionException("shut") }
        var letGo = 0
        UsbStateWatch.queue(context) { letGo++ }
        // At once, not at the deadline: never finished, a broadcast is an ANR.
        assertEquals(1, letGo)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(UsbStateWatch.BUDGET_MS + 10))
        assertEquals(1, letGo)
    }

    /** One run, as a broadcast's queueing would start it. */
    private fun run(): Result = runBlocking {
        TestListenableWorkerBuilder<UsbStateWatch>(context).build().doWork()
    }

    @Test fun notSetUpARunLooksAtNothing() {
        assertEquals(Result.success(), run())
        assertEquals(emptyList<String>(), TestPhone.reviveLog(context))
        noOtherWork(PERIODIC, NOW, WIFI, USB_STATE)
    }

    @Test fun standingDownARunLooksAtNothingAndStartsNothing() = runBlocking {
        TestPhone.setUp(context)
        DebugSwitchesOff.setMode(context, OffMode.ALL_DEBUGGING_OFF)
        RecorderSwitch.set(context, false)
        DebugSwitchesOff.switchOff(context)
        TestPhone.clearReviveLog(context)

        assertEquals(Result.success(), run())

        // No look, no revive: USB debugging stays off, nothing is armed.
        assertEquals(emptyList<String>(), TestPhone.reviveLog(context))
        assertEquals(0, TestPhone.get(context, TestPhone.USB))
        noOtherWork(PERIODIC, NOW, WIFI)
        assertTrue(TestPhone.wifiWatches(context).isEmpty())
    }

    @Test fun aRecorderGoneIsWatchedRevivedAndHandedOverAsAfterACable() {
        TestPhone.setUp(context)

        // Success whatever it found: the keep-alive owns the retrying.
        assertEquals(Result.success(), run())

        val log = TestPhone.reviveLog(context)
        assertEquals("cable: USB state changed; watching", log.first())
        assertTrue(log.toString(), log[1].startsWith("cable: ") && log[1].endsWith(" ms in, recorder gone"))
        // The cable's watch itself: its three tries, then the hand-over.
        assertEquals(CableWatch.ATTEMPTS, log.count { it.startsWith("cable: attempt ") })
        assertEquals("cable: not back after ${CableWatch.ATTEMPTS} attempts; keep-alive", log.last())
        // Not the cable's note here: adbd does not read stopped on the
        // stand-in phone (CableWatch.notesStoppedAdbd, review rounds 1 and 2).
        assertFalse(CableWatch.stoppedAdbd(context))
        // Handed over as the cable's is, off Wi-Fi: the Wi-Fi watch and a kick.
        assertEquals(1, TestPhone.wifiWatches(context).size)
        val kick = work.unique(NOW).single()
        assertEquals(mapOf("for_wifi" to false), work.inputOf(kick.id))
        noOtherWork(PERIODIC, WIFI)
    }
}
