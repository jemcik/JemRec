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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.jemcik.jemrec.ui.theme.LookFonts
import com.jemcik.jemrec.ui.theme.MultitrackColors
import com.jemcik.jemrec.ui.theme.multitrackColors

/**
 * Multitrack's header: one strip of a sound editor's transport - the armed
 * button, the state as a word, the gear - and the tabs under it. Under the
 * word, one LED per setting, lit while it is on and dark while it is off
 * (see Marks), so the four are always in the same places; what to do when
 * it cannot record; or, switching on, a row of LEDs sweeping.
 *
 * Option A of the header redesign (2026-10-23): from 211dp to about 112.
 *
 * With a [panel] - a phone on its side - the header is the side panel: its
 * ground the column's full height, the panel under the state, the tabs at
 * the foot.
 */
@Composable
internal fun MultitrackHeader(state: UiState, vm: MainViewModel, answering: Boolean, panel: (@Composable () -> Unit)? = null) {
    val c = multitrackColors()
    val haptics = LocalHapticFeedback.current
    val status = recorderStatus(state)
    val marks = marks(state, answering)
    val (word, wordColor) = when (status) {
        RecorderStatus.READY -> "ARMED" to c.armed
        RecorderStatus.STARTING -> "ARMING" to c.muted
        RecorderStatus.OFF -> "DISARMED" to c.off
        RecorderStatus.CANNOT -> "NO LINK" to c.cannot
    }
    val instruction = instruction(state)
    val asks = instruction?.tap != null
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (panel != null) Modifier.fillMaxHeight() else Modifier)
            .multitrackPanel(c, aside = panel != null)
            .windowInsetsPadding(BarInsets)
            .padding(start = 16.dp, end = 6.dp, top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier
                    .size(48.dp)
                    .background(c.disc, CircleShape)
                    .border(2.dp, c.ring, CircleShape)
                    .recordToggle(state, vm),
                contentAlignment = Alignment.Center,
            ) {
                val red = if (state.enabled) c.recOn else c.recOff
                Box(
                    Modifier
                        .size(24.dp)
                        .drawBehind {
                            if (state.enabled) {
                                drawCircle(
                                    Brush.radialGradient(listOf(red.copy(alpha = 0.5f), Color.Transparent), radius = size.minDimension),
                                    radius = size.minDimension,
                                )
                            }
                            drawCircle(red)
                        },
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .then(
                        if (asks) {
                            Modifier.instructionTap(vm, instruction)
                        } else {
                            Modifier
                        },
                    )
                    .clearAndSetSemantics { contentDescription = headerSpeech(state, marks) },
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                // Each line as tall as the tallest thing it shows: the
                // sweep is shorter than a line of LEDs or words.
                SteadyLine(sizes = { MultiWord(word, wordColor, fitted = false) }) { MultiWord(word, wordColor) }
                SteadyLine(
                    sizes = {
                        MultiMarks(c, headerMarks(marks, RecorderStatus.READY))
                        for (each in Instruction.entries) MultiNote(c, multiWords(each))
                    },
                ) {
                    when {
                        status == RecorderStatus.STARTING -> LedScan(c.ledOn, c.ledOff)
                        instruction != null -> MultiNote(c, multiWords(instruction))
                        else -> MultiMarks(c, headerMarks(marks, status))
                    }
                }
            }
            // The lanes' buttons' grey, so the look's buttons are one grey
            // (5.8:1 on the white panel in light).
            SettingsGear(vm, c.control)
        }
        panel?.let { PanelMiddle(it) }
        MultiTabs(state, c, Modifier.padding(end = 10.dp)) { haptics.tap(); vm.showTab(it) }
    }
}

private fun multiWords(instruction: Instruction): String = when (instruction) {
    Instruction.SWITCH_ON -> "TAP THE BUTTON TO RECORD CALLS"
    Instruction.ALLOW_WIFI -> "TAP TO ALLOW THIS WI-FI"
    Instruction.JOIN_WIFI -> "JOIN ANY WI-FI · RESUMES ITSELF"
    Instruction.PAIR_AGAIN -> "TAP TO PAIR AGAIN"
    Instruction.START_AGAIN -> "TAP TO START IT AGAIN"
}

/** The state in the editor's capitals: shrunk to fit one line, unless not
 *  [fitted] - the full size, for SteadyLine to measure. */
@Composable
private fun MultiWord(word: String, color: Color, fitted: Boolean = true) {
    Text(
        word,
        fontFamily = LookFonts.mono,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        letterSpacing = 0.08.em,
        color = color,
        maxLines = 1,
        softWrap = false,
        autoSize = if (fitted) oneLineAutoSize(min = 12.dp, max = 18.sp) else null,
    )
}

@Composable
private fun MultiNote(c: MultitrackColors, text: String) {
    Text(text, fontFamily = LookFonts.mono, fontSize = 10.sp, letterSpacing = 0.06.em, color = c.muted)
}

/**
 * One LED per setting, always all four: the mode's goes dark when recording
 * is off ([modeLit]), the rest when their setting is. Wraps rather than
 * clips at twice the text size.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MultiMarks(c: MultitrackColors, marks: List<HeaderMark>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (mark in marks) MultitrackMark(mark, c)
    }
}

/**
 * One setting's mark as the header's row of LEDs shows it - the LED, lit or
 * dark, beside its label - and as Settings shows it beside the setting
 * (SettingsMark). One composable for both: drawn apart, Settings' stood the
 * LED over a smaller label (the user found it, 2026-10-23).
 */
@Composable
internal fun MultitrackMark(mark: HeaderMark, c: MultitrackColors) {
    Row(
        Modifier.testTag(markFaceTag(mark)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Led(if (mark.lit) c.ledOn else c.ledOff, size = 6.dp, glow = mark.lit)
        Text(
            mark.label,
            fontFamily = LookFonts.mono,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.06.em,
            color = if (mark.lit) c.soft else c.faint,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** RECORDINGS | CALLS in the editor's capitals, the chosen one underlined in
 *  the record red; RECORDINGS with the number of them after it. */
@Composable
private fun MultiTabs(state: UiState, c: MultitrackColors, modifier: Modifier = Modifier, onSelect: (HomeTab) -> Unit) {
    val selected = state.homeTab
    Row(modifier.fillMaxWidth().selectableGroup()) {
        for (tab in HomeTab.entries) {
            val on = tab == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .selectable(selected = on, role = Role.Tab) { if (!on) onSelect(tab) }
                    .drawBehind {
                        if (on) {
                            val w = size.width * 0.56f
                            drawLine(
                                c.recOn,
                                Offset((size.width - w) / 2, size.height - 1.dp.toPx()),
                                Offset((size.width + w) / 2, size.height - 1.dp.toPx()),
                                strokeWidth = 2.dp.toPx(),
                            )
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    Modifier.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        tabLabel(tab).uppercase(),
                        fontFamily = LookFonts.mono,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                        letterSpacing = 0.14.em,
                        color = if (on) c.text else c.muted,
                        maxLines = 1,
                        autoSize = oneLineAutoSize(min = 9.dp, max = 13.sp),
                        // Shrinks before the number is pushed off the tab.
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    tabCount(state, tab)?.let { count ->
                        Text(
                            "$count",
                            fontFamily = LookFonts.mono,
                            fontSize = 12.sp,
                            color = if (on) c.soft else c.faint,
                            maxLines = 1,
                            modifier = Modifier.countSemantics(count),
                        )
                    }
                }
            }
        }
    }
}
