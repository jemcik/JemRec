package com.jemcik.jemrec.capture

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The recorder watched through a call (RecorderWatch), on the test's clock. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RecorderWatchTest {

    private class World {
        var callUp = true
        /** Any mode but normal - a ring's too. */
        var anyCall: Boolean? = null
        var recorderUp = true
        var watching = true
        var reviveWorks = false
        /** How long each ask of the recorder takes: with it down, five pings
         *  ~2.4 s (DaemonLauncher.isRunningConfirmed). */
        var pingMs = 0L
        var revives = 0
        var stoppeds = 0
        /** Call starts the service heard: the revived recorder's own. */
        var starts = 0
        var overs = 0
        /** What the last end said of the recorder: up through it, or not. */
        var overUp: Boolean? = null
        /** A messenger call's: a ring's mode is the call too. */
        var ringIsTheCall = false
        val lines = mutableListOf<String>()

        /** Timed by the test's own clock ([now]: the TestScope's currentTime). */
        fun watch(now: () -> Long) = RecorderWatch(
            callUp = { callUp },
            anyCall = { anyCall ?: callUp },
            watching = { watching },
            recorderUp = { kotlinx.coroutines.delay(pingMs); recorderUp },
            revive = { revives++; if (reviveWorks) recorderUp = true; reviveWorks },
            stopped = { stoppeds++ },
            over = { overs++; overUp = it },
            log = { lines += it },
            starts = { starts },
            now = now,
            ringIsTheCall = { ringIsTheCall },
        )
    }

    private val look = RecorderWatch.LOOK_MS

    @Test fun aRecorderThatAnswersIsLeftAlone() = runTest {
        val w = World()
        val job = launch { w.watch { currentTime }.run() }
        advanceTimeBy(10 * look + 1)
        assertEquals(0, w.revives)
        assertEquals(0, w.stoppeds)
        job.cancel()
    }

    /** Gone mid-call: said and brought back at the next look; back, it is
     *  left alone again. */
    @Test fun aRecorderGoneMidCallIsSaidAndRevivedAtTheNextLook() = runTest {
        val w = World()
        val job = launch { w.watch { currentTime }.run() }
        advanceTimeBy(look + 1)
        w.recorderUp = false
        w.reviveWorks = true
        advanceTimeBy(look)
        runCurrent()
        assertEquals(1, w.stoppeds)
        assertEquals(1, w.revives)
        assertTrue("monitor: the recorder is back; it records the rest of the call" in w.lines)
        advanceTimeBy(10 * look)
        assertEquals("not again once back", 1, w.revives)
        job.cancel()
    }

    /** One that will not start is tried again every RETRY_MS, not every look. */
    @Test fun aRecorderThatWillNotStartIsTriedAgainEveryRetry() = runTest {
        val w = World().apply { recorderUp = false }
        val job = launch { w.watch { currentTime }.run() }
        advanceTimeBy(look + 1)
        assertEquals(1, w.revives)
        advanceTimeBy(RecorderWatch.RETRY_MS - 1)
        assertEquals(1, w.revives)
        advanceTimeBy(look + 2)
        assertEquals(2, w.revives)
        assertEquals(2, w.stoppeds)
        job.cancel()
    }

    /** A call that ends while a failed revive waits for the next is over
     *  after GONE_LOOKS looks, not after the wait: the wait was a sleep until
     *  the review, and the call's notification stayed up to 40 s after the
     *  hang-up (stopped_early, 2026-11-18). */
    @Test fun aCallEndedWhileARetryWaitsIsOverAfterItsLooks() = runTest {
        val w = World().apply { recorderUp = false }
        val job = launch { w.watch { currentTime }.run() }
        advanceTimeBy(look + 1)
        assertEquals(1, w.revives)
        w.callUp = false
        advanceTimeBy(RecorderWatch.GONE_LOOKS * look)
        assertEquals(1, w.overs)
        assertTrue(job.isCompleted)
        assertEquals("no revive for a call that is over", 1, w.revives)
    }

    /** The retry is timed, not counted in looks: with each ask of a recorder
     *  that is down taking 2.4 s, six looks were ~45 s, not 30 (review
     *  round 10). */
    @Test fun theRetryIsTimedWhateverALookTakes() = runTest {
        val w = World().apply { recorderUp = false; pingMs = 2_400 }
        val job = launch { w.watch { currentTime }.run() }
        advanceTimeBy(look + w.pingMs + 1)
        assertEquals(1, w.revives)
        advanceTimeBy(RecorderWatch.RETRY_MS + look + w.pingMs)
        assertEquals("again within a look of 30 s", 2, w.revives)
        job.cancel()
    }

    /** A recorder this watch brought back that found no call - the call
     *  ended as it came up - never says the end: over after GONE_LOOKS, not
     *  six looks of "Trying again" (review round 11). */
    @Test fun aRecorderBroughtBackAfterTheCallIsOverSoon() = runTest {
        val w = World().apply { recorderUp = false; reviveWorks = true }
        val job = launch { w.watch { currentTime }.run() }
        advanceTimeBy(look + 1)
        assertEquals(1, w.revives)
        w.callUp = false
        advanceTimeBy(RecorderWatch.GONE_LOOKS * look)
        assertEquals(1, w.overs)
        assertTrue(job.isCompleted)
    }

    /** A recorder brought back that has said the call's start says its end
     *  as any does: waited for as one up, not cut at GONE_LOOKS - its end,
     *  queued behind other wakes, came after and the part before was left
     *  (review round 12). */
    @Test fun aRevivedRecorderThatSaidTheStartIsWaitedForAsAnyUp() = runTest {
        val w = World().apply { recorderUp = false; reviveWorks = true }
        val job = launch { w.watch { currentTime }.run() }
        advanceTimeBy(look + 1)
        assertEquals(1, w.revives)
        w.starts++
        w.callUp = false
        advanceTimeBy(RecorderWatch.GONE_LOOKS * look)
        assertEquals("not yet", 0, w.overs)
        advanceTimeBy((RecorderWatch.GONE_LOOKS_RECORDER_UP - RecorderWatch.GONE_LOOKS) * look)
        assertEquals(1, w.overs)
        assertTrue(job.isCompleted)
    }

    /** A recorder up that has said the call records on through a waiting
     *  call's ring - MODE_RINGTONE, the call up hung up - and says its end
     *  itself: a ring is no end for the watch (review round 13: the call
     *  ended under it). */
    @Test fun aRingIsNoEndWhileTheRecorderRecords() = runTest {
        val w = World().apply { callUp = false; anyCall = true }
        val job = launch { w.watch { currentTime }.run() }
        advanceTimeBy(RecorderWatch.GONE_LOOKS_RECORDER_UP * look * 2)
        assertEquals(0, w.overs)
        w.anyCall = false
        advanceTimeBy(RecorderWatch.GONE_LOOKS_RECORDER_UP * look + 1)
        assertEquals(1, w.overs)
        assertTrue(job.isCompleted)
    }

    /** ...but a ring's mode for four minutes at most - over the three a
     *  network waits for an answer: one another app left set held the
     *  service while the recorder's end went astray (review rounds 14, 15). */
    @Test fun aRingsModeIsTheCallForFourMinutesAtMost() = runTest {
        val w = World().apply { callUp = false; anyCall = true }
        val job = launch { w.watch { currentTime }.run() }
        advanceTimeBy(RecorderWatch.RING_MS)
        assertEquals(0, w.overs)
        advanceTimeBy((RecorderWatch.GONE_LOOKS_RECORDER_UP + 1) * look + 1)
        assertEquals(1, w.overs)
        assertTrue(job.isCompleted)
    }

    /** A messenger call with no recorder, a phone call ringing over it: the
     *  messenger call goes on under the ring, four minutes at most - it was
     *  ended after two looks (review round 21). A phone call's own ring
     *  with no recorder is no call. */
    @Test fun aRingOverAMessengerCallIsTheCallWithNoRecorder() = runTest {
        val w = World().apply { recorderUp = false; watching = false; callUp = false; anyCall = true; ringIsTheCall = true }
        val job = launch { w.watch { currentTime }.run() }
        advanceTimeBy(RecorderWatch.RING_MS)
        assertEquals(0, w.overs)
        advanceTimeBy((RecorderWatch.GONE_LOOKS + 1) * look + 1)
        assertEquals(1, w.overs)
        assertTrue(job.isCompleted)
        val phone = World().apply { recorderUp = false; watching = false; callUp = false; anyCall = true }
        launch { phone.watch { currentTime }.run() }
        advanceTimeBy((RecorderWatch.GONE_LOOKS + 1) * look + 1)
        assertEquals(1, phone.overs)
    }

    /** The ring's limit is time, not looks: with no recorder each look
     *  takes its pings too, and 48 "four minute" looks ran six (review
     *  round 22). */
    @Test fun theRingsLimitIsTimedWhateverALookTakes() = runTest {
        val w = World().apply {
            recorderUp = false; watching = false; callUp = false; anyCall = true; ringIsTheCall = true
            pingMs = 2_400
        }
        launch { w.watch { currentTime }.run() }
        advanceTimeBy(RecorderWatch.RING_MS + 3 * (look + 2_400) + 1)
        assertEquals(1, w.overs)
    }

    /** JemRec off: nothing said, nothing revived - the call's end still read. */
    @Test fun switchedOffItNeitherSaysNorRevives() = runTest {
        val w = World().apply { recorderUp = false; watching = false }
        launch { w.watch { currentTime }.run() }
        advanceTimeBy(5 * look + 1)
        assertEquals(0, w.revives)
        assertEquals(0, w.stoppeds)
        w.callUp = false
        advanceTimeBy(RecorderWatch.GONE_LOOKS * look + 1)
        assertEquals(1, w.overs)
    }

    /** The call gone with no recorder: over after GONE_LOOKS looks, once, and
     *  the watch ends. A call back before then counts again from nothing. */
    @Test fun theCallGoneWithNoRecorderIsOverAfterAFewLooks() = runTest {
        val w = World().apply { recorderUp = false; watching = false }
        val job = launch { w.watch { currentTime }.run() }
        w.callUp = false
        advanceTimeBy(look + 1)
        assertEquals(0, w.overs)
        w.callUp = true
        advanceTimeBy(look)
        w.callUp = false
        advanceTimeBy(look)
        assertEquals("counted again from nothing", 0, w.overs)
        advanceTimeBy(look)
        assertEquals(1, w.overs)
        assertEquals("down: what it left is no whole call", false, w.overUp)
        assertTrue(job.isCompleted)
    }

    /** With a recorder up, which says the end itself seconds after, the
     *  watch waits longer before ending the call itself. */
    @Test fun withARecorderUpTheCallsEndIsWaitedForLonger() = runTest {
        val w = World()
        launch { w.watch { currentTime }.run() }
        w.callUp = false
        advanceTimeBy(RecorderWatch.GONE_LOOKS * look + 1)
        assertEquals(0, w.overs)
        advanceTimeBy((RecorderWatch.GONE_LOOKS_RECORDER_UP - RecorderWatch.GONE_LOOKS) * look)
        assertEquals(1, w.overs)
        assertEquals("up through it: its file whole", true, w.overUp)
        assertFalse(w.lines.any { "gone mid-call" in it })
    }

    /** A down look while the call was out, then the call back: forgotten -
     *  its end later, the recorder up throughout it, leaves the file whole
     *  (review round 32: the reset was pinned by nothing). */
    @Test fun aDownLookBeforeTheCallCameBackIsForgotten() = runTest {
        val w = World().apply { recorderUp = true; watching = false }
        launch { w.watch { currentTime }.run() }
        w.callUp = false
        w.recorderUp = false
        advanceTimeBy(look + 1)
        w.callUp = true
        w.recorderUp = true
        advanceTimeBy(look)
        w.callUp = false
        advanceTimeBy(RecorderWatch.GONE_LOOKS_RECORDER_UP * look)
        assertEquals(1, w.overs)
        assertEquals(true, w.overUp)
    }

    /** Down at the first look after the call's mode left, then back by
     *  another path - the heartbeat, the Wi-Fi watch, the app opened -
     *  before the end's look: it ended during the call all the same, and
     *  the end says so (review round 30: the last look alone judged the cut
     *  file a whole call). */
    @Test fun aRecorderDownAtALookAfterTheCallLeftEndsItCut() = runTest {
        val w = World().apply { recorderUp = true }
        launch { w.watch { currentTime }.run() }
        w.callUp = false
        w.recorderUp = false
        advanceTimeBy(look + 1)
        assertEquals(0, w.overs)
        w.recorderUp = true
        advanceTimeBy((RecorderWatch.GONE_LOOKS_RECORDER_UP - 1) * look)
        assertEquals(1, w.overs)
        assertEquals(false, w.overUp)
    }
}
