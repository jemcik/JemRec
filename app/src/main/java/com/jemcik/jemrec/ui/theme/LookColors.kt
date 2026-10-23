package com.jemcik.jemrec.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.jemcik.jemrec.R

/**
 * The colours each look draws its own pieces in - Deck's face and LCD, Memo's
 * record button, Multitrack's LEDs - which Material's roles have no name for.
 * Everything else (lists, Settings, dialogs) takes the look's colour scheme
 * (see Theme.kt).
 *
 * Every text colour here is at least 4.5:1 on what it sits on.
 */
@Immutable
data class DeckColors(
    /** The recorder's face: the header and the player round the LCD. */
    val face: Color,
    val faceEdge: Color,
    /** Engraved words on the face, and its icons. */
    val label: Color,
    val icon: Color,
    /** The frame round the LCD, and round the REC key. */
    val bezel: Color,
    val keyBezel: Color,
    val keyEdge: Color,
    /** The LCD lit, and with recording off (dimmed). */
    val lcd: Color,
    val ink: Color,
    val lcdOff: Color,
    val inkOff: Color,
    /** Dark only: the segments glow, a negative LCD on black. */
    val glow: Boolean,
    /** The tabs, as two keys; the lit one carries a small LED. */
    val keyOn: Color,
    val keyOnInk: Color,
    val keyOnEdge: Color,
    /** The player's play and pause key's mark: in light, the face's label
     *  grey - the tabs' near-black made the triangle heavy (the user,
     *  2026-10-23); 7.4:1 on the white key. */
    val keyMark: Color,
    val keyOff: Color,
    val keyOffInk: Color,
    val keyLed: Color,
    /** A REC key with recording off: grey instead of red. */
    val keyIdle: List<Color>,
    /** The row that is playing, and a row's waveform - darker on that row. */
    val rowPlaying: Color,
    val wave: Color,
    val wavePlaying: Color,
)

/** Light: a pale aluminium face round a lit green LCD. */
val DeckLight = DeckColors(
    face = Color(0xFFE4E6E9), faceEdge = Color(0xFFCCD0D5),
    label = Color(0xFF50565E), icon = Color(0xFF3B4046),
    bezel = Color(0xFF6E747B), keyBezel = Color(0xFFC4C9CF), keyEdge = Color(0xFFAEB4BA),
    lcd = Color(0xFFC9D6A3), ink = Color(0xFF1E2A12), lcdOff = Color(0xFFA7B092), inkOff = Color(0xFF39422C),
    glow = false,
    keyOn = Color(0xFFFFFFFF), keyOnInk = Color(0xFF1A1C1E), keyOnEdge = Color(0xFFCCD0D5), keyMark = Color(0xFF50565E),
    keyOff = Color(0xFFD5D9DE), keyOffInk = Color(0xFF3B4046), keyLed = Color(0xFF2E9E4F),
    keyIdle = listOf(Color(0xFFF4F5F6), Color(0xFFC9CDD2), Color(0xFF9DA3AA)),
    rowPlaying = Color(0xFFECEAE4), wave = Color(0xFF80878E), wavePlaying = Color(0xFF1D2125),
)

/** Dark: graphite, and the LCD negative - glowing segments on black - so the
 *  one bright thing at night is the text, not the whole display. */
val DeckDark = DeckColors(
    face = Color(0xFF1C1F23), faceEdge = Color(0xFF2C3137),
    label = Color(0xFF9AA3AD), icon = Color(0xFFC9CED4),
    bezel = Color(0xFF0E1013), keyBezel = Color(0xFF0E1013), keyEdge = Color(0xFF3A4047),
    lcd = Color(0xFF0E140B), ink = Color(0xFFB6F27A), lcdOff = Color(0xFF0B0F09), inkOff = Color(0xFF6E8E50),
    glow = true,
    keyOn = Color(0xFF343A41), keyOnInk = Color(0xFFFFFFFF), keyOnEdge = Color(0xFF343A41), keyMark = Color(0xFFFFFFFF),
    keyOff = Color(0xFF262B31), keyOffInk = Color(0xFFAEB5BD), keyLed = Color(0xFF7CF29A),
    keyIdle = listOf(Color(0xFF7A8189), Color(0xFF4A5057), Color(0xFF2A2E33)),
    rowPlaying = Color(0xFF1C1F23), wave = Color(0xFF7D848B), wavePlaying = Color(0xFFE3E5E8),
)

