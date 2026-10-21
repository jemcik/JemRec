package com.jemcik.jemrec.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The parts Settings is built from: a titled group of rows, the rows
 * themselves, a footer, a dialog for picking one of a few, and a row of
 * connected buttons for the same job inline.
 *
 * WHY THESE SHAPES. Android's settings guidelines
 * (source.android.com/docs/core/settings/settings-guidelines) ask for a screen
 * that can be read at a glance - each setting's current value under its title,
 * explanations in a footer, single choices in a dialog - and Material 3's lists
 * guidance groups related rows with small gaps rather than dividers. Settings
 * was fourteen cards of paragraphs with the values buried in them; these are
 * what it is made of now.
 */

/** The corner of a group's outer edge, and of the edges between its rows. */
private val OUTER = 20.dp
private val INNER = 4.dp

/** One row's shape, from where it sits in its group: the group is one rounded
 *  block, split by gaps, so only its first and last rows round off. */
internal fun groupShape(index: Int, count: Int): Shape = RoundedCornerShape(
    topStart = if (index == 0) OUTER else INNER,
    topEnd = if (index == 0) OUTER else INNER,
    bottomStart = if (index == count - 1) OUTER else INNER,
    bottomEnd = if (index == count - 1) OUTER else INNER,
)

/** Collects a group's rows, so each can be drawn knowing where it sits. */
internal class SettingsGroupScope {
    val rows = mutableListOf<@Composable (Shape) -> Unit>()
    fun row(content: @Composable (shape: Shape) -> Unit) {
        rows += content
    }
}

/** A heading in the theme's accent, as Android's own Settings heads a group. */
@Composable
internal fun SettingsHeading(text: String, top: Dp = 24.dp) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = top, bottom = 8.dp),
    )
}

/** Related rows as one block, under an optional heading. */
@Composable
internal fun SettingsGroup(title: String? = null, build: SettingsGroupScope.() -> Unit) {
    val rows = SettingsGroupScope().apply(build).rows
    if (rows.isEmpty()) return
    Column {
        if (title != null) SettingsHeading(title)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            rows.forEachIndexed { index, row -> row(groupShape(index, rows.size)) }
        }
    }
}

/**
 * One setting: an icon, its name, and what it is set to now.
 *
 * Tappable when [onClick] is given - it then opens what changes it - and with
 * whatever [trailing] holds at the end.
 */
@Composable
internal fun SettingsRow(
    title: String,
    shape: Shape,
    summary: String? = null,
    icon: ImageVector? = null,
    summaryColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    rowModifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onClick != null) {
                        Modifier.clickable(onClickLabel = onClickLabel) { haptics.tap(); onClick() }
                    } else {
                        Modifier
                    },
                )
                .then(rowModifier)
                .heightIn(min = if (summary != null) 72.dp else 56.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (leading != null) {
                leading()
            } else if (icon != null) {
                Icon(icon, contentDescription = null, tint = iconTint)
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
                if (summary != null) {
                    Text(summary, style = MaterialTheme.typography.bodyMedium, color = summaryColor)
                }
            }
            trailing?.invoke()
        }
    }
}

/**
 * A setting that is on or off. The whole row is the switch - a tap anywhere
 * flips it, and a screen reader hears one switch - as Material asks of a list
 * item with a trailing switch.
 */
@Composable
internal fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    shape: Shape,
    summary: String? = null,
    icon: ImageVector? = null,
    summaryColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    SettingsRow(
        title = title,
        shape = shape,
        summary = summary,
        icon = icon,
        summaryColor = summaryColor,
        leading = leading,
        rowModifier = Modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
        ) { haptics.toggle(it); onCheckedChange(it) },
        trailing = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
    )
}

/**
 * The explanation at the foot of a screen, under a rule - where Android's
 * settings guidelines put text that needs more than a summary line.
 */
