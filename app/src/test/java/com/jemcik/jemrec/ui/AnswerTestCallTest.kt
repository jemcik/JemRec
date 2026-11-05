package com.jemcik.jemrec.ui

import com.jemcik.jemrec.capture.FakeRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
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
}
