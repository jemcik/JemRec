package com.jemcik.jemrec.ui

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import com.jemcik.jemrec.ui.theme.lookMono
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.jemcik.jemrec.ui.theme.JemRecColors
import com.jemcik.jemrec.ui.theme.LocalLookChoice

/** Which of Settings' dialogs is open. Saved by name, so a rotation keeps it. */
private enum class SettingsDialog { FOLDER, FILE_NAMES, OLD, SHORT, TRACKING_OFF }

/**
 * The things that are not the answer to "would a call be recorded right now".
 *
 * ONE SCREEN THAT CAN BE READ AT A GLANCE. It was fourteen cards, each a
 * paragraph with its control somewhere inside, about eight screens of
 * scrolling with the current values buried in the prose. Now it is Android's
 * settings list (source.android.com/docs/core/settings/settings-guidelines):
 * four groups, every row its name and what it is set to, a switch where on and
 * off is the whole question, and anything longer one tap away - a dialog for a
 * choice, a screen of its own (Whose calls to keep, Messenger calls,
 * Diagnostics) where the explanation is worth keeping, in a footer there.
 *
 * Configuration only. Deleting recordings briefly lived here and should not
 * have: settings are where you change how the app behaves, not where you act
 * on its contents. Recently deleted is reached from here, beside the
 * auto-delete rules that fill it, because it is somewhere you go now and then
 * to take something back - not part of the list you use every day.
 */
