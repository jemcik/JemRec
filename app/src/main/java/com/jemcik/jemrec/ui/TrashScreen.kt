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
import com.jemcik.jemrec.capture.Recording
import com.jemcik.jemrec.capture.Trash

/**
 * Recently deleted: what is in the trash, and a way back out for each.
 *
 * No play button and no delete-for-good. Playing a trashed recording means
 * restoring it first, which is one tap; and nothing here empties the trash
 * early, by choice - deleting is the step this screen exists to take back,
 * and the wait is the point. Time empties it (see Trash).
 */
@Composable
fun TrashScreen(state: UiState, vm: MainViewModel, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
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
                        maxLines = 1,
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

/** "goes in 12 days", or "goes today" on the last one. */
private fun goesIn(recording: Recording): String? {
    val expires = recording.expiresAtMillis ?: return null
    val day = 24L * 60 * 60 * 1000
    val left = expires - System.currentTimeMillis()
    // Rounded up: with 2.5 days left it goes in 3, not 2.
    val days = if (left <= 0) 0L else (left + day - 1) / day
    return when {
        days <= 0L -> "goes today"
        days == 1L -> "goes tomorrow"
        else -> "goes in $days days"
    }
}
