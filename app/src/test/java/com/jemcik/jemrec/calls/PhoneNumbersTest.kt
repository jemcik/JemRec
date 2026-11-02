package com.jemcik.jemrec.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The number helpers in one place; FileNamesTest, NumberFileNamesTest and
 *  CallLogMatchTest hold them through their old names as well. */
class PhoneNumbersTest {

    @Test fun anEmergencyNumberIsAskedAboutByItsDigitsAndPlus() {
        assertEquals("112", PhoneNumbers.emergencyDigits("1-1-2"))
        assertEquals("112", PhoneNumbers.emergencyDigits(" *112# "))
        assertEquals("+1911", PhoneNumbers.emergencyDigits("+1 (911)"))
        assertEquals("", PhoneNumbers.emergencyDigits("Private"))
    }

    @Test fun digitsKeepALeadingPlus() {
        assertEquals("+447700900123", PhoneNumbers.digits("+44 (7700) 900-123"))
        assertEquals("0501234567", PhoneNumbers.digits(" 050 123 45 67 "))
        assertNull(PhoneNumbers.digits("Private"))
    }

    @Test fun dialledIsTheNetworkPartWithoutAHideOrShowCode() {
        assertEquals("0501234567", PhoneNumbers.dialled("0501234567,123"))
        assertEquals("0501234567", PhoneNumbers.dialled("0501234567;9"))
        assertEquals("0501234567", PhoneNumbers.dialled("#31#0501234567"))
        assertEquals("0501234567", PhoneNumbers.dialled("*31#0501234567"))
        assertNull(PhoneNumbers.dialled("*86"))
        assertNull(PhoneNumbers.dialled(null))
    }

    @Test fun theSameNumberWrittenTwoWays() {
        assertTrue(PhoneNumbers.sameNumber("+13034997111", "3034997111"))
        assertFalse(PhoneNumbers.sameNumber("477", "1477"))
        assertFalse(PhoneNumbers.sameNumber("", ""))
    }
}