@Immutable
data class MemoColors(
    /** The header's ground, and the big button's disc on it. */
    val head: Color,
    val disc: Color,
    val discEdge: Color,
    /** The pills that name what is on. */
    val pill: Color,
    val pillEdge: Color,
    val pillInk: Color,
    /** The state, in words: ready, recording or cannot, off. */
    val ready: Color,
    val alarm: Color,
    val idle: Color,
    /** The off button's empty ring. */
    val offRing: Color,
    /** The two tabs' track, the chosen one's segment, and its count. */
    val track: Color,
    val segment: Color,
    val badge: Color,
    val badgeInk: Color,
    /** A recording's card, and the playing one's; the waveform strip on a
     *  card; the length's pill, in the play green. */
    val card: Color,
    val cardEdge: Color,
    val cardPlaying: Color,
    val cardPlayingEdge: Color,
    val strip: Color,
    val lengthPill: Color,
    val lengthInk: Color,
)

val MemoLight = MemoColors(
    head = Color(0xFFEEF2F8), disc = Color(0xFFFFFFFF), discEdge = Color(0xFFDCE1E9),
    pill = Color(0xFFFFFFFF), pillEdge = Color(0xFFDCE1E9), pillInk = Color(0xFF43474E),
    ready = Color(0xFF1E6B34), alarm = Color(0xFFBE3A2D), idle = Color(0xFF43474E), offRing = Color(0xFFC3C7CF),
    track = Color(0xFFE6EAF1), segment = Color(0xFFFFFFFF), badge = Color(0xFFD1E4FF), badgeInk = Color(0xFF001D36),
    card = Color(0xFFFFFFFF), cardEdge = Color(0xFFE3E6EC), cardPlaying = Color(0xFFE8F1FF), cardPlayingEdge = Color(0xFFC9DCF7),
    strip = Color(0xFFB6C2D2), lengthPill = Color(0xFFE3F1E6), lengthInk = Color(0xFF1B5E2E),
)

val MemoDark = MemoColors(
    head = Color(0xFF1E2227), disc = Color(0xFF2A2E34), discEdge = Color(0xFF3A3F46),
    pill = Color(0xFF2A2E34), pillEdge = Color(0xFF3A3F46), pillInk = Color(0xFFC3C7CF),
    ready = Color(0xFF6FDF9B), alarm = Color(0xFFFF7A6B), idle = Color(0xFFC3C7CF), offRing = Color(0xFF6B7078),
    track = Color(0xFF2A2E34), segment = Color(0xFF3B4048), badge = Color(0xFF00497D), badgeInk = Color(0xFFD1E4FF),
    card = Color(0xFF1B1E22), cardEdge = Color(0xFF2A2E34), cardPlaying = Color(0xFF12304D), cardPlayingEdge = Color(0xFF21507A),
    strip = Color(0xFF4A5566), lengthPill = Color(0xFF173D26), lengthInk = Color(0xFF8FE0AE),
)

@Immutable
data class MultitrackColors(
    /** The panels (header, tabs) and the lines between lanes. */
    val panel: Color,
    val line: Color,
    val text: Color,
    val soft: Color,
    val muted: Color,
    val faint: Color,
    /** The record button: its ring and disc, the red lit, the red dark. */
    val ring: Color,
    val disc: Color,
    val recOn: Color,
    val recOff: Color,
    /** The state word: armed, recording, off, no link. */
    val armed: Color,
    val rec: Color,
    val off: Color,
    val cannot: Color,
    /** A setting's LED, lit and dark. */
    val ledOn: Color,
    val ledOff: Color,
    /** The day markers. */
    val marker: Color,
    /** The line under a lane, and the playing lane's ground. */
    val lane: Color,
    val lanePlaying: Color,
    /**
     * The transport's pause key, and the other buttons - Keep, Share, the
     * fold, the header's gear. In light they were the text's near-black and
     * looked heavy
     * (the user, 2026-10-23); softened to the most that keeps the key's mark
     * 7.2:1 and the buttons 4.4:1 on every caller's clip (3:1 is WCAG's
     * least for a control).
     */
    val key: Color,
    val keyInk: Color,
    val control: Color,
    /**
     * Each caller's colour, picked by their name, so a caller's clips are
     * one colour down the list; a number with no name is [unknown]. In light
     * the colours are darkened until the text drawn in them is 4.5:1 on the
     * clip's tint. A clip's ground is its colour at [tint], its edge at [edge].
     */
    val hues: List<Color>,
    val unknown: Color,
    val tint: Float,
    val edge: Float,
)

