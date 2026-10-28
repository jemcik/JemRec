package com.jemcik.jemrec.capture

import java.util.concurrent.TimeUnit

/**
 * A system property, read with getprop, since android.os.SystemProperties is
 * not for apps (see DeviceSkin). Which ones an app may read is sepolicy's to
 * say, and getprop prints the same empty line for a property never set as
 * for one the caller may not read, so each caller says what "" means.
 */
object SystemProperty {

    /** By its full path, not looked up on PATH (CodeQL, code-scanning alert
     *  67): Android's own, in every build. */
    private const val GETPROP = "/system/bin/getprop"
    private const val GETPROP_MS = 2_000L
    private const val EXIT_POLL_MS = 2L

    /**
     * The value, trimmed: "" for a property never set or not readable, null
     * when getprop could not be run or did not finish. Run on revives and
     * screen refreshes, so the process and its pipes are let go every time.
     *
     * Its end is looked for every 2 ms, not with waitFor(timeout), which
     * looks every 100: each read took 102 ms that way, on the Honor,
     * 2026-10-28, eight reads in a row, for a getprop done in a few.
     */
    fun read(name: String): String? {
        val process = runCatching {
            ProcessBuilder(GETPROP, name).redirectErrorStream(true).start()
        }.getOrNull() ?: return null
        return try {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(GETPROP_MS)
            while (process.isAlive) {
                if (System.nanoTime() > deadline) return null
                Thread.sleep(EXIT_POLL_MS)
            }
            process.inputStream.bufferedReader().use { it.readText() }.trim()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: Exception) {
            null
        } finally {
            process.destroy()
        }
    }
}
