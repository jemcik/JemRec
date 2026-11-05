package com.jemcik.jemrec.recorder

import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.capture.FakeRecorder
import com.jemcik.jemrec.capture.TestPhone
import com.jemcik.jemrec.capture.TestPhone.DEV
import com.jemcik.jemrec.capture.TestPhone.USB
import com.jemcik.jemrec.capture.TestPhone.WIFI
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
import kotlin.concurrent.thread

/**
 * Turn all debugging off, and On after it, with a recorder that answers
 * (FakeRecorder): what the app does with each of the recorder's replies to
 * its off (DebugSwitchesOff.switchOff, switchBackOn, finishOffLeftUndone), as
 * it is today. DebugSwitchesOffStateTest has no recorder at all.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class DebugSwitchesOffRecorderTest {

    private val context = RuntimeEnvironment.getApplication()
    private lateinit var recorder: FakeRecorder

    @Before fun aRecorderUpWithJemRecGoingOffWithAllDebugging() {
        TestPhone.switchesOn(context)
        DebugSwitchesOff.forget(context)
        RecorderSwitch.set(context, true)
        TestPhone.clearReviveLog(context)
        recorder = FakeRecorder(context)
        DebugSwitchesOff.setMode(context, OffMode.ALL_DEBUGGING_OFF)
        RecorderSwitch.set(context, false)
    }

    @After fun gone() = recorder.close()

    private fun pending() = Prefs.of(context).getBoolean("debug_off_pending", false)
    private fun heard() = recorder.heard.map { it.key to it.payload.toList() }
    private fun get(name: String) = TestPhone.get(context, name)

    /** The recorder writes both switches off, as it does at OFF NOW, and goes. */
    private fun goesOffAtOnce() {
        recorder.after = { key ->
            if (key == "O") {
                TestPhone.put(context, WIFI, 0)
                TestPhone.put(context, USB, 0)
                recorder.close()
            }
        }
    }

    @Test fun offNowTheRecorderSwitchesThemOffAndGoes() = runBlocking {
        recorder.replies["O"] = "OFF NOW"
        goesOffAtOnce()
        val writes = TestPhone.Writes(context)

        DebugSwitchesOff.switchOff(context)

        // Asked to go (1); Developer options by the app, the other two by the
        // recorder; seen off at once, so nothing is left pending.
        assertEquals(listOf("O" to listOf<Byte>(1)), heard())
        assertEquals(listOf(DEV, WIFI, USB), writes.seen())
        assertFalse(pending())
        assertEquals(listOf("debugging off: the recorder says OFF NOW"), TestPhone.reviveLog(context))
        assertEquals(DebugSwitch.entries.toSet(), DebugSwitchesOff.switchedOff(context))
        writes.close()
    }

    @Test fun offAfterCallLeavesTheSwitchesToTheRecorder() = runBlocking {
        recorder.replies["O"] = "OFF AFTER CALL"
        val writes = TestPhone.Writes(context)

        DebugSwitchesOff.switchOff(context)

        // Only Developer options now; the recorder writes the rest after the
        // call, and an off it never finishes is the app's (pending).
        assertEquals(listOf(DEV), writes.seen())
        assertEquals(listOf(1, 1), listOf(get(WIFI), get(USB)))
        assertTrue(pending())
        assertEquals(listOf("debugging off: the recorder says OFF AFTER CALL"), TestPhone.reviveLog(context))
        writes.close()
    }

    @Test fun anyOtherOffReplyIsAnAnswerThatLeavesThemAsTheyAre() = runBlocking {
        recorder.replies["O"] = "OFF CANCELLED"
        val writes = TestPhone.Writes(context)

        DebugSwitchesOff.switchOff(context)

        // An answer, so the app does not write them itself; not one that says
        // the recorder will, so nothing is pending either.
        assertEquals(listOf(DEV), writes.seen())
        assertFalse(pending())
        assertEquals(listOf("debugging off: the recorder says OFF CANCELLED"), TestPhone.reviveLog(context))
        writes.close()
    }

    @Test fun aReplyThatIsNotAnOffIsNoAnswer() = runBlocking {
        recorder.replies["O"] = "BUSY"
        val writes = TestPhone.Writes(context)

        DebugSwitchesOff.switchOff(context)

        // As with no recorder: the app writes Wireless, then USB debugging.
        assertEquals(listOf(DEV, WIFI, USB), writes.seen())
        assertFalse(pending())
        assertEquals(listOf("debugging off: the recorder says nothing"), TestPhone.reviveLog(context))
        writes.close()
    }

    @Test fun aRecorderThatHangsUpOnTheOffIsNoAnswer() = runBlocking {
        // One from before the command hangs up on it.
        val writes = TestPhone.Writes(context)
        DebugSwitchesOff.switchOff(context)
        assertEquals(listOf(DEV, WIFI, USB), writes.seen())
        assertEquals(listOf("debugging off: the recorder says nothing"), TestPhone.reviveLog(context))
        writes.close()
    }

    @Test fun onCallsOffAnOffStillWaitingThenPutsThemBack() = runBlocking {
        recorder.replies["O"] = "OFF AFTER CALL"
        DebugSwitchesOff.switchOff(context)
        recorder.replies["O"] = "OFF CANCELLED"
        recorder.heard.clear()
        TestPhone.clearReviveLog(context)
        RecorderSwitch.set(context, true)
        // The recorder never wrote them: the call is still up.
        assertEquals(listOf(0, 1, 1), listOf(get(DEV), get(WIFI), get(USB)))
        val writes = TestPhone.Writes(context)

        DebugSwitchesOff.switchBackOn(context)

        assertEquals(listOf("O" to listOf<Byte>(0)), heard())
        // USB and Wireless debugging were never off: written, they change
        // nothing an observer sees; Developer options comes back.
        assertEquals(listOf(DEV), writes.seen())
        assertEquals(1, get(DEV))
        assertTrue(DebugSwitchesOff.switchedOff(context).isEmpty())
        assertFalse(pending())
        assertEquals(
            listOf("debugging back on: USB_DEBUGGING, WIRELESS_DEBUGGING, DEVELOPER_OPTIONS (the recorder said OFF CANCELLED)"),
            TestPhone.reviveLog(context),
        )
        writes.close()
    }

    @Test fun onTooLateWaitsForTheRecorderToBeGone() = runBlocking {
        recorder.replies["O"] = "OFF AFTER CALL"
        DebugSwitchesOff.switchOff(context)
        RecorderSwitch.set(context, true)
        // The call ended and the recorder is writing them off as On comes:
        // it says so, and goes a second and a half later.
        recorder.replies["O"] = "OFF TOO LATE"
        recorder.after = { key ->
            if (key == "O") {
                thread {
                    Thread.sleep(1_500)
                    TestPhone.put(context, WIFI, 0)
                    TestPhone.put(context, USB, 0)
                    recorder.close()
                }
            }
        }
        val from = System.nanoTime()

        DebugSwitchesOff.switchBackOn(context)

        // Its writes landed first, and On's after them: the switches end on.
        assertTrue((System.nanoTime() - from) / 1_000_000 >= 1_500)
        assertFalse(recorder.isOpen)
        assertEquals(listOf(1, 1, 1), listOf(get(USB), get(WIFI), get(DEV)))
        assertTrue(DebugSwitchesOff.switchedOff(context).isEmpty())
    }

    @Test fun anOffLeftUndoneWaitsWhileTheRecorderAnswers() = runBlocking {
        recorder.replies["O"] = "OFF AFTER CALL"
        DebugSwitchesOff.switchOff(context)
        assertTrue(pending())
        val writes = TestPhone.Writes(context)

        // It may be waiting out the call still: left alone.
        DebugSwitchesOff.finishOffLeftUndone(context)
        assertEquals(Admit.StandDown, RecorderLifecycle.admit(context, Trigger.REVIVE))

        assertEquals(emptyList<String>(), writes.seen())
        assertTrue(pending())
        assertEquals(listOf(1, 1), listOf(get(WIFI), get(USB)))
        writes.close()
    }
}
