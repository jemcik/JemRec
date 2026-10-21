package com.jemcik.jemrec.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jemcik.jemrec.capture.CallHistory
import com.jemcik.jemrec.capture.CallHistory.Kind
import com.jemcik.jemrec.capture.CallHistory.Status
import com.jemcik.jemrec.capture.CallLogLookup
import com.jemcik.jemrec.ui.theme.JemRecColors

/**
 * The Calls tab: every call in the phone's log, with a play mark on the ones
 * that were recorded and, on the ones that were not, why - where JemRec knows.
 * See CallHistory for how a call is matched and explained.
 *
 * Rows only inform. A recorded call plays on a tap, exactly as in the
 * Recordings tab, and its player opens under it; keeping, sharing and deleting
 * stay in the Recordings tab, so a row here gives its whole width to who,
 * when, and what happened.
 */
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

    // Grouped once per call log and per day, not on every recomposition.
    val today = dayStamp(System.currentTimeMillis())
    val days = remember(state.calls, today) {
        byDay(state.calls.withIndex().toList(), System.currentTimeMillis()) { it.value.at }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
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
        // Under Today, Yesterday and Earlier, as on the Recordings tab.
        days.forEachIndexed { run, (day, rows) ->
            item(key = "day-$run", contentType = "day") { DayHeading(day) }
            items(rows, key = { (index, call) -> "$index-${call.at}" }, contentType = { "call" }) { (_, call) ->
                val recording = call.recording
                val isCurrent = recording != null && state.playingName == recording.name
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .background(
                                if (isCurrent) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
                            )
                            .then(
                                if (recording != null) {
                                    Modifier.clickable(onClickLabel = "Play") { haptics.tap(); vm.play(recording) }
                                } else {
                                    Modifier
                                },
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CallerAvatar(caller = call.label, photo = call.photo)
                        Column(Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    call.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (call.favorite) {
                                    Icon(
                                        Icons.Filled.Star,
                                        contentDescription = "Favourite contact",
                                        tint = JemRecColors.favorite,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                            CallLine(call, day, playing = isCurrent && state.playing)
                        }
                    }
                    if (isCurrent && state.durationMs > 0) PlayerControls(state, vm)
                }
            }
        }
    }
}

/**
 * The second line: which way the call went, when (the time alone under Today
 * and Yesterday, whose headings say the day), and either the play mark with
 * the recording's length or what became of the call.
 */
@Composable
private fun CallLine(call: CallHistory.Call, day: Day, playing: Boolean) {
    val missed = call.kind != Kind.INCOMING && call.kind != Kind.OUTGOING
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DirectionArrow(
            incoming = call.incoming,
            // Missed and declined in the error colour, the way every phone app
            // marks a call nobody answered.
            tint = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            description = kindWord(call),
        )
        Text(
            "${timeUnder(day, call.at)} ·",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        val recording = call.recording
        if (recording != null) {
            if (playing) {
                PauseGlyph(tint = JemRecColors.play, size = 14.dp)
            } else {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = JemRecColors.play,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                recording.durationLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        } else {
            Text(
                listOfNotNull(
                    length(call.seconds).takeIf { call.status != Status.NOT_CONNECTED },
                    statusWords(call),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                // Red only for the one that should not have happened: recording
                // was on and automatic, and still nothing was saved.
                color = if (call.status == Status.NOT_RECORDED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** What became of a call with no recording, in a few words. */
private fun statusWords(call: CallHistory.Call): String = when (call.status) {
    Status.RECORDED -> ""
    Status.IN_TRASH -> "In Recently deleted"
    Status.DELETED -> "Recording deleted"
    Status.SKIPPED -> "Left out by your rules"
    Status.TOO_SHORT -> "Too short"
    Status.PENDING -> "Recorded, not saved yet"
    Status.RECORDING_OFF -> "Recording was off"
    Status.ASK_FIRST -> "Ask first, not recorded"
    Status.NOT_RECORDED -> "Not recorded"
    Status.NO_RECORDING -> "No recording"
    Status.NOT_CONNECTED -> kindWord(call)
}

private fun kindWord(call: CallHistory.Call): String = when (call.kind) {
    Kind.INCOMING -> "Incoming"
    Kind.OUTGOING -> if (call.status == Status.NOT_CONNECTED) "No answer" else "Outgoing"
    Kind.MISSED -> "Missed"
    Kind.DECLINED -> "Declined"
    Kind.BLOCKED -> "Blocked"
    Kind.VOICEMAIL -> "Voicemail"
    Kind.OTHER -> "Answered elsewhere"
}

/** "3:21", from the call log's seconds. */
private fun length(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)
