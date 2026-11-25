package com.jemcik.jemrec.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.jemcik.jemrec.calls.CallButton.Face
import com.jemcik.jemrec.ui.theme.JemRecColors
import com.jemcik.jemrec.ui.theme.LocalLook
import com.jemcik.jemrec.ui.theme.Look

/**
 * Ask first's button over a call (CallButtonWindow): Record, and a cross that
 * is Not now; then what became of the Record. In the look's colours, type and
 * corners - round as Memo's record control, a Deck key's, a Multitrack clip's.
 * On any call's screen, so it has a ground and an edge of its own: opaque, no
 * shadow, which the window would cut at its bounds.
 */
@Composable
internal fun CallButtonPill(face: Face, onRecord: () -> Unit, onNotNow: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val shape = when (LocalLook.current) {
        Look.MEMO -> CircleShape
        Look.DECK -> RoundedCornerShape(14.dp)
        Look.MULTITRACK -> RoundedCornerShape(6.dp)
    }
    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        when (face) {
            Face.ASK -> Row(
                Modifier.height(IntrinsicSize.Min).heightIn(min = HEIGHT),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier
                        .fillMaxHeight()
                        .heightIn(min = HEIGHT)
                        .clickable(role = Role.Button) { haptics.tap(); onRecord() }
                        .semantics(mergeDescendants = true) { contentDescription = "Record this call" }
                        .padding(start = 20.dp, end = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Dot()
                    Text("Record", style = MaterialTheme.typography.titleMedium)
                }
                VerticalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                IconButton(onClick = { haptics.tap(); onNotNow() }, modifier = Modifier.padding(horizontal = 4.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Not now")
                }
            }
            Face.STARTING, Face.RECORDING -> Row(
                Modifier
                    .heightIn(min = HEIGHT)
                    .padding(horizontal = 20.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Lit once the recorder has said it records, not before.
                if (face == Face.RECORDING) Dot()
                Text(
                    if (face == Face.RECORDING) "Recording" else "Starting",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

/** The record mark, in the icon's red. */
@Composable
private fun Dot() {
    Box(Modifier.size(14.dp).clip(CircleShape).background(JemRecColors.iconRecord))
}

/** Material's extended FAB's, and over its 48 dp target. */
private val HEIGHT = 56.dp
