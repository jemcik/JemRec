package com.jemcik.jemrec.capture

import android.content.Context
import androidx.core.content.edit
import com.jemcik.jemrec.Prefs

/**
 * The recordings that are messages: calls the answering machine took.
 *
 * Kept by name, like KeepMarks, so the file keeps the name the user's File
 * names setting gives it, and Honor's Phone app still finds it by that name.
 * The recorder's own file says it is a message ("_msg" in the daemon's name,
 * see RecordingStore.messageStartMs); this is where the list reads that after
 * the save, and the saved file's tags (RecordingTags) are where it outlives
 * an uninstall.
 */
object MessageMarks {

    private const val KEY = "message_recordings"
    /** The newest this many: a mark outlives its recording - Recently
     *  deleted brings one back by the same name - and a bound keeps that from
     *  adding up in a preferences file read whole at every start. */
    private const val KEEP = 2000

    fun all(context: Context): Set<String> =
        Prefs.of(context).getStringSet(KEY, emptySet())?.toSet() ?: emptySet()

    fun mark(context: Context, name: String) = mark(context, listOf(name))

    @Synchronized
    fun mark(context: Context, names: Collection<String>) {
        val marks = all(context) + names
        val kept = if (marks.size <= KEEP) {
            marks
        } else {
            // Each name read once: the sort's selector runs at every
            // comparison, some 40,000 of them for 2,000 names.
            marks.associateWith { FileNames.parse(it)?.startedAt ?: 0L }
                .entries.sortedByDescending { it.value }.take(KEEP).map { it.key }.toSet()
        }
        Prefs.of(context).edit { putStringSet(KEY, kept) }
    }
}
