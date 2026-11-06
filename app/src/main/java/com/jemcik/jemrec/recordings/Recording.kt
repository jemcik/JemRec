package com.jemcik.jemrec.recordings

import android.content.Context
import android.content.IntentSender
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.jemcik.jemrec.calls.VoipApps
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One recording, described the way a person would describe it.
 *
 * The list used to show `20260907_165331_out.ogg  (339 KB)`, which is the
 * filename and the number of bytes - true, and useless. What someone wants to
 * know is when the call was, which way it went, and how long it lasted, and
 * then to tap it and hear it.
 */
data class Recording(
    val name: String,
    val uri: Uri,
    val whenLabel: String,
    val incoming: Boolean,
    val durationLabel: String,
    val sizeLabel: String,
    /**
     * Set only for recordings still sitting in app-private storage.
     *
     * Those reach the UI as FileProvider uris, which a ContentResolver will
     * happily read and flatly refuse to delete. Deleting one means deleting a
     * file, so the path has to survive the trip.
     */
    val filePath: String? = null,
    /**
     * Who the call was with: a contact name, or a number, or null.
     *
     * Null is the ordinary case, not a failure - it means the call log has not
     * been offered to the app, or the call is not in it.
     */
    val caller: String? = null,
    /** The contact's photo, when there is one and we may read it. */
    val callerPhoto: Uri? = null,
    /**
     * The raw dialled number, when the call log gave us one. Held separately
     * from [caller] so search matches a number even when the row shows a
     * contact's name instead.
     */
    val number: String? = null,
    /** The call was with a contact starred as a favourite in the address book.
     *  Shown as a small star and filtered by the Favourites chip. */
    val callerFavorite: Boolean = false,
    /** How long the call was, in milliseconds; 0 when it could not be read.
     *  [durationLabel] is what is shown; this is what auto-delete compares. */
    val durationMillis: Long = 0,
    /** Marked to keep: never auto-deleted. See RecordingMarks. */
    val kept: Boolean = false,
    /** A message the answering machine took. See RecordingMarks. */
    val message: Boolean = false,
    /** Set only for a recording in the trash: when it goes for good. */
    val expiresAtMillis: Long? = null,
    /** Set only where a row shows this recording for a call of its own - the
     *  Calls tab: that call's time, shown in place of the recording's. A call
     *  that came in during another's recording (call waiting) shares it, and
     *  its row read the first call's time. */
    val shownAt: Long? = null,
) {
    /** When the call was, read from the name in any of its formats (see
     *  FileNames.parse); null if the name is not one of ours. Parsed once per
     *  instance: the list groups and sorts every row by it. */
    val startedAtMillis: Long? get() = parsedName?.startedAt

    /** Which of the three places it lives in. */
    internal val location: RecordingLocation get() = RecordingLocation.of(uri, filePath)

    /** What the name says - time, and for some formats direction, caller and
     *  number. Read once: the list asks it of every row more than once. */
    internal val parsedName: FileNames.Parsed? by lazy { FileNames.parse(name) }

    /** The messenger a call was in - "Viber" - or null for a phone call. */
    val app: String? get() = parsedName?.app?.let(VoipApps::labelForTag)

    /** Whether [incoming] is known. A messenger call whose app did not say
     *  which way it went shows no arrow rather than a guessed one. */
    val directionKnown: Boolean get() = parsedName?.let { it.app == null || it.incoming != null } ?: true

    /** Whoever the call was with takes the headline when it is known, so the
     *  time steps down into the subtitle rather than being dropped. */
    val title: String get() = caller ?: whenLabel

    /** The direction is drawn as an arrow beside this, not spelled out in it. */
    val subtitle: String
        get() = if (caller == null) {
            "$durationLabel · $sizeLabel"
        } else {
            "$whenLabel · $durationLabel"
        }
}

/** The outcome of moving a whole list of them to the trash at once. */
data class BulkDeleteResult(
    val deleted: Int,
    val failed: Int,
    /** One request covering everything MediaStore would not take our word for,
     *  so the user sees a single dialog rather than one per recording. */
    val needsConsent: IntentSender?,
    /** The names that request is about. */
    val disputed: List<String> = emptyList(),
)

