package com.jemcik.jemrec.recordings

import com.jemcik.jemrec.calls.CallRules
import com.jemcik.jemrec.calls.CallRules.Contacts
import com.jemcik.jemrec.recordings.FileNames.Format
import com.jemcik.jemrec.recordings.FileNames.Party
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
        // No name: the number and the time - not the number twice, "+44...@",
        // which the Phone app cannot read (FileNames.build).
        assertEquals("+447700900123_20261020165705.ogg", FileNames.build(Format.HONOR, at, false, Party("+44 7700 900123", null)))
        assertEquals("477_20261020165705.ogg", FileNames.build(Format.HONOR, at, false, Party("477", null)))
        // A hidden number gives the Phone app nothing to match: the plain name.
        assertEquals("20261020_165705_in.ogg", FileNames.build(Format.HONOR, at, true, Party(null, "Priya Shah")))
    }

    @Test fun honorsPhoneAppReadsTheCallsNumberAndTimeInEveryNameItIsGiven() {
        // Names a regular expression would choke on, or make something of,
        // as the Phone app reads them (HonorPhoneApp).
        val names = listOf(
            null, "Priya Shah", "+1 Bob", "Mom (work)", "Ann [x]", "Bob {1}", "\$ign", "Dr. Who", "^up",
            "Ivan+", "a*b?c", "(", "[", "{", "+", "()", "О'Коннор", "李雷", "🙂 Smile", "a\\b", "Bob|Ann",
        )
        names.forEach { name ->
            val built = FileNames.build(Format.HONOR, at, true, Party("+44 7700 900123", name))
            assertEquals("$name -> $built", "+447700900123", HonorPhoneApp.number(built))
            assertEquals("$name -> $built", at, HonorPhoneApp.time(built))
            // And JemRec reads it back the same.
            assertEquals("$name -> $built", "+447700900123", FileNames.parse(built)?.number)
            assertEquals("$name -> $built", at, FileNames.parse(built)?.startedAt)
        }
    }

    @Test fun theNameJemRecGaveACallerWithNoNameWasOneThePhoneAppCouldNotRead() {
        // Up to 1.1: the number before the @, a dangling + to a pattern.
        assertEquals(HonorPhoneApp.NO_NUMBER, HonorPhoneApp.number("+380954388250@+380954388250_20261117155459.ogg"))
        // What the Honor played that day.
        assertEquals("+380954388250", HonorPhoneApp.number("+380954388250_20261117155459.ogg"))
    }

    @Test fun aNameSavedBefore12ThePhoneAppCannotReadIsGivenTheNameItCan() {
        val renames = mapOf(
            "+380954388250@+380954388250_20261117155459.ogg" to "+380954388250_20261117155459.ogg",
            "+380954388250@+380954388250_20261117155459 (1).ogg" to "+380954388250_20261117155459 (1).ogg",
            "+1 Bob@+15550100_20261020165705.ogg" to "1 Bob@+15550100_20261020165705.ogg",
            "Ann [x@+447700900123_20261020165705.ogg" to "Ann x@+447700900123_20261020165705.ogg",
        )
        renames.forEach { (old, new) ->
            // The old name one the app reads no number in: why it goes.
            assertEquals(old, HonorPhoneApp.NO_NUMBER, HonorPhoneApp.number(old))
            assertEquals(old, new, FileNames.honorRenamed(old))
            // The Phone app reads the call's number in the new name, and its time.
            assertEquals(new, FileNames.parse(old)?.number, HonorPhoneApp.number(new))
            assertEquals(new, FileNames.parse(old)?.startedAt, HonorPhoneApp.time(new))
            assertEquals(new, FileNames.parse(old)?.startedAt, FileNames.parse(new)?.startedAt)
            // Done once: the new name is right.
            assertNull(new, FileNames.honorRenamed(new))
        }
    }

    /** A contact saved by its digits, or a call log that names the caller
     *  by the number: written "<number>@<number>" in 1.2's first build, the
     *  name was one the next reading renamed, under its own notification -
     *  Play and Share said it was gone (review, 2026-11-18). */
    @Test fun aCallerNamedByTheNumberIsACallerWithNoName() {
        listOf("0501234567" to "0501234567", "+38 050 123 45 67" to "+380501234567", "380501234567" to "+380501234567").forEach { (name, number) ->
            val built = FileNames.build(Format.HONOR, at, true, Party(number, name))
            assertEquals(name, "${FileNames.digits(number)}_20261020165705.ogg", built)
            assertNull(built, FileNames.honorRenamed(built))
        }
    }

    /** Every name 1.2 writes in Honor's format is one it leaves alone. */
    @Test fun aNameThisVersionWritesIsNeverOneItRenames() {
        val names = listOf(null, "Priya Shah", "+1 Bob", "Mom (work)", "()", "477", "+447700900123", "О'Коннор", "Bob|Ann")
        val numbers = listOf("+44 7700 900123", "477", "0501234567")
        for (name in names) for (number in numbers) {
            val built = FileNames.build(Format.HONOR, at, true, Party(number, name))
            assertNull("$name, $number -> $built", FileNames.honorRenamed(built))
        }
    }

    /** A name saved before 1.2 that the Phone app reads is left as it is,
     *  odd characters and all: renamed, the user's files changed names for
     *  nothing (review, 2026-11-18). */
    @Test fun aNameSavedBefore12ThePhoneAppReadsIsLeftAlone() {
        listOf(
            "477@477_20261020165705.ogg",
            "Mom (work)@+447700900123_20261020165705.ogg",
            "()@+447700900123_20261020165705.ogg",
            "Dr. Who@+447700900123_20261020165705.ogg",
            "0501234567@0501234567_20261020165705.ogg",
        ).forEach { old ->
            assertEquals(old, FileNames.parse(old)?.number, HonorPhoneApp.number(old))
            assertNull(old, FileNames.honorRenamed(old))
        }
    }

    @Test fun aNameThatIsRightOrNotHonorsIsLeftAlone() {
        listOf(
            "Priya Shah@+447700900123_20261020165705.ogg",
            "+447700900123_20261020165705.ogg",
            "20261020_165705_in.ogg",
            "20261020_165705_out_+447700900123.ogg",
            "20261020_203501_in_viber_Priya Raman.ogg",
            "notes.ogg",
        ).forEach { assertNull(it, FileNames.honorRenamed(it)) }
    }

    @Test fun aDeletedRecordingIsOneThePhoneAppCannotMatch() {
        listOf(priya, Party("+44 7700 900123", null)).forEach { party ->
            val trashed = TrashNames.nameFor(FileNames.build(Format.HONOR, at, true, party), 1_795_000_000_000)
            assertEquals(trashed, HonorPhoneApp.NO_NUMBER, HonorPhoneApp.number(trashed))
        }
    }

    @Test fun aCallerWithNoNameReadsBackInHonorsFormat() {
        val parsed = FileNames.parse("+447700900123_20261020165705.ogg")!!
        assertEquals(at, parsed.startedAt)
        assertEquals("+447700900123", parsed.number)
        assertNull(parsed.caller)
        assertNull(parsed.incoming)
        // A name a provider made when the one asked was taken.
        assertEquals(parsed, FileNames.parse("+447700900123_20261020165705 (1).ogg"))
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

    @Test fun aNameAProviderMadeWhenTheOneAskedWasTakenReadsAsTheOneAsked() {
        // " (1)" before the extension: what Android's file providers add.
        assertEquals(FileNames.parse("20261020_165705_in.ogg"), FileNames.parse("20261020_165705_in (1).ogg"))
        assertEquals(FileNames.parse("20261020_203501_in_viber.ogg"), FileNames.parse("20261020_203501_in_viber (1).ogg"))
        assertEquals(FileNames.parse("20261020_203501_viber.ogg"), FileNames.parse("20261020_203501_viber (2).ogg"))
        assertEquals(
            FileNames.parse("Priya Shah@+447700900123_20261020165705.ogg"),
            FileNames.parse("Priya Shah@+447700900123_20261020165705 (1).ogg"),
        )
        // After a caller it may be the caller's own: "Team (2)" stays.
        assertEquals("Team (2)", FileNames.parse("20261020_165705_out_Team (2).ogg")?.caller)
        assertEquals("Team (2)", FileNames.parse("20261020_203501_in_viber_Team (2).ogg")?.caller)
        assertNull(FileNames.parse("voice note (1).ogg"))
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
