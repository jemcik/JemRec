package com.jemcik.jemrec.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.jemcik.jemrec.capture.Recording
import com.jemcik.jemrec.capture.VoipApps
import com.jemcik.jemrec.capture.Waveforms
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** How much smaller than the phone's own screen the preview is drawn. */
private const val SCALE = 0.6f

/** The preview, for the tests to find: it is silent to a screen reader. */
internal const val LOOK_PREVIEW = "look-preview"

/**
 * Settings > Theme's preview: the home screen in the look the app is in,
 * drawn by the home screen's own parts - the look's header, the chips, a
 * day's heading and its rows - so it cannot fall behind the look. The
 * hand-drawn pictures before it had: pills and marks the headers had lost,
 * an LCD with the key on the wrong side (the user, 2026-10-23: "previews
 * does not correspond to how themes really look").
 *
 * Its calls are invented, as the screenshots' are: the user's own would put
 * their calls on a settings screen, and a phone with none would preview an
 * empty list.
 *
 * A picture, not a second home screen. Drawn at the phone's width and the
 * normal text size (at twice the text it would hold a header and half a
 * row), then scaled down; silent to a screen reader, which hears the look's
 * name in the choices under it; and nothing in it can be tapped or focused:
 * its buttons are the real ones, wired to the view model, and its rows are
 * calls that do not exist.
 */
@Composable
internal fun LookPreview(vm: MainViewModel, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    // As wide as the app's window, as the home screen is.
    val width = with(density) { window.width.toDp() }
    // The top of a phone's screen, 9 by 16: the header, the chips and four
    // or five rows - 640dp of the 360dp test phone. No taller than the
    // window: 9 by 16 of a window on its side was taller than the screen,
    // half of it empty, and the looks' choices two screens down.
    val height = minOf(width * 16f / 9f, with(density) { window.height.toDp() })
    val shape = RoundedCornerShape(20.dp)
    val home = remember { InventedHome() }
    Box(
        modifier
            .size(width * SCALE, height * SCALE)
            .clip(shape)
            .clearAndSetSemantics { testTag = LOOK_PREVIEW }
            .focusProperties { onEnter = { cancelFocusChange() } }
            .focusGroup(),
    ) {
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, 1f),
            LocalNow provides { home.now },
            LocalInventedLevels provides { name -> Waveforms.invented(name.hashCode().toLong()) },
        ) {
            Column(
                Modifier
                    // At the phone's size, scaled about its centre - where
                    // requiredSize leaves it in the smaller box, which it
                    // then fills.
                    .requiredSize(width, height)
                    .graphicsLayer {
                        scaleX = SCALE
                        scaleY = SCALE
                    }
                    .background(MaterialTheme.colorScheme.background)
                    // No system bars in a picture: the headers pad for them.
                    .consumeWindowInsets(BarInsets)
                    // As tall as it is; the box cuts it off.
                    .wrapContentHeight(Alignment.Top, unbounded = true),
            ) {
                HomeHeader(home.state, vm, answering = true)
                Box(Modifier.background(MaterialTheme.colorScheme.surface)) {
                    ListChipRow(home.chips, emptySet(), calls = false) {}
                }
                for ((day, rows) in home.days) {
                    DayHeading(day, rows.size)
                    for (recording in rows) RecordingRow(recording, day, home.state, vm)
                }
            }
        }
        // Over it, the edge, and what every touch lands on: the topmost thing
        // hit takes a touch from what is under it. Not consumed, so a drag
        // that starts here still scrolls the screen.
        Box(
            Modifier
                .matchParentSize()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
        )
    }
}

/**
 * The preview's home screen: recording every call, the answering machine
 * and WhatsApp on, and five calls with the screenshots' people, who do not
 * exist - three today, one yesterday and one before, by the preview's own
 * clock, 18:00 on a fixed day, so it reads the same on any day and at any
 * hour. Its calls' waveforms are seeded from their names, which hold the
 * day: from today's date, they changed every day, and the Theme screen's
 * pictures with them.
 */
private class InventedHome {
    val now: Long
    val state: UiState
    val chips: List<ListChip>
    val days: List<Pair<Day, List<Recording>>>

    init {
        val clock = Calendar.getInstance().apply {
            clear()
            set(2026, Calendar.OCTOBER, 21, 18, 0, 0)
        }
        now = clock.timeInMillis
        val date = SimpleDateFormat("yyyyMMdd", Locale.US)
        fun call(
            daysAgo: Int,
            time: String,
            incoming: Boolean,
            caller: String,
            length: String,
            app: String? = null,
            favorite: Boolean = false,
            kept: Boolean = false,
        ): Recording {
            val day = (clock.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -daysAgo) }
            return Recording(
                name = "${date.format(day.time)}_${time}_${if (incoming) "in" else "out"}${app?.let { "_$it" } ?: ""}.ogg",
                uri = Uri.EMPTY,
                whenLabel = "",
                incoming = incoming,
                durationLabel = length,
                sizeLabel = "",
                caller = caller,
                callerFavorite = favorite,
                kept = kept,
            )
        }
        val recordings = listOf(
            call(0, "160200", true, "Olivia Bennett", "4:12", favorite = true, kept = true),
            call(0, "152400", false, "Marcus Chen", "12:05", app = "whatsapp"),
            call(0, "101100", true, "+44 7700 900461", "0:47"),
            call(1, "141600", false, "Priya Raman", "2:38"),
            // Multitrack's rows are the shortest: four left the bottom bare.
            call(2, "131600", true, "Daniel Okafor", "41:02", kept = true),
        )
        val whatsApp = VoipApps.KNOWN.filter { it.tag == "whatsapp" }
        state = UiState(
            step = SetupStep.READY,
            enabled = true,
            daemonRunning = true,
            automatic = true,
            canReadCallLog = true,
            canReadContacts = true,
            canReadAudio = true,
            recordings = recordings,
            autoDeleteAfterDays = 90,
            voipInstalled = whatsApp,
            voipEnabled = whatsApp.map { it.pkg }.toSet(),
        )
        chips = chipsFor(recordings.map { it.chipFacts() }, emptySet())
        days = byDay(recordings, now) { it.startedAtMillis }
    }
}
