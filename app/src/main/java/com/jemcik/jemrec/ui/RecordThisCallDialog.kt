package com.jemcik.jemrec.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Ask first's question as a window (RecordPromptActivity): a dialog, over the
 * call's screen where that is what is in front. A tap outside it, or Back,
 * closes it ([onClose]) and answers nothing - the question still waits in the
 * shade - so whatever it stands over is one tap away, hanging up among it.
 */
@Composable
internal fun RecordThisCallDialog(call: String, onRecord: () -> Unit, onNotNow: () -> Unit, onClose: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Record this call?") },
        text = { Text(call) },
        confirmButton = { TextButton(onClick = { haptics.tap(); onRecord() }) { Text("Record") } },
        dismissButton = { TextButton(onClick = { haptics.tap(); onNotNow() }) { Text("Not now") } },
    )
}
