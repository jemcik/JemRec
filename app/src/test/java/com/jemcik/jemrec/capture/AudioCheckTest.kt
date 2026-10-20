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
