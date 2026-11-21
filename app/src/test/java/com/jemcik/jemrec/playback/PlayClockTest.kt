package com.jemcik.jemrec.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The position by the clock once the sound is under way, the player's own
 *  word otherwise - and whenever the two part; at a pause, where the clock
 *  had the sound. */
class PlayClockTest {

    private val clock = PlayClock()

    @Test fun beforeTheSoundIsUnderWayThePlayerSaysWhere() {
        clock.startsFrom(10_000)
        assertEquals(10_000, clock.position(10_000, playing = true, nowMs = 5_000, durationMs = 60_000))
    }

    @Test fun underWayTheClockSaysWhereBetweenThePlayersSteps() {
        clock.startsFrom(10_000)
        clock.underWay(atMs = 5_000)
        // The player still says 10,000 - its loop has not run - and the
        // sound is 120 ms on.
        assertEquals(10_120, clock.position(10_000, playing = true, nowMs = 5_120, durationMs = 60_000))
        // A step later it says 10,256, the sound is 300 ms on.
        assertEquals(10_300, clock.position(10_256, playing = true, nowMs = 5_300, durationMs = 60_000))
    }

    @Test fun stoppedOtherwiseThePlayerSaysWhere() {
        // Stalled or ended: the sound stopped before the clock was told.
        clock.startsFrom(10_000)
        clock.underWay(atMs = 5_000)
        assertEquals(10_200, clock.position(10_200, playing = false, nowMs = 9_000, durationMs = 60_000))
        clock.stopped()
        assertEquals(10_200, clock.position(10_200, playing = true, nowMs = 9_100, durationMs = 60_000))
    }

    @Test fun aPauseHoldsWhereTheClockHadTheSound() {
        // The Honor, 2026-11-17: paused 104 ms after the player's step at
        // 227,045 - which it would have gone on saying - and it went on from
        // 227,159 when played again.
        clock.startsFrom(226_900)
        clock.underWay(atMs = 5_000)
        clock.pausedAt(reportedMs = 227_045, nowMs = 5_249, durationMs = 600_000)
        // ExoPlayer's listener says it stopped, as pausing it does.
        clock.stopped()
        assertEquals(227_149, clock.position(227_045, playing = false, nowMs = 5_300, durationMs = 600_000))
        // The player's word catches up; the paused place stands - and a
        // second pause, as a focus change while paused makes, keeps it.
        assertEquals(227_149, clock.position(227_159, playing = false, nowMs = 8_000, durationMs = 600_000))
        clock.pausedAt(reportedMs = 227_159, nowMs = 8_050, durationMs = 600_000)
        assertEquals(227_149, clock.position(227_159, playing = false, nowMs = 8_060, durationMs = 600_000))
        // Played again once the player's word is further on: from that.
        clock.resumes(reportedMs = 227_159)
        assertEquals(227_159, clock.position(227_159, playing = true, nowMs = 8_100, durationMs = 600_000))
    }

    @Test fun playedAgainAtOnceItGoesOnFromThePausedPlace() {
        clock.startsFrom(226_900)
        clock.underWay(atMs = 5_000)
        clock.pausedAt(reportedMs = 227_045, nowMs = 5_249, durationMs = 600_000)
        // The player's word still its last step: not back to that...
        clock.resumes(reportedMs = 227_045)
        assertEquals(227_149, clock.position(227_045, playing = true, nowMs = 5_300, durationMs = 600_000))
        // ...and once the sound is under way, on from the paused place.
        clock.underWay(atMs = 5_400)
        assertEquals(227_249, clock.position(227_045, playing = true, nowMs = 5_500, durationMs = 600_000))
    }

    @Test fun playedWithNoPauseHeldThePlayerSaysWhere() {
        clock.startsFrom(10_000)
        clock.underWay(atMs = 5_000)
        clock.stopped()
        clock.resumes(reportedMs = 10_300)
        assertEquals(10_300, clock.position(10_300, playing = true, nowMs = 6_000, durationMs = 60_000))
    }

