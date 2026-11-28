package com.jemcik.jemrec.adb

import java.io.File
import java.security.SecureRandom
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * Whether an adb session JemRec has just made is this phone's own shell,
 * shown before anything goes over it: a fresh random value written to a file
 * in the app's external files directory, then read back over the session with
 * `cat`. This phone's shell can read that directory (scoped storage,
 * Android 11 and up; JemRec's minSdk is 31) - the recorder's jar is handed
 * over the same way (DaemonLauncher.stageAsset). A session that
 * gives back anything else, or nothing, is let go and not used
 * (AdbTransport.prove). Each proof has a file of its own, gone after it
 * either way - and one a process killed mid-proof left, by the next proof.
 */
internal object ShellProof {

    private const val PREFIX = "session-"

    /** A proof's file older than this is one a killed process left: a
     *  proof takes 10 s at most (AdbTransport.PROOF_MS). */
    internal const val LEFT_MS = 60_000L

    /** 128 bits, as hex. */
    internal fun freshValue(random: SecureRandom = SecureRandom()): String =
        ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    /**
     * [run] has the session run a command and gives back what it printed,
     * or null. False without [dir]: there is nowhere the shell could read
     * the value from - and the recorder's start needs that directory anyway.
     */
    suspend fun proved(
        dir: File?,
        run: suspend (String) -> String?,
        value: String = freshValue(),
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (dir == null) return false
        sweep(dir, now)
        val file = File(dir, PREFIX + UUID.randomUUID() + ".txt")
        return try {
            // With its line end: the session reads by lines and then looks
            // for its end marker on a line of its own (AdbTransport's
            // ShellSession) - a value with none runs into the marker, which
            // is then never found, and every proof waited out its time (the
            // Android 16 AVD, 2026-11-27).
            file.writeText(value + "\n")
            run("cat '${file.absolutePath}'")?.trim() == value
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        } finally {
            file.delete()
        }
    }

    /** The files of proofs a process killed mid-proof left behind. */
    private fun sweep(dir: File, now: Long) {
        dir.listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(".txt") && now - f.lastModified() > LEFT_MS }
            ?.forEach { it.delete() }
    }
}
