package com.jemcik.jemrec.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jemcik.jemrec.capture.CallHistory
import com.jemcik.jemrec.capture.CallHistory.Kind
import com.jemcik.jemrec.capture.CallHistory.Status
import com.jemcik.jemrec.ui.theme.LocalLook
import com.jemcik.jemrec.ui.theme.Look
import com.jemcik.jemrec.ui.theme.memoColors
import com.jemcik.jemrec.ui.theme.multitrackColors
import java.util.Locale

/**
 * A call with no recording, on the Calls tab, in the look's own row - Memo's
 * card, Deck's track, Multitrack's lane - built as a recording's row is
 * (design A of the canvas's "The Calls tab, four ways", the user's choice,
 * 2026-10-23): the photo centred beside three lines, the name, the details,
 * and on the third, where a recording's waveform would be, what became of
 * the call. As tall as a recording's row, so the two kinds sit evenly in one
 * list. A call that has a recording is drawn by RecordingRow itself.
 */
@Composable
internal fun CallRow(call: CallHistory.Call, day: Day) {
    when (LocalLook.current) {
        Look.MEMO -> MemoCallRow(call, day)
        Look.DECK -> DeckCallRow(call, day)
        Look.MULTITRACK -> MultitrackCallRow(call, day)
    }
}

/** Whether nobody answered: missed, declined, blocked, voicemail - marked in
 *  the error colour. Not a call answered on another device (Kind.OTHER):
 *  "not incoming and not outgoing" took that in too, and drew it red. */
internal fun unanswered(call: CallHistory.Call) = call.kind in UNANSWERED

private val UNANSWERED = setOf(Kind.MISSED, Kind.DECLINED, Kind.BLOCKED, Kind.VOICEMAIL)

/** The details, as RowMeta's for a phone call: which way, a phone, when. */
@Composable
private fun CallMeta(call: CallHistory.Call, day: Day, style: TextStyle, tint: Color) {
    DetailsLine(
        style, tint,
        incoming = call.incoming,
        phone = true,
        time = timeUnder(day, call.at),
        arrowTint = if (unanswered(call)) MaterialTheme.colorScheme.error else tint,
        arrowWords = kindWord(call),
    )
}

/**
 * The third line: what became of the call - and its mark, where it has one:
 * a warning for what should not have happened, the not-recorded mark for a
 * call left unrecorded by a setting or a rule, the message mark for the
 * answering machine's, the bin for a recording since deleted. In the error
 * colour for a call nobody answered and for what should not have happened.
 * As tall as a recording's waveform line, Keep and Share included
 * (ThirdLineHeight).
 */
