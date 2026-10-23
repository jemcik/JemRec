package com.jemcik.jemrec.ui

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jemcik.jemrec.ui.theme.JemRecColors
import com.jemcik.jemrec.ui.theme.MemoColors
import com.jemcik.jemrec.ui.theme.memoColors

/**
 * Memo's header: one strip - the record button, the state in words, the gear
 * - and the two tabs on a track under it. Under the state, what is on (see
 * Marks) in one line, or what to do when it cannot record. Switching on,
 * Material's progress ring turns round the button.
 *
 * ONE HEIGHT. It was a big button over the state over two rows of pills, a
 * third of the screen, folding away as the list scrolled - and the fold was
 * fragile: on the Honor (2026-10-23) it once took every scroll and the list
 * stopped. Option A of the header redesign put it all in one strip, about
 * 120dp where it was 368, and nothing folds.
 *
 * With a [panel] - a phone on its side - the header is the side panel: its
 * ground the column's full height, the panel under the state, the tabs at
 * the foot.
 */
@Composable
internal fun MemoHeader(state: UiState, vm: MainViewModel, answering: Boolean, panel: (@Composable () -> Unit)? = null) {
    val c = memoColors()
    val haptics = LocalHapticFeedback.current
    val status = recorderStatus(state)
    val marks = marks(state, answering)
    val (title, titleColor) = when (status) {
        RecorderStatus.READY -> "Ready to record" to c.ready
        RecorderStatus.STARTING -> "Starting..." to c.idle
        RecorderStatus.OFF -> "Off" to c.idle
        RecorderStatus.CANNOT -> "Cannot record" to c.alarm
    }
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (panel != null) Modifier.fillMaxHeight() else Modifier)
            .memoBand(c, aside = panel != null)
            .windowInsetsPadding(BarInsets)
            .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                if (status == RecorderStatus.STARTING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(56.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 3.dp,
                    )
                }
                MemoButton(state, vm, c)
            }
            // Heard as one: the state, then what is on.
            Column(
                Modifier.weight(1f).semantics(mergeDescendants = true) { },
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                // Each line as tall as the tallest thing it shows: at larger
                // text the notes wrap where the marks do not.
                SteadyLine(sizes = { MemoTitle(title, titleColor, fitted = false) }) { MemoTitle(title, titleColor) }
                val instruction = instruction(state)
                SteadyLine(
                    sizes = {
                        MemoMarks(c, marks)
                        for (each in Instruction.entries) MemoSmall(memoWords(each), c.idle)
                    },
                ) {
                    if (instruction == null) {
                        MemoMarks(c, marks)
                    } else if (instruction == Instruction.ALLOW_WIFI) {
                        MemoSmall(
                            memoWords(instruction),
                            MaterialTheme.colorScheme.primary,
                            Modifier.allowWifiTap(vm),
                        )
                    } else {
                        MemoSmall(memoWords(instruction), c.idle)
                    }
                }
            }
            SettingsGear(vm, c.idle)
        }
        panel?.let { PanelMiddle(it) }
        MemoTabs(state, c) { haptics.tap(); vm.showTab(it) }
    }
}

/** The button: a white disc with the record mark on it, an empty ring when
 *  recording is off. */
private fun memoWords(instruction: Instruction): String = when (instruction) {
    Instruction.SWITCH_ON -> "Tap the button to record calls"
    Instruction.ALLOW_WIFI -> "Tap to allow this Wi-Fi"
    // Short enough for one line on the Honor: "Join any Wi-Fi - it resumes
    // on its own" took two there, and its line kept room for both.
    Instruction.JOIN_WIFI -> "Join any Wi-Fi to resume"
}

/** The state: shrunk to fit one line, unless not [fitted] - the full size,
 *  for SteadyLine to measure. */
@Composable
private fun MemoTitle(title: String, color: Color, fitted: Boolean = true) {
    Text(
        title,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        color = color,
        maxLines = 1,
        softWrap = false,
        autoSize = if (fitted) oneLineAutoSize(min = 13.dp, max = 19.sp) else null,
    )
}

/** Memo's small words - the notes and the marks' - in Material's Body Small,
 *  12sp on a 16sp line. Plain 12sp kept the 24sp line of the body text it
 *  sat in, and stood the second line well apart from the first. */
@Composable
private fun MemoSmall(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = modifier)
}

