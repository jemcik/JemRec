package com.jemcik.jemrec.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.jemcik.jemrec.capture.Recording
import com.jemcik.jemrec.ui.theme.DeckColors
import com.jemcik.jemrec.ui.theme.JemRecColors
import com.jemcik.jemrec.ui.theme.LocalLook
import com.jemcik.jemrec.ui.theme.Look
import com.jemcik.jemrec.ui.theme.LookFonts
import com.jemcik.jemrec.ui.theme.MultitrackColors
import com.jemcik.jemrec.ui.theme.deckColors
import com.jemcik.jemrec.ui.theme.memoColors
import com.jemcik.jemrec.ui.theme.multitrackColors
import kotlin.math.abs
import java.util.Locale

/**
 * One recording in the list, as the look draws it: Memo a card, Deck a
 * recorder's track, Multitrack an editor's lane - and in each the same three
 * lines beside the photo: the name, the details, then the waveform with the
 * length, Keep and Share at its end. Design A of the canvas's "Recordings
 * list, four ways", with player 1 of "The player, three ways" - the user's
 * choices, 2026-10-23, for one layout in all three looks and more room for
 * the name.
 *
 * WHAT THE ROW DID BEFORE, AND WHERE EACH NOW IS:
 * - a tap plays, a long press starts selecting, and while selecting a tap
 *   picks: rowTaps, the same in every look;
 * - the photo, flipping to a tick, and tapped to select: RowAvatar - now in
 *   every look and in the player too, where it used to become the play key;
 * - the name or number, with the contact's favourite star: RowName, now with
 *   the whole width of its line;
 * - direction, message, messenger app, time - each giving way in order when
 *   short of room: RowMeta, now with a phone for a phone call, and in
 *   Multitrack too, which said the kind in words and the time over the photo;
 * - a play mark by the length, a pause mark on the one playing, neither
 *   while selecting: PlayLength;
 * - Keep and Share, gone while selecting: RowActions, now at the end of the
 *   waveform's line;
 * - at least 64dp tall, selecting or not; the playing one tinted, not while
 *   selecting;
 * - the player under the one playing, with its waveform (or the slider
 *   until that is worked out) and a way to fold it: each look's player, now
 *   the card's whole width, its play key at the head of a bar with the time,
 *   Keep, Share and the fold.
 * Dropped, at the user's word: Multitrack's clips as long as their calls -
 * every clip is full width now, so none gives up room for its name.
 */
@Composable
internal fun RecordingRow(recording: Recording, day: Day, state: UiState, vm: MainViewModel) {
    when (LocalLook.current) {
        Look.MEMO -> MemoRow(recording, day, state, vm)
        Look.DECK -> DeckRow(recording, day, state, vm)
        Look.MULTITRACK -> MultitrackRow(recording, day, state, vm)
    }
}

/**
 * Whether the rows may start a selection: not on the Calls tab, which has
 * none - there a recorded call's row is its Recordings row in every other
 * way, and a long press or a tap on its photo would start a selection with
 * no toolbar to act on it.
 */
internal val LocalRowsSelectable = staticCompositionLocalOf { true }

/**
 * Whether this row is where its recording's player opens. Always, but on
 * the Calls tab, where a call that came in during another's recording (call
 * waiting) shares it: there the player opens under one of their rows (the
 * topmost), not both - each opened its own, two seek bars for one playback.
 * The other row shows the recording playing, tinted, as any current row.
 */
internal val LocalPlayerHere = staticCompositionLocalOf { true }

