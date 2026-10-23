package com.jemcik.jemrec.ui

import android.net.Uri
import com.jemcik.jemrec.capture.CallHistory
import com.jemcik.jemrec.capture.CallHistory.Kind
import com.jemcik.jemrec.capture.CallHistory.Status
import com.jemcik.jemrec.capture.Recording
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

/** The lists' chips, by one rule: a chip only where it would narrow its list
 *  to some of it - and the Calls tab's filters. */
class ListChipsTest {

    private val now = System.currentTimeMillis()
    private val day = 24L * 60 * 60 * 1000

    private fun facts(
        favorite: Boolean = false,
        kept: Boolean = false,
        message: Boolean = false,
        source: String? = null,
        notRecorded: Boolean = false,
    ) = ChipFacts(favorite, kept, message, source, notRecorded)

    private fun chips(vararg items: ChipFacts, on: Set<ListChip> = emptySet()) = chipsFor(items.toList(), on)

    private fun kinds(chips: List<ListChip>) = chips.map { it.kind }

    @Test fun aChipIsDrawnOnlyWhereItLeavesSomeButNotAll() {
        assertTrue(ChipKind.FAVORITES in kinds(chips(facts(favorite = true), facts())))
        // Every one a favourite: the chip would show them all.
        assertFalse(ChipKind.FAVORITES in kinds(chips(facts(favorite = true), facts(favorite = true))))
        // None: it would show nothing.
        assertFalse(ChipKind.FAVORITES in kinds(chips(facts(), facts())))
        // Kept and Messages the same way, as they were before.
        assertTrue(ChipKind.KEPT in kinds(chips(facts(kept = true), facts())))
        assertFalse(ChipKind.MESSAGES in kinds(chips(facts(), facts())))
    }

    @Test fun aChipThatIsOnIsDrawnSoItCanBeTurnedOff() {
        val on = setOf(ListChip(ChipKind.FAVORITES))
        assertTrue(ChipKind.FAVORITES in kinds(chips(facts(favorite = true), facts(favorite = true), on = on)))
    }

    @Test fun theSourcesPhoneFirstThenTheMessengersInSettingsOrder() {
        val drawn = chips(facts(source = "viber"), facts(source = PHONE_SOURCE), facts(source = "whatsapp"))
            .filter { it.kind == ChipKind.SOURCE }.map { it.source }
        assertEquals(listOf(PHONE_SOURCE, "whatsapp", "viber"), drawn)
        // All phone calls: nothing to tell apart.
        assertTrue(chips(facts(source = PHONE_SOURCE), facts(source = PHONE_SOURCE)).none { it.kind == ChipKind.SOURCE })
        // The Calls tab's facts have no source at all.
        assertTrue(chips(facts(), facts(notRecorded = true)).none { it.kind == ChipKind.SOURCE })
    }

    @Test fun aSourcePickedWhoseCallsHaveGoneIsStillDrawn() {
        val on = setOf(ListChip(ChipKind.SOURCE, "telegram"))
        assertTrue(ListChip(ChipKind.SOURCE, "telegram") in chips(facts(source = PHONE_SOURCE), facts(source = PHONE_SOURCE), on = on))
    }

    @Test fun notRecordedWhereSomeCallsWereNot() {
        assertTrue(ChipKind.NOT_RECORDED in kinds(chips(facts(notRecorded = true), facts())))
        assertFalse(ChipKind.NOT_RECORDED in kinds(chips(facts(), facts())))
    }

    @Test fun searchOverAnyListAndNothingOverAnEmptyOne() {
        assertEquals(listOf(ChipKind.SEARCH), kinds(chips(facts())))
        assertTrue(chipsFor(emptyList(), emptySet()).isEmpty())
    }

    @Test fun inOrderSearchThenWhoHowKeptMessagesWhatBecame() {
        val all = kinds(
            chips(
                facts(favorite = true, kept = true, message = true, source = PHONE_SOURCE, notRecorded = true),
                facts(source = "whatsapp"),
                facts(source = PHONE_SOURCE),
            ),
        )
        assertEquals(
            listOf(
                ChipKind.SEARCH, ChipKind.FAVORITES, ChipKind.SOURCE, ChipKind.SOURCE,
                ChipKind.KEPT, ChipKind.MESSAGES, ChipKind.NOT_RECORDED,
            ),
            all,
        )
    }

    // The Calls tab's filters.

    private val uri: Uri = Mockito.mock(Uri::class.java)

