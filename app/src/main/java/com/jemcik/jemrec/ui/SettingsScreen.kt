package com.jemcik.jemrec.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.jemcik.jemrec.capture.CallLogLookup

/**
 * The things that are not the answer to "would a call be recorded right now".
 *
 * Configuration only. Deleting recordings briefly lived here and should not
 * have: settings are where you change how the app behaves, not where you act
 * on its contents. A person wanting to delete a recording goes to the list of
 * recordings, because that is where the recordings are - so selection and
 * deletion live there now.
 */
@Composable
fun SettingsScreen(state: UiState, vm: MainViewModel) {

    val haptics = LocalHapticFeedback.current

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) vm.onFolderChosen(uri) }

    if (state.confirmingReset) {
        AlertDialog(
            onDismissRequest = vm::cancelReset,
            title = { Text("Start fresh?") },
            text = {
                Text(
                    "JemRec will hand back the permissions you granted, turn " +
                        "Wireless debugging off, forget its pairing and stop the " +
                        "recorder. Setup starts again from the first step.\n\n" +
                        "The app will close. Open it again to set it up.\n\n" +
                        "Your recordings are NOT deleted."
                )
            },
            confirmButton = {
                TextButton(onClick = { haptics.confirm(); vm.confirmReset() }) { Text("Start fresh") }
            },
            dismissButton = { TextButton(onClick = vm::cancelReset) { Text("Cancel") } },
        )
    }

    val callLogPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { vm.onCallLogResult() }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {

        // Only while it is worth offering. Once granted there is nothing to
        // do here, and the recordings list shows the result better than a
        // settings row saying "on" ever could.
        if (!state.canReadCallLog) {
            Card {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Show who called", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Label each recording with the contact's name and photo, or the " +
                            "number if they are not in your contacts. Needs permission " +
                            "to read your call log for the name and your contacts for " +
                            "the photo, and applies to recordings you already have.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(
                        onClick = { haptics.tap(); callLogPermission.launch(CallLogLookup.PERMISSIONS) },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Allow") }
                }
            }
        }

        // Title and its supporting text belong TOGETHER, with the switch
        // centred against the pair - the standard Material 3 preference row.
        // They were a title-plus-switch row with the supporting text dropped
        // underneath as a separate block, which left the switch's height as a
        // gap between the two and made the text read as detached from what it
        // describes.
        Card {
            Row(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Record every call", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (state.automatic) {
                            "Every call is recorded, without asking."
                        } else {
                            "A notification appears when a call starts, with a Record " +
                                "button. Nothing is captured until you tap it, so the " +
                                "opening seconds of the call are not saved."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.automatic,
                    onCheckedChange = { haptics.toggle(it); vm.setAutomatic(it) },
                )
            }
        }

        Card {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Saving to", style = MaterialTheme.typography.titleMedium)
                Text(
                    state.folder,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    if (state.usingDefaultFolder) {
                        "The phone's standard Recordings folder. Any file manager " +
                            "or music app can open these."
                    } else {
                        "A folder you chose."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Full width, and split evenly when both are present: one
                // button fills the row, two share it. Same tonal fill and the
                // same width treatment as every other action on this screen, so
                // the cards stop disagreeing with each other.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = { haptics.tap(); folderPicker.launch(null) },
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f),
                    ) { Text("Change") }
                    if (!state.usingDefaultFolder) {
                        FilledTonalButton(
                            onClick = { haptics.tap(); vm.useDefaultFolder() },
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f),
                        ) { Text("Use default") }
                    }
                }
            }
        }

        Card {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Start fresh", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Put JemRec back to how it was when you installed it, so setup " +
                        "runs again from the beginning. Useful if pairing has gone " +
                        "wrong, or before handing the phone to someone else.\n\n" +
                        "Your recordings are kept.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Same shape and width as the others, tinted red rather than
                // shouting. A destructive action should be as easy to find as
                // the rest, but coloured so a glance tells it apart - and it
                // still asks before doing anything.
                FilledTonalButton(
                    onClick = { haptics.tap(); vm.askReset() },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) { Text("Start fresh") }
            }
        }

    }
}
