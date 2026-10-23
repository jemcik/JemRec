package com.jemcik.jemrec.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jemcik.jemrec.ui.theme.LightDark
import com.jemcik.jemrec.ui.theme.LocalLookChoice
import com.jemcik.jemrec.ui.theme.Look

internal fun lookName(look: Look): String = when (look) {
    Look.MEMO -> "Memo"
    Look.DECK -> "Deck"
    Look.MULTITRACK -> "Multitrack"
}

/** Android's own words: its dark theme guide offers "Light", "Dark" and
 *  "System default (the recommended default option)" (developer.android.com,
 *  Dark theme). "Same as phone" did not fit its button (the user,
 *  2026-10-23). */
internal fun lightDarkWord(lightDark: LightDark): String = when (lightDark) {
    LightDark.PHONE -> "System"
    LightDark.LIGHT -> "Light"
    LightDark.DARK -> "Dark"
}

/** The same in Settings' summary line, where "Deck · system" said too little. */
internal fun lightDarkSummary(lightDark: LightDark): String = when (lightDark) {
    LightDark.PHONE -> "system default"
    else -> lightDarkWord(lightDark).lowercase()
}

/** In the guide's order, the phone's own last. */
private val LIGHT_DARK = listOf(LightDark.LIGHT, LightDark.DARK, LightDark.PHONE)

/** One line each: two read as a paragraph under a button (the user,
 *  2026-10-23). */
private fun blurb(look: Look): String = when (look) {
    Look.MEMO -> "Calm and simple, one big record button"
    Look.DECK -> "A pocket recorder, an LCD and a REC key"
    Look.MULTITRACK -> "A sound editor, a colour for each caller"
}

/**
 * Settings > Theme: the home screen in the look picked, the three looks as
 * connected choices under it, then light or dark the same way - design C of
 * the canvas's "The Theme screen, three ways", the user's choice, 2026-10-23.
 * A choice repaints the app at once (Looks), this screen and its preview
 * included, so the preview is always the look and the mode picked.
 *
 * WHAT THE CARDS DID, AND WHERE EACH NOW IS. A card per look, each a picture
 * of its header, a radio, the name and a line:
 * - which look is picked, and picking one: the look's connected choices,
 *   radios to a screen reader as the cards were;
 * - what each looks like: the preview, of the picked one - drawn by the home
 *   screen's own parts, where the pictures were hand-drawn and had fallen
 *   behind; the others are a tap away, and a tap shows the whole look;
 * - the line about each: under the choices, for the picked one.
 */
@Composable
internal fun ThemeScreen(vm: MainViewModel) {
    val chosen = LocalLookChoice.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "All three work the same; only the look changes.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LookPreview(vm, Modifier.padding(vertical = 4.dp).align(Alignment.CenterHorizontally))
        ConnectedChoices(
            choices = Look.entries,
            selected = chosen.look,
            label = ::lookName,
            onChoose = vm::setLook,
        )
        Text(
            blurb(chosen.look),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        SettingsHeading("Light or dark", top = 12.dp)
        ConnectedChoices(
            choices = LIGHT_DARK,
            selected = chosen.lightDark,
            label = ::lightDarkWord,
            onChoose = vm::setLightDark,
        )
        Text(
            "Notifications and the tile always follow the phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
