package com.jemcik.jemrec.recordings

import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import java.io.IOException
import java.io.InputStream

/** A recording in the phone's Recordings folder, through MediaStore. */
internal class MediaStoreItem(private val uri: Uri) : RecordingLocation {

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

    override fun read(context: Context): InputStream =
        context.contentResolver.openInputStream(uri) ?: error("no stream")

    override fun change(context: Context): ParcelFileDescriptor? =
        context.contentResolver.openFileDescriptor(uri, "rw")
}

/**
 * The phone's Recordings folder, through MediaStore: the default place.
 * Android has a trash of its own here (see Trash), and the requests for the
 * user's yes on what it does not count as this app's are its own too.
 */
internal object MediaStoreRecordings {

    private const val TAG = "JemRec"

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