    private fun call(
        label: String,
        status: Status = Status.RECORDED,
        at: Long = now,
        kind: Kind = Kind.INCOMING,
        favorite: Boolean = false,
        kept: Boolean = false,
        message: Boolean = false,
    ) = CallHistory.Call(
        at = at, seconds = 60, kind = kind, label = label, number = "+44 20 7946 0000", favorite = favorite,
        recording = if (status == Status.RECORDED) {
            Recording(
                name = "$label.ogg", uri = uri, whenLabel = "", incoming = true, durationLabel = "1:00",
                sizeLabel = "1 MB", kept = kept, message = message,
            )
        } else {
            null
        },
        status = status,
    )

    private val calls = listOf(
        call("Kept", kept = true),
        call("Message", message = true, at = now - 2 * day),
        call("Favourite", favorite = true, at = now - 10 * day),
        call("Off", Status.RECORDING_OFF),
        call("Missed", Status.NOT_CONNECTED, kind = Kind.MISSED),
        call("Should have", Status.NOT_RECORDED),
    )

    private fun labels(narrowing: CallsNarrowing) = filterCalls(calls, narrowing).map { it.label }

    @Test fun theCallsFiltersEachNarrow() {
        assertEquals(calls.size, labels(CallsNarrowing()).size)
        assertEquals(listOf("Kept"), labels(CallsNarrowing(kept = true)))
        assertEquals(listOf("Message"), labels(CallsNarrowing(messages = true)))
        assertEquals(listOf("Favourite"), labels(CallsNarrowing(favorites = true)))
        // Not recorded: connected and not recorded - not the missed call,
        // which had nothing to record.
        assertEquals(listOf("Off", "Should have"), labels(CallsNarrowing(notRecorded = true)))
        assertEquals(listOf("Favourite"), labels(CallsNarrowing(query = "favou")))
        assertEquals(calls.size, labels(CallsNarrowing(query = "7946")).size)
    }

    @Test fun aCallTheLogRepeatsExactlyGetsAKeyOfItsOwn() {
        val missed = call("Missed", Status.NOT_CONNECTED, kind = Kind.MISSED)
        val keys = keyedCalls(listOf(missed, missed, calls[0])).map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        // The first of a repeat keeps the key it has alone, and so does every
        // other call: one coming in at the top moves no key.
        assertEquals(keyedCalls(listOf(missed)).single().key, keys[0])
        assertEquals(keyedCalls(listOf(calls[0])).single().key, keys[2])
    }

    private fun recording(name: String, favorite: Boolean = false, kept: Boolean = false, message: Boolean = false) = Recording(
        name = name, uri = uri, whenLabel = "", incoming = true, durationLabel = "1:00", sizeLabel = "1 MB",
        callerFavorite = favorite, kept = kept, message = message,
    )

    @Test fun eachChipDrawnLeavesSomeOfItsList() {
        // Whether a chip is drawn and what it leaves when on are one answer
        // (leftBy): each list's filter asked its own fields, and could have
        // drawn a chip that then left nothing, or everything.
        val recordings = listOf(
            recording("20261021_101100_in.ogg", favorite = true),
            recording("20261021_141000_in_telegram.ogg", kept = true),
            recording("20261021_152400_out_whatsapp.ogg", message = true),
        )
        val overRecordings = chipsFor(recordings.map { it.chipFacts() }, emptySet()) - ListChip(ChipKind.SEARCH)
        assertEquals(6, overRecordings.size)
        for (chip in overRecordings) {
            val left = filterRecordings(recordings, RecordingsNarrowing().tapped(chip))
            assertTrue("$chip left ${left.size}", left.size in 1 until recordings.size)
        }
        val overCalls = chipsFor(calls.map { it.chipFacts() }, emptySet()) - ListChip(ChipKind.SEARCH)
        assertEquals(4, overCalls.size)
        for (chip in overCalls) {
            val left = labels(CallsNarrowing().tapped(chip))
            assertTrue("$chip left ${left.size}", left.size in 1 until calls.size)
        }
    }

    @Test fun aChipTappedTwiceIsOffAndSearchFoldingAwayDropsItsQuery() {
        val notRecorded = ListChip(ChipKind.NOT_RECORDED)
        assertTrue(CallsNarrowing().tapped(notRecorded).notRecorded)
        assertFalse(CallsNarrowing().tapped(notRecorded).tapped(notRecorded).notRecorded)
        val searching = CallsNarrowing(searchOpen = true, query = "olivia")
        assertEquals(CallsNarrowing(), searching.tapped(ListChip(ChipKind.SEARCH)))
    }
}
