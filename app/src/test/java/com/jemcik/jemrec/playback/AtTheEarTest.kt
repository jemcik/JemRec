package com.jemcik.jemrec.playback

import com.jemcik.jemrec.playback.AtTheEar.Facts
import com.jemcik.jemrec.playback.AtTheEar.Wants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Listening at the ear: when a recording moves to the earpiece and back,
 *  and when the sensor and the screen's lock are wanted. */
class AtTheEarTest {

    private val ear = AtTheEar()
    private val playing = Facts(
        recording = "20261115_143000_in_Olivia.ogg",
        wanted = true,
        onScreen = true,
        headphones = false,
        playThrough = PlayThrough.LOUDSPEAKER_AND_EAR,
        sensor = true,
    )

    /** Playing at 0, raised past the quiet start: at the earpiece. */
    private fun raised(): Wants {
        ear.update(playing, 0)
        return ear.sensed(true, playing, 600)
    }

    @Test fun playingOnScreenTheSensorIsWatchedAndTheScreenCanGoDark() {
        assertEquals(Wants(Output.SPEAKER, sensor = true, screenOffLock = true), ear.update(playing, 0))
    }

    /** ...and JemRec covers its own screen while it is at the ear. */
    @Test fun raisedWhilePlayingItMovesToTheEarpiece() {
        assertEquals(Wants(Output.EARPIECE, sensor = true, screenOffLock = true, cover = true), raised())
    }

    /** Where it was started, playing on: the user's choice over a pause. */
    @Test fun loweredItGoesBackToTheLoudspeakerAndPlaysOn() {
        raised()
        assertEquals(Wants(Output.SPEAKER, sensor = true, screenOffLock = true), ear.sensed(false, playing, 1200))
    }

    @Test fun raisedAgainItMovesAgain() {
        raised()
        ear.sensed(false, playing, 1200)
        assertEquals(Output.EARPIECE, ear.sensed(true, playing, 1800).output)
    }

    /** The Honor, 2026-11-15: a hand over the sensor for 0.64 s paused the
     *  recording. Now it only dips the sound. */
    @Test fun aHandOverTheSensorOnlyDipsIt() {
        ear.update(playing, 0)
        assertEquals(Output.EARPIECE, ear.sensed(true, playing, 3000).output)
        assertEquals(Wants(Output.SPEAKER, sensor = true, screenOffLock = true), ear.sensed(false, playing, 3640))
    }

    /** Signal's reason for its quiet start: a finger near the sensor from the
     *  tap on Play. Here the reading waits, and counts if it still holds. */
    @Test fun aReadingRightAfterPlayWaitsAndCountsIfItHolds() {
        ear.update(playing, 0)
        assertEquals(Output.SPEAKER, ear.sensed(true, playing, 100).output)
        assertEquals(500L, ear.due())
        assertEquals(Output.EARPIECE, ear.update(playing, 500).output)
    }

    @Test fun aFingerGoneByTheEndOfTheQuietStartMovesNothing() {
        ear.update(playing, 0)
        ear.sensed(true, playing, 100)
        ear.sensed(false, playing, 200)
        assertEquals(Output.SPEAKER, ear.update(playing, 500).output)
    }

    @Test fun aWobbleRightAfterTheRaiseStaysAtTheEar() {
        raised()
        assertEquals(Output.EARPIECE, ear.sensed(false, playing, 700).output)
        assertEquals(1100L, ear.due())
        ear.sensed(true, playing, 800)
        assertEquals(Output.EARPIECE, ear.update(playing, 1100).output)
    }

    @Test fun aLoweringRightAfterTheRaiseCountsOnceItHolds() {
        raised()
        assertEquals(Output.EARPIECE, ear.sensed(false, playing, 700).output)
        assertEquals(Output.SPEAKER, ear.update(playing, 1100).output)
    }

    @Test fun nothingWaitsWhenNothingIsDue() {
        ear.update(playing, 0)
        assertNull(ear.due())
        raised()
        ear.sensed(true, playing, 1200)
        assertNull(ear.due())
    }

