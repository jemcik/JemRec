package com.jemcik.jemrec.capture

import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
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
) {
    /** When the call was, parsed from the name (yyyyMMdd_HHmmss...). Drives the
     *  Today / This week presets; null if the name is not one of ours. Parsed
     *  once per instance: the filter asks every row at every keystroke. */
    val startedAtMillis: Long? by lazy {
        runCatching {
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(name.take(15))?.time
        }.getOrNull()
    }

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

/**
 * What happened when we tried to delete a recording.
 *
 * NeedsConsent is the interesting one. MediaStore lets an app delete media it
 * owns, and ownership is recorded per package - so it normally just works. When
 * it does not, the platform does not want a bare failure either: it hands back
 * a request the user can approve in a system dialog. Throwing that away and
 * reporting "could not delete" would leave someone unable to remove their own
 * recording from inside the app that made it.
 */
/** The outcome of removing a whole list of them at once. */
data class BulkDeleteResult(
    val deleted: Int,
    val failed: Int,
    /** One request covering everything MediaStore would not take our word for,
     *  so the user sees a single dialog rather than one per recording. */
    val needsConsent: IntentSender?,
)

sealed interface DeleteResult {
    data object Deleted : DeleteResult
    data class NeedsConsent(val request: IntentSender) : DeleteResult
    data class Failed(val error: Throwable) : DeleteResult
}

internal object Recordings {

    private const val TAG = "JemRec"

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

    /**
     * Everywhere a recording might be, newest first.
     *
     * Three sources rather than one, because the default moved: recordings now
     * go to the phone's standard Recordings folder, a user may have chosen
     * somewhere else, and older ones are still in app storage from before.
     * Listing only the current default would make a user's earlier calls
     * silently vanish from the app.
     */
    fun list(context: Context): List<Recording> {
        override?.let { return it(context) }
        val found = (fromMediaStore(context) + fromChosenFolder(context) + fromAppStorage(context))
            .distinctBy { it.name }
            .sortedByDescending { it.name }
        // Looked up afterwards, in one query for the whole list, and only if
        // the user has allowed it. Ungranted this returns nothing and every
        // recording simply keeps its time as its headline.
        val callers = CallLogLookup.labelsFor(context, found)
        return if (callers.isEmpty()) found
        else found.map { recording ->
            val caller = callers[recording.name]
            recording.copy(
                caller = caller?.label,
                callerPhoto = caller?.photo,
                number = caller?.number,
                callerFavorite = caller?.favorite ?: false,
            )
        }
    }

    /** The default location. MediaStore knows the duration already, which
     *  saves opening every file just to ask how long it is. */
    private fun fromMediaStore(context: Context): List<Recording> = runCatching {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.DURATION,
        )
        val out = mutableListOf<Recording>()
        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("%${Environment.DIRECTORY_RECORDINGS}/${RecordingStore.FOLDER}%"),
            "${MediaStore.Audio.Media.DATE_MODIFIED} DESC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameCol) ?: continue
                out += Recording(
                    name = name,
                    uri = ContentUris.withAppendedId(collection, cursor.getLong(idCol)),
                    // DATE_MODIFIED is in SECONDS, not milliseconds. Treating
                    // it as millis dates every recording to January 1970.
                    whenLabel = whenLabel(cursor.getLong(dateCol) * 1000),
                    incoming = RecordingStore.isIncoming(name),
                    durationLabel = duration(cursor.getLong(durCol)),
                    sizeLabel = size(cursor.getLong(sizeCol)),
                )
            }
        }
        out
    }.onFailure { Log.w(TAG, "recordings: MediaStore query failed", it) }.getOrDefault(emptyList())

    private fun fromChosenFolder(context: Context): List<Recording> = runCatching {
        val tree = RecordingStore.treeUri(context) ?: return emptyList()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree),
        )
        val out = mutableListOf<Recording>()
        context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1) ?: continue
                if (!name.endsWith(".${RecordingStore.EXTENSION}")) continue
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
                out += Recording(
                    name = name,
                    uri = uri,
                    whenLabel = whenLabel(cursor.getLong(3)),
                    incoming = RecordingStore.isIncoming(name),
                    durationLabel = durationOf(context, uri),
                    sizeLabel = size(cursor.getLong(2)),
                )
            }
        }
        out
    }.onFailure { Log.w(TAG, "recordings: chosen folder query failed", it) }.getOrDefault(emptyList())

    /** Recordings made before the standard folder became the default. */
    private fun fromAppStorage(context: Context): List<Recording> =
        RecordingStore.listAppStorage(context)
            .filter { it.isFile && it.length() > 0 }
            .mapNotNull { file ->
                runCatching {
                    val uri = FileProvider.getUriForFile(
                        context, "${context.packageName}.recordings", file,
                    )
                    Recording(
                        name = file.name,
                        uri = uri,
                        whenLabel = whenLabel(file.lastModified()),
                        incoming = RecordingStore.isIncoming(file.name),
                        durationLabel = durationOf(context, uri),
                        sizeLabel = size(file.length()),
                        filePath = file.absolutePath,
                    )
                }.getOrNull()
            }

    /**
     * Remove a recording, wherever it happens to live.
     *
     * Three storage locations means three different ways to delete, and none of
     * them generalises: a MediaStore row is deleted through the resolver, a
     * document in a folder the user picked through DocumentsContract, and an
     * app-storage file by deleting the file. Sending the wrong one at the wrong
     * uri fails quietly, which for a delete button is the worst outcome - the
     * user believes the recording of their call is gone and it is not.
     *
     * Deleting is the one destructive thing this app can do, so it reports what
     * actually happened rather than returning Unit and hoping.
     */
    fun delete(context: Context, recording: Recording): DeleteResult {
        recording.filePath?.let { path ->
            val file = File(path)
            // Already gone counts as deleted. The user asked for it to not be
            // there, and it is not there.
            return if (!file.exists() || file.delete()) DeleteResult.Deleted
            else DeleteResult.Failed(IOException("could not delete $path"))
        }

        return if (recording.uri.authority == MediaStore.AUTHORITY) {
            deleteFromMediaStore(context, recording.uri)
        } else {
            deleteDocument(context, recording.uri)
        }
    }

    /**
     * Remove all of these, for the "delete my recordings too" half of uninstall.
     *
     * Deliberately NOT a loop of confirmation dialogs. Anything MediaStore
     * refuses is gathered into one request the user approves once - being asked
     * forty times is how people start tapping Allow without reading.
     */
    fun deleteAll(context: Context, recordings: List<Recording>): BulkDeleteResult {
        var deleted = 0
        var failed = 0
        val disputed = mutableListOf<Uri>()
        recordings.forEach { recording ->
            when (val result = delete(context, recording)) {
                is DeleteResult.Deleted -> deleted++
                // Its single-uri request is discarded; they are batched below.
                is DeleteResult.NeedsConsent -> disputed += recording.uri
                is DeleteResult.Failed -> {
                    failed++
                    Log.w(TAG, "recordings: could not delete ${recording.name}", result.error)
                }
            }
        }
        val consent = if (disputed.isEmpty()) null else runCatching {
            MediaStore.createDeleteRequest(context.contentResolver, disputed).intentSender
        }.getOrNull()
        return BulkDeleteResult(deleted, failed, consent)
    }

    private fun deleteFromMediaStore(context: Context, uri: Uri): DeleteResult = try {
        val rows = context.contentResolver.delete(uri, null, null)
        if (rows > 0) DeleteResult.Deleted
        else DeleteResult.Failed(IOException("MediaStore deleted no rows for $uri"))
    } catch (e: SecurityException) {
        // We are not the owner as far as MediaStore is concerned. The user
        // still is, and can say so in a system dialog.
        Log.i(TAG, "recordings: need the user's consent to delete $uri")
        runCatching {
            DeleteResult.NeedsConsent(
                MediaStore.createDeleteRequest(context.contentResolver, listOf(uri)).intentSender
            ) as DeleteResult
        }.getOrElse { DeleteResult.Failed(e) }
    } catch (t: Throwable) {
        DeleteResult.Failed(t)
    }

    private fun deleteDocument(context: Context, uri: Uri): DeleteResult = runCatching {
        if (DocumentsContract.deleteDocument(context.contentResolver, uri)) {
            DeleteResult.Deleted
        } else {
            DeleteResult.Failed(IOException("the folder refused to delete $uri")) as DeleteResult
        }
    }.getOrElse { DeleteResult.Failed(it) }

    /** "Today 16:53" reads better than a date for the common case, which is a
     *  call from the last few minutes. */
    private fun whenLabel(millis: Long): String {
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
        return "%d:%02d".format(total / 60, total % 60)
    }

    /** For sources that do not already know the duration. Opens the file, so
     *  callers must be off the main thread - and they are. */
    private fun durationOf(context: Context, uri: Uri): String = try {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(context, uri)
            duration(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            )
        }
    } catch (t: Throwable) {
        // A recording whose duration cannot be read still plays, and is still
        // worth listing. "--:--" beats hiding it.
        "--:--"
    }

    private fun size(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "%d KB".format(bytes / 1024)
        else -> "$bytes B"
    }
}
