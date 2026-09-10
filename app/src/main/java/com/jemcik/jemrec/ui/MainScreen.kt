package com.jemcik.jemrec.ui

import android.app.Activity
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jemcik.jemrec.capture.CaptureDaemon
import com.jemcik.jemrec.capture.RecordingSaver
import com.jemcik.jemrec.ui.theme.JemRecColors
import kotlinx.coroutines.delay
import kotlin.system.exitProcess
import kotlinx.coroutines.flow.conflate

/**
 * Routes between setup and the running app.
 *
 * There is no "settings" concept here on purpose. Either the phone can record a
 * call or it cannot, and whichever is true is what the screen shows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current

    // Re-checked every time the screen comes forward, not once when it is
    // created. Everything shown here lives outside this process - the daemon,
    // the pairing, the recordings on disk - and the interesting moment is
    // exactly the one where the user returns from somewhere else: from Settings
    // after pairing, or from a call that just produced a recording.
    //
    // Doing this only on first composition is why a finished call showed
    // nothing until "Check" was pressed. Making the user ask is not a refresh
    // strategy.
    LifecycleResumeEffect(Unit) {
        vm.refreshSetup()
        onPauseOrDispose { }
    }

    // A recording saved while this screen is up - by the per-call service
    // seconds after a hang-up, or by the keep-alive's reconcile right after an
    // open - would otherwise wait for the next resume to appear. Measured: the
    // file was in the folder and not in the list until the app was reopened.
    // Conflated behind a short pause, so a reconcile saving several files is
    // one refresh.
    LaunchedEffect(Unit) {
        RecordingSaver.saved.conflate().collect {
            delay(250)
            vm.refresh()
        }
    }

    // The recorder coming back is the other change that happens out of sight:
    // the keep-alive revives it when Wi-Fi returns, and the header kept saying
    // "needs Wi-Fi" until the next resume. Measured, after a reboot.
    LaunchedEffect(Unit) {
        CaptureDaemon.changed.collect { vm.refresh() }
    }

    // And Wi-Fi arriving while this screen is up starts the revive from here,
    // so the header goes to "Starting..." at once rather than waiting for the
    // keep-alive job to be scheduled, run, and report back. The ViewModel
    // decides whether there is anything to revive.
    val appContext = LocalContext.current.applicationContext
    DisposableEffect(Unit) {
        val cm = appContext.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = vm.onWifiAvailable()
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        runCatching { cm?.registerNetworkCallback(request, callback) }
        onDispose { runCatching { cm?.unregisterNetworkCallback(callback) } }
    }

    // MediaStore will occasionally not take our word for it that a recording
    // is ours to delete, and hands back a request for the user to approve.
    // Launching it needs an Activity, so it lives here, above both screens -
    // deleting one recording happens on the home screen and deleting all of
    // them happens in settings, and they should not each own a copy.
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result -> vm.onDeleteConsentResult(result.resultCode == Activity.RESULT_OK) }

    LaunchedEffect(state.deleteConsent) {
        state.deleteConsent?.let {
            consentLauncher.launch(IntentSenderRequest.Builder(it).build())
        }
    }

    // Reset revokes this app's own permissions, and the system only applies
    // that once the app is out of the way. Closing is part of the operation,
    // not a courtesy.
    val activity = LocalActivity.current
    LaunchedEffect(state.resetDone) {
        if (!state.resetDone) return@LaunchedEffect
        activity?.finishAndRemoveTask()
        // Handing back a permission only takes effect when the process dies,
        // and Android decides when that is - for a cached process it can be a
        // long while. Ending it here is what makes "start fresh" mean now
        // rather than eventually, and the dialog promised the app would close.
        delay(300)
        exitProcess(0)
    }

    val ready = state.step == SetupStep.READY
    val inSettings = ready && state.screen == Screen.SETTINGS
    val selecting = ready && !inSettings && state.selecting

    // Back unwinds one layer at a time, innermost first: out of selection, then
    // out of settings, then out of the app. Selection has to come first - Back
    // is how Android users leave a selection, and dropping them to the home
    // screen instead would lose the picking they had just done.
    BackHandler(enabled = selecting || inSettings) {
        if (selecting) vm.clearSelection() else vm.showHome()
    }

    Scaffold(
        topBar = {
            // No bar during setup. There is nothing to configure yet and no
            // second screen to reach, so a gear there would be a dead end.
            if (ready) {
                // The bar and the line under it are one thing, so they live in
                // one Column. TopAppBar has no bottom border of its own.
                Column {
                    TopAppBar(
                        title = {
                            // THE STATE IS THE HEADLINE.
                            //
                            // The app's name was, and a name is the one thing
                            // on this screen the user already knows - it is
                            // written under the icon they just tapped. What
                            // they cannot know without being told is whether
                            // their next call will be recorded, so that gets
                            // the row.
                            Column {
                                Text(
                                    if (inSettings) {
                                        "Settings"
                                    } else {
                                        when {
                                            !state.enabled -> "Off"
                                            state.starting -> "Starting..."
                                            state.daemonRunning -> "Ready to record"
                                            // The recorder is down. Wi-Fi is the
                                            // one lever the user has: the app
                                            // turns Wireless debugging back on by
                                            // itself when it revives, so that
                                            // switch is never theirs to touch
                                            // here (it used to be named, and the
                                            // phone turns it off on every Wi-Fi
                                            // loss - so it was named constantly,
                                            // and wrongly). The Wi-Fi goes in the
                                            // line under this one: at title size
                                            // "Cannot record, needs Wi-Fi" wrapped.
                                            else -> "Cannot record"
                                        }
                                    },
                                    // ONE LINE, WHATEVER THE FONT SIZE. The
                                    // headline is the state and has to be read
                                    // at a glance beside the switch; a wrap
                                    // there looks like a broken layout. The
                                    // wording fits at the default size, and the
                                    // type steps down a little for anyone
                                    // running large fonts rather than wrapping
                                    // or losing letters to an ellipsis.
                                    maxLines = 1,
                                    autoSize = TextAutoSize.StepBased(
                                        minFontSize = 15.sp,
                                        maxFontSize = 22.sp,
                                    ),
                                    // A traffic light: green ready, coral cannot,
                                    // grey off/starting. Green is the play colour,
                                    // so "go" reads the same in the header as it
                                    // does on every row - and says "good to go"
                                    // where the accent blue only said "accent".
                                    color = when {
                                        inSettings -> MaterialTheme.colorScheme.onSurface
                                        !state.enabled || state.starting ->
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        state.daemonRunning -> JemRecColors.play
                                        else -> MaterialTheme.colorScheme.error
                                    },
                                )
                                // Under the ready state, HOW it will record - so
                                // the user is not left to remember whether the
                                // next call records on its own or waits for a
                                // tap. Only when it is actually ready: under
                                // "Off", "Starting" or "Cannot record" it would
                                // either be moot or contradict the line above.
                                if (!inSettings && state.enabled && state.daemonRunning) {
                                    Text(
                                        if (state.automatic) "Recording every call"
                                        else "Asks before each call",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        autoSize = SUBTITLE_AUTO_SIZE,
                                    )
                                }
                                // Under the down state, what to do about it. The
                                // app watches for Wi-Fi and revives the recorder
                                // the moment it returns - re-arming Wireless
                                // debugging itself - so this is one instruction
                                // and a wait, not a fault to act on.
                                if (!inSettings && state.enabled && !state.starting &&
                                    !state.daemonRunning
                                ) {
                                    Text(
                                        // Every fact in one short line: the cause,
                                        // that any network will do, and that there
                                        // is nothing else to do. The longer version
                                        // wrapped under the title.
                                        "Join any Wi-Fi - it resumes on its own",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        autoSize = SUBTITLE_AUTO_SIZE,
                                    )
                                }
                            }
                        },
                        navigationIcon = {
                            if (inSettings) {
                                IconButton(onClick = { haptics.tap(); vm.showHome() }) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back",
                                    )
                                }
                            }
                        },
                        actions = {
                            if (!inSettings) {
                                // Red when on, matching the record capsule in
                                // the launcher icon - so the thing that means
                                // "recording" is one colour everywhere the user
                                // meets it.
                                Switch(
                                    checked = state.enabled,
                                    onCheckedChange = { haptics.toggle(it); vm.setEnabled(it) },
                                    enabled = !state.busy,
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = JemRecColors.iconRecord,
                                        checkedBorderColor = JemRecColors.iconRecord,
                                    ),
                                )
                                IconButton(onClick = { haptics.tap(); vm.showSettings() }) {
                                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                                }
                            }
                        },
                    )
                    HeaderRule(active = state.busy)
                }
            }
        },
    ) { padding ->
        // The home screen is its own scrolling list (a LazyColumn with a pinned
        // header), so it must NOT be nested in a vertical scroll - two vertical
        // scrolls inside each other is both a crash and the wrong behaviour. It
        // takes the padding and fills the area itself. Setup and Settings are
        // short, static content, so they keep the plain page scroll.
        if (ready && !inSettings) {
            HomeScreen(state, vm, modifier = Modifier.padding(padding))
        } else {
            val scrollState = rememberScrollState()
            // A self-test's result lands in the log card at the very bottom of
            // Diagnostics - below the fold, so the user pressed the button and
            // saw nothing move. When the log changes in Settings, run the scroll
            // to the end, so the answer is on screen instead of under a thumb.
            LaunchedEffect(state.log) {
                if (inSettings && state.log.isNotEmpty()) {
                    // The log card is added/grown in this same frame and only
                    // measured after it, so the scroll range does not yet include
                    // it - reading maxValue now gives the OLD bottom, and the
                    // scroll goes nowhere. Wait two frames for layout to settle,
                    // then run to the real end.
                    withFrameNanos {}
                    withFrameNanos {}
                    scrollState.animateScrollTo(scrollState.maxValue)
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    // verticalScroll BEFORE the 16dp, so the scroll viewport
                    // reaches up to the header rule and content clips exactly
                    // there as it scrolls under it.
                    .verticalScroll(scrollState)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Only during setup, where there is no header rule to animate.
                if (state.busy && !ready) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                if (!ready) SetupScreen(state, vm) else SettingsScreen(state, vm)

                // Diagnostics always sit at the foot of Settings - no toggle to
                // reveal them. NOT during setup, which is kept to its own steps.
                if (inSettings) DiagnosticsSection(state, vm)
            }
        }
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
 * panel now answers the question that matters - is the recorder running - and
 * describes the ADB session as what it is.
 *
 * THE PORT AND CODE FIELDS ARE GONE. Typing a code into the app cannot work:
 * leaving Settings closes the pairing dialog, which is what generates the code
 * and listens for it. That is exactly why pairing moved to the notification
 * shade. A control that cannot work is worse than no control, and worse still
 * in a panel people open when something is already wrong.
 *
 * What is left is what someone would actually use: the state, the things that
 * repair it, a way to see the mid-call prompt without ringing anyone, and the
 * log.
 */