@Composable
fun SettingsScreen(state: UiState, vm: MainViewModel, modifier: Modifier = Modifier) {

    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    var dialog by rememberSaveable { mutableStateOf<SettingsDialog?>(null) }
    // Asked once: both mean resolving a dozen activities through the
    // package manager, and Settings recomposes on every state change.
    val autoStart = remember { if (AutoStart.available(context)) AutoStart.label(context) else null }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) vm.onFolderChosen(uri) }

    val callLogPermission = rememberPermissionRequest { vm.onCallLogResult() }

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

    when (dialog) {
        SettingsDialog.FOLDER -> FolderDialog(
            state,
            change = { dialog = null; folderPicker.launch(null) },
            useDefault = { dialog = null; vm.useDefaultFolder() },
            dismiss = { dialog = null },
        )
        SettingsDialog.FILE_NAMES -> FileNamesDialog(
            state, vm,
            allow = { callLogPermission.launch(CallLogLookup.PERMISSIONS) },
            chooseHonorFolder = { folderPicker.launch(FileNames.honorFolderUri) },
            dismiss = { dialog = null },
        )
        SettingsDialog.OLD -> ChoiceDialog(
            title = "Auto-delete old recordings",
            intro = KEPT_AND_TRASH,
            choices = Retention.AGE_CHOICES,
            selected = state.autoDeleteAfterDays,
            label = ::ageChoice,
            onChoose = { vm.setAutoDelete(afterDays = it) },
            onDismiss = { dialog = null },
            enabled = !state.busy,
        )
        SettingsDialog.SHORT -> ChoiceDialog(
            title = "Auto-delete short calls",
            intro = KEPT_AND_TRASH,
            choices = Retention.SHORT_CHOICES,
            selected = state.autoDeleteShorterThan,
            label = { if (it == 0) "Off" else "Shorter than $it seconds" },
            onChoose = { vm.setAutoDelete(shorterThan = it) },
            onDismiss = { dialog = null },
            enabled = !state.busy,
        )
        // Exact call tracking switched off with the answering machine on: the
        // machine goes off with it, and did without a word (the user found it,
        // 2026-10-24). Asked first - Material's dialog "to provide
        // confirmation of a choice before committing to it" - with the
        // question itself as the headline and a confirm named for what it
        // does. Cancel leaves both on.
        SettingsDialog.TRACKING_OFF -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Turn off the answering machine too?") },
            text = {
                Text(
                    "The answering machine needs exact call tracking to answer calls, so it goes " +
                        "off with it. Turning tracking back on leaves the machine off: switch it on " +
                        "again in Answering machine."
                )
            },
            confirmButton = {
                TextButton(onClick = { haptics.confirm(); dialog = null; vm.setCallTracking(false) }) { Text("Turn both off") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        null -> Unit
    }

    Column(modifier) {

        // Only while it is worth offering. Once granted there is nothing to
        // do here, and the recordings list shows the result better than a
        // settings row saying "on" ever could.
        if (!state.canReadCallLog) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Show who called", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Each recording labelled with the contact's name and photo, or " +
                            "the number, and a warning if a call ever goes by unrecorded. " +
                            "Needs your call log and contacts. Applies to recordings " +
                            "you already have.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    FilledTonalButton(
                        onClick = { haptics.tap(); callLogPermission.launch(CallLogLookup.PERMISSIONS) },
                        enabled = !state.busy,
                        modifier = Modifier.align(Alignment.End),
                    ) { Text("Allow") }
                }
            }
        }

        // The four settings the header marks carry its mark here, where their
        // icon would be: one function decides both (headerMark).
        val answer by vm.answer.state.collectAsState()
        val headerFacts = marks(state, answer.enabled)
        val status = recorderStatus(state)
        SettingsGroup("Recording") {
            row {
                SwitchRow(
                    title = "Record every call",
                    summary = if (state.automatic) {
                        "Every call, without asking"
                    } else {
                        "Asks with a notification when a call starts. The opening " +
                            "seconds are not saved."
                    },
                    mark = headerMark(Marked.MODE, headerFacts, status),
                    checked = state.automatic,
                    onCheckedChange = vm::setAutomatic,
                )
            }
            row {
                val rules = rulesSummary(state)
                SettingsRow(
                    title = "Whose calls to keep",
                    summary = rules.text,
                    summaryColor = if (rules.nothing) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    icon = Icons.Filled.Person,
                    onClick = { vm.showScreen(Screen.WHOSE_CALLS) },
                )
            }
            row {
                SettingsRow(
                    title = "Answering machine",
                    summary = answerSummary(answer),
                    mark = headerMark(Marked.ANSWERING, headerFacts, status),
                    onClick = { vm.showScreen(Screen.ANSWERING_MACHINE) },
                )
            }
            row {
                SettingsRow(
                    title = "Messenger calls",
                    summary = messengerSummary(state),
                    mark = headerMark(Marked.MESSENGERS, headerFacts, status),
                    onClick = { vm.showScreen(Screen.MESSENGER_CALLS) },
                )
            }
            // Telecom's own account of each call. Its switch is on unless turned
            // off, and off is exactly how JemRec worked before it. See CallTracking.
            row {
                SwitchRow(
                    title = "Exact call tracking",
                    summary = if (state.callTracking) {
                        "Starts each recording at the answer, and skips calls nobody answered"
                    } else {
                        "Recordings start when a call does, ringing included"
                    },
                    icon = Icons.Filled.Phone,
                    checked = state.callTracking,
                    // Off with the answering machine on turns that off too:
                    // asked first (SettingsDialog.TRACKING_OFF).
                    onCheckedChange = { on ->
                        if (!on && answer.enabled) dialog = SettingsDialog.TRACKING_OFF else vm.setCallTracking(on)
                    },
                )
            }
        }

        SettingsGroup("Look") {
            row {
                SettingsRow(
                    title = "Theme",
                    summary = LocalLookChoice.current.let { "${lookName(it.look)} · ${lightDarkSummary(it.lightDark)}" },
                    icon = SettingsIcons.Palette,
                    onClick = { vm.showScreen(Screen.THEME) },
                )
            }
        }

        SettingsGroup("Saving") {
            row {
                SettingsRow(
                    title = "Saving to",
                    summary = state.folder,
                    icon = SettingsIcons.Folder,
                    onClick = { dialog = SettingsDialog.FOLDER },
                )
            }
            row {
                val problem = fileNamesProblem(state)
                SettingsRow(
                    title = "File names",
                    summary = problem ?: fileNamesLabel(state.fileNames),
                    summaryColor = if (problem != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    icon = Icons.Filled.Edit,
                    onClick = { dialog = SettingsDialog.FILE_NAMES },
                )
            }
            // Both rules off until chosen. Nothing they remove is gone: it waits
            // in Recently deleted, said in their dialogs so nobody picks a rule
            // fearing it.
            row {
                SettingsRow(
                    title = "Auto-delete old recordings",
                    summary = if (state.autoDeleteAfterDays == 0) {
                        "Never"
                    } else {
                        "Older than ${ageChoice(state.autoDeleteAfterDays)}"
                    },
                    mark = headerMark(Marked.AUTO_DELETE, headerFacts, status),
                    onClick = { dialog = SettingsDialog.OLD },
                )
            }
            row {
                SettingsRow(
                    title = "Auto-delete short calls",
                    summary = if (state.autoDeleteShorterThan == 0) {
                        "Off"
                    } else {
                        "Shorter than ${state.autoDeleteShorterThan} seconds"
                    },
                    icon = SettingsIcons.Timer,
                    onClick = { dialog = SettingsDialog.SHORT },
                )
            }
            row {
                SettingsRow(
                    title = "Recently deleted",
                    summary = when (val count = state.trashed.size) {
                        0 -> "Empty"
                        1 -> "1 recording, restorable for ${Trash.DAYS} days"
                        else -> "$count recordings, restorable for ${Trash.DAYS} days"
                    },
                    icon = Icons.Filled.Delete,
                    onClick = vm::showTrash,
                )
            }
        }

        SettingsGroup("Notifications and shortcuts") {
            // The notification that follows each call. Its switch is only about
            // the everyday one: a recording that came out silent is reported
            // either way, because that is the news nobody should miss.
            row {
                SwitchRow(
                    title = "Show each call when it ends",
                    summary = if (state.afterCallNotice) {
                        "A notification with Play, Share and Delete"
                    } else {
                        "Only when a recording comes out silent"
                    },
                    icon = Icons.Filled.Notifications,
                    checked = state.afterCallNotice,
                    onCheckedChange = vm::setAfterCallNotice,
                )
            }
            // The tile is the same switch as the one in the header. Android 13
            // and later can add it for the user; before that, all this row can
            // do is say where it is. Once it is in the panel the row says so -
            // it used to go on offering to add it, and a tap then met nothing,
            // because Android answers a tile already added without a word. The
            // row stays tappable: if the tile was taken out since, Android asks
            // again; if not, its answer is said back.
            row {
                SettingsRow(
                    title = "Quick Settings tile",
                    summary = when {
                        state.tileAdded -> "In your Quick Settings panel"
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                            "Turn recording on and off from the panel. Tap to add it."
                        else -> "Edit the Quick Settings panel and drag JemRec into place"
                    },
                    icon = SettingsIcons.Tile,
                    onClick = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        { addTile(context, vm::onTileAddAnswer) }
                    } else {
                        null
                    },
                )
            }
        }

        // Debugging, and what JemRec does with it: Android's reminder that it is
        // on, and what else goes off when JemRec does (issue #37).
        SettingsGroup("Debugging") {
            row {
                SettingsRow(
                    title = "When JemRec is off",
                    summary = offModeTitle(state.offMode),
                    icon = SettingsIcons.Power,
                    onClick = { vm.showScreen(Screen.WHEN_OFF) },
                )
            }
            // Setup's debugging question, answerable again. Debugging itself is
            // not a choice while JemRec is on - the recorder needs it - so the
            // summary says what stays on whichever way the switch is set, and
            // the switch is only about Android's reminder of it. See
            // DebugNotificationCleaner.
            row {
                SwitchRow(
                    title = "Hide the debugging notification",
                    summary = if (state.debugNotificationProblem) {
                        "Could not turn this on: it needs Wi-Fi. Try again on Wi-Fi."
                    } else {
                        "Debugging stays on for recording, so a paired computer can " +
                            "still connect on Wi-Fi"
                    },
                    summaryColor = if (state.debugNotificationProblem) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    icon = SettingsIcons.Hidden,
                    checked = state.debugNotificationHidden,
                    onCheckedChange = vm::setHideDebugNotification,
                )
            }
        }

        SettingsGroup("Troubleshooting") {
            // ONLY WHERE THERE IS SOMETHING TO OPEN. On a phone with no launch
            // manager this row would be an offer to fix something that is not
            // broken, which is worse than saying nothing - so AutoStart answers
            // whether the screen exists before the row is drawn at all.
            if (autoStart != null) {
                row {
                    SettingsRow(
                        title = "Survive a restart",
                        summary = "Allow JemRec in $autoStart, so it can " +
                            "start again by itself after a reboot",
                        icon = Icons.Filled.Refresh,
                        onClick = { AutoStart.open(context) },
                    )
                }
            }
            row {
                SettingsRow(
                    title = "Diagnostics",
                    summary = if (state.daemonRunning) "Recorder running" else "Recorder not running",
                    summaryColor = if (state.daemonRunning) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    icon = Icons.Filled.Build,
                    onClick = { vm.showScreen(Screen.DIAGNOSTICS) },
                )
            }
            // In the destructive colour, so a glance tells it apart, and it
            // still asks before doing anything.
            row {
                SettingsRow(
                    title = "Start fresh",
                    summary = "Set JemRec up again from the first step. Your " +
                        "recordings are kept.",
                    titleColor = MaterialTheme.colorScheme.error,
                    iconTint = MaterialTheme.colorScheme.error,
                    icon = SettingsIcons.Restore,
                    onClick = vm::askReset,
                    enabled = !state.busy,
                )
            }
        }
    }
}

