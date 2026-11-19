package com.jemcik.jemrec.recordings

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.calls.CallLogLookup

/**
 * What the app remembers of each recording, by its file name: that it is
 * kept, that it is a message, that it is one of a call's parts, which way
 * the call went, who a messenger call was with, when it began in UTC (and a
 * joined part's call), and that its file has been read. Each lives in the
 * preferences, where the list reads it; all but the last are in the
 * recording's own tags as well (RecordingTags), and [recover] brings them
 * back from there. The stores
 * keep their keys from before they had one owner: an update reads what the
 * last version wrote.
 *
 * A name, because it is the one thing that does not change for the life of
 * the file: not through a restore from Recently deleted, and not when the
 * folder setting changes - but once, for a name Honor's Phone app could not
 * read (HonorRenames), and [renamed] carries every mark to the new one. Each
 * store is locked on its own: reading the files back can take seconds after
 * a reinstall, and a keep mark is set from the main thread.
 */
internal object RecordingMarks {

    private const val TAG = "JemRec"

    /** The newest this many, in the five stores that hold a bound, and of
     *  the names read ([recover]): a mark outlives its recording - Recently
     *  deleted brings one back by the same name - and a bound keeps that from
     *  adding up in a preferences file read whole at every start. */
    private const val NEWEST = 2000

    /**
     * [entries] held to the bound, each read as a name by [name]: the newest,
     * and any older the list has read ([readNames]) or is reading now
     * ([listed]), which is every file on the phone. It is marks of gone files
     * the bound is for: an old message past the newest 2000 lost its mark while
     * its file stayed, and with it the short-call rule's exception, so
     * auto-delete could take it for a wrong number; a direction or a caller,
     * what its row shows. And a reinstall read every file back at once, none
     * of them read yet, and noted them as read when the oldest had gone.
     */
    private fun bounded(context: Context, entries: Set<String>, listed: Set<String>, name: (String) -> String): Set<String> {
        if (entries.size <= NEWEST) return entries
        val here = readNames(context) + listed
        return (newest(entries, name) + entries.filter { name(it) in here }).toSet()
    }

    /** The newest [NEWEST] of [entries], by the time each one's name holds;
     *  all of them, unread, when they fit: the list asks at every refresh. */
    private fun newest(entries: Set<String>, name: (String) -> String): Collection<String> {
        if (entries.size <= NEWEST) return entries
        // Each name read once: the sort's selector runs at every comparison,
        // some 40,000 of them for 2,000 names.
        return entries.associateWith { FileNames.parse(name(it))?.startedAt ?: 0L }
            .entries.sortedByDescending { it.value }.take(NEWEST).map { it.key }
    }

    /**
     * The recordings the user has marked to keep: what auto-delete never
     * touches.
     *
     * A mark on the RECORDING, not the contact. The star in the list is the
     * address book's favourite contact, read-only here; this is the user
     * saying "this call matters", one recording at a time. No bound: nothing
     * else remembers it. A file the app may not write - one an earlier
     * install made, which Android counts as nobody's after an uninstall - or
     * one from before tags, keeps its mark here alone.
     *
     * Start fresh leaves it alone. The auto-delete settings it protects
     * against default to off after a reinstall, and the list reads the marks
     * back before auto-delete can ask.
     */
    object Keep {
        private const val KEY_KEPT = "kept_recordings"

        fun all(context: Context): Set<String> =
            Prefs.of(context).getStringSet(KEY_KEPT, emptySet())?.toSet() ?: emptySet()

        /** Mark or unmark these, all the same way. */
        @Synchronized
        fun set(context: Context, names: Collection<String>, kept: Boolean) {
            val now = all(context).toMutableSet()
            if (kept) now += names else now -= names.toSet()
            Prefs.of(context).edit { putStringSet(KEY_KEPT, now) }
        }

        @Synchronized
        internal fun renamed(context: Context, from: String, to: String) {
            val now = all(context)
            if (from in now) Prefs.of(context).edit { putStringSet(KEY_KEPT, now - from + to) }
        }
    }

    /**
     * The recordings that are messages: calls the answering machine took.
     *
     * Kept by name so the file keeps the name the user's File names setting
     * gives it, and Honor's Phone app still finds it by that name. The
     * recorder's own file says it is a message ("_msg" in the daemon's name,
     * see DaemonFile); this is where the list reads that after the save.
     */
    object Messages {
        private const val KEY_MESSAGES = "message_recordings"

        fun all(context: Context): Set<String> =
            Prefs.of(context).getStringSet(KEY_MESSAGES, emptySet())?.toSet() ?: emptySet()

        fun mark(context: Context, name: String) = mark(context, listOf(name))

