package com.jemcik.jemrec.ui

import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** The place the user was, through the end of the process in the
 *  background: the screen, the tab, the narrowing, the selection. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class SavedPlaceTest {

    private val there = UiState(
        screen = Screen.WHOSE_CALLS,
        homeTab = HomeTab.CALLS,
        recordingsNarrowing = RecordingsNarrowing(favorites = true, sources = setOf("phone", "org.telegram.messenger"), kept = true, query = "Olivia", searchOpen = true),
        callsNarrowing = CallsNarrowing(notRecorded = true, query = "+44", searchOpen = true),
        selecting = true,
        selected = setOf("a.ogg", "b.ogg"),
        visibleCount = 60,
    )

    private fun roundTrip(state: UiState): UiState {
        val bundle = Bundle()
        SavedPlace.save(state, bundle)
        // As Android keeps it: written out and read back.
        val parcel = android.os.Parcel.obtain()
        bundle.writeToParcel(parcel, 0)
        parcel.setDataPosition(0)
        val back = Bundle.CREATOR.createFromParcel(parcel)
        parcel.recycle()
        return SavedPlace.restore(UiState(), back)
    }

    @Test fun thePlaceComesBackAsItWas() {
        val back = roundTrip(there)
        assertEquals(there.screen, back.screen)
        assertEquals(there.homeTab, back.homeTab)
        assertEquals(there.recordingsNarrowing, back.recordingsNarrowing)
        assertEquals(there.callsNarrowing, back.callsNarrowing)
        assertEquals(there.selecting, back.selecting)
        assertEquals(there.selected, back.selected)
        // How far the list had grown, so its place past the first page is
        // there to come back to.
        assertEquals(there.visibleCount, back.visibleCount)
    }

    /** Each switch on its own, on and off, so a key left out of the save, or
     *  two swapped, cannot pass for the round trip (review, 2026-11-18). */
    @Test fun eachSwitchComesBackOnItsOwn() {
        val recordings = listOf<(RecordingsNarrowing) -> RecordingsNarrowing>(
            { it.copy(favorites = true) }, { it.copy(kept = true) }, { it.copy(messages = true) }, { it.copy(searchOpen = true) },
        )
        recordings.forEach { turn ->
            val state = UiState(recordingsNarrowing = turn(RecordingsNarrowing()))
            assertEquals(state.recordingsNarrowing, roundTrip(state).recordingsNarrowing)
        }
        val calls = listOf<(CallsNarrowing) -> CallsNarrowing>(
            { it.copy(favorites = true) }, { it.copy(kept = true) }, { it.copy(messages = true) },
            { it.copy(notRecorded = true) }, { it.copy(searchOpen = true) },
        )
        calls.forEach { turn ->
            val state = UiState(callsNarrowing = turn(CallsNarrowing()))
            assertEquals(state.callsNarrowing, roundTrip(state).callsNarrowing)
        }
        // And every one on at once, against a state that has them all off.
        val all = UiState(
            recordingsNarrowing = RecordingsNarrowing(favorites = true, kept = true, messages = true, searchOpen = true, query = "a"),
            callsNarrowing = CallsNarrowing(favorites = true, kept = true, messages = true, notRecorded = true, searchOpen = true, query = "b"),
        )
        val back = roundTrip(all)
        assertEquals(all.recordingsNarrowing, back.recordingsNarrowing)
        assertEquals(all.callsNarrowing, back.callsNarrowing)
    }

    /** A library picked whole would be past Android's limit for a window's
     *  saved state: so many picked are let go, the rest of the place kept. */
    @Test fun aSelectionTooBigToSaveIsLetGo() {
        val many = (0 until 2_000).map { "Someone With A Long Name@+447700900123_20261020${"%06d".format(it)}.ogg" }.toSet()
        val bundle = Bundle()
        SavedPlace.save(there.copy(selected = many), bundle)
        assertTrue(bundle.getStringArray("place.selected").isNullOrEmpty())
        val back = SavedPlace.restore(UiState(), bundle)
        assertEquals(false, back.selecting)
        assertEquals(emptySet<String>(), back.selected)
        assertEquals(there.screen, back.screen)
    }

    @Test fun nothingSavedLeavesTheStateAsItIs() {
        val state = UiState(screen = Screen.SETTINGS)
        assertEquals(state, SavedPlace.restore(state, Bundle()))
    }

    @Test fun aScreenThisVersionDoesNotKnowIsLeftAlone() {
        val bundle = Bundle()
        SavedPlace.save(there, bundle)
        bundle.putString("place.screen", "GONE_SINCE")
        assertEquals(Screen.HOME, SavedPlace.restore(UiState(), bundle).screen)
    }
}