    @Test fun pausedARaiseMovesNothing() {
        val paused = playing.copy(wanted = false)
        ear.update(paused, 0)
        assertEquals(Wants(Output.SPEAKER, sensor = false, screenOffLock = false), ear.sensed(true, paused, 600))
    }

    /** Paused, or ended, at the ear: Play then starts on the loudspeaker, as
     *  it was started, not quietly at an earpiece held in front - and the
     *  screen stays covered until the phone leaves the ear, where the phone
     *  does not darken it (the 1.1 review). */
    @Test fun endedAtTheEarItWaitsOnTheLoudspeakerCoveredUntilLowered() {
        raised()
        val ended = playing.copy(wanted = false)
        assertEquals(Wants(Output.SPEAKER, sensor = true, screenOffLock = false, cover = true), ear.update(ended, 900))
        assertEquals(Wants(Output.SPEAKER, sensor = false, screenOffLock = false), ear.sensed(false, ended, 1300))
    }

    @Test fun raisedNowSaysWhyItIsAtTheEarpiece() {
        ear.update(playing, 0)
        assertFalse(ear.raisedNow())
        raised()
        assertTrue(ear.raisedNow())
        ear.choose(Output.EARPIECE, playing, 900)
        assertFalse("the button's, not a raise", ear.raisedNow())
    }

    @Test fun withHeadphonesInNothingIsWatchedAndNothingMoves() {
        val plugged = playing.copy(headphones = true)
        ear.update(plugged, 0)
        assertEquals(Wants(Output.SPEAKER, sensor = false, screenOffLock = false), ear.sensed(true, plugged, 600))
    }

    /** Plugged in at the ear: the recording goes to them, playing on. */
    @Test fun headphonesPluggedAtTheEarTakeTheSound() {
        raised()
        assertEquals(Output.SPEAKER, ear.update(playing.copy(headphones = true), 900).output)
    }

    /** JemRec left at the ear - a cheek's swipe home, a call's screen: it
     *  stays at the earpiece, the screen darkened, until the phone is
     *  lowered, and only then the loudspeaker. Uncovered: not JemRec's
     *  screen. */
    @Test fun leftAtTheEarItStaysAtTheEarpieceUntilLowered() {
        raised()
        val away = playing.copy(onScreen = false)
        assertEquals(Wants(Output.EARPIECE, sensor = true, screenOffLock = true, cover = false), ear.update(away, 900))
        assertEquals(Wants(Output.SPEAKER, sensor = false, screenOffLock = false), ear.sensed(false, away, 1300))
    }

    /** Lowered away from JemRec, it is not raised again from there: in a
     *  pocket a raise means nothing. */
    @Test fun loweredAwayFromJemRecItIsNotRaisedAgain() {
        raised()
        val away = playing.copy(onScreen = false)
        ear.update(away, 900)
        ear.sensed(false, away, 1300)
        assertEquals(Output.SPEAKER, ear.sensed(true, away, 2000).output)
    }

    @Test fun offScreenARaiseMovesNothing() {
        val away = playing.copy(onScreen = false)
        ear.update(away, 0)
        assertEquals(Wants(Output.SPEAKER, sensor = false, screenOffLock = false), ear.sensed(true, away, 600))
    }

    /** The button's earpiece: nothing for the sensor to move - it is
     *  watched only to cover the screen at the ear - and the screen still
     *  goes dark at the ear. */
    @Test fun theButtonMovesItToTheEarpieceAndTheScreenCanGoDark() {
        ear.update(playing, 0)
        assertEquals(Wants(Output.EARPIECE, sensor = true, screenOffLock = true), ear.choose(Output.EARPIECE, playing, 100))
    }

    /** The Honor in landscape does not darken the screen by the sensor: JemRec
     *  covers its own while the phone is at the ear, and uncovers it as the
     *  phone comes down. */
    @Test fun atTheEarTheScreenIsCoveredAndLoweredUncovered() {
        raised()
        assertEquals(Wants(Output.SPEAKER, sensor = true, screenOffLock = true, cover = false), ear.sensed(false, playing, 1200))
    }