/** What auto-delete promises, said wherever one of its rules is chosen. */
private const val KEPT_AND_TRASH = "Recordings you mark to keep are never removed. " +
    "Anything removed waits in Recently deleted for ${Trash.DAYS} days first."

/** An age to choose, in the header's words for it (ageWords) - or Never. */
private fun ageChoice(days: Int): String = if (days == 0) "Never" else ageWords(days)

/** A format's name - the same words in the dialog and on the row, so the row
 *  shows the option that was picked. */
private fun fileNamesLabel(format: FileNames.Format): String = when (format) {
    FileNames.Format.DATE -> "Date and time"
    FileNames.Format.DATE_AND_CALLER -> "With the caller"
    FileNames.Format.HONOR -> "For Honor's Phone app"
}

/** What stops the chosen format from working yet, or null. */
internal fun fileNamesProblem(state: UiState): String? = when {
    state.fileNames != FileNames.Format.DATE && !state.canReadCallLog ->
        "Needs your call log. Named by date and time until then."
    state.fileNames == FileNames.Format.HONOR && !state.inHonorFolder ->
        "Not saving to ${FileNames.HONOR_FOLDER} yet"
    else -> null
}

/** The rules in a few words, for the row, or as a sentence for the top of
 *  their screen; [nothing] when no call would be kept. Tested, with the others
 *  below, in SettingsSummaryTest. */
