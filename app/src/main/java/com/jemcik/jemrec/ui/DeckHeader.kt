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
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.jemcik.jemrec.ui.theme.DeckColors
import com.jemcik.jemrec.ui.theme.LookFonts
import com.jemcik.jemrec.ui.theme.deckColors

/**
 * Deck's header: one strip of a pocket recorder - the REC key, the LCD, the
 * gear - and the tabs as two keys under it. The LCD says the state; under
 * it, a mark for each setting that is on (see Marks), what to do when the
 * recorder cannot record, or - switching on - a block of segments running
 * along it.
 *
 * One strip in every look (option A of the header redesign, 2026-10-23):
 * the label row over it went, and the 84dp key became 48, which took the
 * header from 245dp to about 130.
 */
@Composable
internal fun DeckHeader(state: UiState, vm: MainViewModel, answering: Boolean) {
    val c = deckColors()
    val haptics = LocalHapticFeedback.current
    val shape = RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp)
    Column(
        Modifier
            .fillMaxWidth()
            // A 1dp edge under the face: the face is pale on a pale page in
            // light, and needs a line to end on.
            .background(c.faceEdge, shape)
            .padding(bottom = 1.dp)
            .background(c.face, shape)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier
                    .size(48.dp)
                    .shadow(3.dp, CircleShape)
                    .background(c.keyBezel, CircleShape)
                    .border(1.dp, c.keyEdge, CircleShape)
                    .recordToggle(state, vm)
                    .padding(4.dp),
            ) {
                Box(Modifier.fillMaxSize().domed(if (state.enabled) LitRed else c.keyIdle))
            }
            DeckLcd(state, vm, marks(state, answering), c, Modifier.weight(1f))
            IconButton(onClick = { haptics.tap(); vm.showSettings() }) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = c.icon)
            }
        }
        DeckTabs(state, c, Modifier.padding(end = 10.dp)) { haptics.tap(); vm.showTab(it) }
    }
}

