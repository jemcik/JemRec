package com.jemcik.jemrec.capture

import com.jemcik.jemrec.capture.CallRules.Contacts
import com.jemcik.jemrec.capture.FileNames.Format
import com.jemcik.jemrec.capture.FileNames.Party
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

/** The ways to name a recording, and every one read back. The number
 *  format's own tests are in NumberFileNamesTest. */
class FileNamesTest {

    private val at = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse("20261020_165705")!!.time
    private val priya = Party("+44 7700 900123", "Priya Shah")

    @Test fun theDefaultIsTheNameJemRecAlwaysUsed() {
        assertEquals("20261020_165705_out.ogg", FileNames.build(Format.DATE, at, false, priya))
        assertEquals("20261020_165705_in.ogg", FileNames.build(Format.DATE, at, true, null))
    }

    @Test fun withTheCallerItAddsTheirName() {
        assertEquals("20261020_165705_out_Priya Shah.ogg", FileNames.build(Format.DATE_AND_CALLER, at, false, priya))
        // No contact name: the number, as digits.
        assertEquals(
            "20261020_165705_in_+447700900123.ogg",
            FileNames.build(Format.DATE_AND_CALLER, at, true, Party("+44 7700 900123", null)),
        )
        // Nobody known at all: the plain name.
        assertEquals("20261020_165705_in.ogg", FileNames.build(Format.DATE_AND_CALLER, at, true, Party(null, null)))
        // Caller ID hidden for this call: the number after the code. Any
        // other code was never a number (NumberFileNamesTest has both too).
        assertEquals(
            "20261020_165705_out_0501234567.ogg",
            FileNames.build(Format.DATE_AND_CALLER, at, false, Party("#31#0501234567", null)),
        )
        assertEquals("20261020_165705_out.ogg", FileNames.build(Format.DATE_AND_CALLER, at, false, Party("*86", null)))
        // An extension after a pause is no part of the number: it was 0501234567123.
        assertEquals(
            "20261020_165705_out_0501234567.ogg",
            FileNames.build(Format.DATE_AND_CALLER, at, false, Party("0501234567,123", null)),
        )
    }

    @Test fun honorsNameIsWhatHonorsPhoneAppLooksFor() {
        assertEquals("Priya Shah@+447700900123_20261020165705.ogg", FileNames.build(Format.HONOR, at, true, priya))
        assertEquals("477@477_20261020165705.ogg", FileNames.build(Format.HONOR, at, false, Party("477", null)))
        // A hidden number gives the Phone app nothing to match: the plain name.
        assertEquals("20261020_165705_in.ogg", FileNames.build(Format.HONOR, at, true, Party(null, "Priya Shah")))
    }

    @Test fun everyFormatReadsBack() {
        Format.entries.forEach { format ->
            listOf(true, false).forEach { incoming ->
                val parsed = FileNames.parse(FileNames.build(format, at, incoming, priya))!!
                assertEquals("$format", at, parsed.startedAt)
                if (format == Format.HONOR) {
                    assertNull(parsed.incoming)
                    assertEquals("+447700900123", parsed.number)
                    assertEquals("Priya Shah", parsed.caller)
                } else {
                    assertEquals(incoming, parsed.incoming)
                }
            }
        }
        assertEquals("Priya Shah", FileNames.parse("20261020_165705_out_Priya Shah.ogg")?.caller)
    }

    @Test fun namesFromBeforeReadExactlyAsTheyDid() {
        val parsed = FileNames.parse("20261020_165705_in.ogg")!!
        assertEquals(at, parsed.startedAt)
        assertEquals(true, parsed.incoming)
        assertNull(parsed.caller)
        assertNull(FileNames.parse("not-one-of-ours.txt"))
        // A tag inside a word is not a direction.
        assertNull(FileNames.parse("20261020_165705_input.ogg")?.incoming)
    }

