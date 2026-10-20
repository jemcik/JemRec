package com.jemcik.jemrec.capture

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/**
 * Recently deleted: where a deleted recording waits for [DAYS] days before it
 * is gone for good, and where it can be restored from until then.
 *
 * A call recording is the one thing in this app that cannot be made again, and
 * delete used to be immediate and final - from the list, from the after-call
 * notification, and soon from auto-delete, which deletes things nobody is
 * looking at. So delete now means "move to the trash", and only time empties
 * it.
 *
 * THE THREE PLACES A RECORDING CAN LIVE TRASH DIFFERENTLY, and none of the
 * ways generalises:
 *
 *   - The phone's Recordings folder, through MediaStore (the default): Android
 *     has a trash of its own (IS_TRASHED, Android 11+). The file disappears
 *     from every other app at once, shows in the Files app's trash, and
 *     Android itself removes it when DATE_EXPIRES passes - 30 days - with no
 *     help from JemRec. An app may trash and restore what it owns without
 *     asking. Anything it does not own - a file from before a reinstall -
 *     needs the user's yes in a system dialog, as delete always did.
 *   - A folder the user picked: no trash exists there, so the file is renamed
 *     to the name Android's own trash uses on disk, ".trashed-<expiry>-<name>".
 *     The leading dot hides it from most file managers, the expiry is in the
 *     name, and JemRec deletes it once that passes (see [purgeExpired]).
 *   - App storage, for recordings from before the Recordings folder was the
 *     default: the same rename, on the file.
 */
object Trash {

    private const val TAG = "JemRec"

    /** How long a deleted recording waits. Android's own trash keeps 30 days,
     *  and the other two places match it. */
    const val DAYS = 30
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val PREFIX = ".trashed-"

    // --- names, for the two places that trash by renaming -------------------

    /**
     * The trashed name of [name], expiring at [expiresAtMillis].
     *
     * With no @ in it. Honor's Phone app plays any file in its folder whose
     * name holds "@<number>_<time>" - tested: a leading dot does not stop
     * it, and nor does anything after the time - so a recording deleted from
     * there (FileNames.Format.HONOR) would stay playable from the call
     * history for its 30 days here. The @ is written %40, and % as %25 so
     * that reads back unchanged; names from before this had neither.
     */
    internal fun nameFor(name: String, expiresAtMillis: Long): String =
        "$PREFIX${expiresAtMillis / 1000}-${name.replace("%", "%25").replace("@", "%40")}"

    /** The original name and expiry of a trashed name, or null if it is not one. */
    internal fun parse(trashedName: String): Pair<String, Long>? {
        if (!trashedName.startsWith(PREFIX)) return null
        val rest = trashedName.removePrefix(PREFIX)
        val seconds = rest.substringBefore('-', "").toLongOrNull() ?: return null
        val name = rest.substringAfter('-', "").takeIf { it.isNotEmpty() } ?: return null
        return ESCAPED.replace(name) { if (it.value == "%40") "@" else "%" } to seconds * 1000
    }

    private val ESCAPED = Regex("%40|%25")

    fun isTrashedName(name: String): Boolean = parse(name) != null

    // --- trash and restore ----------------------------------------------------

    /** Move one recording to the trash. Deleted means it is there now. */
    fun trash(context: Context, uri: Uri, filePath: String?, name: String): DeleteResult {
        val expires = System.currentTimeMillis() + DAYS * DAY_MS
        filePath?.let { path ->
            val file = File(path)
            if (!file.exists()) return DeleteResult.Deleted
            val target = File(file.parentFile, nameFor(name, expires))
            return if (file.renameTo(target)) DeleteResult.Deleted
            else DeleteResult.Failed(IOException("could not move $path to the trash"))
        }
        return if (uri.authority == MediaStore.AUTHORITY) {
            setTrashed(context, listOf(uri), trashed = true)
        } else {
            renameDocument(context, uri, nameFor(name, expires))
        }
    }

    /** Move these to the trash, asking once for whatever MediaStore will not
     *  take this app's word for. */
    fun trashAll(context: Context, recordings: List<Recording>): BulkDeleteResult {
        var done = 0
        var failed = 0
        val disputed = mutableListOf<Uri>()
        recordings.forEach { recording ->
            when (val result = trash(context, recording.uri, recording.filePath, recording.name)) {
                is DeleteResult.Deleted -> done++
                is DeleteResult.NeedsConsent -> disputed += recording.uri
                is DeleteResult.Failed -> {
                    failed++
                    Log.w(TAG, "trash: could not trash ${recording.name}", result.error)
                }
            }
        }
        return BulkDeleteResult(done, failed, consent(context, disputed, trashed = true))
    }

    /** Bring one back from the trash, as it was. Deleted means restored. */
    fun restore(context: Context, recording: Recording): DeleteResult {
        recording.filePath?.let { path ->
            val file = File(path)
            val target = File(file.parentFile, recording.name)
            return if (file.renameTo(target)) DeleteResult.Deleted
            else DeleteResult.Failed(IOException("could not restore $path"))
        }
        return if (recording.uri.authority == MediaStore.AUTHORITY) {
            setTrashed(context, listOf(recording.uri), trashed = false)
        } else {
            renameDocument(context, recording.uri, recording.name)
        }
    }

