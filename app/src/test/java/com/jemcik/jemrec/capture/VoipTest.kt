package com.jemcik.jemrec.capture

import com.jemcik.jemrec.capture.VoipCallNotes.Note
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Messenger calls: which app a daemon file is, and who and which way from
 *  the app's own call notifications. */
class VoipTest {

    private val viber = "com.viber.voip"
    private val start = 1_795_000_000_000L
    private val second = 1_000L

    @Test fun aDaemonFileSaysWhichMessengerItIs() {
        assertEquals("Viber", VoipApps.forRecfile("jemrec_rec_1795000000000_voip-com.viber.voip.dat")?.label)
        assertEquals("viber", VoipApps.forRecfile("jemrec_rec_1_voip-com.viber.voip.dat")?.tag)
        assertNull(VoipApps.forRecfile("jemrec_rec_1795000000000_in.dat"))
        // Not on the list, and still saved as a messenger call.
        assertEquals("Messenger", VoipApps.forRecfile("jemrec_rec_1_voip-org.example.chat.dat")?.label)
    }

    @Test fun aCallThatRangFirstWasIncoming() {
        val notes = listOf(
            Note(viber, start - 12 * second, "Priya Raman", true),
            Note(viber, start + 3 * second, "Priya Raman", null),
        )
        val call = VoipCallNotes.pick(notes, viber, start)!!
        assertEquals(true, call.incoming)
        assertEquals("Priya Raman", call.name)
    }

    @Test fun anEarlierCallsRingDoesNotMakeThisOneIncoming() {
        // Answered a call two minutes ago, then placed this one.
        val notes = listOf(
            Note(viber, start - 120 * second, "Someone Else", true),
            Note(viber, start - 2 * second, "Priya Raman", null),
        )
        assertEquals(false, VoipCallNotes.pick(notes, viber, start)?.incoming)
    }

    @Test fun aCallThatNeverRangWasPlacedByTheUser() {
        val notes = listOf(Note(viber, start - 2 * second, "Priya Raman", null))
        assertEquals(false, VoipCallNotes.pick(notes, viber, start)?.incoming)
    }

    @Test fun anotherAppsOrAnOldCallsNotesDoNotCount() {
        val notes = listOf(
            Note("com.whatsapp", start, "Someone", true),
            Note(viber, start - 10 * 60 * second, "Earlier", true),
        )
        assertNull(VoipCallNotes.pick(notes, viber, start))
    }

    @Test fun theNameNearestTheCallWins() {
        val notes = listOf(
            Note(viber, start - 90 * second, "Old Name", null),
            Note(viber, start + 1 * second, "Priya Raman", null),
            Note(viber, start + 30 * second, null, null),
        )
        assertEquals("Priya Raman", VoipCallNotes.pick(notes, viber, start)?.name)
    }

    @Test fun anAppsOwnNameIsNoCaller() {
        assertNull(VoipCallNotes.clean("Viber", "Viber"))
        // Telegram's title during an outgoing call; the caller is in the text.
        assertNull(VoipCallNotes.clean("Ongoing Telegram call", "Telegram"))
        assertNull(VoipCallNotes.clean("  ", "Viber"))
        assertEquals("Priya Raman", VoipCallNotes.clean(" Priya Raman ", "Viber"))
    }

    @Test fun notesRoundTrip() {
        listOf(
            Note(viber, start, "Priya Raman", true),
            Note(viber, start, null, null),
            Note(viber, start, "a\u0001b", false),
        ).forEach { assertEquals(it, VoipCallNotes.decode(VoipCallNotes.encode(it))) }
        assertNull(VoipCallNotes.decode("rubbish"))
    }
}