        @Synchronized
        fun mark(context: Context, names: Collection<String>, listed: Set<String> = emptySet()) {
            val kept = bounded(context, all(context) + names, listed) { it }
            Prefs.of(context).edit { putStringSet(KEY_MESSAGES, kept) }
        }

        @Synchronized
        internal fun renamed(context: Context, from: String, to: String) {
            val now = all(context)
            if (from in now) Prefs.of(context).edit { putStringSet(KEY_MESSAGES, now - from + to) }
        }
    }

    /**
     * The recordings that are one of a call's - a recorder lost mid-call
     * having left more than one, joined mid-call, or stopped before the
     * call ended (SavePlan.isPart): the short-call rule
     * judges the call, not such a part (Retention) - it took a part of a
     * ten-minute call, the 20 s before the recorder was lost, to the trash
     * (review round 13). By name; in the file's tags too (recover).
     */
    object Parts {
        private const val KEY_PARTS = "part_recordings"

        fun all(context: Context): Set<String> =
            Prefs.of(context).getStringSet(KEY_PARTS, emptySet())?.toSet() ?: emptySet()

        @Synchronized
        fun mark(context: Context, names: Collection<String>, listed: Set<String> = emptySet()) {
            if (names.isEmpty()) return
            val kept = bounded(context, all(context) + names, listed) { it }
            Prefs.of(context).edit { putStringSet(KEY_PARTS, kept) }
        }

        @Synchronized
        internal fun renamed(context: Context, from: String, to: String) {
            val now = all(context)
            if (from in now) Prefs.of(context).edit { putStringSet(KEY_PARTS, now - from + to) }
        }
    }

    /**
     * Which way a call went, for the names that do not say: Honor's format.
     * The saver notes it here as it saves, and the list falls back to the
     * call log, matched on the number the name does carry.
     */
    object Directions {
        private const val KEY_DIRECTIONS = "name_directions"

        /** The saver just saved [name]: if its name will not say which way
         *  the call went, remember it here. */
        fun note(context: Context, name: String, incoming: Boolean) = note(context, mapOf(name to incoming))

        /** The same, for many at once: one write of the preferences. */
        @Synchronized
        fun note(context: Context, directions: Map<String, Boolean>, listed: Set<String> = emptySet()) {
            val unsaid = directions.filterKeys { FileNames.parse(it)?.incoming == null }
            if (unsaid.isEmpty()) return
            val prefs = Prefs.of(context)
            val entries = (prefs.getStringSet(KEY_DIRECTIONS, emptySet()) ?: emptySet()).toMutableSet()
            entries.removeAll { it.drop(2) in unsaid }
            unsaid.forEach { (name, incoming) -> entries += "${if (incoming) "i" else "o"}:$name" }
            prefs.edit { putStringSet(KEY_DIRECTIONS, bounded(context, entries, listed) { it.drop(2) }) }
        }

        /** Every direction noted, by name: true for incoming. */
        fun all(context: Context): Map<String, Boolean> =
            (Prefs.of(context).getStringSet(KEY_DIRECTIONS, emptySet()) ?: emptySet())
                .associate { it.drop(2) to it.startsWith("i:") }

        @Synchronized
        internal fun renamed(context: Context, from: String, to: String) {
            val entries = Prefs.of(context).getStringSet(KEY_DIRECTIONS, emptySet()) ?: emptySet()
            val moved = entries.filter { it.drop(2) == from }.map { it.take(2) + to }
            if (moved.isNotEmpty()) {
                Prefs.of(context).edit { putStringSet(KEY_DIRECTIONS, entries.filterNot { it.drop(2) == from }.toSet() + moved) }
            }
        }
    }

    /**
     * When each recording began, in UTC, by name: its name holds only the
     * wall clock where it was recorded, read in the zone the phone is in now,
     * and matched by that alone after travel it was hours off its call
     * (review round 13). Noted as it is saved, and in the file's tags, so a
     * reinstall reads it back (recover). In a file of its own, as [READ_FILE]:
     * one per recording, and the shared preferences are rewritten whole at
     * every write.
     */
    object Starts {
        private const val FILE = "jemrec_starts"
        private const val KEY_STARTS = "name_starts"

        private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

        /** When it began, and for a part joined mid-call its call's start
         *  ([Recording.callAt]; review round 14). */
        data class Start(val at: Long, val callAt: Long? = null)

        /** [starts], by name: one write for many. */
        @Synchronized
        fun note(context: Context, starts: Map<String, Start>, listed: Set<String> = emptySet()) {
            if (starts.isEmpty()) return
            val entries = (prefs(context).getStringSet(KEY_STARTS, emptySet()) ?: emptySet()).toMutableSet()
            entries.removeAll { it.substringBefore('\u0000') in starts }
            starts.forEach { (name, s) -> entries += "$name\u0000${s.at}" + (s.callAt?.let { "\u0000$it" } ?: "") }
            prefs(context).edit { putStringSet(KEY_STARTS, bounded(context, entries, listed) { it.substringBefore('\u0000') }) }
        }

