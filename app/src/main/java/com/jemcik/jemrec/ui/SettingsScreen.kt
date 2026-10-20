package com.jemcik.jemrec.ui

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.annotation.RequiresApi
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jemcik.jemrec.R
import com.jemcik.jemrec.capture.CallLogLookup
import com.jemcik.jemrec.capture.CallRules
import com.jemcik.jemrec.capture.FileNames
import com.jemcik.jemrec.capture.Retention
import com.jemcik.jemrec.capture.Trash

/**
 * The things that are not the answer to "would a call be recorded right now".
 *
 * Configuration only. Deleting recordings briefly lived here and should not
 * have: settings are where you change how the app behaves, not where you act
 * on its contents. A person wanting to delete a recording goes to the list of
 * recordings, because that is where the recordings are - so selection and
 * deletion live there now. Recently deleted is reached from here, beside the
 * auto-delete rules that fill it, because it is somewhere you go now and
 * then to take something back - not part of the list you use every day.
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
                            "number if they are not in your contacts, and be told if a " +
                            "call ever goes by unrecorded. Needs permission to read your " +
                            "call log for the name and that check, and your contacts for " +
                            "the photo. Applies to recordings you already have.",
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

        WhoseCallsToKeep(
            state, vm,
            allow = { callLogPermission.launch(CallLogLookup.PERMISSIONS) },
        )

        // The notification that follows each call. Its switch is only about
        // the everyday one: a recording that came out silent is reported
        // either way, because that is the news nobody should miss.
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
                    Text("Show each call when it ends", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "A silent notification after every recorded call, with Play, Share " +
                            "and Delete. A recording that came out silent is reported " +
                            "either way.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.afterCallNotice,
                    onCheckedChange = { haptics.toggle(it); vm.setAfterCallNotice(it) },
                )
            }
        }

        // The tile is the same switch as the one in the header. Android 13
        // and later can add it for the user; before that, all this card can do
        // is say where it is.
        val context = LocalContext.current
        Card {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Quick Settings tile", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Turn recording on and off from the Quick Settings panel, without " +
                        "opening JemRec. " +
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            "Or edit the panel and drag JemRec into place."
                        } else {
                            "Edit the panel and drag JemRec into place."
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    FilledTonalButton(
                        onClick = { haptics.tap(); addTile(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Add the tile") }
                }
            }
        }

        // ONLY WHERE THERE IS SOMETHING TO OPEN. On a phone with no launch
        // manager this card would be an offer to fix something that is not
        // broken, which is worse than saying nothing - so AutoStart answers
        // whether the screen exists before the card is drawn at all.
        if (remember { AutoStart.available(context) }) {
            Card {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Survive a restart", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "A reboot stops the recorder, and JemRec rebuilds it by itself " +
                            "the moment the phone is back on Wi-Fi - but only if this " +
                            "phone lets it start on its own after a restart. Yours has a " +
                            "list for that. Find JemRec in it and allow it to start " +
                            "automatically.\n\n" +
                            "Without it nothing is recorded after a restart until you " +
                            "open JemRec once, which also works.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(
                        onClick = { haptics.tap(); AutoStart.open(context) },
                    ) { Text("Open ${AutoStart.label(context)}") }
                }
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

        FileNamesCard(
            state, vm,
            allow = { callLogPermission.launch(CallLogLookup.PERMISSIONS) },
            chooseHonorFolder = { folderPicker.launch(FileNames.honorFolderUri) },
        )

        // Both rules off until chosen. Nothing they remove is gone: it waits
        // in Recently deleted, said here so nobody picks a rule fearing it.
        Card {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Auto-delete", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Recordings you mark to keep are never removed. Anything removed " +
                        "waits in Recently deleted for ${Trash.DAYS} days first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Recordings older than", style = MaterialTheme.typography.labelLarge)
                Choices(
                    choices = Retention.AGE_CHOICES,
                    selected = state.autoDeleteAfterDays,
                    label = { days ->
                        when (days) {
                            0 -> "Never"
                            180 -> "6 months"
                            365 -> "1 year"
                            else -> "$days days"
                        }
                    },
                    enabled = !state.busy,
                    onChoose = { haptics.tap(); vm.setAutoDelete(afterDays = it) },
                )
                Text("Calls shorter than", style = MaterialTheme.typography.labelLarge)
                Choices(
                    choices = Retention.SHORT_CHOICES,
                    selected = state.autoDeleteShorterThan,
                    label = { seconds -> if (seconds == 0) "Off" else "$seconds s" },
                    enabled = !state.busy,
                    onChoose = { haptics.tap(); vm.setAutoDelete(shorterThan = it) },
                )
            }
        }

        Card {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Recently deleted", style = MaterialTheme.typography.titleMedium)
                Text(
                    when (val count = state.trashed.size) {
                        0 -> "Empty. Deleted recordings wait here for ${Trash.DAYS} days, " +
                            "and can be restored until then."
                        1 -> "1 recording, which can be restored until its ${Trash.DAYS} days are up."
                        else -> "$count recordings, which can be restored until their " +
                            "${Trash.DAYS} days are up."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(
                    onClick = { haptics.tap(); vm.showTrash() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open") }
            }
        }

        // Setup's debugging question, answerable again. Debugging itself is
        // not a choice here - the recorder needs it - so the card says what
        // stays on whichever way the switch is set, and the switch is only
        // about Android's reminder of it. See DebugNotificationCleaner.
        Card {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            "Hide the debugging notification",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "USB and Wireless debugging stay on while JemRec is " +
                                "installed, because it records through them. A " +
                                "computer paired with this phone can connect to it " +
                                "on the same Wi-Fi. " +
                                if (state.debugNotificationHidden) {
                                    "Android's notification saying so is hidden."
                                } else {
                                    "Android's notification saying so is showing."
                                },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.debugNotificationHidden,
                        onCheckedChange = { haptics.toggle(it); vm.setHideDebugNotification(it) },
                    )
                }
                if (state.debugNotificationProblem) {
                    Text(
                        "Could not turn this on: it needs Wi-Fi. Try again on Wi-Fi.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
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

/**
 * Whose calls to keep (see CallRules): which contacts, and other numbers.
 *
 * Both questions need to know who a call was with, which only the call log
 * and the contacts can say - so without them the card says what it would do
 * and offers the permission, rather than showing switches that cannot work.
 */
