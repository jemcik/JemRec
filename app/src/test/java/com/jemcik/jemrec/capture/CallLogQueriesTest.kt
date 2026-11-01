package com.jemcik.jemrec.capture

import android.Manifest
import android.provider.CallLog
import com.jemcik.jemrec.capture.CallHistory.Kind
import com.jemcik.jemrec.capture.CallHistory.Status
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * The four reads of the phone's call log, against a provider that runs their
 * own SQL (FakeCallLog): which call types each one lets through, and that
 * each keeps only the phone's own calls (CallLogLookup.isPhoneCall). They
 * differ on purpose, and the differences are what is pinned:
 *   - the naming query (CallLogLookup.query) leaves out the five types that
 *     never went off-hook, so a vendor's own answered type is kept;
 *   - the self-test's and the missed-call check's (callStartsSince,
 *     answeredCallsSince) take INCOMING and OUTGOING only;
 *   - the Calls tab (CallHistory) reads every type and calls the ones it
 *     does not know OTHER.
 * No recorder answers here: the Calls tab's pending list comes back empty.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class CallLogQueriesTest {

    private val context = RuntimeEnvironment.getApplication()
    private val t = 1_795_000_000_000L
    private val second = 1_000L
    private lateinit var log: FakeCallLog

    /** One row of each kind, a second apart: the time says which row it is. */
    private val incoming = t + 1 * second
    private val outgoing = t + 2 * second
    private val missed = t + 3 * second
    private val voicemail = t + 4 * second
    private val rejected = t + 5 * second
    private val blocked = t + 6 * second
    private val elsewhere = t + 7 * second
    private val vendor = t + 8 * second

    @Before fun aLogWithEveryType() {
        log = FakeCallLog.install()
        log.add(incoming, CallLog.Calls.INCOMING_TYPE, seconds = 60, number = "+380441111111", name = "Anna")
        log.add(outgoing, CallLog.Calls.OUTGOING_TYPE, seconds = 30, number = "+380442222222")
        log.add(missed, CallLog.Calls.MISSED_TYPE, number = "+380443333333")
        log.add(voicemail, CallLog.Calls.VOICEMAIL_TYPE, number = "+380444444444")
        log.add(rejected, CallLog.Calls.REJECTED_TYPE, number = "+380445555555")
        log.add(blocked, CallLog.Calls.BLOCKED_TYPE, number = "+380446666666")
        log.add(elsewhere, CallLog.Calls.ANSWERED_EXTERNALLY_TYPE, seconds = 50, number = "+380447777777")
        log.add(vendor, FakeCallLog.VENDOR_TYPE, seconds = 45, number = "+380448888888")
    }

    private fun grant() = shadowOf(context).grantPermissions(Manifest.permission.READ_CALL_LOG)

    private fun naming(from: Long = t, to: Long = t + 60 * second) = CallLogLookup.entriesBetween(context, from, to)

    private fun callsTab() = runBlocking { CallHistory.load(context, emptyList(), emptyList()) }

    @Test fun theNamingQueryKeepsEveryTypeButTheFiveThatNeverWentOffHook() {
        grant()
        // Newest first; missed, voicemail, rejected, blocked and answered
        // elsewhere left out by the SQL; the vendor's type kept on purpose.
        assertEquals(listOf(vendor, outgoing, incoming), naming().map { it.at })
    }

    @Test fun theNamingQueryTakesEveryTypeButIncomingForOutgoing() {
        grant()
        val byTime = naming().associateBy { it.at }
        assertTrue(byTime.getValue(incoming).incoming)
        assertFalse(byTime.getValue(outgoing).incoming)
        // Whatever the vendor's type means, it is matched as an outgoing call.
        assertFalse(byTime.getValue(vendor).incoming)
    }

    @Test fun theNamingQueryTakesBothEndsOfItsWindow() {
        grant()
        assertEquals(listOf(outgoing, incoming), naming(from = incoming, to = outgoing).map { it.at })
        assertEquals(listOf(outgoing), naming(from = outgoing, to = outgoing).map { it.at })
    }

    @Test fun theNamingQueryReadsTheNameThenTheNumber() {
        grant()
        val at = t + 30 * second
        log.add(at, CallLog.Calls.INCOMING_TYPE, seconds = 12, number = "  ", name = " ", country = "UA")
        log.add(at + second, CallLog.Calls.OUTGOING_TYPE, seconds = 7, number = "477", country = "")
        val byTime = naming().associateBy { it.at }
        assertEquals("Anna", byTime.getValue(incoming).label)
        assertEquals("Anna", byTime.getValue(incoming).name)
        assertEquals(60, byTime.getValue(incoming).seconds)
        assertEquals("+380442222222", byTime.getValue(outgoing).label)
        assertNull(byTime.getValue(outgoing).name)
        // A blank name and number are none; a withheld number still says so.
        val withheld = byTime.getValue(at)
        assertEquals("Unknown number", withheld.label)
        assertNull(withheld.number)
        assertNull(withheld.name)
        assertEquals("UA", withheld.country)
        assertNull("a blank country is none", byTime.getValue(at + second).country)
    }

    @Test fun theNamingQueryNeedsTheCallLogPermission() {
        assertTrue(naming().isEmpty())
        assertNull(CallLogLookup.callFor(context, outgoing, false, emptyList()))
    }

    @Test fun theSaversLookupSeesTheSameTypes() {
        grant()
        // callFor runs the naming query: the vendor's row is an outgoing
        // call a recording can be named after; a missed call never is, and
        // the answered call two seconds before it is taken instead.
        assertEquals(vendor, CallLogLookup.callFor(context, vendor, false, emptyList())?.at)
        assertEquals(incoming, CallLogLookup.callFor(context, missed, true, emptyList())?.at)
    }

    @Test fun theSelfTestCountsAnsweredIncomingAndOutgoingCallsOnly() {
        assertEquals(setOf(incoming, outgoing), CallLogLookup.callStartsSince(context, t).toSet())
        // From its moment on, that moment included.
        assertEquals(setOf(outgoing), CallLogLookup.callStartsSince(context, outgoing).toSet())
    }

    @Test fun theMissedCallCheckReadsAnsweredIncomingAndOutgoingCallsOnly() {
        log.add(t + 20 * second, CallLog.Calls.OUTGOING_TYPE, seconds = 9, number = null)
        val calls = CallLogLookup.answeredCallsSince(context, t)
        // Oldest first, unlike the other two.
        assertEquals(
            listOf(
                CallLogLookup.LoggedCall(incoming, 60, true, "Anna"),
                CallLogLookup.LoggedCall(outgoing, 30, false, "+380442222222"),
                CallLogLookup.LoggedCall(t + 20 * second, 9, false, "Unknown number"),
            ),
            calls,
        )
        assertEquals(calls.drop(1), CallLogLookup.answeredCallsSince(context, outgoing))
    }

    @Test fun theCallsTabReadsEveryTypeAndCallsTheUnknownOnesOther() {
        grant()
        val calls = callsTab()
        assertEquals(listOf(vendor, elsewhere, blocked, rejected, voicemail, missed, outgoing, incoming), calls.map { it.at })
        assertEquals(
            mapOf(
                incoming to Kind.INCOMING,
                outgoing to Kind.OUTGOING,
                missed to Kind.MISSED,
                voicemail to Kind.VOICEMAIL,
                rejected to Kind.DECLINED,
                blocked to Kind.BLOCKED,
                elsewhere to Kind.OTHER,
                vendor to Kind.OTHER,
            ),
            calls.associate { it.at to it.kind },
        )
        val status = calls.associate { it.at to it.status }
        listOf(missed, voicemail, rejected, blocked, elsewhere).forEach {
            assertEquals(Status.NOT_CONNECTED, status.getValue(it))
        }
        // Pins today's behaviour, which looks like a bug: a vendor's answered
        // type, which the naming query keeps as a call, is "not connected"
        // here and never matched to its recording (to be decided with the
        // OnePlus's AOSP call log, in its own commit)
        assertEquals(Status.NOT_CONNECTED, status.getValue(vendor))
    }

    @Test fun theCallsTabNeedsTheCallLogPermission() {
        assertTrue(callsTab().isEmpty())
    }

    @Test fun eachReadKeepsThePhonesOwnCallsOnly() {
        grant()
        val from = t + 100 * second
        val phone = from + 1 * second
        val noAccount = from + 2 * second
        val blankAccount = from + 3 * second
        val meet = from + 4 * second
        val lookalike = from + 5 * second
        log.add(phone, CallLog.Calls.INCOMING_TYPE, seconds = 10)
        log.add(noAccount, CallLog.Calls.INCOMING_TYPE, seconds = 10, account = null)
        log.add(blankAccount, CallLog.Calls.INCOMING_TYPE, seconds = 10, account = "")
        // Google Meet logs its calls with the other person's number (the
        // Honor, 2026-10-26): Meet's, never a phone call.
        log.add(meet, CallLog.Calls.INCOMING_TYPE, seconds = 10, account = FakeCallLog.MEET)
        log.add(lookalike, CallLog.Calls.INCOMING_TYPE, seconds = 10, account = "com.android.phonex/x")
        val phoneCalls = setOf(phone, noAccount, blankAccount)
        assertEquals(phoneCalls, naming(from = from, to = from + 60 * second).map { it.at }.toSet())
        assertEquals(phoneCalls, CallLogLookup.callStartsSince(context, from).toSet())
        assertEquals(phoneCalls, CallLogLookup.answeredCallsSince(context, from).map { it.at }.toSet())
        assertEquals(phoneCalls, callsTab().map { it.at }.filter { it >= from }.toSet())
    }
}
