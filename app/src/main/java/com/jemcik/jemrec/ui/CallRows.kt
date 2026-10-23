package com.jemcik.jemrec.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.ui.unit.sp
import com.jemcik.jemrec.capture.CallHistory
import com.jemcik.jemrec.capture.CallHistory.Kind
import com.jemcik.jemrec.capture.CallHistory.Status
import com.jemcik.jemrec.ui.theme.JemRecColors
import com.jemcik.jemrec.ui.theme.LocalLook
import com.jemcik.jemrec.ui.theme.Look
import com.jemcik.jemrec.ui.theme.LookFonts
import com.jemcik.jemrec.ui.theme.memoColors
import com.jemcik.jemrec.ui.theme.multitrackColors

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

/** The name or number, with the contact's favourite star, as RowName. */
@Composable
private fun CallName(call: CallHistory.Call, style: TextStyle) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(call.label, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (call.favorite) {
            Icon(Icons.Filled.Star, contentDescription = "Favourite contact", tint = JemRecColors.favorite, modifier = Modifier.size(16.dp))
        }
    }
}

/** Whether nobody answered: missed, declined, blocked, voicemail - marked in
 *  the error colour, the way every phone app marks them. */
private fun unanswered(call: CallHistory.Call) = call.kind != Kind.INCOMING && call.kind != Kind.OUTGOING

/** The details, as RowMeta's for a phone call: which way, a phone, when. */
@Composable
private fun CallMeta(call: CallHistory.Call, day: Day, style: TextStyle, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        DirectionArrow(
            incoming = call.incoming,
            tint = if (unanswered(call)) MaterialTheme.colorScheme.error else tint,
            description = kindWord(call),
        )
        Icon(Icons.Filled.Call, contentDescription = "Phone call", tint = tint, modifier = Modifier.size(14.dp))
        Text(timeUnder(day, call.at), style = style, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The third line: what became of the call - and its mark, where it has one:
 * a warning for what should not have happened, the not-recorded mark for a
 * call left unrecorded by a setting or a rule, the message mark for the
 * answering machine's, the bin for a recording since deleted. In the error
 * colour for a call nobody answered and for what should not have happened.
 * As tall as a recording's waveform line, Keep and Share included.
 */
@Composable
private fun Outcome(call: CallHistory.Call, style: TextStyle, tint: Color) {
    val error = MaterialTheme.colorScheme.error
    val alarming = call.status == Status.NOT_RECORDED || call.status == Status.GREETING_FAILED
    val color = if (alarming || (call.status == Status.NOT_CONNECTED && unanswered(call))) error else tint
    Row(
        Modifier.fillMaxWidth().heightIn(min = 36.dp),
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
private fun length(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

@Composable
private fun MemoCallRow(call: CallHistory.Call, day: Day) {
    val c = memoColors()
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(c.card)
            .border(1.dp, c.cardEdge, shape),
    ) {
        RowHead(
            Modifier.padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 4.dp),
            avatar = { CallerAvatar(call.label, call.photo, size = 40.dp) },
        ) {
            CallName(call, MaterialTheme.typography.bodyLarge)
            CallMeta(call, day, MaterialTheme.typography.bodySmall, scheme.onSurfaceVariant)
            Outcome(call, MaterialTheme.typography.bodySmall, scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DeckCallRow(call: CallHistory.Call, day: Day) {
    val scheme = MaterialTheme.colorScheme
    val mono = TextStyle(fontFamily = LookFonts.engraved, fontSize = 13.sp)
    RowHead(
        Modifier
            .heightIn(min = 64.dp)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        avatar = { CallerAvatar(call.label, call.photo, size = 40.dp) },
    ) {
        CallName(call, MaterialTheme.typography.bodyLarge)
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
        Modifier
            .heightIn(min = 64.dp)
            .drawBehind {
                drawLine(c.lane, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
            }
            .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        avatar = {
            CallerAvatar(call.label, call.photo, size = 40.dp, container = hue.copy(alpha = c.tint), content = hue, edge = hue.copy(alpha = c.edge))
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
                .padding(start = 9.dp, end = 2.dp, top = 5.dp, bottom = 1.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            CallName(call, multitrackName.copy(color = c.text))
            CallMeta(call, day, multitrackMono, c.muted)
            Outcome(call, multitrackMono, c.muted)
        }
    }
}
