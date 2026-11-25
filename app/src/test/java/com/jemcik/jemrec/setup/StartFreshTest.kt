package com.jemcik.jemrec.setup

import com.jemcik.jemrec.adb.AdbIdentity
import com.jemcik.jemrec.capture.CaptureDaemon
import com.jemcik.jemrec.capture.FakeRecorder
import com.jemcik.jemrec.capture.HeldWork
import com.jemcik.jemrec.capture.TestPhone
import com.jemcik.jemrec.recorder.DebugSwitchesOff
import com.jemcik.jemrec.recorder.OffMode
import com.jemcik.jemrec.recorder.NetworkRevive
import com.jemcik.jemrec.recorder.RecorderSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Start fresh (Setup.reset) beside a revive. It runs from the screen, whose
 * scope ends when JemRec is closed from Recents, and waits for the recorder's
 * lock behind a revive:
 * once begun, it has to finish - half a reset leaves a forgotten pairing
 * under a setup still marked complete. And it ends the recorder under that
 * lock, so a revive under way cannot start one after it.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class StartFreshTest {

    private val context = RuntimeEnvironment.getApplication()
    private lateinit var work: HeldWork

    /** Start fresh asks WorkManager to end the keep-alive: a test of its own
     *  has none, and the class passed only where an earlier one had set it up. */
    @Before fun workManager() {
        work = HeldWork(context)
    }

    @After fun closeWork() = work.close()

    @Test fun aStartFreshCancelledWhileItWaitsForTheLockStillFinishes() = runBlocking {
        TestPhone.switchesOn(context)
        TestPhone.offWifi(context)
        TestPhone.setUp(context)
        TestPhone.clearReviveLog(context)
        // JemRec off with Developer options hidden: the recorder kept, so a
        // revive goes on.
        DebugSwitchesOff.forget(context)
        DebugSwitchesOff.setMode(context, OffMode.HIDE_DEVELOPER_OPTIONS)
        RecorderSwitch.set(context, false)
        DebugSwitchesOff.switchOff(context)
        assertEquals(0, TestPhone.get(context, TestPhone.DEV))

        // A revive holds the recorder's lock, probing a recorder that is not
        // there (about 2.4 s)...
        val revive = async(Dispatchers.IO) { CaptureDaemon.revive(context) }
        withTimeout(5_000) { while ("revive: start" !in TestPhone.reviveLog(context)) delay(20) }
        // ...when Start fresh begins, and waits for it; then JemRec is closed
        // from Recents.
        val reset = launch(Dispatchers.IO) { Setup.reset(context) }
        delay(500)
        reset.cancel()
        reset.join()
        assertFalse(revive.await())

        // All of it done all the same: the pairing gone and setup with it,
        // Developer options back for setup's second step.
        assertFalse(AdbIdentity.exists(context))
        assertFalse(Setup.isComplete(context))
        assertEquals(1, TestPhone.get(context, TestPhone.DEV))
        assertTrue(DebugSwitchesOff.switchedOff(context).isEmpty())
    }

    @Test fun aStartFreshAsksTheRecorderToQuitOnlyOnceARevivesStartIsDone() = runBlocking {
        TestPhone.switchesOn(context)
        TestPhone.offWifi(context)
        TestPhone.setUp(context)
        TestPhone.clearReviveLog(context)
        DebugSwitchesOff.forget(context)
        RecorderSwitch.set(context, true)
        // Two offs that took the recorder, the guard's note: Start fresh
        // forgets it (row 29).
        NetworkRevive.noteOffLoss(context)
        NetworkRevive.noteOffLoss(context)
        assertTrue(NetworkRevive.wirelessKeepsAdbd(context))
        // A recorder slow to answer the revive's first ping: the revive holds
        // the lock meanwhile, as one starting a recorder does.
        val recorder = FakeRecorder(context)
        val firstPing = AtomicBoolean(true)
        // Written on the recorder's threads, read on this one.
        val answeredAt = AtomicLong()
        val quitAt = AtomicLong()
        // And Keep JemRec paired's put back, asked under the same lock.
        val putBackAt = AtomicLong()
        recorder.replies["J"] = "PAIRING ok"
        // And "display over other apps", allowed for the button over calls -
        // which was switched off with no recorder up, so still allowed.
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
        recorder.replies["M"] = "OVERLAY ok"
        recorder.stall = { key ->
            if (key == "P" && firstPing.compareAndSet(true, false)) {
                Thread.sleep(1_500)
                answeredAt.set(System.nanoTime())
            }
            if (key == "J") putBackAt.set(System.nanoTime())
            if (key == "Q") quitAt.set(System.nanoTime())
        }
        try {
            val revive = async(Dispatchers.IO) { CaptureDaemon.revive(context) }
            withTimeout(5_000) { while ("revive: start" !in TestPhone.reviveLog(context)) delay(20) }
            val reset = async(Dispatchers.IO) { Setup.reset(context) }
            assertTrue(revive.await())
            assertTrue(reset.await().isSuccess)

            // Asked to quit after the revive had its answer, not while it waited.
            assertTrue(quitAt.get() > 0)
            assertTrue("quit at $quitAt, answered at $answeredAt", quitAt.get() > answeredAt.get())
            // Android's pairing timeout put back - a 0 byte - by the recorder
            // before it quit, and after the revive: under the lock too.
            assertTrue(recorder.heard.any { it.key == "J" && it.payload.contentEquals(byteArrayOf(0)) })
            assertTrue("put back at $putBackAt, answered at $answeredAt", putBackAt.get() > answeredAt.get())
            assertTrue("put back at $putBackAt, quit at $quitAt", putBackAt.get() < quitAt.get())
            // "Display over other apps" taken back before the quit.
            val keys = recorder.heard.map { it.key }
            assertTrue(recorder.heard.any { it.key == "M" && it.payload.contentEquals(byteArrayOf(0)) })
            assertTrue("$keys", keys.indexOf("M") in 0 until keys.lastIndexOf("Q"))
            assertFalse(AdbIdentity.exists(context))
            assertFalse("the guard's count forgotten", NetworkRevive.wirelessKeepsAdbd(context))
        } finally {
            recorder.close()
        }
    }
}
