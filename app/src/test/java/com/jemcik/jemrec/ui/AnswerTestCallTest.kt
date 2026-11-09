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

    /** The greeting, unreadable as it is sent to the recorder - deleted on a
     *  second JemRec as the test was armed, say: not sent, and nothing
     *  thrown. Thrown, it ended the app. */
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

    /** A screen watching a test while a newer one is armed elsewhere - a
     *  second JemRec stacked over it, gone since: its watch ends without
     *  taking the newer test, and watches that one on. */
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

    /** The greeting, unreadable as its preview starts - deleted on a second
     *  JemRec just then, say: no preview, and nothing thrown. */
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
