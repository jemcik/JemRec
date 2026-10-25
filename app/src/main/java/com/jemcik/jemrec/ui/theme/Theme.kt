package com.jemcik.jemrec.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The app's colours, in one place instead of two.
 *
 * WHAT WAS WRONG BEFORE
 *
 * Both activities called MaterialTheme with lightColorScheme() or
 * darkColorScheme() and nothing else, which is not "no theme" - it is Material
 * 3's REFERENCE theme, the violet one every screenshot in the spec uses. It is
 * meant as a starting point and reads, correctly, as a starting point: every
 * heading, every icon and every button in the app came out the same lilac.
 *
 * Defining it twice was the other half of the problem. Two activities, two
 * copies, and any change to one silently not applying to the other.
 *
 * WHAT THIS IS
 *
 * A blue scheme, with the full set of Material 3 roles filled in rather than a
 * primary swapped and the rest left violet - the neutrals and surfaces carry
 * the reference hue too, which is why changing only the primary leaves an app
 * looking faintly purple in every shadow and divider.
 *
 * Blue rather than a green or a teal on purpose: the two colours this app most
 * needs to be unmistakable are the green that starts playback and the red that
 * deletes, and a primary from either of those families would blunt one of them.
 *
 * Tertiary is teal, not the reference violet, so nothing in the app is purple.
 */

// Light
private val LightPrimary = Color(0xFF0061A4)
private val LightOnPrimary = Color(0xFFFFFFFF)
private val LightPrimaryContainer = Color(0xFFD1E4FF)
private val LightOnPrimaryContainer = Color(0xFF001D36)
private val LightSecondary = Color(0xFF535F70)
private val LightOnSecondary = Color(0xFFFFFFFF)
private val LightSecondaryContainer = Color(0xFFD7E3F7)
private val LightOnSecondaryContainer = Color(0xFF101C2B)
private val LightTertiary = Color(0xFF00696B)
private val LightOnTertiary = Color(0xFFFFFFFF)
private val LightTertiaryContainer = Color(0xFF6FF6F8)
private val LightOnTertiaryContainer = Color(0xFF002020)
// ERROR IS CORAL, NOT CRIMSON.
//
// Material's default error ramp is a pure red (#BA1A1A), a different red from
// the coral (#E8564B) the icon and the record switch use - so a red delete
// icon and a red "Start fresh" clashed with the app's own red. The error role
// is retuned to the icon's hue, so everything red in the app is one red: the
// record mark, the delete action, the destructive button, the "cannot record"
// state. Same hue throughout, only the tone shifting for contrast.
private val LightError = Color(0xFFBE3A2D)
private val LightOnError = Color(0xFFFFFFFF)
private val LightErrorContainer = Color(0xFFFBDAD4)
private val LightOnErrorContainer = Color(0xFF43110A)
private val LightBackground = Color(0xFFFDFCFF)
private val LightOnBackground = Color(0xFF1A1C1E)
private val LightSurface = Color(0xFFFDFCFF)
private val LightOnSurface = Color(0xFF1A1C1E)
private val LightSurfaceVariant = Color(0xFFDFE2EB)
private val LightOnSurfaceVariant = Color(0xFF43474E)
private val LightOutline = Color(0xFF73777F)
private val LightOutlineVariant = Color(0xFFC3C7CF)

// Dark
private val DarkPrimary = Color(0xFF9ECAFF)
private val DarkOnPrimary = Color(0xFF003258)
private val DarkPrimaryContainer = Color(0xFF00497D)
private val DarkOnPrimaryContainer = Color(0xFFD1E4FF)
private val DarkSecondary = Color(0xFFBBC7DB)
private val DarkOnSecondary = Color(0xFF253140)
private val DarkSecondaryContainer = Color(0xFF3B4858)
private val DarkOnSecondaryContainer = Color(0xFFD7E3F7)
private val DarkTertiary = Color(0xFF4CD9DB)
private val DarkOnTertiary = Color(0xFF003737)
private val DarkTertiaryContainer = Color(0xFF004F51)
private val DarkOnTertiaryContainer = Color(0xFF6FF6F8)
// Coral in dark too. error is a bright coral that reads as red rather than the
// pale salmon Material's ramp produces; errorContainer is a restrained brick,
// not the blood-crimson the default gave the "Start fresh" button.
private val DarkError = Color(0xFFFF7A6B)
private val DarkOnError = Color(0xFF5F160C)
private val DarkErrorContainer = Color(0xFF7C2E22)
private val DarkOnErrorContainer = Color(0xFFFFDBD3)
private val DarkBackground = Color(0xFF1A1C1E)
private val DarkOnBackground = Color(0xFFE2E2E6)
private val DarkSurface = Color(0xFF1A1C1E)
private val DarkOnSurface = Color(0xFFE2E2E6)
private val DarkSurfaceVariant = Color(0xFF43474E)
private val DarkOnSurfaceVariant = Color(0xFFC3C7CF)
private val DarkOutline = Color(0xFF8D9199)
private val DarkOutlineVariant = Color(0xFF43474E)

