package com.jemcik.jemrec.recordings

import com.jemcik.jemrec.recordings.DaemonFile.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recorder's names for its files, read whole: one row for each kind the
 * recorder writes (its Checks.recfileTag, messageRecfile and takenRecfile),
 * and a few it does not.
 */
class DaemonFileTest {

    private val at = 1_792_661_198_533L

    private fun file(
        start: Long? = at,
        incoming: Boolean = false,
        message: Message? = null,
        taken: Long? = null,
        messenger: String? = null,
        messengerIncoming: Boolean? = null,
        missed: Long? = null,
    ) = DaemonFile(start, incoming, message, taken, messenger, messengerIncoming, missed)

    /** The names the recorder makes for a call joined mid-call (ChecksTest
     *  makes them): joined, its guessed way read as written, an early end's
     *  tag beside it (review round 11: no app test read one). */
    @Test fun aCallJoinedMidCallsName() {
        val joined = DaemonFile.parse("jemrec_rec_${at}_joined_out.dat")
        assertTrue(joined.joined)
        assertFalse(joined.incoming)
        assertEquals(at, joined.start)
        val early = DaemonFile.parse("jemrec_rec_${at}_early4100_joined_out.dat")
        assertTrue(early.joined)
        assertEquals(4100L, early.missedMs)
        assertFalse(DaemonFile.parse("jemrec_rec_${at}_out.dat").joined)
    }

    @Test fun aPhoneCallsName() {
        assertEquals(file(incoming = true), DaemonFile.parse("jemrec_rec_${at}_in.dat"))
        assertEquals(file(incoming = false), DaemonFile.parse("jemrec_rec_${at}_out.dat"))
    }

    @Test fun aMessagesName() {
        assertEquals(file(incoming = true, message = Message.Starts(8120)), DaemonFile.parse("jemrec_rec_${at}_msg8120_in.dat"))
        assertEquals(file(incoming = true, message = Message.Starts(0)), DaemonFile.parse("jemrec_rec_${at}_msg0_in.dat"))
        assertEquals(file(incoming = true, message = Message.None), DaemonFile.parse("jemrec_rec_${at}_msgnone_in.dat"))
        assertEquals(file(incoming = true, message = Message.Failed), DaemonFile.parse("jemrec_rec_${at}_msgfail_in.dat"))
        // A start past what a Long holds is no start: not a message.
        assertEquals(file(incoming = true), DaemonFile.parse("jemrec_rec_${at}_msg99999999999999999999_in.dat"))
    }

    @Test fun aCallTakenOversName() {
        assertEquals(file(incoming = true, taken = 8120), DaemonFile.parse("jemrec_rec_${at}_from8120_in.dat"))
        assertEquals(file(taken = 0), DaemonFile.parse("jemrec_rec_${at}_from0_out.dat"))
    }

    @Test fun aMessengersName() {
        assertEquals(file(messenger = "com.whatsapp"), DaemonFile.parse("jemrec_rec_${at}_voip-com.whatsapp.dat"))
        assertEquals(
            file(messenger = "com.google.android.apps.tachyon", messengerIncoming = true),
            DaemonFile.parse("jemrec_rec_${at}_in_voip-com.google.android.apps.tachyon.dat"),
        )
        assertEquals(
            file(messenger = "com.google.android.apps.tachyon", messengerIncoming = false),
            DaemonFile.parse("jemrec_rec_${at}_out_voip-com.google.android.apps.tachyon.dat"),
        )
        assertEquals(file(), DaemonFile.parse("jemrec_rec_${at}_voip-.dat"))
    }

    /** Ended before its call (the recorder's Checks.earlyRecfile): just
     *  after the start, in either kind of name, with a message's tag after. */
    @Test fun aRecordingThatEndedEarly() {
        assertEquals(file(incoming = true, missed = 41_250), DaemonFile.parse("jemrec_rec_${at}_early41250_in.dat"))
        assertEquals(
            file(incoming = true, message = Message.Starts(7400), missed = 9000),
            DaemonFile.parse("jemrec_rec_${at}_early9000_msg7400_in.dat"),
        )
        assertEquals(
            file(messenger = "com.whatsapp", messengerIncoming = true, missed = 9000),
            DaemonFile.parse("jemrec_rec_${at}_early9000_in_voip-com.whatsapp.dat"),
        )
        // Anywhere but just after the start, it is not the recorder's.
        assertEquals(file(incoming = true, taken = 5), DaemonFile.parse("jemrec_rec_${at}_from5_early9000_in.dat"))
    }

    /** A package's own underscores are never read as the recorder's tags -
     *  a package may be named so - nor its "_in." as the way. */
    @Test fun aPackageIsNotReadForTags() {
        assertEquals(
            file(messenger = "org.x_msg1_early5_from2_in.chat"),
            DaemonFile.parse("jemrec_rec_${at}_voip-org.x_msg1_early5_from2_in.chat.dat"),
        )
    }

    @Test fun namesTheRecorderDoesNotWrite() {
        val nothing = file(start = null)
        listOf("", "recording.dat", "jemrec_rec_.dat", "jemrec_rec_abc_out.dat", "jemrec_rec_$at.dat").forEach {
            assertEquals(it, nothing, DaemonFile.parse(it))
        }
        assertEquals(nothing.copy(incoming = true), DaemonFile.parse("jemrec_rec_x_in.dat"))
        assertEquals(file(), DaemonFile.parse("jemrec_rec_${at}_input.dat"))
    }

    @Test fun eachPartIsReadOutOnItsOwn() {
        val message = DaemonFile.parse("jemrec_rec_${at}_msg8120_from300_in.dat")
        assertEquals(at, message.start)
        assertEquals(true, message.incoming)
        assertEquals(8120L, (message.message as Message.Starts).ms)
        assertEquals(300L, message.takenFromMs)
        val meet = DaemonFile.parse("jemrec_rec_${at}_out_voip-com.google.android.apps.tachyon.dat")
        assertEquals("com.google.android.apps.tachyon", meet.messenger)
        assertEquals(false, meet.messengerIncoming)
        assertEquals(false, meet.incoming)
        assertEquals(true, DaemonFile.parse("jemrec_rec_${at}_in_voip-com.whatsapp.dat").messengerIncoming)
    }

    @Test fun theDirectionOfASavedName() {
        assertEquals(true, DaemonFile.isIncoming("20261101_120000_in.ogg"))
        assertEquals(false, DaemonFile.isIncoming("20261101_120000_out.ogg"))
        assertEquals(false, DaemonFile.isIncoming("20261101_120000_input.ogg"))
    }
}
