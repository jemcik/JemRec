package com.jemcik.jemrec.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

/** Each look's type for whatever is drawn in Material's styles: Memo the
 *  phone's, Deck engraved throughout, Multitrack Space Grotesk for names and
 *  JetBrains Mono for the rest - by role, never by a style's values. */
class LookTypeTest {

    private fun Typography.names() = listOf(
        displayLarge, displayMedium, displaySmall, headlineLarge, headlineMedium, headlineSmall,
        titleLarge, titleMedium, titleSmall, bodyLarge,
    )

    private fun Typography.rest() = listOf(bodyMedium, bodySmall, labelLarge, labelMedium, labelSmall)

    @Test fun memoIsThePhonesType() {
        // Material's own styles, which name the phone's sans-serif.
        assertEquals(Typography(), typographyFor(Look.MEMO))
    }

    @Test fun deckIsEngravedThroughoutWithNoSpacingAdded() {
        val deck = typographyFor(Look.DECK)
        (deck.names() + deck.rest()).forEach { style: TextStyle ->
            assertEquals(LookFonts.engraved, style.fontFamily)
            assertEquals(0.sp, style.letterSpacing)
        }
    }

    @Test fun multitrackNamesInGroteskTheRestInMono() {
        val multitrack = typographyFor(Look.MULTITRACK)
        multitrack.names().forEach { assertEquals(LookFonts.grotesk, it.fontFamily) }
        multitrack.rest().forEach { assertEquals(LookFonts.mono, it.fontFamily) }
        // Material's titleSmall and labelLarge are the same style; told
        // apart by role, the buttons still get the mono.
        assertEquals(LookFonts.grotesk, multitrack.titleSmall.fontFamily)
        assertEquals(LookFonts.mono, multitrack.labelLarge.fontFamily)
    }

    @Test fun onlyTheFamilyChangesTheSizesAreMaterials() {
        val plain = Typography()
        val multitrack = typographyFor(Look.MULTITRACK)
        assertEquals(plain.bodyLarge.fontSize, multitrack.bodyLarge.fontSize)
        assertEquals(plain.labelLarge.fontWeight, multitrack.labelLarge.fontWeight)
    }
}