/**
 * What happened when we tried to move a recording to the trash, or back out of
 * it (see Trash).
 *
 * NeedsConsent is the interesting one. MediaStore lets an app trash media it
 * owns, and ownership is recorded per package - so it normally just works. When
 * it does not, the platform does not want a bare failure either: it hands back
 * a request the user can approve in a system dialog. Throwing that away and
 * reporting "could not delete" would leave someone unable to remove their own
 * recording from inside the app that made it.
 */
sealed interface DeleteResult {
    data object Deleted : DeleteResult
    data class NeedsConsent(val request: IntentSender) : DeleteResult
    data class Failed(val error: Throwable) : DeleteResult
}

internal object Recordings {

    /**
     * A stand-in list, for taking the README's screenshots.
     *
     * Screenshots of a call recorder are a problem of their own: the real list
     * is a person's real calls, with their contacts' names and numbers in it,
     * and no amount of cropping makes that publishable. So the list has one
     * seam, and a DEBUG-ONLY provider fills it with invented calls (see
     * app/src/debug/.../DemoRecordings.kt).
     *
     * Nothing in the main source set ever writes to this. The debug source set
     * does not exist in a release APK, so a shipped build has no code that
     * could - which is why this is a plain nullable rather than a flag read
     * from preferences.
     */
    @Volatile
    var override: ((Context) -> List<Recording>)? = null

    /** Every recording, newest first: RecordingLibrary's, or the stand-in. */
    fun list(context: Context, withCallers: Boolean = true): List<Recording> {
        override?.let { return it(context) }
        return RecordingLibrary.list(context, withCallers = withCallers)
    }

    /**
     * A recording as the list shows it, from what its source knows.
     * [whenMillis] is the file's own date, shown only for a name that does
     * not say when the call was: a file's date moves whenever it is written,
     * and marking a recording kept writes it (RecordingTags) - its date moved
     * 4 s on the Android 17 emulator (2026-10-24), and with it the time on a
     * row with no caller. The name's time is the call's start, as the day it
     * is listed under and a named row's time already were.
     */
    internal fun describe(
        name: String,
        uri: Uri,
        sizeBytes: Long,
        durationMillis: Long,
        whenMillis: Long = 0L,
    ): Recording {
        // Once: every row of every list comes through here.
        val parsed = FileNames.parse(name)
        return Recording(
            name = name,
            uri = uri,
            whenLabel = whenLabel(parsed?.startedAt ?: whenMillis),
            incoming = parsed?.incoming ?: RecordingStore.isIncoming(name),
            durationLabel = duration(durationMillis),
            sizeLabel = size(sizeBytes),
            durationMillis = durationMillis.coerceAtLeast(0),
        )
    }

    /** The same, for a source that does not know the duration: the file is
     *  opened to ask. */
    internal fun describe(
        context: Context,
        name: String,
        uri: Uri,
        sizeBytes: Long,
        whenMillis: Long = 0L,
    ) = describe(name, uri, sizeBytes, durationOf(context, uri), whenMillis)

    /** "Today 16:53" reads better than a date for the common case, which is a
     *  call from the last few minutes. */
    internal fun whenLabel(millis: Long): String {
        val stamp = Date(millis)
        val day = SimpleDateFormat("yyyyMMdd", Locale.US)
        val time = SimpleDateFormat("HH:mm", Locale.US).format(stamp)
        return if (day.format(stamp) == day.format(Date())) {
            "Today $time"
        } else {
            SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(stamp)
        }
    }

    private fun duration(millis: Long): String {
        if (millis <= 0) return "--:--"
        val total = millis / 1000
        return "%d:%02d".format(Locale.US, total / 60, total % 60)
    }

    /** For sources that do not already know the duration, in milliseconds.
     *  Opens the file, so callers must be off the main thread - and they are. */
    private fun durationOf(context: Context, uri: Uri): Long = try {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        }
    } catch (t: Throwable) {
        // A recording whose duration cannot be read still plays, and is still
        // worth listing: it shows "--:--" rather than being hidden.
        0L
    }

    private fun size(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "%d KB".format(bytes / 1024)
        else -> "$bytes B"
    }
}