    @Test fun pausedBeforeTheSoundIsUnderWayItIsWhereItStartsFrom() {
        clock.startsFrom(10_000)
        clock.pausedAt(reportedMs = 10_000, nowMs = 5_000, durationMs = 60_000)
        assertEquals(10_000, clock.position(10_000, playing = false, nowMs = 6_000, durationMs = 60_000))
    }

    @Test fun pausedAgainAtOnceAfterPlayingAgainTheResumedPlaceStands() {
        clock.startsFrom(226_900)
        clock.underWay(atMs = 5_000)
        clock.pausedAt(reportedMs = 227_045, nowMs = 5_249, durationMs = 600_000)
        clock.resumes(reportedMs = 227_045)
        // Paused again before the sound is under way, the player's word still
        // its old step.
        clock.pausedAt(reportedMs = 227_045, nowMs = 5_300, durationMs = 600_000)
        assertEquals(227_149, clock.position(227_045, playing = false, nowMs = 5_400, durationMs = 600_000))
    }

    @Test fun pausedAfterAStallThePlayersWordIsHeld() {
        clock.startsFrom(10_000)
        clock.underWay(atMs = 5_000)
        // Two seconds on by the clock, the player stuck at 10,500.
        clock.pausedAt(reportedMs = 10_500, nowMs = 7_000, durationMs = 60_000)
        assertEquals(10_500, clock.position(10_500, playing = false, nowMs = 7_100, durationMs = 60_000))
    }

    @Test fun sentElsewhereWhilePausedThePlayerSaysWhere() {
        clock.startsFrom(10_000)
        clock.underWay(atMs = 5_000)
        clock.pausedAt(reportedMs = 10_000, nowMs = 5_200, durationMs = 60_000)
        clock.startsFrom(40_000)
        assertEquals(40_000, clock.position(40_000, playing = false, nowMs = 6_000, durationMs = 60_000))
    }

    /** Sent somewhere while paused, it is there, though the player - sent
     *  80 ms early for the decoder's pre-roll (OpusPreRoll) - says less. */
    @Test fun sentToAPlaceWhilePausedItIsThere() {
        clock.startsFrom(10_000)
        clock.underWay(atMs = 5_000)
        clock.pausedAt(reportedMs = 10_000, nowMs = 5_200, durationMs = 60_000)
        clock.sentTo(40_000)
        assertEquals(40_000, clock.position(39_920, playing = false, nowMs = 6_000, durationMs = 60_000))
        // Further on by the player's word, the word.
        assertEquals(40_050, clock.position(40_050, playing = false, nowMs = 6_000, durationMs = 60_000))
    }

    /** Playing, the seek under way: Media3 says it is not playing while it
     *  seeks, and the clock is stopped - the place sent to stands. */
    @Test fun sentToAPlaceItStaysThereThroughTheSeeksOwnStop() {
        clock.startsFrom(10_000)
        clock.underWay(atMs = 5_000)
        clock.sentTo(40_000)
        clock.stopped()
        assertEquals(40_000, clock.position(39_920, playing = false, nowMs = 5_100, durationMs = 60_000))
        assertEquals(40_000, clock.position(39_920, playing = true, nowMs = 5_150, durationMs = 60_000))
        // Under way: the clock from there, past the player's first word.
        clock.underWay(atMs = 5_200)
        assertEquals(40_100, clock.position(39_990, playing = true, nowMs = 5_300, durationMs = 60_000))
    }

    /** Played after a seek made while paused, it goes on from the place sent
     *  to, not from the player's word 80 ms short of it. */
    @Test fun playedAfterASeekWhilePausedItGoesOnFromThePlaceSent() {
        clock.startsFrom(10_000)
        clock.underWay(atMs = 5_000)
        clock.pausedAt(reportedMs = 10_000, nowMs = 5_200, durationMs = 60_000)
        clock.sentTo(40_000)
        clock.resumes(reportedMs = 39_920)
        assertEquals(40_000, clock.position(39_920, playing = true, nowMs = 6_000, durationMs = 60_000))
        // And paused again at once, before the sound: still there.
        clock.pausedAt(reportedMs = 39_920, nowMs = 6_010, durationMs = 60_000)
        assertEquals(40_000, clock.position(39_920, playing = false, nowMs = 6_100, durationMs = 60_000))
    }