/** The mode, then an icon for each other setting that is on. Wraps rather
 *  than clips at twice the text size. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemoMarks(c: MemoColors, marks: Marks) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        for (mark in headerMarks(marks, RecorderStatus.READY)) {
            when {
                mark.setting == Marked.MODE -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    MemoMark(mark, c)
                    MemoSmall(if (marks.automatic) "Every call" else "Asks each call", c.idle)
                }
                mark.lit -> MemoMark(
                    mark,
                    c,
                    Modifier.semantics(mergeDescendants = true) { contentDescription = memoMarkWords(mark) },
                )
            }
        }
    }
}

private fun memoMarkWords(mark: HeaderMark): String = when (mark.setting) {
    Marked.MODE -> ""
    Marked.ANSWERING -> "Answering machine on"
    Marked.MESSENGERS -> "Messenger calls on"
    Marked.AUTO_DELETE -> "Auto-delete after ${ageWords(mark.days)}"
}

/**
 * One setting's mark as Memo's header line draws it - the mode's dot, then
 * the answering machine's, a messenger's and the bin's icons, the bin with
 * its age - and as Settings draws it beside the setting (SettingsMark). One
 * composable for both: drawn apart, Settings' marks sat in coloured circles
 * the header never had (the user found it, 2026-10-23). A mark that is off,
 * which the header leaves out, Settings draws faded.
 *
 * [scale] draws its dot or icon larger, and the spacing - not the age,
 * which scaled with them ran into the row's name - and is
 * MEMO_MARK_IN_SETTINGS there: at the header's size the marks sat small
 * among the rows' 24 dp icons (the user, 2026-10-23).
 */
@Composable
internal fun MemoMark(mark: HeaderMark, c: MemoColors, modifier: Modifier = Modifier, scale: Float = 1f) {
    Row(
        modifier.testTag(markFaceTag(mark)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp * scale),
    ) {
        val glyph = Modifier.testTag(markGlyphTag(mark))
        when (mark.setting) {
            Marked.MODE -> Led(JemRecColors.play, size = 7.dp * scale, glow = false, modifier = glyph)
            Marked.ANSWERING -> Icon(messageIcon, contentDescription = null, tint = c.idle, modifier = glyph.size(16.dp * scale))
            Marked.MESSENGERS -> Icon(SettingsIcons.Chat, contentDescription = null, tint = c.idle, modifier = glyph.size(14.dp * scale))
            Marked.AUTO_DELETE -> {
                Icon(Icons.Filled.Delete, contentDescription = null, tint = c.idle, modifier = glyph.size(14.dp * scale))
                if (mark.lit) MemoSmall(ageShort(mark.days), c.idle)
            }
        }
    }
}

/** A Memo mark's dot or icon, for the tests to measure. */
internal fun markGlyphTag(mark: HeaderMark) = "${markFaceTag(mark)}-glyph"

/** Memo's marks in Settings, against the header's: the answering machine's
 *  16 dp icon at the rows' 24 dp. Deck's and Multitrack's are their
 *  header's size there - they are print and LEDs, not icons. */
internal const val MEMO_MARK_IN_SETTINGS = 1.5f

@Composable
private fun MemoButton(state: UiState, vm: MainViewModel, c: MemoColors) {
    Box(
        Modifier
            .size(48.dp)
            .shadow(4.dp, CircleShape)
            .background(c.disc, CircleShape)
            .border(1.dp, c.discEdge, CircleShape)
            .recordToggle(state, vm),
        contentAlignment = Alignment.Center,
    ) {
        if (state.enabled) {
            Box(Modifier.size(26.dp).background(JemRecColors.iconRecord, CircleShape))
        } else {
            Box(Modifier.size(26.dp).border(2.dp, c.offRing, CircleShape))
        }
    }
}

/** Recordings | Calls: two segments on a track, the chosen one raised, with
 *  the number of recordings on its own. */
@Composable
private fun MemoTabs(state: UiState, c: MemoColors, onSelect: (HomeTab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 10.dp)
            .heightIn(min = 44.dp)
            .clip(CircleShape)
            .background(c.track)
            .padding(4.dp)
            .selectableGroup(),
    ) {
        for (tab in HomeTab.entries) {
            val on = tab == state.homeTab
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = 36.dp)
                    .then(if (on) Modifier.shadow(1.dp, CircleShape) else Modifier)
                    .clip(CircleShape)
                    .background(if (on) c.segment else Color.Transparent)
                    .selectable(selected = on, role = Role.Tab) { if (!on) onSelect(tab) },
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    tabLabel(tab),
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (on) MaterialTheme.colorScheme.onSurface else c.idle,
                    maxLines = 1,
                    autoSize = oneLineAutoSize(min = 10.dp, max = 15.sp),
                    // Shrinks before the count is pushed off the segment.
                    modifier = Modifier.weight(1f, fill = false),
                )
                tabCount(state, tab)?.let { count ->
                    Text(
                        "$count",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = c.badgeInk,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(c.badge)
                            .padding(horizontal = 7.dp, vertical = 1.dp)
                            .countSemantics(count),
                    )
                }
            }
        }
    }
}
