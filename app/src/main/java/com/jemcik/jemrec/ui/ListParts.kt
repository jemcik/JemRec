package com.jemcik.jemrec.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.jemcik.jemrec.minutesSeconds
import com.jemcik.jemrec.ui.theme.LocalLook
import com.jemcik.jemrec.ui.theme.Look
import com.jemcik.jemrec.ui.theme.LookFonts
import com.jemcik.jemrec.ui.theme.multitrackColors

/**
 * What time it is, for the lists' day headings. The clock, except in the
 * screen tests, which fix it so a screenshot of "Today" is the same tomorrow.
 */
internal val LocalNow = staticCompositionLocalOf<() -> Long> { System::currentTimeMillis }

/**
 * Marks a child of [YieldingRow] that gives way when the row runs short of
 * room; the lowest [order] gives way first. A [whole] one is shown whole or
 * not at all - a dot cut in half is noise, not an ellipsis - and any one is
 * dropped rather than squeezed into less than [atLeast], where an ellipsis
 * would be all that is left of it.
 */
internal data class GivesWay(val order: Int, val whole: Boolean = false, val atLeast: Dp = 0.dp)

/**
 * A line of short pieces that gives way in a set order when it runs out of
 * room: pieces not marked [GivesWay] (the arrow, the play mark, the length)
 * always get theirs; the marked ones share what is left, highest order first,
 * each cut with an ellipsis or dropped when nothing is left.
 *
 * A Row cannot say this. Its weights split the leftover in proportion rather
 * than in turn: weighted, the time was cut to "1..." to leave room for a
 * messenger's name; unweighted, it took its room before the length, which came
 * out as "12:0" - both at twice the text size, where the line cannot hold all
 * of it.
 */
@Composable
internal fun YieldingRow(gap: Dp, content: @Composable () -> Unit) {
    Layout(content) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        val loose = constraints.copy(minWidth = 0)
        val placeables = arrayOfNulls<Placeable>(measurables.size)
        var room = constraints.maxWidth
        var shownSoFar = 0
        measurables.forEachIndexed { i, piece ->
            if (piece.layoutId !is GivesWay) {
                placeables[i] = piece.measure(loose.copy(maxWidth = Constraints.Infinity))
                    .also { room -= it.width + if (shownSoFar++ > 0) gapPx else 0 }
            }
        }
        // Each piece that gives way pays for the gap beside it only when it is
        // shown: reserving gaps for pieces then dropped took room the time
        // could have used.
        measurables.indices
            .filter { measurables[it].layoutId is GivesWay }
            .sortedByDescending { (measurables[it].layoutId as GivesWay).order }
            .forEach { i ->
                val piece = measurables[i]
                val rule = piece.layoutId as GivesWay
                val available = room - if (shownSoFar > 0) gapPx else 0
                val needs = if (rule.whole) piece.maxIntrinsicWidth(constraints.maxHeight) else rule.atLeast.roundToPx()
                val fits = available > 0 && available >= needs
                placeables[i] = piece.measure(loose.copy(maxWidth = if (fits) available else 0)).also {
                    if (it.width > 0) {
                        room = available - it.width
                        shownSoFar++
                    }
                }
            }
        val shown = placeables.filterNotNull().filter { it.width > 0 }
        val width = (shown.sumOf { it.width } + gapPx * (shown.size - 1).coerceAtLeast(0))
            .coerceAtMost(constraints.maxWidth)
        val height = shown.maxOfOrNull { it.height } ?: 0
        layout(width, height) {
            var x = 0
            shown.forEach {
                it.placeRelative(x, (height - it.height) / 2)
                x += it.width + gapPx
            }
        }
    }
}

/** What a list says when its chips and search leave nothing: the
 *  Recordings tab's and the Calls tab's, [why] in a line under it. */
