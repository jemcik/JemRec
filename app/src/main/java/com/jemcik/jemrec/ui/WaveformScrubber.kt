package com.jemcik.jemrec.ui

import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The recording as a waveform, in its player: a bar for each slice of its
 * loudness (Waveforms), so where people talk and where it is quiet can be
 * seen, and gone to. The part played so far is in the slider's own colours -
 * the accent for played, the container colour for the rest - with a line
 * where it is up to.
 *
 * Tap a place to go there. Drag to look for one: [onDrag] has where the
 * finger is, for the time under it, and [onSeek] comes once, on letting go,
 * as with the slider this stands in for - seeking on every move stuttered
 * the sound. To a screen reader it is that slider still: a position in the
 * recording, read as a time, moved a twentieth at a time.
 */
@Composable
internal fun WaveformScrubber(
    levels: List<Float>,
    positionMs: Int,
    durationMs: Int,
    onDrag: (Int?) -> Unit,
    onSeek: (Int) -> Unit,
    modifier: Modifier = Modifier,
    bar: Dp = 1.dp,
    gap: Dp = 0.67.dp,
    // Each look's own: Deck draws in its LCD's ink, Multitrack in the caller's
    // colour. The slider's colours by default.
    played: Color = MaterialTheme.colorScheme.primary,
    rest: Color = MaterialTheme.colorScheme.secondaryContainer,
    head: Color = played,
    height: Dp = 48.dp,
    // While it plays, the playhead glides between the player's readings.
    playing: Boolean = false,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val duration by rememberUpdatedState(durationMs)
    val drag by rememberUpdatedState(onDrag)
    val seek by rememberUpdatedState(onSeek)
    // Where a drag is, for its end: read by the gestures, never drawn.
    val dragged = remember { mutableIntStateOf(NOT_DRAGGING) }
    val position = positionMs.coerceIn(0, max(0, durationMs))
    // Where the playhead is drawn: playing, PlayheadGlide's, moved on every
    // frame between the player's readings; paused or dragged, where the
    // player or the finger is. Read only in the drawing, so a frame redraws
    // the bars and recomposes nothing. Infinite-animation frames, which the
    // screen tests hold still, so their pictures do not depend on how long
    // a test takes.
    val glide = remember(durationMs) { PlayheadGlide(position) }
    val drawn = remember(durationMs) { mutableFloatStateOf(position.toFloat()) }
    val reading by rememberUpdatedState(position)
    // Not playing, a new position is drawn at once; an unchanged one leaves
    // the head where it was drawn - a pause's last reading can be a tick
    // behind it.
    val placed = remember { mutableIntStateOf(position) }
    SideEffect {
        if (!playing && position != placed.intValue) {
            glide.place(position)
            drawn.floatValue = position.toFloat()
        }
        placed.intValue = position
    }
    LaunchedEffect(glide, playing) {
        if (!playing) return@LaunchedEffect
        glide.resume()
        while (true) {
            withInfiniteAnimationFrameMillis { now -> drawn.floatValue = glide.frame(now, reading, durationMs) }
        }
    }
    // A seek from here: the head goes there now, and the readings from
    // before the player got there do not pull it back.
    fun goTo(ms: Int) {
        glide.soughtTo(ms)
        drawn.floatValue = ms.toFloat()
        seek(ms)
    }

    /** The time under [x], in a waveform [width] wide. */
    fun timeAt(x: Float, width: Int): Int {
        val share = (x / max(1, width)).coerceIn(0f, 1f)
        return ((if (rtl) 1 - share else share) * duration).roundToInt()
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(rtl, glide) {
                detectTapGestures { goTo(timeAt(it.x, size.width)) }
            }
            .pointerInput(rtl, glide) {
                detectHorizontalDragGestures(
                    onDragStart = { dragged.intValue = timeAt(it.x, size.width).also(drag) },
                    onDragEnd = {
                        dragged.intValue.takeIf { it != NOT_DRAGGING }?.let { goTo(it) }
                        dragged.intValue = NOT_DRAGGING
                        drag(null)
                    },
                    onDragCancel = {
                        dragged.intValue = NOT_DRAGGING
                        drag(null)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        dragged.intValue = timeAt(change.position.x, size.width).also(drag)
                    },
                )
            }
            .semantics {
                contentDescription = "Position in the recording"
                stateDescription = "${clock(position)} of ${clock(durationMs)}"
                progressBarRangeInfo = ProgressBarRangeInfo(position.toFloat(), 0f..max(1, durationMs).toFloat())
                setProgress { target ->
                    goTo(target.roundToInt().coerceIn(0, durationMs))
                    true
                }
            },
    ) {
        // Three bars where there were one (3 + 2 dp), for the detail of a
        // call - on a screen dense enough to draw them: no thinner than
        // 3 px, nor a gap than 2, or a coarse screen breaks them up.
        val barPx = max(bar.toPx(), 3f)
        val gapPx = max(gap.toPx(), 2f)
        val count = max(1, ((size.width + gapPx) / (barPx + gapPx)).toInt())
        // The bars spread over the whole width, the last flush with its end.
        val step = if (count > 1) (size.width - barPx) / (count - 1) else 0f
        val lowest = 2.dp.toPx()
        // The touch area is 48 dp; the bars keep off its edges, and the row
        // above, by this much.
        val inset = 6.dp.toPx()
        val tallest = size.height - 2 * inset
        val share = if (durationMs > 0) drawn.floatValue / durationMs else 0f
        val headAt = (if (rtl) 1 - share else share) * size.width
        for (i in 0 until count) {
            val level = loudestIn(levels, i, count)
            val height = lowest + level * (tallest - lowest)
            val left = i * step
            val x = if (rtl) size.width - left - barPx else left
            val isPlayed = if (rtl) x + barPx / 2 >= headAt else x + barPx / 2 <= headAt
            drawRoundRect(
                color = if (isPlayed) played else rest,
                topLeft = Offset(x, (size.height - height) / 2),
                size = Size(barPx, height),
                cornerRadius = CornerRadius(barPx / 2),
            )
        }
        val line = 2.dp.toPx()
        drawRoundRect(
            color = head,
            topLeft = Offset((headAt - line / 2).coerceIn(0f, size.width - line), inset / 2),
            size = Size(line, size.height - inset),
            cornerRadius = CornerRadius(line / 2),
        )
    }
}

