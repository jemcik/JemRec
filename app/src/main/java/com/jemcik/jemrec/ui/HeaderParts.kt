package com.jemcik.jemrec.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import com.jemcik.jemrec.ui.theme.DeckColors
import com.jemcik.jemrec.ui.theme.MemoColors
import com.jemcik.jemrec.ui.theme.MultitrackColors

/**
 * The room a bar at the top of a screen keeps for the system: the status
 * bar, and at the sides a navigation bar or a cutout - in landscape, with
 * three-button navigation, the navigation bar is at a side. What Material's
 * TopAppBar kept (TopAppBarDefaults.windowInsets, material3 1.4.0: the
 * system bars and the cutout, top and sides). The looks' headers and bars
 * kept the status bar alone, and in landscape the navigation bar covered
 * the gear, Back or the record button at their ends.
 */
internal val BarInsets: WindowInsets
    @Composable get() = WindowInsets.systemBars.union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)

/** Memo's header ground, its tinted band round at the bottom: the home
 *  header's and the Settings bar's, so the two cannot drift apart. */
internal fun Modifier.memoBand(c: MemoColors) =
    background(c.head, RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))

/** Deck's, the recorder's face on a 1dp edge: the face is pale on a pale
 *  page in light, and needs a line to end on. */
internal fun Modifier.deckFace(c: DeckColors): Modifier {
    val shape = RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp)
    return background(c.faceEdge, shape).padding(bottom = 1.dp).background(c.face, shape)
}

/** Multitrack's, the editor's panel on its line. */
internal fun Modifier.multitrackPanel(c: MultitrackColors) = background(c.panel).lineUnder(c.line)

/** A 1dp line along the bottom: what Multitrack's panels and lanes end on. */
internal fun Modifier.lineUnder(color: Color) = drawBehind {
    drawLine(color, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
}

/**
 * A Deck key: the lit face on its edge when [on], the dark one when not, and
 * the lower lip every Deck key has - the tabs', the switches', Back's and the
 * player's, which were written out four times. A dark key's edge is its own
 * fill, and not for nothing: it firms the key's rounded edge - without it
 * the corners' pixels moved by up to 23 of 255 in the screenshots.
 */
internal fun Modifier.deckKey(c: DeckColors, on: Boolean, shape: Shape): Modifier =
    clip(shape)
        .background(if (on) c.keyOn else c.keyOff)
        .border(1.dp, if (on) c.keyOnEdge else c.keyOff, shape)
        .keyLip(c.glow)

/** A key's lower lip: darker in dark, where the face is. */
private fun Modifier.keyLip(dark: Boolean) = drawBehind {
    val lip = 3.dp.toPx()
    drawRect(
        Color.Black.copy(alpha = if (dark) 0.35f else 0.10f),
        topLeft = Offset(0f, size.height - lip),
        size = Size(size.width, lip),
    )
}

/** The gear at a header's end, in every look: Settings. */
@Composable
internal fun SettingsGear(vm: MainViewModel, tint: Color) {
    val haptics = LocalHapticFeedback.current
    IconButton(onClick = { haptics.tap(); vm.showSettings() }) {
        Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = tint)
    }
}

/**
 * The record control, whichever look draws it: Deck's REC key, Memo's
 * button, Multitrack's armed button. It is the old header switch in another
 * shape, and does what the switch did - turns recording on and off - and is
 * still a switch to a screen reader. Busy or not: the switch was disabled
 * while busy, and showed it; these looked the same and dropped the tap.
 * Now it flips at once and the recorder follows when free (setEnabled).
 *
 * Round in every look, so it is clipped round here: the press ripple fills
 * whatever it is clipped to, and Multitrack's button - no shadow, which is
 * what clipped Memo's and Deck's - lit up a square on the Honor (2026-10-23).
 */
@Composable
internal fun Modifier.recordToggle(state: UiState, vm: MainViewModel): Modifier {
    val haptics = LocalHapticFeedback.current
    return this
        .clip(CircleShape)
        .toggleable(value = state.enabled, role = Role.Switch) {
            haptics.toggle(it)
            vm.setEnabled(it)
        }
        .semantics { contentDescription = "Record calls" }
}

/** The tap that asks Android to allow this Wi-Fi, in every look's header:
 *  written three times, the copies had drifted - Deck's and Multitrack's
 *  lost the old header's haptic, Memo's had no label for TalkBack. */
@Composable
internal fun Modifier.allowWifiTap(state: UiState, vm: MainViewModel): Modifier {
    val haptics = LocalHapticFeedback.current
    return clickable(enabled = !state.busy, onClickLabel = "Allow this Wi-Fi") {
        haptics.tap()
        vm.allowThisWifi()
    }
}

/** A red that looks lit: brighter up and to the left, as a domed key does. */
internal val LitRed = listOf(Color(0xFFFF9184), Color(0xFFE8564B), Color(0xFFA8322A))

/** Fills a round key with [colors] - light, mid, dark - lit from up-left. */
internal fun Modifier.domed(colors: List<Color>): Modifier = drawBehind {
    drawCircle(
        brush = Brush.radialGradient(
            0f to colors[0], 0.46f to colors[1], 1f to colors[2],
            center = Offset(size.width * 0.38f, size.height * 0.32f),
            radius = size.width * 0.78f,
        ),
    )
}