    /** Sent somewhere and paused before the sound got under way there, a
     *  late word that the track ran dry - the sound before the seek - does
     *  not pull the paused place back to the player's word, 80 ms short. */
    @Test fun aLateUnderrunLeavesThePlaceSentTo() {
        clock.startsFrom(10_000)
        clock.underWay(atMs = 5_000)
        clock.sentTo(40_000)
        clock.pausedAt(reportedMs = 39_920, nowMs = 5_050, durationMs = 60_000)
        assertFalse(clock.underrun(reportedMs = 39_920))
        assertEquals(40_000, clock.position(39_920, playing = false, nowMs = 5_100, durationMs = 60_000))
    }

    /** Sent past the end while paused - a controller can ask - it is shown
     *  at the end, as every other place is capped. */
    @Test fun aPlaceSentPastTheEndIsShownAtTheEnd() {
        clock.startsFrom(10_000)
        clock.underWay(atMs = 5_000)
        clock.pausedAt(reportedMs = 10_000, nowMs = 5_200, durationMs = 60_000)
        clock.sentTo(70_000)
        assertEquals(60_000, clock.position(59_920, playing = false, nowMs = 6_000, durationMs = 60_000))
    }

    @Test fun aPausedPlaceIsNeverPastTheEnd() {
        clock.startsFrom(59_900)
        clock.underWay(atMs = 5_000)
        clock.pausedAt(reportedMs = 59_900, nowMs = 5_300, durationMs = 60_000)
        assertEquals(60_000, clock.position(59_900, playing = false, nowMs = 5_400, durationMs = 60_000))
    }

    @Test fun sentElsewhereItWaitsForTheSoundAgain() {
        clock.startsFrom(10_000)
        clock.underWay(atMs = 5_000)
        clock.startsFrom(40_000)
        assertEquals(40_000, clock.position(40_000, playing = true, nowMs = 6_000, durationMs = 60_000))
        clock.underWay(atMs = 6_050)
        assertEquals(40_100, clock.position(40_000, playing = true, nowMs = 6_150, durationMs = 60_000))
    }

    @Test fun withinThePlayersStepsTheClockSaysWhere() {
        clock.startsFrom(10_000)
        clock.underWay(atMs = 5_000)
        // 900 ms on, the player still at 10,500: within its steps.
        assertEquals(10_900, clock.position(10_500, playing = true, nowMs = 5_900, durationMs = 60_000))
    }

    @Test fun thePlayersStepsOnAnEarbudAreNoStall() {
        // The Honor's earbud, 2026-11-17: the clock up to about 400 ms
        // ahead of ExoPlayer's word between its steps.
        clock.startsFrom(10_000)
        clock.underWay(atMs = 5_000)
        assertEquals(10_450, clock.position(10_000, playing = true, nowMs = 5_450, durationMs = 60_000))
        assertEquals(10_900, clock.position(10_257, playing = true, nowMs = 5_900, durationMs = 60_000))
    }

    @Test fun aStallUnderTheClockGivesThePlayerTheWordAndTheClockGoesOnFromIt() {
        clock.startsFrom(10_000)
        clock.underWay(atMs = 5_000)
        // Two seconds on by the clock, the player stuck at 10,500: the sound
        // stopped. Its word, not the clock's...
        assertEquals(10_500, clock.position(10_500, playing = true, nowMs = 7_000, durationMs = 60_000))
        // ...and once the sound is going again, the clock from there.
        assertEquals(10_650, clock.position(10_500, playing = true, nowMs = 7_150, durationMs = 60_000))
    }

