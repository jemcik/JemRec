package com.jemcik.jemrec.ui

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandVertically
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jemcik.jemrec.capture.CallHistory
import com.jemcik.jemrec.capture.CallLogLookup
import com.jemcik.jemrec.capture.Recording

/**
 * The Calls tab: every call in the phone's log, with a play mark on the ones
 * that were recorded and, on the ones that were not, why - where JemRec knows.
 * See CallHistory for how a call is matched and explained.
 *
 * A recorded call's row is its Recordings row - played, kept and shared the
 * same way, with the same player - and a call with no recording says, in a
 * row of the same shape, what became of it (CallRow): design A of the
 * canvas's "The Calls tab, four ways", the user's choice (2026-10-23), so the
 * two tabs' rows are one design. It used to be a list of its own that only
 * informed. Deleting stays in the Recordings tab, behind selection, which
 * this tab does not have. Over the list, the Recordings tab's chips by the
 * same rule, and its own: Not recorded.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CallsScreen(state: UiState, vm: MainViewModel, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val callLogPermission = rememberPermissionRequest { vm.onCallLogResult() }

    // The tab is the call log; without it there is nothing to show but the
    // reason, and the way to change it.
    if (!state.canReadCallLog) {
        Column(modifier.fillMaxSize().padding(16.dp)) {
            Card {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("See every call here", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "This tab is your phone's call log, with each call's recording " +
                            "beside it - and, for a call that was not recorded, why. It " +
                            "needs permission to read your call log, and your contacts " +
                            "for names and photos. Nothing leaves the phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(
                        onClick = { haptics.tap(); callLogPermission.launch(CallLogLookup.PERMISSIONS) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Allow") }
                }
            }
        }
        return
    }

    // Narrowed by the tab's own chips and search, then grouped by day - once
    // per change of what they depend on, not on every recomposition.
    val now = LocalNow.current
    val today = dayStamp(now())
    val narrowing = state.callsNarrowing
    val shown = remember(state.calls, narrowing) { filterCalls(state.calls, narrowing) }
    val days = remember(shown, today) { byDay(keyedCalls(shown), now()) { it.call.at } }
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(narrowing.searchOpen) {
        if (narrowing.searchOpen) runCatching { searchFocus.requestFocus() }
    }
    val listState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { listState.canScrollBackward } }

    // No selection here: a recorded call's row is its Recordings row in
    // every other way, Keep and Share included (see LocalRowsSelectable).
    CompositionLocalProvider(LocalRowsSelectable provides false) {
        LazyColumn(
            state = listState,
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            // The Recordings tab's chips, by the same rule (chipsFor), and its
            // search; pinned and on the list's ground, as there.
            if (state.calls.isNotEmpty()) {
                stickyHeader {
                    Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
                        val on = narrowing.chipsOn()
                        val chips = remember(state.calls, on) { chipsFor(state.calls.map { it.chipFacts() }, on) }
                        ListChipRow(chips, on, calls = true) { chip -> haptics.tap(); vm.tapCallsChip(chip) }
                        AnimatedVisibility(
                            visible = narrowing.searchOpen,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut(),
                        ) {
                            ListSearchField(narrowing.query, vm::setCallsQuery, searchFocus, "Search by name or number")
                        }
                        if (scrolled) HorizontalDivider()
                    }
                }
            }
            if (state.calls.isEmpty()) {
                item {
                    Text(
                        "No calls in the call log yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                    )
                }
            }
            // The chips and search left nothing: said, as on the Recordings
            // tab, not a blank list under the chips.
            if (state.calls.isNotEmpty() && shown.isEmpty()) {
                item {
                    NoMatches(
                        when {
                            narrowing.notRecorded -> "No unrecorded calls match."
                            narrowing.messages -> "No messages match."
                            narrowing.kept -> "No calls with a kept recording match."
                            narrowing.favorites -> "No calls with favourite contacts match."
                            else -> "No calls match your search."
                        },
                    )
                }
            }
            // Under Today, Yesterday and Earlier, as on the Recordings tab, with
            // how many each holds.
            days.forEachIndexed { run, (day, rows) ->
                item(key = "day-$run", contentType = "day") { DayHeading(day, rows.size, calls = true) }
                items(rows, key = { it.key }, contentType = { "call" }) { (_, call) ->
                    val recording = call.recording
                    if (recording != null) {
                        RecordingRow(recording.forCall(call), day, state, vm)
                    } else {
                        CallRow(call, day)
                    }
                }
            }
        }
    }
}

/**
 * [this] recording, as the row of [call] shows it: the call's own name,
 * photo, star, direction and time, as the Calls tab's rows always showed. A
 * call that came in during another's recording (call waiting) shares that
 * recording, and drawn as the recording, its row repeated the first caller.
 */
internal fun Recording.forCall(call: CallHistory.Call): Recording = copy(
    caller = call.label,
    callerPhoto = call.photo,
    callerFavorite = call.favorite,
    incoming = call.incoming,
    shownAt = call.at,
)
