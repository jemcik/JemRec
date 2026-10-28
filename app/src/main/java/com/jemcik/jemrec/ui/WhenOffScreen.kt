package com.jemcik.jemrec.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.jemcik.jemrec.capture.OffMode

/** A choice's name: the row in Settings shows it as the setting's value, the
 *  screen as the choice - one wording, so the two cannot drift. */
internal fun offModeTitle(mode: OffMode): String = when (mode) {
    OffMode.KEEP_DEBUGGING -> "Keep debugging on"
    OffMode.HIDE_DEVELOPER_OPTIONS -> "Hide Developer options"
    OffMode.ALL_DEBUGGING_OFF -> "Turn all debugging off"
}

/**
 * Each choice's two facts, worded the same way for all three so they read
 * across: what goes off with JemRec, and what turning it back on takes. The
 * user read an earlier draft three times and could not tell the second choice
 * from the third (2026-10-26) - one said "Wireless debugging goes off too",
 * the other "All of it" - and the difference is exactly these two lines.
 * [portSet]: adbd is on Start without Wi-Fi's port, so the third comes back
 * with no Wi-Fi too.
 */
internal fun offModeLines(mode: OffMode, portSet: Boolean): List<Pair<String, String>> = when (mode) {
    OffMode.KEEP_DEBUGGING -> listOf(
        "Switches off:" to "nothing else",
        "Back on:" to "instantly, anywhere",
    )
    OffMode.HIDE_DEVELOPER_OPTIONS -> listOf(
        "Switches off:" to "Developer options, Wireless debugging",
        "Back on:" to "instantly, anywhere",
    )
    OffMode.ALL_DEBUGGING_OFF -> listOf(
        "Switches off:" to "Developer options, Wireless debugging, USB debugging",
        "Back on:" to "${if (portSet) "anywhere" else "needs Wi-Fi"}, about 15 s. No answering machine until then",
    )
}

/**
 * When JemRec is off (DebugSwitchesOff): what else goes off with it, for the
 * apps - banking ones mostly - that will not open while Developer options or
 * debugging is on (issue #37). Picked in place, as Android's settings
 * guidelines allow a single choice on a screen of its own.
 */
@Composable
fun WhenOffScreen(state: UiState, vm: MainViewModel, modifier: Modifier = Modifier) {
    Column(modifier) {
        // One group to TalkBack, as the choice dialogs' radios are: it then
        // says "1 of 3" with each, so they are heard as one choice.
        Column(Modifier.selectableGroup()) {
            SettingsGroup {
                OffMode.entries.forEach { mode ->
                    row(mode) {
                        ChoiceRow(
                            title = offModeTitle(mode),
                            selected = mode == state.offMode,
                            onSelect = { vm.setOffMode(mode) },
                            lines = offModeLines(mode, state.portSet),
                        )
                    }
                }
            }
        }
        SettingsFooter(*whenOffFooter(state).toTypedArray())
    }
}

/**
 * The When JemRec is off screen's footer. The port is adbd's, and adbd runs
 * while USB debugging is on: the first two choices leave it open - Hide
 * Developer options, the one tried first for a bank, included
 * (StartWithoutWifi). Said with the switch on, and with it off while the port
 * is open all the same - a computer's `adb tcpip`, or a close that did not
 * take - where pointing at the switch would contradict the lines above,
 * which say Back on: anywhere. The choice applies at the next off: On puts
 * back what the last off switched off, whatever this says by then.
 */
internal fun whenOffFooter(state: UiState): List<String> = listOfNotNull(
    "Some banking apps won't open while Developer options or debugging is on. Try " +
        "Hide Developer options first; if your bank still refuses, Turn all " +
        "debugging off. Turning JemRec back on switches back on what it switched off.",
    when {
        state.startWithoutWifi ->
            "With Start without Wi-Fi on, debugging port 5555 stays open while " +
                "JemRec is off, unless Turn all debugging off goes with it."
        // Not "is open": with all debugging off adbd is stopped, and nothing
        // listens until On (as the Settings summary says).
        state.portSet ->
            "Debugging port 5555 stays open until the phone restarts - while JemRec " +
                "is off too, unless Turn all debugging off goes with it."
        else ->
            "To turn back on away from Wi-Fi after Turn all debugging off, switch on " +
                "Start without Wi-Fi in Settings, under Debugging."
    },
    "JemRec is off now, so a new choice applies the next time you turn it off."
        .takeIf { !state.enabled },
)
