package com.jemcik.jemrec.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.jemcik.jemrec.ui.theme.LocalLook
import com.jemcik.jemrec.ui.theme.lookMono
import com.jemcik.jemrec.ui.theme.Look
import com.jemcik.jemrec.ui.theme.LookFonts
import com.jemcik.jemrec.ui.theme.deckColors
import com.jemcik.jemrec.ui.theme.memoColors
import com.jemcik.jemrec.ui.theme.multitrackColors

/*
 * The parts Settings is built from: a titled group of rows, the rows
 * themselves, a footer, a dialog for picking one of a few, and a row of
 * connected buttons for the same job inline.
 *
 * WHY THESE SHAPES. Android's settings guidelines
 * (source.android.com/docs/core/settings/settings-guidelines) ask for a screen
 * that can be read at a glance - each setting's current value under its title,
 * explanations in a footer, single choices in a dialog. Settings was fourteen
 * cards of paragraphs with the values buried in them; these are what it is
 * made of now.
 *
 * IN THE LOOK'S OWN WAY - design C of the canvas's "Settings, three ways",
 * the user's choice, 2026-10-23. A group is headed as a day is in the lists
 * and sits on the look's ground, a line between its rows, as the lists' rows
 * sit: a Memo card, a Deck face plate, Multitrack's lanes. It was Material
 * 3's grouped tiles, split by small gaps, the same in every look. A row's
 * words are in the look's type; its switch is the look's own; and a setting
 * the header marks carries that mark where its icon was.
 */

/** Collects a group's rows, so each can be drawn knowing where it sits.
 *  A row of a list that changes (a chosen contact) gives a [key], so removing
 *  one does not hand its remembered state - its photo - to the next. */
internal class SettingsGroupScope {
    val rows = mutableListOf<Pair<Any?, @Composable () -> Unit>>()
    fun row(key: Any? = null, content: @Composable () -> Unit) {
        rows += key to content
    }
}

/** A group's heading, as a day's is in the lists (LookHeading): Memo's
 *  words, Deck's engraved capitals on a rule, Multitrack's marker capitals
 *  - without the marker's flag, as a group does not fold. */
@Composable
internal fun SettingsHeading(text: String, top: Dp = 20.dp) {
    LookHeading(text, Modifier.padding(start = 4.dp, end = 4.dp, top = top, bottom = 8.dp))
}

/** Related rows as one block, under an optional heading, with an optional
 *  [note] under them, on a tip card: something they depend on that is set
 *  elsewhere. On the look's own ground, a line between its rows: a Memo
 *  card, a Deck face plate, Multitrack's lanes across the screen. */
@Composable
internal fun SettingsGroup(title: String? = null, note: String? = null, build: SettingsGroupScope.() -> Unit) {
    val rows = SettingsGroupScope().apply(build).rows
    if (rows.isEmpty()) return
    Column {
        if (title != null) SettingsHeading(title)
        GroupGround {
            rows.forEachIndexed { index, (rowKey, row) ->
                key(rowKey ?: index) {
                    if (index > 0) GroupLine()
                    row()
                }
            }
        }
        if (note != null) TipCard(note, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    }
}

@Composable
private fun GroupGround(rows: @Composable () -> Unit) {
    when (LocalLook.current) {
        Look.MEMO -> {
            val c = memoColors()
            val shape = RoundedCornerShape(20.dp)
            Column(Modifier.fillMaxWidth().clip(shape).background(c.card).border(1.dp, c.cardEdge, shape)) { rows() }
        }
        Look.DECK -> {
            // The face the player's card is: a plate on the page.
            val c = deckColors()
            val shape = RoundedCornerShape(18.dp)
            Column(Modifier.fillMaxWidth().clip(shape).background(c.face).border(1.dp, c.faceEdge, shape)) { rows() }
        }
        Look.MULTITRACK -> Column(Modifier.toTheEdges(16.dp)) {
            GroupLine()
            rows()
            GroupLine()
        }
    }
}

/** The line between two rows: the card's edge, the face's, a lane's. */
@Composable
private fun GroupLine() {
    val color = when (LocalLook.current) {
        Look.MEMO -> memoColors().cardEdge
        Look.DECK -> deckColors().faceEdge
        Look.MULTITRACK -> multitrackColors().lane
    }
    HorizontalDivider(thickness = 1.dp, color = color)
}

/** Out past the screen's [margin] to its edges, as Multitrack's lanes run
 *  across the home screen. Settings' column has a 16dp margin. */
private fun Modifier.toTheEdges(margin: Dp) = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val out = margin.roundToPx()
    val placeable = measurable.measure(
        constraints.copy(minWidth = constraints.minWidth + 2 * out, maxWidth = constraints.maxWidth + 2 * out),
    )
    layout(placeable.width - 2 * out, placeable.height) { placeable.place(-out, 0) }
}

