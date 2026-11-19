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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.jemcik.jemrec.recordings.Waveforms
import kotlin.math.abs
import kotlin.math.max
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
    // Each look's own: Memo's are the slider's, Deck draws in its LCD's ink,
    // Multitrack in the caller's colour.
    played: Color,
    rest: Color,
    head: Color,
    // While it plays, the playhead follows the sound, read every frame from
    // [positionNow] - or [positionMs] where that has nothing to say.
    playing: Boolean,
    modifier: Modifier = Modifier,
    positionNow: () -> Int? = { null },
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val duration by rememberUpdatedState(durationMs)
    val drag by rememberUpdatedState(onDrag)
    val seek by rememberUpdatedState(onSeek)
    // Where a drag is, for its end: read by the gestures, never drawn.
    val dragged = remember { mutableIntStateOf(NOT_DRAGGING) }
    val position = positionMs.coerceIn(0, max(0, durationMs))
    // Where the playhead is drawn: playing, PlayheadGlide's, where the sound
    // is at every frame; paused or dragged, where the player or the finger
    // is. Read only in the drawing, so a frame redraws the bars and
    // recomposes nothing. Infinite-animation frames, which the screen tests
    // hold still, so their pictures do not depend on how long a test takes.
    val glide = remember(durationMs) { PlayheadGlide(position) }
    val drawn = remember(durationMs) { mutableFloatStateOf(position.toFloat()) }
    val reading by rememberUpdatedState(position)
    val now by rememberUpdatedState(positionNow)
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
        while (true) {
            withInfiniteAnimationFrameMillis { frameMs ->
                val at = glide.frame(frameMs, now() ?: reading, durationMs)
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
 * Where to draw the playhead while it plays, frame by frame, from where the
 * sound is at that frame (SoundPosition, read from the player itself).
 *
 * It used to be worked out between readings of Media3's controller every
 * 300 ms - moved on at the player's pace from the last one, slowed when
 * ahead and hurried when behind - and every way that guess went wrong showed:
 * stepping back after Play and after a tap (the user, the Honor, 2026-10-23),
 * slowing every few seconds where ExoPlayer's position moved in steps
 * (2026-11-16), and running ahead from Play while a Bluetooth earbud took
 * 0.67 s to start the sound (2026-11-17, SoundPosition). Read every frame
 * from the player, the position needs no guessing, but its word can still
 * move on unevenly: on a Bluetooth earbud, before Media3 says when the sound
 * started, it is ExoPlayer's own, in steps of about 10 to 20 ms, and then put
 * right by 104 or 132 ms when Media3 does (the Honor, 2026-11-17); and a
 * frame reads it a few ms early or late. So:
 * - while the sound goes on, the head goes on too, each frame between
 *   [SLOWER] and [FASTER] times the sound's pace, towards where the sound
 *   is: a correction of 104 ms is taken over about a second, a tenth faster,
 *   rather than a jump or a stop (at a quarter, as first written, it took
 *   0.4 s on the earbud); and a word that moves on in steps is drawn in
 *   small ones;
 * - the word still, the head catches up with it if behind, at most a tenth
 *   faster, and otherwise waits - never a step back;
 * - a frame that comes more than [LONG_FRAME_MS] after the last - the screen
 *   held up - is not paced: the head goes where the sound is, never back;
 * - [AHEAD_SNAP_MS] or more ahead, or [BACK_MS] or more behind, the sound
 *   was sent there - a seek made elsewhere (the system's controls) - and it
 *   goes there;
 * - after a seek from the scrubber ([soughtTo]), a reading away from where it
 *   was sent is from before the player got there, and is not believed - for
 *   [STALE_MS] at most.
 * Plain arithmetic, so the rules are tested off the screen
 * (PlayheadGlideTest).
 */
internal class PlayheadGlide(start: Int) {
    var head: Float = start.toFloat()
        private set
    private var sought = NOT_DRAGGING
    private var soughtAt = NONE
    private var frameAt = NONE
    private var lastReading = start

    /** Not playing: the head is at [ms], the player's or the finger's. Away
     *  from a seek's target, the seek is over: tapped just before the end,
     *  the recording finished and went back to 0:00 before the player said
     *  where it was sent. */
    fun place(ms: Int) {
        head = ms.toFloat()
        lastReading = ms
        frameAt = NONE
        if (sought != NOT_DRAGGING && abs(ms - sought) > LANDED_MS) sought = NOT_DRAGGING
    }

    /** The scrubber sent the player to [ms]. */
    fun soughtTo(ms: Int) {
        head = ms.toFloat()
        lastReading = ms
        frameAt = NONE
        sought = ms
        soughtAt = NONE
    }

    /** A frame at [now] (milliseconds), the sound being at [reading]: where
     *  to draw the head. */
    fun frame(now: Long, reading: Int, durationMs: Int): Float {
        val elapsed = if (frameAt == NONE) 0f else (now - frameAt).toFloat()
        frameAt = now
        if (sought != NOT_DRAGGING) {
            if (soughtAt == NONE) soughtAt = now
            if (abs(reading - sought) > LANDED_MS && now - soughtAt < STALE_MS) return head
            sought = NOT_DRAGGING
        }
        val at = reading.coerceIn(0, max(0, durationMs)).toFloat()
        val goingOn = reading > lastReading
        lastReading = reading
        head = when {
            at - head >= AHEAD_SNAP_MS || head - at >= BACK_MS -> at
            // A frame that came late: the pace says nothing over it - where
            // the sound is, never back.
            elapsed > LONG_FRAME_MS -> maxOf(head, at)
            // The sound going on: so does the head, at its pace give or take
            // a tenth, towards where it is - never a short step on the way.
            goingOn -> head + (at - head).coerceIn(elapsed * SLOWER, elapsed * FASTER)
            // The word still but ahead - it moves on in steps: caught up.
            at > head -> minOf(at, head + elapsed * FASTER)
            // Still, and not ahead: waited for.
            else -> head
        }.coerceIn(0f, max(0, durationMs).toFloat())
        return head
    }

    companion object {
        /** A reading this far ahead or more is a seek, gone to at once. */
        const val AHEAD_SNAP_MS = 300f

        /** This far behind or more, likewise; less, it is waited for. */
        const val BACK_MS = 250f

        /** The most and the least of the sound's pace the head takes while
         *  it makes up a difference: a tenth either way. */
        const val FASTER = 1.1f
        const val SLOWER = 0.9f

        /** A frame later than this after the last - the screen held up -
         *  is not paced: at nine tenths of it the head could run well past
         *  a sound whose word moved on less. */
        const val LONG_FRAME_MS = 100f

        /** A seek has landed when the player says this near where it was sent:
         *  sent somewhere, it says that place until the sound goes on. */
        const val LANDED_MS = 50

        /** How long readings from before a seek landed are not believed. */
        const val STALE_MS = 1_000L
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
