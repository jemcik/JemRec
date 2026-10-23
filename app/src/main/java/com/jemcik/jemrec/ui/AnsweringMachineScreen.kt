package com.jemcik.jemrec.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jemcik.jemrec.capture.AnswerDiagnostics
import com.jemcik.jemrec.capture.AnsweringMachine
import com.jemcik.jemrec.capture.EmergencyPause
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/** Which of the answering machine's dialogs is open. Saved by name. */
private enum class AnswerDialog { DELAY, DND, LONGEST, NO_LIMIT, SIM, TEST, CARRIER, RESUME }

/**
 * THE ANSWERING MACHINE: it answers the phone calls you do not take, plays your own
 * greeting to the caller with your microphone muted, and records their
 * message. Off until switched on, and it can be switched on only once the
 * no-call checks pass and a greeting is recorded. The test call underneath is
 * how a phone goes from "possible" to "works".
 */
@Composable
fun AnsweringMachineScreen(state: UiState, vm: MainViewModel) {
    val a by vm.answer.state.collectAsState()
    val haptics = LocalHapticFeedback.current
    var dialog by rememberSaveable { mutableStateOf<AnswerDialog?>(null) }

    LaunchedEffect(Unit) { vm.answer.opened() }

    when (dialog) {
        AnswerDialog.DELAY -> ChoiceDialog(
            title = "Answer after",
            intro = "How long it rings before JemRec answers. Your carrier's own voicemail, " +
                "if you have it, must wait longer.",
            choices = AnsweringMachine.DELAYS,
            selected = a.delay,
            label = { "$it seconds" },
            onChoose = vm.answer::setDelay,
            onDismiss = { dialog = null },
        )
        AnswerDialog.DND -> ChoiceDialog(
            title = "With Do Not Disturb on",
            intro = "For callers Do Not Disturb would silence. Callers it lets through ring as usual.",
            choices = listOf(true, false),
            selected = a.dndShort,
            label = { if (it) "After 2 rings (about ${AnsweringMachine.DND_DELAY} seconds)" else "The same delay" },
            onChoose = vm.answer::setDndShort,
            onDismiss = { dialog = null },
        )
        AnswerDialog.LONGEST -> ChoiceDialog(
            title = "Longest message",
            intro = "How long a caller can speak after the beep before JemRec hangs up and " +
                "saves the message. No limit waits for the caller to hang up, or to fall silent.",
            choices = AnsweringMachine.LONGEST,
            selected = a.longest,
            label = ::longestLabel,
            // No limit is asked about first, in a dialog of its own.
            onChoose = {
                if (it == AnsweringMachine.NO_LIMIT) dialog = AnswerDialog.NO_LIMIT else vm.answer.setLongest(it)
            },
            onDismiss = { if (dialog == AnswerDialog.LONGEST) dialog = null },
        )
        AnswerDialog.NO_LIMIT -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("No limit?") },
            text = {
                Text(
                    "The message runs until the caller hangs up, or until they have been " +
                        "silent for ${AnsweringMachine.SILENCE_HANGUP_SECONDS} seconds. Until then " +
                        "your phone is in that call: you cannot make a call without ending it, " +
                        "and other calls wait. A caller somewhere noisy may never count as " +
                        "silent, and then it lasts until they hang up.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    haptics.confirm()
                    vm.answer.setLongest(AnsweringMachine.NO_LIMIT)
                    dialog = null
                }) { Text("No limit") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        AnswerDialog.SIM -> ChoiceDialog(
            title = "Answer on",
            choices = a.sims.map { it.first } + AnsweringMachine.SIM_BOTH,
            selected = when (a.sim) {
                AnsweringMachine.SIM_DEFAULT -> a.defaultSim ?: AnsweringMachine.SIM_BOTH
                else -> a.sim
            },
            label = { id -> if (id == AnsweringMachine.SIM_BOTH) "Both SIMs" else simLabel(a, id) },
            onChoose = vm.answer::setSim,
            onDismiss = { dialog = null },
        )
        AnswerDialog.TEST -> TestDialog(
            pausedUntil = a.pausedUntil,
            start = { incoming, number -> dialog = null; vm.answer.startTest(incoming, number) },
            dismiss = { dialog = null },
        )
        AnswerDialog.CARRIER -> CarrierDialog(a, vm, dismiss = { dialog = null })
        AnswerDialog.RESUME -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Answer calls again?") },
            text = {
                Text(
                    "You called an emergency number. If they call you back while you cannot " +
                        "pick up, the answering machine would answer them with your greeting, " +
                        "instead of letting your phone ring until you can." +
                        (a.pausedUntil?.let { " The pause ends by itself on ${pauseEnd(it)}." } ?: ""),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    haptics.confirm()
                    vm.answer.resumeAfterEmergency()
                    dialog = null
                }) { Text("Answer again") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Keep paused") } },
        )
        null -> Unit
    }

    a.askHeard?.let { type ->
        AlertDialog(
            onDismissRequest = vm.answer::dismissHeard,
            title = { Text("Did the caller hear your greeting?") },
            text = { Text("All of it, from the start, and no sound from around this phone.") },
            confirmButton = { TextButton(onClick = { haptics.confirm(); vm.answer.heard(type, true) }) { Text("Yes") } },
            dismissButton = { TextButton(onClick = { vm.answer.heard(type, false) }) { Text("No") } },
        )
    }

    Column {
        SettingsGroup {
            row {
                val blocked = a.blocked
                val paused = a.paused
                SwitchRow(
                    title = "Answering machine",
                    summary = when {
                        blocked != null && !a.enabled -> blocked
                        paused != null -> "Paused after an emergency call, until ${pauseEnd(paused)}"
                        a.enabled -> "Answers the phone calls you don't take, after ${a.delay} seconds"
                        else -> "Off"
                    },
                    summaryColor = if (blocked != null && a.stageOne?.possible == false || paused != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    // ANS, as on the Settings row that leads here.
                    mark = headerMark(Marked.ANSWERING, marks(state, a.enabled), recorderStatus(state)),
                    checked = a.enabled,
                    enabled = a.enabled || blocked == null,
                    onCheckedChange = vm.answer::setEnabled,
                )
            }
            // A day after a call to an emergency number it stands down, so a
            // callback rings on (EmergencyPause). Ending that early is the
            // user's to choose, warned.
            if (a.paused != null) {
                row {
                    SettingsRow(
                        title = "Answer again now",
                        summary = "It stands down for a day after you call an emergency number",
                        icon = Icons.Filled.Warning,
                        onClick = { dialog = AnswerDialog.RESUME },
                    )
                }
            }
        }

        SettingsGroup(
            "When it answers",
            note = "It answers only the callers JemRec records: Whose calls to keep, in " +
                "Settings, applies here too.",
        ) {
            row {
                SettingsRow(
                    title = "Answer after",
                    summary = "${a.delay} seconds" + (a.carrierSeconds?.takeIf { it <= a.delay }
                        ?.let { " - your carrier's voicemail answers first, at $it" } ?: ""),
                    summaryColor = if (a.carrierSeconds?.let { it <= a.delay } == true) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    icon = SettingsIcons.Timer,
                    onClick = { dialog = AnswerDialog.DELAY },
                )
            }
            row {
                SettingsRow(
                    title = "With Do Not Disturb on",
                    summary = if (a.dndShort) "After 2 rings" else "The same delay",
                    icon = Icons.Filled.Notifications,
                    onClick = { dialog = AnswerDialog.DND },
                )
            }
            if (a.sims.size >= 2) {
                row {
                    SettingsRow(
                        title = "Answer on",
                        summary = when (a.sim) {
                            AnsweringMachine.SIM_BOTH -> "Both SIMs"
                            AnsweringMachine.SIM_DEFAULT -> a.defaultSim?.let { simLabel(a, it) } ?: "Both SIMs"
                            else -> simLabel(a, a.sim)
                        },
                        icon = Icons.Filled.Call,
                        onClick = { dialog = AnswerDialog.SIM },
                    )
                }
            }
            row {
                SettingsRow(
                    title = "Longest message",
                    summary = if (a.longest == AnsweringMachine.NO_LIMIT) {
                        "No limit - it hangs up after ${AnsweringMachine.SILENCE_HANGUP_SECONDS} seconds of silence"
                    } else {
                        "${seconds(a.longest)}, then it hangs up"
                    },
                    icon = SettingsIcons.Timer,
                    onClick = { dialog = AnswerDialog.LONGEST },
                )
            }
        }

        SettingsGroup("Greeting") {
            row {
                SettingsRow(
                    title = when {
                        a.recordingTake -> "Recording your greeting"
                        a.greetingSeconds != null -> "Your greeting"
                        else -> "No greeting yet"
                    },
                    summary = when {
                        a.recordingTake -> "Speak now. Tap Stop when you are done (${com.jemcik.jemrec.capture.Greeting.MAX_SECONDS} seconds at most)."
                        a.greetingProblem != null -> a.greetingProblem
                        a.greetingSeconds != null ->
                            "${seconds((a.greetingSeconds ?: 0.0).toInt().coerceAtLeast(1))}. " +
                                if (a.playing) "Playing - tap to stop" else "Tap to play it"
                        else -> "Record what callers hear before the beep"
                    },
                    summaryColor = if (a.greetingProblem != null && !a.recordingTake) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    icon = if (a.recordingTake) SettingsIcons.Mic else Icons.Filled.PlayArrow,
                    onClick = if (a.greetingSeconds != null && !a.recordingTake) vm.answer::togglePreview else null,
                )
            }
        }
        val record = if (a.greetingSeconds == null) "Record" else "Record again"
        val labels = when {
            a.recordingTake -> listOf("Stop")
            a.greetingSeconds != null -> listOf(record, "Delete")
            else -> listOf(record)
        }
        ButtonPair(labels, Modifier.padding(top = 8.dp)) { i, modifier ->
            when (labels[i]) {
                "Stop" -> FilledTonalButton(
                    onClick = { haptics.tap(); vm.answer.stopTake() },
                    modifier = modifier,
                ) { ButtonLabel("Stop") }
                "Delete" -> FilledTonalButton(
                    onClick = { haptics.tap(); vm.answer.deleteGreeting() },
                    modifier = modifier,
                ) { ButtonLabel("Delete") }
                else -> FilledTonalButton(
                    onClick = { haptics.tap(); vm.answer.startTake() },
                    enabled = state.daemonRunning,
                    modifier = modifier,
                ) { ButtonLabel(record) }
            }
        }
        if (a.recordingTake) TakeTimer(a.takeStartedAt)

        SettingsGroup("Test on this phone") {
            row {
                val stage = a.stageOne
                SettingsRow(
                    title = "Checks",
                    summary = when {
                        a.checking -> "Checking..."
                        stage == null -> "Not run yet - tap to run"
                        stage.possible -> "Passed: this phone can play a greeting into a call. A test call tells for sure."
                        else -> stage.problem
                    },
                    summaryColor = if (stage?.possible == false) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    icon = Icons.Filled.Build,
                    onClick = vm.answer::runChecks,
                    enabled = !a.checking && state.daemonRunning,
                )
            }
            row {
                SettingsRow(
                    title = "Test call",
                    summary = when (a.test) {
                        "armed" -> if (a.testIncoming) {
                            "Waiting for someone to call, until ${clock(a.testUntil)}"
                        } else {
                            "Calling..."
                        }
                        "running" -> "Testing - your greeting is playing to the call"
                        else -> AnswerDiagnostics.summary(a.results) ?: "Not tested on this phone yet"
                    },
                    icon = Icons.Filled.Call,
                    onClick = if (a.test == null) ({ dialog = AnswerDialog.TEST }) else null,
                    enabled = a.stageOne?.possible == true && a.greetingSeconds != null && state.daemonRunning,
                )
            }
            row {
                SettingsRow(
                    title = "Carrier voicemail",
                    summary = when {
                        a.checkingCarrier -> "Asking your carrier... this takes a few seconds"
                        a.carrier == null -> "Tap to ask your carrier when its voicemail answers"
                        a.carrier?.startsWith("off") == true -> "Off: nothing answers before JemRec"
                        a.carrierSeconds != null -> "Answers unanswered calls after ${a.carrierSeconds} seconds"
                        else -> "Your carrier did not say"
                    },
                    icon = SettingsIcons.Voicemail,
                    onClick = { dialog = AnswerDialog.CARRIER },
                    enabled = state.daemonRunning,
                )
            }
            row {
                SettingsRow(
                    title = "Copy a report",
                    summary = "This phone and what the tests found, to paste into a report on GitHub",
                    icon = Icons.Filled.Share,
                    onClick = { haptics.tap(); vm.answer.copyReport() },
                )
            }
        }

        SettingsFooter(
            "When it answers, your microphone is muted and your greeting plays to the caller, " +
                "then a beep. Their message is saved like any recording, marked as a message, " +
                "and a notification tells you. While they speak you can still take the call: " +
                "Talk to them in the notification, or unmute on the call screen.",
            "It answers phone calls only - not WhatsApp, Telegram or Viber calls - and never " +
                "while you are on another call, a messenger call or in a meeting, or roaming, or " +
                "for a day after you call an emergency number. If JemRec cannot tell who is " +
                "calling, it lets the phone ring.",
            "JemRec records your greeting the way it records calls, through its recorder, so it " +
                "never needs permission to use the microphone. Experimental: tested on one phone, an Honor, on " +
                "2G calls. " +
                "The test call here is how to know it works on yours.",
        )
    }
}

@Composable
private fun TakeTimer(since: Long) {
    // The app's clock, which the screen tests fix.
    val clock = LocalNow.current
    var now by androidx.compose.runtime.remember { mutableLongStateOf(clock()) }
    LaunchedEffect(since) {
        while (true) {
            now = clock()
            delay(250)
        }
    }
    Text(
        "%d s".format(((now - since) / 1000).coerceAtLeast(0)),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(16.dp),
    )
}

@Composable
private fun TestDialog(pausedUntil: Long?, start: (Boolean, String) -> Unit, dismiss: () -> Unit) {
    val context = LocalContext.current
    var number by rememberSaveable { mutableStateOf("") }
    // Sent as a USSD code, not dialled: no call would come of it, and the
    // next call the user made would be taken as the test.
    val code = number.endsWith("#")
    // Never dialled for a test: the call it made would be an emergency call.
    val emergency = remember(number) { EmergencyPause.isEmergencyNumber(context, number) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Test call") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (pausedUntil == null) {
                        "Best: someone calls you. The next call in three minutes is answered with a " +
                            "short tone and your greeting, whoever is calling - then you are asked " +
                            "whether they heard it."
                    } else {
                        // Whoever calls in its three minutes is answered: in
                        // the day after an emergency call, that may be them.
                        "Someone calling you is a test again from ${pauseEnd(pausedUntil)}, a day " +
                            "after your emergency call: until then, the caller may be them calling back."
                    },
                )
                Text(
                    "Or JemRec calls a number that answers by itself, such as your carrier's " +
                        "information line, and measures what it can.",
                )
                OutlinedTextField(
                    value = number,
                    onValueChange = { v -> number = v.filter { it.isDigit() || it in "+*#" }.take(32) },
                    label = { Text("Number to call") },
                    singleLine = true,
                    isError = code || emergency,
                    supportingText = when {
                        code -> {
                            { Text("A code ending in # is not a call") }
                        }
                        emergency -> {
                            { Text("Never an emergency number") }
                        }
                        else -> null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { start(true, "") }, enabled = pausedUntil == null) { Text("Someone calls me") }
        },
        dismissButton = {
            TextButton(onClick = { start(false, number) }, enabled = number.length >= 3 && !code && !emergency) {
                Text("Call the number")
            }
        },
    )
}