    @Test fun aChosenEarpieceIsCoveredOnlyAtTheEar() {
        ear.update(playing, 0)
        ear.choose(Output.EARPIECE, playing, 100)
        assertEquals(Wants(Output.EARPIECE, sensor = true, screenOffLock = true, cover = true), ear.sensed(true, playing, 700))
        assertEquals(Wants(Output.EARPIECE, sensor = true, screenOffLock = true, cover = false), ear.sensed(false, playing, 900))
    }

    /** Covering is safe: a reading at a chosen earpiece counts at once,
     *  quiet start or not. */
    @Test fun atAChosenEarpieceTheCoverDoesNotWait() {
        val earpiece = playing.copy(playThrough = PlayThrough.EARPIECE)
        ear.update(earpiece, 0)
        assertTrue(ear.sensed(true, earpiece, 50).cover)
    }

    @Test fun onTheLoudspeakerNothingIsCovered() {
        val loud = playing.copy(playThrough = PlayThrough.LOUDSPEAKER)
        ear.update(loud, 0)
        assertEquals(Wants(Output.SPEAKER, sensor = false, screenOffLock = false), ear.sensed(true, loud, 600))
    }

    /** Covering starts only while one plays at the earpiece on the screen,
     *  and goes with the screen. */
    @Test fun pausedNothingNewIsCoveredAndOutOfSightNothingIs() {
        val earpiece = playing.copy(playThrough = PlayThrough.EARPIECE)
        val paused = earpiece.copy(wanted = false)
        ear.update(paused, 0)
        assertFalse(ear.sensed(true, paused, 100).cover)
        ear.update(earpiece, 300)
        assertTrue(ear.sensed(true, earpiece, 400).cover)
        assertFalse(ear.update(earpiece.copy(onScreen = false), 500).cover)
    }

    @Test fun theButtonsEarpieceIsNotTakenBackByTheSensor() {
        ear.update(playing, 0)
        ear.choose(Output.EARPIECE, playing, 100)
        ear.sensed(true, playing, 700)
        assertEquals(Output.EARPIECE, ear.sensed(false, playing, 1300).output)
        assertEquals(Output.EARPIECE, ear.update(playing, 2000).output)
    }

    @Test fun theButtonsEarpiecePlaysOnOutOfSight() {
        ear.update(playing, 0)
        ear.choose(Output.EARPIECE, playing, 100)
        assertEquals(Wants(Output.EARPIECE, sensor = false, screenOffLock = false), ear.update(playing.copy(onScreen = false), 200))
    }

    @Test fun theButtonBringsItBackToTheSpeaker() {
        raised()
        assertEquals(Output.SPEAKER, ear.choose(Output.SPEAKER, playing, 900).output)
    }

    /** "Loudspeaker" in Settings: the sensor moves nothing; it only darkens
     *  the screen at an earpiece the button chose. */
    @Test fun onTheLoudspeakerOnlyTheButtonMovesIt() {
        val off = playing.copy(playThrough = PlayThrough.LOUDSPEAKER)
        assertEquals(Wants(Output.SPEAKER, sensor = false, screenOffLock = false), ear.update(off, 0))
        assertEquals(Wants(Output.EARPIECE, sensor = true, screenOffLock = true), ear.choose(Output.EARPIECE, off, 100))
    }

    @Test fun withoutASensorTheButtonStillWorksAndNothingDarkens() {
        val none = playing.copy(sensor = false)
        assertEquals(Wants(Output.SPEAKER, sensor = false, screenOffLock = false), ear.update(none, 0))
        assertEquals(Wants(Output.EARPIECE, sensor = false, screenOffLock = false), ear.choose(Output.EARPIECE, none, 100))
    }

    @Test fun eachRecordingStartsOnTheSpeaker() {
        ear.update(playing, 0)
        ear.choose(Output.EARPIECE, playing, 100)
        assertEquals(Output.SPEAKER, ear.update(playing.copy(recording = "20261115_150000_out_Noah.ogg"), 200).output)
    }

    /** Stopped at the ear: covered until it is lowered, then nothing. */
    @Test fun stoppedEverythingGoesOnceLowered() {
        raised()
        val stopped = playing.copy(recording = null, wanted = false)
        assertEquals(Wants(Output.SPEAKER, sensor = true, screenOffLock = false, cover = true), ear.update(stopped, 900))
        assertEquals(Wants(Output.SPEAKER, sensor = false, screenOffLock = false), ear.sensed(false, stopped, 1300))
    }

