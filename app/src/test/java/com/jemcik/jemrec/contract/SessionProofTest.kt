package com.jemcik.jemrec.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every adb session JemRec makes shows it is the phone's shell before it is
 * used (AdbTransport.prove), and every connect dials only the classic ports
 * adbd's own properties name (PortChange.classicPorts). Both are wiring a JVM
 * test cannot run - libadb needs a phone - so this reads it: a connect whose
 * proof was taken away passed every other test (review round 4 of 1.11).
 */
class SessionProofTest {

    private val transport = Repo.code(Repo.text("app/src/main/java/com/jemcik/jemrec/adb/AdbTransport.kt"), kotlin = true)

    @Test
    fun everyConnectIsProvedBeforeItsFunctionEnds() {
        // libadb's connects of any kind, on any receiver, are mgr.connect's.
        val anyConnect = Regex("""\.(connect|connectTls|connectTcp|autoConnect)\(""").findAll(transport)
            .map { transport.substring(maxOf(0, it.range.first - 3), it.range.last + 1) }.toList()
        assertEquals(listOf("mgr.connect("), anyConnect.distinct())
        // And a proof's answer is used: never a call on its own.
        assertTrue(
            "a proof whose answer is not used",
            !Regex("""^\s*prove\([^\n]*\)\s*$""", RegexOption.MULTILINE).containsMatchIn(transport),
        )
        // One libadb connect, in libadbConnect, which notes who made the session.
        val libadb = Regex("""\bmgr\.connect\(""").findAll(transport).map { it.range.first }.toList()
        val helper = body("private fun libadbConnect(")
        assertTrue("libadb's connect is libadbConnect's alone", libadb.size == 1 && libadb[0] in helper)
        // Called by connect() and by connectTrying, autoConnect's, which
        // makes no session of its own to use: each of the three calls that
        // makes one is proved.
        val trying = body("private suspend fun connectTrying(")
        val direct = Regex("""(?<!fun )\blibadbConnect\(mgr, """).findAll(transport).map { it.range.first }.toList()
        assertEquals("libadbConnect's callers: connect() and connectTrying", 2, direct.size)
        assertEquals(1, direct.count { it in trying })
        // Its retry is againAfter's, the rule ConnectAttemptTest tests.
        assertTrue("connectTrying retries by againAfter", "againAfter(attempt.exceptionOrNull()" in transport.substring(trying))
        val viaTrying = Regex("""(?<!fun )\bconnectTrying\(mgr, """).findAll(transport).map { it.range.first }.toList()
        val connects = (direct.filter { it !in trying } + viaTrying).sorted()
        // connect()'s, and autoConnect's two: Wireless debugging's port and the classic ones.
        assertEquals("the connects that make a session to use", 3, connects.size)
        val proofs = Regex("""(?<!fun )\bprove\(app, mgr, """).findAll(transport).map { it.range.first }.toList()
        val functions = Regex("""\bfun \w""").findAll(transport).map { it.range.first }.toList()
        for (at in connects) {
            val proof = proofs.firstOrNull { it > at }
            val nextFunction = functions.firstOrNull { it > at } ?: Int.MAX_VALUE
            val nextConnect = connects.firstOrNull { it > at } ?: Int.MAX_VALUE
            assertTrue(
                "the connect at ${line(at)} is not followed by its proof before its function ends",
                proof != null && proof < nextFunction && proof < nextConnect,
            )
        }
    }