        /** Every start noted, by name. */
        fun all(context: Context): Map<String, Start> =
            (prefs(context).getStringSet(KEY_STARTS, emptySet()) ?: emptySet()).mapNotNull { entry ->
                val fields = entry.split('\u0000')
                fields.getOrNull(1)?.toLongOrNull()?.let { at -> fields[0] to Start(at, fields.getOrNull(2)?.toLongOrNull()) }
            }.toMap()

        @Synchronized
        internal fun renamed(context: Context, from: String, to: String) {
            val entries = prefs(context).getStringSet(KEY_STARTS, emptySet()) ?: emptySet()
            val moved = entries.filter { it.substringBefore('\u0000') == from }
                .map { to + it.removePrefix(from) }
            if (moved.isNotEmpty()) {
                prefs(context).edit { putStringSet(KEY_STARTS, entries.filterNot { it.substringBefore('\u0000') == from }.toSet() + moved) }
            }
        }

        /** [recordings] with their noted starts. */
        fun onto(context: Context, recordings: List<Recording>): List<Recording> {
            val starts = all(context)
            if (starts.isEmpty()) return recordings
            return recordings.map { r -> starts[r.name]?.let { r.copy(startUtc = it.at, callAt = it.callAt) } ?: r }
        }
    }

    /**
     * A messenger call's caller, for the names that do not carry it (the date
     * and number formats, or a caller with nothing a file name can take):
     * nothing else can say it, as messenger calls are not in the phone's
     * call log.
     */
    object Callers {
        private const val KEY_CALLERS = "name_callers"

        fun note(context: Context, name: String, caller: String) = note(context, mapOf(name to caller))

        /** The same, for many at once: one write of the preferences. */
        @Synchronized
        fun note(context: Context, callers: Map<String, String>, listed: Set<String> = emptySet()) {
            if (callers.isEmpty()) return
            val prefs = Prefs.of(context)
            val entries = (prefs.getStringSet(KEY_CALLERS, emptySet()) ?: emptySet()).toMutableSet()
            entries.removeAll { it.substringBefore('\u0000') in callers }
            callers.forEach { (name, caller) -> entries += "$name\u0000$caller" }
            prefs.edit { putStringSet(KEY_CALLERS, bounded(context, entries, listed) { it.substringBefore('\u0000') }) }
        }

        /** Every caller noted, by name. */
        fun all(context: Context): Map<String, String> =
            (Prefs.of(context).getStringSet(KEY_CALLERS, emptySet()) ?: emptySet())
                .associate { it.substringBefore('\u0000') to it.substringAfter('\u0000') }
    }

    // --- reading them back ---------------------------------------------------

    /**
     * The names read, in a file of their own: one per recording, and the one
     * preferences file is rewritten whole at every write anywhere in the app
     * ([Messages] caps its set at 2000 for that reason).
     */
    private const val READ_FILE = "jemrec_tags_read"
    private const val KEY_READ = "names"

    fun readNames(context: Context): Set<String> =
        context.getSharedPreferences(READ_FILE, Context.MODE_PRIVATE).getStringSet(KEY_READ, emptySet()).orEmpty()

    /**
     * The file [from] is called [to] now (HonorRenames): what was remembered
     * of it - kept, a message, a part, which way the call went, when it
     * began, read - by its new name. A messenger call's caller is not among them: Honor's names are
     * for phone calls alone.
     */
    @Synchronized
    fun renamed(context: Context, from: String, to: String) {
        Keep.renamed(context, from, to)
        Messages.renamed(context, from, to)
        Directions.renamed(context, from, to)
        Starts.renamed(context, from, to)
        Parts.renamed(context, from, to)
        val read = readNames(context)
        if (from in read) {
            context.getSharedPreferences(READ_FILE, Context.MODE_PRIVATE).edit { putStringSet(KEY_READ, read - from + to) }
        }
    }

