package com.jemcik.jemrec.recorder

import androidx.work.ListenableWorker.Result
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.capture.FakeRecorder
import com.jemcik.jemrec.capture.HeldWork
import com.jemcik.jemrec.capture.RecorderKeepAlive
import com.jemcik.jemrec.capture.TestPhone
import com.jemcik.jemrec.capture.TestPhone.DEV
import com.jemcik.jemrec.capture.TestPhone.USB
import com.jemcik.jemrec.capture.TestPhone.WIFI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.concurrent.atomic.AtomicReference

/**
 * Turn all debugging off cut short - the process gone while the recorder
 * was being asked to go, after Off had recorded what it switches off and
 * before anything else (DebugSwitchesOff.switchOff). JemRec then says off
 * with debugging off; the next look at it must finish the off, not leave
 * debugging on and the recorder up for good.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class OffCutShortTest {

    private val context = RuntimeEnvironment.getApplication()
    private lateinit var work: HeldWork
    private lateinit var recorder: FakeRecorder

    @Before fun aRecorderUpWithJemRecOn() {
        TestPhone.switchesOn(context)
        TestPhone.offWifi(context)
        DebugSwitchesOff.forget(context)
        RecorderSwitch.set(context, true)
        TestPhone.setUp(context)
        TestPhone.clearReviveLog(context)
        work = HeldWork(context)
        recorder = FakeRecorder(context)
    }

    @After fun closeAll() {
        recorder.close()
        work.close()
    }

    private fun get(name: String) = TestPhone.get(context, name)

    private fun until(what: String, done: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!done()) {
            if (System.nanoTime() > deadline) fail("never happened: $what")
            Thread.sleep(10)
        }
    }

    @Test fun anOffCutShortWhileTheRecorderWasAskedIsFinishedByTheNextLook() = runBlocking {
        // The recorder takes a second and a half to answer the off...
        val asked = CompletableDeferred<Unit>()
        val firstOff = AtomicReference<Thread>()
        recorder.replies["O"] = "OFF NOW"
        recorder.stall = { key ->
            if (key == "O") {
                firstOff.set(Thread.currentThread())
                asked.complete(Unit)
                Thread.sleep(1_500)
            }
        }
        DebugSwitchesOff.setMode(context, OffMode.ALL_DEBUGGING_OFF)
        RecorderSwitch.set(context, false)
        // ...and the process goes meanwhile: Off cut short after its record
        // and Developer options, before the recorder's answer.
        val off = launch(Dispatchers.IO) { DebugSwitchesOff.switchOff(context) }
        withTimeout(5_000) { asked.await() }
        off.cancel()
        off.join()
        assertEquals(DebugSwitch.entries.toSet(), DebugSwitchesOff.switchedOff(context))
        assertEquals(listOf(0, 1, 1), listOf(get(DEV), get(WIFI), get(USB)))

        // The next look - a keep-alive run, standing down - finds the
        // recorder still up and never told: asked again, it goes, and
        // switches them off as it goes.
        recorder.stall = {}
        // On the second off's thread only: the first's, held above, may come
        // to its hook after this is set.
        recorder.after = { key ->
            if (key == "O" && Thread.currentThread() != firstOff.get()) {
                TestPhone.put(context, WIFI, 0)
                TestPhone.put(context, USB, 0)
                recorder.close()
            }
        }
        val result = TestListenableWorkerBuilder<RecorderKeepAlive>(context)
            .setInputData(workDataOf("for_wifi" to false))
            .setRunAttemptCount(0)
            .build()
            .doWork()
        assertEquals(Result.success(), result)

        until("all debugging off") { get(WIFI) == 0 && get(USB) == 0 }
        // Both asked it to go (1): asked again with a 0, a recorder cancels
        // the off and stays, and debugging stays on.
        assertEquals(listOf(listOf<Byte>(1), listOf<Byte>(1)), recorder.heard.filter { it.key == "O" }.map { it.payload.toList() })
        // Its line, for the revive log.
        assertEquals(
            "debugging off: asked the recorder again, it says OFF NOW",
            TestPhone.reviveLog(context).last { it.startsWith("debugging off:") },
        )
        // Seen off, nothing is left pending.
        DebugSwitchesOff.finishOffLeftUndone(context)
        assertEquals(false, Prefs.of(context).getBoolean("debug_off_pending", false))
    }
}
