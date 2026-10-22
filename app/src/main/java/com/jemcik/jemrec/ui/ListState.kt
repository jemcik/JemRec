package com.jemcik.jemrec.ui

import com.jemcik.jemrec.capture.RecordingFilter
import com.jemcik.jemrec.capture.filterRecordings

/*
 * Small decisions about the recordings list, kept out of the ViewModel so
 * they can be tested (ListStateTest). Each acted on the wrong rows once.
 */

/** How much of what is shown is selected - what select-all's box says, and
 *  what tapping it does. */
internal enum class Picked { NONE, SOME, ALL }

/** Rows, not counts: with a row saved while selecting, 20 picked of 21 shown
 *  read as all, and a tap took every pick away. */
internal fun picked(selected: Set<String>, shown: List<String>): Picked = when {
    selected.isEmpty() || shown.none { it in selected } -> Picked.NONE
    shown.all { it in selected } -> Picked.ALL
    else -> Picked.SOME
}

/** The selection, kept to the rows still shown: after a refresh, a row that
 *  left the visible list (past midnight under Today, unstarred under
 *  Favourites) must leave the selection too, or "Delete 3" takes rows the
 *  user cannot see. */
internal fun stillShown(state: UiState): Set<String> {
    if (state.selected.isEmpty()) return state.selected
    val shown = filterRecordings(state.recordings, state.query, state.filter, state.favoritesOnly, state.keptOnly, state.messagesOnly)
        .map { it.name }.toSet()
    return state.selected intersect shown
}

/** The list with nothing narrowing it, on the Recordings tab: a recording a
 *  notification's Play or Share asked for has to be on screen, for its
 *  player and its share sheet to be. */
internal fun UiState.revealed(): UiState = copy(
    screen = Screen.HOME, homeTab = HomeTab.RECORDINGS,
    query = "", searchOpen = false,
    filter = RecordingFilter.ALL, favoritesOnly = false, keptOnly = false, messagesOnly = false,
    selecting = false, selected = emptySet(),
)