/** A row's name and its summary, in the look's type: Memo's, the phone's;
 *  Deck's summary engraved, as its rows' details are; Multitrack's name in
 *  Space Grotesk over JetBrains Mono, as its lanes'.
 *
 *  Deck's summary was 13sp, spaced 0.02em: a mono letter is as wide as an
 *  m, and "Asks with a notification when a call starts..." took five lines
 *  on the Honor (2026-10-23). At 12sp, unspaced - Multitrack's size - it is
 *  a line shorter, as short as in the phone's font, and still Deck's. */
@Composable
private fun rowTitle(): TextStyle = when (LocalLook.current) {
    Look.MULTITRACK -> TextStyle(fontFamily = LookFonts.grotesk, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 22.sp)
    else -> MaterialTheme.typography.bodyLarge
}

@Composable
private fun rowSummary(): TextStyle = when (LocalLook.current) {
    Look.MEMO -> MaterialTheme.typography.bodyMedium
    Look.DECK -> TextStyle(fontFamily = LookFonts.engraved, fontSize = 12.sp, lineHeight = 17.sp)
    Look.MULTITRACK -> TextStyle(fontFamily = LookFonts.mono, fontSize = 12.sp, lineHeight = 17.sp, letterSpacing = 0.02.em)
}

/**
 * One setting: an icon, its name, and what it is set to now.
 *
 * Tappable when [onClick] is given - it then opens what changes it - and with
 * whatever [trailing] holds at the end. Not [enabled], it is faded as
 * Material's disabled list items are, so a tap that does nothing is expected.
 * A setting the header marks gives its [mark], drawn where the icon would be
 * (SettingsMark). An icon, a photo or a mark take one width, so the names
 * line up under each other.
 */
@Composable
internal fun SettingsRow(
    title: String,
    summary: String? = null,
    icon: ImageVector? = null,
    summaryColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    rowModifier: Modifier = Modifier,
    mark: HeaderMark? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(enabled = enabled) { haptics.tap(); onClick() }
                } else {
                    Modifier
                },
            )
            .then(rowModifier)
            .heightIn(min = if (summary != null) 72.dp else 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The trailing control is left alone: a switch has disabled
        // colours of its own.
        val faded = Modifier.alpha(if (enabled) 1f else 0.38f)
        if (mark != null || leading != null || icon != null) {
            Box(Modifier.width(48.dp).then(faded), contentAlignment = Alignment.Center) {
                when {
                    mark != null -> SettingsMark(mark)
                    leading != null -> leading()
                    icon != null -> Icon(icon, contentDescription = null, tint = iconTint)
                }
            }
        }
        Column(Modifier.weight(1f).then(faded)) {
            Text(title, style = rowTitle(), color = titleColor)
            if (summary != null) {
                Text(summary, style = rowSummary(), color = summaryColor)
            }
        }
        trailing?.invoke()
    }
}

/**
 * A setting that is on or off. The whole row is the switch - a tap anywhere
 * flips it, and a screen reader hears one switch - as Material asks of a list
 * item with a trailing switch. The switch drawn is the look's (LookSwitch).
 */
@Composable
internal fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    summary: String? = null,
    icon: ImageVector? = null,
    summaryColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    enabled: Boolean = true,
    mark: HeaderMark? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    SettingsRow(
        title = title,
        summary = summary,
        icon = icon,
        summaryColor = summaryColor,
        enabled = enabled,
        mark = mark,
        leading = leading,
        rowModifier = Modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
        ) { haptics.toggle(it); onCheckedChange(it) },
        trailing = { LookSwitch(checked, enabled) },
    )
}