internal class RulesSummary(val text: String, val nothing: Boolean = false)

/**
 * What the rules come to, decided once for both the row and the sentence, so
 * the two cannot disagree - they were two copies of this, and a fix to one
 * (all but nobody read "except calls with 0 contacts") had to be made twice.
 */
private enum class RulesCase { EVERY, CONTACTS, ALL_BUT, CONTACTS_BUT, STRANGERS, SOME_AND_STRANGERS, SOME, NOTHING }

private fun rulesCase(state: UiState): RulesCase {
    val n = state.ruleChosen.size
    val other = state.ruleOtherNumbers
    return when (state.ruleContacts) {
        // All but nobody - until someone is added - is all, and is said so.
        CallRules.Contacts.ALL -> if (other) RulesCase.EVERY else RulesCase.CONTACTS
        CallRules.Contacts.ALL_BUT -> when {
            n == 0 -> if (other) RulesCase.EVERY else RulesCase.CONTACTS
            other -> RulesCase.ALL_BUT
            else -> RulesCase.CONTACTS_BUT
        }
        CallRules.Contacts.ONLY -> when {
            n == 0 -> if (other) RulesCase.STRANGERS else RulesCase.NOTHING
            other -> RulesCase.SOME_AND_STRANGERS
            else -> RulesCase.SOME
        }
        CallRules.Contacts.NONE -> if (other) RulesCase.STRANGERS else RulesCase.NOTHING
    }
}

