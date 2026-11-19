package com.jemcik.jemrec.recordings

import com.jemcik.jemrec.calls.CallLogLookup
import com.jemcik.jemrec.calls.CallMatch
import com.jemcik.jemrec.calls.CallTracking
import com.jemcik.jemrec.calls.CallTracking.Tracked
import com.jemcik.jemrec.recordings.FileNames.Format
import com.jemcik.jemrec.recordings.SavePlan.Decision
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
     *  entries only after [logLag] seconds of waiting - all but [onTime],
     *  written already (the call before). */
    private class Phone(
        val contacts: Map<String, CallLogLookup.Contact> = emptyMap(),
        val log: List<CallLogLookup.Entry> = emptyList(),
        override val callLogAllowed: Boolean = true,
        val contactsFail: Boolean = false,
        val logLag: Int = 0,
        override val country: String? = null,
        val onTime: List<CallLogLookup.Entry> = emptyList(),
    ) : SavePlan.Lookups {
        private val written get() = if (pauses < logLag) onTime else log

        var pauses = 0
        var logReads = 0

        override fun contact(number: String?): CallLogLookup.Contact {
            if (contactsFail) return CallLogLookup.Contact(null, null, false, failed = true)
            val match = contacts.entries.firstOrNull { CallLogLookup.sameNumber(it.key, number) }
            return match?.value ?: CallLogLookup.Contact(null, null, false)
        }

        override fun logged(start: Long, incoming: Boolean): CallLogLookup.Entry? {
            logReads++
            // In the window the phone's lookup has (CallLogLookup.callFor,
            // CallMatch.closest): with none, a call minutes away was found
            // and no test could see a lookup made at the wrong time (review
            // round 13).
            return CallMatch.closest(written, start, incoming)
        }

        override fun loggedBetween(from: Long, to: Long): List<CallLogLookup.Entry> =
            written.filter { it.at in from..to }

        override suspend fun pause() {
            pauses++
        }
    }

    private fun contact(name: String, id: Long) = CallLogLookup.Contact(name, null, false, id)

    private fun entry(at: Long, incoming: Boolean, number: String?, name: String? = null, seconds: Int = 17, country: String? = null) =
        CallLogLookup.Entry(at, incoming, name ?: number ?: "", number, seconds, name, country)

    private fun settings(format: Format = Format.DATE_AND_CALLER, ruled: Boolean = false, keeps: (List<Long>) -> Boolean = { true }) =
        SavePlan.Settings(format, ruled, keeps)

    private fun decide(
        phone: Phone,
        tracked: List<Tracked>,
        incoming: Boolean = false,
        start: Long = t + 60,
        settings: SavePlan.Settings = settings(),
        tries: Int = 1,
        joined: Boolean = false,
    ): Decision = runBlocking { SavePlan.decide(phone, settings, start, incoming, tracked, tries, joined) }

    private fun saved(decision: Decision): List<String> = (decision as Decision.Save).parts.map { it.name }

    /** The same call, both ways: by the call log, and by Telecom. Telecom
     *  writes the number its own way ([telecomNumber]); the log its way. */
    private fun bothWays(phone: Phone, telecomNumber: String?, format: Format, incoming: Boolean = false): Pair<String, String> {
        val call = Tracked(t, incoming, telecomNumber, listOf(t + 60), t + 20 * s)
        val byLog = saved(decide(phone, emptyList(), incoming, settings = settings(format))).single()
        val byTelecom = saved(decide(phone, listOf(call), incoming, settings = settings(format))).single()
        return byLog to byTelecom
    }

    // --- a recording joined mid-call (DaemonFile.joined; review round 10) ---------

    /** Tracking off: the log's call up at the recording's start, whichever
     *  way - its caller ruled, its way in the name, not the recorder's
     *  guess. As any other recording, the call 4 minutes before it is none. */
    @Test fun aJoinedRecordingIsTheLogsCallUpAtItsStart() {
        val phone = Phone(
            contacts = mapOf("3034997111" to contact("Priya Raman", 7)),
            log = listOf(entry(t, true, "3034997111", seconds = 400)),
        )
        val start = t + 240 * s
        val kept = saved(decide(phone, emptyList(), incoming = false, start = start, joined = true)).single()
        assertTrue(kept, kept.endsWith("_in_Priya Raman.ogg"))
        val ruled = settings(ruled = true, keeps = { 7L !in it })
        val leftOut = decide(phone, emptyList(), incoming = false, start = start, settings = ruled, joined = true)
        assertTrue("the rules leave its caller out", leftOut is Decision.LeaveOut)
        assertTrue((leftOut as Decision.LeaveOut).leftOut.single().incoming)
        val asAnyOther = saved(decide(phone, emptyList(), incoming = false, start = start, settings = ruled)).single()
        assertFalse("as any other: no caller, kept", asAnyOther.contains("Priya"))
    }

    /** A waiting call dated after the recording's start, or one ended
     *  before it, is not the call up at its start (review round 11). */
    @Test fun aJoinedRecordingIsNotALaterOrAnEndedCall() {
        val phone = Phone(
            log = listOf(
                entry(t, true, "3034997111", "Up", seconds = 400),
                entry(t + 250 * s, false, "5550000", "Waiting", seconds = 60),
                entry(t - 300 * s, false, "5551111", "Before", seconds = 100),
            ),
        )
        val kept = saved(decide(phone, emptyList(), incoming = false, start = t + 240 * s, joined = true)).single()
        assertTrue(kept, kept.endsWith("_in_Up.ogg"))
    }

    /** The call's own entry not written yet as the hang-up's save asks:
     *  the call before, ended two minutes before the recorder came back, is
     *  not taken by the slack at once - it was named and ruled by it - but
     *  the log waited for (review round 12). */
    @Test fun aJoinedRecordingWaitsForItsOwnCallsEntry() {
        val before = entry(t - 300 * s, false, "5551111", "Before", seconds = 180)
        val own = entry(t - 60 * s, true, "3034997111", "Own", seconds = 400)
        val phone = Phone(log = listOf(before, own), onTime = listOf(before), logLag = 3)
        val kept = saved(decide(phone, emptyList(), incoming = false, start = t, tries = 8, joined = true)).single()
        assertTrue(kept, kept.endsWith("_in_Own.ogg"))
    }

    /** Call waiting with tracking off: the recorder back while the waiting
     *  call B is up, C on hold. B's caller left out by the rules does not
     *  leave out the file, which holds C's audio after B - C, up at its
     *  start and dated before B, is another call in it (review round 12). */
    @Test fun aJoinedRecordingHoldingTheHeldCallIsNotLeftOutForTheWaitingOne() {
        val phone = Phone(
            contacts = mapOf("5550000" to contact("Left Out", 9)),
            log = listOf(
                entry(t, true, "3034997111", "Held", seconds = 400),
                entry(t + 100 * s, false, "5550000", "Waiting", seconds = 60),
            ),
        )
        val ruled = settings(ruled = true, keeps = { 9L !in it })
        val decision = decide(phone, emptyList(), incoming = false, start = t + 120 * s, settings = ruled, joined = true)
        assertTrue(decision.toString(), decision is Decision.Save)
    }

    /** Each part of a joined recording carries its call's start, which
     *  matches it to its call; a call answered before the recording began
     *  starts at its first packet - dated by its answer, it took recording
     *  1's part's name, "(1)" (review round 12). */
    @Test fun aJoinedRecordingsPartsCarryTheirCallsStart() {
        val a = Tracked(t, true, "3034997111", listOf(t + 8 * s, t + 6 * 60 * s, t + 630 * s), t + 12 * 60 * s)
        val b = Tracked(t + 290 * s, true, "5550000", listOf(t + 5 * 60 * s, t + 8 * 60 * s), t + 10 * 60 * s)
        val start = t + 7 * 60 * s
        val parts = (decide(Phone(), listOf(a, b), start = start, joined = true) as Decision.Save).parts
        assertEquals(listOf(a.id, b.id), parts.map { it.callAt })
        assertEquals(listOf(start, start + 60 * s), parts.map { it.startMillis })
        val plain = (decide(Phone(), listOf(Tracked(t, false, "1", listOf(t + 60), t + 20 * s))) as Decision.Save).parts
        assertEquals("not joined, none", -1L, plain.single().callAt)
        val untracked = (decide(Phone(log = listOf(entry(t, true, "1", seconds = 400))), emptyList(), start = t + 240 * s, joined = true) as Decision.Save).parts
        assertEquals("by the log", t, untracked.single().callAt)
    }

    /** A held call taken back after the revive is looked up in the log at
     *  its own start, not its part's minutes later - out of the lookup's
     *  window there, it was named from Telecom's number, the log's name lost
     *  (review round 13). */
    @Test fun aJoinedRecordingsOtherCallIsLookedUpAtItsOwnStart() {
        val a = Tracked(t, true, "3034997111", listOf(t + 8 * s, t + 6 * 60 * s, t + 630 * s), t + 12 * 60 * s)
        val held = Tracked(t + 290 * s, true, "5550000", listOf(t + 5 * 60 * s, t + 8 * 60 * s), t + 10 * 60 * s)
        val phone = Phone(log = listOf(entry(t, true, "3034997111", "Own"), entry(t + 290 * s, true, "5550000", "Held Caller")))
        val parts = (decide(phone, listOf(a, held), start = t + 7 * 60 * s, joined = true) as Decision.Save).parts
        assertTrue(parts.map { it.name }.toString(), parts.any { it.name.endsWith("_in_Held Caller.ogg") })
    }

    /** Left out by the rules, a joined part is noted with what was left of
     *  the call from the revive, not its whole length on: calls minutes
     *  after its end read as left out (review round 13). */
    @Test fun aJoinedPartLeftOutIsNotedWithWhatWasLeftOfItsCall() {
        val phone = Phone(
            contacts = mapOf("3034997111" to contact("Priya Raman", 7)),
            log = listOf(entry(t, true, "3034997111", seconds = 400)),
        )
        val ruled = settings(ruled = true, keeps = { 7L !in it })
        val leftOut = decide(phone, emptyList(), start = t + 240 * s, settings = ruled, joined = true) as Decision.LeaveOut
        assertEquals(160, leftOut.leftOut.single().seconds)
    }

    /** The same with tracking on: its own call's seconds from the revive,
     *  not from its answer - the entry ran past the call's end (review
     *  round 14). */
    @Test fun aTrackedJoinedCallLeftOutIsNotedWithWhatWasLeftOfIt() {
        val start = t + 8 * 60 * s
        val own = Tracked(t, true, "3034997111", listOf(t + 8 * s), t + 10 * 60 * s)
        val phone = Phone(contacts = mapOf("3034997111" to contact("Left Out", 7)))
        val leftOut = decide(phone, listOf(own), incoming = true, start = start, settings = settings(ruled = true, keeps = { 7L !in it }), joined = true)
        assertEquals(120, (leftOut as Decision.LeaveOut).leftOut.single().seconds)
    }

    /** A joined file's other call active at its first slot: its part a
     *  second on, not the file's own second - one name for two, and one of
     *  them unknown to the ledger (review round 13). */
    @Test fun aJoinedRecordingsPartsNeverShareTheirStart() {
        val start = t + 7 * 60 * s
        val waiting = Tracked(t + 290 * s, true, "5550000", listOf(t + 5 * 60 * s), t + 10 * 60 * s)
        val own = Tracked(t, true, "3034997111", listOf(t + 8 * s, start + 2 * s), t + 12 * 60 * s)
        val parts = (decide(Phone(), listOf(own, waiting), start = start, joined = true) as Decision.Save).parts
        assertEquals(setOf(start, start + 1_000), parts.map { it.startMillis }.toSet())
    }

    /** Its own call left out, a waiting call kept: the file noted at its own
     *  start, which the next collection knows it by - noted at its call's,
     *  a kept part outliving its save was saved again, "(1)" (review round
     *  13). */
    @Test fun anOwnCallLeftOutIsNotedAtTheFilesStart() {
        val start = t + 60
        val own = Tracked(t, true, "3034997111", listOf(start), t + 120 * s)
        val waiting = Tracked(t + 30 * s, true, "5550000", listOf(t + 40 * s), t + 80 * s)
        val phone = Phone(contacts = mapOf("3034997111" to contact("Left Out", 7)))
        val decision = decide(phone, listOf(own, waiting), incoming = true, start = start, settings = settings(ruled = true, keeps = { 7L !in it }))
        assertEquals(start, (decision as Decision.Save).leftOut.single().start)
    }

    /** A call's part, known before the write, so its tags carry it: saved
     *  among others, joined, marked by the recorder as stopped early, or
     *  noted as a call's part - not a whole call that ran to its end
     *  (review rounds 13 and 14). */
    @Test fun aPartIsKnownBeforeItIsWritten() {
        fun file(name: String) = DaemonFile.parse(name)
        assertTrue(SavePlan.isPart(true, file("jemrec_rec_${t}_in.dat"), false))
        assertTrue(SavePlan.isPart(false, file("jemrec_rec_${t}_joined_in.dat"), false))
        assertTrue(SavePlan.isPart(false, file("jemrec_rec_${t}_early20000_in.dat"), false))
        assertTrue(SavePlan.isPart(false, file("jemrec_rec_${t}_in.dat"), true))
        assertFalse(SavePlan.isPart(false, file("jemrec_rec_${t}_in.dat"), false))
        assertFalse("a stop within the run-on", SavePlan.isPart(false, file("jemrec_rec_${t}_early2000_in.dat"), false))
    }

    /** Its way is asked of the log even when nothing needs who it was: the
     *  date-only name says which way. */
    @Test fun aJoinedRecordingsWayComesFromTheLogInADateOnlyName() {
        val phone = Phone(log = listOf(entry(t, true, "3034997111", seconds = 400)))
        val name = saved(decide(phone, emptyList(), incoming = false, start = t + 240 * s, settings = settings(Format.DATE), joined = true)).single()
        assertTrue(name, name.endsWith("_in.ogg"))
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

    // The number format writes the number with its country code, which needs
    // Android's phone number library: NumberFileNamesTest has that. Here, the
    // same name both ways, and the country it is read in. With no country, as
    // in the first, a phone too keeps the log's digits.

    @Test fun theNumberFormatLeavesOutContactAndCallerIdNamesBothWays() {
        listOf(true, false).forEach { incoming ->
            listOf(emptyMap(), mapOf("3034997111" to contact("Priya Raman", 7))).forEach { contacts ->
                val phone = Phone(contacts = contacts, log = listOf(entry(t, incoming, "3034997111", "Caller ID")))
                val (byLog, byTelecom) = bothWays(phone, "+13034997111", Format.DATE_AND_NUMBER, incoming)
                assertTrue(byLog, byLog.endsWith("_${if (incoming) "in" else "out"}_3034997111.ogg"))
                assertEquals(byLog, byTelecom)
            }
        }
    }

    @Test fun theNumberIsReadInTheCallsCountryElseThePhonesBothWays() {
        val call = Tracked(t, false, "+13034997111", listOf(t + 60), t + 20 * s)
        fun countries(phone: Phone) = runBlocking {
            listOf(
                SavePlan.fromCallLog(phone, t + 60, false, 1)?.party?.country,
                SavePlan.fromTelecom(phone, call, t + 60, named = true, tries = 1)?.party?.country,
            )
        }
        assertEquals(listOf("US", "US"), countries(Phone(log = listOf(entry(t, false, "3034997111", country = "US")), country = "ua")))
        assertEquals(listOf("ua", "ua"), countries(Phone(log = listOf(entry(t, false, "3034997111")), country = "ua")))
        // Not in the log yet: Telecom's number, read where the phone is.
        assertEquals(listOf(null, "ua"), countries(Phone(country = "ua")))
    }

    @Test fun theNumberFormatIsTheDateWithoutTheCallLogBothWays() {
        val phone = Phone(
            contacts = mapOf("3034997111" to contact("Priya Raman", 7)),
            log = listOf(entry(t, false, "3034997111")),
            callLogAllowed = false,
        )
        val (byLog, byTelecom) = bothWays(phone, "+13034997111", Format.DATE_AND_NUMBER)
        assertTrue(byLog, byLog.endsWith("_out.ogg"))
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
        // At the file's start both ways: what the next collection knows the
        // file by (review round 13).
        assertEquals(Decision.LeaveOut(listOf(SavePlan.LeftOut(t + 60, false, 19))), byTelecom)
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

    @Test fun aRecordingEndingWellBeforeItsCallStoppedEarly() {
        // 50 packets from 1000: it ends at 2000.
        assertEquals(18_000L, SavePlan.stoppedEarlyMs(start = 1_000, lastSlot = 49, endedAt = 20_000))
        // Within the capture's lag of the end, or past it: not early.
        assertEquals(null, SavePlan.stoppedEarlyMs(start = 1_000, lastSlot = 49, endedAt = 2_000 + SavePlan.STOPPED_EARLY_MS))
        assertEquals(null, SavePlan.stoppedEarlyMs(start = 1_000, lastSlot = 149, endedAt = 3_000))
    }

    /** The recorder's mark: over STOPPED_EARLY_MS. */
    @Test fun theRecordersMarkCountsPastTheLag() {
        assertEquals(30_000L, SavePlan.markedEarlyMs(30_000))
        assertEquals(null, SavePlan.markedEarlyMs(SavePlan.STOPPED_EARLY_MS))
        assertEquals(null, SavePlan.markedEarlyMs(null))
    }

    /** A recording carried on by another of the same call - a recorder
     *  ended mid-call and brought back while it was up - did not lose the
     *  call's last part; one started before it stopped, or after the call,
     *  carries nothing on. */
    @Test fun aSecondRecordingOfTheCallCarriesItOn() {
        val start = 1_000_000L
        val lastSlot = 99L // stopped 2 s in
        val stopped = start + 100 * CallTracking.PACKET_MS
        val end = start + 60_000
        assertTrue(SavePlan.carriedOn(start, lastSlot, end, listOf(stopped + 15_000)))
        assertFalse(SavePlan.carriedOn(start, lastSlot, end, listOf(start)))
        assertFalse(SavePlan.carriedOn(start, lastSlot, end, listOf(end + 1)))
        assertFalse(SavePlan.carriedOn(start, lastSlot, end, emptyList()))
        // Carried on: not said, but a part of the call - no short call
        // (review round 15).
        val carried = StoppedEarly.check(start, lastSlot, end, listOf(stopped + 15_000))
        assertTrue(carried?.carried == true)
        assertFalse(StoppedEarly.check(start, lastSlot, end, emptyList())!!.carried)
    }
}