/**
 * On or off, in the look's own way: Memo keeps Material's switch; Deck's is
 * a key with its LED, as its tabs are - lit, and printed ON, when on;
 * Multitrack's an editor's lit button. Only drawn: the row is the switch, to
 * a tap and to a screen reader, which hears it as one already.
 */
@Composable
private fun LookSwitch(checked: Boolean, enabled: Boolean) {
    when (LocalLook.current) {
        Look.MEMO -> Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        Look.DECK -> {
            val c = deckColors()
            val shape = RoundedCornerShape(8.dp)
            Row(
                Modifier
                    .clearAndSetSemantics { }
                    .alpha(if (enabled) 1f else 0.38f)
                    .heightIn(min = 36.dp)
                    .widthIn(min = 62.dp)
                    .deckKey(c, checked, shape)
                    .padding(start = 10.dp, end = 10.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (checked) Led(c.keyLed, size = 6.dp)
                Text(
                    if (checked) "ON" else "OFF",
                    fontFamily = LookFonts.engraved,
                    fontSize = 13.sp,
                    color = if (checked) c.keyOnInk else c.keyOffInk,
                    maxLines = 1,
                )
            }
        }
        Look.MULTITRACK -> {
            val c = multitrackColors()
            val shape = RoundedCornerShape(4.dp)
            Row(
                Modifier
                    .clearAndSetSemantics { }
                    .alpha(if (enabled) 1f else 0.38f)
                    .heightIn(min = 32.dp)
                    .widthIn(min = 58.dp)
                    .clip(shape)
                    .background(if (checked) c.ledOn.copy(alpha = 0.14f) else Color.Transparent)
                    .border(1.dp, if (checked) c.ledOn else c.faint, shape)
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Led(if (checked) c.ledOn else c.ledOff, size = 7.dp, glow = checked)
                Text(
                    if (checked) "ON" else "OFF",
                    fontFamily = LookFonts.mono,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.06.em,
                    color = if (checked) c.text else c.muted,
                    maxLines = 1,
                )
            }
        }
    }
}

/** The mark, for the tests to find: it is silent to a screen reader. */
internal fun markTag(mark: HeaderMark) = "mark-${mark.label}-${if (mark.lit) "lit" else "dark"}"

/**
 * The header's mark for a row's setting, where its icon would be, so
 * Settings says what each mark in the header is (headerMark decides both).
 * Drawn by the header's own composable for the look - MemoMark, DeckMark,
 * MultitrackMark - so it is the header's mark, not a likeness: drawn here
 * apart, Memo's had sat in coloured circles, Multitrack's LED over a smaller
 * label, Deck's in another spacing (the user found it, 2026-10-23). Memo's
 * is drawn larger here, at the rows' icon size (MEMO_MARK_IN_SETTINGS). A mark
 * the header leaves out because it is off is drawn here as off: Memo's
 * faded, Deck's on its LCD's off grey, Multitrack's LED dark, as its header
 * shows them.
 *
 * Drawn at the normal text size, as the header is at it: it is a picture of
 * the header's mark, and the row's name and summary say it in words, at any
 * size. Silent to a screen reader for the same reason - "ANS" read out after
 * "Answering machine" would say it twice, the second time in code. Wider
 * than the icon's slot where the header's mark is (Multitrack's DEL 90D),
 * centred over it rather than squeezed.
 */
@Composable
internal fun SettingsMark(mark: HeaderMark) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, 1f)) {
        Box(
            Modifier
                .wrapContentWidth(unbounded = true)
                .clearAndSetSemantics { testTag = markTag(mark) },
            contentAlignment = Alignment.Center,
        ) {
            when (LocalLook.current) {
                Look.MEMO -> MemoMark(mark, memoColors(), Modifier.alpha(if (mark.lit) 1f else 0.38f), MEMO_MARK_IN_SETTINGS)
                Look.DECK -> DeckMarkOnLcd(mark)
                Look.MULTITRACK -> MultitrackMark(mark, multitrackColors())
            }
        }
    }
}

/** Deck's mark on a scrap of its LCD: it is the LCD's print, and needs the
 *  LCD's ground - lit, or the off grey the LCD has with recording off. */