    /** "Earpiece" in Settings: each recording starts there, the screen goes
     *  dark at the ear, and the sensor never moves it. */
    @Test fun theEarpieceSettingStartsThereAndTheSensorNeverMovesIt() {
        val earpiece = playing.copy(playThrough = PlayThrough.EARPIECE)
        assertEquals(Wants(Output.EARPIECE, sensor = true, screenOffLock = true), ear.update(earpiece, 0))
        ear.sensed(true, earpiece, 600)
        assertEquals(Output.EARPIECE, ear.sensed(false, earpiece, 1200).output)
        assertEquals(Output.EARPIECE, ear.update(earpiece.copy(onScreen = false), 1300).output)
        assertEquals(Output.EARPIECE, ear.update(earpiece.copy(recording = "20261115_150000_out_Noah.ogg"), 1400).output)
    }

    @Test fun theEarpieceSettingStillGivesWayToTheButton() {
        val earpiece = playing.copy(playThrough = PlayThrough.EARPIECE)
        ear.update(earpiece, 0)
        assertEquals(Output.SPEAKER, ear.choose(Output.SPEAKER, earpiece, 100).output)
    }

    @Test fun headphonesComeFirstWhateverTheSetting() {
        for (way in PlayThrough.entries) {
            val plugged = playing.copy(playThrough = way, headphones = true)
            assertEquals(way.name, Output.SPEAKER, AtTheEar().update(plugged, 0).output)
        }
    }

    /** Changed in Settings while one plays: what plays follows it. */
    @Test fun aSettingChangedMovesWhatPlays() {
        ear.update(playing, 0)
        assertEquals(Output.EARPIECE, ear.update(playing.copy(playThrough = PlayThrough.EARPIECE), 100).output)
        assertEquals(Output.SPEAKER, ear.update(playing, 200).output)
    }

    @Test fun eachRecordingStartsWhereTheSettingSays() {
        assertEquals(Output.SPEAKER, AtTheEar.startsOn(PlayThrough.LOUDSPEAKER))
        assertEquals(Output.SPEAKER, AtTheEar.startsOn(PlayThrough.LOUDSPEAKER_AND_EAR))
        assertEquals(Output.EARPIECE, AtTheEar.startsOn(PlayThrough.EARPIECE))
    }

    @Test fun aSensorThatReadsOnlyNearOrFarIsReadSo() {
        // The Honor's: 0 near, its maximum far.
        assertTrue(AtTheEar.isNear(0f, 5f))
        assertFalse(AtTheEar.isNear(5f, 5f))
        // One whose maximum is under 5 cm.
        assertTrue(AtTheEar.isNear(0f, 1f))
        assertFalse(AtTheEar.isNear(1f, 1f))
    }

    @Test fun aSensorThatReadsCentimetresIsNearUnderFive() {
        assertTrue(AtTheEar.isNear(3f, 10f))
        assertTrue(AtTheEar.isNear(4.9f, 10f))
        assertFalse(AtTheEar.isNear(5f, 10f))
        assertFalse(AtTheEar.isNear(10f, 10f))
    }

