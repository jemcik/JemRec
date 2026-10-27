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
 */
internal fun offModeLines(mode: OffMode): List<Pair<String, String>> = when (mode) {
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
        "Back on:" to "needs Wi-Fi, about 15 s. No answering machine until then",
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
                            lines = offModeLines(mode),
                        )
                    }
                }
            }
        }
        SettingsFooter(
            *listOfNotNull(
                "Some banking apps won't open while Developer options or debugging is on. Try " +
                    "Hide Developer options first; if your bank still refuses, Turn all " +
                    "debugging off. Turning JemRec back on switches back on what it switched off.",
                // The choice applies at the next off: On puts back what the
                // last off switched off, whatever this says by then.
                "JemRec is off now, so a new choice applies the next time you turn it off."
                    .takeIf { !state.enabled },
            ).toTypedArray(),
        )
    }
}
