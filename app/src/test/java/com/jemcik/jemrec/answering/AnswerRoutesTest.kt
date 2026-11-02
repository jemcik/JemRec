package com.jemcik.jemrec.answering

import com.jemcik.jemrec.answering.AnswerDiagnostics.StageOne
import com.jemcik.jemrec.answering.AnswerDiagnostics.Verdict
import com.jemcik.jemrec.answering.AnsweringMachine.ROUTE_MUSIC
import com.jemcik.jemrec.answering.AnsweringMachine.ROUTE_SCREENING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which way the greeting goes into a call, after the checks and after a failed test. */
class AnswerRoutesTest {

    private fun stage(music: Boolean, screening: Boolean, unread: Boolean = false) =
        StageOne(permissions = true, inCallMusic = music, screening = screening, describe = "", audioUnread = unread)

    @Test fun afterTheChecksOnlyScreeningIsSwitchedTo() {
        assertEquals(ROUTE_SCREENING, AnswerDiagnostics.routeAfterChecks(stage(music = false, screening = true), ROUTE_MUSIC))
        assertNull("already", AnswerDiagnostics.routeAfterChecks(stage(music = false, screening = true), ROUTE_SCREENING))
        assertNull("music is there", AnswerDiagnostics.routeAfterChecks(stage(music = true, screening = true), ROUTE_MUSIC))
        assertNull("no screening", AnswerDiagnostics.routeAfterChecks(stage(music = false, screening = false), ROUTE_MUSIC))
        assertNull(
            "a setup that could not be read is no no",
            AnswerDiagnostics.routeAfterChecks(stage(music = false, screening = true, unread = true), ROUTE_MUSIC),
        )
    }

    @Test fun afterAFailureTheOtherWay() {
        assertEquals(ROUTE_SCREENING, AnswerDiagnostics.routeAfterFailure(ROUTE_MUSIC, stage(true, true), emptyMap(), "2G"))
        assertEquals(ROUTE_MUSIC, AnswerDiagnostics.routeAfterFailure(ROUTE_SCREENING, stage(true, true), emptyMap(), "2G"))
        assertEquals(
            ROUTE_MUSIC,
            AnswerDiagnostics.routeAfterFailure(ROUTE_SCREENING, stage(false, true, unread = true), emptyMap(), "2G"),
        )
    }

    @Test fun notWhereTheOtherWayIsNotThere() {
        assertNull(AnswerDiagnostics.routeAfterFailure(ROUTE_MUSIC, stage(true, false), emptyMap(), "2G"))
        assertNull(AnswerDiagnostics.routeAfterFailure(ROUTE_SCREENING, stage(false, true), emptyMap(), "2G"))
        assertNull("no checks run", AnswerDiagnostics.routeAfterFailure(ROUTE_MUSIC, null, emptyMap(), "2G"))
    }

    @Test fun notWhenThisWayPassedOnAnotherCallType() {
        listOf(Verdict.WORKS, Verdict.LIKELY).forEach {
            assertNull(AnswerDiagnostics.routeAfterFailure(ROUTE_MUSIC, stage(true, true), mapOf("VoLTE" to it), "2G"))
        }
        // Its own type's earlier pass, or another type's failure, does not hold it.
        assertEquals(
            ROUTE_SCREENING,
            AnswerDiagnostics.routeAfterFailure(ROUTE_MUSIC, stage(true, true), mapOf("2G" to Verdict.WORKS, "VoLTE" to Verdict.FAILS), "2G"),
        )
    }
}
