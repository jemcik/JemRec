package com.jemcik.jemrec.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp

/**
 * How to share a recording, and the wait while it converts - for a Share
 * tapped on either tab: drawn by AppScaffold, over whichever is showing. It
 * was HomeScreen's, from when only the Recordings tab had Share; the Calls
 * tab's recorded rows got Share buttons, and a tap there set the choice and
 * showed nothing - until the Recordings tab was next opened.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShareSheets(state: UiState, vm: MainViewModel) {
    val haptics = LocalHapticFeedback.current
    // Share asks HOW first: the original Opus is instant and fine for Android
    // and desktop; the .m4a plays on Apple's stock apps too but has to be
    // converted. A bottom sheet, not a dialog: a short menu of ways to do one
    // thing, with room to say what each costs.
    state.shareChoice?.let { recording ->
        ModalBottomSheet(onDismissRequest = { vm.dismissShareChoice() }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(
                    "Share as",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
                )
                ListItem(
                    headlineContent = { Text("Any device (.m4a)") },
                    supportingContent = {
                        Text("Converts first - plays on iPhone, Mac, Windows, anything")
                    },
                    leadingContent = {
                        Icon(Icons.Filled.Share, contentDescription = null)
                    },
                    modifier = Modifier.clickable {
                        haptics.tap(); vm.shareUniversal(recording)
                    },
                )
                ListItem(
                    headlineContent = { Text("Original (.ogg)") },
                    supportingContent = {
                        Text("No conversion, smaller - best for Android and desktop")
                    },
                    leadingContent = {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    },
                    modifier = Modifier.clickable {
                        haptics.tap(); vm.shareOriginal(recording)
                    },
                )
            }
        }
    }

    // While the .m4a is being made. Indeterminate - the wait is the length of
    // the call, not a known number of steps - and not dismissible: cancelling
    // half a transcode just leaves nothing to share. A short blocking wait, so
    // a spinner with a word, not a whole screen.
    if (state.converting) {
        AlertDialog(
            // Back or a tap outside cancels too - the same as the button, so the
            // dialog is never a trap while it works.
            onDismissRequest = { vm.cancelConversion() },
            confirmButton = {
                TextButton(onClick = { haptics.tap(); vm.cancelConversion() }) {
                    Text("Cancel")
                }
            },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator()
                    Text("Converting to .m4a…")
                }
            },
        )
    }
}
