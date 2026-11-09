package com.jemcik.jemrec.ui

import com.jemcik.jemrec.answering.AnswerDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

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

    /**
     * The screen's times - a test call's "until", the end of a pause after an
     * emergency call - in Latin digits and English words, as the rest of the
     * app writes them: in the phone's language they came out in digits the
     * looks' fonts do not have (Arabic, Persian), or a month in another
     * language inside English text (Ukrainian).
     */
    @Test fun timesAreWrittenAsTheRestOfTheAppsAreInAnyLanguage() {
        val locale = Locale.getDefault()
        val zone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            for (language in listOf("ar", "fa", "uk")) {
                Locale.setDefault(Locale.forLanguageTag(language))
                // 3 November 2026, 14:30 UTC.
                assertEquals(language, "14:30", timeOfDay(1_793_716_200_000L))
                assertEquals(language, "3 Nov at 14:30", pauseEnd(1_793_716_200_000L))
                // The greeting's take, counting.
                assertEquals(language, "12 s", takeSeconds(12_400L))
            }
        } finally {
            Locale.setDefault(locale)
            TimeZone.setDefault(zone)
        }
    }
}