@Composable
private fun DeckMarkOnLcd(mark: HeaderMark) {
    val c = deckColors()
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier
            .height(26.dp)
            .widthIn(min = 40.dp)
            .clip(shape)
            .background(if (mark.lit) c.lcd else c.lcdOff)
            .border(1.dp, c.bezel, shape)
            .padding(horizontal = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        DeckMark(mark, deckSmall(c, if (mark.lit) c.ink else c.inkOff))
    }
}

/**
 * A label for a button of ButtonPair: one line, shrunk at most to
 * [SHRINK_AT_MOST] of its size to fit. On two lines it was a block, not a
 * button - Diagnostics' "Start recorder" in Multitrack's monospace, wider
 * than the phone's font, on the Honor (2026-10-23).
 */
@Composable
internal fun ButtonLabel(text: String) {
    val size = MaterialTheme.typography.labelLarge.fontSize
    Text(
        text,
        maxLines = 1,
        softWrap = false,
        // Only where its pair said it must: the steps up from the floor do
        // not land on the full size, and every label came out a shade small.
        autoSize = if (LocalLabelsShrink.current) TextAutoSize.StepBased(minFontSize = size * SHRINK_AT_MOST, maxFontSize = size) else null,
    )
}

/** Whether ButtonPair's labels need to shrink to sit side by side. */
private val LocalLabelsShrink = staticCompositionLocalOf { false }

/** How far a ButtonLabel may shrink: a little, never as far as large text
 *  undone - at twice the text size, shrunk to fit beside "Delete", "Record
 *  again" was half its neighbour's size. */
private const val SHRINK_AT_MOST = 0.85f

/**
 * Buttons that share a row at equal width - or, where a label would not fit
 * its half even shrunk to [SHRINK_AT_MOST], one under the other, each the
 * full width: at large text a pair stacks, rather than wrap a label onto two
 * lines or shrink it past reading. [button] draws the [i]th with the
 * modifier it is given.
 */
@Composable
internal fun ButtonPair(
    labels: List<String>,
    modifier: Modifier = Modifier,
    button: @Composable (i: Int, modifier: Modifier) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val gap = 8.dp
        // A filled button's own padding, 24 dp a side (Material's
        // ButtonDefaults.ContentPadding), out of each share.
        val room = (maxWidth - gap * (labels.size - 1)) / labels.size - 48.dp
        fun fit(scale: Float) = labels.all { label ->
            val width = measurer.measure(label, style.copy(fontSize = style.fontSize * scale), maxLines = 1, softWrap = false).size.width
            with(density) { width.toDp() } <= room
        }
        if (fit(SHRINK_AT_MOST)) {
            CompositionLocalProvider(LocalLabelsShrink provides !fit(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) { labels.indices.forEach { button(it, Modifier.weight(1f)) } }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(gap)) { labels.indices.forEach { button(it, Modifier.fillMaxWidth()) } }
        }
    }
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
                                        fontFamily = lookMono(),
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
                    // Faded while it cannot be used, as Material's disabled
                    // controls are, so a tap that does nothing is expected -
                    // the button and its label both, so first in the chain.
                    .alpha(if (enabled) 1f else 0.38f)
                    .clip(RoundedCornerShape(topStart = start, bottomStart = start, topEnd = end, bottomEnd = end))
                    .background(container)
                    .selectable(selected = isSelected, enabled = enabled, role = Role.RadioButton) {
                        haptics.tap()
                        if (!isSelected) onChoose(value)
                    }
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
                        // A floor in dp: an sp one doubled at twice the text
                        // size and "All but" came out as "All b".
                        autoSize = oneLineAutoSize(min = 9.dp, max = 14.sp),
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
 * than pulling in material-icons-extended for nine of them (see KeepIcons). The
 * paths are Material's own.
 */
internal object SettingsIcons {

    val Chat = icon("Chat", "M20,2H4c-1.1,0 -1.99,0.9 -1.99,2L2,22l4,-4h14c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2zM18,14H6v-2h12v2zM18,11H6V9h12v2zM18,8H6V6h12v2z")

    val Folder = icon("Folder", "M10,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z")

    val Timer = icon("Timer", "M11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 22,12S17.52,2 11.99,2zM12,20c-4.42,0 -8,-3.58 -8,-8s3.58,-8 8,-8 8,3.58 8,8 -3.58,8 -8,8zM12.5,7H11v6l5.25,3.15 0.75,-1.23 -4.5,-2.67z")

