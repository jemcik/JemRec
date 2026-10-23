package com.jemcik.jemrec.ui

import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.jemcik.jemrec.capture.Waveforms
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
    modifier: Modifier,
    // Each look's own: Memo's are the slider's, Deck draws in its LCD's ink,
    // Multitrack in the caller's colour.
    played: Color,
    rest: Color,
    head: Color,
    // While it plays, the playhead glides between the player's readings.
    playing: Boolean,
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
    // How wide it is in pixels: how far the head goes in one.
    val widthPx = remember { mutableIntStateOf(0) }
    // Each bar's loudest level, for this many bars, worked out once.
    val loudness = remember(levels) { BarLoudness(levels) }
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
            withInfiniteAnimationFrameMillis { now ->
                val at = glide.frame(now, reading, durationMs)
                // Drawn again once the head reaches another pixel, not every
                // frame: on a phone-wide scrubber a minute-long call's moves
                // a pixel in several frames, a half-hour one's in dozens, and
                // each drawing is two hundred bars.
                val perPixel = if (widthPx.intValue > 0) duration.toFloat() / widthPx.intValue else 0f
                if (abs(at - drawn.floatValue) >= perPixel) drawn.floatValue = at
            }
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
            .height(44.dp)
            .onSizeChanged { widthPx.intValue = it.width }
            // Its own layer: drawn again as the head moves, it records itself
            // again and not the card or LCD round it.
            .graphicsLayer()
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
        // call - 1dp and 0.67dp apart on a screen dense enough to draw them:
        // no thinner than 3 px, nor a gap than 2, or a coarse screen breaks
        // them up.
        val barPx = max(1.dp.toPx(), 3f)
        val gapPx = max(0.67.dp.toPx(), 2f)
        // The bars keep off the touch area's edges, and the row above, by
        // this much.
        val inset = 6.dp.toPx()
        val share = if (durationMs > 0) drawn.floatValue / durationMs else 0f
        val headAt = (if (rtl) 1 - share else share) * size.width
        drawBars(loudness.of(barCount(size.width, barPx, gapPx)), barPx, 2.dp.toPx(), size.height - 2 * inset, rtl) { x ->
            val isPlayed = if (rtl) x + barPx / 2 >= headAt else x + barPx / 2 <= headAt
            if (isPlayed) played else rest
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

    /** Not playing: the head is at [ms], the player's or the finger's. Away
     *  from a seek's target, the seek is over: tapped just before the end,
     *  a recording finished before a reading came near it, the head was put
     *  back at 0:00 - and the next play's first readings, far from the old
     *  target, were not believed, the head waiting a second as it played. */
    fun place(ms: Int) {
        head = ms.toFloat()
        last = ms
        seen = ms
        moving = false
        if (sought != NOT_DRAGGING && abs(ms - sought) > SETTLE_MS) {
            sought = NOT_DRAGGING
            staleLeft = 0
        }
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
        // Moved on from the last reading for two of the player's readings at
        // most: a player that stalls while still playing sends the same
        // reading again, which moves nothing here, and the head ran on ahead
        // of the sound - then stopped, or jumped back.
        val running = moving && now - lastAt <= AHEAD_MS
        val player = if (moving) last + min(now - lastAt, AHEAD_MS) else last.toLong()
        val behind = player - head
        val elapsed = now - frameAt
        frameAt = now
        head = if (abs(behind) >= JUMP_MS) {
            player.toFloat()
        } else {
            val pace = (if (running) 1f else 0f) + behind / CATCH_UP_MS
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

        /** Two of MainViewModel's 300 ms readings. */
        const val AHEAD_MS = 600L
        private const val NONE = Long.MIN_VALUE
    }
}

/** The loudest of [levels] for each of a number of bars, worked out once
 *  for each number: the bars are drawn again as the head moves, and each
 *  bar's loudest was looked for again among the 800 levels every time. */
internal class BarLoudness(private val levels: List<Float>) {
    private var count = -1
    private var loudest = FloatArray(0)

    fun of(count: Int): FloatArray {
        if (count != this.count) {
            loudest = FloatArray(count) { loudestIn(levels, it, count) }
            this.count = count
        }
        return loudest
    }
}

/** The loudest of [levels] that bar [i] of [count] stands for. */
internal fun loudestIn(levels: List<Float>, i: Int, count: Int): Float {
    if (levels.isEmpty()) return 0f
    var loudest = 0f
    for (l in Waveforms.slice(i, count, levels.size)) loudest = max(loudest, levels[l])
    return loudest
}

/** How many bars [barPx] wide, at least [gapPx] apart, [width] has room for. */
internal fun barCount(width: Float, barPx: Float, gapPx: Float): Int =
    max(1, ((width + gapPx) / (barPx + gapPx)).toInt())

/**
 * [loudest] - a level a bar - as bars [barPx] wide spread over the whole
 * width, the last flush with its end, each centred and from [lowest] up to
 * [tallest] tall, in [colorAt] where it starts. Time runs the way the
 * player's does: right to left in a right-to-left language. The row's strip
 * and the player's waveform both: each drew its bars with a copy of this.
 */
internal inline fun DrawScope.drawBars(
    loudest: FloatArray,
    barPx: Float,
    lowest: Float,
    tallest: Float,
    rtl: Boolean,
    colorAt: (x: Float) -> Color,
) {
    val count = loudest.size
    val step = if (count > 1) (size.width - barPx) / (count - 1) else 0f
    for (i in 0 until count) {
        val height = lowest + loudest[i] * (tallest - lowest)
        val left = i * step
        val x = if (rtl) size.width - left - barPx else left
        drawRoundRect(
            color = colorAt(x),
            topLeft = Offset(x, (size.height - height) / 2),
            size = Size(barPx, height),
            cornerRadius = CornerRadius(barPx / 2),
        )
    }
}
