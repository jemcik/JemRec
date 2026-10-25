package com.jemcik.jemrec.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
 * for again each time one is kept, by anyone (Waveforms.kept) - asked here,
 * by the row, where it was handed down five composables from the screen.
 * Held per recording: a row's place can come to hold
 * another (the Calls tab keeps a row per call, and a call's recording can
 * become its message), and a state kept across the change drew the last
 * one's waveform.
 */
@Composable
internal fun rememberRowLevels(recording: Recording): List<Float>? {
    LocalInventedLevels.current?.let { invented -> return remember(recording.name) { invented(recording.name) } }
    val context = LocalContext.current
    val version by Waveforms.kept.collectAsStateWithLifecycle()
    val levels by key(recording.name) {
        produceState(Waveforms.peek(recording.name), version) {
            if (value == null) value = withContext(Dispatchers.IO) { Waveforms.cached(context, recording.name) }
        }
    }
    return levels
}

/**
 * [levels] as a strip of bars, for a row: not a control - the player under
 * the row is - so nothing a screen reader visits. Bars 2dp wide, 1dp apart
 * at least; as many as fit, each its slice's loudest moment (loudestIn),
 * from 2dp up to the strip's height, drawn as the player's are (drawBars).
 */
@Composable
internal fun MiniWave(levels: List<Float>, color: Color, modifier: Modifier = Modifier) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val loudness = remember(levels) { BarLoudness(levels) }
    Canvas(modifier) {
        val barPx = max(2.dp.toPx(), 2f)
        val gapPx = max(1.dp.toPx(), 1f)
        drawBars(loudness.of(barCount(size.width, barPx, gapPx)), barPx, 2.dp.toPx(), size.height, rtl) { color }
    }
}
