package com.jemcik.jemrec.capture

import android.content.Context
import com.jemcik.jemrec.Prefs

/**
 * The recordings the user has marked to keep: what auto-delete never touches.
 *
 * A mark on the RECORDING, not the contact. The star in the list is the
 * address book's favourite contact, read-only here; this is the user saying
 * "this call matters", one recording at a time.
 *
 * Kept by name, which is the call's start time and direction and does not
 * change for the life of the file: not through a restore from Recently
 * deleted, and not when the folder setting changes. Kept in the app's
 * preferences, where the list and auto-delete read it - and written into the
 * recording's own tags as well (RecordingTags), the one thing the three
 * places a recording can live have in common, so that an uninstall, which
 * deletes the preferences, does not take the mark with it: a reinstall reads
 * it back from the file. A file the app may not write - one an earlier
 * install made, which Android counts as nobody's after an uninstall - or
 * one from before tags, keeps its mark here alone.
 *
 * Start fresh leaves it alone. The auto-delete settings it protects against
 * default to off after a reinstall, and the list reads the marks back before
 * auto-delete can ask.
 */
object KeepMarks {

    private const val KEY = "kept_recordings"

    fun all(context: Context): Set<String> =
        Prefs.of(context).getStringSet(KEY, emptySet())?.toSet() ?: emptySet()

    fun isKept(context: Context, name: String): Boolean = name in all(context)

    /** Mark or unmark these, all the same way. */
    @Synchronized
    fun set(context: Context, names: Collection<String>, kept: Boolean) {
        val now = all(context).toMutableSet()
        if (kept) now += names else now -= names.toSet()
        Prefs.of(context).edit().putStringSet(KEY, now).apply()
    }
}