/** What a tap means: play - or, selecting, pick; a long press starts
 *  selecting, as Material's selection pattern has it: "On touch devices,
 *  select an item by pressing and holding it" (m1.material.io, Patterns >
 *  Selection, read 2026-10-23). Which is why a long press is not a menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.rowTaps(recording: Recording, state: UiState, vm: MainViewModel): Modifier {
    val haptics = LocalHapticFeedback.current
    if (!LocalRowsSelectable.current) {
        return clickable(onClickLabel = "Play") { haptics.tap(); vm.play(recording) }
    }
    return combinedClickable(
        onClickLabel = if (state.selecting) "Select" else "Play",
        onLongClickLabel = if (state.selecting) "Select" else "Start selecting",
        onClick = {
            if (state.selecting) {
                haptics.toggle(recording.name !in state.selected)
                vm.toggleSelected(recording)
            } else {
                haptics.tap()
                vm.play(recording)
            }
        },
        onLongClick = {
            if (state.selecting) {
                haptics.toggle(recording.name !in state.selected)
                vm.toggleSelected(recording)
            } else {
                haptics.longPress()
                vm.startSelecting(recording)
            }
        },
    )
}

/** The photo: tapped, it selects the row (the Gmail split - the photo
 *  selects, the row plays), and it turns over to a tick when picked. */
@Composable
internal fun RowAvatar(
    recording: Recording,
    state: UiState,
    vm: MainViewModel,
    container: Color = MaterialTheme.colorScheme.secondary,
    content: Color = MaterialTheme.colorScheme.onSecondary,
    edge: Color? = null,
) {
    val haptics = LocalHapticFeedback.current
    val size = RowPhoto
    if (!LocalRowsSelectable.current) {
        CallerAvatar(recording.caller, recording.callerPhoto, size, container, content, edge)
        return
    }
    val selected = recording.name in state.selected
    SelectableAvatar(recording.caller, recording.callerPhoto, selected, size, container, content, edge) {
        if (state.selecting) {
            haptics.toggle(!selected)
            vm.toggleSelected(recording)
        } else {
            haptics.longPress()
            vm.startSelecting(recording)
        }
    }
}

/** The contact's name, or the number; the address book's favourite star -
 *  read-only, not something to toggle here. */
@Composable
internal fun RowName(recording: Recording, style: TextStyle) {
    NameLine(recording.caller ?: recording.whenLabel, recording.callerFavorite, style)
}

/** A row's name line, with the favourite star: a recording's (RowName) and
 *  a call's on the Calls tab, so the two kinds, side by side there, are one
 *  line - the Calls tab's was a copy of it. */
@Composable
internal fun NameLine(label: String, favorite: Boolean, style: TextStyle) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            label,
            style = style,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (favorite) {
            Icon(
                Icons.Filled.Star,
                contentDescription = "Favourite contact",
                tint = JemRecColors.favorite,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * The line under the name, the same in every look: direction, how the call
 * came - a phone for a phone call, the messenger's own icon for one of
 * theirs, so every row says it and not only the messengers' (the user,
 * 2026-10-23) - a message's mark, and when. The time gives way first when
 * room is short (see YieldingRow), the app's name in words, where its icon
 * cannot be had, even before it. The length is not here: it ends the
 * waveform's line.
 */
@Composable
internal fun RowMeta(recording: Recording, day: Day, style: TextStyle, tint: Color) {
    // Which app, by its own icon - unless the headline already says it
    // ("Viber call").
    val app = recording.app?.takeIf { recording.caller != "$it call" }
    val appIcon = recording.parsedName?.app
        ?.takeIf { app != null }
        ?.let { rememberAppIcon(packagesForTag(it), 16.dp) }
    DetailsLine(
        style, tint,
        // No arrow where the direction is not known (a messenger call whose
        // app did not say).
        incoming = recording.incoming.takeIf { recording.directionKnown },
        phone = recording.app == null,
        appIcon = appIcon,
        appName = app,
        message = recording.message,
        // With no caller the headline already is the date.
        time = if (recording.caller != null) {
            (recording.shownAt ?: recording.startedAtMillis)?.let { timeUnder(day, it) } ?: recording.whenLabel
        } else {
            null
        },
    )
}

/**
 * A row's details line - which way, a phone or the messenger's icon, the
 * answering machine's mark, when - giving way in a set order when short of
 * room (YieldingRow): a recording's (RowMeta) and an unrecorded call's on
 * the Calls tab (CallMeta), so the two kinds, side by side there, are one
 * line - the call's was a copy in a plain row, and broke otherwise at large
 * text. [arrowTint] and [arrowWords] are the call's: red, and its kind, for
 * one nobody answered.
 */
@Composable
internal fun DetailsLine(
    style: TextStyle,
    tint: Color,
    incoming: Boolean?,
    phone: Boolean,
    time: String?,
    arrowTint: Color = tint,
    arrowWords: String? = null,
    appIcon: ImageBitmap? = null,
    appName: String? = null,
    message: Boolean = false,
) {
    YieldingRow(gap = 4.dp) {
        if (incoming != null) {
            if (arrowWords != null) {
                DirectionArrow(incoming, tint = arrowTint, description = arrowWords)
            } else {
                DirectionArrow(incoming, tint = arrowTint)
            }
        }
        if (phone) {
            Icon(Icons.Filled.Call, contentDescription = "Phone call", tint = tint, modifier = Modifier.size(14.dp))
        }
        if (appIcon != null) Image(appIcon, contentDescription = appName, modifier = Modifier.size(16.dp))
        if (message) {
            Icon(messageIcon, contentDescription = "Message", tint = tint, modifier = Modifier.size(16.dp))
        }
        if (time != null) {
            Text(
                time,
                style = style,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.layoutId(GivesWay(order = 3)),
            )
        }
        val app = appName
        if (app != null && appIcon == null) {
            Text(
                "· $app",
                style = style,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.layoutId(GivesWay(order = 1, atLeast = 40.dp)),
            )
        }
    }
}

/**
 * The length, with the play mark that says the row plays and for how long -
 * the pause mark on the one playing; neither while selecting, where a tap
 * picks the row instead.
 */
@Composable
internal fun PlayLength(recording: Recording, state: UiState, style: TextStyle, tint: Color, mark: Color = JemRecColors.play) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (!state.selecting) {
            if (state.playingName == recording.name && state.playing) {
                PauseGlyph(tint = mark, size = 13.dp)
            } else {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = mark, modifier = Modifier.size(15.dp))
            }
        }
        Text(recording.durationLabel, style = style, color = tint, maxLines = 1, softWrap = false)
    }
}