@Composable
private fun CarrierDialog(a: AnswerUi, vm: MainViewModel, dismiss: () -> Unit) {
    val longer = a.carrierSeconds?.let { it < 30 } == true
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Carrier voicemail") },
        text = {
            Text(
                "Your carrier's own voicemail takes calls nobody answers, after its own " +
                    "timer. JemRec has to answer first. JemRec can ask your carrier for the " +
                    "timer" + if (longer) {
                        ", and make it wait the longest it allows, 30 seconds - your carrier's " +
                            "voicemail then still takes calls JemRec does not. It is your carrier's " +
                            "setting, so it stays if JemRec goes."
                    } else {
                        "."
                    },
            )
        },
        confirmButton = {
            if (longer) {
                TextButton(onClick = { dismiss(); vm.answer.carrierWaitLonger() }) { Text("Wait 30 seconds") }
            } else {
                TextButton(onClick = { dismiss(); vm.answer.checkCarrier() }) { Text("Ask the carrier") }
            }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Close") } },
    )
}

private fun simLabel(a: AnswerUi, id: String): String =
    a.sims.firstOrNull { it.first == id }?.second ?: "SIM"

private fun seconds(s: Int): String = when {
    s < 60 -> "$s seconds"
    s == 60 -> "1 minute"
    s % 60 == 0 -> "${s / 60} minutes"
    else -> "$s seconds"
}

private fun longestLabel(s: Int): String = if (s == AnsweringMachine.NO_LIMIT) "No limit" else seconds(s)

private fun clock(millis: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

/** When a pause ends, with its day: a pause is a day long, so "14:30" alone
 *  would read as today. */
internal fun pauseEnd(millis: Long): String =
    SimpleDateFormat("d MMM 'at' HH:mm", Locale.getDefault()).format(Date(millis))

/** The answering machine in a few words, for its row in Settings. Tested in
 *  AnswerSummaryTest. */
internal fun answerSummary(a: AnswerUi): String = when {
    a.paused != null -> "Paused after an emergency call"
    a.enabled -> "After ${a.delay} seconds" +
        if (a.results.isEmpty()) " - not tested on this phone yet" else ""
    a.stageOne?.possible == false -> "Not available on this phone"
    // Why it is off, where the user looks: Android's settings guide, a
    // dependent setting with "a brief explanation of why" it is unavailable.
    !a.callTracking -> "Off - needs exact call tracking"
    else -> "Off"
}

/** The icon the row and the list share. */
internal val messageIcon = SettingsIcons.Voicemail
