package com.jemcik.jemrec.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the reply field's text becomes before it is sent to adbd as a pairing code. */
class PairingCodeTest {

    @Test fun sixDigitsPassThroughWhateverSeparatesThem() {
        assertEquals("123456", pairingCodeFrom("123456"))
        assertEquals("123456", pairingCodeFrom("123 456"))
        assertEquals("123456", pairingCodeFrom("12-34-56"))
        assertEquals("123456", pairingCodeFrom(" 123456\n"))
        assertEquals("123456", pairingCodeFrom("code: 123456"))
    }

    @Test fun anythingButSixDigitsIsNotACode() {
        assertNull(pairingCodeFrom("12345"))
        assertNull(pairingCodeFrom("1234567"))
        assertNull(pairingCodeFrom(""))
        assertNull(pairingCodeFrom("abcdef"))
        assertNull(pairingCodeFrom(null))
    }
}