/**
 * An icon button [size] across, its ripple round. The rows' Keep and Share
 * are 36dp, under Android's 48dp touch-target advice
 * (developer.android.com, "Make apps more accessible"): the user chose the
 * room for the name over the full size (2026-10-23). The player's are 40dp,
 * Material's icon-button size.
 */
@Composable
private fun SmallIconButton(onClick: () -> Unit, size: Dp, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Keep and Share, in [tint] - Keep in [kept] once kept. Keep is the mark
 *  itself, filled when kept. Delete is not here: it lives in selection, a
 *  deliberate step. */
@Composable
internal fun RowActions(recording: Recording, vm: MainViewModel, tint: Color, kept: Color, size: Dp = 36.dp) {
    val haptics = LocalHapticFeedback.current
    Row {
        SmallIconButton({ haptics.tap(); vm.toggleKept(recording) }, size) {
            Icon(
                if (recording.kept) KeepIcons.Kept else KeepIcons.NotKept,
                contentDescription = if (recording.kept) "Kept - tap to stop keeping" else "Keep: never auto-delete",
                tint = if (recording.kept) kept else tint,
                modifier = Modifier.size(20.dp),
            )
        }
        SmallIconButton({ haptics.tap(); vm.share(recording) }, size) {
            Icon(Icons.Filled.Share, contentDescription = "Share", tint = tint, modifier = Modifier.size(20.dp))
        }
    }
}

/** Whether [recording]'s player shows under this row: it is loaded, known
 *  long, the list is not selecting - where the row's taps pick instead -
 *  and the player opens here (LocalPlayerHere). */
@Composable
private fun playerShows(recording: Recording, state: UiState) =
    LocalPlayerHere.current && !state.selecting && state.playingName == recording.name && state.durationMs > 0

/**
 * The photo, centred beside the row's lines: three of them, or - playing -
 * the name and details, with the player under both. Centred, not at the
 * top, at the user's word: the photo is the row's, not its first line's.
 */
@Composable
internal fun RowHead(modifier: Modifier, avatar: @Composable () -> Unit, gap: Dp = 12.dp, lines: @Composable ColumnScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        avatar()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp), content = lines)
    }
}

