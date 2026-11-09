package com.jemcik.jemrec.ui

import com.jemcik.jemrec.answering.AnswerDiagnostics
import com.jemcik.jemrec.answering.Greeting
import com.jemcik.jemrec.capture.CaptureDaemon
import com.jemcik.jemrec.capture.FakeRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.runBlocking

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
        // The one-arming flag is the process's: an arming a failed test left
        // in flight would refuse the next test's.
        eventually("no arming in flight") { !testArming.get() }
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
        eventually("an answer to show") { said.isNotEmpty() || controller.state.value.test == "armed" }
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

    /** Sent, and no word back: it may have armed all the same - an outgoing
     *  test's call going out, an incoming one's next caller answered - and
     *  the screen says so, rather than that it could not. Nothing the
     *  recorder says later tells which arming a result is, so no test is
     *  watched, rather than one read with another test's result. */
    @Test fun aRecorderThatHangsUpMayHaveArmed() {
        recorder.replies.remove("XA")
        assertEquals("the recorder did not answer, and may be placing the call all the same; how it goes is not read", armedWith(null))
        assertNull(controller.state.value.test)
        assertNull(AnswerDiagnostics.pendingTest(context))
        said.clear()
        controller.startTest(incoming = true)
        eventually("an answer") { said.isNotEmpty() }
        assertEquals(
            listOf("the recorder did not answer, and may have armed the test all the same: a call in the next three minutes may be answered with your greeting, unread"),
            said.toList(),
        )
        assertNull(AnswerDiagnostics.pendingTest(context))
    }

    /** A test from before still to be read - its watch ended unanswered - is
     *  read before another is armed: armed by the recorder and not answered,
     *  the new one would leave the old entry to be read with its result. */
    @Test fun aTestStillToBeReadIsReadBeforeTheNext() {
        val older = System.currentTimeMillis() - 10 * 60_000L
        AnswerDiagnostics.noteTestArmed(context, true, older)
        recorder.replies["XR"] = passed
        recorder.replies["XA"] = "TEST armed"
        controller.startTest(incoming = true)
        eventually("the older one read") { AnswerDiagnostics.results(context)["2G"] != null }
        assertTrue(said.toString(), said.any { "read first" in it })
        // Long enough for an arming after the read to have reached it.
        Thread.sleep(500)
        assertFalse(recorder.heard.any { it.key == "XA" })
        // Read, the next one arms: the read let the one-arming flag go.
        eventually("the read over") { controller.state.value.test == null }
        controller.startTest(incoming = true)
        eventually("armed") { recorder.heard.any { it.key == "XA" } }
    }

    /** No recorder to send it to: nothing armed, and the screen says so. */
    @Test fun withNoRecorderNothingIsArmed() {
        recorder.close()
        controller.startTest(incoming = true)
        eventually("an answer") { said.isNotEmpty() }
        assertEquals(listOf("the recorder could not arm a test call"), said.toList())
        assertNull(controller.state.value.test)
        assertNull(AnswerDiagnostics.pendingTest(context))
    }

    /** The greeting, unreadable as it is sent to the recorder - gone between
     *  the look and the read, say: not sent, and nothing thrown. Thrown, it
     *  would end the app. */
    @Test fun aGreetingThatCannotBeReadIsNotSent() {
        Greeting.save(context, ByteArray(Greeting.RATE * 2))
        val file = Greeting.file(context)
        assertTrue(file.setReadable(false))
        try {
            runBlocking { CaptureDaemon.syncGreeting(context) }
            assertFalse(recorder.heard.any { it.key == "GP" })
        } finally {
            file.setReadable(true)
        }
    }

    @Test fun armedSaysNothingAndShowsArmed() {
        assertNull(armedWith("TEST armed"))
        assertEquals("armed", controller.state.value.test)
        // The greeting and the settings first, then the arming.
        assertEquals(listOf("GQ", "N", "XA"), recorder.heard.map { it.key }.take(3))
    }

    /** Arming takes the recorder a moment: the screen shows it at once - Delete
     *  greyed, the row taking no tap - and a second start meanwhile arms
     *  nothing more. */
    @Test fun armingShowsAtOnceAndArmsOnce() {
        val gate = CountDownLatch(1)
        recorder.stall = { if (it == "XA") gate.await(10, TimeUnit.SECONDS) }
        recorder.replies["XA"] = "TEST armed"
        controller.startTest(incoming = true)
        assertEquals("arming", controller.state.value.test)
        controller.startTest(incoming = true)
        // The second start sent nothing: by the first's arming, one greeting
        // check, where a second start would have sent its own.
        eventually("the arming asked") { recorder.heard.any { it.key == "XA" } }
        Thread.sleep(300)
        assertEquals(1, recorder.heard.count { it.key == "GQ" })
        gate.countDown()
        eventually("armed") { controller.state.value.test == "armed" }
        Thread.sleep(300)
        assertEquals(1, recorder.heard.count { it.key == "XA" })
    }

    /** The screen gone - JemRec closed from Recents - while the recorder was
     *  arming the test: it is kept all the same, for the next screen to
     *  read; the recorder answers its caller either way. */
    @Test fun aTestArmedAsTheScreenClosesIsKept() {
        val gate = CountDownLatch(1)
        recorder.stall = { if (it == "XA") gate.await(10, TimeUnit.SECONDS) }
        recorder.replies["XA"] = "TEST armed"
        controller.startTest(incoming = true)
        eventually("the arming asked") { recorder.heard.any { it.key == "XA" } }
        scope.cancel()
        gate.countDown()
        eventually("the test kept") { AnswerDiagnostics.pendingTest(context) is AnswerDiagnostics.PendingTest.Armed }
        // And the arming let the one-arming flag go with the screen gone - it
        // notes the test first, then lets go: the next screen is told to read
        // the kept test, not that one is arming.
        eventually("the arming done") { !testArming.get() }
        val other = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            AnswerController(context, other, { said += it }).startTest(incoming = true)
            eventually("told") { said.any { "read first" in it } }
            assertFalse(said.toString(), said.any { "being armed" in it })
        } finally {
            other.cancel()
        }
    }

    /** An outgoing test: the recorder starts the call before it answers, with
     *  Android's `am`, which it runs there with no time limit (its own wakes
     *  allow `am` ten seconds a try). The answer is waited for, and the test
     *  watched, rather than left unanswered as the call goes out and run
     *  unread. */
    @Test fun aSlowArmingOfAnOutgoingTestIsWaitedFor() {
        recorder.stall = { if (it == "XA") Thread.sleep(6_000) }
        assertNull(armedWith("TEST armed"))
        assertEquals("armed", controller.state.value.test)
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

    /** Two screens open on one armed test - a second JemRec window, which one
     *  window (0.30) no longer opens; this holds for whatever else reads the
     *  entry - and one result: it is acted on once. */
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
        eventually("the test called off") { said.any { "the recorder restarted" in it } && screen.state.value.test == null }
    }

    /** Read late, and the recorder not to be asked just then: the test is
     *  kept for the next opening, not called a test nobody rang. */
    @Test fun aLateReadTheRecorderDidNotAnswerKeepsTheTest() {
        AnswerDiagnostics.noteTestArmed(context, true, System.currentTimeMillis() - 10 * 60_000L)
        recorder.replies.remove("XR")
        val screen = opened(scope)
        eventually("the read given up") { said.any { "could not be asked" in it } && screen.state.value.test == null }
        assertFalse(said.toString(), said.any { "no test call came" in it })
        assertTrue(AnswerDiagnostics.pendingTest(context) is AnswerDiagnostics.PendingTest.Armed)
    }

    /** Answered once - the call under way - and the watch over before it
     *  ended: how it went is not known yet, so the test is kept for the next
     *  opening, not called a test nobody rang. */
    @Test fun aTestStillRunningAsTheWatchEndsIsKept() {
        AnswerDiagnostics.noteTestArmed(context, true, System.currentTimeMillis() - 10 * 60_000L)
        recorder.replies["XR"] = "RESULT 1 state=running"
        val screen = opened(scope)
        eventually("the watch over") { said.any { "still going on" in it } && screen.state.value.test == null }
        assertFalse(said.toString(), said.any { "no test call came" in it })
        assertTrue(AnswerDiagnostics.pendingTest(context) is AnswerDiagnostics.PendingTest.Armed)
    }

    /** Read again at the front while the recorder arms a new test - the
     *  dialer's call screen over JemRec, say - a test another screen keeps
     *  is not taken up: the arming stands, and that test is not said to have
     *  had no call. */
    @Test fun aReadWhileArmingLeavesTheArmingAlone() {
        recorder.replies["XR"] = "RESULT 1 state=armed"
        val gate = CountDownLatch(1)
        recorder.stall = { if (it == "XA") gate.await(10, TimeUnit.SECONDS) }
        recorder.replies["XA"] = "TEST armed"
        controller.startTest(incoming = true)
        eventually("the arming asked") { recorder.heard.any { it.key == "XA" } }
        AnswerDiagnostics.noteTestArmed(context, true, System.currentTimeMillis() - 10 * 60_000L)
        controller.readPendingTest()
        assertEquals("arming", controller.state.value.test)
        gate.countDown()
        eventually("armed") { controller.state.value.test == "armed" }
        Thread.sleep(500)
        assertFalse(said.toString(), said.any { "no test call came" in it })
        assertTrue(controller.state.value.testUntil > System.currentTimeMillis())
    }

    /** A screen watching a test while a newer one is armed elsewhere: its
     *  watch ends without taking the newer test, and watches that one on. */
    @Test fun aWatchWhoseTestWasReplacedWatchesTheNewOne() {
        val older = System.currentTimeMillis() - 10 * 60_000L
        AnswerDiagnostics.noteTestArmed(context, true, older)
        val gate = CountDownLatch(1)
        recorder.stall = { if (it == "XR") gate.await(10, TimeUnit.SECONDS) }
        recorder.replies["XR"] = "RESULT 1 state=armed"
        val screen = opened(scope)
        eventually("the recorder asked") { recorder.heard.any { it.key == "XR" } }
        val newer = System.currentTimeMillis() + 60_000L
        AnswerDiagnostics.noteTestArmed(context, true, newer)
        gate.countDown()
        eventually("the newer test watched") { screen.state.value.test == "armed" && screen.state.value.testUntil == newer }
        assertEquals(AnswerDiagnostics.PendingTest.Armed(true, newer), AnswerDiagnostics.pendingTest(context))
        assertFalse(said.toString(), said.any { "no test call came" in it })
    }

    /** ...and a result read as another screen had already taken it and
     *  armed a newer test: that one is watched on here too. */
    @Test fun aResultTakenElsewhereWithANewerTestArmedWatchesThatOne() {
        val older = System.currentTimeMillis() + 60_000L
        AnswerDiagnostics.noteTestArmed(context, true, older)
        val gate = CountDownLatch(1)
        recorder.stall = { if (it == "XR") gate.await(10, TimeUnit.SECONDS) }
        recorder.replies["XR"] = passed
        // The recorder armed for the newer test after it gave the result.
        recorder.after = { key -> if (key == "XR") recorder.replies["XR"] = "RESULT 1 state=armed" }
        val screen = opened(scope)
        eventually("the recorder asked") { recorder.heard.any { it.key == "XR" } }
        // The other screen: it read the result, put it down, and armed again.
        assertTrue(AnswerDiagnostics.takeArmedTest(context, older, asking = null))
        AnswerDiagnostics.record(context, "2G", AnswerDiagnostics.Verdict.FAILS)
        val newer = System.currentTimeMillis() + 3 * 60_000L
        AnswerDiagnostics.noteTestArmed(context, true, newer)
        gate.countDown()
        eventually("the newer test watched") { screen.state.value.test == "armed" && screen.state.value.testUntil == newer }
        assertEquals(AnswerDiagnostics.PendingTest.Armed(true, newer), AnswerDiagnostics.pendingTest(context))
        // Not put down again here, and shown as the other screen put it.
        assertEquals(AnswerDiagnostics.Verdict.FAILS, AnswerDiagnostics.results(context)["2G"])
        assertEquals(AnswerDiagnostics.Verdict.FAILS, screen.state.value.results["2G"])
    }

    /** A watch ending on a call still under way, its test taken meanwhile by
     *  another screen that armed a newer one: the newer one is watched on,
     *  not "still going on" said of a test no longer this screen's. */
    @Test fun aWatchEndingOnACallUnderWayFollowsANewerTest() {
        val older = System.currentTimeMillis() - 10 * 60_000L
        AnswerDiagnostics.noteTestArmed(context, true, older)
        val gate = CountDownLatch(1)
        recorder.stall = { if (it == "XR") gate.await(10, TimeUnit.SECONDS) }
        recorder.replies["XR"] = "RESULT 1 state=running"
        val screen = opened(scope)
        eventually("the recorder asked") { recorder.heard.any { it.key == "XR" } }
        val newer = System.currentTimeMillis() + 60_000L
        AnswerDiagnostics.noteTestArmed(context, true, newer)
        gate.countDown()
        eventually("the newer test watched") { screen.state.value.testUntil == newer && screen.state.value.test != null }
        assertFalse(said.toString(), said.any { "still going on" in it })
    }

    /** A watch ending unanswered, its test taken meanwhile by another screen
     *  that armed a newer one: the newer one is watched on, not "could not
     *  be asked" said of a test no longer this screen's. */
    @Test fun aWatchEndingUnansweredFollowsANewerTest() {
        AnswerDiagnostics.noteTestArmed(context, true, System.currentTimeMillis() - 10 * 60_000L)
        val gate = CountDownLatch(1)
        recorder.stall = { if (it == "XR") gate.await(10, TimeUnit.SECONDS) }
        recorder.replies.remove("XR")
        val screen = opened(scope)
        eventually("the recorder asked") { recorder.heard.any { it.key == "XR" } }
        val newer = System.currentTimeMillis() + 60_000L
        AnswerDiagnostics.noteTestArmed(context, true, newer)
        gate.countDown()
        eventually("the newer test watched") { screen.state.value.testUntil == newer && screen.state.value.test != null }
        assertFalse(said.toString(), said.any { "could not be asked" in it })
    }

    /** ...and taken there and asking its question, from a call under way here:
     *  the question is asked here too, and nothing said of this watch's
     *  test. */
    @Test fun aWatchEndingOnACallUnderWayShowsTheQuestionAskedElsewhere() {
        val older = System.currentTimeMillis() - 10 * 60_000L
        AnswerDiagnostics.noteTestArmed(context, true, older)
        val gate = CountDownLatch(1)
        recorder.stall = { if (it == "XR") gate.await(10, TimeUnit.SECONDS) }
        recorder.replies["XR"] = "RESULT 1 state=running"
        val screen = opened(scope)
        eventually("the recorder asked") { recorder.heard.any { it.key == "XR" } }
        assertTrue(AnswerDiagnostics.takeArmedTest(context, older, asking = "2G"))
        gate.countDown()
        eventually("asked here") { screen.state.value.askHeard == "2G" && screen.state.value.test == null }
        assertFalse(said.toString(), said.any { "still going on" in it })
    }

    /** ...and taken there and done with: nothing shown, nothing said. */
    @Test fun aWatchEndingUnansweredAfterTheTestWasDoneElsewhereSaysNothing() {
        val older = System.currentTimeMillis() - 10 * 60_000L
        AnswerDiagnostics.noteTestArmed(context, true, older)
        val gate = CountDownLatch(1)
        recorder.stall = { if (it == "XR") gate.await(10, TimeUnit.SECONDS) }
        recorder.replies.remove("XR")
        val screen = opened(scope)
        eventually("the recorder asked") { recorder.heard.any { it.key == "XR" } }
        assertTrue(AnswerDiagnostics.takeArmedTest(context, older, asking = null))
        gate.countDown()
        eventually("the watch over") { screen.state.value.test == null }
        Thread.sleep(300)
        assertFalse(said.toString(), said.any { "could not be asked" in it })
        assertNull(screen.state.value.askHeard)
    }

    /** One test armed at a time, whichever screen arms it - JemRec swiped
     *  away and opened again while the recorder was still arming, say: the
     *  entry kept is whichever answer came last, and the recorder's state
     *  whichever arming reached it last, so two at once could be filed under
     *  each other. */
    @Test fun oneArmingAtATimeAcrossScreens() {
        val gate = CountDownLatch(1)
        recorder.stall = { if (it == "XA") gate.await(10, TimeUnit.SECONDS) }
        recorder.replies["XA"] = "TEST armed"
        controller.startTest(incoming = true)
        eventually("the arming asked") { recorder.heard.any { it.key == "XA" } }
        val other = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val second = AnswerController(context, other, { said += it })
            second.startTest(incoming = true)
            assertTrue(said.toString(), said.any { "being armed" in it })
            assertNull(second.state.value.test)
            gate.countDown()
            eventually("armed") { controller.state.value.test == "armed" }
            Thread.sleep(300)
            assertEquals(1, recorder.heard.count { it.key == "XA" })
        } finally {
            other.cancel()
        }
    }

    /** Stopped, a preview can be played again at once: the stopped one held
     *  every tap off until its length was out. */
    @Test fun aStoppedPreviewPlaysAgain() {
        Greeting.save(context, ByteArray(Greeting.RATE * 2 * 10))
        val made = java.util.concurrent.atomic.AtomicInteger()
        val screen = AnswerController(context, scope, { said += it }, previewOf = { pcm -> made.incrementAndGet(); Greeting.preview(pcm) })
        screen.togglePreview()
        eventually("playing") { screen.state.value.playing }
        screen.togglePreview()
        assertFalse(screen.state.value.playing)
        screen.togglePreview()
        eventually("playing again") { screen.state.value.playing }
        assertEquals(2, made.get())
        screen.togglePreview()
    }

    /** A preview still being started when Record is tapped - its player not
     *  yet made - is not started: it would play over the loudspeaker into the
     *  take. */
    @Test fun aPreviewBeingStartedIsNotStartedUnderATake() {
        Greeting.save(context, ByteArray(Greeting.RATE * 2 * 3))
        val making = CountDownLatch(1)
        val made = CountDownLatch(1)
        val screen = AnswerController(context, scope, { said += it }, previewOf = { pcm ->
            making.countDown()
            made.await(10, TimeUnit.SECONDS)
            Greeting.preview(pcm)
        })
        screen.togglePreview()
        assertTrue(making.await(10, TimeUnit.SECONDS))
        screen.startTake()
        made.countDown()
        Thread.sleep(500)
        assertFalse(screen.state.value.playing)
    }

    /** A take stopped and still being processed takes a preview of the
     *  greeting there is: refused, the tap did nothing for up to half a
     *  minute, with no sign. */
    @Test fun aPreviewPlaysWhileATakeIsProcessed() {
        Greeting.save(context, ByteArray(Greeting.RATE * 2 * 3))
        val gate = CountDownLatch(1)
        recorder.stall = { if (it == "GT") gate.await(10, TimeUnit.SECONDS) }
        recorder.replies["GR"] = "TAKE recording"
        recorder.replies["GS"] = "TAKE 0"
        val made = java.util.concurrent.atomic.AtomicInteger()
        val screen = AnswerController(context, scope, { said += it }, previewOf = { pcm -> made.incrementAndGet(); Greeting.preview(pcm) })
        screen.startTake()
        eventually("recording") { screen.state.value.recordingTake }
        screen.stopTake()
        eventually("the take being fetched") { recorder.heard.any { it.key == "GT" } }
        screen.togglePreview()
        eventually("playing") { screen.state.value.playing }
        assertEquals(1, made.get())
        gate.countDown()
    }

    /** Stop tapped and not yet answered: the microphone may still be open,
     *  and a preview over the loudspeaker would end the new take. */
    @Test fun noPreviewBeforeTheRecorderAnswersStop() {
        Greeting.save(context, ByteArray(Greeting.RATE * 2 * 3))
        val gate = CountDownLatch(1)
        recorder.stall = { if (it == "GS") gate.await(10, TimeUnit.SECONDS) }
        recorder.replies["GR"] = "TAKE recording"
        recorder.replies["GS"] = "TAKE 0"
        val made = java.util.concurrent.atomic.AtomicInteger()
        val screen = AnswerController(context, scope, { said += it }, previewOf = { pcm -> made.incrementAndGet(); Greeting.preview(pcm) })
        screen.startTake()
        eventually("recording") { screen.state.value.recordingTake }
        screen.stopTake()
        eventually("the take asked to stop") { recorder.heard.any { it.key == "GS" } }
        screen.togglePreview()
        Thread.sleep(500)
        assertEquals(0, made.get())
        gate.countDown()
        eventually("answered") { !screen.state.value.takeListening }
    }

    /** A greeting that cannot be saved - a full disk - is said on its row:
     *  thrown, it ended the app. */
    @Test fun aGreetingThatCannotBeSavedIsSaidOnItsRow() {
        recorder.replies["GR"] = "TAKE recording"
        recorder.replies["GS"] = "TAKE 1"
        val rate = Greeting.RATE
        recorder.take = ShortArray(rate * 3) { i ->
            if (i in rate / 2 until rate * 5 / 2) (5000 * kotlin.math.sin(2 * Math.PI * 220 * i / rate)).toInt().toShort() else 0
        }.let { samples ->
            java.nio.ByteBuffer.allocate(samples.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply { samples.forEach { putShort(it) } }.array()
        }
        val thrown = CopyOnWriteArrayList<Throwable>()
        val watched = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t -> thrown += t })
        val dir = Greeting.file(context).parentFile!!
        try {
            val screen = AnswerController(context, watched, { said += it })
            screen.startTake()
            eventually("recording") { screen.state.value.recordingTake }
            assertTrue(dir.setWritable(false))
            screen.stopTake()
            eventually("said") { screen.state.value.greetingProblem != null }
            assertEquals("The greeting could not be saved. Is the phone's storage full?", screen.state.value.greetingProblem)
            assertEquals(emptyList<Throwable>(), thrown.toList())
        } finally {
            dir.setWritable(true)
            watched.cancel()
        }
    }

    /** The verdict's own words - a microphone that would not mute - are told
     *  on the test's screen, and only logged over another. */
    @Test fun aVerdictsWordsAreToldOnlyOnItsScreen() {
        recorder.replies["XR"] = "RESULT 1 state=done route=1 realms=4000 audioms=4000 tone=0 tonechunks=0 mute=0 voice=16 played=1"
        for (onIt in listOf(false, true)) {
            said.clear()
            val told = CopyOnWriteArrayList<String>()
            AnswerDiagnostics.noteTestArmed(context, true, System.currentTimeMillis() + 60_000L)
            val screen = AnswerController(context, scope, { said += it }, tell = { told += it }, shown = { onIt })
            screen.opened()
            eventually("read") { (said + told).any { "did not stay muted" in it } }
            assertEquals(onIt, told.any { "did not stay muted" in it })
        }
    }

    /** The screen left with a take recording - Back, a link: it is stopped on
     *  the recorder and not kept. It went on unseen, and a call played over
     *  the loudspeaker meanwhile became the greeting every caller hears. */
    @Test fun leavingTheScreenStopsATakeAndKeepsTheGreeting() {
        Greeting.save(context, ByteArray(Greeting.RATE * 2 * 3))
        val before = Greeting.file(context).readBytes()
        recorder.replies["GR"] = "TAKE recording"
        recorder.replies["GS"] = "TAKE 0"
        val screen = AnswerController(context, scope, { said += it })
        screen.startTake()
        eventually("recording") { screen.state.value.recordingTake }
        screen.left()
        eventually("stopped") { recorder.heard.any { it.key == "GS" } }
        assertFalse(screen.state.value.recordingTake)
        Thread.sleep(500)
        assertFalse("not fetched", recorder.heard.any { it.key == "GT" })
        assertTrue(Greeting.file(context).readBytes().contentEquals(before))
    }

    /** Stop not answered: the recorder may record on to its own limit, so
     *  the microphone is held - no preview into it. */
    @Test fun anUnansweredStopHoldsTheMicrophone() {
        Greeting.save(context, ByteArray(Greeting.RATE * 2 * 3))
        recorder.replies["GR"] = "TAKE recording"
        recorder.replies.remove("GS")
        val made = java.util.concurrent.atomic.AtomicInteger()
        val screen = AnswerController(context, scope, { said += it }, previewOf = { pcm -> made.incrementAndGet(); Greeting.preview(pcm) })
        screen.startTake()
        eventually("recording") { screen.state.value.recordingTake }
        screen.stopTake()
        eventually("the stop asked") { recorder.heard.any { it.key == "GS" } }
        eventually("past the stop") { recorder.heard.any { it.key == "GT" } }
        assertTrue(screen.state.value.takeListening)
        screen.togglePreview()
        Thread.sleep(300)
        assertEquals(0, made.get())
    }

    /** A take the recorder did not send is not called silence: "Nothing was
     *  heard" blamed the voice for a recorder restarted, or a socket cut. */
    @Test fun aTakeNotSentIsNotCalledSilence() {
        recorder.replies["GR"] = "TAKE recording"
        recorder.replies["GS"] = "TAKE 0"
        val screen = AnswerController(context, scope, { said += it })
        screen.startTake()
        eventually("recording") { screen.state.value.recordingTake }
        screen.stopTake()
        eventually("said") { screen.state.value.greetingProblem != null }
        assertEquals("The recorder did not send the take. Try again.", screen.state.value.greetingProblem)
    }

    /** A delete the recorder missed is said: it answered calls with the
     *  deleted greeting, without a word, until its next start. */
    @Test fun aDeleteTheRecorderMissedIsSaid() {
        Greeting.save(context, ByteArray(Greeting.RATE * 2 * 3))
        recorder.close()
        val told = CopyOnWriteArrayList<String>()
        val screen = AnswerController(context, scope, { said += it }, tell = { told += it })
        screen.deleteGreeting()
        eventually("told") { told.any { "drop the greeting when it next restarts" in it } }
    }

    /** A setting that did not reach the recorder is told on this screen, and
     *  only logged over another (tracking switched off in Settings turns the
     *  machine off from there). */
    @Test fun aSettingNotSentIsToldOnItsScreenOnly() {
        recorder.close()
        for (onIt in listOf(false, true)) {
            said.clear()
            val told = CopyOnWriteArrayList<String>()
            val screen = AnswerController(context, scope, { said += it }, tell = { told += it }, shown = { onIt })
            screen.setEnabled(false)
            eventually("said") { (said + told).any { "pick up the answering machine" in it } }
            assertEquals(onIt, told.any { "pick up the answering machine" in it })
        }
    }

    /** The screen gone while Record waits for the recorder's yes: told to
     *  stop all the same, where it was not. */
    @Test fun aTakeAskedForIsStoppedWithTheScreen() {
        val gate = CountDownLatch(1)
        recorder.stall = { if (it == "GR") gate.await(10, TimeUnit.SECONDS) }
        recorder.replies["GR"] = "TAKE recording"
        recorder.replies["GS"] = "TAKE 0"
        val screen = AnswerController(context, scope, { said += it })
        screen.startTake()
        eventually("asked") { recorder.heard.any { it.key == "GR" } }
        screen.close()
        eventually("told to stop") { recorder.heard.any { it.key == "GS" } }
        gate.countDown()
    }

    /** The screen gone with Stop not yet answered: told to stop again from a
     *  scope that outlives it - the first stop may never have been sent. */
    @Test fun aTakeBeingStoppedIsStoppedWithTheScreen() {
        val gate = CountDownLatch(1)
        recorder.stall = { if (it == "GS") gate.await(10, TimeUnit.SECONDS) }
        recorder.replies["GR"] = "TAKE recording"
        recorder.replies["GS"] = "TAKE 0"
        val screen = AnswerController(context, scope, { said += it })
        screen.startTake()
        eventually("recording") { screen.state.value.recordingTake }
        screen.stopTake()
        eventually("the stop asked") { recorder.heard.any { it.key == "GS" } }
        screen.close()
        eventually("asked again") { recorder.heard.count { it.key == "GS" } >= 2 }
        gate.countDown()
    }

    /** A take recording takes no preview either: the row's tap is gone then,
     *  but one landing as the recorder said yes still reached the greeting. */
    @Test fun noPreviewWhileATakeRecords() {
        Greeting.save(context, ByteArray(Greeting.RATE * 2 * 3))
        recorder.replies["GR"] = "TAKE recording"
        val made = java.util.concurrent.atomic.AtomicInteger()
        val screen = AnswerController(context, scope, { said += it }, previewOf = { pcm -> made.incrementAndGet(); Greeting.preview(pcm) })
        screen.startTake()
        eventually("recording") { screen.state.value.recordingTake }
        screen.togglePreview()
        Thread.sleep(500)
        assertEquals(0, made.get())
    }

    /** A take stopped and being made a greeting of: Delete then was undone
     *  when the new greeting was saved, the machine left off. It is refused
     *  meanwhile (and greyed), as Record is. */
    @Test fun noDeleteWhileATakeIsProcessed() {
        Greeting.save(context, ByteArray(Greeting.RATE * 2 * 3))
        com.jemcik.jemrec.answering.AnsweringMachine.setEnabled(context, true)
        val gate = CountDownLatch(1)
        recorder.stall = { if (it == "GS") gate.await(10, TimeUnit.SECONDS) }
        recorder.replies["GR"] = "TAKE recording"
        recorder.replies["GS"] = "TAKE 0"
        val screen = AnswerController(context, scope, { said += it })
        screen.startTake()
        eventually("recording") { screen.state.value.recordingTake }
        screen.stopTake()
        assertTrue(screen.state.value.processingTake)
        screen.deleteGreeting()
        assertTrue(Greeting.present(context))
        assertTrue(com.jemcik.jemrec.answering.AnsweringMachine.enabled(context))
        gate.countDown()
        eventually("processed") { !screen.state.value.processingTake }
    }

    /** The old greeting's preview, played while the new take was made one,
     *  ends as the new greeting is saved: the row tells of the new one, and
     *  said "Playing" over the old. */
    @Test fun aPreviewOfTheOldGreetingEndsWhenTheNewIsSaved() {
        Greeting.save(context, ByteArray(Greeting.RATE * 2 * 20))
        val gate = CountDownLatch(1)
        recorder.stall = { if (it == "GT") gate.await(10, TimeUnit.SECONDS) }
        recorder.replies["GR"] = "TAKE recording"
        recorder.replies["GS"] = "TAKE 1"
        // Half a second of room, two of speech, half a second of room.
        val rate = Greeting.RATE
        recorder.take = ShortArray(rate * 3) { i ->
            if (i in rate / 2 until rate * 5 / 2) (5000 * kotlin.math.sin(2 * Math.PI * 220 * i / rate)).toInt().toShort() else 0
        }.let { samples ->
            java.nio.ByteBuffer.allocate(samples.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply { samples.forEach { putShort(it) } }.array()
        }
        val screen = AnswerController(context, scope, { said += it })
        screen.startTake()
        eventually("recording") { screen.state.value.recordingTake }
        screen.stopTake()
        eventually("the take being fetched") { recorder.heard.any { it.key == "GT" } }
        screen.togglePreview()
        eventually("the old one playing") { screen.state.value.playing }
        gate.countDown()
        eventually("the new one saved") { (screen.state.value.greetingSeconds ?: 20.0) < 10.0 }
        assertFalse(screen.state.value.playing)
    }

    /** What answers a tap is told - a snackbar as well as the log's line;
     *  the checks run on opening are only logged: told, they were a snackbar
     *  on every opening and every turn of the phone while the recorder was
     *  down. */
    @Test fun theChecksAreToldWhenTappedAndOnlyLoggedOnOpening() {
        val told = CopyOnWriteArrayList<String>()
        val screen = AnswerController(context, scope, { said += it }, tell = { told += it })
        screen.opened()
        eventually("the checks run") { said.any { "checks need the recorder" in it } }
        assertFalse(told.toString(), told.any { "checks need the recorder" in it })
        eventually("the checks done") { !screen.state.value.checking }
        screen.runChecks()
        eventually("told") { told.any { "checks need the recorder" in it } }
    }

    /** A test call's ending is told on its screen, and only logged over
     *  another: a watch runs on while the user is elsewhere in JemRec. */
    @Test fun aTestEndingIsToldOnlyOnItsScreen() {
        recorder.replies["XR"] = "RESULT 1 state=armed"
        for (onIt in listOf(false, true)) {
            said.clear()
            val told = CopyOnWriteArrayList<String>()
            AnswerDiagnostics.noteTestArmed(context, true, System.currentTimeMillis() - 10 * 60_000L)
            val screen = AnswerController(context, scope, { said += it }, tell = { told += it }, shown = { onIt })
            screen.opened()
            eventually("ended") { said.any { "no test call came" in it } || told.any { "no test call came" in it } }
            assertEquals(onIt, told.any { "no test call came" in it })
        }
    }

    /** The carrier's answer to Wait 30 seconds, in words: until 0.30 its own
     *  words were said - "null", "error 3", "unchanged off 0". */
    @Test fun theCarriersAnswerIsSaidInWords() {
        assertNull(controller.carrierRefusal("set on 30 *8250"))
        assertEquals("no answer came from the recorder; ask your carrier again to see if it took", controller.carrierRefusal(null))
        assertEquals("your carrier's voicemail is off, so there was nothing to change", controller.carrierRefusal("unchanged off 0"))
        assertEquals("your carrier did not say when its voicemail answers, so nothing was changed", controller.carrierRefusal("unchanged none"))
        assertEquals("the phone's forwarding could not be read, so nothing was changed", controller.carrierRefusal("unchanged error 2"))
        assertEquals("no answer came in time", controller.carrierRefusal("error timeout"))
        // Not the carrier's doing: the phone's own setting, or the phone not
        // letting the recorder ask.
        assertEquals("the phone's fixed dialing numbers did not allow the change", controller.carrierRefusal("error 2"))
        assertEquals("the recorder could not make the change on this phone", controller.carrierRefusal("error InvocationTargetException"))
        assertEquals("the recorder could not make the change on this phone", controller.carrierRefusal("error no-telephony"))
        assertEquals("this phone or carrier does not take this change", controller.carrierRefusal("error 3"))
        assertEquals("the change did not go through, and no reason was given", controller.carrierRefusal("error 1"))
    }

    /** A take starting - the recorder asked, not yet recording - takes no
     *  preview: the greeting played over the loudspeaker into the new take. */
    @Test fun noPreviewWhileATakeStarts() {
        Greeting.save(context, ByteArray(Greeting.RATE * 2 * 3))
        val gate = CountDownLatch(1)
        recorder.stall = { if (it == "GR") gate.await(10, TimeUnit.SECONDS) }
        recorder.replies["GR"] = "TAKE recording"
        val made = java.util.concurrent.atomic.AtomicInteger()
        val screen = AnswerController(context, scope, { said += it }, previewOf = { pcm -> made.incrementAndGet(); Greeting.preview(pcm) })
        screen.startTake()
        eventually("the take asked") { recorder.heard.any { it.key == "GR" } }
        screen.togglePreview()
        Thread.sleep(500)
        assertEquals(0, made.get())
        assertFalse(screen.state.value.playing)
        gate.countDown()
    }

    /** Two taps on the greeting quicker than its file is read: one preview.
     *  Two were made, and the first - no longer the screen's - played on
     *  under the second and was never let go. */
    @Test fun twoQuickTapsMakeOnePreview() {
        Greeting.save(context, ByteArray(Greeting.RATE * 2 * 3))
        val made = java.util.concurrent.atomic.AtomicInteger()
        val screen = AnswerController(context, scope, { said += it }, previewOf = { pcm -> made.incrementAndGet(); Greeting.preview(pcm) })
        screen.togglePreview()
        screen.togglePreview()
        eventually("playing") { screen.state.value.playing }
        Thread.sleep(300)
        assertEquals(1, made.get())
    }

    /** The greeting, unreadable as its preview starts - gone between the look
     *  and the read, say: no preview, and nothing thrown. */
    @Test fun aGreetingThatCannotBeReadIsNotPreviewed() {
        Greeting.save(context, ByteArray(Greeting.RATE * 2))
        val file = Greeting.file(context)
        assertTrue(file.setReadable(false))
        val thrown = CopyOnWriteArrayList<Throwable>()
        val watched = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t -> thrown += t })
        try {
            val screen = AnswerController(context, watched, { said += it })
            screen.togglePreview()
            Thread.sleep(500)
            assertEquals(emptyList<Throwable>(), thrown.toList())
            assertFalse(screen.state.value.playing)
        } finally {
            watched.cancel()
            file.setReadable(true)
        }
    }

    /** Two screens watching one test as its result comes: the one that does
     *  not act on it shows the question the other asks. */
    @Test fun bothScreensWatchingShowTheQuestion() {
        AnswerDiagnostics.noteTestArmed(context, true, System.currentTimeMillis() + 60_000L)
        recorder.replies["XR"] = "RESULT 1 state=armed"
        val other = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val one = opened(scope)
            val two = opened(other)
            eventually("both watching") { recorder.heard.count { it.key == "XR" } >= 2 }
            recorder.replies["XR"] = passed
            eventually("both asking") { one.state.value.askHeard == "2G" && two.state.value.askHeard == "2G" }
        } finally {
            other.cancel()
        }
    }

    /** Only the test it watched is taken: a newer one, armed meanwhile, is
     *  the newer watch's. */
    @Test fun aWatchTakesOnlyItsOwnTest() {
        AnswerDiagnostics.noteTestArmed(context, true, 1_000L)
        assertFalse(AnswerDiagnostics.takeArmedTest(context, until = 2_000L, asking = null))
        assertTrue(AnswerDiagnostics.pendingTest(context) is AnswerDiagnostics.PendingTest.Armed)
        assertTrue(AnswerDiagnostics.takeArmedTest(context, until = 1_000L, asking = null))
        assertNull(AnswerDiagnostics.pendingTest(context))
    }

    /** "Did they hear it?" on two screens: answered on one, the other's
     *  answer changes nothing, and opened again it no longer asks. */
    @Test fun theQuestionIsAnsweredOnce() {
        AnswerDiagnostics.noteTestArmed(context, true, System.currentTimeMillis() + 60_000L)
        recorder.replies["XR"] = passed
        val one = opened(scope)
        eventually("asked") { one.state.value.askHeard == "2G" }
        val other = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val two = opened(other)
            eventually("asked there too") { two.state.value.askHeard == "2G" }
            one.heard("2G", heard = false)
            assertEquals(AnswerDiagnostics.Verdict.FAILS, AnswerDiagnostics.results(context)["2G"])
            // Answered there: the other screen's answer changes nothing ...
            two.heard("2G", heard = true)
            assertEquals(AnswerDiagnostics.Verdict.FAILS, AnswerDiagnostics.results(context)["2G"])
        } finally {
            other.cancel()
        }
    }

    /** ...and a screen still asking, opened again after the answer came on
     *  another, asks no more. */
    @Test fun aQuestionAnsweredElsewhereLeavesTheScreen() {
        AnswerDiagnostics.noteTestArmed(context, true, System.currentTimeMillis() + 60_000L)
        recorder.replies["XR"] = passed
        val one = opened(scope)
        eventually("asked") { one.state.value.askHeard == "2G" }
        val other = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val two = opened(other)
            eventually("asked there too") { two.state.value.askHeard == "2G" }
            one.heard("2G", heard = true)
            two.opened()
            assertNull(two.state.value.askHeard)
        } finally {
            other.cancel()
        }
    }

    /** Put aside, the question is not asked again on the next opening. */
    @Test fun theQuestionPutAsideIsNotAskedAgain() {
        AnswerDiagnostics.noteTestArmed(context, true, System.currentTimeMillis() + 60_000L)
        recorder.replies["XR"] = passed
        val one = opened(scope)
        eventually("asked") { one.state.value.askHeard == "2G" }
        one.dismissHeard()
        val other = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val next = opened(other)
            Thread.sleep(300)
            assertNull(next.state.value.askHeard)
        } finally {
            other.cancel()
        }
    }
}