private fun people(state: UiState): String =
    state.ruleChosen.size.let { if (it == 1) "1 contact" else "$it contacts" }

internal fun rulesSummary(state: UiState): RulesSummary {
    if (!state.canReadCallLog || !state.canReadContacts) return RulesSummary("Every call")
    val people = people(state)
    return when (rulesCase(state)) {
        RulesCase.EVERY -> RulesSummary("Every call")
        RulesCase.CONTACTS -> RulesSummary("Your contacts, not other numbers")
        RulesCase.ALL_BUT -> RulesSummary("Everyone but $people")
        RulesCase.CONTACTS_BUT -> RulesSummary("Your contacts but $people, not other numbers")
        RulesCase.STRANGERS -> RulesSummary("Only numbers not in your contacts")
        RulesCase.SOME_AND_STRANGERS -> RulesSummary("$people, and numbers not in your contacts")
        RulesCase.SOME -> RulesSummary("Only $people")
        RulesCase.NOTHING -> RulesSummary("No call will be kept", nothing = true)
    }
}

/** The rules as a sentence, at the top of their screen. */
internal fun rulesSentence(state: UiState): RulesSummary {
    val people = people(state)
    return when (rulesCase(state)) {
        RulesCase.EVERY -> RulesSummary("Every call is kept.")
        RulesCase.CONTACTS -> RulesSummary("Calls with your contacts are kept. Other numbers are not.")
        RulesCase.ALL_BUT -> RulesSummary("Every call is kept, except calls with $people.")
        RulesCase.CONTACTS_BUT ->
            RulesSummary("Calls with your contacts are kept, except $people. Other numbers are not.")
        RulesCase.STRANGERS -> RulesSummary("Only calls with numbers not in your contacts are kept.")
        RulesCase.SOME_AND_STRANGERS ->
            RulesSummary("Calls with $people, and with numbers not in your contacts, are kept.")
        RulesCase.SOME -> RulesSummary("Only calls with $people are kept.")
        RulesCase.NOTHING -> RulesSummary("As set, no call will be kept.", nothing = true)
    }
}

internal fun messengerSummary(state: UiState): String = when {
    !state.voipSupported -> "Needs Android 14 or later"
    state.voipInstalled.isEmpty() -> "None of the supported apps is installed"
    else -> state.voipInstalled.filter { it.pkg in state.voipEnabled }
        .joinToString(", ") { it.label }
        .ifEmpty { "Off" }
}

/** Where recordings go, and the way to change it. */
@Composable
private fun FolderDialog(state: UiState, change: () -> Unit, useDefault: () -> Unit, dismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Saving to") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = change, enabled = !state.busy) { Text("Change") }
        },
        dismissButton = {
            Row {
                if (!state.usingDefaultFolder) {
                    TextButton(onClick = useDefault, enabled = !state.busy) { Text("Use default") }
                }
                TextButton(onClick = dismiss) { Text("Cancel") }
            }
        },
    )
}

/**
 * How new recordings are named (see FileNames), with an example under each so
 * nobody has to imagine it. The Honor format only on a phone with Honor's
 * Phone app, and with the one folder that app looks in a tap away.
 *
 * It stays open after a choice: what the choice needs - the call log, Honor's
 * folder - appears under it, with the button that gives it.
 */
