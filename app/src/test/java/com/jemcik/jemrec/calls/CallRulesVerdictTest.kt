package com.jemcik.jemrec.calls

import android.Manifest
import androidx.core.content.edit
import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.recordings.FakeContacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * The rules' two questions: whose call this is to keep while it rings
 * (verdict, for the early skip and the answering machine), and whether the
 * rules have a say in a recording at all when it is saved (applies).
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class CallRulesVerdictTest {

    private val context = RuntimeEnvironment.getApplication()
    private val number = "+380501234567"
    private val t = 1_795_000_000_000L

    private fun allowed() =
        shadowOf(context).grantPermissions(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS)

    @Test fun withNoRulesEveryCallIsKept() {
        assertEquals(true, CallRules.verdict(context, number))
        assertFalse(CallRules.applies(context, t))
    }

    @Test fun withoutTheCallLogOrTheContactsItCannotBeTold() {
        CallRules.setOtherNumbers(context, false)
        assertNull(CallRules.verdict(context, number))
        shadowOf(context).grantPermissions(Manifest.permission.READ_CALL_LOG)
        assertNull(CallRules.verdict(context, number))
    }

    @Test fun aLookupThatFailsCannotBeTold() {
        CallRules.setOtherNumbers(context, false)
        allowed()
        // No contacts provider at all: no answer, which is not "no contact".
        assertNull(CallRules.verdict(context, number))
    }

    @Test fun aContactIsKeptAndAStrangerLeftOut() {
        CallRules.setOtherNumbers(context, false)
        allowed()
        val contacts = FakeContacts.install()
        assertEquals(false, CallRules.verdict(context, number))
        contacts.known[number] = 7L to "Mum"
        assertEquals(true, CallRules.verdict(context, number))
    }

    @Test fun theRulesApplyWithTheContactsAndNotToACallRecordedInAskFirst() {
        CallRules.setOtherNumbers(context, false)
        assertFalse("not without the contacts", CallRules.applies(context, t))
        allowed()
        assertTrue(CallRules.applies(context, t))
        Prefs.of(context).edit { putStringSet("coverage_history", setOf("${t - 1}:ASK_FIRST")) }
        assertFalse(CallRules.applies(context, t))
        assertTrue("a recording from before the history: as things are now", CallRules.applies(context, t - 2))
    }
}