    /**
     * For each of [recordings] whose file has not been read yet, what it says,
     * noted where the list looks: the preferences. After a reinstall that is
     * every recording, once; after that, only one that came from somewhere
     * else. Each is read once - a mark changed since is the preferences' to
     * say - unless reading it failed, when it is tried again at the next list.
     *
     * A name not listed stays read, the newest [NEWEST] of them. It used to go
     * at once, so a file that left the list and came back was read again, and
     * a keep mark the user had removed, on a file this app may not write, came
     * back from it. A file leaves the list for Recently deleted, for a query
     * that failed, for a permission taken back or another folder chosen, and
     * only the first can be told from here; none of them makes it a new file.
     * The bound is so this does not grow with every recording ever deleted:
     * past it a name goes, and its file is read again if it ever returns.
     */
    @Synchronized
    fun recover(context: Context, recordings: List<Recording>) {
        val seen = readNames(context)
        val names = recordings.map { it.name }.toSet()
        val unread = recordings.filter { it.name !in seen && it.parsedName != null }
        val results = unread.map { it.name to RecordingTags.read(context, it) }
        val found = results.mapNotNull { (name, result) -> result.getOrNull()?.let { name to it } }
        val failed = results.count { it.second.isFailure }
        if (found.isNotEmpty() || failed > 0) {
            note(context, found, names)
            Log.i(TAG, "tags: read ${unread.size - failed} recording(s), ${found.size} with JemRec's tags, $failed to try again")
        }
        val now = (seen intersect names) + results.filter { it.second.isSuccess }.map { it.first } + newest(seen - names) { it }
        if (now != seen) {
            context.getSharedPreferences(READ_FILE, Context.MODE_PRIVATE).edit { putStringSet(KEY_READ, now) }
        }
    }

    /** What [found] says, into the store each mark has always lived in. */
    private fun note(context: Context, found: List<Pair<String, RecordingTags.Facts>>, listed: Set<String>) {
        val messages = found.filter { it.second.message }.map { it.first }
        if (messages.isNotEmpty()) Messages.mark(context, messages, listed)
        val keeps = found.filter { it.second.kept }.map { it.first }
        if (keeps.isNotEmpty()) Keep.set(context, keeps, kept = true)
        Directions.note(context, found.mapNotNull { (name, f) -> f.incoming?.let { name to it } }.toMap(), listed)
        Starts.note(context, found.mapNotNull { (name, f) -> f.startedAt?.let { name to Starts.Start(it, f.callAt) } }.toMap(), listed)
        Parts.mark(context, found.filter { it.second.part }.map { it.first }, listed)
        // Only where the name does not say who: the date and number formats'.
        Callers.note(
            context,
            found.mapNotNull { (name, f) ->
                val parsed = FileNames.parse(name)
                f.caller?.takeIf { parsed?.app != null && parsed.caller == null }?.let { name to it }
            }.toMap(),
            listed,
        )
    }

    // --- what a row shows ------------------------------------------------------

    /** [recordings] with what the stores say of each: kept, and a message. */
    fun onto(context: Context, recordings: List<Recording>): List<Recording> {
        val kept = Keep.all(context)
        val messages = Messages.all(context)
        val parts = Parts.all(context)
        return recordings
            .map { if (it.name in kept) it.copy(kept = true) else it }
            .map { if (it.name in messages) it.copy(message = true) else it }
            .map { if (it.name in parts) it.copy(part = true) else it }
    }

    /**
     * Who each call was with. Looked up in one query for the whole list, and
     * only if the user has allowed it; ungranted, a name with the caller in
     * it (FileNames) still says who, and anything else keeps its time as its
     * headline.
     */
    fun withCallers(context: Context, recordings: List<Recording>): List<Recording> {
        val callers = CallLogLookup.labelsFor(context, recordings)
        val noted = if (recordings.any { it.parsedName?.app != null }) Callers.all(context) else emptyMap()
        val named = recordings.map { recording ->
            val caller = callers[recording.name]
            val fromName = recording.parsedName
            // A messenger call is never in the call log: who it was comes
            // from its name, or from what was noted when it was saved, or it
            // is simply that app's call.
            val messenger = recording.app?.let { app -> noted[recording.name] ?: "$app call" }
            recording.copy(
                caller = caller?.label ?: fromName?.caller ?: fromName?.number ?: messenger,
                callerPhoto = caller?.photo,
                number = caller?.number ?: fromName?.number,
                callerFavorite = caller?.favorite ?: false,
            )
        }
        return withDirections(context, named, callers)
    }

    /** Which way each call went, for the names that do not say: as the saver
     *  noted it, else as the call log has it. */
    fun withDirections(
        context: Context,
        recordings: List<Recording>,
        callers: Map<String, CallLogLookup.Caller> = emptyMap(),
    ): List<Recording> {
        if (recordings.none { it.parsedName != null && it.parsedName?.incoming == null }) return recordings
        val noted = Directions.all(context)
        return recordings.map { recording ->
            if (recording.parsedName?.incoming != null) return@map recording
            val incoming = noted[recording.name] ?: callers[recording.name]?.incoming ?: return@map recording
            recording.copy(incoming = incoming)
        }
    }
}

/** The stores' names from before they had one owner. */
internal typealias KeepMarks = RecordingMarks.Keep
internal typealias MessageMarks = RecordingMarks.Messages