    @Test
    fun commandsRunOnlyOnTheSessionShown() {
        // exec and restartAdbd take the shown session (live()) under exec's
        // lock; nothing opens a stream on the manager, whichever connection
        // it holds then.
        assertTrue("no stream opened on the manager", "mgr.openStream(" !in transport && "manager?.openStream(" !in transport)
        val takes = Regex("""val conn = live\(\) \?: return@withLock notConnected\(\)""").findAll(transport).count()
        assertEquals("exec and restartAdbd each take the shown session under the lock", 2, takes)
        // The session is shown once, in the proof, after it passed - and
        // what is up is read from it: before round 4 of 1.11 a command
        // queued behind a proof ran on the session being proved.
        val setsShown = Regex("""\bshown = (?!null)""").findAll(transport).map { it.range.first }.toList()
        assertEquals("shown is set in one place", 1, setsShown.size)
        val proof = transport.indexOf("ShellProof.proved(")
        val passed = transport.indexOf("if (ok) {", proof)
        val nextFunction = Regex("""\bfun \w""").find(transport, proof)?.range?.first ?: Int.MAX_VALUE
        assertTrue("shown is set after the proof passed", proof in 0 until passed && setsShown[0] in passed until nextFunction)
        assertTrue("live() reads shown", "private fun live(): AdbConnection? = shown?.takeIf {" in transport)
        assertTrue("isConnected is live()'s", "(live() != null).also { lastKnownConnected = it }" in transport)
        // A cancel is passed on, not taken for a failed proof (round 5).
        assertTrue("runOnShell passes a cancel on", "runOnShellCatching(conn, command, timeoutMs).also(::passOnCancel)" in transport)
        // And autoConnect, thrown or only noticed, before it keeps anything
        // of the look (round 6).
        val keeps = transport.indexOf("lastConnect = outcome")
        val passes = transport.lastIndexOf("passOnCancel(result)", keeps)
        val noticed = transport.lastIndexOf("coroutineContext.ensureActive()", keeps)
        assertTrue("autoConnect passes a cancel on before it keeps the look", passes in 0 until keeps && noticed in passes until keeps)
        assertTrue(
            "and stops after the finder",
            Regex("""\.find\(timeoutMs\)\.also \{ found = it \}\s+job\.ensureActive\(\)""").containsMatchIn(transport),
        )
        // libadb's connection classes are AdbTransport's alone.
        val others = Repo.appFiles().filter { (name, code) ->
            name != "AdbTransport.kt" && Regex("""\b(AbsAdbConnectionManager|AdbConnection)\b""").containsMatchIn(code)
        }
        assertEquals(emptyList<String>(), others.map { it.first })
    }

    @Test
    fun everyAutoConnectDialsByTheRule() {
        // No list of its own: autoConnect's default is the rule, or the
        // revive's, which is the rule read on its own thread.
        val calls = Repo.appFiles().flatMap { (name, code) ->
            Regex("""AdbTransport\.autoConnect\(""").findAll(code).map { name to arguments(code, it.range.last) }
        }
        assertTrue("the app's autoConnect calls found", calls.size >= 4)
        // Past the context and the look's time, only the rule's own list.
        val own = calls.filter { (_, args) ->
            val rest = topLevel(args).drop(2)
            rest.any { it != "classicPorts = classicPorts()" } || topLevel(args).take(2).any { "classicPorts" in it }
        }
        assertEquals(emptyList<Pair<String, String>>(), own)
        val revive = Repo.code(Repo.text("app/src/main/java/com/jemcik/jemrec/recorder/ReviveWorld.kt"), kotlin = true)
        assertTrue("the revive's list is the rule's", "override fun classicPorts() = PortChange.classicPorts(context)" in revive)
    }

    @Test
    fun theArgumentReaderReadsToItsOwnParenthesis() {
        val code = "AdbTransport.autoConnect(context, timeoutMs = f(1, 2), classicPorts = classicPorts()) + 1"
        val args = arguments(code, code.indexOf('('))
        assertEquals("context, timeoutMs = f(1, 2), classicPorts = classicPorts()", args)
        assertEquals(listOf("context", "timeoutMs = f(1, 2)", "classicPorts = classicPorts()"), topLevel(args))
        // A list in third place, unnamed, is one of its own.
        assertEquals(listOf("app", "4_000", "listOf(5555)"), topLevel("app, 4_000, listOf(5555)"))
    }

    /** [args] split at its own commas, not at those of a call inside it. */
    private fun topLevel(args: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var from = 0
        args.forEachIndexed { i, c ->
            when (c) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) {
                    parts += args.substring(from, i).trim()
                    from = i + 1
                }
            }
        }
        if (args.isNotBlank()) parts += args.substring(from).trim()
        return parts
    }

    /** What follows the call's opening parenthesis at [open], to the one that closes it. */
    private fun arguments(code: String, open: Int): String {
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return code.substring(open + 1, i).trim()
            }
        }
        error("no closing parenthesis after $open")
    }

    /** The range of the function [declaration] begins, to the next one. */
    private fun body(declaration: String): IntRange {
        val at = transport.indexOf(declaration)
        assertTrue("$declaration is there", at >= 0)
        val next = Regex("""\bfun \w""").find(transport, at + declaration.indexOf("fun ") + 4)?.range?.first ?: transport.length
        return at until next
    }

    private fun line(at: Int) = "line " + (transport.substring(0, at).count { it == '\n' } + 1) + " (comments taken out)"
}