private val LightScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    // The container roles matter more than they look: Card uses them, and left
    // at their defaults every card in the app keeps the reference violet tint.
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F9FC),
    surfaceContainer = Color(0xFFF1F3F7),
    surfaceContainerHigh = Color(0xFFEBEEF1),
    surfaceContainerHighest = Color(0xFFE5E8EC),
)

private val DarkScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    surfaceContainerLowest = Color(0xFF0F1417),
    surfaceContainerLow = Color(0xFF1A1C1E),
    surfaceContainer = Color(0xFF1E2022),
    surfaceContainerHigh = Color(0xFF282A2D),
    surfaceContainerHighest = Color(0xFF333537),
)

/**
 * Two colours Material 3 has no role for, and this app needs.
 *
 * The scheme has `error`, which is why deleting was already red-ish, but it has
 * nothing that means "this starts playing" - so play was drawn in `primary`,
 * the same colour as every heading and every button, and read as decoration
 * rather than as a control.
 *
 * Material 3 accounts for exactly this: a scheme is meant to be extended with
 * custom colours where an app has a meaning the roles do not cover. Green for
 * go and red for destroy are older than Material and understood without being
 * taught, which is the entire argument for spending two colours on them.
 *
 * They are defined here, with the rest, rather than as hex literals at the call
 * site. A colour written into a composable is a colour nobody can find later.
 */
object JemRecColors {

    /**
     * The launcher icon's red (ic_launcher_foreground.xml), which Memo's
     * record button is lit in. NOT taken from the scheme: the icon is a fixed
     * thing that sits on a home screen next to other icons, and it does not
     * change with the theme - so what is meant to match it must not either.
     * A copy of the drawable's colour, named so it can be found when that
     * one changes.
     */
    val iconRecord: Color = Color(0xFFE8564B)

    /** Starts playback. */
    val play: Color
        @Composable
        @ReadOnlyComposable get() = if (LocalDark.current) Color(0xFF6FDF9B) else Color(0xFF14713C)

    /** A call with a favourite contact - the gold star convention used
     *  everywhere for favourites, a touch lighter on dark for contrast. */
    val favorite: Color
        @Composable
        @ReadOnlyComposable get() = if (LocalDark.current) Color(0xFFFFCA28) else Color(0xFFF9A825)

    /**
     * The favourite star on a SELECTED filter chip.
     *
     * A selected chip's pill is `secondary`, which is a LIGHT tone in dark
     * theme - and the bright gold above washed out against it, pale on pale. So
     * on dark theme the selected star is a deeper amber that stands off the pale
     * pill while still reading as gold. Light theme needs none of this: there
     * the selected pill is dark, so the bright gold is kept.
     */
    val favoriteSelected: Color
        @Composable
        @ReadOnlyComposable get() = if (LocalDark.current) Color(0xFF7A5900) else Color(0xFFF9A825)

    /**
     * Deletes a recording - to Recently deleted, since the trash; still the
     * destructive colour.
     *
     * Just the scheme's `error` now that error is the icon's coral rather than
     * Material's crimson - so delete, the record mark and the destructive button
     * are all one red. Kept as a name so call sites read "delete", not "error",
     * where deletion is what they mean.
     */
    val delete: Color
        @Composable
        @ReadOnlyComposable get() = MaterialTheme.colorScheme.error
}

// Deck: warm paper under a graphite-and-aluminium recorder. Primary is the
// face's graphite in light and the LCD's glow in dark, so a switch in
// Settings is the same object as the header.
private val DeckLightScheme = lightColorScheme(
    primary = Color(0xFF4A5058),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDDE5C2),
    onPrimaryContainer = Color(0xFF1E2A12),
    secondary = Color(0xFF50555C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE4E6E9),
    onSecondaryContainer = Color(0xFF1A1C1E),
    tertiary = Color(0xFF4E6B26),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDDE5C2),
    onTertiaryContainer = Color(0xFF1E2A12),
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
    background = Color(0xFFF6F5F2),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFF6F5F2),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE4E6E9),
    onSurfaceVariant = Color(0xFF50555C),
    outline = Color(0xFF7A8088),
    outlineVariant = Color(0xFFCCD0D5),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F0EC),
    surfaceContainer = Color(0xFFECEBE6),
    surfaceContainerHigh = Color(0xFFE7E6E1),
    surfaceContainerHighest = Color(0xFFE0DFDA),
)

