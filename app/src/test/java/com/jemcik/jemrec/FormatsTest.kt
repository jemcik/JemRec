package com.jemcik.jemrec

import org.junit.Assert.assertEquals
import org.junit.Test

/** The small formats the app prints in several places (Formats.kt); what
 *  the four places print with m:ss, and hex's two fixed outputs, are held by
 *  SmallFormatsTest. */
class FormatsTest {

    @Test fun hexIsTwoLowerCaseDigitsAByte() {
        assertEquals("", hex(ByteArray(0)))
        // A negative byte is its unsigned value: -1 is ff, not -1.
        assertEquals("000f10ff7f80", hex(byteArrayOf(0, 15, 16, -1, 127, -128)))
        assertEquals("000f10ff7f80", hex(listOf<Byte>(0, 15, 16, -1, 127, -128)))
    }

    @Test fun minutesThenTwoDigitsOfSeconds() {
        assertEquals("0:00", minutesSeconds(0))
        assertEquals("0:07", minutesSeconds(7))
        assertEquals("0:59", minutesSeconds(59))
        assertEquals("1:00", minutesSeconds(60))
        assertEquals("59:59", minutesSeconds(3599))
        // No hours: a long call is many minutes.
        assertEquals("60:00", minutesSeconds(3600))
        assertEquals("125:07", minutesSeconds(7507))
    }

    /** Latin digits whatever the phone's language: Arabic's own are not in
     *  the looks' fonts. */
    @Test fun latinDigitsInAnyLanguage() {
        val before = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG"))
            assertEquals("3:21", minutesSeconds(201))
            assertEquals("0aff", hex(byteArrayOf(10, -1)))
        } finally {
            java.util.Locale.setDefault(before)
        }
    }
}
