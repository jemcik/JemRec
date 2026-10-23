package com.jemcik.jemrec.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
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
 */
@Composable
internal fun MemoHeader(state: UiState, vm: MainViewModel, answering: Boolean) {
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
            .background(c.head, RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
            .windowInsetsPadding(WindowInsets.statusBars)
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
                            Modifier.clickable(enabled = !state.busy) { haptics.tap(); vm.allowThisWifi() },
                        )
                    } else {
                        MemoSmall(memoWords(instruction), c.idle)
                    }
                }
            }
            IconButton(onClick = { haptics.tap(); vm.showSettings() }) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = c.idle)
            }
        }
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Led(JemRecColors.play, size = 7.dp, glow = false)
            MemoSmall(if (marks.automatic) "Every call" else "Asks each call", c.pillInk)
        }
        if (marks.answering) {
            Icon(messageIcon, contentDescription = "Answering machine on", tint = c.pillInk, modifier = Modifier.size(16.dp))
        }
        if (marks.messengers) {
            Icon(SettingsIcons.Chat, contentDescription = "Messenger calls on", tint = c.pillInk, modifier = Modifier.size(14.dp))
        }
        if (marks.autoDeleteDays > 0) {
            Row(
                Modifier.semantics(mergeDescendants = true) {
                    contentDescription = "Auto-delete after ${ageWords(marks.autoDeleteDays)}"
                },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null, tint = c.pillInk, modifier = Modifier.size(14.dp))
                MemoSmall(ageShort(marks.autoDeleteDays), c.pillInk)
            }
        }
    }
}

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
