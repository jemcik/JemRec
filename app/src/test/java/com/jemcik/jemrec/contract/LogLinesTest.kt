package com.jemcik.jemrec.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The log lines tools/emulator_checks.py waits for, still written by the code.
 *
 * The checks follow a call, a revive or a cable through the app's and the
 * recorder's own lines (`wait_log(...)`, `"..." in log()`, the revive log, the
 * Copy a report text). A line reworded, or its log call lost in a move, fails
 * them only at the end of a release's emulator run, as a timeout an hour in.
 * The 1.0 restructuring moves much of the code that writes them, so this asks
 * the same question on every build, in a second.
 *
 * Every literal run of six characters or more in what the checks match - a
 * `wait_log` or `re.search` pattern on our text, or a `"..." in` it - must be
 * in the sources as it is, or be listed in [BUILT] with the pieces the code
 * builds it from, each of which must be in the sources. A check that matches
 * a new line that is neither fails here until the line is listed.
 */
class LogLinesTest {

    @Test
    fun everyLineTheChecksMatchIsStillWritten() {
        val missing = matched().filter { it !in Repo.sources && it !in BUILT }
        assertTrue(
            "The emulator checks match text no source writes as it is, and BUILT does not say how it is built:\n" +
                missing.joinToString("\n"),
            missing.isEmpty(),
        )
    }

