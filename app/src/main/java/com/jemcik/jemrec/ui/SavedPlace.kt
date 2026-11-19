package com.jemcik.jemrec.ui

import android.os.Bundle

/**
 * Where the user was - the screen, the home tab, each list's narrowing, the
 * recordings picked - kept through the end of JemRec's process in the
 * background. A ViewModel does not outlive its process, and Android ends one
 * left in the background whenever it needs the memory; the screen comes back
 * as it was "Save UI states" asks, from the window's saved state
 * (developer.android.com, Save UI states). Until 1.2 it came back on home,
 * the search and the selection gone. The view model keeps it in its
 * SavedStateHandle, the guide's place for state a view model holds, and one
 * made new takes it back from there (MainViewModel); one that lived through -
 * the phone turned - has it already.
 */
internal object SavedPlace {

    /** Its name in the view model's saved state (MainViewModel): not a
     *  preference, so not a KEY (PreferencesInventoryTest). */
    const val NAME = "jemrec.place"

    private const val SCREEN = "place.screen"
    private const val TAB = "place.tab"
    private const val REC_FAVORITES = "place.rec.favorites"
    private const val REC_SOURCES = "place.rec.sources"
    private const val REC_KEPT = "place.rec.kept"
    private const val REC_MESSAGES = "place.rec.messages"
    private const val REC_QUERY = "place.rec.query"
    private const val REC_SEARCH = "place.rec.search"
    private const val CALLS_FAVORITES = "place.calls.favorites"
    private const val CALLS_KEPT = "place.calls.kept"
    private const val CALLS_MESSAGES = "place.calls.messages"
    private const val CALLS_NOT_RECORDED = "place.calls.notRecorded"
    private const val CALLS_QUERY = "place.calls.query"
    private const val CALLS_SEARCH = "place.calls.search"
    private const val SELECTING = "place.selecting"
    private const val SELECTED = "place.selected"
    private const val SHOWN = "place.shown"

    /** The most the picked names may take in the saved state, as a Parcel
     *  writes them (two bytes a character, and a length), of Android's 50 KB. */
    internal const val SELECTION_BYTES = 16 * 1024L

    fun save(state: UiState, out: Bundle) {
        out.putString(SCREEN, state.screen.name)
        out.putString(TAB, state.homeTab.name)
        state.recordingsNarrowing.let {
            out.putBoolean(REC_FAVORITES, it.favorites)
            out.putStringArray(REC_SOURCES, it.sources.toTypedArray())
            out.putBoolean(REC_KEPT, it.kept)
            out.putBoolean(REC_MESSAGES, it.messages)
            out.putString(REC_QUERY, it.query)
            out.putBoolean(REC_SEARCH, it.searchOpen)
        }
        state.callsNarrowing.let {
            out.putBoolean(CALLS_FAVORITES, it.favorites)
            out.putBoolean(CALLS_KEPT, it.kept)
            out.putBoolean(CALLS_MESSAGES, it.messages)
            out.putBoolean(CALLS_NOT_RECORDED, it.notRecorded)
            out.putString(CALLS_QUERY, it.query)
            out.putBoolean(CALLS_SEARCH, it.searchOpen)
        }
        // The recordings picked, while they are few enough: every name goes
        // into the window's saved state, which Android asks to keep under
        // 50 KB ("Parcelables and bundles") and fails past its transaction
        // limit - a library picked whole would. More than that, the
        // selection is let go (review, 2026-11-18).
        val fits = state.selected.sumOf { 2L * it.length + 8 } <= SELECTION_BYTES
        out.putBoolean(SELECTING, state.selecting && fits)
        out.putStringArray(SELECTED, if (fits) state.selected.toTypedArray() else emptyArray())
        // How far the list had grown: its place, kept by the list itself,
        // may be past the first page.
        out.putInt(SHOWN, state.visibleCount)
    }

    /** [state] with the place in [saved] put back; anything not there, or
     *  not known to this version (a screen renamed), left as it is. */
    fun restore(state: UiState, saved: Bundle): UiState {
        if (!saved.containsKey(SCREEN)) return state
        val screen = Screen.entries.firstOrNull { it.name == saved.getString(SCREEN) } ?: state.screen
        val tab = HomeTab.entries.firstOrNull { it.name == saved.getString(TAB) } ?: state.homeTab
        return state.copy(
            screen = screen,
            homeTab = tab,
            recordingsNarrowing = state.recordingsNarrowing.copy(
                favorites = saved.getBoolean(REC_FAVORITES),
                sources = saved.getStringArray(REC_SOURCES)?.toSet().orEmpty(),
                kept = saved.getBoolean(REC_KEPT),
                messages = saved.getBoolean(REC_MESSAGES),
                query = saved.getString(REC_QUERY).orEmpty(),
                searchOpen = saved.getBoolean(REC_SEARCH),
            ),
            callsNarrowing = state.callsNarrowing.copy(
                favorites = saved.getBoolean(CALLS_FAVORITES),
                kept = saved.getBoolean(CALLS_KEPT),
                messages = saved.getBoolean(CALLS_MESSAGES),
                notRecorded = saved.getBoolean(CALLS_NOT_RECORDED),
                query = saved.getString(CALLS_QUERY).orEmpty(),
                searchOpen = saved.getBoolean(CALLS_SEARCH),
            ),
            selecting = saved.getBoolean(SELECTING),
            selected = saved.getStringArray(SELECTED)?.toSet().orEmpty(),
            visibleCount = maxOf(state.visibleCount, saved.getInt(SHOWN, PAGE)),
        )
    }
}
