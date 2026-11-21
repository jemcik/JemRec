package com.jemcik.jemrec.capture

import com.jemcik.jemrec.capture.AudioCheck.Levels
import com.jemcik.jemrec.capture.AudioCheck.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

/** The judgement on a decoded recording: silent, one-sided, or fine. */
class AudioCheckTest {

    private fun judge(frames: Int, vararg active: Int, separate: Boolean = false) =
        AudioCheck.classify(Levels(frames, active, separate))

    @Test fun aCallTooShortToJudgeIsNotJudged() {
        assertEquals(Verdict.UNKNOWN, judge(AudioCheck.MIN_FRAMES - 1, 0, 0))
    }

    /** The Honor's part of 2026-11-19: 5.5 s, 4.5 s of live line and then
     *  about a second of voice - under the half-second floor, and not silent.
     *  Under ten seconds nothing is judged. */
    @Test fun aFewSecondsWithAWordAtTheEndAreNotCalledSilent() {
        assertEquals(Verdict.UNKNOWN, judge(275, 20, 3))
        assertEquals(500, AudioCheck.MIN_FRAMES)
        // Ten seconds of nothing is judged.
        assertEquals(Verdict.SILENT, judge(500, 0, 0))
    }

    /** The meter stops once each channel has had three seconds of sound,
     *  which a lively call has within its first ten, and the Honor puts a
     *  whole call in one channel: three seconds on the loudest is a verdict,
     *  never silent - not "too short to judge" - and a call whose other part
     *  is silent is then not called silent (review rounds 2 and 3 of 1.4). */
    @Test fun soundEnoughOnTheLoudestChannelIsJudgedHoweverShort() {
        assertEquals(Verdict.FINE, judge(300, AudioCheck.ENOUGH_ACTIVE, AudioCheck.ENOUGH_ACTIVE))
        assertEquals(Verdict.FINE, judge(160, AudioCheck.ENOUGH_ACTIVE))
        assertEquals(Verdict.FINE, judge(300, AudioCheck.ENOUGH_ACTIVE, 40))
        // The whole call in one channel, the other split off and dead.
        val oneChannel = judge(400, 200, 5, separate = true)
        assertEquals(Verdict.ONE_SIDE, oneChannel)
        val silentPart = judge(1_000, 0, 0)
        assertEquals(Verdict.ONE_SIDE, AudioCheck.ofCall(listOf(oneChannel, silentPart)))
        val lively = judge(300, AudioCheck.ENOUGH_ACTIVE, AudioCheck.ENOUGH_ACTIVE)
        assertEquals(Verdict.FINE, AudioCheck.ofCall(listOf(lively, silentPart)))
        // Short of three seconds on every channel, under ten: not judged.
        assertEquals(Verdict.UNKNOWN, judge(275, 20, 3))
    }

    /** A call in parts is silent only if every part that can be judged is. */
    @Test fun aCallInPartsIsJudgedAsOne() {
        assertEquals(Verdict.SILENT, AudioCheck.ofCall(listOf(Verdict.SILENT, Verdict.SILENT)))
        assertEquals(Verdict.FINE, AudioCheck.ofCall(listOf(Verdict.SILENT, Verdict.FINE)))
        // A part too short to judge says nothing either way.
        assertEquals(Verdict.SILENT, AudioCheck.ofCall(listOf(Verdict.UNKNOWN, Verdict.SILENT)))
        assertEquals(Verdict.UNKNOWN, AudioCheck.ofCall(listOf(Verdict.UNKNOWN, Verdict.UNKNOWN)))
        assertEquals(Verdict.ONE_SIDE, AudioCheck.ofCall(listOf(Verdict.ONE_SIDE, Verdict.SILENT)))
        assertEquals(Verdict.FINE, AudioCheck.ofCall(listOf(Verdict.FINE)))
    }

    @Test fun aRecordingWithNoSoundIsSilent() {
        // A minute of muted capture: the packets are all there, the voices are not.
        assertEquals(Verdict.SILENT, judge(3_000, 0, 0))
    }

    @Test fun aFewClicksDoNotMakeACall() {
        assertEquals(Verdict.SILENT, judge(3_000, AudioCheck.SILENT_FLOOR - 1, 3))
    }

    @Test fun aLongSilentFileNeedsMoreThanTheFloorToCountAsSound() {
        // Ten minutes of nothing but two seconds of noise is still nothing.
        assertEquals(Verdict.SILENT, judge(AudioCheck.MAX_FRAMES, 100, 100))
    }

    @Test fun anOrdinaryCallIsFine() {
        assertEquals(Verdict.FINE, judge(500, AudioCheck.ENOUGH_ACTIVE, AudioCheck.ENOUGH_ACTIVE))
    }

    @Test fun aDeadSideOnSplitChannelsIsOneSided() {
        assertEquals(Verdict.ONE_SIDE, judge(AudioCheck.MAX_FRAMES, 4_000, 0, separate = true))
        assertEquals(Verdict.ONE_SIDE, judge(AudioCheck.MAX_FRAMES, 2, 4_000, separate = true))
    }

    @Test fun aQuietSideIsNotADeadOne() {
        // The other person barely spoke, but did.
        assertEquals(Verdict.FINE, judge(AudioCheck.MAX_FRAMES, 4_000, 400, separate = true))
    }

    @Test fun oneSidedCannotBeClaimedOnAMixCopiedToBothChannels() {
        // Same mix in both channels: one voice in it looks exactly like a call
        // where the other person said nothing, so it is not called one-sided.
        assertEquals(Verdict.FINE, judge(AudioCheck.MAX_FRAMES, 4_000, 0, separate = false))
    }

    @Test fun monoIsJudgedOnItsOneChannel() {
        assertEquals(Verdict.SILENT, judge(3_000, 0))
        assertEquals(Verdict.FINE, judge(3_000, 800))
    }
}
