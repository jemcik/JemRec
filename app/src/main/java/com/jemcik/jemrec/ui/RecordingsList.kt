package com.jemcik.jemrec.ui

import android.app.Application
import com.jemcik.jemrec.recordings.Recording
import com.jemcik.jemrec.recordings.RecordingMarks
import com.jemcik.jemrec.recordings.RecordingTags
import com.jemcik.jemrec.recordings.RecordingLibrary
import com.jemcik.jemrec.recordings.Trash
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The home screen's lists: the chips and search of both tabs, the
 * Recordings tab's paging, keep marks, and selection with its keep and
 * delete.
 */
internal class RecordingsList(
    private val app: Application,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<UiState>,
    private val log: ActionLog,
    private val actions: Actions,
    private val reading: Reading,
    private val playback: Playback,
    private val trash: TrashActions,
) {
    fun setQuery(query: String) = state.update { current ->
        // Searching stays live during selection now, so a query can narrow the
        // list out from under picked rows. Any selection that no longer matches
        // has left the visible list, and must leave the selection with it - a
        // later delete acts on names, and would otherwise take a row the user
        // can no longer see. Widening the query keeps everything, as it should.
        val next = current.copy(recordingsNarrowing = current.recordingsNarrowing.copy(query = query))
        next.copy(selected = stillShown(next))
    }

    /** A chip of the Recordings tab tapped. Leaves a selection to the rows
     *  still shown, as a search does: the chips are hidden while selecting,
     *  but whichever way the list narrows, a delete must not take a row it
     *  has stopped showing - only the sources' chip made sure of that. */
    fun tapRecordingsChip(chip: ListChip) = state.update { current ->
        val next = current.copy(recordingsNarrowing = current.recordingsNarrowing.tapped(chip))
        next.copy(selected = stillShown(next))
    }

    /** The narrowing line's Show all: every chip off, the search folded away
     *  with what was typed. Widening, so the selection stays as it is. */
    fun showAllRecordings() = state.update { it.copy(recordingsNarrowing = RecordingsNarrowing()) }

    fun showAllCalls() = state.update { it.copy(callsNarrowing = CallsNarrowing()) }

    /** A chip of the Calls tab tapped. */
    fun tapCallsChip(chip: ListChip) = state.update { it.copy(callsNarrowing = it.callsNarrowing.tapped(chip)) }

    fun setCallsQuery(query: String) = state.update { it.copy(callsNarrowing = it.callsNarrowing.copy(query = query)) }

    /** Mark one recording to keep, or take the mark off. Kept recordings are
     *  never auto-deleted. */
    fun toggleKept(recording: Recording) {
        RecordingMarks.Keep.set(app, listOf(recording.name), !recording.kept)
        reading.refresh()
        keepInFiles(listOf(recording))
    }

    /** The mark into the files too, so a reinstall finds it (RecordingTags):
     *  as the preferences have it when the write runs, which is after the
     *  list has it - the preferences say it at once, the files take a write
     *  each. */
    private fun keepInFiles(recordings: List<Recording>) {
        scope.launch(Dispatchers.IO) { RecordingTags.writeKept(app, recordings) }
    }

    /** The selection toolbar's keep: marks every picked recording, or - when
     *  all of them are kept already - takes the mark off them all. */
    fun keepSelected() {
        val current = state.value
        val picked = current.recordings.filter { it.name in current.selected }
        if (picked.isEmpty()) return
        val kept = !picked.all { it.kept }
        RecordingMarks.Keep.set(app, picked.map { it.name }, kept = kept)
        state.update { it.copy(selecting = false, selected = emptySet()) }
        reading.refresh()
        keepInFiles(picked.filter { it.kept != kept })
    }

    /**
     * Show the next batch of older recordings.
     */
    fun showMore() = state.update { it.copy(visibleCount = it.visibleCount + PAGE) }

    /** [name]'s row brought into view (UiState.reveal). */
    fun onRevealed(name: String) = state.update { if (it.reveal == name) it.copy(reveal = null) else it }

    /**
     * Enter selection mode from the header, with nothing chosen yet.
     *
     * Long-press is the documented way into selection on Android and it is
     * supported below, but it is an invisible affordance: nothing on screen
     * says it is there. This is the visible way in, for the majority who will
     * never think to try holding a row down.
     */
    fun startSelecting() {
        // Selecting stops playback. The scrubber is hidden while selecting,
        // so a recording left playing under it was playing invisibly, with no
        // control on screen that could stop it.
        playback.stopPlayback()
        rememberSelectHintSeen()
        state.update { it.copy(selecting = true, selected = emptySet(), selectHintSeen = true) }
    }

    /** Long-press, or a tap on a row's photo: enter selection with this one chosen. */
    fun startSelecting(recording: Recording) {
        playback.stopPlayback()
        rememberSelectHintSeen()
        state.update { it.copy(selecting = true, selected = setOf(recording.name), selectHintSeen = true) }
    }

    /** The tip's x: it is not shown again. */
    fun dismissSelectHint() {
        rememberSelectHintSeen()
        state.update { it.copy(selectHintSeen = true) }
    }

    /** The tap-a-photo hint has done its job once selection has been used. */
    private fun rememberSelectHintSeen() {
        SelectHint.markSeen(app)
    }

    fun toggleSelected(recording: Recording) = state.update { current ->
        val next = if (recording.name in current.selected) {
            current.selected - recording.name
        } else {
            current.selected + recording.name
        }
        // Unpicking the last one leaves selection mode, as Gmail does: an empty
        // selection toolbar is a mode hanging around with nothing to act on, and
        // the photo that dropped you in is the natural way back out.
        if (next.isEmpty()) current.copy(selecting = false, selected = emptySet())
        else current.copy(selected = next)
    }

    /** Everything currently listed, which is not necessarily everything there
     *  is - the list pages, and selecting rows the user cannot see would be a
     *  quiet way to delete more than they meant to. */
    fun selectAllVisible() = state.update { current ->
        // "All visible" means all that pass the search and preset - the same
        // rows the list is showing - not every recording on disk.
        val visible = filterRecordings(current.recordings, current.recordingsNarrowing).take(current.visibleCount)
        current.copy(selected = visible.map { it.name }.toSet())
    }

    fun clearSelection() = state.update { it.copy(selecting = false, selected = emptySet()) }

    /** Unpick everything but stay in selection mode: the user is still
     *  choosing, they have just changed their mind about all of it. */
    fun deselectAll() = state.update { it.copy(selected = emptySet()) }

    fun askDeleteSelected() = state.update { it.copy(confirmingDeleteSelected = true) }

    fun cancelDeleteSelected() = state.update { it.copy(confirmingDeleteSelected = false) }

    /**
     * Delete what is selected - to Recently deleted, where it can be restored
     * for 30 days (see Trash).
     *
     * Selection mode ends whether or not every deletion succeeded. Leaving the
     * user in a toolbar showing a count that no longer means anything is worse
     * than making them tap Select again.
     */
    fun confirmDeleteSelected() {
        state.update { it.copy(confirmingDeleteSelected = false) }
        // What the question counted, read as it is answered, as Delete all
        // for good does: read after the wait for busy, a row picked meanwhile
        // went to Recently deleted unasked.
        val current = state.value
        // Less what a yes still waiting its turn has: said yes to again
        // while busy, the rows were moved twice (Claims).
        val doomed = claims.take(current.recordings.filter { it.name in current.selected })
        if (doomed.isEmpty()) return
        actions.run(Action.DELETE_SELECTED) {
            try {
                // Those still listed once its turn comes: one gone meanwhile -
                // from its notification, or auto-delete - said "could not
                // move 1" after a delete that worked.
                val listed = state.value.recordings.associateBy { it.uri }
                val still = doomed.mapNotNull { listed[it.uri] }
                if (still.any { it.name == state.value.playingName }) playback.stopPlayback()

                val result = withContext(Dispatchers.IO) {
                    Trash.trashAll(app, still)
                }
                // Any list open reads it again, as for a call saved: run past
                // its screen, a delete left a screen opened meanwhile listing
                // what had moved.
                if (result.deleted > 0) RecordingLibrary.announce("trash")
                // Whatever Android wants confirmed is not moved yet - its own
                // dialog comes next - so it is counted as asked, not as failed.
                trash.report(
                    still.size,
                    result,
                    done = { "moved $it to Recently deleted" },
                    couldNot = { "could not move $it" },
                    leaving = true,
                )
                state.update { it.copy(selecting = false, selected = emptySet()) }
                reading.refresh()
            } finally {
                claims.letGo(doomed)
            }
        }
    }

    private val claims = Claims()
}
