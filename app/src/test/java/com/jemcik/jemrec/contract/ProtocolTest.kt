package com.jemcik.jemrec.contract

import com.jemcik.jemrec.capture.CaptureDaemon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The replies in shellserver/tests/fixtures/protocol.txt, still spoken by both
 * sides.
 *
 * The app and the recorder each write the protocol by hand, and the app never
 * compiles the recorder's sources: a reply reworded on one side compiles, and
 * every test of the side it was changed on passes. Until the 1.0 restructuring
 * moves the recorder's formatting where daemon-tests can run it,
 * this checks the text each side has for every reply, and reads every example
 * with the app's own parsers.
 */
class ProtocolTest {

    private data class Reply(val command: String, val app: String, val recorder: String, val example: String)

    private val replies: List<Reply> = Repo.text("shellserver/tests/fixtures/protocol.txt").lines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { line ->
            val columns = line.split('\t')
            check(columns.size == 4) { "not four tab-separated columns: $line" }
            Reply(columns[0], columns[1], columns[2], columns[3])
        }

    @Test
    fun theRecorderStillWritesEveryReply() {
        val gone = replies.filter { it.recorder !in Repo.recorder }.map { "${it.command}: ${it.recorder}" }
        assertTrue("No longer in shellserver/src:\n" + gone.joinToString("\n"), gone.isEmpty())
    }

    @Test
    fun theAppStillReadsEveryReply() {
        val gone = replies.filter { it.app != "-" && it.app !in Repo.app }.map { "${it.command}: ${it.app}" }
        assertTrue("No longer in app/src/main:\n" + gone.joinToString("\n"), gone.isEmpty())
    }

    @Test
    fun everyCommandTheAppSendsHasItsReplies() {
        // The app's command bytes (CaptureDaemon.COMMAND_*) against the
        // fixture's first column: a command added on one side shows up here.
        val sent = Regex("""const val COMMAND_\w+\s*=\s*'(.)'""").findAll(Repo.app).map { it.groupValues[1] }.toSet()
        assertTrue("no COMMAND_ constants found: is the pattern stale?", sent.isNotEmpty())
        val answered = replies.map { it.command.take(1) }.toSet()
        // A (the handshake) is answered in bytes - the recorder's nonce and its
        // MAC - not a line, and R streams audio: neither is in the fixture.
        assertEquals(emptySet<String>(), sent - answered - setOf("A", "R"))
    }

    @Test
    fun theAppsParsersReadEveryExample() {
        fun example(command: String, startingWith: String) =
            replies.first { it.command == command && it.example.startsWith(startingWith) }.example

        assertEquals(CaptureDaemon.Answer(2, "1a2b3c4d"), CaptureDaemon.parsePong(example("P", "PONG")))
        assertEquals(CaptureDaemon.QuitReply.BYE, CaptureDaemon.parseQuitReply(example("Q", "BYE")))
        assertEquals(CaptureDaemon.QuitReply.BUSY, CaptureDaemon.parseQuitReply(example("Q", "BUSY")))

        val watch = CaptureDaemon.parseWatch(example("W", "WATCH"))
        assertNotNull("the WATCH example does not parse", watch)
        assertEquals(1761990000000L, watch!!.since)
        assertTrue(watch.watching)

        val diag = CaptureDaemon.fields(example("XC", "DIAG"), "DIAG 1")
        assertEquals("HONOR", diag!!["maker"])
        val result = CaptureDaemon.fields(example("XR", "RESULT"), "RESULT 1")
        assertEquals("done", result!!["state"])

        val sims = CaptureDaemon.parseSims(example("XP", "SIMS"))
        assertEquals("1", sims!!.defaultId)
        assertEquals(2, sims.all.size)

        // The rest are read by their head alone.
        replies.filter { it.app.startsWith("startsWith(\"") }.forEach {
            val head = it.app.removePrefix("startsWith(\"").removeSuffix("\")")
            assertTrue("${it.example} does not start with $head", it.example.startsWith(head))
        }
    }
}
