package com.jemcik.jemrec.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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

/**
 * The record control, whichever look draws it: Deck's REC key, Memo's
 * button, Multitrack's armed button. It is the old header switch in another
 * shape, and does what the switch did - turns recording on and off, not
 * while the app is busy - and is still a switch to a screen reader.
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
        .toggleable(value = state.enabled, enabled = !state.busy, role = Role.Switch) {
            haptics.toggle(it)
            vm.setEnabled(it)
        }
        .semantics { contentDescription = "Record calls" }
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
    Box(
        Modifier
            .size(size)
            .drawBehind {
                if (glow) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(color.copy(alpha = 0.55f), Color.Transparent),
                            center = center,
                            radius = this.size.minDimension * 1.3f,
                        ),
                        radius = this.size.minDimension * 1.3f,
                    )
                }
                drawCircle(color)
            },
    )
}

/**
 * Whether the header shows its look's busy signal: while recording is being
 * switched on - the wait the user started, with the recorder taking a couple
 * of seconds to come up. Not on every busy: that is also raised by the check
 * on each opening of the app, and the old busy line moved every time the app
 * was opened, for nothing the user did.
 */
internal fun switchingOn(state: UiState): Boolean = state.enabled && state.starting

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
            // Lit while the moving block, four segments long, is over it.
            val behind = (at - i + count) % count
            Box(
                Modifier
                    .width(7.dp)
                    .height(7.dp)
                    .graphicsLayer { alpha = if (behind < 4f) 1f else 0.13f }
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
            val near = abs(at - i) < 2f
            Led(if (near) lit else dark, size = 6.dp, glow = near)
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
