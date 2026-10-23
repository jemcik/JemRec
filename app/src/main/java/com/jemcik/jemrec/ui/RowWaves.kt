package com.jemcik.jemrec.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.jemcik.jemrec.capture.Recording
import com.jemcik.jemrec.capture.Waveforms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Levels for rows whose calls are invented - the Theme screen's preview -
 * from the name, instead of the cache: an invented call has no sound, and is
 * not to be looked for among the real recordings' waveforms. Null, the cache,
 * everywhere else.
 */
internal val LocalInventedLevels = staticCompositionLocalOf<((name: String) -> List<Float>)?> { null }

/**
 * A row's waveform: the recording's levels from the cache, or null while it
 * has none - the row then draws none, rather than a made-up shape. Looked
 * for again each time the view model works one out ([version], its
 * wavesWorkedOut).
 */
@Composable
internal fun rememberRowLevels(recording: Recording, version: Int): List<Float>? {
    LocalInventedLevels.current?.let { invented -> return remember(recording.name) { invented(recording.name) } }
    val context = LocalContext.current
    val levels by produceState(Waveforms.peek(recording.name), recording.name, version) {
        if (value == null) value = withContext(Dispatchers.IO) { Waveforms.cached(context, recording.name) }
    }
    return levels
}

/**
 * [levels] as a strip of bars, for a row: not a control - the player under
 * the row is - so nothing a screen reader visits. [bar] and [gap] wide; as
 * many bars as fit, each its slice's loudest moment (loudestIn), from
 * [lowest] up to the strip's height.
 */
@Composable
internal fun MiniWave(
    levels: List<Float>,
    color: Color,
    modifier: Modifier,
    bar: Dp = 2.dp,
    gap: Dp = 1.dp,
    lowest: Dp = 2.dp,
) {
    // Time runs the way the player's does: right to left in a right-to-left
    // language.
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(modifier) {
        val barPx = max(bar.toPx(), 2f)
        val gapPx = max(gap.toPx(), 1f)
        val count = max(1, ((size.width + gapPx) / (barPx + gapPx)).toInt())
        val step = if (count > 1) (size.width - barPx) / (count - 1) else 0f
        val low = lowest.toPx()
        for (i in 0 until count) {
            val height = low + loudestIn(levels, i, count) * (size.height - low)
            val left = i * step
            drawRoundRect(
                color = color,
                topLeft = Offset(if (rtl) size.width - left - barPx else left, (size.height - height) / 2),
                size = Size(barPx, height),
                cornerRadius = CornerRadius(barPx / 2),
            )
        }
    }
}
