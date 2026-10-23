package com.jemcik.jemrec.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.jemcik.jemrec.ui.theme.JemRecColors
import com.jemcik.jemrec.ui.theme.LightDark
import com.jemcik.jemrec.ui.theme.LocalDark
import com.jemcik.jemrec.ui.theme.Look
import com.jemcik.jemrec.ui.theme.LookFonts
import com.jemcik.jemrec.ui.theme.deckColors
import com.jemcik.jemrec.ui.theme.memoColors
import com.jemcik.jemrec.ui.theme.multitrackColors

internal fun lookName(look: Look): String = when (look) {
    Look.MEMO -> "Memo"
    Look.DECK -> "Deck"
    Look.MULTITRACK -> "Multitrack"
}

internal fun lightDarkWord(lightDark: LightDark): String = when (lightDark) {
    LightDark.PHONE -> "Same as phone"
    LightDark.LIGHT -> "Light"
    LightDark.DARK -> "Dark"
}

private fun blurb(look: Look): String = when (look) {
    Look.MEMO -> "Calm and simple, one big record button."
    Look.DECK -> "A pocket recorder: an LCD screen and a REC key."
    Look.MULTITRACK -> "A sound editor: LEDs, and a colour for each caller."
}

/**
 * Settings > Theme: the three looks, each as a picture of its header, and
 * light or dark. A choice repaints the app at once (Looks), this screen
 * included - so the pictures are drawn in the mode the app is in now.
 */
@Composable
internal fun ThemeScreen(state: UiState, vm: MainViewModel) {
    val haptics = LocalHapticFeedback.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "How JemRec looks. Recording, the answering machine and every setting work the same in all three.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (look in Look.entries) {
                ThemeCard(look, selected = state.look == look) {
                    haptics.tap()
                    vm.setLook(look)
                }
            }
        }
        SettingsHeading("Light or dark", top = 12.dp)
        ConnectedChoices(
            choices = LightDark.entries,
            selected = state.lightDark,
            label = ::lightDarkWord,
            onChoose = vm::setLightDark,
        )
        Text(
            "Notifications and the Quick Settings tile always follow the phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ThemeCard(look: Look, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(scheme.surfaceContainerLowest)
            .border(2.dp, if (selected) scheme.primary else scheme.outlineVariant, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // A picture of the look, not part of what a screen reader hears:
        // the name and the line under it say it.
        Box(
            Modifier
                .fillMaxWidth()
                .height(92.dp)
                .clip(RoundedCornerShape(14.dp))
                .clearAndSetSemantics { },
        ) {
            // Pictures, 92dp tall: drawn at the normal text size whatever the
            // phone is set to, or the text would burst out of them - and with
            // no body line height, which made an 8sp mark 24sp tall.
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 1f),
                LocalTextStyle provides TextStyle.Default,
            ) {
                when (look) {
                    Look.MEMO -> MemoPicture()
                    Look.DECK -> DeckPicture()
                    Look.MULTITRACK -> MultitrackPicture()
                }
            }
        }
        Row(
            Modifier.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RadioButton(selected = selected, onClick = null)
            Column {
                Text(lookName(look), style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
                Text(blurb(look), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
    }
}


@Composable
private fun MemoPicture() {
    val c = memoColors()
    Row(
        Modifier.fillMaxSize().background(c.head),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(50.dp).clip(CircleShape).background(c.disc).border(1.dp, c.discEdge, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(32.dp).background(JemRecColors.iconRecord, CircleShape))
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Ready to record", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.ready)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (word in listOf("Every call", "Answering")) {
                    Text(
                        word,
                        fontSize = 10.sp,
                        color = c.pillInk,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(c.pill)
                            .border(1.dp, c.pillEdge, CircleShape)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DeckPicture() {
    val c = deckColors()
    Row(
        Modifier
            .fillMaxSize()
            .background(c.face)
            .border(1.dp, c.faceEdge, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .height(64.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(c.lcd)
                .border(2.dp, c.bezel, RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (mark in listOf("AUTO", "ANS", "MSG")) {
                    Text(
                        mark,
                        fontFamily = LookFonts.engraved,
                        fontSize = 8.sp,
                        color = c.ink,
                        modifier = Modifier.border(1.dp, c.ink, RoundedCornerShape(2.dp)).padding(horizontal = 2.dp),
                    )
                }
            }
            Text("REC READY", fontFamily = LookFonts.lcd, fontWeight = FontWeight.Black, fontSize = 18.sp, color = c.ink)
            Spacer(Modifier.height(1.dp))
        }
        Box(
            Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(c.keyBezel)
                .border(1.dp, c.keyEdge, CircleShape)
                .padding(4.dp),
        ) {
            Box(Modifier.fillMaxSize().domed(LitRed))
        }
    }
}

@Composable
private fun MultitrackPicture() {
    val c = multitrackColors()
    val dark = LocalDark.current
    // Two callers' clips, in the colours Multitrack's rows give them.
    val clips = if (dark) {
        listOf(Color(0xFFFF9A6B) to 0.74f, Color(0xFF4FD1B3) to 0.88f)
    } else {
        listOf(Color(0xFFA8461A) to 0.74f, Color(0xFF0B6E59) to 0.88f)
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(c.panel)
            .border(1.dp, c.line, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp, Alignment.CenterVertically),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(c.disc).border(2.dp, c.ring, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(18.dp).background(c.recOn, CircleShape))
            }
            Text(
                "ARMED",
                fontFamily = LookFonts.mono,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                letterSpacing = 0.08.em,
                color = c.armed,
                modifier = Modifier.weight(1f),
            )
            for (label in listOf("AUTO", "ANS")) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(6.dp).background(c.ledOn, CircleShape))
                    Text(label, fontFamily = LookFonts.mono, fontWeight = FontWeight.SemiBold, fontSize = 9.sp, color = c.soft)
                }
            }
        }
        for ((colour, width) in clips) {
            Box(
                Modifier
                    .fillMaxWidth(width)
                    .height(13.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(colour.copy(alpha = if (dark) 0.2f else 0.1f))
                    .border(1.dp, colour.copy(alpha = if (dark) 0.6f else 0.45f), RoundedCornerShape(4.dp)),
            )
        }
    }
}

