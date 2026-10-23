package com.jemcik.jemrec.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.jemcik.jemrec.ui.theme.LocalLook
import com.jemcik.jemrec.ui.theme.Look
import com.jemcik.jemrec.ui.theme.LookFonts
import com.jemcik.jemrec.ui.theme.deckColors
import com.jemcik.jemrec.ui.theme.memoColors
import com.jemcik.jemrec.ui.theme.multitrackColors

/** The bar, for the tests to measure. */
internal const val SETTINGS_BAR = "settings-bar"

/**
 * The bar over every screen reached from Settings, in the look's own way -
 * the home header's ground, its kind of key for Back, its words - design C
 * of the canvas's "Settings, three ways" (the user's choice, 2026-10-23):
 * Memo's tinted band with Back as a disc, as its record button is; Deck's
 * face with Back as a key, as its tabs are, and the screen's name on the
 * LCD; Multitrack's panel, the name in its capitals.
 *
 * WHAT THE BAR BEFORE IT DID, AND WHERE EACH NOW IS. It was Material's
 * TopAppBar with the gradient rule under it (HeaderRule):
 * - the screen's name, on one line at any text size: each look's name,
 *   auto-sized the same way;
 * - Back, with a tap's haptic, heard as "Back": each look's Back, the
 *   haptic in [onBack] as before;
 * - room for the status bar: windowInsetsPadding, as the home headers do;
 * - the rule, a line between the bar and what scrolls under it: each bar's
 *   own edge - Memo's band ends on the page, Deck's face on its edge line,
 *   Multitrack's panel on its line;
 * - the rule's colours, the icon's blue and red: gone, as they went from
 *   home, for the look's own;
 * - the rule moving while the app is [busy]: the look's own busy signal at
 *   the bar's end, on the same busy - Memo's progress ring, Deck's segments
 *   running on its LCD, Multitrack's LEDs sweeping - its room kept when
 *   idle, so the name does not move when work starts.
 * The name is a heading to a screen reader, and heard in its own words:
 * "Settings", not Deck's and Multitrack's SETTINGS.
 */
@Composable
internal fun SettingsBar(title: String, busy: Boolean, onBack: () -> Unit) {
    when (LocalLook.current) {
        Look.MEMO -> MemoBar(title, busy, onBack)
        Look.DECK -> DeckBar(title, busy, onBack)
        Look.MULTITRACK -> MultitrackBar(title, busy, onBack)
    }
}

/** Heard as [title], a heading, whatever case it is drawn in. */
private fun Modifier.barTitle(title: String) = clearAndSetSemantics {
    heading()
    text = AnnotatedString(title)
}

@Composable
private fun MemoBar(title: String, busy: Boolean, onBack: () -> Unit) {
    val c = memoColors()
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .testTag(SETTINGS_BAR)
            .fillMaxWidth()
            .background(c.head, RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onBack) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(c.disc)
                    .border(1.dp, c.discEdge, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = scheme.onSurface, modifier = Modifier.size(20.dp))
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium,
            color = scheme.onSurface,
            maxLines = 1,
            softWrap = false,
            autoSize = oneLineAutoSize(min = 15.dp, max = 22.sp),
            modifier = Modifier.weight(1f).barTitle(title),
        )
        Box(Modifier.size(24.dp)) {
            if (busy) CircularProgressIndicator(Modifier.fillMaxSize(), color = scheme.primary, strokeWidth = 2.5.dp)
        }
    }
}

/** Six of the LCD's segments: room for the chase, kept when idle. */
private val CHASE_WIDTH = (6 * 7 + 5 * 2).dp

@Composable
private fun DeckBar(title: String, busy: Boolean, onBack: () -> Unit) {
    val c = deckColors()
    val shape = RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp)
    val glow = if (c.glow) Shadow(c.ink.copy(alpha = 0.55f), blurRadius = 14f) else null
    Row(
        Modifier
            .testTag(SETTINGS_BAR)
            .fillMaxWidth()
            // The face on its 1dp edge, as the home header's.
            .background(c.faceEdge, shape)
            .padding(bottom = 1.dp)
            .background(c.face, shape)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val keyShape = RoundedCornerShape(10.dp)
        Box(
            Modifier
                .size(48.dp)
                .clip(keyShape)
                .background(c.keyOff)
                .border(1.dp, c.keyOff, keyShape)
                .keyLip(c.glow)
                .clickable(role = Role.Button, onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = c.keyOffInk)
        }
        val lcdShape = RoundedCornerShape(8.dp)
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .clip(lcdShape)
                .background(c.lcd)
                .border(2.dp, c.bezel, lcdShape)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title.uppercase(),
                fontFamily = LookFonts.lcd,
                fontWeight = FontWeight.Black,
                color = c.ink,
                style = TextStyle(shadow = glow),
                maxLines = 1,
                softWrap = false,
                autoSize = oneLineAutoSize(min = 12.dp, max = 21.sp),
                modifier = Modifier.weight(1f).barTitle(title),
            )
            Box(Modifier.width(CHASE_WIDTH)) {
                if (busy) SegmentChase(c.ink, count = 6)
            }
        }
    }
}

/** A key's lower lip, as Deck's tabs have. */
internal fun Modifier.keyLip(dark: Boolean) = drawBehind {
    val lip = 3.dp.toPx()
    drawRect(
        Color.Black.copy(alpha = if (dark) 0.35f else 0.10f),
        topLeft = Offset(0f, size.height - lip),
        size = Size(size.width, lip),
    )
}

/** Six LEDs: room for the sweep, kept when idle. */
private val SCAN_WIDTH = (6 * 6 + 5 * 3).dp

@Composable
private fun MultitrackBar(title: String, busy: Boolean, onBack: () -> Unit) {
    val c = multitrackColors()
    Row(
        Modifier
            .testTag(SETTINGS_BAR)
            .fillMaxWidth()
            .background(c.panel)
            .drawBehind {
                drawLine(c.line, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
            }
            .windowInsetsPadding(WindowInsets.statusBars)
            .heightIn(min = 64.dp)
            .padding(start = 4.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = c.control)
        }
        Text(
            title.uppercase(),
            fontFamily = LookFonts.mono,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.08.em,
            color = c.text,
            maxLines = 1,
            softWrap = false,
            autoSize = oneLineAutoSize(min = 12.dp, max = 18.sp),
            modifier = Modifier.weight(1f).barTitle(title),
        )
        Box(Modifier.width(SCAN_WIDTH)) {
            if (busy) LedScan(c.ledOn, c.ledOff, count = 6)
        }
    }
}