/**
 * The third line: the row's waveform across, then [length] - and, not while
 * selecting, Keep and Share. The waveform gives way to them: it is the line
 * the room was taken from, so the name and details keep all of theirs.
 */
@Composable
private fun WaveLine(
    recording: Recording,
    state: UiState,
    vm: MainViewModel,
    wave: Color,
    tint: Color,
    kept: Color,
    length: @Composable () -> Unit,
) {
    // As tall selecting as not: without Keep and Share the line would be
    // shorter, and every row would shrink - the list lurching - as
    // selection starts.
    Row(
        Modifier.fillMaxWidth().heightIn(min = ThirdLineHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val levels = rememberRowLevels(recording)
        Box(Modifier.weight(1f).height(16.dp)) {
            if (levels != null) MiniWave(levels, wave, Modifier.fillMaxSize())
        }
        length()
        if (!state.selecting) RowActions(recording, vm, tint, kept)
    }
}

/**
 * The waveform the player seeks with, or the plain slider until it is worked
 * out; [onDrag] has where a drag is, for the time shown with it.
 */
@Composable
private fun Seeker(
    state: UiState,
    vm: MainViewModel,
    dragging: Int?,
    onDrag: (Int?) -> Unit,
    played: Color,
    rest: Color,
    head: Color,
    modifier: Modifier,
) {
    val waveform = state.waveform
    if (waveform != null) {
        WaveformScrubber(
            levels = waveform,
            positionMs = dragging ?: state.positionMs,
            durationMs = state.durationMs,
            onDrag = onDrag,
            onSeek = { vm.seekTo(it) },
            modifier = modifier,
            played = played,
            rest = rest,
            head = head,
            playing = state.playing && dragging == null,
        )
    } else {
        // Where the drag is, read when it ends. A tap, and TalkBack's adjust,
        // call onValueChange and onValueChangeFinished in one go (material3
        // 1.4.0), before [dragging] - this composition's value - can change:
        // reading that, a tap never sought.
        val pending = remember { mutableStateOf<Int?>(null) }
        val drag by rememberUpdatedState(onDrag)
        // The waveform can arrive mid-drag and take the slider's place; the
        // drag then never ends here, and the row's dragging stayed set - the
        // clock frozen, the seek lost. It ends as the slider goes instead.
        DisposableEffect(Unit) {
            onDispose {
                pending.value?.let {
                    vm.seekTo(it)
                    pending.value = null
                    drag(null)
                }
            }
        }
        Slider(
            value = (dragging ?: state.positionMs).toFloat().coerceIn(0f, state.durationMs.toFloat()),
            onValueChange = {
                pending.value = it.toInt()
                onDrag(it.toInt())
            },
            onValueChangeFinished = {
                pending.value?.let { vm.seekTo(it) }
                pending.value = null
                onDrag(null)
            },
            valueRange = 0f..state.durationMs.toFloat(),
            colors = SliderDefaults.colors(thumbColor = head, activeTrackColor = played, inactiveTrackColor = rest),
            modifier = modifier,
        )
    }
}

/**
 * Play or pause, as a key of the look's own shape at the head of the
 * player's bar - Memo's rounded square, Deck's key, Multitrack's transport
 * button. Never a circle in the photo's place, as it was: the user found it
 * out of line with the photos around it (2026-10-23). Each look gives it
 * corners that follow its card's - the card's radius less the room between
 * them - and as much room below it as beside it, at the user's word.
 * [modifier] is the key's face: its shape and ground, and whatever else the
 * look's keys have - Deck's edge and lower lip (deckKey).
 */
@Composable
private fun PlayKey(
    state: UiState,
    recording: Recording,
    vm: MainViewModel,
    width: Dp,
    height: Dp,
    ink: Color,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val playing = state.playing && state.playingName == recording.name
    Box(
        modifier
            .size(width, height)
            .clickable(onClickLabel = if (playing) "Pause" else "Play") { haptics.tap(); vm.play(recording) }
            .semantics { contentDescription = if (playing) "Pause" else "Play" },
        contentAlignment = Alignment.Center,
    ) {
        // The marks in proportion to the key, so a smaller key is not a
        // crowded one.
        if (playing) {
            PauseGlyph(tint = ink, size = height * 0.5f)
        } else {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = ink, modifier = Modifier.size(height * 0.62f))
        }
    }
}