private val DeckDarkScheme = darkColorScheme(
    primary = Color(0xFFB6F27A),
    onPrimary = Color(0xFF1E2A12),
    primaryContainer = Color(0xFF2A3A1A),
    onPrimaryContainer = Color(0xFFD6F7B0),
    secondary = Color(0xFFA3A9B0),
    onSecondary = Color(0xFF1A1C1E),
    secondaryContainer = Color(0xFF2C3137),
    onSecondaryContainer = Color(0xFFE3E5E8),
    tertiary = Color(0xFFB6F27A),
    onTertiary = Color(0xFF1E2A12),
    tertiaryContainer = Color(0xFF2A3A1A),
    onTertiaryContainer = Color(0xFFD6F7B0),
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
    background = Color(0xFF111315),
    onBackground = Color(0xFFE3E5E8),
    surface = Color(0xFF111315),
    onSurface = Color(0xFFE3E5E8),
    surfaceVariant = Color(0xFF2A2E33),
    onSurfaceVariant = Color(0xFFA3A9B0),
    outline = Color(0xFF6E747B),
    outlineVariant = Color(0xFF2C3137),
    surfaceContainerLowest = Color(0xFF0C0E10),
    surfaceContainerLow = Color(0xFF16191C),
    surfaceContainer = Color(0xFF1C1F23),
    surfaceContainerHigh = Color(0xFF23272B),
    surfaceContainerHighest = Color(0xFF2A2E33),
)

// Multitrack: a sound editor. Primary is the LED green - a switch that is on
// is lit - and the panels are white on grey in light, as an editor's lanes.
private val MultitrackLightScheme = lightColorScheme(
    primary = Color(0xFF12803F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCFEEDB),
    onPrimaryContainer = Color(0xFF002110),
    secondary = Color(0xFF555D68),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE1E4E8),
    onSecondaryContainer = Color(0xFF1B1F24),
    tertiary = Color(0xFF8A6400),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE08F),
    onTertiaryContainer = Color(0xFF2B1F00),
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
    background = Color(0xFFF3F4F6),
    onBackground = Color(0xFF1B1F24),
    surface = Color(0xFFF3F4F6),
    onSurface = Color(0xFF1B1F24),
    surfaceVariant = Color(0xFFE7EAEE),
    onSurfaceVariant = Color(0xFF555D68),
    outline = Color(0xFF8A929C),
    outlineVariant = Color(0xFFE1E4E8),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFBFC),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFE7EAEE),
)

private val MultitrackDarkScheme = darkColorScheme(
    primary = Color(0xFF3DDC84),
    onPrimary = Color(0xFF003919),
    primaryContainer = Color(0xFF1B3A28),
    onPrimaryContainer = Color(0xFFB6F5CF),
    secondary = Color(0xFFA3ABB6),
    onSecondary = Color(0xFF0E1014),
    secondaryContainer = Color(0xFF232831),
    onSecondaryContainer = Color(0xFFE6E9EE),
    tertiary = Color(0xFFF5C542),
    onTertiary = Color(0xFF3F2E00),
    tertiaryContainer = Color(0xFF5B4300),
    onTertiaryContainer = Color(0xFFFFE08F),
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
    background = Color(0xFF0E1014),
    onBackground = Color(0xFFE6E9EE),
    surface = Color(0xFF0E1014),
    onSurface = Color(0xFFE6E9EE),
    surfaceVariant = Color(0xFF1B1F26),
    onSurfaceVariant = Color(0xFFA3ABB6),
    outline = Color(0xFF5A626E),
    outlineVariant = Color(0xFF232831),
    surfaceContainerLowest = Color(0xFF0A0C10),
    surfaceContainerLow = Color(0xFF12151A),
    surfaceContainer = Color(0xFF161A20),
    surfaceContainerHigh = Color(0xFF161A20),
    surfaceContainerHighest = Color(0xFF1E232B),
)

/** Whether the app is drawn dark: the phone's mode, or the user's own choice
 *  (LightDark). Read this, never isSystemInDarkTheme(), which only knows the
 *  phone's. */
val LocalDark = staticCompositionLocalOf { false }

/** Which look is on, for the pieces each look draws its own way. */
val LocalLook = staticCompositionLocalOf { Look.MEMO }