@Composable
private fun WhoseCallsToKeep(state: UiState, vm: MainViewModel, allow: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val contactPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickContact()
    ) { uri -> if (uri != null) vm.addRuleContact(uri) }

    Card {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Whose calls to keep", style = MaterialTheme.typography.titleMedium)
            if (!state.canReadCallLog || !state.canReadContacts) {
                Text(
                    "Every call is kept. To leave some out - keep only your " +
                        "contacts' calls, say, or everyone's but a few people's - JemRec " +
                        "needs to read your call log and contacts, to tell who a call " +
                        "was with.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(
                    onClick = { haptics.tap(); allow() },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Allow") }
                return@Column
            }

            Text("Your contacts", style = MaterialTheme.typography.labelLarge)
            Choices(
                choices = CallRules.Contacts.entries,
                selected = state.ruleContacts,
                label = { contacts ->
                    when (contacts) {
                        CallRules.Contacts.ALL -> "All"
                        CallRules.Contacts.ALL_BUT -> "All but…"
                        CallRules.Contacts.ONLY -> "Only…"
                        CallRules.Contacts.NONE -> "None"
                    }
                },
                enabled = !state.busy,
                onChoose = { haptics.tap(); vm.setRuleContacts(it) },
            )
            if (state.ruleContacts == CallRules.Contacts.ALL_BUT || state.ruleContacts == CallRules.Contacts.ONLY) {
                if (state.ruleChosen.isEmpty()) {
                    Text(
                        if (state.ruleContacts == CallRules.Contacts.ALL_BUT) {
                            "Add the contacts whose calls to leave out."
                        } else {
                            "Add the contacts whose calls to record."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.ruleChosen.forEach { chosen ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CallerAvatar(caller = chosen.name, photo = chosen.photo)
                        Text(
                            chosen.name,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { haptics.tap(); vm.removeRuleContact(chosen.key) },
                            enabled = !state.busy,
                        ) { Icon(Icons.Filled.Close, contentDescription = "Remove ${chosen.name}") }
                    }
                }
                FilledTonalButton(
                    onClick = { haptics.tap(); contactPicker.launch(null) },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Add a contact") }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Other numbers", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Numbers not in your contacts, and hidden ones.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.ruleOtherNumbers,
                    onCheckedChange = { haptics.toggle(it); vm.setRuleOtherNumbers(it) },
                )
            }

            val nothing = !state.ruleOtherNumbers && when (state.ruleContacts) {
                CallRules.Contacts.NONE -> true
                CallRules.Contacts.ONLY -> state.ruleChosen.isEmpty()
                else -> false
            }
            if (nothing) {
                Text(
                    "As set, no call will be kept.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                "Who a call was with is only known once it ends, from the call log. So " +
                    "every call is recorded, and one left out here is deleted as it " +
                    "ends, before it is saved. If the call log does not say who it was, " +
                    "the recording is kept." +
                    if (state.automatic) "" else {
                        " In ask-first mode, a call you tap Record on is always kept."
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * How new recordings are named (see FileNames), with an example of each so
 * nobody has to imagine it. The Honor format only on a phone with Honor's
 * Phone app, and with the one folder that app looks in a tap away.
 */
@Composable
private fun FileNamesCard(
    state: UiState,
    vm: MainViewModel,
    allow: () -> Unit,
    chooseHonorFolder: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val formats = FileNames.Format.entries.filter {
        it != FileNames.Format.HONOR || state.honorDialer || state.fileNames == FileNames.Format.HONOR
    }
    Card {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("File names", style = MaterialTheme.typography.titleMedium)
            Choices(
                choices = formats,
                selected = state.fileNames,
                label = { format ->
                    when (format) {
                        FileNames.Format.DATE -> "Date and time"
                        FileNames.Format.DATE_AND_CALLER -> "With the caller"
                        FileNames.Format.HONOR -> "For Honor's Phone app"
                    }
                },
                enabled = !state.busy,
                onChoose = { haptics.tap(); vm.setFileNames(it) },
            )
            Text(
                when (state.fileNames) {
                    FileNames.Format.DATE -> "20261020_165705_out.ogg"
                    FileNames.Format.DATE_AND_CALLER -> "20261020_165705_out_Priya Raman.ogg"
                    FileNames.Format.HONOR -> "Priya Raman@+447700900123_20261020165705.ogg"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                when (state.fileNames) {
                    FileNames.Format.DATE ->
                        "When the call began, and which way it went."
                    FileNames.Format.DATE_AND_CALLER ->
                        "The same, then the contact's name or the number - easier to " +
                            "find in a file manager or on a computer."
                    FileNames.Format.HONOR ->
                        "The name Honor's own recorder uses. Honor's Phone app then " +
                            "shows a play button on the call in its history - as long " +
                            "as the recordings are in ${FileNames.HONOR_FOLDER}."
                } + " New recordings only: the ones you have keep their names.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.fileNames != FileNames.Format.DATE && !state.canReadCallLog) {
                Text(
                    "Needs your call log, to know who each call was with. Until then, " +
                        "recordings are named by date and time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                FilledTonalButton(
                    onClick = { haptics.tap(); allow() },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Allow") }
            }
            if (state.fileNames == FileNames.Format.HONOR && !state.inHonorFolder) {
                Text(
                    "Recordings are not going to ${FileNames.HONOR_FOLDER} yet. Choose " +
                        "it, then tap Use this folder and Allow.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                FilledTonalButton(
                    onClick = { haptics.tap(); chooseHonorFolder() },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save to ${FileNames.HONOR_FOLDER}") }
            }
        }
    }
}

/**
 * Mutually exclusive choices, as chips that wrap.
 *
 * Not segmented buttons, which were tried: five segments in a phone-width
 * card left about 60dp each, and "Never" came out as "Neve". Chips take the
 * room their words need and move to a second line when they run out of it.
 */
@Composable
private fun <T> Choices(
    choices: List<T>,
    selected: T,
    label: (T) -> String,
    enabled: Boolean,
    onChoose: (T) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        choices.forEach { value ->
            FilterChip(
                selected = value == selected,
                onClick = { if (value != selected) onChoose(value) },
                label = { Text(label(value)) },
                enabled = enabled,
                shape = CircleShape,
            )
        }
    }
}

/** Android 13 and later: ask the system to add the tile, in its own dialog. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun addTile(context: Context) {
    runCatching {
        context.getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(context, RecorderTile::class.java),
            "JemRec",
            Icon.createWithResource(context, R.drawable.ic_tile),
            context.mainExecutor,
        ) { result -> Log.i("JemRec", "tile: add request answered $result") }
    }.onFailure { Log.w("JemRec", "tile: could not ask to add it", it) }
}
