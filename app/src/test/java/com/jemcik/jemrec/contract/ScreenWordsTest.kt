package com.jemcik.jemrec.contract

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The words the emulator checks wait for on the screen and in the
 * notifications - a card's title, a button, a header's line - are words the
 * app still writes. LogLinesTest holds the log lines the checks read; these
 * were held by nothing, and a reworded card showed only an hour into an
 * emulator run, as a timeout (review, 2026-11-18).
 */
class ScreenWordsTest {

    /** Android's own words the checks wait for or tap: its dialogs and its
     *  folder picker, not the app's. */
    private val androids = setOf(
        "Allow wireless debugging on this network?",
        "Always allow on this network",
        "Show roots",
        "Wait",
    )

    /** How the checks find words whole: a node of a screen's dump, its text
     *  or content description, or a notification's. */
    private val asANode = listOf(
        Regex("""on_screen\("([^"]+)"\)"""),
        Regex("""tap_until\("([^"]+)""""),
        Regex(""""([^"]+)" in sh\("dumpsys notification"""),
        Regex(""""([^"]+)" in notes"""),
        // The buttons the checks tap, and the Settings rows they open and
        // what each shows once open (review, 2026-11-18).
        Regex("""\btap\("([^"]+)""""),
        Regex("""open_settings_row\("([^"]+)""""),
        Regex("""open_settings_row\("[^"]+",\s*"([^"]+)""""),
        // And what the checks scroll to, find in a screen's dump
        // (`text="..."`), keep in a variable, or name by package.
        Regex("""scroll_to\("([^"]+)""""),
        // (A capture of a regex's own group starts with no letter.)
        Regex("""text="([A-Za-z][^"]*)""""),
        Regex("""\blabel = "([^"]+)""""),
        Regex(""""org\.telegram[^"]*": "([^"]+)""""),
    )

    /** And how they find them at the start of the header's one sentence,
     *  the content description each look's header is (headerSpeech). */
    private val inTheHeader = Regex("""header_says\("([^"]+)"""")

    private fun waitedFor(): Set<String> = found(asANode + inTheHeader)

    private fun found(patterns: List<Regex>): Set<String> {
        val checks = Repo.text("tools/emulator_checks.py")
        // An f-string's field is the check's own, and a regex's any-letter
        // stands for a letter the app writes (" . " for its " · "): the
        // words around them, each held - after a field too (review round 9).
        return patterns.flatMap { p -> p.findAll(checks).map { it.groupValues[1] } }
            .flatMap { it.split(Regex("""\{[^}]*\}| \. """)) }
            .map { it.trim() }
            .filter { it.length >= 4 }
            .toSet()
    }

    /** Words Memo's header draws but keeps from accessibility: its title,
     *  and an instruction with no tap. Its sentence says them
     *  (BehaviourTest.memosHeaderIsOneSentenceToAScreenReader,
     *  memosPlainInstructionIsNotSaidApart), and a dump of the screen is
     *  what accessibility gets. Waited for as a node of their own, the
     *  1.4 run's `looks` timed out on all four AVDs, and
     *  `notifications_off` on the 16 and 17 ones (2026-11-21). */
    private val saidOnlyInTheSentence = setOf(
        "Ready to record",
        "Starting...",
        "Checking...",
        "Cannot record",
        "Tap the button to record calls",
        "Debugging off · turn on over Wi-Fi",
        "Join any Wi-Fi to resume",
    )

    @Test fun theHeadersOwnWordsAreFoundInItsSentence() {
        val asNodes = found(asANode) intersect saidOnlyInTheSentence
        assertTrue("Waited for as a node, which the dump does not have: $asNodes - use header_says", asNodes.isEmpty())
        val stale = saidOnlyInTheSentence.filterNot { it in written }
        assertTrue("No longer written by the app: $stale", stale.isEmpty())
    }

    /** What the app writes: its string literals, those joined by `+` as one
     *  - not its code, where a word may stand as a name (review round 9). */
    private val written: String by lazy {
        val joined = Repo.app.replace(Regex(""""\s*\+\s*""""), "")
        Regex(""""((?:[^"\\\n]|\\.)*)"""").findAll(joined).joinToString("\n") { it.groupValues[1] }
    }

    @Test fun everyWordTheChecksWaitForIsStillWritten() {
        val words = waitedFor()
        assertTrue("found none: the patterns no longer match the script", words.size >= 15)
        val missing = words - androids - words.filter { it in written }.toSet()
        assertTrue("The emulator checks wait for words the app no longer writes: $missing", missing.isEmpty())
    }

    @Test fun androidsOwnWordsAreStillWaitedFor() {
        val stale = androids - waitedFor()
        assertTrue("Listed as Android's, waited for by no check any more: $stale", stale.isEmpty())
    }
}