@Composable
private fun DiagnosticsSection(state: UiState, vm: MainViewModel) {
    val haptics = LocalHapticFeedback.current
    // One card, like every other Settings block: the title sits inside it and
    // the content follows, rather than a bare heading floating over three
    // separate cards - which read as a looser, different kind of thing than the
    // sections above it.
    Card {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Diagnostics", style = MaterialTheme.typography.titleMedium)

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (state.daemonRunning) "Recorder running" else "Recorder not running",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (state.daemonRunning) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                )
                Text(
                    if (state.connected) {
                        "ADB session open. Only needed to start the recorder."
                    } else {
                        "No ADB session. Normal - it is only needed to start the " +
                            "recorder, which then runs without it."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Two related actions, one language: tonal buttons sharing the row
            // at equal width, not a loud one beside a faint one.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { haptics.tap(); vm.selfTest() },
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                ) { Text("Self-test") }
                // Off when there is nothing to start: against a running recorder
                // it short-circuits on the first ping and reports success, which
                // is indistinguishable from doing nothing - because it is.
                FilledTonalButton(
                    onClick = { haptics.tap(); vm.bootstrapDaemon() },
                    enabled = !state.busy && !state.daemonRunning,
                    modifier = Modifier.weight(1f),
                ) { Text("Start recorder") }
            }

            if (state.log.isNotEmpty()) {
                // A recessed block, not a nested card: the log is output, set
                // apart by a darker ground the way a code block is, rather than
                // a second card floating inside the first.
                Text(
                    text = state.log,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerLowest,
                            MaterialTheme.shapes.small,
                        )
                        .padding(12.dp),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * The line under the control bar.
 *
 * A gradient in the launcher icon's own two colours, left to right - the blue
 * of the waveform running into the red of the record mark. Separating fixed
 * controls from scrolling content is what any divider would do; what this one
 * additionally does is carry the icon's colours into the app, so the thing on
 * the home screen and the thing you open are recognisably the same object.
 *
 * WHEN BUSY, THE LINE ITSELF MOVES.
 *
 * A separate progress bar used to appear below this rule while the app worked -
 * turning on, checking setup - which put two horizontal lines a few dp apart,
 * one of them meaningful and one of them chrome. Animating the rule instead
 * keeps it to one line: the gradient slides, so the divider that is already
 * there becomes the activity indicator rather than growing a second one beneath
 * it. The colours flow wave-into-record-into-wave (a mirrored, tiled gradient),
 * so the motion reads as the app's own rather than a generic bar.
 */
@Composable
private fun HeaderRule(active: Boolean) {
    val period = with(LocalDensity.current) { 220.dp.toPx() }

    val shift by rememberInfiniteTransition(label = "rule").animateFloat(
        initialValue = 0f,
        targetValue = period,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rule-shift",
    )

    val brush = if (active) {
        // Three stops, mirror-tiled, translated by an animated offset: the
        // gradient flows along the line without a visible seam. Mirror rather
        // than Repeated so blue never butts straight up against blue.
        Brush.linearGradient(
            colors = listOf(
                JemRecColors.iconWave, JemRecColors.iconRecord, JemRecColors.iconWave,
            ),
            start = Offset(shift, 0f),
            end = Offset(shift + period, 0f),
            tileMode = TileMode.Mirror,
        )
    } else {
        Brush.horizontalGradient(
            listOf(JemRecColors.iconWave, JemRecColors.iconRecord),
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(brush),
    )
}

/** The header's second line: bodySmall, stepping down a little before it would wrap. */
private val SUBTITLE_AUTO_SIZE = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = 12.sp)
