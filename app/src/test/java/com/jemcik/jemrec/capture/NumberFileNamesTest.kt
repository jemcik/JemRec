package com.jemcik.jemrec.capture

import com.jemcik.jemrec.capture.FileNames.Format
import com.jemcik.jemrec.capture.FileNames.Party
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The number format (issue #71): the number with its country code, in one
 * place in every name, for a program that sorts recordings by it. On
 * Robolectric because the country code comes from Android's own phone
 * number library (PhoneNumberUtils), which a plain test does not have.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class NumberFileNamesTest {

    private val at = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse("20261020_165705")!!.time

    private fun named(party: Party?, incoming: Boolean = false) = FileNames.build(Format.DATE_AND_NUMBER, at, incoming, party)

    @Test fun aContactGetsTheNumberNotTheName() {
        val priya = Party("+44 7700 900123", "Priya Shah", "GB")
        assertEquals("20261020_165705_out_+447700900123.ogg", named(priya))
        assertEquals("20261020_165705_in_+447700900123.ogg", named(priya, incoming = true))
    }

    @Test fun oneNumberWhicheverWayItWasWritten() {
        // Dialled without the code, and as the network sends it in.
        val out = named(Party("050 123 45 67", "Ivan", "UA"))
        val back = named(Party("+380501234567", null, "UA"), incoming = true)
        assertEquals("20261020_165705_out_+380501234567.ogg", out)
        assertEquals("20261020_165705_in_+380501234567.ogg", back)
        // The phone's own country comes lower case (TelephonyManager).
        assertEquals(out, named(Party("0501234567", null, "ua")))
    }

    @Test fun whatHasNoCountryCodeKeepsItsDigits() {
        // A short code is no number Android can give a code to; nor is a
        // local number with no country to read it in.
        assertEquals("20261020_165705_out_477.ogg", named(Party("477", null, "UA")))
        assertEquals("20261020_165705_out_0501234567.ogg", named(Party("050 123 45 67", null, null)))
        // Nor in a country where it is no number: no US number starts with 0.
        // The emulator check counts on it to tell the call log's country from
        // the network's (tools/emulator_checks.py, number_names).
        assertEquals("20261020_165705_out_0501234567.ogg", named(Party("0501234567", null, "US")))
    }

    @Test fun aCodeIsNoNumber() {
        // Caller ID hidden or shown for this call: the number after the code.
        assertEquals("20261020_165705_out_+380501234567.ogg", named(Party("#31#0501234567", null, "UA")))
        assertEquals("20261020_165705_out_+380501234567.ogg", named(Party("*31#0501234567", null, "UA")))
        // Any other code: Android gives it no country code, and its digits -
        // 86, 06 - were never a number.
        listOf("*86", "*#06#").forEach { assertEquals("20261020_165705_out.ogg", named(Party(it, null, "UA"))) }
    }

    @Test fun anExtensionIsNoPartOfTheNumber() {
        // A pause (,) or a wait (;), then an extension: dialled once the call
        // is up. Before, it ran on into the digits where Android could not
        // give a country code: 477;1 was 4771.
        assertEquals("20261020_165705_out_+380501234567.ogg", named(Party("0501234567,123", null, "UA")))
        assertEquals("20261020_165705_out_477.ogg", named(Party("477;1", null, "UA")))
        assertEquals("20261020_165705_out_2015550123.ogg", named(Party("2015550123,1", null, "UA")))
    }

    @Test fun noNumberLeavesTheDateNeverAName() {
        listOf(null, Party(null, "Priya Shah", "GB"), Party("", "Priya Shah", "GB"), Party("Private", "Priya Shah", "GB"))
            .forEach { party ->
                listOf(true, false).forEach { incoming ->
                    assertEquals(FileNames.build(Format.DATE, at, incoming, party), named(party, incoming))
                }
            }
    }

    @Test fun itReadsBack() {
        val parsed = FileNames.parse("20261020_165705_out_+380501234567.ogg")!!
        assertEquals(at, parsed.startedAt)
        assertEquals(false, parsed.incoming)
        // What the list shows when it may not read the call log.
        assertEquals("+380501234567", parsed.caller)
    }

    @Test fun aMessengerCallHasNoNumberSoNoCaller() {
        listOf(true, false, null).forEach { incoming ->
            val name = FileNames.buildVoip(Format.DATE_AND_NUMBER, at, incoming, "whatsapp", "Priya Raman")
            assertEquals(FileNames.buildVoip(Format.DATE, at, incoming, "whatsapp", "Priya Raman"), name)
            val parsed = FileNames.parse(name)!!
            assertEquals("whatsapp", parsed.app)
            assertNull(parsed.caller)
        }
    }
}
