package com.jemcik.jemrec.recordings

import com.jemcik.jemrec.recordings.DaemonFile.Message
import org.junit.Assert.assertEquals
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
    ) = DaemonFile(start, incoming, message, taken, messenger, messengerIncoming)

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