/** An LED: [color], with a soft glow round it when [glow]. */
@Composable
internal fun Led(color: Color, size: Dp = 7.dp, glow: Boolean = true) {
    Box(Modifier.size(size).drawBehind { drawLed(color, glow) })
}

private fun DrawScope.drawLed(color: Color, glow: Boolean) {
    if (glow) {
        drawCircle(
            brush = Brush.radialGradient(
                listOf(color.copy(alpha = 0.55f), Color.Transparent),
                center = center,
                radius = size.minDimension * 1.3f,
            ),
            radius = size.minDimension * 1.3f,
        )
    }
    drawCircle(color)
}

/**
 * Deck's busy signal: a block of the LCD's segments running along it, as a
 * recorder's display does while it works. [count] segments in [ink].
 */
@Composable
internal fun SegmentChase(ink: Color, modifier: Modifier = Modifier, count: Int = 18) {
    val at by rememberInfiniteTransition(label = "chase").animateFloat(
        initialValue = 0f,
        targetValue = count.toFloat(),
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart),
        label = "chase-at",
    )
    Row(
        modifier.clearAndSetSemantics { },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            // Lit while the moving block, four segments long, is over it -
            // read in the layer, so a frame is a redraw: read here, every
            // frame composed every segment again.
            Box(
                Modifier
                    .width(7.dp)
                    .height(7.dp)
                    .graphicsLayer { alpha = if ((at - i + count) % count < 4f) 1f else 0.13f }
                    .drawBehind { drawRoundRect(ink, cornerRadius = CornerRadius(1.dp.toPx())) },
            )
        }
    }
}

/**
 * Multitrack's busy signal: a row of LEDs with a light sweeping to and fro,
 * as an editor's meter does while it arms. [count] LEDs in [lit], [dark].
 */
@Composable
internal fun LedScan(lit: Color, dark: Color, modifier: Modifier = Modifier, count: Int = 22) {
    val at by rememberInfiniteTransition(label = "scan").animateFloat(
        initialValue = 0f,
        targetValue = (count - 1).toFloat(),
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "scan-at",
    )
    Row(
        modifier.clearAndSetSemantics { },
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            // Read in the drawing, as SegmentChase's.
            Box(
                Modifier.size(6.dp).drawBehind {
                    val near = abs(at - i) < 2f
                    drawLed(if (near) lit else dark, glow = near)
                },
            )
        }
    }
}

/**
 * One line of a look's header, kept as tall as the tallest thing it can
 * show, so the header is one height whatever the record switch and the
 * Wi-Fi make it say. [sizes] lays those things out unseen and unheard -
 * text only, nothing that moves, and only what this state's settings can
 * show: room for a mark that is off is a blank band - and
 * [content] draws what the line shows now, at its start, centred up and
 * down. The height is the sizes' alone: what is shown spills over, centred,
 * rather than grow the line - a word shrunk to fit lays out a pixel taller
 * than the same word at full size (Memo's title, at twice the text size).
 *
 * Multitrack's header was 5dp shorter while it armed, on the Honor
 * (2026-10-23): its sweep of LEDs is shorter than a line of text, so the
 * list under the header jumped at every switch. At larger text sizes Memo's
 * and Deck's did the same, where a line wrapped in one state and not in
 * another. BehaviourTest.*HeaderIsOneHeight holds all three to one height.
 *
 * At normal text size what it keeps room for has to fit one line, or the
 * room shows: on the Honor (2026-10-23) Memo's second line kept two, for
 * its longest note and for marks that were off, and its one line of marks
 * sat in the middle of them, with the record button centred on the blank.
 * BehaviourTest.*WasteNoRoom holds that.
 */
@Composable
internal fun SteadyLine(sizes: @Composable () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.testTag(STEADY_LINE)) {
        Box(Modifier.alpha(0f).clearAndSetSemantics { }) { sizes() }
        Box(
            Modifier.matchParentSize().wrapContentHeight(unbounded = true).testTag(STEADY_SHOWN),
            contentAlignment = Alignment.CenterStart,
        ) { content() }
    }
}

/** For the tests: a steady line, and what it shows inside it. */
internal const val STEADY_LINE = "steady-line"
internal const val STEADY_SHOWN = "steady-shown"

/**
 * The number on the Recordings tab - how many recordings the list has - in
 * every look and every state; null on the Calls tab and for an empty list.
 * One place for it in all three (the user's choice, 2026-10-23): Deck once
 * had it in its LCD, and only while ready; Multitrack not at all.
 */
internal fun tabCount(state: UiState, tab: HomeTab): Int? =
    state.recordings.size.takeIf { tab == HomeTab.RECORDINGS && it > 0 }

/** The tab's number, heard as what it counts. */
internal fun Modifier.countSemantics(count: Int): Modifier =
    semantics { contentDescription = if (count == 1) "1 recording" else "$count recordings" }

/** The tabs' names, the same in every look. */
internal fun tabLabel(tab: HomeTab): String = when (tab) {
    HomeTab.RECORDINGS -> "Recordings"
    HomeTab.CALLS -> "Calls"
}