/**
 * The player's bar, under its waveform: [key] first, then [time], then
 * Keep, Share and the fold - the controls the row has, and one more.
 */
@Composable
private fun TransportBar(
    recording: Recording,
    vm: MainViewModel,
    tint: Color,
    kept: Color,
    key: @Composable () -> Unit,
    time: @Composable RowScope.() -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        key()
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, content = time)
        Row {
            RowActions(recording, vm, tint, kept, size = 40.dp)
            SmallIconButton({ haptics.tap(); vm.stopPlaying() }, 40.dp) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Collapse player", tint = tint)
            }
        }
    }
}

// THE FRAMES: each look's, a recording's row and a call's alike - on the
// Calls tab the two kinds sit in one list, and the call rows' copies of these
// had to be kept in step by hand.

/** The photo beside a row's lines, in every look and both kinds of row. */
internal val RowPhoto = 40.dp

/** A row's third line, at least: the waveform with Keep and Share, or an
 *  unrecorded call's outcome (CallRows' Outcome), so the two kinds of row
 *  are one height. */
internal val ThirdLineHeight = 36.dp

private val MemoCard = RoundedCornerShape(20.dp)

/** Memo's card, [ground] on its [edge]. */
internal fun Modifier.memoCard(ground: Color, edge: Color): Modifier =
    padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(MemoCard).background(ground).border(1.dp, edge, MemoCard)

/** Where the photo and lines sit in Memo's card - [bottom] more over a
 *  player - with [taps] over all of it. */
internal fun Modifier.memoHead(taps: Modifier = Modifier, bottom: Dp = 4.dp): Modifier =
    then(taps).padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = bottom)

/** Deck's track, on [ground] - the playing one's tint - with [taps] inside
 *  the ground and outside the padding. */
internal fun Modifier.deckTrack(ground: Color = Color.Transparent, taps: Modifier = Modifier): Modifier =
    heightIn(min = 64.dp).background(ground).then(taps).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp)

/** Multitrack's lane: [ground] - the playing one's - on the line under it. */
internal fun Modifier.multitrackLane(c: MultitrackColors, ground: Color = Color.Transparent): Modifier =
    background(ground).lineUnder(c.lane)

/** A lane's row: the photo 12dp in, then its clip; [taps] as Deck's. */
internal fun Modifier.multitrackRow(c: MultitrackColors, ground: Color = Color.Transparent, taps: Modifier = Modifier): Modifier =
    heightIn(min = 64.dp).multitrackLane(c, ground).then(taps).padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)

/** Inside a lane's clip, the name, details and third line, 9dp in. */
internal val MultitrackClipPadding = PaddingValues(start = 9.dp, end = 2.dp, top = 5.dp, bottom = 1.dp)

// MEMO: a card per recording; playing, the card holds the player.

