package com.jemcik.jemrec.ui

import com.jemcik.jemrec.answering.AnswerDiagnostics
import com.jemcik.jemrec.capture.FakeRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The answering machine's test call (AnswerController.startTest) against
 * each of the recorder's answers to arming it, as it is: what the user is
 * told for each refusal, and the armed state for a yes. The recorder is a
 * FakeRecorder; the replies are the protocol's (protocol.txt, XA).
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class AnswerTestCallTest {

    private val context = RuntimeEnvironment.getApplication()
    private val recorder = FakeRecorder(context).apply { replies["N"] = "ANSWER 1,60,music" }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val said = CopyOnWriteArrayList<String>()
    private val controller = AnswerController(context, scope, { said += it })

    @After fun closeAll() {
        scope.cancel()
        recorder.close()
    }

    private fun eventually(what: String, done: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!done()) {
            if (System.nanoTime() > deadline) fail("never happened: $what")
            Thread.sleep(10)
        }
    }

    /** An outgoing test to a number that is no emergency's, answered [reply]. */
    private fun armedWith(reply: String?): String? {
        if (reply != null) recorder.replies["XA"] = reply
        controller.startTest(incoming = false, number = "+380441234567")
        eventually("the arming to be asked") { recorder.heard.any { it.key == "XA" } }
        eventually("an answer to show") { said.isNotEmpty() || controller.state.value.test != null }
        return said.singleOrNull()
    }

    @Test fun aBadNumber() = assertEquals("that number cannot be dialled", armedWith("TEST bad-number"))

    @Test fun anEmergencyNumber() =
        assertEquals("an emergency number is never called for a test", armedWith("TEST emergency"))

    @Test fun noGreetingYet() = assertEquals(
        "the recorder does not have your greeting yet - try again in a moment",
        armedWith("TEST no-greeting"),
    )

    @Test fun aTestStillGoingOn() = assertEquals("the last test call is still going on", armedWith("TEST busy"))

    @Test fun anythingElseIsACouldNot() {
        assertEquals("the recorder could not arm a test call", armedWith("TEST none"))
    }

    @Test fun aRecorderThatHangsUpCouldNotEither() {
        assertEquals("the recorder could not arm a test call", armedWith(null))
    }

    @Test fun armedSaysNothingAndShowsArmed() {
        assertNull(armedWith("TEST armed"))
        assertEquals("armed", controller.state.value.test)
        // The greeting and the settings first, then the arming.
        assertEquals(listOf("GQ", "N", "XA"), recorder.heard.map { it.key }.take(3))
    }

    /**
     * Armed, then the screen gone - JemRec closed from Recents - and the call
     * taken meanwhile: the recorder answers it either way, and the next
     * screen reads how it went. Only the screen that armed it was watching,
     * and the verdict, the other route on a failure and "Did they hear it?"
     * went with it.
     */
    @Test fun aTestArmedAsTheScreenWentIsReadByTheNext() {
        recorder.replies["XR"] = "RESULT 1 state=armed"
        assertNull(armedWith("TEST armed"))
        scope.cancel()
        recorder.replies["XR"] = "RESULT 1 state=done route=1 realms=4000 audioms=4000 tone=1 tonechunks=40 mute=1 voice=16 played=1"
        val nextScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val next = AnswerController(context, nextScope, { said += it })
            next.opened()
            eventually("the result read") { AnswerDiagnostics.results(context)["2G"] != null }
            assertEquals(AnswerDiagnostics.Verdict.WORKS, AnswerDiagnostics.results(context)["2G"])
            eventually("the test over") { next.state.value.test == null }
        } finally {
            nextScope.cancel()
        }
    }

    /** A screen of the answering machine over [scope], opened. */
    private fun opened(scope: CoroutineScope): AnswerController =
        AnswerController(context, scope, { said += it }).also { it.opened() }

    private val passed = "RESULT 1 state=done route=1 realms=4000 audioms=4000 tone=0 tonechunks=0 mute=1 voice=16 played=1"

    /** Read long after its window - the screen opened again minutes after the
     *  call - the result the recorder still holds is read: it was given up
     *  unread, "no test call came". */
    @Test fun aTestReadAfterItsWindowIsStillRead() {
        AnswerDiagnostics.noteTestArmed(context, true, System.currentTimeMillis() - 10 * 60_000L)
        recorder.replies["XR"] = passed
        val screen = opened(scope)
        eventually("the result read") { AnswerDiagnostics.results(context)["2G"] != null }
        eventually("the caller asked") { screen.state.value.askHeard == "2G" }
        assertFalse(said.toString(), said.any { "no test call came" in it })
    }

    /** Two screens open on one armed test - a second JemRec stacked by a
     *  notification - and one result: it is acted on once. */
    @Test fun twoScreensActOnOneResultOnce() {
        AnswerDiagnostics.noteTestArmed(context, true, System.currentTimeMillis() + 60_000L)
        recorder.replies["XR"] = "RESULT 1 state=done route=1 realms=4000 audioms=4000 tone=0 tonechunks=0 mute=0 voice=16 played=1"
        val other = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val one = opened(scope)
            val two = opened(other)
            eventually("both done") { one.state.value.test == null && two.state.value.test == null }
            Thread.sleep(500)
            assertEquals(said.toString(), 1, said.count { "did not stay muted" in it })
        } finally {
            other.cancel()
        }
    }

    /** Read in the background and the screen gone before "Did they hear
     *  it?" was answered: the next screen asks it. */
    @Test fun theQuestionWaitsForTheNextScreen() {
        AnswerDiagnostics.noteTestArmed(context, true, System.currentTimeMillis() + 60_000L)
        recorder.replies["XR"] = passed
        val first = opened(scope)
        eventually("asked") { first.state.value.askHeard == "2G" }
        scope.cancel()
        val other = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val next = opened(other)
            eventually("asked again") { next.state.value.askHeard == "2G" }
        } finally {
            other.cancel()
        }
    }

    /** A recorder that restarted forgot the armed test ("none"): the screen
     *  says so at once, rather than waiting out the window and then saying
     *  that no call came. */
    @Test fun aRecorderThatForgotTheTestEndsItAtOnce() {
        AnswerDiagnostics.noteTestArmed(context, true, System.currentTimeMillis() + 3 * 60_000L)
        recorder.replies["XR"] = "RESULT 1 state=none"
        val screen = opened(scope)
        eventually("the test called off") { said.any { "the recorder restarted" in it } }
        assertNull(screen.state.value.test)
    }
}
