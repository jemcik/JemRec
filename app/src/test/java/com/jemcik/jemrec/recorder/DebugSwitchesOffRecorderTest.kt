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
import java.util.concurrent.CopyOnWriteArrayList
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

    /** Threads that write as the recorder would, a moment after it replies:
     *  started before its line, and waited for before the test ends, so a
     *  write meant for this test never lands in the next one's settings. */
    private val helpers = CopyOnWriteArrayList<Thread>()

    @After fun gone() {
        helpers.forEach { it.join(12_000) }
        recorder.close()
    }

    private fun pending() = Prefs.of(context).getBoolean("debug_off_pending", false)
    private fun heard() = recorder.heard.map { it.key to it.payload.toList() }
    private fun get(name: String) = TestPhone.get(context, name)

    /** The recorder writes both switches off, as it does at OFF NOW. They
     *  are written here as it replies, before its line, so they have landed
     *  when the app looks: the recorder writes them just after it, and
     *  written after the line, they could land before the app's look or
     *  after it. After it is offNowWaitsForTheSwitchesTheRecorderIsWriting.
     *  The recorder then goes, its write having stopped adbd; here it stays
     *  up until the test ends, so a ping the app had no need to send is
     *  heard - closed at once, it went to a port no one answered. */
    private fun goesOffAtOnce() {
        recorder.stall = { key ->
            if (key == "O") {
                TestPhone.put(context, WIFI, 0)
                TestPhone.put(context, USB, 0)
            }
        }
    }

    @Test fun offNowTheRecorderSwitchesThemOffAndGoes() = runBlocking {
        recorder.replies["O"] = "OFF NOW"
        goesOffAtOnce()
        val writes = TestPhone.Writes(context)

        DebugSwitchesOff.switchOff(context)

        // Asked to go (1), and nothing after: seen off at once, the app does
        // not ask whether it is still there. Developer options by the app,
        // the other two by the recorder; nothing is left pending.
        assertEquals(listOf("O" to listOf<Byte>(1)), heard())
        assertEquals(listOf(DEV, WIFI, USB), writes.seen())
        assertFalse(pending())
        assertEquals(listOf("debugging off: the recorder says OFF NOW"), TestPhone.reviveLog(context))
        assertEquals(DebugSwitch.entries.toSet(), DebugSwitchesOff.switchedOff(context))
        writes.close()
    }

    /** OFF NOW, and the switches still on when the app looks - the recorder
     *  writes them just after it replies: the app waits while the recorder
     *  answers, and once they have landed nothing is left pending, and the
     *  recorder is not asked again. */
    @Test fun offNowWaitsForTheSwitchesTheRecorderIsWriting() = runBlocking {
        recorder.replies["O"] = "OFF NOW"
        // Written once the app has looked, found them on, and asked whether
        // the recorder is still there; then it goes. Never asked, it writes
        // nothing and ends: the test has failed.
        recorder.stall = { key ->
            if (key == "O") {
                helpers += thread(isDaemon = true) {
                    val deadline = System.nanoTime() + 10_000_000_000L
                    while (recorder.heard.none { it.key == "P" }) {
                        if (System.nanoTime() > deadline) return@thread
                        Thread.sleep(10)
                    }
                    TestPhone.put(context, WIFI, 0)
                    TestPhone.put(context, USB, 0)
                    recorder.close()
                }
            }
        }
        val writes = TestPhone.Writes(context)

        DebugSwitchesOff.switchOff(context)

        assertEquals("O" to listOf<Byte>(1), heard().first())
        assertTrue(heard().toString(), heard().size > 1 && heard().drop(1).all { it.first == "P" })
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
        recorder.stall = { key ->
            if (key == "O") {
                helpers += thread(isDaemon = true) {
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

        // It may be waiting out the call still: left alone - asked again to
        // go (1), as it was first.
        DebugSwitchesOff.finishOffLeftUndone(context)
        assertEquals(listOf(listOf<Byte>(1), listOf<Byte>(1)), recorder.heard.filter { it.key == "O" }.map { it.payload.toList() })
        assertEquals(Admit.StandDown, RecorderLifecycle.admit(context, Trigger.REVIVE))

        assertEquals(emptyList<String>(), writes.seen())
        assertTrue(pending())
        assertEquals(listOf(1, 1), listOf(get(WIFI), get(USB)))
        writes.close()
    }
}
