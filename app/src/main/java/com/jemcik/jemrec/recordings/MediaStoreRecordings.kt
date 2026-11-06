package com.jemcik.jemrec.recordings

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.IOException

/** A recording in the phone's Recordings folder, through MediaStore. */
internal class MediaStoreItem(uri: Uri) : ProviderItem(uri) {

    override fun trash(context: Context, name: String, expiresAtMillis: Long): DeleteResult =
        MediaStoreRecordings.setTrashed(context, uri, trashed = true)

    override fun restore(context: Context, name: String): DeleteResult =
        MediaStoreRecordings.setTrashed(context, uri, trashed = false)

    /** Null is MediaStore's question for the user, not a failure. */
    override fun deleteForGood(context: Context): Boolean? = try {
        // Trashed rows included, as the restore's update must ask (setTrashed).
        // A delete by the recording's own uri reached its trashed row without
        // it as well, on the Android 17 emulator (2026-10-23, the emulator
        // check recently_deleted); asked anyway, as nothing promises that.
        context.contentResolver.delete(uri, MediaStoreRecordings.trashedToo()) > 0
    } catch (_: SecurityException) {
        null
    }
}

/**
 * The phone's Recordings folder, through MediaStore: the default place.
 * Android has a trash of its own here (see Trash), and the requests for the
 * user's yes on what it does not count as this app's are its own too.
 */
internal object MediaStoreRecordings {

    private const val TAG = "JemRec"

    private val collection: Uri
        get() = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /** The columns the list reads, by place: MediaStore's own names. */
    private val COLUMNS = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.DISPLAY_NAME,
        MediaStore.Audio.Media.SIZE,
        MediaStore.Audio.Media.DATE_MODIFIED,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.DATE_EXPIRES,
    )

    /** Somewhere to write [name] in Recordings/JemRec. */
    fun open(context: Context, name: String): RecordingStore.Target? = runCatching {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, RecordingStore.MIME)
            put(MediaStore.Audio.Media.RELATIVE_PATH, RecordingStore.relativePath())
            // Hidden from other apps until the recording is finished, so a
            // half-written file never shows up in a music app mid-call.
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("MediaStore insert returned null")
        val pfd = resolver.openFileDescriptor(uri, "rw")
            ?: error("openFileDescriptor returned null")

        RecordingStore.Target(
            name = name,
            descriptor = pfd,
            uri = uri,
            onFinish = {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
                    null, null,
                )
            },
            onAbandon = { resolver.delete(uri, null, null) },
            nameWhenFinished = { RecordingStore.nameOf(context, uri) },
        )
    }.onFailure {
        Log.w(TAG, "store: could not write to ${RecordingStore.relativePath()}", it)
    }.getOrNull()

    /** The default location. MediaStore knows the duration already, which
     *  saves opening every file just to ask how long it is. */
    fun list(context: Context): List<Recording> = runCatching {
        val newestFirst = inRecordingsFolder().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.Audio.Media.DATE_MODIFIED} DESC")
        }
        rows(context, newestFirst, trashed = false)
    }.onFailure { Log.w(TAG, "recordings: MediaStore query failed", it) }.getOrDefault(emptyList())

    /** What is in Android's trash. */
    fun trashed(context: Context): List<Recording> = runCatching {
        val onlyTrashed = inRecordingsFolder().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY) }
        rows(context, onlyTrashed, trashed = true)
    }.onFailure { Log.w(TAG, "trash: MediaStore query failed", it) }.getOrDefault(emptyList())

    /** The one selection: Recordings/JemRec, however the path is written. */
    private fun inRecordingsFolder() = Bundle().apply {
        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?")
        putStringArray(
            ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
            arrayOf("%${Environment.DIRECTORY_RECORDINGS}/${RecordingStore.FOLDER}%"),
        )
    }

    private fun rows(context: Context, args: Bundle, trashed: Boolean): List<Recording> {
        val out = mutableListOf<Recording>()
        context.contentResolver.query(collection, COLUMNS, args, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val shown = cursor.getString(1) ?: continue
                val uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
                // Android's own pending row is left out of this query. One made
                // through a chosen folder that is this one has no such mark.
                if (!trashed && cursor.getLong(2) == 0L && RecordingStore.holdsNothing(context, uri)) continue
                val recording = Recordings.describe(
                    // Android renames the file on disk while it is trashed,
                    // and the name column follows it; the original is inside.
                    name = if (trashed) TrashNames.parse(shown)?.first ?: shown else shown,
                    uri = uri,
                    sizeBytes = cursor.getLong(2),
                    durationMillis = cursor.getLong(4),
                    // DATE_MODIFIED is in SECONDS, not milliseconds. Treating
                    // it as millis dates every recording to January 1970.
                    // The same in the trash, or a recording would change its
                    // time on the way in.
                    whenMillis = cursor.getLong(3) * 1000,
                )
                out += if (trashed) recording.copy(expiresAtMillis = cursor.getLong(5) * 1000) else recording
            }
        }
        return out
    }

    /** Including trashed rows, or a restore matches nothing: they are left
     *  out of every query and update by default. */
    fun trashedToo() = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }

    fun setTrashed(context: Context, uri: Uri, trashed: Boolean): DeleteResult = try {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, if (trashed) 1 else 0) }
        val rows = context.contentResolver.update(uri, values, trashedToo())
        if (rows > 0) DeleteResult.Deleted
        else DeleteResult.Failed(IOException("MediaStore changed no rows for [$uri]"))
    } catch (e: SecurityException) {
        // Not ours as far as MediaStore is concerned - typically a recording
        // made before a reinstall. The user still owns it, and can say so.
        Log.i(TAG, "trash: need the user's consent for [$uri]")
        askToTrash(context, listOf(uri), trashed)?.let { DeleteResult.NeedsConsent(it) } ?: DeleteResult.Failed(e)
    } catch (t: Throwable) {
        DeleteResult.Failed(t)
    }

    /** One request for all of [uris], into the trash or out of it; null if
     *  there are none, or it cannot be made. */
    fun askToTrash(context: Context, uris: List<Uri>, trashed: Boolean): IntentSender? =
        if (uris.isEmpty()) null
        else runCatching { MediaStore.createTrashRequest(context.contentResolver, uris, trashed).intentSender }
            .getOrNull()

    /** The same, to delete them for good. */
    fun askToDelete(context: Context, uris: List<Uri>): IntentSender? =
        if (uris.isEmpty()) null
        else runCatching { MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender }
            .getOrNull()
}