@Composable
private fun MemoRow(recording: Recording, day: Day, state: UiState, vm: MainViewModel) {
    val c = memoColors()
    val scheme = MaterialTheme.colorScheme
    val current = !state.selecting && state.playingName == recording.name
    val shows = playerShows(recording, state)
    Column(
        Modifier.memoCard(
            ground = if (current) c.cardPlaying else c.card,
            edge = if (current) c.cardPlayingEdge else c.cardEdge,
        ),
    ) {
        RowHead(
            Modifier.memoHead(taps = Modifier.rowTaps(recording, state, vm), bottom = if (shows) 6.dp else 4.dp),
            avatar = { RowAvatar(recording, state, vm) },
        ) {
            RowName(recording, MaterialTheme.typography.bodyLarge)
            RowMeta(recording, day, MaterialTheme.typography.bodySmall, scheme.onSurfaceVariant)
            if (!shows) {
                WaveLine(recording, state, vm, c.strip, tint = scheme.secondary, kept = scheme.primary) {
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .background(c.lengthPill)
                            .padding(start = 7.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
                    ) {
                        PlayLength(
                            recording,
                            state,
                            MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            tint = c.lengthInk,
                            mark = c.lengthInk,
                        )
                    }
                }
            }
        }
        if (shows) {
            var dragging by remember { mutableStateOf<Int?>(null) }
            // The key 48x36 (it was 56x40, large beside the photo on the
            // Honor), 12dp from the card's edge below as beside it - the bar
            // is 40dp tall, the key 36 - and its corners the card's, less
            // that 12dp, so they curve round with the card's.
            Column(Modifier.padding(start = 12.dp, end = 6.dp, bottom = 10.dp)) {
                Seeker(
                    state, vm, dragging, { dragging = it },
                    played = scheme.primary, rest = scheme.secondaryContainer, head = scheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(end = 6.dp),
                )
                TransportBar(
                    recording, vm, tint = scheme.secondary, kept = scheme.primary,
                    key = {
                        PlayKey(
                            state, recording, vm, 48.dp, 36.dp,
                            ink = scheme.onPrimary,
                            modifier = Modifier.clip(RoundedCornerShape(20.dp - 12.dp)).background(scheme.primary),
                        )
                    },
                ) {
                    Text(
                        clock(dragging ?: state.positionMs) + " / " + clock(state.durationMs),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// DECK: a recorder's track; playing, one aluminium card with the LCD.

@Composable
private fun DeckRow(recording: Recording, day: Day, state: UiState, vm: MainViewModel) {
    if (playerShows(recording, state)) {
        DeckPlaying(recording, day, state, vm, deckColors())
        return
    }
    val c = deckColors()
    val scheme = MaterialTheme.colorScheme
    val current = !state.selecting && state.playingName == recording.name
    val mono = deckMono
    RowHead(
        Modifier.deckTrack(ground = if (current) c.rowPlaying else Color.Transparent, taps = Modifier.rowTaps(recording, state, vm)),
        avatar = { RowAvatar(recording, state, vm) },
    ) {
        RowName(recording, MaterialTheme.typography.bodyLarge)
        RowMeta(recording, day, mono, scheme.onSurfaceVariant)
        WaveLine(
            recording, state, vm,
            wave = if (current) c.wavePlaying else c.wave,
            tint = scheme.secondary, kept = scheme.onSurface,
        ) {
            PlayLength(recording, state, mono.copy(fontSize = 14.sp), tint = scheme.onSurface)
        }
    }
}

/**
 * A time in the LCD's dots, its colon two plain squares: Doto's own colon is
 * a pair of dotted marks that read as a dagger (the user, on the canvas,
 * 2026-10-23). Heard as the time it shows.
 */
@Composable
private fun DeckClock(ms: Int, style: TextStyle, ink: Color, modifier: Modifier = Modifier, sign: String = "") {
    val words = clock(ms)
    val minutes = words.substringBefore(':')
    val seconds = words.substringAfter(':')
    val dot = with(LocalDensity.current) { (style.fontSize * 0.18f).toDp() }
    Row(
        modifier.clearAndSetSemantics { contentDescription = sign + words },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(sign + minutes, style = style, maxLines = 1, softWrap = false)
        Column(
            Modifier.padding(horizontal = dot),
            verticalArrangement = Arrangement.spacedBy(dot * 1.6f),
        ) {
            repeat(2) { Box(Modifier.size(dot).background(ink)) }
        }
        Text(seconds, style = style, maxLines = 1, softWrap = false)
    }
}

/**
 * The track that is playing, as one piece of the recorder: its line - the
 * photo where the other tracks have theirs, the name - then the LCD across
 * the whole card, with where it is, what is left and the waveform to seek
 * in, and under it the keys: play and pause, Keep, Share and the fold. The
 * row's own small waveform and length are not drawn twice.
 */
@Composable
private fun DeckPlaying(recording: Recording, day: Day, state: UiState, vm: MainViewModel, c: DeckColors) {
    val scheme = MaterialTheme.colorScheme
    var dragging by remember { mutableStateOf<Int?>(null) }
    val position = dragging ?: state.positionMs
    val glow = deckGlow(c)
    val lcdText = TextStyle(fontFamily = LookFonts.lcd, fontWeight = FontWeight.Black, fontSize = 17.sp, color = c.ink, shadow = glow)
    val faceShape = RoundedCornerShape(18.dp)
    val lcdShape = RoundedCornerShape(10.dp)
    // Inset by 6dp; the padding makes up the rest, so the photo and the
    // buttons sit where the other tracks' do, 16dp in and 8dp from the end.
    // (The edge is drawn over the padding, not beside it.)
    Column(
        Modifier
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .fillMaxWidth()
            .clip(faceShape)
            .background(c.face)
            .border(1.dp, c.faceEdge, faceShape),
    ) {
        RowHead(
            Modifier
                .rowTaps(recording, state, vm)
                .padding(start = 10.dp, end = 2.dp, top = 8.dp, bottom = 6.dp),
            avatar = { RowAvatar(recording, state, vm) },
        ) {
            RowName(recording, MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface))
            RowMeta(recording, day, deckMono, c.label)
        }
        Column(
            Modifier
                .padding(horizontal = 10.dp)
                .fillMaxWidth()
                .clip(lcdShape)
                .background(c.lcd)
                .border(3.dp, c.bezel, lcdShape)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            // Where it is and what is left, as a recorder's counter does.
            Row(Modifier.fillMaxWidth()) {
                DeckClock(position, lcdText, c.ink, Modifier.weight(1f))
                DeckClock((state.durationMs - position).coerceAtLeast(0), lcdText, c.ink, sign = "-")
            }
            Seeker(
                state, vm, dragging, { dragging = it },
                played = c.ink, rest = c.ink.copy(alpha = 0.28f), head = c.ink,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // 10dp under the key to the face's edge, as beside it; the key's
        // corners are the face's less that 10dp.
        Box(Modifier.padding(start = 10.dp, end = 2.dp, top = 6.dp, bottom = 8.dp)) {
            TransportBar(
                // The rows' Keep and Share grey, not the face's darker icon
                // grey: the same buttons, one grey.
                recording, vm, tint = scheme.secondary, kept = scheme.onSurface,
                key = {
                    PlayKey(
                        state, recording, vm, 52.dp, 36.dp,
                        ink = c.keyMark,
                        modifier = Modifier.deckKey(c, on = true, RoundedCornerShape(18.dp - 10.dp)),
                    )
                },
            ) {}
        }
    }
}

// MULTITRACK: an editor's lane - a clip in the caller's colour - and,
// playing, the clip opened up across the lane with its transport.

/** A caller's colour: picked by name, so a caller's clips are one colour all
 *  down the list; a bare number's is the neutral one. */
internal fun hueOf(recording: Recording, c: MultitrackColors): Color = hueFor(recording.caller, c)

/** The colour for [caller], a name or a number: by name, so the Calls tab's
 *  rows wear the same colour as the caller's recordings. */
internal fun hueFor(caller: String?, c: MultitrackColors): Color {
    val name = caller?.takeIf { it.any { ch -> ch.isLetter() } } ?: return c.unknown
    return c.hues[abs(name.lowercase().hashCode() % c.hues.size)]
}

/** Deck's small engraved words: a row's details, the Calls tab's. */
internal val deckMono = TextStyle(fontFamily = LookFonts.engraved, fontSize = 13.sp)

/** Deck's LCD glow in dark - its segments lit, a negative LCD's - or none
 *  in light: the header's, the player's, Settings' bar and marks. */
internal fun deckGlow(c: DeckColors, ink: Color = c.ink, blur: Float = 14f): Shadow? =
    if (c.glow) Shadow(ink.copy(alpha = 0.55f), blurRadius = blur) else null

internal val multitrackName =
    TextStyle(fontFamily = LookFonts.grotesk, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)

internal val multitrackMono = TextStyle(fontFamily = LookFonts.mono, fontSize = 11.sp, letterSpacing = 0.04.em)

@Composable
private fun MultitrackRow(recording: Recording, day: Day, state: UiState, vm: MainViewModel) {
    val c = multitrackColors()
    val hue = hueOf(recording, c)
    if (playerShows(recording, state)) {
        MultitrackPlaying(recording, day, state, vm, c, hue)
        return
    }
    val current = !state.selecting && state.playingName == recording.name
    val clip = RoundedCornerShape(8.dp)
    RowHead(
        Modifier.multitrackRow(c, ground = if (current) c.lanePlaying else Color.Transparent, taps = Modifier.rowTaps(recording, state, vm)),
        avatar = {
            RowAvatar(recording, state, vm, container = hue.copy(alpha = c.tint), content = hue, edge = hue.copy(alpha = c.edge))
        },
        gap = 10.dp,
    ) {
        // The clip: every one the lane's whole width.
        Column(
            Modifier
                .fillMaxWidth()
                .clip(clip)
                .background(hue.copy(alpha = c.tint))
                .border(1.dp, hue.copy(alpha = c.edge), clip)
                .padding(MultitrackClipPadding),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            RowName(recording, multitrackName.copy(color = c.text))
            RowMeta(recording, day, multitrackMono, c.muted)
            WaveLine(
                recording, state, vm,
                wave = hue.copy(alpha = 0.85f),
                tint = c.control, kept = MaterialTheme.colorScheme.primary,
            ) {
                PlayLength(recording, state, multitrackMono.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold), tint = hue, mark = hue)
            }
        }
    }
}

/** Hours, minutes and seconds, as an editor's timecode: 00:01:43. */
private fun timecode(ms: Int): String {
    val s = ms / 1000
    return "%02d:%02d:%02d".format(Locale.US, s / 3600, s / 60 % 60, s % 60)
}

/**
 * The lane that is playing: its clip opened up across the lane, holding
 * the photo and the name, the waveform to seek in, and the transport - the
 * play key, the timecode, Keep, Share and the fold. The clip starts 4dp in
 * and its padding makes up the rest, so the photo inside it sits where the
 * other lanes' do, 12dp in - in whole pixels (3 and 9 rounded a pixel
 * apart at 1.5x).
 */
@Composable
private fun MultitrackPlaying(recording: Recording, day: Day, state: UiState, vm: MainViewModel, c: MultitrackColors, hue: Color) {
    var dragging by remember { mutableStateOf<Int?>(null) }
    val clip = RoundedCornerShape(8.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .multitrackLane(c, c.lanePlaying)
            .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(clip)
                .background(hue.copy(alpha = c.tint))
                .border(1.dp, hue.copy(alpha = c.edge), clip)
                // 8dp under the key to the clip's edge, as beside it.
                .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 4.dp),
        ) {
            RowHead(
                Modifier.rowTaps(recording, state, vm),
                avatar = { RowAvatar(recording, state, vm, container = c.panel, content = hue, edge = hue.copy(alpha = c.edge)) },
                gap = 10.dp,
            ) {
                RowName(recording, multitrackName.copy(color = c.text))
                RowMeta(recording, day, multitrackMono, c.muted)
            }
            Seeker(
                state, vm, dragging, { dragging = it },
                played = hue, rest = hue.copy(alpha = 0.3f), head = c.text,
                modifier = Modifier.fillMaxWidth().padding(end = 4.dp),
            )
            TransportBar(
                recording, vm, tint = c.control, kept = MaterialTheme.colorScheme.primary,
                // The clip's 8dp corners less the 8dp to its edge would leave
                // none, and a square key looked wrong (the user, 2026-10-23):
                // 4dp, never square.
                key = {
                    PlayKey(
                        state, recording, vm, 44.dp, 32.dp,
                        ink = c.keyInk,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(c.key),
                    )
                },
            ) {
                Text(
                    timecode(dragging ?: state.positionMs) + " / " + timecode(state.durationMs),
                    fontFamily = LookFonts.mono,
                    fontSize = 12.sp,
                    color = c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
