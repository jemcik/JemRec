package com.jemcik.jemrec.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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
     * The two colours the launcher icon is made of.
     *
     * Named here so the app and its icon cannot drift apart. They are NOT taken
     * from the scheme: the icon is a fixed thing that exists on a home screen
     * next to other icons, and it does not change with the theme - so anything
     * meant to match it must not either.
     */
    val iconWave: Color = Color(0xFF8AB4F8)
    val iconRecord: Color = Color(0xFFE8564B)

    /** Starts playback. */
    val play: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFF6FDF9B) else Color(0xFF14713C)

    /** A call with a favourite contact - the gold star convention used
     *  everywhere for favourites, a touch lighter on dark for contrast. */
    val favorite: Color
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFFFFCA28) else Color(0xFFF9A825)

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
        @Composable get() = if (isSystemInDarkTheme()) Color(0xFF7A5900) else Color(0xFFF9A825)

    /**
     * Deletes a recording, permanently.
     *
     * Just the scheme's `error` now that error is the icon's coral rather than
     * Material's crimson - so delete, the record mark and the destructive button
     * are all one red. Kept as a name so call sites read "delete", not "error",
     * where deletion is what they mean.
     */
    val delete: Color
        @Composable get() = MaterialTheme.colorScheme.error
}

@Composable
fun JemRecTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        content = content,
    )
}
