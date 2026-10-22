package com.jemcik.jemrec.ui

import com.jemcik.jemrec.capture.AnswerDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The answering machine's row in Settings, and why its switch may be off. */
class AnswerSummaryTest {

    private val passed = AnswerDiagnostics.StageOne(permissions = true, inCallMusic = true, screening = false, describe = "")
    private val failed = passed.copy(inCallMusic = false)

    @Test fun theRowSaysWhatItDoes() {
        assertEquals("Off", answerSummary(AnswerUi()))
        assertEquals(
            "After 20 seconds - not tested on this phone yet",
            answerSummary(AnswerUi(enabled = true, stageOne = passed)),
        )
        assertEquals(
            "After 10 seconds",
            answerSummary(AnswerUi(enabled = true, delay = 10, results = mapOf("2G" to AnswerDiagnostics.Verdict.LIKELY))),
        )
        assertEquals("Not available on this phone", answerSummary(AnswerUi(stageOne = failed)))
        assertEquals("Paused after an emergency call", answerSummary(AnswerUi(enabled = true, pausedUntil = 1L)))
        // Off, it is just off.
        assertEquals("Off", answerSummary(AnswerUi(pausedUntil = 1L)))
    }

    @Test fun theSwitchWaitsForTheChecksTrackingAndAGreeting() {
        assertEquals("Run the checks below first", AnswerUi().blocked)
        assertEquals(failed.problem, AnswerUi(stageOne = failed).blocked)
        assertEquals("Needs exact call tracking, in Settings", AnswerUi(stageOne = passed, callTracking = false).blocked)
        assertEquals("Record a greeting first", AnswerUi(stageOne = passed).blocked)
        assertNull(AnswerUi(stageOne = passed, greetingSeconds = 6.0).blocked)
    }

    @Test fun theCarriersTimerIsReadOnlyWhenOn() {
        assertEquals(25, AnswerUi(carrier = "on 25 *8250").carrierSeconds)
        assertNull(AnswerUi(carrier = "off 0").carrierSeconds)
        assertNull(AnswerUi(carrier = "error 3").carrierSeconds)
        assertNull(AnswerUi().carrierSeconds)
    }
}