    /**
     * The promises, over many runs of everything that can happen: an
     * earpiece the button or the setting chose is left only by the button,
     * the setting or headphones - never by the sensor; and the earpiece of a
     * raise lasts only while it is watched - on the screen, playing, the
     * earpiece at the ear in Settings, nothing else to listen with.
     */
    /** Lowered to answer and raised for the call, under the call's screen:
     *  the call holds it back, and where the phone is when the call ends is
     *  where it plays on. Unwatched there, it played on 1.8 s from the
     *  loudspeaker at the ear (the Honor, 2026-11-16). */
    @Test fun heldBackByACallItFollowsThePhoneAndPlaysOnWhereItIs() {
        val callsScreen = playing.copy(onScreen = false)
        val answered = callsScreen.copy(heldByCall = true)
        raised()
        // Lowered to answer: the loudspeaker; the call's screen comes up.
        assertEquals(Output.SPEAKER, ear.sensed(false, playing, 1200).output)
        assertEquals(Wants(Output.SPEAKER, sensor = false, screenOffLock = false), ear.update(callsScreen, 1300))
        // Answered: watched under the call, past a quiet start.
        assertTrue(ear.update(answered, 2000).sensor)
        // Raised for the call: the earpiece, the screen darkened by the lock.
        assertEquals(Wants(Output.EARPIECE, sensor = true, screenOffLock = true), ear.sensed(true, answered, 2600))
        // The call over, JemRec's screen not back yet: it plays on there.
        assertEquals(Wants(Output.EARPIECE, sensor = true, screenOffLock = true), ear.update(callsScreen, 3000))
        // Lowered: the loudspeaker, and nothing watched off the screen.
        assertEquals(Wants(Output.SPEAKER, sensor = false, screenOffLock = false), ear.sensed(false, callsScreen, 3600))
    }

    /** Under a call, the sensor moves only what it moves on the screen:
     *  nothing with Loudspeaker set, nor with headphones in. */
    @Test fun underACallOnlyTheSensorsLoudspeakerMoves() {
        val answered = playing.copy(onScreen = false, heldByCall = true)
        ear.update(answered.copy(playThrough = PlayThrough.LOUDSPEAKER), 0)
        assertEquals(Wants(Output.SPEAKER, sensor = false, screenOffLock = false), ear.sensed(true, answered.copy(playThrough = PlayThrough.LOUDSPEAKER), 600))
        val buds = AtTheEar()
        buds.update(answered.copy(headphones = true), 0)
        assertEquals(Output.SPEAKER, buds.sensed(true, answered.copy(headphones = true), 600).output)
    }

    @Test fun aChosenEarpieceStaysAndARaiseLastsOnlyWhileWatched() {
        val random = Random(83)
        repeat(2000) {
            val ear = AtTheEar()
            var facts = playing.copy(playThrough = PlayThrough.entries.random(random))
            var now = 0L
            ear.update(facts, now)
            var chosen = AtTheEar.startsOn(facts.playThrough)
            var before = chosen
            var lastNear: Boolean? = null
            repeat(40) {
                now += random.nextLong(0, 900)
                val w = when (random.nextInt(10)) {
                    0, 1 -> ear.sensed(true, facts, now).also { lastNear = true }
                    2, 3 -> ear.sensed(false, facts, now).also { lastNear = false }
                    4 -> ear.update(facts.copy(onScreen = !facts.onScreen).also { facts = it }, now)
                    9 -> ear.update(facts.copy(heldByCall = !facts.heldByCall).also { facts = it }, now)
                    5 -> ear.update(facts.copy(headphones = !facts.headphones).also { facts = it }, now)
                    6 -> ear.update(facts.copy(wanted = !facts.wanted).also { facts = it }, now)
                    7 -> {
                        val way = PlayThrough.entries.random(random)
                        if (way != facts.playThrough) chosen = AtTheEar.startsOn(way)
                        ear.update(facts.copy(playThrough = way).also { facts = it }, now)
                    }
                    else -> {
                        chosen = if (random.nextBoolean()) Output.EARPIECE else Output.SPEAKER
                        ear.choose(chosen, facts, now)
                    }
                }
                if (chosen == Output.EARPIECE && !facts.headphones) {
                    assertEquals("a chosen earpiece: $facts", Output.EARPIECE, w.output)
                }
                if (w.cover) {
                    assertTrue("covered only on the screen, at the ear: $facts", facts.onScreen && lastNear == true)
                }
                val watched = facts.wanted && facts.playThrough == PlayThrough.LOUDSPEAKER_AND_EAR && !facts.headphones
                if (chosen == Output.SPEAKER && !watched) {
                    assertEquals("a raise not watched: $facts", Output.SPEAKER, w.output)
                }
                if (chosen == Output.SPEAKER && before == Output.SPEAKER && w.output == Output.EARPIECE) {
                    assertTrue("raised only on the screen, or under a call: $facts", facts.onScreen || facts.heldByCall)
                }
                before = w.output
            }
        }
    }
}
