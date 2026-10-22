package com.jemcik.jemrec.capture

import com.jemcik.jemrec.capture.AnsweringMachine.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The answering machine's ring-time decision: every doubt is a "no". */
class AnsweringMachineTest {

    private fun ringing(
        enabled: Boolean = true,
        greeting: Boolean = true,
        recorderOn: Boolean = true,
        trackingOn: Boolean = true,
        otherCalls: Int = 0,
        roaming: Boolean = false,
        rulesKeep: Boolean? = true,
        dndSilences: Boolean = false,
        delay: Int = 20,
        dndShort: Boolean = true,
        simAllowed: Boolean = true,
    ) = AnsweringMachine.Ringing(
        enabled, greeting, recorderOn, trackingOn, otherCalls, roaming, rulesKeep, dndSilences, delay, dndShort, simAllowed,
    )

    private fun no(r: AnsweringMachine.Ringing) = assertTrue("$r", AnsweringMachine.decide(r) is Decision.No)

    @Test fun answersAfterTheDelay() {
        assertEquals(Decision.Answer(20), AnsweringMachine.decide(ringing()))
        assertEquals(Decision.Answer(5), AnsweringMachine.decide(ringing(delay = 5)))
    }

    @Test fun doNotDisturbAnswersAfterTwoRings() {
        assertEquals(Decision.Answer(AnsweringMachine.DND_DELAY), AnsweringMachine.decide(ringing(dndSilences = true)))
        // "The same delay" chosen: DND changes nothing.
        assertEquals(Decision.Answer(20), AnsweringMachine.decide(ringing(dndSilences = true, dndShort = false)))
        // Never later than the user's own delay.
        assertEquals(Decision.Answer(5), AnsweringMachine.decide(ringing(dndSilences = true, delay = 5)))
    }

    @Test fun everyDoubtIsANo() {
        no(ringing(enabled = false))
        no(ringing(greeting = false))
        no(ringing(recorderOn = false))
        no(ringing(trackingOn = false))
        no(ringing(otherCalls = 1))
        // On WhatsApp or in a meeting: not a Telecom call, but a call.
        no(ringing().copy(otherAppCall = true))
        no(ringing(roaming = true))
        no(ringing(rulesKeep = false))
        // Recording keeps a call it cannot place; answering does not.
        no(ringing(rulesKeep = null))
        no(ringing(simAllowed = false))
    }

    @Test fun neverAnEmergencyCallback() {
        // Marked as one by the network or the phone: never answered.
        no(ringing().copy(emergencyCallback = true))
        // A day after the user called an emergency number, no call is: the
        // callback could come from any number.
        no(ringing().copy(emergencyPaused = true))
    }

    @Test fun theRecorderReadsTheWire() {
        assertEquals("1,60,music", AnsweringMachine.wire(on = true, longest = 60, route = "music"))
        assertEquals("0,120,screening", AnsweringMachine.wire(on = false, longest = 120, route = "screening"))
        // No limit is 0, as the recorder's AnswerSettings reads it.
        assertEquals("1,0,music", AnsweringMachine.wire(on = true, longest = AnsweringMachine.NO_LIMIT, route = "music"))
    }
}
