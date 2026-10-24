package com.jemcik.jemrec.capture

import com.jemcik.jemrec.capture.CallTracking.Tracked
import com.jemcik.jemrec.capture.FileNames.Format
import com.jemcik.jemrec.capture.SavePlan.Decision
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * What becomes of a recording: SavePlan, with a phone made up for each test.
 *
 * The first group holds the two ways of knowing a call - Telecom's account
 * (exact call tracking) and the call log - to the same file name for the
 * same call. They once disagreed (issue #30): 0.14's tracked path named a
 * number in no contact by its digits, and by Telecom's spelling of them,
 * where the call log path used the log's name and number.
 */
class SavePlanTest {

    private val t = 1_795_000_000_000L
    private val s = 1_000L

    /** A phone: [contacts] by number, [log] the call log, which writes its
     *  entries only after [logLag] seconds of waiting. */
    private class Phone(
        val contacts: Map<String, CallLogLookup.Contact> = emptyMap(),
        val log: List<CallLogLookup.Entry> = emptyList(),
        override val callLogAllowed: Boolean = true,
        val contactsFail: Boolean = false,
        val logLag: Int = 0,
    ) : SavePlan.Lookups {
        var pauses = 0
        var logReads = 0

        override fun contact(number: String?): CallLogLookup.Contact {
            if (contactsFail) return CallLogLookup.Contact(null, null, false, failed = true)
            val match = contacts.entries.firstOrNull { CallLogLookup.sameNumber(it.key, number) }
            return match?.value ?: CallLogLookup.Contact(null, null, false)
        }

        override fun logged(start: Long, incoming: Boolean): CallLogLookup.Entry? {
            logReads++
            if (pauses < logLag) return null
            return log.filter { it.incoming == incoming }.minByOrNull { abs(it.at - start) }
        }

        override fun loggedBetween(from: Long, to: Long): List<CallLogLookup.Entry> =
            log.filter { it.at in from..to }

        override suspend fun pause() {
            pauses++
        }
    }

    private fun contact(name: String, id: Long) = CallLogLookup.Contact(name, null, false, id)

    private fun entry(at: Long, incoming: Boolean, number: String?, name: String? = null, seconds: Int = 17) =
        CallLogLookup.Entry(at, incoming, name ?: number ?: "", number, seconds, name)

    private fun settings(format: Format = Format.DATE_AND_CALLER, ruled: Boolean = false, keeps: (List<Long>) -> Boolean = { true }) =
        SavePlan.Settings(format, ruled, keeps)

    private fun decide(
        phone: Phone,
        tracked: List<Tracked>,
        incoming: Boolean = false,
        start: Long = t + 60,
        settings: SavePlan.Settings = settings(),
        tries: Int = 1,
    ): Decision = runBlocking { SavePlan.decide(phone, settings, start, incoming, tracked, tries) }

    private fun saved(decision: Decision): List<String> = (decision as Decision.Save).parts.map { it.name }

    /** The same call, both ways: by the call log, and by Telecom. Telecom
     *  writes the number its own way ([telecomNumber]); the log its way. */
    private fun bothWays(phone: Phone, telecomNumber: String?, format: Format, incoming: Boolean = false): Pair<String, String> {
        val call = Tracked(t, incoming, telecomNumber, listOf(t + 60), t + 20 * s)
        val byLog = saved(decide(phone, emptyList(), incoming, settings = settings(format))).single()
        val byTelecom = saved(decide(phone, listOf(call), incoming, settings = settings(format))).single()
        return byLog to byTelecom
    }

    // --- one rule for who a call was with -------------------------------------

    @Test fun aNumberInNoContactIsNamedAsTheCallLogNamesItBothWays() {
        // Issue #30's call: Google Phone's caller ID named it in the log.
        val phone = Phone(log = listOf(entry(t, false, "3034997111", "NIST Radio Station WWV")))
        val (byLog, byTelecom) = bothWays(phone, "+13034997111", Format.DATE_AND_CALLER)
        assertTrue(byLog, byLog.endsWith("_out_NIST Radio Station WWV.ogg"))
        assertEquals(byLog, byTelecom)
    }

    @Test fun aContactsNameComesFirstBothWays() {
        val phone = Phone(
            contacts = mapOf("3034997111" to contact("Priya Raman", 7)),
            log = listOf(entry(t, false, "3034997111", "Someone Else")),
        )
        val (byLog, byTelecom) = bothWays(phone, "+13034997111", Format.DATE_AND_CALLER)
        assertTrue(byLog, byLog.endsWith("_out_Priya Raman.ogg"))
        assertEquals(byLog, byTelecom)
    }

    @Test fun withNoNameAnywhereTheNumberIsTheLogsBothWays() {
        val phone = Phone(log = listOf(entry(t, false, "3034997111")))
        val (byLog, byTelecom) = bothWays(phone, "+13034997111", Format.DATE_AND_CALLER)
        assertTrue(byLog, byLog.endsWith("_out_3034997111.ogg"))
        assertEquals(byLog, byTelecom)
    }

    @Test fun honorsFormatCarriesTheLogsNumberBothWays() {
        // Honor's Phone app finds a recording by the number in its name: the
        // number has to be the call log's.
        val phone = Phone(
            contacts = mapOf("3034997111" to contact("Priya Raman", 7)),
            log = listOf(entry(t, true, "3034997111")),
        )
        val (byLog, byTelecom) = bothWays(phone, "+13034997111", Format.HONOR, incoming = true)
        assertTrue(byLog, byLog.startsWith("Priya Raman@3034997111_"))
        assertEquals(byLog, byTelecom)
    }

    @Test fun aHiddenNumberIsNamedByDateBothWays() {
        val phone = Phone(log = listOf(entry(t, true, null)))
        val (byLog, byTelecom) = bothWays(phone, null, Format.DATE_AND_CALLER, incoming = true)
        assertTrue(byLog, byLog.endsWith("_in.ogg"))
        assertEquals(byLog, byTelecom)
    }

    @Test fun anotherCallsEntryIsNotTakenForThisOnesName() {
        // The log has not written this call yet; its closest entry is another
        // number's. Telecom's number stands in rather than that call's name.
        val phone = Phone(log = listOf(entry(t - 30 * s, false, "5550100", "Pizza Place")))
        val call = Tracked(t, false, "+13034997111", listOf(t + 60), t + 20 * s)
        val name = saved(decide(phone, listOf(call))).single()
        assertTrue(name, name.endsWith("_out_+13034997111.ogg"))
    }

    @Test fun theCallLogIsWaitedForAsBefore() {
        val phone = Phone(log = listOf(entry(t, false, "3034997111", "NIST Radio Station WWV")), logLag = 3)
        val call = Tracked(t, false, "+13034997111", listOf(t + 60), t + 20 * s)
        val name = saved(decide(phone, listOf(call), tries = 8)).single()
        assertTrue(name, name.endsWith("_NIST Radio Station WWV.ogg"))
        assertEquals(3, phone.pauses)
    }

    @Test fun datedNamesWithoutRulesAskNothing() {
        val phone = Phone(log = listOf(entry(t, false, "3034997111", "NIST Radio Station WWV")))
        val call = Tracked(t, false, "+13034997111", listOf(t + 60), t + 20 * s)
        decide(phone, listOf(call), settings = settings(Format.DATE))
        decide(phone, emptyList(), settings = settings(Format.DATE))
        assertEquals(0, phone.logReads)
    }

    // --- kept when unsure -------------------------------------------------------

    @Test fun withoutTheCallLogNothingIsLeftOut() {
        val phone = Phone(callLogAllowed = false)
        val call = Tracked(t, false, "+13034997111", listOf(t + 60), t + 20 * s)
        val rules = settings(ruled = true, keeps = { false })
        assertTrue(decide(phone, listOf(call), settings = rules) is Decision.Save)
        assertTrue(decide(phone, emptyList(), settings = rules) is Decision.Save)
    }

    @Test fun whenTheContactsCannotBeAskedNothingIsLeftOut() {
        val phone = Phone(contactsFail = true, log = listOf(entry(t, false, "3034997111")))
        val call = Tracked(t, false, "+13034997111", listOf(t + 60), t + 20 * s)
        val rules = settings(ruled = true, keeps = { false })
        assertTrue(decide(phone, listOf(call), settings = rules) is Decision.Save)
        assertTrue(decide(phone, emptyList(), settings = rules) is Decision.Save)
    }

    // --- the rules and the cut ---------------------------------------------------

    @Test fun theRulesLeaveOutTheSameCallBothWays() {
        val phone = Phone(log = listOf(entry(t, false, "3034997111", seconds = 19)))
        val strangersOut = settings(ruled = true, keeps = { ids -> ids.isNotEmpty() })
        val call = Tracked(t, false, "+13034997111", listOf(t + 60), t + 20 * s)
        val byLog = decide(phone, emptyList(), settings = strangersOut)
        val byTelecom = decide(phone, listOf(call), settings = strangersOut)
        assertEquals(Decision.LeaveOut(listOf(SavePlan.LeftOut(t + 60, false, 19))), byLog)
        assertEquals(Decision.LeaveOut(listOf(SavePlan.LeftOut(t, false, 19))), byTelecom)
    }

    @Test fun withoutTrackingAWaitingCallKeepsTheFileItIsIn() {
        // No Telecom account: a stranger's call (left out), and a contact's
        // call waiting during it. The file cannot be cut, so it is kept whole
        // rather than deleted with the contact's call in it.
        val phone = Phone(
            contacts = mapOf("+15550101" to contact("Priya Raman", 7)),
            log = listOf(entry(t, true, "+15550199", seconds = 40), entry(t + 20 * s, true, "+15550101", seconds = 15)),
        )
        val strangersOut = settings(ruled = true, keeps = { ids -> ids.isNotEmpty() })
        assertTrue(decide(phone, emptyList(), incoming = true, start = t + 2 * s, settings = strangersOut) is Decision.Save)
        // With no call waiting, the stranger's call is left out as before.
        val alone = Phone(log = listOf(entry(t, true, "+15550199", seconds = 40)))
        assertTrue(decide(alone, emptyList(), incoming = true, start = t + 2 * s, settings = strangersOut) is Decision.LeaveOut)
    }

    @Test fun anUnansweredCallIsNotSaved() {
        val unanswered = Tracked(t, false, "+13034997111", emptyList(), t + 30 * s)
        assertEquals(Decision.NotAnswered(t + 60, false), decide(Phone(), listOf(unanswered)))
    }

    @Test fun aRedialAfterAnUnansweredCallIsSavedNotDeleted() {
        // The whole file was deleted as "not answered" when the first call
        // was the only one known to be in it.
        val first = Tracked(t, false, "+13034997111", emptyList(), t + 10 * s)
        val redial = Tracked(t + 11 * s, false, "+13034997111", listOf(t + 15 * s), t + 30 * s)
        val tracked = CallTracking.callsIn(listOf(first, redial), t + 60, incoming = false)
        val decision = decide(Phone(), tracked, settings = settings(Format.DATE))
        decision as Decision.Save
        assertEquals(1, decision.parts.size)
        assertEquals(t + 15 * s, decision.parts.single().startMillis)
    }

    @Test fun theRingingIsCutAway() {
        // Dialled at t, recording from t + 60 ms, answered 3.6 s on.
        val call = Tracked(t, false, "+13034997111", listOf(t + 3_660), t + 17 * s)
        val part = (decide(Phone(), listOf(call), settings = settings(Format.DATE)) as Decision.Save).parts.single()
        assertEquals(listOf(180L until 847L), part.packets)
    }

    @Test fun callWaitingKeepsOneCallAndLeavesOutTheOther() {
        // A contact's call; a stranger's came in at 20 s and was taken at 30 s.
        val phone = Phone(
            contacts = mapOf("+15550101" to contact("Priya Raman", 7)),
            log = listOf(entry(t, true, "+15550101"), entry(t + 20 * s, true, "+15550199")),
        )
        val first = Tracked(t, true, "+15550101", listOf(t + 2 * s, t + 50 * s), t + 70 * s)
        val second = Tracked(t + 20 * s, true, "+15550199", listOf(t + 30 * s), t + 50 * s)
        val strangersOut = settings(ruled = true, keeps = { ids -> ids.isNotEmpty() })
        val decision = decide(phone, listOf(first, second), incoming = true, start = t + 2 * s, settings = strangersOut)
        decision as Decision.Save
        assertEquals(1, decision.parts.size)
        assertTrue(decision.parts.single().name, decision.parts.single().name.endsWith("_in_Priya Raman.ogg"))
        assertEquals(listOf(SavePlan.LeftOut(t + 20 * s, true, 20)), decision.leftOut)
        // Nothing to save whole should the cut catch nothing: the stranger
        // is in the file too.
        assertEquals(null, SavePlan.wholeIfNothingFits(decision, listOf(first, second)))
    }

    @Test fun aCallWhoseCutCatchesNothingIsSavedWholeNotDeleted() {
        // Issue #40: the cut fell past every packet, and the call was
        // deleted as one with no audio.
        val call = Tracked(t, false, "+13034997111", listOf(t + 32 * s), t + 52 * s)
        val decision = decide(Phone(), listOf(call), settings = settings(Format.DATE)) as Decision.Save
        val whole = SavePlan.wholeIfNothingFits(decision, listOf(call))!!
        assertEquals(decision.parts.single().name, whole.name)
        assertEquals(listOf(0L until Long.MAX_VALUE), whole.packets)
    }

    @Test fun twoCallsKeptAreNotSavedWholeIntoOne() {
        val first = Tracked(t, true, "+15550101", listOf(t + 2 * s, t + 50 * s), t + 70 * s)
        val second = Tracked(t + 20 * s, true, "+15550199", listOf(t + 30 * s), t + 50 * s)
        val decision = decide(Phone(), listOf(first, second), incoming = true, start = t + 2 * s) as Decision.Save
        assertEquals(2, decision.parts.size)
        assertEquals(null, SavePlan.wholeIfNothingFits(decision, listOf(first, second)))
    }
}