    @Test
    fun everyPieceABuiltLineIsMadeOfIsStillInTheSources() {
        val missing = BUILT.flatMap { (line, pieces) -> pieces.filter { it !in Repo.sources }.map { "$line <- $it" } }
        assertTrue("A built line lost a piece:\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    @Test
    fun aPatternsRunsAreTheTextItMatchesAsItIs() {
        assertEquals(
            listOf("save: wrote ", " packets, ", "s from "),
            literalRuns("""save: wrote \d+ packets, ([\d.]+)s from \S+""").filter { it.isNotEmpty() },
        )
        // A group's alternatives apart; an escaped bracket is a bracket.
        assertEquals(
            listOf("revive: recorder ", "up", "down", " (connect ", ")"),
            literalRuns("""revive: recorder (?:up|down) \(connect \w+\)""").filter { it.isNotEmpty() },
        )
        // What need not be there is not asked for: a quantified letter, a
        // lookahead's contents, a counted repeat; an f-string field is text
        // the check fills in.
        assertEquals(listOf("call", " ended"), literalRuns("""calls? ended""").filter { it.isNotEmpty() })
        assertEquals(listOf("save: wrote"), literalRuns("""save: wrote(?! 0 packets)""").filter { it.isNotEmpty() })
        assertEquals(listOf("track", " on"), literalRuns("""tracks{0,1} on""").filter { it.isNotEmpty() })
        assertEquals(listOf("diag[", "] <<<"), literalRuns("""diag\[{op}\] <<<""").filter { it.isNotEmpty() })
        // A group's name, an inline flag, a back-reference: none is text.
        assertEquals(
            listOf("save: wrote ", " packets", " again"),
            literalRuns("""save: wrote (?P<n>\d+) packets(?i)(?P=n)(?i: again)""").filter { it.isNotEmpty() },
        )
        // A name that never closes ends the pattern rather than the scan's progress.
        assertEquals(listOf("save: wrote "), literalRuns("""save: wrote (?P<n""").filter { it.isNotEmpty() })
    }

    @Test
    fun everyBuiltLineIsStillOneTheChecksMatch() {
        val stale = BUILT.keys - matched().toSet()
        assertTrue("Listed in BUILT, matched by no check any more: $stale", stale.isEmpty())
    }

    private companion object {
        /** A Python string literal, with any r/f prefix: its text in group 1 or
         *  2. Unrolled (`a*(?:\\.a*)*`), as `(?:a|\\.)*` recurses once per
         *  character in java.util.regex and overflows the stack on this file. */
        const val STR = """(?:rf|fr|r|f)?(?:"([^"\\]*(?:\\.[^"\\]*)*)"|'([^'\\]*(?:\\.[^'\\]*)*)')"""

        /** What the checks match against that the app or the recorder wrote:
         *  logcat, the revive log, the recorder's log, the call report, the
         *  debug receiver's answers. The rest (`sh(...)` of dumpsys,
         *  uiautomator dumps, content queries) is Android's own text. */
        const val OURS = """(?:log\(\)|revive_since\(|revive_log\(|sh\(daemon_log\)|""" +
            """sh\("cat /data/local/tmp/jemrec-capture|(?:log_text|text|since|seen|said|report|after|line|l)\b)"""

        /** The literal text of every match on our own lines: each literal run
         *  of a regex, and for `"..." in`, the text before any f-string field.
         *  Shorter than six characters is too little to say anything. */
        fun matched(): List<String> {
            val checks = Repo.text("tools/emulator_checks.py")
            val regexes = (
                Regex("""wait_log\(\s*$STR""").findAll(checks) +
                    Regex("""re\.(?:search|findall|match|finditer)\(\s*$STR\s*,\s*$OURS""").findAll(checks)
                ).flatMap { literalRuns(it.text()) }
            val substrings = Regex("""$STR\s+(?:not\s+)?in\s+$OURS""").findAll(checks)
                .map { it.text().substringBefore('{') }
            return (regexes + substrings).map { it.trim() }.filter { it.length >= 6 }.distinct().toList()
        }

        fun MatchResult.text() = groupValues[1].ifEmpty { groupValues[2] }

        /**
         * The runs of text a regex matches literally, split wherever it
         * matches anything else: a class, a group's edge, an alternative, an
         * escape like `\d`, an f-string's field. A quantified character -
         * `*`, `+`, `?` or a count like `{0,1}` - is dropped from its run,
         * since the line need not have it; a lookaround is skipped whole, as
         * it says what is next to the line, not what is in it; an escaped
         * special character is the character.
         */
        fun literalRuns(regex: String): List<String> {
            val runs = mutableListOf<String>()
            val run = StringBuilder()
            fun cut() {
                runs += run.toString()
                run.clear()
            }
            /** Past the group opening at [open], nested groups and escapes included. */
            fun pastGroup(open: Int): Int {
                var depth = 0
                var j = open
                while (j < regex.length) {
                    when (regex[j]) {
                        '\\' -> j++
                        '(' -> depth++
                        ')' -> if (--depth == 0) return j + 1
                    }
                    j++
                }
                return regex.length
            }
            val count = Regex("""\{\d+(,\d*)?}""")
            var i = 0
            while (i < regex.length) {
                val c = regex[i]
                when {
                    c == '\\' && i + 1 < regex.length -> {
                        if (regex[i + 1] in "().[]{}*+?|^$\\ ") run.append(regex[i + 1]) else cut()
                        i += 2
                    }
                    c == '(' && listOf("?=", "?!", "?<=", "?<!", "?P=", "?#").any { regex.startsWith(it, i + 1) } -> {
                        // A lookaround, a back-reference or a comment: no text of its own.
                        cut()
                        i = pastGroup(i)
                    }
                    c == '(' && regex.startsWith("?", i + 1) -> {
                        // A group's opening that is not text: (?:, (?P<name>, and inline
                        // flags - (?i) alone, or (?i: opening a group.
                        cut()
                        val flags = Regex("""\?[aiLmsux-]*[):]""")
                        i = when {
                            regex.startsWith("?P<", i + 1) -> regex.indexOf('>', i).let { if (it < 0) regex.length else it + 1 }
                            flags.matchesAt(regex, i + 1) -> regex.indexOfAny(charArrayOf(')', ':'), i) + 1
                            else -> i + 1
                        }
                    }
                    c == '{' && count.matchesAt(regex, i) -> {
                        if (run.isNotEmpty()) run.setLength(run.length - 1)
                        cut()
                        i = regex.indexOf('}', i) + 1
                    }
                    c == '[' || c == '{' -> {
                        cut()
                        val end = regex.indexOf(if (c == '[') ']' else '}', i + 1)
                        i = if (end < 0) regex.length else end + 1
                    }
                    c in "*+?" -> {
                        if (run.isNotEmpty()) run.setLength(run.length - 1)
                        cut()
                        i++
                    }
                    c in "().|^$" -> {
                        cut()
                        i++
                    }
                    else -> {
                        run.append(c)
                        i++
                    }
                }
            }
            cut()
            return runs
        }

        /**
         * Lines the code builds from parts, each with the parts that make it -
         * enum constants by a use of them, since a log line prints their name.
         */
        val BUILT: Map<String, List<String>> = run {
            val machineSays = "\"calltrack: answering machine - not answering: \${decision.why}\""
            val cable = listOf("\"cable: \${what ?: \"changed\"}; watching the recorder\"", "\"plugged in\" : \"unplugged\"")
            val swapped = "Ln.i(\"recording: \" + why + \" (audio mode \" + mode + \"); \""
            val port = listOf("const val PORT = AdbTransport.LEGACY_TCP_PORT", "const val LEGACY_TCP_PORT = 5555")
            val keepalive = listOf("\"keepalive: \${if (forWifi) \"Wi-Fi run\" else \"run\"} (attempt \${runAttemptCount + 1})\"")
            val grant = "\"setup: grant -> \$outcome ("
            mapOf(
                "answering machine - not answering: whose calls to keep" to
                    listOf(machineSays, "Decision.No(\"whose calls to keep leaves this caller out\")"),
                "not answering: paused after an emergency call" to
                    listOf(machineSays, "Decision.No(\"paused after an emergency call\")"),
                "another app's call through Telecom is off-hook" to
                    listOf("\"callwatch: another app's call through Telecom is \" + event", "appsCall(\"off-hook\")"),
                "recording: the call's voice path (audio mode 2" to
                    listOf("Ln.i(\"recording: the call's voice path (audio mode \" + mode"),
                "recording: the call plays through the mixer now (audio mode 3)" to
                    listOf("\"the call plays through the mixer now\"", swapped),
                "recording: the call is back on the modem's voice path (audio mode 2)" to
                    listOf("\"the call is back on the modem's voice path\"", swapped),
                "recording: audio mode 3 during the call" to
                    listOf("Ln.i(\"recording: audio mode \" + mode + \" during the call\")"),
                "recording: audio mode 3, but another app's call is up; the call capture stays" to listOf(
                    "\"another app's call is up\"",
                    "Ln.i(\"recording: audio mode \" + mode + \", but \" + why + \"; the call capture stays\")",
                ),
                // The call report's timeline (CallReport), as Copy a report gives it.
                "audio mode 3" to listOf("report.note(\"audio mode \" + mode)", ".append(\"  audio mode \").append(mode)"),
                "cable: unplugged; watching the recorder" to cable,
                "cable: plugged in; watching the recorder" to cable,
                // The recorder's list of port IDs, written by List.toString:
                // the emulator's port is called "cable".
                "cable: watching USB port(s) [cable" to listOf(
                    "Ln.i(\"cable: watching USB port(s) \" + ports + \" every \"",
                    "java.util.List<String> ports = new java.util.ArrayList<>();",
                ),
                "recorder took it: true" to listOf("recorder took it: \$set, "),
                "wire 1," to listOf("\"wire \${com.jemcik.jemrec.capture.AnsweringMachine.wire(appContext)}, recorder took it"),
                "recorder up (connect CONNECTED)" to listOf(
                    "\"revive: \${if (alive) \"recorder up\" else \"recorder down\"} (connect \${found.connect})\"",
                    "ConnectOutcome.CONNECTED",
                ),
                "revive: adbd STOPPED" to listOf("\"revive: adbd \$adbd\"", "AdbdState.State.STOPPED"),
                "revive: Wireless debugging CYCLE" to listOf("\"revive: Wireless debugging \$step\"", "WirelessStep.CYCLE"),
                "setup: grant -> REFUSED" to listOf(grant, "Outcome.REFUSED"),
                "setup: grant -> GRANTED" to listOf(grant, "Outcome.GRANTED"),
                "keepalive: Wi-Fi run" to keepalive,
                "keepalive: run (attempt 3)" to keepalive,
                "revive: port 5555 opened" to
                    listOf("\"revive: port \$port \${if (opened) \"opened\" else ") + port,
                "revive: connecting on port 5555, no Wi-Fi" to
                    listOf("\"revive: connecting on port \${StartWithoutWifi.PORT}, no Wi-Fi\"") + port,
                "backgroundkill: on open" to listOf("\"backgroundkill: \$where; \"", "checkOwnRecords(app, \"on open\")"),
                "started stopped true" to
                    listOf("\"started stopped \${if (fromDaemon) \"not read (the recorder judged)\" else leftStopped}; \""),
                "newest exit reason 10" to listOf("\"newest exit \${newest?.let { \"reason \${it.reason} importance "),
                "debugging off: the recorder says OFF NOW" to
                    listOf("\"debugging off: the recorder says \${reply ?: \"nothing\"}\"", "\"OFF AFTER CALL\" : \"OFF NOW\""),
                "debugging back on: USB_DEBUGGING" to listOf(
                    "\"debugging back on: \${off.inOrderBackOn().joinToString { it.name }}\"",
                    "DebugSwitch.USB_DEBUGGING",
                ),
            )
        }
    }
}
