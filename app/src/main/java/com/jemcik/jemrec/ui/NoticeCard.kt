package com.jemcik.jemrec.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * A card above the recordings that asks for something: a title, what it is
 * about, and a button - and, where it may be put off, "Not now" or another
 * way out ([notNow]). The call-log offer and the notices - notifications
 * off, the Battery setting Restricted, the folder lost - are all this card
 * (one way to draw one thing).
 */
@Composable
internal fun NoticeCard(
    title: String,
    text: String,
    action: String,
    onAction: () -> Unit,
    onNotNow: (() -> Unit)?,
    modifier: Modifier = Modifier,
    notNow: String = "Not now",
    /** False while the screen is busy, for a way out whose action waits for
     *  nothing and is dropped meanwhile: greyed, as the busy table has it
     *  (ARCHITECTURE, A tap while the app is checking). The action itself
     *  stays live: on home, a folder picked is taken at once. */
    notNowEnabled: Boolean = true,
) {
    val haptics: HapticFeedback = LocalHapticFeedback.current
    Card(modifier.padding(horizontal = 16.dp).padding(top = 16.dp, bottom = 16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // A FlowRow, as setup's pair of buttons: at twice the text size
            // "Choose again" and "Use the standard folder" do not fit one
            // line, and in a Row the second broke mid-word into a column
            // (review, 2026-11-18).
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilledTonalButton(onClick = { haptics.tap(); onAction() }) { Text(action) }
                if (onNotNow != null) {
                    TextButton(onClick = { haptics.tap(); onNotNow() }, enabled = notNowEnabled) { Text(notNow) }
                }
            }
        }
    }
}
