package com.jemcik.jemrec.recordings

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import com.jemcik.jemrec.Prefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * What a save is doing to its places, written down before it does it, so
 * that a save cut short - the app's process killed in the middle, as Android
 * kills one it needs the memory of - is finished or undone by the next save
 * of the same recorder file, never left half way: a write-ahead log, as a
 * database keeps one for the same reason.
 *
 * Without it, on the Android 17 emulator (killed_mid_save, 2026-11-18): a
 * save killed just after publishing was saved again at the next start, as
 * "(1)" - the ledger's note that it was saved and the recorder's delete come
 * after the publish; one killed while writing left its MediaStore row
 * pending, hidden, for Android's seven days (DATE_EXPIRES) - the whole
 * recording's size once it was written. In a folder the user picked, a file is made under the
 * recording's own name at once - a folder has nothing like MediaStore's
 * pending row - and the kill left it there, seen by every other app.
 *
 * Two stages a recorder file is in while it is saved:
 *  - [Writing]: the places opened so far. Cut short here, they are deleted:
 *    none is a whole recording, and the recorder's copy is saved again.
 *  - [Publishing]: every part written whole and copied into place, with
 *    what publishing does after - the ledger's note, the marks. Cut short
 *    here, the next save publishes what is still pending and notes the rest,
 *    and deletes the recorder's copy, saving nothing twice.
 *
 * Kept with commit(), not apply(): apply "starts an asynchronous commit to
 * disk" (SharedPreferences.Editor.apply), and a process killed meanwhile
 * loses it. The same file as the ledger, so a commit here also writes out
 * the ledger's note applied just before it.
 *
 * A kill between a place made and its line here leaves that place unknown:
 * MediaStore's pending row is removed by Android when it expires (its
 * DATE_EXPIRES), a picked folder's empty file stays, hidden from JemRec's
 * list (FolderRecordings.list) - a window of the few calls that make and
 * open the place. A place made and then failing to open is let go there
 * (MediaStoreRecordings.open, FolderRecordings.open).
 */
internal object SaveJournal {

    private const val KEY = "save_journal"

    /** A place a part is written into, as RecordingLocation.of takes it. */
    data class Place(val uri: Uri, val filePath: String?)

    /** A part written whole, about to be published, and what publishing
     *  notes of it. */
    data class Part(
        val place: Place,
        /** Its name as the place gave it before publishing. */
        val name: String,
        val startMillis: Long,
        val incoming: Boolean,
        val seconds: Int,
        /** A phone call's part: noted for the missed-call check and its
         *  direction; otherwise a messenger's, noted only as saved. */
        val phoneCall: Boolean,
        val message: Boolean,
        /** Published by the save itself: gone later is the user's doing,
         *  not a part lost. */
        val published: Boolean = false,
        /** A part joined mid-call's own call start, for the ledger
         *  (CallLedger.Recorded.callAt); -1 for none. */
        val callAt: Long = -1,
        /** One of a call's recordings (RecordingSaver.save's part). */
        val part: Boolean = false,
    )

    /** What a place of a save cut short holds now; null where its
     *  provider would not say - never taken for gone (review, 2026-11-18). */
    enum class Left {
        /** MediaStore's row, written whole and not yet published. */
        PENDING,

        /** There, with bytes, seen by other apps. */
        THERE,

        /** No such file, or nothing in it. */
        MISSING,
    }

    /** A call in the recording that the rules leave out, noted in the
     *  ledger with the parts once they are published (CallLedger.noteSkipped):
     *  the recording's own call is noted at the file's start, which tells the
     *  next collection the file was saved - noted before, a save that failed
     *  had its retry delete the recorder's only copy (the 1.2 capped
     *  review). */
    data class Skip(val start: Long, val incoming: Boolean, val seconds: Int, val callAt: Long = -1)

    sealed interface Entry { val recfile: String }
    data class Writing(override val recfile: String, val places: List<Place>) : Entry
    data class Publishing(
        override val recfile: String,
        val parts: List<Part>,
        /** That it stopped early, to be said once it is published. */
        val early: StoppedEarly.Early? = null,
        /** The calls the rules leave out, noted with the parts. */
        val skips: List<Skip> = emptyList(),
    ) : Entry

    /** [place] opened for [recfile]: deleted if the save is cut short. */
    @Synchronized
    fun opened(context: Context, recfile: String, place: Place) {
        val now = of(context, recfile)
        val places = (now as? Writing)?.places.orEmpty() + place
        put(context, recfile, Writing(recfile, places))
    }

    /** Every part of [recfile] written whole: published if cut short. */
    @Synchronized
    fun publishing(
        context: Context,
        recfile: String,
        parts: List<Part>,
        early: StoppedEarly.Early? = null,
        skips: List<Skip> = emptyList(),
    ) = put(context, recfile, Publishing(recfile, parts, early, skips))