    private fun setTrashed(context: Context, uris: List<Uri>, trashed: Boolean): DeleteResult = try {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, if (trashed) 1 else 0) }
        // Including trashed rows, or a restore matches nothing: they are left
        // out of every query and update by default.
        val extras = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }
        val rows = uris.sumOf { context.contentResolver.update(it, values, extras) }
        if (rows > 0) DeleteResult.Deleted
        else DeleteResult.Failed(IOException("MediaStore changed no rows for $uris"))
    } catch (e: SecurityException) {
        // Not ours as far as MediaStore is concerned - typically a recording
        // made before a reinstall. The user still owns it, and can say so.
        Log.i(TAG, "trash: need the user's consent for $uris")
        consent(context, uris, trashed)?.let { DeleteResult.NeedsConsent(it) } ?: DeleteResult.Failed(e)
    } catch (t: Throwable) {
        DeleteResult.Failed(t)
    }

    private fun consent(context: Context, uris: List<Uri>, trashed: Boolean) =
        if (uris.isEmpty()) null
        else runCatching { MediaStore.createTrashRequest(context.contentResolver, uris, trashed).intentSender }
            .getOrNull()

    private fun renameDocument(context: Context, uri: Uri, to: String): DeleteResult = runCatching {
        if (DocumentsContract.renameDocument(context.contentResolver, uri, to) != null) {
            DeleteResult.Deleted
        } else {
            DeleteResult.Failed(IOException("the folder would not rename $uri")) as DeleteResult
        }
    }.getOrElse { DeleteResult.Failed(it) }

    // --- what is in it --------------------------------------------------------

    /** Everything in the trash, soonest to go first. Each has [Recording.name]
     *  as it was before, and [Recording.expiresAtMillis] set. */
    fun list(context: Context): List<Recording> {
        val found = (fromMediaStore(context) + fromChosenFolder(context) + fromAppStorage(context))
            .distinctBy { it.name }
            .sortedBy { it.expiresAtMillis }
        return Recordings.withCallers(context, found)
    }

    private fun fromMediaStore(context: Context): List<Recording> = runCatching {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val args = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?",
            )
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf("%${Environment.DIRECTORY_RECORDINGS}/${RecordingStore.FOLDER}%"),
            )
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
        }
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_EXPIRES,
            MediaStore.Audio.Media.DATE_MODIFIED,
        )
        val out = mutableListOf<Recording>()
        context.contentResolver.query(collection, projection, args, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val shown = cursor.getString(1) ?: continue
                // Android renames the file on disk while it is trashed, and
                // the name column follows it; the original is inside.
                val name = parse(shown)?.first ?: shown
                out += Recordings.describe(
                    name = name,
                    uri = ContentUris.withAppendedId(collection, cursor.getLong(0)),
                    sizeBytes = cursor.getLong(2),
                    durationMillis = cursor.getLong(3),
                    // Dated the way the list dates it, or a recording would
                    // change its time on the way into the trash. Seconds.
                    whenMillis = cursor.getLong(5) * 1000,
                ).copy(expiresAtMillis = cursor.getLong(4) * 1000)
            }
        }
        out
    }.onFailure { Log.w(TAG, "trash: MediaStore query failed", it) }.getOrDefault(emptyList())

    private fun fromChosenFolder(context: Context): List<Recording> =
        Recordings.chosenFolderDocuments(context).mapNotNull { doc ->
            val (name, expires) = parse(doc.name) ?: return@mapNotNull null
            Recordings.describe(context, name, doc.uri, doc.sizeBytes, whenMillis = doc.modifiedMillis)
                .copy(expiresAtMillis = expires)
        }

    private fun fromAppStorage(context: Context): List<Recording> =
        RecordingStore.listAppStorage(context).mapNotNull { file ->
            val (name, expires) = parse(file.name) ?: return@mapNotNull null
            runCatching {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.recordings", file)
                Recordings.describe(context, name, uri, file.length(), whenMillis = file.lastModified())
                    .copy(filePath = file.absolutePath, expiresAtMillis = expires)
            }.getOrNull()
        }

    /**
     * Delete for good whatever has waited its [DAYS] days, in the two places
     * that trash by renaming. MediaStore needs none of this: Android empties
     * its own trash. How many went.
     */
    fun purgeExpired(context: Context, now: Long = System.currentTimeMillis()): Int {
        var gone = 0
        Recordings.chosenFolderDocuments(context).forEach { doc ->
            val (_, expires) = parse(doc.name) ?: return@forEach
            if (expires <= now && runCatching { DocumentsContract.deleteDocument(context.contentResolver, doc.uri) }.getOrDefault(false)) {
                gone++
            }
        }
        RecordingStore.listAppStorage(context).forEach { file ->
            val (_, expires) = parse(file.name) ?: return@forEach
            if (expires <= now && file.delete()) gone++
        }
        if (gone > 0) Log.i(TAG, "trash: removed $gone expired recording(s) for good")
        return gone
    }
}
