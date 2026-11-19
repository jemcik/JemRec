package com.jemcik.jemrec.recordings

import android.content.Context
import java.io.File

/**
 * The .m4a copies Share makes of a call (ui/Sharing), so it plays in Apple's
 * stock apps: in the app's own external files dir, handed out per file by the
 * FileProvider (res/xml/file_paths.xml).
 *
 * They are copies of calls, so they must not outlive their use. Until 1.2
 * they were swept only at the next share - a phone that never shared again
 * kept every one, and a recording deleted for good went on as its copy (the
 * practice audit, 2026-11-17). Now a copy goes once it is [STALE_MS] old -
 * swept at every share, every keep-alive run and every start of the app's
 * process - and when its recording leaves the list (Trash.left), unless it
 * may still be being read: shared and then deleted at once - "sent it, now
 * delete it" - an app that reads its share later, a queued upload, lost it
 * (review, 2026-11-18). That one goes with the stale ones.
 */
object ShareCopies {

    /** A share in flight is minutes old at most, so this never sweeps a copy
     *  the receiving app is still reading. */
    internal const val STALE_MS = 6 * 60 * 60 * 1000L

    /** How long a copy may still be being read by the app it was shared
     *  to, as far as a recording leaving the list is concerned. */
    internal const val IN_FLIGHT_MS = 10 * 60 * 1000L

    /** Where the copies are made; null while Android has no external files
     *  dir to give (storage not mounted). */
    fun dir(context: Context): File? = context.getExternalFilesDir(null)?.let { File(it, DIR) }

    /** The copy of the recording called [recordingName]. */
    fun of(dir: File, recordingName: String): File = File(dir, recordingName.substringBeforeLast('.') + ".m4a")

    /** Delete the copies older than [STALE_MS]. How many went. */
    fun sweep(dir: File, now: Long = System.currentTimeMillis()): Int =
        dir.listFiles().orEmpty().count { it.isFile && it.lastModified() < now - STALE_MS && it.delete() }

    fun sweep(context: Context): Int = dir(context)?.let { sweep(it) } ?: 0

    /** Delete the copy of the recording called [recordingName], if there is
     *  one and it is past [IN_FLIGHT_MS]: the recording has left the list. */
    fun forget(context: Context, recordingName: String) {
        dir(context)?.let { forget(it, recordingName) }
    }

    internal fun forget(dir: File, recordingName: String, now: Long = System.currentTimeMillis()): Boolean {
        val copy = of(dir, recordingName)
        return copy.isFile && copy.lastModified() < now - IN_FLIGHT_MS && copy.delete()
    }

    private const val DIR = "shares"
}
