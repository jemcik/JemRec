package com.jemcik.jemrec.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import com.jemcik.jemrec.capture.Recording
import com.jemcik.jemrec.capture.Trash

/**
 * Recently deleted: what is in the trash, a way back out for each, and for
 * all of them at once - Restore all, and Delete all for good.
 *
 * No play button: playing a trashed recording means restoring it first,
 * which is one tap. At first there was no delete-for-good either, by choice -
 * deleting is the step this screen exists to take back, and time empties it
 * (see Trash). The user asked for both buttons (2026-10-23); Delete all for
 * good asks first, saying it cannot be undone, as it is the one step here
 * that cannot be.
 */
@Composable
fun TrashScreen(state: UiState, vm: MainViewModel, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    if (state.confirmingEmptyTrash) EmptyTrashDialog(state.trashed.size, vm)
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item {
            Text(
                if (state.trashed.isEmpty()) {
                    "Nothing here. Deleted recordings wait here for ${Trash.DAYS} days, " +
                        "and can be restored until then."
                } else {
                    "Each recording here is removed for good ${Trash.DAYS} days after it " +
                        "was deleted. Until then, Restore puts it back in the list."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            if (state.trashed.isNotEmpty()) {
                Row(
                    Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = { haptics.tap(); vm.restoreAll() }, enabled = !state.busy) {
                        Text("Restore all")
                    }
                    TextButton(
                        onClick = { haptics.tap(); vm.askEmptyTrash() },
                        enabled = !state.busy,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Delete all for good") }
                }
            }
        }
        itemsIndexed(state.trashed, key = { _, recording -> recording.name }) { index, recording ->
            if (index > 0) HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CallerAvatar(caller = recording.caller, photo = recording.callerPhoto)
                Column(Modifier.weight(1f)) {
                    Text(
                        recording.caller ?: recording.whenLabel,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(
                            recording.whenLabel.takeIf { recording.caller != null },
                            recording.durationLabel,
                            goesIn(recording),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        // Two lines: on one, at 360 dp, "goes in 14 days" -
                        // what this screen is for - was cut to "goes in 14 d...".
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(
                    onClick = { haptics.tap(); vm.restore(recording) },
                    enabled = !state.busy,
                ) { Text("Restore") }
            }
        }
    }
}

/** Delete all for good's question. The count again in the sentence, as the
 *  home list's delete has it: this is the last point at which it is read. */
@Composable
private fun EmptyTrashDialog(count: Int, vm: MainViewModel) {
    val haptics = LocalHapticFeedback.current
    AlertDialog(
        onDismissRequest = vm::cancelEmptyTrash,
        title = { Text(if (count == 1) "Delete it for good?" else "Delete all $count for good?") },
        text = {
            Text(
                if (count == 1) {
                    "The recording in Recently deleted is deleted now, and cannot be restored."
                } else {
                    "All $count recordings in Recently deleted are deleted now, and cannot be restored."
                }
            )
        },
        confirmButton = {
            TextButton(
                onClick = { haptics.confirm(); vm.confirmEmptyTrash() },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Delete for good") }
        },
        dismissButton = { TextButton(onClick = vm::cancelEmptyTrash) { Text("Cancel") } },
    )
}

/** "goes in 12 days", or "goes today" on the last one. */
private fun goesIn(recording: Recording): String? {
    val expires = recording.expiresAtMillis ?: return null
    val day = 24L * 60 * 60 * 1000
    val left = expires - System.currentTimeMillis()
    // Rounded up: with 2.5 days left it goes in 3, not 2.
    val days = if (left <= 0) 0L else (left + day - 1) / day
    // Non-breaking spaces: wrapped, it moves to the next line whole, not as
    // "goes in 14" over "days".
    return when {
        days <= 0L -> "goes\u00A0today"
        days == 1L -> "goes\u00A0tomorrow"
        else -> "goes\u00A0in\u00A0$days\u00A0days"
    }
}
