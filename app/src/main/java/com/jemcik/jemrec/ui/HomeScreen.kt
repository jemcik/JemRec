package com.jemcik.jemrec.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jemcik.jemrec.capture.AudioAccess
import com.jemcik.jemrec.recordings.Recording
import com.jemcik.jemrec.recordings.Trash
import com.jemcik.jemrec.ui.theme.JemRecColors
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The recordings, and nothing else.
 *
 * It used to open with a status card and a mode switch, so the list people came
 * to read started below the fold behind two things they had already read. State
 * and the on/off switch moved into the header, which does not scroll - whether
 * the app is recording your calls should not be something you scroll to find -
 * and the mode went to Settings, where a preference belongs.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: UiState,
    vm: MainViewModel,
    askForCallLog: () -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    narrowingAside: Boolean = false,
) {
    val haptics = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val narrowing = state.recordingsNarrowing
    val narrowed = narrowingOf(state, vm, calls = false)
    val searchFocus = rememberSearchFocus(narrowed.searchOpen, narrowingAside)
    // Entering selection blurs the field and drops the keyboard. The field
    // stays on screen as context - the query you searched by - but you are
    // picking rows now, not typing, so the keyboard should not be eating the
    // list, and the text cursor should not be left dangling under the field.
    LaunchedEffect(state.selecting) {
        if (state.selecting) focusManager.clearFocus()
    }
    if (state.confirmingDeleteSelected) {
        val count = state.selected.size
        AlertDialog(
            onDismissRequest = vm::cancelDeleteSelected,
            title = {
                Text(if (count == 1) "Delete this recording?" else "Delete $count recordings?")
            },
            text = {
                // The count again, in the sentence. A number in a title is
                // glanced at; a number in the sentence you are agreeing to is
                // read - and this is the last point at which it can be read.
                Text(
                    if (count == 1) {
                        "It goes to Recently deleted, in Settings, where it can be " +
                            "restored for ${Trash.DAYS} days."
                    } else {
                        "All $count of them go to Recently deleted, in Settings, where " +
                            "they can be restored for ${Trash.DAYS} days."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { haptics.confirm(); vm.confirmDeleteSelected() },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = vm::cancelDeleteSelected) { Text("Cancel") }
            },
        )
    }

    val audioPermission = rememberPermissionRequest { vm.onAudioAccessResult() }

    // Filtered, cut and grouped once per change of what they depend on - the
    // grouping also per day, which the headings read - not on every
    // recomposition: the player's position ticks recompose this screen three
    // times a second.
    val now = LocalNow.current
    val today = dayStamp(now())
    val filtered = remember(state.recordings, narrowing) { filterRecordings(state.recordings, narrowing) }
    val shown = remember(filtered, state.visibleCount) { filtered.take(state.visibleCount) }
    val days = remember(shown, today) { byDay(shown, now()) { it.startedAtMillis } }
    // A day's count is all of its recordings, not the ones the endless
    // scroll has drawn so far: counted from `shown`, Earlier read 15 beside a
    // tab saying 50, and grew as the list scrolled.
    val dayCounts = remember(filtered, today) { filtered.groupingBy { dayOf(it.startedAtMillis, now()) }.eachCount() }

    // Endless scroll instead of a "Show more" button. Everything is already in
    // memory - the list, the durations, the caller lookups - so a button gating
    // it was friction in front of data that was right there. As the last rows
    // come into view the window quietly widens; with nothing to fetch there is
    // no spinner, and it stops on its own once the whole filtered list is shown.
    // Not before the list is measured: with nothing laid out yet, "the last
    // row in view" read as row 0 of 0 - near the end - so every time the tab
    // was shown anew a page came, and select-all then took rows nobody had
    // scrolled to.
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            last >= listState.layoutInfo.totalItemsCount - 3
        }
    }
    LaunchedEffect(nearEnd, filtered.size, state.visibleCount) {
        if (nearEnd && filtered.size > state.visibleCount) vm.showMore()
    }
    val scrolled by remember { derivedStateOf { listState.canScrollBackward } }

    // A notification's Play or Share: its row brought into view, below the
    // pinned header - from a list left scrolled down, the recording played
    // with its player off the screen - unless it is in view already. Keyed on
    // the link alone, and let go however it ends - a touch taking the scroll
    // over, the row not drawn - or it waited, and a later change to the list
    // jumped to it while the user was elsewhere.
    val offer = state.offerCallLog && state.recordings.isNotEmpty()
    val tip = !state.selectHintSeen && !state.selecting
    val placeOf by rememberUpdatedState { name: String -> placeInList(name, offer, tip, days) }
    val reveal = state.reveal
    LaunchedEffect(reveal) {
        val name = reveal ?: return@LaunchedEffect
        try {
            // Drawn - its window was widened with the link - and laid out as
            // drawn, for the header's height and where the row is now.
            val (place, items) = withTimeoutOrNull(2_000) {
                snapshotFlow { placeOf(name) }.filterNotNull().first()
            } ?: return@LaunchedEffect
            withTimeoutOrNull(2_000) { snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it == items } }
            val laid = listState.layoutInfo
            val pinned = laid.visibleItemsInfo.firstOrNull { it.index == (if (offer) 1 else 0) }?.size ?: 0
            val row = laid.visibleItemsInfo.firstOrNull { it.key == name }
            if (row == null || row.offset < pinned || row.offset + row.size > laid.viewportEndOffset) {
                listState.scrollToItem(place, -pinned)
            }
        } finally {
            vm.onRevealed(name)
        }
    }

    // THE LIST IS ITS OWN SCROLL, so its header can PIN. It used to be one item
    // in the page-wide scroll, which meant "Recordings / Select" scrolled away
    // with the rows - wrong for a header, which has to stay reachable while you
    // move through a long list. A LazyColumn with a sticky header keeps it put,
    // the way a calls or messages list does, and is why the home screen is no
    // longer wrapped in the page scroll.
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        // No TOP content padding: it would sit above the sticky header and let
        // it travel that far before pinning. The header carries its own top
        // padding instead, which pins with it. Bottom padding is fine - it is
        // below everything and never pins.
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        // Offered once, above the thing it would improve. It scrolls away (not
        // pinned): it is a one-time suggestion, not part of the list's chrome.
        if (offer) {
            item {
                Card(Modifier.padding(horizontal = 16.dp).padding(top = 16.dp, bottom = 16.dp)) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Show who called", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "JemRec can label each recording with the contact's name " +
                                "and photo, or the number if they are not in your " +
                                "contacts. That means permission to read your call log, " +
                                "which holds the name, and your contacts, which hold " +
                                "the photo. The call log also lets JemRec tell you if a " +
                                "call ever goes by unrecorded.\n\nNothing else changes, " +
                                "and you can say no to either.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = {
                                    haptics.tap()
                                    askForCallLog()
                                },
                            ) { Text("Allow") }
                            TextButton(
                                onClick = { haptics.tap(); vm.dismissCallLogOffer() },
                            ) { Text("Not now") }
                        }
                    }
                }
            }
        }

        // Pinned, and on the list's own ground. It was a grey band, which
        // put a white header, the rule, grey chips and a white list in a stack
        // - the tabs above it looked as if they belonged to nothing. The tint
        // now belongs to the app bar and tabs (see MainScreen); the filters
        // are part of the list they filter. Opaque, so rows scroll UNDER it
        // without showing through.
        stickyHeader {
            // Presets, and a search that stays folded away until asked for.
            // A full-width search bar sat here permanently for something used
            // once in a while; now the chips are all that is pinned, and the
            // last chip unfolds the field below them on demand. Hidden while
            // selecting, where the header is a selection toolbar instead. The
            // search field is deliberately NOT gated on selecting: it stays
            // open through selection, so the query you searched by is still on
            // screen while you tick rows and delete - and the list does not
            // jump as it vanishes. Beside the list instead, on a phone on its
            // side (NarrowingPanel), chips and search both.
            NarrowingHeader(
                narrowed,
                shown = filtered.size,
                of = state.recordings.size,
                onShowAll = vm::showAllRecordings,
                aside = narrowingAside,
                focus = searchFocus,
                listed = state.recordings.isNotEmpty(),
                chipsShown = !state.selecting,
                scrolled = scrolled,
            ) {
                // NO "Recordings" TITLE, NO "Select" BUTTON. The screen is the
                // recordings, so a heading only restated the obvious and cost a
                // whole pinned row. Selection is entered by tapping a row's photo
                // (Gmail/Photos), so the only thing pinned here between calls is
                // what actually filters the list - search and presets. During
                // selection this row becomes the toolbar instead.
                // The chip row and the selection toolbar just need the header's
                // height and inset; the section's surfaceContainerHigh background
                // is carried by the Column now, so both rows are transparent on it
                // and the field below shares the same ground. HeaderRowHeight so
                // swapping rows never moves the list.
                val headerBand = Modifier
                    .fillMaxWidth()
                    .height(HeaderRowHeight)
                    .padding(horizontal = 16.dp)
                if (state.selecting) {
                    Row(
                        modifier = headerBand,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = { haptics.tap(); vm.clearSelection() },
                            modifier = Modifier.offset(x = (-12).dp),
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Cancel selection",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Text(
                            if (state.selected.isEmpty()) "Select recordings"
                            else "${state.selected.size} selected",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        // Tri-state rather than a guessed "select all" glyph:
                        // it says what tapping will do and reports what is
                        // already picked, which an icon cannot.
                        val picked = picked(state.selected, shown.map { it.name })
                        TriStateCheckbox(
                            state = when (picked) {
                                Picked.NONE -> ToggleableState.Off
                                Picked.ALL -> ToggleableState.On
                                Picked.SOME -> ToggleableState.Indeterminate
                            },
                            onClick = {
                                val selectingAll = picked != Picked.ALL
                                haptics.toggle(selectingAll)
                                if (selectingAll) vm.selectAllVisible() else vm.deselectAll()
                            },
                        )
                        // Hidden with nothing picked. A delete button that
                        // deletes nothing is a question mark on a toolbar whose
                        // whole job is to be unambiguous.
                        if (state.selected.isNotEmpty()) {
                            // Keep marks them all, or - when every one is
                            // kept already - takes the mark off them all. The
                            // icon says which: the one tapping will leave.
                            val allKept = state.recordings
                                .filter { it.name in state.selected }
                                .all { it.kept }
                            IconButton(
                                onClick = { haptics.tap(); vm.keepSelected() },
                                modifier = Modifier.offset(x = 12.dp),
                            ) {
                                Icon(
                                    if (allKept) KeepIcons.NotKept else KeepIcons.Kept,
                                    contentDescription = if (allKept) "Stop keeping selected" else "Keep selected",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            IconButton(
                                onClick = { haptics.tap(); vm.askDeleteSelected() },
                                modifier = Modifier.offset(x = 12.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Delete selected",
                                    tint = JemRecColors.delete,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (state.recordings.isEmpty()) {
            item {
                // AN EMPTY LIST SAYS IT IS EMPTY. IT DOES NOT EXPLAIN THE APP.
                //
                // This read "Nothing yet. Recording starts by itself when a
                // call does" - a sentence about how the recorder works rather
                // than about the list, in the one place a person is looking for
                // their recordings. It was also not true: with "Record every
                // call" off nothing starts by itself, the app asks first. The
                // header already says which mode is on, so this says the one
                // thing the header cannot: there is nothing here.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp, vertical = 56.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Phone,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "No recordings yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    // The exception to saying nothing else, because here the
                    // list is empty and WRONG: reinstalling leaves the old
                    // recordings on the phone but unreadable, and a person
                    // looking at "no recordings" beside a folder full of them
                    // is owed the reason rather than left to conclude the app
                    // threw their calls away.
                    if (!state.canReadAudio) {
                        // Conditional: on a first install there is nothing from
                        // before, and saying there is would be false.
                        Text(
                            "If JemRec was installed on this phone before, the calls " +
                                "it recorded then are still here. " + AudioAccess.askFor,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        FilledTonalButton(
                            onClick = {
                                haptics.tap()
                                audioPermission.launch(AudioAccess.PERMISSIONS)
                            },
                        ) { Text("Allow") }
                    }
                }
            }
        } else if (shown.isEmpty()) {
            item {
                NoMatches(
                    if (narrowing.messages) {
                        "No messages match."
                    } else if (narrowing.kept) {
                        "No kept recordings match."
                    } else if (narrowing.favorites) {
                        "No starred recordings match."
                    } else {
                        "No recordings match your search or filter."
                    },
                )
            }
        } else {
            // A one-time nudge toward the way in that has no button: selection
            // starts by tapping a photo or pressing and holding a row, and
            // nothing on screen says so until you try. It goes the first time
            // selection is used, or at its x.
            //
            // A TIP, NOT A CAPTION. It was a line of grey text above the list,
            // which read as a label a developer forgot - and half of it said
            // what every row's play mark already shows. Now it says only the
            // part that cannot be seen, in a card that is plainly a tip and
            // plainly dismissable. The Calls tab has none: its rows show the
            // play mark too, and selection does not exist there.
            if (!state.selectHintSeen && !state.selecting) {
                item(key = "tip", contentType = "tip") {
                    SelectTip(onDismiss = { haptics.tap(); vm.dismissSelectHint() })
                }
            }
            // Under Today, Yesterday and Earlier, as a phone's call history
            // reads, with no rules between rows: the headings do the grouping.
            days.forEachIndexed { run, (day, rows) ->
                item(key = "day-$run", contentType = "day") { DayHeading(day, dayCounts[day] ?: rows.size) }
                items(rows, key = { it.name }, contentType = { "recording" }) { recording ->
                    RecordingRow(recording, day, state, vm)
                }
            }

            // No "Show more" footer: the list extends itself as you scroll (see
            // the endless-scroll effect above), so the window grows to the whole
            // filtered list on its own and there is nothing to tap.
        }
    }
}

/** The tip over the recordings: how selection starts, with an x to put it away. */
@Composable
private fun SelectTip(onDismiss: () -> Unit) {
    TipCard(
        "Tap a photo, or press and hold a call, to select recordings",
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
        onDismiss = onDismiss,
    )
}

/**
 * Where [name]'s row is in HomeScreen's list - or, the first of its day, its
 * day's heading, so both show - and how many items the list has, counted as
 * the LazyColumn adds them: the call-log [offer], the header, the [tip], then
 * each day's heading and rows. Null if the row is not drawn.
 */
private fun placeInList(name: String, offer: Boolean, tip: Boolean, days: List<Pair<Day, List<Recording>>>): Pair<Int, Int>? {
    var at = (if (offer) 1 else 0) + 1 + (if (tip) 1 else 0)
    var place: Int? = null
    for ((_, rows) in days) {
        val row = rows.indexOfFirst { it.name == name }
        if (row >= 0 && place == null) place = if (row == 0) at else at + 1 + row
        at += 1 + rows.size
    }
    return place?.let { it to at }
}