    /** A stall shorter than MOST_AHEAD_MS: the audio track says it ran dry,
     *  and the player's word stands until it moves on - the new sound heard
     *  only after the output's latency - and the clock goes on from where it
     *  moved to. Without it the clock stayed 300 ms ahead of the sound for
     *  good (reviews, 2026-11-18). */
    @Test fun aShortStallTheTrackSaysPutsTheClockBack() {
        clock.startsFrom(10_000)
        clock.underWay(atMs = 5_000)
        assertEquals(11_000, clock.position(11_000, playing = true, nowMs = 6_000, durationMs = 60_000))
        // 300 ms with no sound: the player stands at 11,000 while the clock
        // runs on; then the track says it starved.
        assertTrue(clock.underrun(11_000))
        // Its word stands while it stands...
        assertEquals(11_000, clock.position(11_000, playing = true, nowMs = 6_450, durationMs = 60_000))
        // ...and once it moves, the clock goes on from there.
        assertEquals(11_257, clock.position(11_257, playing = true, nowMs = 6_700, durationMs = 60_000))
        assertEquals(11_557, clock.position(11_257, playing = true, nowMs = 7_000, durationMs = 60_000))
        // Not said, it would read 12,000 - and a move would seek there.
        val unsaid = PlayClock()
        unsaid.startsFrom(10_000)
        unsaid.underWay(atMs = 5_000)
        assertEquals(12_000, unsaid.position(11_257, playing = true, nowMs = 7_000, durationMs = 60_000))
    }

    /** Paused during the stall, before the track said it - it says so at
     *  its next write: the paused place goes back to the player's word. */
    @Test fun aStallSaidAfterAPauseTakesThePausedPlaceBack() {
        clock.startsFrom(10_000)
        clock.underWay(atMs = 5_000)
        clock.pausedAt(11_000, nowMs = 6_300, durationMs = 60_000)
        assertEquals(11_300, clock.position(11_000, playing = false, nowMs = 6_400, durationMs = 60_000))
        assertTrue(clock.underrun(11_000))
        assertEquals(11_000, clock.position(11_000, playing = false, nowMs = 6_500, durationMs = 60_000))
    }

    /** Before the sound is under way, and paused short of the word, an
     *  underrun changes nothing. */
    @Test fun anUnderrunWithNothingAheadChangesNothing() {
        clock.startsFrom(10_000)
        assertFalse(clock.underrun(10_000))
        assertEquals(10_000, clock.position(9_900, playing = true, nowMs = 5_300, durationMs = 60_000))
    }

    /** Paused at or behind the player's word, an underrun said then changes
     *  nothing: the paused place stands (review, 2026-11-18). */
    @Test fun anUnderrunWhilePausedShortOfTheWordChangesNothing() {
        clock.startsFrom(10_000)
        clock.underWay(atMs = 5_000)
        clock.pausedAt(10_300, nowMs = 5_300, durationMs = 60_000)
        assertFalse(clock.underrun(10_300))
        assertFalse(clock.underrun(10_400))
        assertEquals(10_300, clock.position(10_400, playing = false, nowMs = 6_000, durationMs = 60_000))
    }

    /** Sent elsewhere after an underrun, the clock waits for the sound from
     *  there, not for the old word to move (review, 2026-11-18). */
    @Test fun aMoveAfterAnUnderrunClearsTheWait() {
        clock.startsFrom(10_000)
        clock.underWay(atMs = 5_000)
        assertTrue(clock.underrun(10_200))
        clock.startsFrom(30_000)
        assertEquals(30_000, clock.position(29_990, playing = true, nowMs = 6_000, durationMs = 60_000))
        assertEquals(30_000, clock.position(30_000, playing = true, nowMs = 6_100, durationMs = 60_000))
    }

