package com.jemcik.jemrec.adb

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** A session shows it is the phone's shell by reading back a file only the
 *  shell and the app can read (ShellProof), before anything goes over it. */
class ShellProofTest {

    @get:Rule val folder = TemporaryFolder()

    /** A shell: runs the `cat` it is given, on this machine's files. */
    private val shell: suspend (String) -> String? = { command ->
        val path = Regex("^cat '(.+)'$").find(command)?.groupValues?.get(1)
        path?.let { File(it).takeIf(File::exists)?.readText() }
    }

    private fun leftOver() = folder.root.listFiles().orEmpty().toList()

    @Test fun theShellReadsTheValueBackAndTheFileGoes() = runBlocking {
        assertTrue(ShellProof.proved(folder.root, shell))
        assertEquals(emptyList<File>(), leftOver())
    }

    @Test fun anythingElseIsNotTheShell() = runBlocking {
        // Something that is not the phone's shell cannot read the app's
        // folder: whatever it answers, it is not the value.
        assertFalse(ShellProof.proved(folder.root, { "OK" }))
        assertFalse(ShellProof.proved(folder.root, { "" }))
        assertFalse(ShellProof.proved(folder.root, { null }))
        assertFalse(ShellProof.proved(folder.root, { throw IOException("stream closed") }))
        // The value of another proof, or another value altogether.
        assertFalse(ShellProof.proved(folder.root, { "0123456789abcdef0123456789abcdef" }, value = ShellProof.freshValue()))
        assertEquals(emptyList<File>(), leftOver())
    }

    @Test fun withNoFolderThereIsNoProof() = runBlocking {
        var asked = false
        assertFalse(ShellProof.proved(null, { asked = true; "anything" }))
        assertFalse(asked)
    }

    @Test fun eachProofHasAFileAndAValueOfItsOwn() = runBlocking {
        val commands = mutableListOf<String>()
        repeat(2) { ShellProof.proved(folder.root, { commands += it; shell(it) }) }
        assertNotEquals(commands[0], commands[1])
        assertNotEquals(ShellProof.freshValue(), ShellProof.freshValue())
        assertEquals(32, ShellProof.freshValue().length)
        // The file is the app folder's, read with `cat`, quoted.
        assertTrue(commands.all { it.startsWith("cat '${folder.root.absolutePath}/") && it.endsWith(".txt'") })
    }

    @Test fun aShellLineEndIsNoMismatch() = runBlocking {
        // The shell's own output ends with a newline (exec keeps it).
        assertTrue(ShellProof.proved(folder.root, { shell(it) + "\n" }))
    }

    @Test fun theFileEndsInALineEnd() = runBlocking {
        // The session's reader reads lines and then waits for its end marker
        // on a line of its own: a value with no line end runs into it, and
        // every proof timed out on the Android 16 AVD, 2026-11-27. A shell
        // that prints the file and then the marker, as the session does:
        var printed = ""
        val marked: suspend (String) -> String? = { command ->
            printed = (shell(command) ?: "") + "__end__"
            printed.lines().firstOrNull()
        }
        assertTrue(ShellProof.proved(folder.root, marked))
        assertTrue("the marker on a line of its own: $printed", printed.lines().contains("__end__"))
    }

    @Test fun aCancelIsPassedOnAndTheFileStillGoes() {
        // A caller cancelled mid-proof: not "not the shell" (which closes
        // the session), its cancel (review round 5 of 1.11).
        val thrown = runCatching {
            runBlocking { ShellProof.proved(folder.root, { throw kotlinx.coroutines.CancellationException("closed") }) }
        }.exceptionOrNull()
        assertTrue("$thrown", thrown is kotlinx.coroutines.CancellationException)
        assertEquals(emptyList<File>(), leftOver())
    }

    @Test fun aFileAKilledProofLeftGoesWithTheNext() = runBlocking {
        // A process killed mid-proof runs no `finally`: its file stays, and
        // the next proof clears it - but not one a proof under way now has
        // just written, nor anything else in the folder.
        val now = System.currentTimeMillis()
        val left = File(folder.root, "session-0.txt").apply { writeText("x\n"); setLastModified(now - ShellProof.LEFT_MS - 1_000) }
        val fresh = File(folder.root, "session-1.txt").apply { writeText("y\n"); setLastModified(now - 1_000) }
        val jar = File(folder.root, "jemrec.jar").apply { writeText("z"); setLastModified(now - ShellProof.LEFT_MS - 1_000) }
        assertTrue(ShellProof.proved(folder.root, shell, now = now))
        assertFalse(left.exists())
        assertTrue(fresh.exists())
        assertTrue(jar.exists())
    }
}