@Composable
private fun Outcome(call: CallHistory.Call, style: TextStyle, tint: Color) {
    val error = MaterialTheme.colorScheme.error
    val alarming = call.status == Status.NOT_RECORDED || call.status == Status.GREETING_FAILED
    val color = if (alarming || (call.status == Status.NOT_CONNECTED && unanswered(call))) error else tint
    Row(
        Modifier.fillMaxWidth().heightIn(min = ThirdLineHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        outcomeMark(call)?.let { Icon(it, contentDescription = null, tint = color, modifier = Modifier.size(16.dp)) }
        Text(outcomeWords(call), style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun outcomeMark(call: CallHistory.Call): ImageVector? = when (call.status) {
    Status.NOT_RECORDED, Status.GREETING_FAILED -> Icons.Filled.Warning
    Status.SKIPPED, Status.TOO_SHORT, Status.RECORDING_OFF, Status.ASK_FIRST, Status.NO_RECORDING -> CallIcons.NotRecorded
    Status.GREETING_HANGUP, Status.TEST_CALL -> messageIcon
    Status.IN_TRASH, Status.DELETED -> Icons.Filled.Delete
    Status.RECORDED, Status.PENDING, Status.NOT_CONNECTED -> null
}

/** What became of the call, in a few words, with how long it was where it
 *  connected: "3:12 · Recording was off". */
internal fun outcomeWords(call: CallHistory.Call): String =
    listOfNotNull(length(call.seconds).takeIf { call.status != Status.NOT_CONNECTED }, statusWords(call))
        .joinToString(" · ")

/** What became of a call with no recording, in a few words. */
private fun statusWords(call: CallHistory.Call): String = when (call.status) {
    Status.RECORDED -> ""
    Status.GREETING_HANGUP -> "Hung up during the greeting"
    Status.GREETING_FAILED -> "Greeting could not play"
    Status.TEST_CALL -> "Answering machine test"
    Status.IN_TRASH -> "In Recently deleted"
    Status.DELETED -> "Recording deleted"
    Status.SKIPPED -> "Left out by your rules"
    Status.TOO_SHORT -> "Too short"
    Status.PENDING -> "Recorded, not saved yet"
    Status.RECORDING_OFF -> "Recording was off"
    Status.ASK_FIRST -> "Ask first, not recorded"
    Status.NOT_RECORDED -> "Not recorded"
    Status.NO_RECORDING -> "No recording"
    Status.NOT_CONNECTED -> kindWord(call)
}

private fun kindWord(call: CallHistory.Call): String = when (call.kind) {
    Kind.INCOMING -> "Incoming"
    Kind.OUTGOING -> if (call.status == Status.NOT_CONNECTED) "No answer" else "Outgoing"
    Kind.MISSED -> "Missed"
    Kind.DECLINED -> "Declined"
    Kind.BLOCKED -> "Blocked"
    Kind.VOICEMAIL -> "Voicemail"
    Kind.OTHER -> "Answered elsewhere"
}

/** "3:21", from the call log's seconds. */
private fun length(seconds: Int): String = "%d:%02d".format(Locale.US, seconds / 60, seconds % 60)

@Composable
private fun MemoCallRow(call: CallHistory.Call, day: Day) {
    val c = memoColors()
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.memoCard(c.card, c.cardEdge)) {
        RowHead(
            modifier = Modifier.memoHead(),
            avatar = { CallerAvatar(call.label, call.photo, size = RowPhoto) },
        ) {
            NameLine(call.label, call.favorite, MaterialTheme.typography.bodyLarge)
            CallMeta(call, day, MaterialTheme.typography.bodySmall, scheme.onSurfaceVariant)
            Outcome(call, MaterialTheme.typography.bodySmall, scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DeckCallRow(call: CallHistory.Call, day: Day) {
    val scheme = MaterialTheme.colorScheme
    val mono = deckMono
    RowHead(
        modifier = Modifier.deckTrack(),
        avatar = { CallerAvatar(call.label, call.photo, size = RowPhoto) },
    ) {
        NameLine(call.label, call.favorite, MaterialTheme.typography.bodyLarge)
        CallMeta(call, day, mono, scheme.onSurfaceVariant)
        Outcome(call, mono, scheme.onSurfaceVariant)
    }
}

/**
 * Multitrack: the lane of a call with no recording holds an empty clip - an
 * outline in the caller's colour, dashed, with nothing inside: a region where
 * no audio was laid down.
 */
@Composable
private fun MultitrackCallRow(call: CallHistory.Call, day: Day) {
    val c = multitrackColors()
    val hue = hueFor(call.label, c)
    RowHead(
        modifier = Modifier.multitrackRow(c),
        avatar = {
            CallerAvatar(call.label, call.photo, size = RowPhoto, container = hue.copy(alpha = c.tint), content = hue, edge = hue.copy(alpha = c.edge))
        },
        gap = 10.dp,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    val line = 1.dp.toPx()
                    val dash = 4.dp.toPx()
                    drawRoundRect(
                        color = hue.copy(alpha = c.edge),
                        topLeft = Offset(line / 2, line / 2),
                        size = Size(size.width - line, size.height - line),
                        cornerRadius = CornerRadius(8.dp.toPx()),
                        style = Stroke(width = line, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash * 0.75f))),
                    )
                }
                .padding(MultitrackClipPadding),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            NameLine(call.label, call.favorite, multitrackName.copy(color = c.text))
            CallMeta(call, day, multitrackMono, c.muted)
            Outcome(call, multitrackMono, c.muted)
        }
    }
}