@Composable
internal fun SettingsFooter(vararg paragraphs: String) {
    Column(
        modifier = Modifier.padding(top = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HorizontalDivider()
        Icon(
            Icons.Filled.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        paragraphs.forEach {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

/**
 * Pick one of a few, in a dialog - the single-choice pattern Android's
 * settings guidelines name, rather than a row of chips that wraps.
 *
 * Choosing applies at once. With [closeOnChoose] the dialog then goes, as a
 * list preference does; without it, it stays, for a choice whose
 * consequences ([footer]) are worth seeing before leaving.
 */
@Composable
internal fun <T> ChoiceDialog(
    title: String,
    choices: List<T>,
    selected: T,
    label: (T) -> String,
    onChoose: (T) -> Unit,
    onDismiss: () -> Unit,
    intro: String? = null,
    detail: ((T) -> String)? = null,
    closeOnChoose: Boolean = true,
    enabled: Boolean = true,
    footer: (@Composable () -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (intro != null) Text(intro, style = MaterialTheme.typography.bodyMedium)
                Column(Modifier.selectableGroup()) {
                    choices.forEach { value ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .selectable(
                                    selected = value == selected,
                                    enabled = enabled,
                                    role = Role.RadioButton,
                                ) {
                                    haptics.tap()
                                    if (value != selected) onChoose(value)
                                    if (closeOnChoose) onDismiss()
                                }
                                .heightIn(min = 48.dp)
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            RadioButton(selected = value == selected, onClick = null, enabled = enabled)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    label(value),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                detail?.invoke(value)?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                footer?.invoke()
            }
        },
        confirmButton = action ?: {
            TextButton(onClick = onDismiss) { Text(if (closeOnChoose) "Cancel" else "Done") }
        },
        dismissButton = if (action != null) {
            { TextButton(onClick = onDismiss) { Text(if (closeOnChoose) "Cancel" else "Done") } }
        } else {
            null
        },
    )
}

/**
 * Pick one of two to five, inline: Material 3's connected button group, which
 * replaced the segmented button.
 *
 * It stays on one line and spans its container (m3.material.io, Button
 * groups). Chips were here before and wrapped, so the last choice sat alone on
 * a second row. The selected button turns fully round and filled, the others
 * keep their inner corners square-ish - shape and colour both say which.
 */
@Composable
internal fun <T> ConnectedChoices(
    choices: List<T>,
    selected: T,
    label: (T) -> String,
    onChoose: (T) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        choices.forEachIndexed { index, value ->
            val isSelected = value == selected
            val start by animateDpAsState(if (isSelected || index == 0) 24.dp else 8.dp, label = "start")
            val end by animateDpAsState(if (isSelected || index == choices.lastIndex) 24.dp else 8.dp, label = "end")
            val container = if (isSelected) {
                MaterialTheme.colorScheme.secondary
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            }
            val content = if (isSelected) {
                MaterialTheme.colorScheme.onSecondary
            } else {
                MaterialTheme.colorScheme.onSurface
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(topStart = start, bottomStart = start, topEnd = end, bottomEnd = end))
                    .background(container)
                    .selectable(selected = isSelected, enabled = enabled, role = Role.RadioButton) {
                        haptics.tap()
                        if (!isSelected) onChoose(value)
                    }
                    // Faded while it cannot be used, as Material's disabled
                    // controls are, so a tap that does nothing is expected.
                    .alpha(if (enabled) 1f else 0.38f)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (isSelected) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
                    }
                    Text(
                        label(value),
                        style = MaterialTheme.typography.labelLarge,
                        color = content,
                        maxLines = 1,
                        softWrap = false,
                        autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = 14.sp),
                    )
                }
            }
        }
    }
}

/** A small round mark before a status - green for running, red for not. */
@Composable
internal fun StatusDot(color: Color) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
}

/**
 * The icons Settings needs that material-icons-core lacks, drawn here rather
 * than pulling in material-icons-extended for six of them (see KeepIcons). The
 * paths are Material's own.
 */
internal object SettingsIcons {

    val Record = icon("Record", "M12,7c-2.76,0 -5,2.24 -5,5s2.24,5 5,5 5,-2.24 5,-5 -2.24,-5 -5,-5zM12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM12,20c-4.42,0 -8,-3.58 -8,-8s3.58,-8 8,-8 8,3.58 8,8 -3.58,8 -8,8z")

    val Chat = icon("Chat", "M20,2H4c-1.1,0 -1.99,0.9 -1.99,2L2,22l4,-4h14c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2zM18,14H6v-2h12v2zM18,11H6V9h12v2zM18,8H6V6h12v2z")

    val Folder = icon("Folder", "M10,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z")

    val Timer = icon("Timer", "M11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 22,12S17.52,2 11.99,2zM12,20c-4.42,0 -8,-3.58 -8,-8s3.58,-8 8,-8 8,3.58 8,8 -3.58,8 -8,8zM12.5,7H11v6l5.25,3.15 0.75,-1.23 -4.5,-2.67z")

    val Tile = icon("Tile", "M3,13h8V3H3v10zM3,21h8v-6H3v6zM13,21h8V11h-8v10zM13,3v6h8V3h-8z")

    val Hidden = icon("Hidden", "M12,7c2.76,0 5,2.24 5,5 0,0.65 -0.13,1.26 -0.36,1.83l2.92,2.92c1.51,-1.26 2.7,-2.89 3.43,-4.75 -1.73,-4.39 -6,-7.5 -11,-7.5 -1.4,0 -2.74,0.25 -3.98,0.7l2.16,2.16C10.74,7.13 11.35,7 12,7zM2,4.27l2.28,2.28 0.46,0.46C3.08,8.3 1.78,10.02 1,12c1.73,4.39 6,7.5 11,7.5 1.55,0 3.03,-0.3 4.38,-0.84l0.42,0.42L19.73,22 21,20.73 3.27,3 2,4.27zM7.53,9.8l1.55,1.55c-0.05,0.21 -0.08,0.43 -0.08,0.65 0,1.66 1.34,3 3,3 0.22,0 0.44,-0.03 0.65,-0.08l1.55,1.55c-0.67,0.33 -1.41,0.53 -2.2,0.53 -2.76,0 -5,-2.24 -5,-5 0,-0.79 0.2,-1.53 0.53,-2.2zM11.84,9.02l3.15,3.15 0.02,-0.16c0,-1.66 -1.34,-3 -3,-3l-0.17,0.01z")

    val Restore = icon("Restore", "M14,12c0,-1.1 -0.9,-2 -2,-2s-2,0.9 -2,2 0.9,2 2,2 2,-0.9 2,-2zM12,3c-4.97,0 -9,4.03 -9,9H0l4,4 4,-4H5c0,-3.87 3.13,-7 7,-7s7,3.13 7,7 -3.13,7 -7,7c-1.51,0 -2.91,-0.49 -4.06,-1.3l-1.42,1.44C8.04,20.3 9.94,21 12,21c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9z")

    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = PathParser().parsePathString(path).toNodes(),
            // Tinted by whoever draws it; black is only the mask.
            fill = SolidColor(Color.Black),
        ).build()
}