    /** Paused while the clock waits after an underrun: the word it waits on
     *  is the paused place (review, 2026-11-18). */
    @Test fun pausedDuringTheWaitHoldsTheWord() {
        clock.startsFrom(10_000)
        clock.underWay(atMs = 5_000)
        assertTrue(clock.underrun(10_200))
        clock.pausedAt(10_200, nowMs = 5_500, durationMs = 60_000)
        assertEquals(10_200, clock.position(10_200, playing = false, nowMs = 5_600, durationMs = 60_000))
    }

    /** Paused during a stall and played again before the track said it: the
     *  start goes back to the word when it does, not on from the paused place
     *  ahead of the sound (review, 2026-11-18). */
    @Test fun playedAgainBeforeTheStallWasSaidGoesBackToTheWord() {
        clock.startsFrom(10_000)
        clock.underWay(atMs = 5_000)
        clock.pausedAt(10_200, nowMs = 5_800, durationMs = 60_000)   // the clock had 10,800
        clock.resumes(10_200)
        assertEquals(10_800, clock.position(10_200, playing = true, nowMs = 5_900, durationMs = 60_000))
        assertTrue(clock.underrun(10_200))
        assertEquals(10_200, clock.position(10_200, playing = true, nowMs = 6_000, durationMs = 60_000))
        assertEquals(10_457, clock.position(10_457, playing = true, nowMs = 6_250, durationMs = 60_000))
        // Its own under-way, from the playout start: on from 10,200 then,
        // not from the word read meanwhile - counted twice, 257 ms ahead.
        clock.underWay(atMs = 6_000)
        assertEquals(10_500, clock.position(10_457, playing = true, nowMs = 6_300, durationMs = 60_000))
    }

    /** Stopped by itself while waiting on the dry word - buffering - its
     *  restart brings its own under-way, counted once (review, 2026-11-18). */
    @Test fun stoppedWhileWaitingOnTheDryWordCountsTheRestartOnce() {
        clock.startsFrom(10_000)
        clock.underWay(atMs = 5_000)
        assertTrue(clock.underrun(10_200))
        clock.stopped()
        // The word moves before the restart's under-way is said: followed,
        // not anchored to.
        assertEquals(10_300, clock.position(10_300, playing = true, nowMs = 6_000, durationMs = 60_000))
        // Under way from 5,900 on: from 10,200 then, counted once.
        clock.underWay(atMs = 5_900)
        assertEquals(10_600, clock.position(10_350, playing = true, nowMs = 6_300, durationMs = 60_000))
    }

    @Test fun aClockBehindThePlayerIsBroughtToIt() {
        clock.startsFrom(10_000)
        clock.underWay(atMs = 5_000)
        assertEquals(10_600, clock.position(10_600, playing = true, nowMs = 5_300, durationMs = 60_000))
        assertEquals(10_700, clock.position(10_600, playing = true, nowMs = 5_400, durationMs = 60_000))
    }

    @Test fun aStaleStartIsPutRightOnlyEverForward() {
        // Started from 9,800 read before the player caught up with its pause
        // at 10,000; it then steps 256 ms at a time.
        clock.startsFrom(9_800)
        clock.underWay(atMs = 5_000)
        val seen = listOf(
            clock.position(10_000, playing = true, nowMs = 5_100, durationMs = 60_000),
            clock.position(10_000, playing = true, nowMs = 5_200, durationMs = 60_000),
            clock.position(10_256, playing = true, nowMs = 5_300, durationMs = 60_000),
            clock.position(10_256, playing = true, nowMs = 5_400, durationMs = 60_000),
            clock.position(10_512, playing = true, nowMs = 5_550, durationMs = 60_000),
        )
        assertEquals(listOf(10_000L, 10_100L, 10_256L, 10_356L, 10_512L), seen)
    }

    @Test fun neverPastTheEnd() {
        clock.startsFrom(59_800)
        clock.underWay(atMs = 5_000)
        assertEquals(60_000, clock.position(59_800, playing = true, nowMs = 5_350, durationMs = 60_000))
        // A length not known yet is no limit.
        assertEquals(60_150, clock.position(59_800, playing = true, nowMs = 5_350, durationMs = 0))
    }
}