    @Test fun aMessengerCallCarriesItsApp() {
        assertEquals("20261020_165705_in_viber.ogg", FileNames.buildVoip(Format.DATE, at, true, "viber", "Priya Raman"))
        assertEquals("20261020_165705_viber.ogg", FileNames.buildVoip(Format.DATE, at, null, "viber", null))
        assertEquals(
            "20261020_165705_out_whatsapp_Priya Raman.ogg",
            FileNames.buildVoip(Format.DATE_AND_CALLER, at, false, "whatsapp", "Priya Raman"),
        )
        // Honor's format has nothing to match a messenger call to: the caller form.
        assertEquals(
            "20261020_165705_in_viber_Priya Raman.ogg",
            FileNames.buildVoip(Format.HONOR, at, true, "viber", "Priya Raman"),
        )
    }

    @Test fun aMessengerCallReadsBack() {
        val full = FileNames.parse("20261020_165705_out_whatsapp_Priya Raman.ogg")!!
        assertEquals(at, full.startedAt)
        assertEquals(false, full.incoming)
        assertEquals("whatsapp", full.app)
        assertEquals("Priya Raman", full.caller)
        val bare = FileNames.parse("20261020_165705_viber.ogg")!!
        assertNull(bare.incoming)
        assertEquals("viber", bare.app)
        assertNull(bare.caller)
        // A phone call with a caller is still a phone call.
        assertNull(FileNames.parse("20261020_165705_in_Priya Raman.ogg")?.app)
    }

    @Test fun nothingInANameCanBreakAFolderOrTheFormat() {
        assertEquals("a b c d", FileNames.clean("a/b:c*d"))
        // @ is what Honor's format splits on; % is what the trash escapes with.
        assertEquals("Bob Work 100", FileNames.clean("Bob@Work 100%"))
        assertEquals("hidden", FileNames.clean("..hidden"))
        // An underscore separates a name's parts: a caller cannot carry one.
        assertEquals("viber support", FileNames.clean("viber_support"))
        assertNull(FileNames.clean("  /  "))
        assertEquals(60, FileNames.clean("x".repeat(200))!!.length)
    }

    @Test fun shorteningNeverSplitsAnEmoji() {
        // A surrogate pair straddling the limit is dropped whole.
        val name = "x".repeat(59) + "\uD83D\uDE00" + "tail"
        val cleaned = FileNames.clean(name)!!
        assertEquals("x".repeat(59), cleaned)
        assertFalse(Character.isHighSurrogate(cleaned.last()))
    }

    @Test fun aNumberIsItsDigits() {
        assertEquals("+447700900123", FileNames.digits("+44 (7700) 900-123"))
        assertEquals("0501234567", FileNames.digits("050 123 45 67"))
        assertNull(FileNames.digits("Private"))
        assertNull(FileNames.digits(null))
    }
}

/** Whose calls the rules keep. */
class CallRulesTest {

    private val mum = 7L
    private val boss = 9L

    private fun keeps(contacts: Contacts, others: Boolean = true, id: Long?, chosen: Set<Long> = setOf(mum)) =
        CallRules.keeps(contacts, others, id, chosen)

    @Test fun everyoneByDefault() {
        assertTrue(keeps(Contacts.ALL, id = mum))
        assertTrue(keeps(Contacts.ALL, id = boss))
        assertTrue(keeps(Contacts.ALL, id = null))
    }

    @Test fun allButLeavesOutTheChosen() {
        assertFalse(keeps(Contacts.ALL_BUT, id = mum))
        assertTrue(keeps(Contacts.ALL_BUT, id = boss))
    }

    @Test fun onlyKeepsJustTheChosen() {
        assertTrue(keeps(Contacts.ONLY, id = mum))
        assertFalse(keeps(Contacts.ONLY, id = boss))
    }

    @Test fun noneKeepsNoContactsButStillOtherNumbers() {
        assertFalse(keeps(Contacts.NONE, id = mum))
        assertTrue(keeps(Contacts.NONE, id = null))
    }

    @Test fun otherNumbersAreTheirOwnQuestion() {
        assertFalse(keeps(Contacts.ALL, others = false, id = null))
        assertTrue(keeps(Contacts.ALL, others = false, id = boss))
        assertTrue(keeps(Contacts.ONLY, others = true, id = null))
    }
}