@Composable
private fun FileNamesDialog(
    state: UiState,
    vm: MainViewModel,
    allow: () -> Unit,
    chooseHonorFolder: () -> Unit,
    dismiss: () -> Unit,
) {
    val formats = FileNames.Format.entries.filter {
        it != FileNames.Format.HONOR || state.honorDialer || state.fileNames == FileNames.Format.HONOR
    }
    val needsCallLog = state.fileNames != FileNames.Format.DATE && !state.canReadCallLog
    val needsHonorFolder = state.fileNames == FileNames.Format.HONOR && !state.inHonorFolder
    ChoiceDialog(
        title = "File names",
        choices = formats,
        selected = state.fileNames,
        label = ::fileNamesLabel,
        detail = { format ->
            when (format) {
                FileNames.Format.DATE -> "20261020_165705_out.ogg"
                FileNames.Format.DATE_AND_CALLER -> "20261020_165705_out_Priya Raman.ogg"
                FileNames.Format.HONOR -> "Priya Raman@+447700900123_20261020165705.ogg"
            }
        },
        onChoose = vm::setFileNames,
        onDismiss = dismiss,
        closeOnChoose = false,
        enabled = !state.busy,
        footer = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when (state.fileNames) {
                        FileNames.Format.DATE ->
                            "When the call began, and which way it went."
                        FileNames.Format.DATE_AND_CALLER ->
                            "The same, then the contact's name or the number - easier " +
                                "to find in a file manager or on a computer."
                        FileNames.Format.HONOR ->
                            "The name Honor's own recorder uses. Honor's Phone app then " +
                                "shows a play button on the call in its history - as long " +
                                "as the recordings are in ${FileNames.HONOR_FOLDER}."
                    } + " New recordings only: the ones you have keep their names.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (needsCallLog) {
                    Text(
                        "Needs your call log, to know who each call was with. Until " +
                            "then, recordings are named by date and time.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (needsHonorFolder) {
                    Text(
                        "Recordings are not going to ${FileNames.HONOR_FOLDER} yet. " +
                            "Choose it, then tap Use this folder and Allow.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        action = when {
            needsCallLog -> {
                { TextButton(onClick = allow, enabled = !state.busy) { Text("Allow") } }
            }
            needsHonorFolder -> {
                { TextButton(onClick = chooseHonorFolder, enabled = !state.busy) { Text("Choose the folder") } }
            }
            else -> null
        },
    )
}

/**
 * Whose calls to keep (see CallRules): which contacts, and other numbers.
 *
 * Both questions need to know who a call was with, which only the call log
 * and the contacts can say - so without them the screen says what it would do
 * and offers the permission, rather than showing switches that cannot work.
 */
@Composable
fun WhoseCallsScreen(state: UiState, vm: MainViewModel, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val callLogPermission = rememberPermissionRequest { vm.onCallLogResult() }
    val contactPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickContact()
    ) { uri -> if (uri != null) vm.addRuleContact(uri) }

    Column(modifier) {
        if (!state.canReadCallLog || !state.canReadContacts) {
            Text(
                "Every call is kept. To leave some out - keep only your contacts' " +
                    "calls, say, or everyone's but a few people's - JemRec needs to read " +
                    "your call log and contacts, to tell who a call was with.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            FilledTonalButton(
                onClick = { haptics.tap(); callLogPermission.launch(CallLogLookup.PERMISSIONS) },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text("Allow") }
            return@Column
        }

        val sentence = rulesSentence(state)
        Text(
            sentence.text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (sentence.nothing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        SettingsHeading("Your contacts", top = 8.dp)
        ConnectedChoices(
            choices = CallRules.Contacts.entries,
            selected = state.ruleContacts,
            label = { contacts ->
                when (contacts) {
                    CallRules.Contacts.ALL -> "All"
                    CallRules.Contacts.ALL_BUT -> "All but"
                    CallRules.Contacts.ONLY -> "Only"
                    CallRules.Contacts.NONE -> "None"
                }
            },
            enabled = !state.busy,
            onChoose = vm::setRuleContacts,
        )

        if (state.ruleContacts == CallRules.Contacts.ALL_BUT || state.ruleContacts == CallRules.Contacts.ONLY) {
            val leaveOut = state.ruleContacts == CallRules.Contacts.ALL_BUT
            Text(
                when {
                    state.ruleChosen.isEmpty() && leaveOut -> "Add the contacts whose calls to leave out."
                    state.ruleChosen.isEmpty() -> "Add the contacts whose calls to keep."
                    leaveOut -> "Calls with these contacts are not kept"
                    else -> "Calls with these contacts are kept"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            )
            SettingsGroup {
                state.ruleChosen.forEach { chosen ->
                    row(key = chosen.key) {
                        SettingsRow(
                            title = chosen.name,
                            leading = { CallerAvatar(caller = chosen.name, photo = chosen.photo) },
                            trailing = {
                                IconButton(
                                    onClick = { haptics.tap(); vm.removeRuleContact(chosen.key) },
                                    enabled = !state.busy,
                                ) {
                                    Icon(Icons.Filled.Close, contentDescription = "Remove ${chosen.name}")
                                }
                            },
                        )
                    }
                }
                row {
                    SettingsRow(
                        title = "Add a contact",
                        titleColor = MaterialTheme.colorScheme.primary,
                        iconTint = MaterialTheme.colorScheme.primary,
                        icon = Icons.Filled.Person,
                        onClick = { contactPicker.launch(null) },
                        enabled = !state.busy,
                    )
                }
            }
        }

        SettingsGroup("Everyone else") {
            row {
                SwitchRow(
                    title = "Numbers not in your contacts",
                    summary = "And hidden numbers",
                    checked = state.ruleOtherNumbers,
                    onCheckedChange = vm::setRuleOtherNumbers,
                )
            }
        }

        SettingsFooter(
            "Who a call was with is only known once it ends, from the call log. So " +
                "every call is recorded, and one left out here is deleted as it " +
                "ends, before it is saved. If the call log does not say who it was, " +
                "the recording is kept." +
                if (state.automatic) "" else {
                    " In ask-first mode, a call you tap Record on is always kept."
                },
        )
    }
}

/**
 * Messenger calls (see VoipApps): one switch per supported app that is
 * installed, all off until turned on, and caller names from their call
 * notifications - which needs notification access, said plainly.
 */
@Composable
fun MessengerCallsScreen(state: UiState, vm: MainViewModel, modifier: Modifier = Modifier) {
    Column(modifier) {
        if (!state.voipSupported) {
            Text(
                "WhatsApp, Telegram, Signal, Viber and Google Meet calls cannot be recorded " +
                    "on this phone: its Android does not let the recorder copy another app's " +
                    "call audio. Android allows it from version 14.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            return@Column
        }
        if (state.voipInstalled.isEmpty()) {
            Text(
                "None of WhatsApp, Telegram, Signal, Viber or Google Meet is on this phone.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            return@Column
        }

        SettingsGroup("Record calls in") {
            state.voipInstalled.forEach { app ->
                row {
                    SwitchRow(
                        title = app.label,
                        checked = app.pkg in state.voipEnabled,
                        onCheckedChange = { vm.setVoipApp(app.pkg, it) },
                        leading = { AppIcon(app.pkg) },
                    )
                }
            }
        }

        if (state.voipEnabled.isNotEmpty()) {
            SettingsGroup("Caller names") {
                row {
                    SwitchRow(
                        title = "Show who called",
                        summary = if (state.voipNamesProblem) {
                            "Could not turn this on: it needs Wi-Fi. Try again on Wi-Fi."
                        } else {
                            "From each app's call notification"
                        },
                        summaryColor = if (state.voipNamesProblem) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        checked = state.voipNames,
                        enabled = !state.busy,
                        onCheckedChange = vm::setVoipNames,
                    )
                }
            }
        }

        SettingsFooter(
            "Both sides are recorded, the way phone calls are. Each app is off until " +
                "you turn it on. An app can refuse to let its calls be recorded, and " +
                "Android honours that. Their recordings are in Recordings, marked with " +
                "the app's icon; Whose calls to keep does not apply to them - the switch for " +
                "each app is the rule.",
            "These are not phone calls, so JemRec does not look them up in the call " +
                "log; only the app's own call notification says who it was. Reading it " +
                "takes notification access, which Android grants to all notifications - " +
                "JemRec keeps just the caller's name, from these apps' call notifications.",
        )
    }
}

/**
 * The installed app's own icon, so each switch is recognisable at a glance.
 * Falls back to a blank tile of the same size if the icon cannot be read.
 */
@Composable
private fun AppIcon(pkg: String) {
    val bitmap = rememberAppIcon(listOf(pkg), 40.dp)
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = Modifier.size(40.dp))
    } else {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        )
    }
}

/**
 * What a bug report needs, and nothing that only looks like it.
 *
 * This was the whole app while the transport was being proven, and it kept the
 * shape of that: two text fields for a pairing port and code, a Disconnect
 * button, and a line reading "ADB not connected" above all of it.
 *
 * Every one of those was misleading by the time anyone else saw it.
 *
 * "ADB NOT CONNECTED" was the worst, because it is the NORMAL state and it read
 * as a fault. ADB is a bootstrap here, not a transport: it exists to start the
 * shell-side daemon, and once that daemon is up it serves loopback and needs no
 * adbd, no mDNS and no Wi-Fi. A phone recording calls perfectly well on mobile
 * data has no ADB session and should not be told it is missing one. So the
 * screen answers the question that matters - is the recorder running - and
 * describes the ADB session as what it is.
 *
 * THE PORT AND CODE FIELDS ARE GONE. Typing a code into the app cannot work:
 * leaving Settings closes the pairing dialog, which is what generates the code
 * and listens for it. That is exactly why pairing moved to the notification
 * shade. A control that cannot work is worse than no control, and worse still
 * on a screen people open when something is already wrong.
 *
 * What is left is what someone would actually use: the state, the things that
 * test and repair it, and the log. Its own screen now, reached from Settings'
 * Troubleshooting group, where it used to sit at the foot of the one long page.
 */
@Composable
fun DiagnosticsScreen(state: UiState, vm: MainViewModel, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsGroup {
            row {
                SettingsRow(
                    title = if (state.daemonRunning) "Recorder running" else "Recorder not running",
                    titleColor = if (state.daemonRunning) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    summary = if (state.connected) {
                        "ADB session open. Only needed to start the recorder."
                    } else {
                        "No ADB session. Normal - it is only needed to start the " +
                            "recorder, which then runs without it."
                    },
                    leading = {
                        StatusDot(if (state.daemonRunning) JemRecColors.play else MaterialTheme.colorScheme.error)
                    },
                )
            }
        }

        // Two related actions, one language: tonal buttons sharing the row at
        // equal width, not a loud one beside a faint one.
        ButtonPair(listOf("Self-test", "Start recorder")) { i, modifier ->
            if (i == 0) {
                FilledTonalButton(
                    onClick = { haptics.tap(); vm.selfTest() },
                    enabled = !state.busy,
                    modifier = modifier,
                ) { ButtonLabel("Self-test") }
            } else {
                // Off when there is nothing to start: against a running
                // recorder it short-circuits on the first ping and reports
                // success, which is indistinguishable from doing nothing -
                // because it is.
                FilledTonalButton(
                    onClick = { haptics.tap(); vm.bootstrapDaemon() },
                    enabled = !state.busy && !state.daemonRunning,
                    modifier = modifier,
                ) { ButtonLabel("Start recorder") }
            }
        }

        // The answering machine's report row, for the last call: on when the
        // recorder is not, as that one is, and saying so in the report.
        SettingsGroup {
            row {
                SettingsRow(
                    title = "Copy a report",
                    summary = "This phone and how its last call was recorded, to paste into a report " +
                        "on GitHub. No numbers or names in it",
                    icon = Icons.Filled.Share,
                    onClick = { haptics.tap(); vm.copyCallReport() },
                )
            }
        }

        if (state.log.isNotEmpty()) {
            // A block of its own: the log is output, set apart on the rows'
            // tone the way a code block is. Not the lowest container it wore
            // inside the old card: on the bare screen that is white on white.
            Text(
                text = state.log,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        RoundedCornerShape(20.dp),
                    )
                    .padding(12.dp),
                fontFamily = lookMono(),
                style = MaterialTheme.typography.bodySmall,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

/** Android 13 and later: ask the system to add the tile, in its own dialog,
 *  and hand its answer to [answered]. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun addTile(context: Context, answered: (Int) -> Unit) {
    runCatching {
        context.getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(context, RecorderTile::class.java),
            "JemRec",
            Icon.createWithResource(context, R.drawable.ic_tile),
            context.mainExecutor,
        ) { result ->
            Log.i("JemRec", "tile: add request answered $result")
            answered(result)
        }
    }.onFailure { Log.w("JemRec", "tile: could not ask to add it", it) }
}
