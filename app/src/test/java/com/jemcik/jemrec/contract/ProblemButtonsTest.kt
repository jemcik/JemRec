package com.jemcik.jemrec.contract

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No problem notification's button does what its tap does: Android's guide,
 * "Don't include text actions that duplicate the behavior of tapping on the
 * notification body" (developer.android.com, Notifications). Every one did
 * until 1.2's reviews, through the one recipe (AfterCall.problem), which
 * ProblemNotificationTest holds; this holds its callers - none hands the
 * button the tap's own intent.
 */
class ProblemButtonsTest {

    /** Each `AfterCall.problem(` call in the app's code, as its arguments. */
    private fun calls(): List<Pair<String, List<String>>> {
        val calls = mutableListOf<Pair<String, List<String>>>()
        for ((file, text) in Repo.appFiles()) {
            var at = text.indexOf("AfterCall.problem(")
            while (at >= 0) {
                val open = text.indexOf('(', at)
                var depth = 0
                var end = open
                val args = mutableListOf<String>()
                var from = open + 1
                while (end < text.length) {
                    when (text[end]) {
                        '(', '{', '[' -> depth++
                        ')', '}', ']' -> {
                            depth--
                            if (depth == 0) break
                        }
                        ',' -> if (depth == 1) {
                            args += text.substring(from, end).trim()
                            from = end + 1
                        }
                    }
                    end++
                }
                text.substring(from, end).trim().takeIf { it.isNotEmpty() }?.let { args += it }
                calls += file to args
                at = text.indexOf("AfterCall.problem(", end)
            }
        }
        return calls
    }

    /** Every problem notification with a button, found - none missed, as a
     *  pattern missed RecorderHealth's (review round 9) - and in each the
     *  button's intent is not the tap's: another name, made another way. */
    @Test fun noButtonIsTheTap() {
        val withButtons = calls().filter { (_, args) -> args.size >= 5 && !args[4].startsWith("collapsed") }
        assertTrue("found too few: ${withButtons.map { it.first }}", withButtons.size >= 5)
        for ((file, args) in withButtons) {
            val tap = args[3]
            val button = Regex("""\bto\s+(\w+)""").find(args[4].removePrefix("button = "))?.groupValues?.get(1)
            assertTrue("$file: no `label to intent` in ${args[4]}", button != null)
            assertTrue("$file: the button is the tap, $tap", button != tap)
            val text = Repo.appFiles().first { it.first == file }.second
            fun made(name: String) = Regex("""val $name = ([\s\S]*?)\n\s*\n|val $name = ([^\n]*)""").find(text)?.value
            val tapMade = made(tap)
            val buttonMade = made(button!!)
            assertTrue("$file: $tap or $button not made here", tapMade != null && buttonMade != null)
            assertTrue("$file: $button made as $tap is", tapMade!!.substringAfter("=") != buttonMade!!.substringAfter("="))
        }
    }
}
