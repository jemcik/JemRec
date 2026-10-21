package com.jemcik.jemrec.capture

import com.jemcik.jemrec.capture.CallRules.Contacts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Whose calls to keep, for a number that can belong to several contacts, or
 *  to none, and when the chosen contacts could not be read. */
class CallRulesContactsTest {

    private val x = 1L
    private val y = 2L

    @Test fun aSharedNumberIsKeptIfAnyOfItsContactsIs() {
        // "Only these people": Y is chosen; the number is X's and Y's.
        assertTrue(CallRules.keeps(Contacts.ONLY, false, listOf(x, y), setOf(y)))
        // "All but these": X is left out; Y, with the same number, is not.
        assertTrue(CallRules.keeps(Contacts.ALL_BUT, false, listOf(x, y), setOf(x)))
        // Both left out: left out.
        assertFalse(CallRules.keeps(Contacts.ALL_BUT, true, listOf(x, y), setOf(x, y)))
    }

    @Test fun chosenContactsThatCouldNotBeReadLeaveNothingOut() {
        assertTrue(CallRules.keeps(Contacts.ONLY, false, listOf(x), null))
        assertTrue(CallRules.keeps(Contacts.ALL_BUT, false, listOf(x), null))
    }

    @Test fun aNumberInNoContactIsOtherNumbers() {
        assertTrue(CallRules.keeps(Contacts.ONLY, true, emptyList(), setOf(x)))
        assertFalse(CallRules.keeps(Contacts.ONLY, false, emptyList(), setOf(x)))
    }

    @Test fun allAndNoneNeedNoChosenContacts() {
        assertTrue(CallRules.keeps(Contacts.ALL, false, listOf(x), null))
        assertFalse(CallRules.keeps(Contacts.NONE, true, listOf(x), null))
    }
}
