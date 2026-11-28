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
        val missing = matched().filter { it !in Repo.sources && it !in BUILT && it !in ANDROIDS }
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
    fun adjacentLiteralsAreOneString() {
        val twoLines = "r\"monitor: (out\"\n        r\"|back)\""
        assertEquals("monitor: (out|back)", joined(twoLines))
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
         *  debug receiver's answers, and the app's own notifications as
         *  `dumpsys notification` shows them (review rounds 25, 26), and any
         *  variable a `diag(...)` answer is kept in (matched(); round 27).
         *  Not read: the app's preferences file and a recording's tags as the
         *  checks read them (keys and tags in their own XML and Ogg framing),
         *  the app's own text in uiautomator dumps, mixed with Android's, nor
         *  `ls` listings of the recorder's file names (`"jemrec_rec_" not in
         *  sh("ls ...")`) - a rename there shows at the emulator run. The rest (other `sh(...)`
         *  of dumpsys, content queries) is Android's own text. */
        const val OURS = """(?:log\(\)|notes\b(?:\(\))?|sh\("dumpsys notification[^"]*"\)|""" +
            """revive_since\(|revive_log\(|sh\(daemon_log\)|capture_log\(\)|""" +
            """sh\("cat /data/local/tmp/jemrec-capture|(?:log_text|text|since|seen|said|report|after|line|l)\b)"""

        /** The literal text of every match on our own lines: each literal run
         *  of a regex, and for `"..." in`, the text before any f-string field.
         *  Shorter than six characters is too little to say anything. */
        fun matched(): List<String> {
            val checks = Repo.text("tools/emulator_checks.py")
            // And whatever a check keeps a debug receiver's answer in: `held
            // = diag(...)` read as `"held at" in held` (review round 27).
            val answers = Regex("""(\w+)\s*=\s*diag\(""").findAll(checks).map { it.groupValues[1] }.toSet()
            val ours = """(?:$OURS|diag\(|(?:${answers.joinToString("|")})\b)"""
            val regexes = (
                Regex("""wait_log\(\s*($LITERALS)""").findAll(checks) +
                    // count() and wait_more() read log() (review round 25).
                    Regex("""(?<![\w.])(?:count|wait_more)\(\s*($LITERALS)""").findAll(checks) +
                    Regex("""re\.(?:search|findall|match|finditer)\(\s*($LITERALS)\s*,\s*$ours""").findAll(checks)
                ).flatMap { literalRuns(joined(it.groupValues[1])) }
            val substrings = Regex("""$STR\s+(?:not\s+)?in\s+$ours""").findAll(checks)
                .map { it.text().substringBefore('{') }
            return (regexes + substrings).map { it.trim() }.filter { it.length >= 6 }.distinct().toList()
        }

        fun MatchResult.text() = groupValues[1].ifEmpty { groupValues[2] }

        /** Python's adjacent literals, one string: a pattern split over two
         *  lines was checked by its first piece only (review round 24). */
        const val LITERALS = """$STR(?:\s*$STR)*"""

        /** Android's own words in what [OURS] reads: the notification dump
         *  frames the app's text in its own record format. */
        val ANDROIDS = setOf(
            "NotificationRecord(",
            // The check's own contact, made by the check (add_contact).
            "TestKept",
        )

        /** The text of [LITERALS]' pieces, joined as Python joins them. */
        fun joined(literals: String): String = Regex(STR).findAll(literals).joinToString("") { it.text() }

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
            val keepalive = listOf("\"keepalive: \${if (forWifi) \"Wi-Fi run\" else \"run\"} (attempt \${runAttemptCount + 1})\"")
            val grant = "\"setup: grant -> \$outcome ("
            val greetingOn = "Ln.i(\"answer: the greeting on route \" + route + \" (\""
            // The recorder's test result line, built field by field
            // (Wire.result): what the answering machine's test check reads.
            val result = { key: String -> listOf("\" $key=\" + $key") }
            mapOf(
                // The debug op's answers: what the recorder said, what Android
                // now says, and a button pressed.
                "recorder true, allowed true" to listOf(": recorder \$answer, allowed \${"),
                "recorder true, allowed false" to listOf(": recorder \$answer, allowed \${"),
                "pressed Record" to listOf("\"pressed \$label on \$title\""),
                // A connect's one line (AdbTransport.Attempt.line): its
                // outcome's name, then the port (mdns_silent).
                "autoConnect: CONNECTED on" to listOf("\"autoConnect: \${attempt.line()}\"", "CONNECTED,", "append(\" on \")"),
                // The debug op connect's answer, as the receiver logs it.
                "diag[connect] connected" to listOf("\"diag[\$op] \$it\"", "{ \"connected\" }"),
                // Our wake lock's tag, as the power dump quotes it.
                "'jemrec:ear'" to listOf("LOCK_TAG = \"jemrec:ear\""),
                // The service says each audio mode it hears (modeChanged).
                "monitor: audio mode 0" to listOf("\"monitor: audio mode \$mode\""),
                "state=done" to listOf("\"RESULT 1 state=\" + state", "AnswerTest.testState = \"done\""),
                "wifi=0" to result("wifi"),
                "mixer=0" to result("mixer"),
                // The off-hook's way, the recorder's and the service's
                // alike (succession counts the incoming one).
                "off-hook (incoming)" to listOf(
                    "\"callwatch: off-hook (\" + (sawRinging ? \"incoming\"",
                    "\"monitor: off-hook (\${if (incoming) \"incoming\" else \"outgoing\"})",
                ),
                // The recorder's wakes to the app (Wakes): the action by its
                // constant, the try by its count - and Android's own answer
                // to `am start-foreground-service`, which the line carries
                // (battery_restricted reads that it refused).
                "wake: com.jemcik.jemrec.CALL_ENDED" to listOf(
                    "Ln.i(\"wake: \" + action + \" (\" + recfile + \") -> exit 0\"",
                    "ACTION_CALL_ENDED = \"com.jemcik.jemrec.CALL_ENDED\"",
                ),
                // port_opened_elsewhere: no close asked of adbd for a port JemRec
                // did not open (PortChange.changePort).
                "closing port" to listOf("\"revive: \${if (open) \"opening\" else \"closing\"} port \$port, adbd said \""),
                // port_other_used: no ask for JemRec's own number over a port
                // someone else opened (PortChange.changePort).
                "opening port" to listOf("\"revive: \${if (open) \"opening\" else \"closing\"} port \$port, adbd said \""),
                // port_number_own and port_other_used: Settings' line names the
                // port by its number, its first word made a capital
                // (startWithoutWifiSummary, portWords).
                "port 5555" to listOf("number?.let { \"debugging port \$it\" }"),
                "Debugging port" to listOf(
                    "number?.let { \"debugging port \$it\" }",
                    "port.replaceFirstChar { it.uppercase() }",
                ),
                // session_proof: a connect on the classic door let go
                // (AdbTransport.connect's check, ConnectOp's report), and the
                // proof's own line with its time (AdbTransport.prove).
                "connect failed: the session on" to listOf(
                    "\"connect failed: \${it.message}\"",
                    "error(\"the session on \$port \$why\")",
                ),
                "connect: the session on" to listOf(
                    "\"connect: \$where \$NOT_SHELL (\$ms ms); let go\"",
                    "\"the session on \$port\"",
                ),
                "did not show itself as the phone's shell (" to listOf(
                    "NOT_SHELL = \"did not show itself as the phone's shell\"",
                    "\$NOT_SHELL (\$ms ms)",
                ),
                "on attempt 3" to listOf("\" on attempt \" + attempt", "WAKE_ATTEMPTS = 3"),
                // And with its file's name, which early_end reads the mark in.
                "wake: com.jemcik.jemrec.CALL_ENDED (jemrec_rec_" to listOf(
                    "Ln.i(\"wake: \" + action + \" (\" + recfile + \") -> exit 0\"",
                    "ACTION_CALL_ENDED = \"com.jemcik.jemrec.CALL_ENDED\"",
                    "REC_PREFIX = \"jemrec_rec_\"",
                ),
                "no service started" to listOf("\" on attempt \" + attempt + \": \" + said"),
                // The quality (1.2): the app's line with the recorder's reply
                // in it, and the recorder's own at its start.
                "quality: daemon says QUALITY" to
                    listOf("\"quality: daemon says \$reply\"", "\"QUALITY \" + bps"),
                // The recorder's own word on exact call tracking, at its start
                // and at the app's 'T' (Tracking.applyTracking).
                "tracking: off" to listOf(
                    "Ln.i(\"tracking: \" + (trackingOn ?",
                    ": \"off\"));",
                ),
                "recording: quality 128 kbps" to listOf(
                    "Ln.i(\"recording: quality \" + Quality.words(Quality.bitRate))",
                    "(bps / 1000) + \" kbps\"",
                ),
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
                "(the call's voice path)" to
                    listOf(greetingOn, "\"the call's voice path\""),
                "(the call is through the mixer)" to
                    listOf(greetingOn, "\"the call is through the mixer\""),
                "answer: the greeting stopped (the call moved" to listOf(
                    "effects.log(\"answer: the greeting stopped (\" + why + \"); \"",
                    "cut(\"the call moved \" + (path == Checks.PATH_MIXER ? \"to the mixer\" : \"to its voice path\"))",
                ),
                "recording: audio mode 3, but another app's call is up; the call capture stays" to listOf(
                    "\"another app's call is up\"",
                    "Ln.i(\"recording: audio mode \" + mode + \", but \" + why + \"; the call capture stays\")",
                ),
                // The call report's timeline (CallReport), as Copy a report gives it.
                "audio mode 3" to listOf("report.note(\"audio mode \" + mode)", ".append(\"  audio mode \").append(mode)"),
                // A USB state's run (1.6) of the cable's watch.
                "cable: USB state changed; watching" to listOf(
                    "ReviveLog.note(context, \"cable: \${what ?: \"changed\"}; watching\")",
                    "CableWatch.watch(applicationContext, \"USB state changed\", Trigger.USB_STATE)",
                ),
                // The Default USB configuration's set taken by the recorder
                // (1.7), logged before it writes.
                "usb: Default USB configuration asked (1) - USB setting" to listOf(
                    "Ln.i(\"usb: Default USB configuration asked (\" + value + \") - \" + answer)",
                    "static final String USB_SETTING = \"USB setting\"",
                ),
                "cable: unplugged; watching the recorder" to cable,
                "cable: plugged in; watching the recorder" to cable,
                // The recorder's list of port IDs, written by List.toString:
                // the emulator's port is called "cable".
                "cable: watching USB port(s) [cable" to listOf(
                    "Ln.i(\"cable: watching USB port(s) \" + ports + \" every \"",
                    "java.util.List<String> ports = new java.util.ArrayList<>();",
                ),
                "recorder took it: true" to listOf("recorder took it: \$set, "),
                "wire 1," to listOf("\"wire \${com.jemcik.jemrec.answering.AnsweringMachine.wire(appContext)}, recorder took it"),
                "recorder up (connect CONNECTED)" to listOf(
                    "\"revive: \${if (alive) \"recorder up\" else \"recorder down\"} (connect \${found.connect})\"",
                    "ConnectOutcome.CONNECTED",
                ),
                "revive: adbd STOPPED" to listOf("\"revive: adbd \$adbd\"", "AdbdState.State.STOPPED"),
                "revive: Wireless debugging CYCLE" to listOf("\"revive: Wireless debugging \$step\"", "WirelessStep.CYCLE"),
                // Wireless debugging only to start (1.5): the step, the
                // recorder's answer, and the grant that starts the recorder.
                "revive: Wireless debugging TURN_ON" to listOf("\"revive: Wireless debugging \$step\"", "WirelessStep.TURN_ON"),
                "revive: Wireless debugging LEAVE" to listOf("\"revive: Wireless debugging \$step\"", "WirelessStep.LEAVE"),
                "revive: recorder up (connect CONNECTED)" to listOf(
                    "\"revive: \${if (alive) \"recorder up\" else \"recorder down\"} (connect \${found.connect})\"",
                    "ConnectOutcome.CONNECTED",
                ),
                "revive: Wireless debugging off - the recorder says SWITCHED_OFF" to listOf(
                    "\"revive: Wireless debugging off - the recorder says \${answer ?: \"nothing\"}\"",
                    "WirelessOff.SWITCHED_OFF",
                ),
                "settings: notif listener -> no recorder answered; started: true" to
                    listOf("\"settings: notif listener -> no recorder answered; started: \$up\""),
                "setup: grant -> REFUSED" to listOf(grant, "Outcome.REFUSED"),
                "setup: grant -> GRANTED" to listOf(grant, "Outcome.GRANTED"),
                "keepalive: Wi-Fi run" to keepalive,
                "keepalive: run (attempt" to keepalive,
                "backgroundkill: on open" to listOf("\"backgroundkill: \$where; \"", "checkOwnRecords(app, \"on open\")"),
                "started stopped true" to
                    listOf("\"started stopped \${if (fromDaemon) \"not read (the recorder judged)\" else leftStopped}; \""),
                "newest exit reason 10" to listOf("\"newest exit \${newest?.let { \"reason \${it.reason} importance "),
                "debugging off: the recorder says OFF NOW" to
                    listOf("\"debugging off: the recorder says \${reply ?: \"nothing\"}\"", "OFF_NOW = \"OFF NOW\""),
                "debugging back on: USB_DEBUGGING" to listOf(
                    "\"debugging back on: \${off.inOrderBackOn().joinToString { it.name }}\"",
                    "DebugSwitch.USB_DEBUGGING",
                ),
            )
        }
    }
}
