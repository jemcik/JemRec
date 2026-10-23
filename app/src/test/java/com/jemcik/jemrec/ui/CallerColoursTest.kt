package com.jemcik.jemrec.ui

import com.jemcik.jemrec.ui.theme.MultitrackDark
import com.jemcik.jemrec.ui.theme.MultitrackLight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Multitrack's caller colours: one each, kept, as few shared as can be;
 *  and what is kept says nothing of who. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class CallerColoursTest {

    private val six = listOf("Olivia Bennett", "Marcus Chen", "Priya Raman", "Hana Sato", "Daniel Okafor", "Sofia Marchetti")

    @Test fun sixNewCallersAreSixColours() {
        val colours = CallerColours.assign(emptyMap(), six)
        assertEquals(6, six.map { colours[CallerColours.keyOf(it)] }.toSet().size)
    }

    @Test fun theTwoTheHashAloneMadeBlueAreTwoColours() {
        // The user's two callers shared a colour by the name's hash alone,
        // as these two demo names do.
        assertEquals(CallerColours.legacyIndex("Marcus Chen"), CallerColours.legacyIndex("Hana Sato"))
        val colours = CallerColours.assign(emptyMap(), listOf("Marcus Chen", "Hana Sato"))
        assertTrue(colours[CallerColours.keyOf("Marcus Chen")] != colours[CallerColours.keyOf("Hana Sato")])
    }

    @Test fun aCallerKeepsTheirColour() {
        val first = CallerColours.assign(emptyMap(), six)
        val later = CallerColours.assign(first, listOf("Zoe Kim") + six.reversed())
        six.forEach { assertEquals(it, first[CallerColours.keyOf(it)], later[CallerColours.keyOf(it)]) }
    }

    @Test fun aNewCallerGetsTheColourFewestHave() {
        // Five callers, colours 0..4 taken: the sixth gets 5, whatever its
        // name's hash would say.
        val five = six.take(5)
        val known = five.mapIndexed { i, name -> CallerColours.keyOf(name) to i }.toMap()
        val colours = CallerColours.assign(known, five + "Zoe Kim")
        assertEquals(5, colours[CallerColours.keyOf("Zoe Kim")])
    }

    @Test fun callersNoLongerInTheListsDoNotCount() {
        // Colour 0 was a caller's long gone: it is free again for a new one
        // whose name's hash picks it.
        val gone = "Someone Long Gone"
        val name = generateSequence(1) { it + 1 }.map { "Caller $it" }.first { CallerColours.legacyIndex(it) == 0 }
        val known = mapOf(CallerColours.keyOf(gone) to 0)
        assertEquals(0, CallerColours.assign(known, listOf(name))[CallerColours.keyOf(name)])
    }

    @Test fun mostCallsFirstThenTheLatestAndNoBareNumbers() {
        val calls = listOf("Ann" to 10L, "Bob" to 30L, "Ann" to 5L, "+44 7700 900461" to 40L, "Cy" to 20L, null to 50L)
        assertEquals(listOf("Ann", "Bob", "Cy"), CallerColours.ranked(calls))
    }

    @Test fun whatIsKeptSaysNothingOfWho() {
        val key = CallerColours.keyOf("Olivia Bennett")
        assertEquals(12, key.length)
        assertTrue(key.all { it in "0123456789abcdef" })
        assertFalse(key.contains("olivia", ignoreCase = true))
        assertEquals(key, CallerColours.keyOf("OLIVIA BENNETT"))
    }

    @Test fun bothPalettesHaveAsManyColoursAsAreGiven() {
        assertEquals(CallerColours.HUES, MultitrackLight.hues.size)
        assertEquals(CallerColours.HUES, MultitrackDark.hues.size)
    }

    @Test fun theColoursAreKeptFromOneRefreshToTheNext() {
        val app = RuntimeEnvironment.getApplication()
        val first = CallerColours.update(app, six.map { it to 0L })
        val again = CallerColours.update(app, (listOf("Zoe Kim") + six).map { it to 0L })
        six.forEach { assertEquals(first[CallerColours.keyOf(it)], again[CallerColours.keyOf(it)]) }
        assertEquals(7, again.size)
    }
}