@Composable
internal fun NoMatches(why: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "No matches",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            why,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * A day's heading in the Recordings and Calls lists, in the look's own way
 * (LookHeading), with how many at the line's end: [count], the day's
 * recordings - or, with [calls], its calls - every one, not only the rows
 * drawn so far. Memo once had no count; the user asked for the three to say
 * the same (2026-10-23).
 */
@Composable
internal fun DayHeading(day: Day, count: Int, calls: Boolean = false) {
    // Heard with what it counts: "Today, 3" said nothing of what the 3 was.
    val said = "$count ${if (calls) "call" else "recording"}${if (count == 1) "" else "s"}"
    when (LocalLook.current) {
        Look.MEMO -> LookHeading(
            day.title, Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
            end = count.toString(), endSaid = said,
        )
        Look.DECK -> LookHeading(
            day.title, Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
            end = count.toString(), endSaid = said,
        )
        Look.MULTITRACK -> {
            // Recordings are the editor's clips; calls are calls.
            val unit = if (calls) "CALL" else "CLIP"
            LookHeading(
                day.title,
                Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
                end = if (count == 1) "1 $unit" else "$count ${unit}S",
                flag = true,
                endSaid = said,
            )
        }
    }
}

/**
 * A heading in the look's own way: Memo's words; Deck's engraved capitals on
 * a rule; Multitrack's editor's marker, in its capitals on a line, with the
 * marker's [flag] over a list of days. [end] at the line's end, heard as
 * [endSaid] where it is given. The days' headings, and Settings'
 * (SettingsHeading), so a group in Settings is headed as a day is.
 */
@Composable
internal fun LookHeading(
    title: String,
    modifier: Modifier = Modifier,
    end: String? = null,
    flag: Boolean = false,
    endSaid: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val row = Modifier
        .fillMaxWidth()
        .then(modifier)
        .semantics(mergeDescendants = true) {
            heading()
            if (endSaid != null) contentDescription = "$title, $endSaid"
        }
    when (LocalLook.current) {
        Look.MEMO -> Row(row, verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            // At the line's end, where the other looks have it.
            if (end != null) Text(end, style = MaterialTheme.typography.titleSmall, color = scheme.onSurfaceVariant)
        }
        Look.DECK -> Row(
            row,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val engraved = TextStyle(fontFamily = LookFonts.engraved, fontSize = 12.sp, letterSpacing = 0.14.em, color = scheme.onSurfaceVariant)
            Text(title.uppercase(), style = engraved)
            Box(Modifier.weight(1f).height(1.dp).background(scheme.outlineVariant))
            if (end != null) Text(end, style = engraved)
        }
        Look.MULTITRACK -> {
            val c = multitrackColors()
            Row(
                row,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val mono = TextStyle(fontFamily = LookFonts.mono, fontSize = 11.sp, letterSpacing = 0.14.em)
                // The marker's flag: an editor's, pointing down at its lanes.
                if (flag) {
                    Box(
                        Modifier.size(10.dp).drawBehind {
                            drawPath(
                                Path().apply {
                                    moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2, size.height * 0.8f); close()
                                },
                                c.marker,
                            )
                        },
                    )
                }
                Text(title.uppercase(), style = mono.copy(fontWeight = FontWeight.SemiBold), color = c.marker)
                Box(Modifier.weight(1f).height(1.dp).background(c.line))
                if (end != null) Text(end, style = mono, color = c.faint)
            }
        }
    }
}

/**
 * Which way the call went, as Material's call_made and call_received
 * icons show it.
 *
 * Neither is in material-icons-core, which ships about fifty icons rather than several
 * thousand. Both shapes are a plain arrow turned: out is up-and-right, in is
 * down-and-left. Rotating one is honest and free, where pulling in
 * material-icons-extended for two glyphs would cost thousands of vectors.
 *
 * The words it replaces were "Incoming" and "Outgoing", which is a lot of a
 * short line spent on something an arrow says at a glance - and the room is
 * wanted for who the call was with.
 */
@Composable
internal fun DirectionArrow(
    incoming: Boolean,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    description: String = if (incoming) "Incoming" else "Outgoing",
) {
    Icon(
        Icons.Filled.ArrowForward,
        contentDescription = description,
        tint = tint,
        modifier = Modifier
            .size(14.dp)
            .rotate(if (incoming) 135f else -45f),
    )
}

/**
 * A pause glyph, drawn rather than imported.
 *
 * material-icons-core has PlayArrow but not Pause, and pulling in
 * material-icons-extended - thousands of vectors - for two rectangles would be
 * a poor trade.
 */
@Composable
internal fun PauseGlyph(tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Row(
        modifier = modifier.size(size),
        horizontalArrangement = Arrangement.spacedBy(size / 6, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(2) {
            Box(
                Modifier
                    .width(size / 4)
                    .height(size * 0.75f)
                    .background(tint, RoundedCornerShape(size / 12))
            )
        }
    }
}

/** mm:ss, for the scrubber's two ends. In Latin digits in any language, as
 *  the rows' times are (SimpleDateFormat, Locale.US): the phone's own made
 *  Arabic-Indic digits in Arabic or Persian, which the looks' fonts do not
 *  have, beside Latin ones - and so for every m:ss the app draws. */
internal fun clock(ms: Int): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return minutesSeconds(total.toLong())
}
