package com.jemcik.jemrec.capture

import com.jemcik.jemrec.capture.AnswerDiagnostics.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the diagnostics make of the recorder's measurements. */
class AnswerDiagnosticsTest {

    /** As the Honor answered stage one, 2026-10-22. */
    private val honor = CaptureDaemon.fields(
        "DIAG 1 perms=ok incallmusic=1 screening=1 greeting=0 route=music sdk=37 voice=13 soc=SM8850 maker=HONOR model=BKQ-N49",
        "DIAG 1",
    )!!

    @Test fun theHonorPassesTheNoCallChecks() {
        val stage = AnswerDiagnostics.stageOne(honor)
        assertTrue(stage.possible)
        assertNull(stage.problem)
        assertEquals("HONOR BKQ-N49 (SM8850) Android API 37", stage.describe)
    }

    @Test fun noInCallMusicAndNoScreeningIsNotAvailable() {
        val stage = AnswerDiagnostics.stageOne(honor + mapOf("incallmusic" to "0", "screening" to "0"))
        assertFalse(stage.possible)
        assertTrue(stage.problem!!.contains("no in-call music"))
        // The call-screening route alone is still worth a test call.
        assertTrue(AnswerDiagnostics.stageOne(honor + mapOf("incallmusic" to "0")).possible)
        assertFalse(AnswerDiagnostics.stageOne(honor + mapOf("perms" to "MODIFY_PHONE_STATE")).possible)
    }

    @Test fun anAudioSetupThatCouldNotBeReadIsNoNo() {
        // dumpsys failed, or printed what the check cannot read: not a
        // certain "no" that blocks the switch - the test call tells.
        val stage = AnswerDiagnostics.stageOne(honor + mapOf("incallmusic" to "-1", "screening" to "0"))
        assertTrue(stage.audioUnread)
        assertTrue(stage.possible)
        assertNull(stage.problem)
    }

    @Test fun whatTheRecorderLacksIsSaidPlainly() {
        // No mute: not a permission anyone can grant.
        assertTrue(AnswerDiagnostics.stageOne(honor + mapOf("perms" to "mute")).problem!!.contains("mute your microphone"))
        // Cannot see other apps' calls: it would refuse every ring.
        assertTrue(AnswerDiagnostics.stageOne(honor + mapOf("perms" to "calls")).problem!!.contains("never answer"))
        assertEquals(
            "The recorder lacks a permission it needs here.",
            AnswerDiagnostics.stageOne(honor + mapOf("perms" to "RECORD_AUDIO")).problem,
        )
    }

    private fun result(line: String) = CaptureDaemon.fields(line, "RESULT 1")!!

    @Test fun theHonorTestCallIsLikely() {
        // Measured on the Honor, 2026-10-22, a 2G test call: on the output,
        // 9.4 s played in 9.5 s, no tone.
        val r = result("RESULT 1 state=done route=1 realms=9416 audioms=9539 tone=0 tonechunks=0 mute=1 voice=16")
        assertEquals(Verdict.LIKELY, AnswerDiagnostics.verdict(r))
    }

    @Test fun theToneInTheRecordingProvesIt() {
        val r = result("RESULT 1 state=done route=1 realms=9416 audioms=9539 tone=1 mute=1 voice=13")
        assertEquals(Verdict.WORKS, AnswerDiagnostics.verdict(r))
    }

    @Test fun eatenTooFastOrTheWrongOutputFails() {
        // The redirect route: 6.5 s of greeting gone in 3.4.
        assertEquals(Verdict.FAILS, AnswerDiagnostics.verdict(result("RESULT 1 state=done route=1 realms=3400 audioms=6500 tone=0")))
        assertEquals(Verdict.FAILS, AnswerDiagnostics.verdict(result("RESULT 1 state=done route=0 realms=9400 audioms=9500 tone=0")))
        // The route is unknown off the in-call music route: pace decides.
        assertEquals(Verdict.LIKELY, AnswerDiagnostics.verdict(result("RESULT 1 state=done route=-1 realms=9400 audioms=9500 tone=0")))
    }

    @Test fun aMuteThatDidNotHoldFails() {
        // The recorder hung up as the mute failed: nothing else was measured,
        // and the caller would have heard the room.
        val r = result("RESULT 1 state=done route=-1 realms=0 audioms=0 tone=0 tonechunks=0 mute=0 voice=16 played=-1")
        assertEquals(Verdict.FAILS, AnswerDiagnostics.verdict(r))
    }

    @Test fun aGreetingThatCouldNotPlayFails() {
        assertEquals(Verdict.FAILS, AnswerDiagnostics.verdict(result("RESULT 1 state=done route=-1 realms=0 audioms=0 played=0")))
    }

    @Test fun aTestThatNeverPlayedIsInconclusive() {
        assertEquals(Verdict.INCONCLUSIVE, AnswerDiagnostics.verdict(result("RESULT 1 state=done route=-1 realms=0 audioms=0")))
    }

    @Test fun callTypesByNetwork() {
        assertEquals("2G", AnswerDiagnostics.callType(16))
        assertEquals("3G", AnswerDiagnostics.callType(3))
        assertEquals("VoLTE", AnswerDiagnostics.callType(13))
        assertEquals("Wi-Fi Calling", AnswerDiagnostics.callType(18))
        assertEquals("an unknown call type", AnswerDiagnostics.callType(-1))
    }

    @Test fun theSummarySaysWhatWasTested() {
        assertNull(AnswerDiagnostics.summary(emptyMap()))
        assertEquals(
            "Works on 2G; Very likely works on VoLTE",
            AnswerDiagnostics.summary(mapOf("VoLTE" to Verdict.LIKELY, "2G" to Verdict.WORKS)),
        )
    }

    @Test fun theReportHoldsNothingAboutCalls() {
        val report = AnswerDiagnostics.report("perms=ok incallmusic=1", mapOf("2G" to Verdict.WORKS), "music", "0.17")
        assertTrue(report.contains("0.17"))
        assertTrue(report.contains("Test call on 2G: WORKS"))
        assertTrue(report.contains("incallmusic=1"))
    }

    @Test fun theSimListParses() {
        val sims = CaptureDaemon.parseSims("SIMS default=8938003 8938003|Kyivstar 8938005|life:)")!!
        assertEquals("8938003", sims.defaultId)
        assertEquals(listOf("8938003" to "Kyivstar", "8938005" to "life:)"), sims.all)
        assertNull(CaptureDaemon.parseSims(null))
        assertEquals(CaptureDaemon.Sims(null, emptyList()), CaptureDaemon.parseSims("SIMS default="))
    }
}