@Composable
private fun DeckLcd(state: UiState, vm: MainViewModel, marks: Marks, c: DeckColors, modifier: Modifier) {
    val status = recorderStatus(state)
    val off = status == RecorderStatus.OFF
    val ink = if (off) c.inkOff else c.ink
    // Dark: the segments glow, the way a negative LCD's do.
    val glow = if (c.glow) Shadow(ink.copy(alpha = 0.55f), blurRadius = 14f) else null
    // The small lines in a plain mono: the LCD's dot font is for the one big
    // word, and too hard to read at this size.
    val small = TextStyle(fontFamily = LookFonts.engraved, fontSize = 11.sp, letterSpacing = 0.04.em, color = ink, shadow = glow)
    val word = when (status) {
        RecorderStatus.READY -> "REC READY"
        RecorderStatus.STARTING -> "STARTING"
        RecorderStatus.OFF -> "OFF"
        RecorderStatus.CANNOT -> "CAN'T REC"
    }
    // Under the word: the marks while it can record; what to do while it
    // cannot - the old header's second line, the Wi-Fi question a tap away.
    val instruction = instruction(state)
    val asks = instruction == Instruction.ALLOW_WIFI
    val lcdShape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .heightIn(min = 56.dp)
            .clip(lcdShape)
            .background(if (off) c.lcdOff else c.lcd)
            .border(2.dp, c.bezel, lcdShape)
            .then(
                if (asks) {
                    Modifier.clickable(enabled = !state.busy, onClickLabel = "Allow this Wi-Fi") { vm.allowThisWifi() }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column(
            Modifier
                .clearAndSetSemantics { contentDescription = headerSpeech(state, marks) }
                .padding(horizontal = 9.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Each line as tall as the tallest thing it shows, so the LCD is
            // one height whatever it says.
            SteadyLine(sizes = { DeckWord(word, ink, glow, fitted = false) }) { DeckWord(word, ink, glow) }
            SteadyLine(
                sizes = {
                    DeckMarks(markLabels(marks, RecorderStatus.READY), small, ink)
                    for (each in Instruction.entries) Text(deckWords(each), style = small)
                },
            ) {
                when {
                    status == RecorderStatus.STARTING -> SegmentChase(ink)
                    instruction != null -> Text(deckWords(instruction), style = small)
                    else -> DeckMarks(markLabels(marks, status), small, ink)
                }
            }
        }
    }
}

private fun deckWords(instruction: Instruction): String = when (instruction) {
    Instruction.SWITCH_ON -> "PRESS REC TO RECORD CALLS"
    Instruction.ALLOW_WIFI -> "TAP TO ALLOW THIS WI-FI"
    Instruction.JOIN_WIFI -> "JOIN ANY WI-FI · RESUMES ITSELF"
}

/** The state in the LCD's dots: shrunk to fit one line, unless not
 *  [fitted] - the full size, for SteadyLine to measure. */
@Composable
private fun DeckWord(word: String, ink: Color, glow: Shadow?, fitted: Boolean = true) {
    Text(
        word,
        fontFamily = LookFonts.lcd,
        fontWeight = FontWeight.Black,
        fontSize = 21.sp,
        color = ink,
        style = TextStyle(shadow = glow),
        maxLines = 1,
        softWrap = false,
        autoSize = if (fitted) oneLineAutoSize(min = 12.dp, max = 21.sp) else null,
    )
}

/** A box for each setting that is on. Wraps rather than clips: at twice the
 *  text size the four are wider than the LCD. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeckMarks(labels: List<String>, small: TextStyle, ink: Color) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for (label in labels) {
            Text(
                label,
                style = small.copy(fontSize = 9.sp),
                maxLines = 1,
                modifier = Modifier
                    .border(1.dp, ink, RoundedCornerShape(2.dp))
                    .padding(horizontal = 3.dp),
            )
        }
    }
}

/** Recordings | Calls, as two keys on the face; the lit one has an LED, and
 *  Recordings the number of them, printed as the key's legend is. */
@Composable
private fun DeckTabs(state: UiState, c: DeckColors, modifier: Modifier, onSelect: (HomeTab) -> Unit) {
    val selected = state.homeTab
    Row(modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (tab in HomeTab.entries) {
            val on = tab == selected
            val shape = RoundedCornerShape(10.dp)
            Row(
                Modifier
                    .weight(1f)
                    // At least, not exactly: a fixed height left large text no
                    // room, and auto-size shrank it to the floor.
                    .heightIn(min = 44.dp)
                    .clip(shape)
                    .background(if (on) c.keyOn else c.keyOff)
                    .border(1.dp, if (on) c.keyOnEdge else c.keyOff, shape)
                    // The key's lower lip.
                    .drawBehind {
                        val lip = 3.dp.toPx()
                        drawRect(
                            Color.Black.copy(alpha = if (c.glow) 0.35f else 0.10f),
                            topLeft = Offset(0f, size.height - lip),
                            size = Size(size.width, lip),
                        )
                    }
                    .selectable(selected = on, role = Role.Tab) { if (!on) onSelect(tab) },
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (on) Led(c.keyLed, size = 6.dp)
                Text(
                    tabLabel(tab),
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (on) c.keyOnInk else c.keyOffInk,
                    maxLines = 1,
                    softWrap = false,
                    autoSize = oneLineAutoSize(min = 10.dp, max = 15.sp),
                    // Shrinks before it runs into the LED, the number or the
                    // key's edge.
                    modifier = Modifier.weight(1f, fill = false).padding(end = 4.dp),
                )
                tabCount(state, tab)?.let { count ->
                    Text(
                        "$count",
                        fontFamily = LookFonts.engraved,
                        fontSize = 14.sp,
                        color = if (on) c.keyOnInk else c.keyOffInk,
                        maxLines = 1,
                        modifier = Modifier.padding(end = 4.dp).countSemantics(count),
                    )
                }
            }
        }
    }
}