/**
 * The look and light or dark the app is drawn in, as chosen: what Settings
 * and its Theme screen say is picked. Read from the theme, so what they say
 * is what is drawn. A copy kept in the screen's state said Memo and System
 * until the first refresh was done - seconds, with the recorder down - over
 * an app in Deck.
 */
val LocalLookChoice = staticCompositionLocalOf { LookChoice() }

internal fun schemeFor(look: Look, dark: Boolean): ColorScheme = when (look) {
    Look.MEMO -> if (dark) DarkScheme else LightScheme
    Look.DECK -> if (dark) DeckDarkScheme else DeckLightScheme
    Look.MULTITRACK -> if (dark) MultitrackDarkScheme else MultitrackLightScheme
}

/**
 * Each look's type for everything drawn in Material's styles - the screens
 * past Settings' list, the dialogs and sheets, chips, buttons, the empty
 * lists - as its own pieces have it: Memo the phone's; Deck engraved, Share
 * Tech Mono, as its keys' legends and its LCD's small print are (Doto stays
 * the LCD's word alone); Multitrack Space Grotesk for names and titles and
 * JetBrains Mono for the rest, as its lanes and its Settings rows are. Until
 * 2026-10-23 only each look's own pieces had its type, and whatever used
 * Material's styles was in the phone's font in every look: the user found
 * Deck's names and its Recordings and Calls keys in it.
 */
internal fun typographyFor(look: Look): Typography = when (look) {
    Look.MEMO -> Typography()
    // No letter spacing added: Material's suits a proportional face, and on a
    // mono one, every letter already as wide as an m, it spread names out
    // ("O l i v i a") and put "Exact call tracking" on two lines. Deck's own
    // styles never added any.
    Look.DECK -> Typography().inFamilies(names = LookFonts.engraved, rest = LookFonts.engraved, spacing = 0.sp)
    Look.MULTITRACK -> Typography().inFamilies(names = LookFonts.grotesk, rest = LookFonts.mono)
}

/** Each look's monospace, for what is set as code - the Diagnostics log, a
 *  file name's example: the phone's in Memo, Deck's engraved face,
 *  Multitrack's readouts' face. It was the phone's in every look. */
@Composable
@ReadOnlyComposable
internal fun lookMono(): FontFamily = when (LocalLook.current) {
    Look.MEMO -> FontFamily.Monospace
    Look.DECK -> LookFonts.engraved
    Look.MULTITRACK -> LookFonts.mono
}

/** [names] for the display, headline and title styles and bodyLarge - what
 *  names a thing; [rest] for the smaller body and the label styles - what
 *  is said about it, and the buttons'. By role: Material's titleSmall and
 *  labelLarge are the same style, so telling them apart by their values
 *  would put the buttons in the names' type. */
private fun Typography.inFamilies(names: FontFamily, rest: FontFamily, spacing: TextUnit = TextUnit.Unspecified): Typography {
    fun TextStyle.set(family: FontFamily) = copy(fontFamily = family, letterSpacing = if (spacing.isSpecified) spacing else letterSpacing)
    return Typography(
        displayLarge = displayLarge.set(names),
        displayMedium = displayMedium.set(names),
        displaySmall = displaySmall.set(names),
        headlineLarge = headlineLarge.set(names),
        headlineMedium = headlineMedium.set(names),
        headlineSmall = headlineSmall.set(names),
        titleLarge = titleLarge.set(names),
        titleMedium = titleMedium.set(names),
        titleSmall = titleSmall.set(names),
        bodyLarge = bodyLarge.set(names),
        bodyMedium = bodyMedium.set(rest),
        bodySmall = bodySmall.set(rest),
        labelLarge = labelLarge.set(rest),
        labelMedium = labelMedium.set(rest),
        labelSmall = labelSmall.set(rest),
    )
}

@Composable
fun JemRecTheme(choice: LookChoice = LookChoice(), content: @Composable () -> Unit) {
    // Worked out here as well as told to Android (Looks): with the phone's
    // mode isSystemInDarkTheme() is right, and with the user's own it is right
    // too once Android has applied it - this is what makes it right before.
    val dark = when (choice.lightDark) {
        LightDark.PHONE -> isSystemInDarkTheme()
        LightDark.LIGHT -> false
        LightDark.DARK -> true
    }
    CompositionLocalProvider(LocalDark provides dark, LocalLook provides choice.look, LocalLookChoice provides choice) {
        MaterialTheme(colorScheme = schemeFor(choice.look, dark), typography = typographyFor(choice.look), content = content)
    }
}