/** Light: a sound editor in daylight - white panels, the same LEDs. */
val MultitrackLight = MultitrackColors(
    panel = Color(0xFFFFFFFF), line = Color(0xFFE1E4E8),
    text = Color(0xFF1B1F24), soft = Color(0xFF3A4049), muted = Color(0xFF555D68), faint = Color(0xFF626A76),
    ring = Color(0xFFC9CED6), disc = Color(0xFFF3F4F6), recOn = Color(0xFFE8463B), recOff = Color(0xFFE3CFCC),
    armed = Color(0xFF128A45), rec = Color(0xFFC8372D), off = Color(0xFF555D68), cannot = Color(0xFF8A6400),
    ledOn = Color(0xFF18A957), ledOff = Color(0xFFCDD2D9),
    marker = Color(0xFF8A6400),
    lane = Color(0xFFE7EAEE), lanePlaying = Color(0xFFEAF0F7), key = Color(0xFF505864), keyInk = Color(0xFFFFFFFF),
    control = Color(0xFF5E6672),
    hues = listOf(
        Color(0xFFA8461A), Color(0xFF0B6E59), Color(0xFF2460C4), Color(0xFF7A5806), Color(0xFFA53466), Color(0xFF436A12),
    ),
    unknown = Color(0xFF5A626E), tint = 0.10f, edge = 0.45f,
)

val MultitrackDark = MultitrackColors(
    panel = Color(0xFF161A20), line = Color(0xFF232831),
    text = Color(0xFFE6E9EE), soft = Color(0xFFC9CED6), muted = Color(0xFFA3ABB6), faint = Color(0xFF8B939E),
    ring = Color(0xFF3A404A), disc = Color(0xFF1B1F26), recOn = Color(0xFFFF4D42), recOff = Color(0xFF4A2C2A),
    armed = Color(0xFF7CF29A), rec = Color(0xFFFF6A5F), off = Color(0xFFA3ABB6), cannot = Color(0xFFF5C542),
    ledOn = Color(0xFF3DDC84), ledOff = Color(0xFF2A3038),
    marker = Color(0xFFF5C542),
    lane = Color(0xFF1B1F26), lanePlaying = Color(0xFF12161C), key = Color(0xFFE6E9EE), keyInk = Color(0xFF0E1014),
    control = Color(0xFFC9CED6),
    hues = listOf(
        Color(0xFFFF9A6B), Color(0xFF4FD1B3), Color(0xFF7DB2FF), Color(0xFFF2C14E), Color(0xFFF48FB1), Color(0xFFA5D66F),
    ),
    unknown = Color(0xFF9AA3AF), tint = 0.14f, edge = 0.55f,
)

@Composable
fun deckColors(): DeckColors = if (LocalDark.current) DeckDark else DeckLight

@Composable
fun memoColors(): MemoColors = if (LocalDark.current) MemoDark else MemoLight

@Composable
fun multitrackColors(): MultitrackColors = if (LocalDark.current) MultitrackDark else MultitrackLight

/**
 * The looks' own typefaces: Doto for Deck's LCD, Share Tech Mono for the
 * words engraved on its face, JetBrains Mono and Space Grotesk for
 * Multitrack. Memo keeps the phone's own.
 *
 * Bundled in res/font, unmodified, from github.com/google/fonts (2026-10-23),
 * all under the SIL Open Font License - its text travels in the APK, in
 * assets/licenses, as the licence asks (see NOTICE.md). Bundled rather than
 * fetched: Android's downloadable fonts come from Google Play services, which
 * not every phone F-Droid serves has.
 *
 * Three are variable fonts, one file for every weight: each weight used here
 * names its point on the weight axis.
 */
object LookFonts {
    val lcd: FontFamily = variable(R.font.doto, FontWeight.Bold, FontWeight.Black)
    val engraved: FontFamily = FontFamily(Font(R.font.share_tech_mono))
    val mono: FontFamily = variable(
        R.font.jetbrains_mono, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold,
    )
    val grotesk: FontFamily = variable(
        R.font.space_grotesk, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold,
    )

    private fun variable(res: Int, vararg weights: FontWeight): FontFamily = FontFamily(
        weights.map { weight ->
            Font(res, weight = weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))
        },
    )
}