    val Tile = icon("Tile", "M3,13h8V3H3v10zM3,21h8v-6H3v6zM13,21h8V11h-8v10zM13,3v6h8V3h-8z")

    val Hidden = icon("Hidden", "M12,7c2.76,0 5,2.24 5,5 0,0.65 -0.13,1.26 -0.36,1.83l2.92,2.92c1.51,-1.26 2.7,-2.89 3.43,-4.75 -1.73,-4.39 -6,-7.5 -11,-7.5 -1.4,0 -2.74,0.25 -3.98,0.7l2.16,2.16C10.74,7.13 11.35,7 12,7zM2,4.27l2.28,2.28 0.46,0.46C3.08,8.3 1.78,10.02 1,12c1.73,4.39 6,7.5 11,7.5 1.55,0 3.03,-0.3 4.38,-0.84l0.42,0.42L19.73,22 21,20.73 3.27,3 2,4.27zM7.53,9.8l1.55,1.55c-0.05,0.21 -0.08,0.43 -0.08,0.65 0,1.66 1.34,3 3,3 0.22,0 0.44,-0.03 0.65,-0.08l1.55,1.55c-0.67,0.33 -1.41,0.53 -2.2,0.53 -2.76,0 -5,-2.24 -5,-5 0,-0.79 0.2,-1.53 0.53,-2.2zM11.84,9.02l3.15,3.15 0.02,-0.16c0,-1.66 -1.34,-3 -3,-3l-0.17,0.01z")

    val Restore = icon("Restore", "M14,12c0,-1.1 -0.9,-2 -2,-2s-2,0.9 -2,2 0.9,2 2,2 2,-0.9 2,-2zM12,3c-4.97,0 -9,4.03 -9,9H0l4,4 4,-4H5c0,-3.87 3.13,-7 7,-7s7,3.13 7,7 -3.13,7 -7,7c-1.51,0 -2.91,-0.49 -4.06,-1.3l-1.42,1.44C8.04,20.3 9.94,21 12,21c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9z")

    val Voicemail = icon("Voicemail", "M18.5,6C15.46,6 13,8.46 13,11.5c0,1.33 0.47,2.55 1.26,3.5H9.74c0.79,-0.95 1.26,-2.17 1.26,-3.5C11,8.46 8.54,6 5.5,6S0,8.46 0,11.5 2.46,17 5.5,17h13c3.04,0 5.5,-2.46 5.5,-5.5S21.54,6 18.5,6zM5.5,15C3.57,15 2,13.43 2,11.5S3.57,8 5.5,8 9,9.57 9,11.5 7.43,15 5.5,15zM18.5,15c-1.93,0 -3.5,-1.57 -3.5,-3.5S16.57,8 18.5,8 22,9.57 22,11.5 20.43,15 18.5,15z")

    // Material's Palette.
    val Palette = icon("Palette", "M12,3c-4.97,0 -9,4.03 -9,9s4.03,9 9,9c0.83,0 1.5,-0.67 1.5,-1.5 0,-0.39 -0.15,-0.74 -0.39,-1.01 -0.23,-0.26 -0.38,-0.61 -0.38,-0.99 0,-0.83 0.67,-1.5 1.5,-1.5L16,16c2.76,0 5,-2.24 5,-5 0,-4.42 -4.03,-8 -9,-8zM6.5,12c-0.83,0 -1.5,-0.67 -1.5,-1.5S5.67,9 6.5,9 8,9.67 8,10.5 7.33,12 6.5,12zM9.5,8C8.67,8 8,7.33 8,6.5S8.67,5 9.5,5s1.5,0.67 1.5,1.5S10.33,8 9.5,8zM14.5,8c-0.83,0 -1.5,-0.67 -1.5,-1.5S13.67,5 14.5,5s1.5,0.67 1.5,1.5S15.33,8 14.5,8zM17.5,12c-0.83,0 -1.5,-0.67 -1.5,-1.5S16.67,9 17.5,9s1.5,0.67 1.5,1.5 -0.67,1.5 -1.5,1.5z")

    val Mic = icon("Mic", "M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11L5,11c0,3.41 2.72,6.23 6,6.72L11,21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z")

    private fun icon(name: String, path: String): ImageVector = pathIcon(name, path)
}