    /** One part of [recfile], at [uri], published: written down at once, so
     *  a part gone later is told from one never published (pickUp). */
    @Synchronized
    fun published(context: Context, recfile: String, uri: Uri) {
        val now = of(context, recfile) as? Publishing ?: return
        put(context, recfile, now.copy(parts = now.parts.map { if (it.place.uri == uri) it.copy(published = true) else it }))
    }

    /** The early stop of [recfile] told: taken out of its entry, so a save
     *  cut short after the telling is not told again by the one that
     *  finishes it. Told at least once: killed between the telling and
     *  this, it is told once more - never not at all (review round 10). */
    @Synchronized
    fun told(context: Context, recfile: String) {
        val now = of(context, recfile) as? Publishing ?: return
        if (now.early != null) put(context, recfile, now.copy(early = null))
    }

    /** [recfile]'s save came to an end, whichever: nothing left to do. */
    @Synchronized
    fun done(context: Context, recfile: String) {
        if (of(context, recfile) != null) put(context, recfile, null)
    }

    /** What a save of [recfile] cut short left; null for none. */
    @Synchronized
    fun of(context: Context, recfile: String): Entry? = all(context).firstOrNull { it.recfile == recfile }

    /** Every save cut short, whatever the recorder still has. */
    @Synchronized
    fun all(context: Context): List<Entry> =
        Prefs.of(context).getStringSet(KEY, emptySet()).orEmpty().mapNotNull(::decode)

    private fun put(context: Context, recfile: String, entry: Entry?) {
        val prefs = Prefs.of(context)
        // Each file's one entry - and none that cannot be read, kept for
        // ever otherwise (review, 2026-11-18).
        val others = prefs.getStringSet(KEY, emptySet()).orEmpty().filter { decode(it)?.recfile.let { r -> r != null && r != recfile } }.toSet()
        val next = if (entry == null) others else others + encode(entry)
        // commit(): see the class's comment.
        prefs.edit(commit = true) { putStringSet(KEY, next) }
    }

    internal fun encode(entry: Entry): String {
        val json = JSONObject().put("recfile", entry.recfile)
        when (entry) {
            is Writing -> json.put("stage", "writing")
                .put("places", JSONArray(entry.places.map(::placeJson)))
            is Publishing -> json.put("stage", "publishing").apply {
                if (entry.skips.isNotEmpty()) {
                    put(
                        "skips",
                        JSONArray(
                            entry.skips.map {
                                JSONObject().put("start", it.start).put("incoming", it.incoming)
                                    .put("seconds", it.seconds).put("callAt", it.callAt)
                            },
                        ),
                    )
                }
                entry.early?.let {
                    put("early", JSONObject().put("start", it.start).put("missed", it.missed).put("ended", it.endedAt).put("carried", it.carried))
                }
            }.put(
                "parts",
                JSONArray(
                    entry.parts.map { p ->
                        placeJson(p.place).put("name", p.name).put("start", p.startMillis)
                            .put("incoming", p.incoming).put("seconds", p.seconds)
                            .put("phoneCall", p.phoneCall).put("message", p.message).put("published", p.published)
                            .apply { if (p.callAt >= 0) put("callAt", p.callAt) }
                            .apply { if (p.part) put("part", true) }
                    },
                ),
            )
        }
        return json.toString()
    }

    /** Null for anything it cannot read: an entry it does not understand is
     *  dropped, never a reason to stop saving. */
    internal fun decode(text: String): Entry? = runCatching {
        val json = JSONObject(text)
        val recfile = json.getString("recfile")
        when (json.getString("stage")) {
            "writing" -> {
                val places = json.getJSONArray("places")
                Writing(recfile, (0 until places.length()).map { place(places.getJSONObject(it)) })
            }
            "publishing" -> {
                val parts = json.getJSONArray("parts")
                val early = json.optJSONObject("early")?.let {
                    StoppedEarly.Early(it.getLong("start"), it.getLong("missed"), it.getLong("ended"), it.optBoolean("carried", false))
                }
                Publishing(
                    recfile,
                    (0 until parts.length()).map {
                        val p = parts.getJSONObject(it)
                        Part(
                            place(p), p.getString("name"), p.getLong("start"), p.getBoolean("incoming"),
                            p.getInt("seconds"), p.getBoolean("phoneCall"), p.getBoolean("message"),
                            p.optBoolean("published", false), p.optLong("callAt", -1), p.optBoolean("part", false),
                        )
                    },
                    early,
                    json.optJSONArray("skips")?.let { skips ->
                        (0 until skips.length()).map {
                            val s = skips.getJSONObject(it)
                            Skip(s.getLong("start"), s.getBoolean("incoming"), s.getInt("seconds"), s.optLong("callAt", -1))
                        }
                    }.orEmpty(),
                )
            }
            else -> null
        }
    }.getOrNull()

    private fun placeJson(place: Place) = JSONObject().put("uri", place.uri.toString())
        .apply { place.filePath?.let { put("path", it) } }

    private fun place(json: JSONObject) =
        Place(Uri.parse(json.getString("uri")), json.optString("path").takeIf { json.has("path") })
}
