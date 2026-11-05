package com.jemcik.jemrec.capture

import android.content.Intent
import androidx.work.WorkInfo
import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.recorder.CableWatch
import com.jemcik.jemrec.recorder.DebugSwitchesOff
import com.jemcik.jemrec.recorder.RecorderSwitch
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * The recorder's CABLE wake (CallMonitorService, then CableWatch.watch), with
 * a recorder that answers (FakeRecorder): one that stays up through the
 * watch, and one that goes and is back for the first try at bringing it back.
 * The watch that cannot bring it back is in CallMonitorServiceTest.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class CableWakeTest {

    private val context = RuntimeEnvironment.getApplication()
    private lateinit var controller: ServiceController<CallMonitorService>
    private lateinit var work: HeldWork
    private var recorder: FakeRecorder? = null

    @Before fun aPhoneSetUpWithJemRecOn() {
        ShadowLog.clear()
        TestPhone.switchesOn(context)
        TestPhone.offWifi(context)
        DebugSwitchesOff.forget(context)
        RecorderSwitch.set(context, true)
        TestPhone.setUp(context)
        TestPhone.clearReviveLog(context)
        work = HeldWork(context)
        controller = Robolectric.buildService(CallMonitorService::class.java).create()
    }

    @After fun closeAll() {
        controller.destroy()
        recorder?.close()
        work.close()
    }

    /** As the recorder wakes the service (Main.wake), the token with it. */
    private fun unplugged() {
        val intent = Intent("com.jemcik.jemrec.CABLE")
            .setClassName(context, "com.jemcik.jemrec.capture.CallMonitorService")
            .putExtra("recfile", "")
            .putExtra("incoming", false)
            .putExtra("token", CaptureDaemon.token(context))
            .putExtra("cable", "unplugged")
        controller.get().onStartCommand(intent, 0, 1)
    }

    /** Polled every 10 ms; with [clock], Android's clock moved on with it -
     *  Robolectric's SystemClock stands still otherwise, and the watch times
     *  its window by it. */
    private fun eventually(what: String, seconds: Long = 20, clock: Boolean = false, done: () -> Boolean) {
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        while (!done()) {
            if (System.nanoTime() > deadline) fail("never happened: $what")
            Thread.sleep(10)
            if (clock) ShadowSystemClock.advanceBy(Duration.ofMillis(10))
        }
    }

    private fun logs() = ShadowLog.getLogsForTag("JemRec").map { it.msg }

    private fun cableWatch(): Job? =
        CallMonitorService::class.java.getDeclaredField("cableWatch").apply { isAccessible = true }.get(controller.get()) as Job?

    private val stopped get() = shadowOf(controller.get()).isStoppedBySelf

    private fun noted() = Prefs.of(context).contains("cable_stopped_adbd")

    @Test fun aRecorderThatStaysUpIsWatchedForTheWindowAndLeft() {
        recorder = FakeRecorder(context)
        val from = System.nanoTime()

        unplugged()
        eventually("the watch's end", clock = true) { cableWatch()?.isCompleted == true }

        // A look every half second for the eight seconds, then nothing to do.
        assertTrue((System.nanoTime() - from) / 1_000_000 >= CableWatch.WATCH_MS)
        assertTrue("cable: the recorder is unaffected" in logs())
        val log = TestPhone.reviveLog(context)
        assertEquals("cable: unplugged; watching", log[1])
        assertTrue(log.drop(2).all { it.startsWith("cable: ") && it.endsWith(" recorder up") })
        assertFalse(noted())
        assertTrue(recorder!!.heard.all { it.key == "P" })
        eventually("the service's stand-down") { stopped }
    }

    @Test fun aRecorderThatStaysGoneIsLeftToTheKeepAliveAndTheWifiWatch() {
        // Off Wi-Fi: three tries, each "not on Wi-Fi", three seconds apart.
        unplugged()
        eventually("the watch's end", seconds = 40) { cableWatch()?.isCompleted == true }

        val log = TestPhone.reviveLog(context)
        assertEquals(listOf("cable: attempt 1", "cable: attempt 2", "cable: attempt 3"), log.filter { it.startsWith("cable: attempt") })
        assertEquals("cable: not back after 3 attempts; keep-alive", log.last())
        // The same hand-over as the USB watch's: the Wi-Fi watch, off Wi-Fi,
        // and a kick; nothing else.
        assertEquals(1, TestPhone.wifiWatches(context).size)
        assertEquals(mapOf("for_wifi" to false), work.inputOf(work.unique(HeldWork.NOW).single().id))
        listOf(HeldWork.PERIODIC, HeldWork.WIFI, HeldWork.USB_WATCH).forEach {
            assertEquals(it, emptyList<WorkInfo>(), work.unique(it))
        }
        // The note stays for the next revive on Wi-Fi.
        assertTrue(noted())
    }

    @Test fun aRecorderThatGoesIsBroughtBackByTheFirstTry() {
        unplugged()
        // Gone at the first look; back - started over adbd, say - as the
        // first try's revive looks for it.
        eventually("the first try") { "cable: attempt 1" in TestPhone.reviveLog(context) }
        assertTrue("the note is written before the revive", noted())
        recorder = FakeRecorder(context)
        eventually("the watch's end") { cableWatch()?.isCompleted == true }

        val log = TestPhone.reviveLog(context)
        assertTrue(log.any { it.startsWith("cable: back (attempt 1, ") })
        assertEquals("revive: recorder up (connect NONE)", log[log.indexOfFirst { it.startsWith("cable: back") } - 1])
        assertTrue(logs().any { it.startsWith("cable: the recorder is back (attempt 1, ") })
        // Up, so the note goes with the revive.
        assertFalse(noted())
        eventually("the service's stand-down") { stopped }
    }
}