private const val NOT_DRAGGING = -1

/**
 * Where to draw the playhead while it plays, frame by frame, from the
 * player's readings (every 300 ms - MainViewModel's ticker). Drawn only at
 * the readings it stepped three times a second; moved on at the player's
 * pace from the last one, it stepped back a little just after playing began
 * and after a tap (both seen by the user on the Honor, 2026-10-23). The
 * cause, read from the code rather than measured: it ran on from the first
 * reading while the player, just started or just sent elsewhere, had not
 * yet moved or got there, and the next reading pulled it back. So:
 * - it moves on only once the readings show the player moving;
 * - it never goes back, except for a seek: ahead of the player it slows,
 *   to a stop if need be, and behind it hurries; within [CATCH_UP_MS] the
 *   two are together again;
 * - a reading [JUMP_MS] or more from where the player should be is a seek
 *   made elsewhere, and the head goes there;
 * - after a seek from the scrubber ([soughtTo]) the next few readings that
 *   are not near where it was sent are from before the player got there,
 *   and are not believed.
 * Plain arithmetic, so the rules are tested off the screen
 * (PlayheadGlideTest).
 */
internal class PlayheadGlide(start: Int) {
    var head: Float = start.toFloat()
        private set
    // The last reading believed, and when it came; the last one seen at all.
    private var last = start
    private var lastAt = NONE
    private var seen = start
    private var frameAt = NONE
    private var moving = false
    private var sought = NOT_DRAGGING
    private var staleLeft = 0

    /** Not playing: the head is at [ms], the player's or the finger's. */
    fun place(ms: Int) {
        head = ms.toFloat()
        last = ms
        seen = ms
        moving = false
    }

    /** The scrubber sent the player to [ms]. */
    fun soughtTo(ms: Int) {
        place(ms)
        sought = ms
        staleLeft = STALE_READINGS
    }

    /** Playing (again): the clock starts afresh, and the head waits for the
     *  player to be seen moving. */
    fun resume() {
        lastAt = NONE
        frameAt = NONE
        moving = false
    }

    /** A frame at [now] (milliseconds), the player's latest reading being
     *  [reading]: where to draw the head. */
    fun frame(now: Long, reading: Int, durationMs: Int): Float {
        if (frameAt == NONE) {
            frameAt = now
            lastAt = now
        }
        // A new reading - counted once, however many frames it lasts.
        if (reading != seen) {
            seen = reading
            if (sought != NOT_DRAGGING && abs(reading - sought) > SETTLE_MS && staleLeft > 0) {
                staleLeft--
            } else {
                sought = NOT_DRAGGING
                moving = reading > last
                last = reading
                lastAt = now
            }
        }
        val player = if (moving) last + (now - lastAt) else last.toLong()
        val behind = player - head
        val elapsed = now - frameAt
        frameAt = now
        head = if (abs(behind) >= JUMP_MS) {
            player.toFloat()
        } else {
            val pace = (if (moving) 1f else 0f) + behind / CATCH_UP_MS
            head + elapsed * pace.coerceAtLeast(0f)
        }
        head = head.coerceIn(0f, max(0, durationMs).toFloat())
        return head
    }

    companion object {
        const val CATCH_UP_MS = 250f
        const val JUMP_MS = 1500f
        const val SETTLE_MS = 700
        const val STALE_READINGS = 3
        private const val NONE = Long.MIN_VALUE
    }
}

/** The loudest of [levels] that bar [i] of [count] stands for. */
internal fun loudestIn(levels: List<Float>, i: Int, count: Int): Float {
    if (levels.isEmpty()) return 0f
    val from = (i.toLong() * levels.size / count).toInt()
    val to = min(levels.size, max(from + 1, ((i + 1).toLong() * levels.size / count).toInt()))
    var loudest = 0f
    for (l in from until to) loudest = max(loudest, levels[l])
    return loudest
}
