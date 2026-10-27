package com.jemcik.jemrec.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * The one question turning JemRec off can ask: with When JemRec is off set to
 * Turn all debugging off, and no Wi-Fi to come back on over (OffMode.asksFirst).
 * Every other off is instant both ways and asks nothing - Material 3 keeps
 * dialogs for the decisions that need one (m3.material.io, Dialogs).
 *
 * The same composable for the header's button and for the Quick Settings tile
 * (TurnOffQuestionActivity), so the two cannot word it differently.
 */
@Composable
internal fun TurnOffAwayFromWifiDialog(onTurnOff: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Turn off away from Wi-Fi?") },
        text = {
            Text(
                "JemRec can only turn back on over Wi-Fi. Until you join one, calls aren't " +
                    "recorded and the answering machine is off.",
            )
        },
        confirmButton = { TextButton(onClick = onTurnOff) { Text("Turn off") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
