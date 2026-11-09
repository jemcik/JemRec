package com.jemcik.jemrec.ui

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
 *  left the visible list (unstarred under Favourites, kept no longer under
 *  Kept) must leave the selection too, or "Delete 3" takes rows the
 *  user cannot see. */
internal fun stillShown(state: UiState): Set<String> {
    if (state.selected.isEmpty()) return state.selected
    val shown = filterRecordings(state.recordings, state.recordingsNarrowing).map { it.name }.toSet()
    return state.selected intersect shown
}

/** The list with nothing narrowing it, on the Recordings tab: a recording a
 *  notification's Play or Share asked for has to be on screen, for its
 *  player and its share sheet to be. */
internal fun UiState.revealed(): UiState = copy(
    screen = Screen.HOME, homeTab = HomeTab.RECORDINGS,
    recordingsNarrowing = RecordingsNarrowing(),
    selecting = false, selected = emptySet(),
)

/** [revealed], and [name]'s row in the list's window and asked into view
 *  (UiState.reveal): from a list left scrolled down, a notification's Play
 *  played a recording whose player was off the screen, and one past the
 *  window had no row drawn at all. */
internal fun UiState.revealing(name: String): UiState {
    val all = revealed()
    val at = filterRecordings(all.recordings, all.recordingsNarrowing).indexOfFirst { it.name == name }
    if (at < 0) return all
    return all.copy(visibleCount = maxOf(all.visibleCount, (at / PAGE + 1) * PAGE), reveal = name)
}
