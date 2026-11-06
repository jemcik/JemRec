package com.jemcik.jemrec.recordings

import android.app.PendingIntent
import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.core.os.BundleCompat
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.util.DataSource
import java.io.File
import java.io.FileNotFoundException

/**
 * The three places a recording can live, for the Robolectric tests of the
 * list and the trash. App storage is real files. Robolectric has no
 * MediaStore and no folder provider, so those two are faked below, only as
 * far as Recordings and Trash ask them - which is what lets a test put one
 * name in more than one place.
 */
internal object FakeStorage {

    /** Where app storage keeps recordings: RecordingStore's folder. */
    fun appFolder(context: Context): File =
        File(context.getExternalFilesDir(null), "recordings").apply { mkdirs() }

    /** A file in app storage, [bytes] long, its file date [modified]. */
    fun appFile(context: Context, name: String, bytes: Int = 64, modified: Long? = null): File =
        File(appFolder(context), name).apply {
            writeBytes(ByteArray(bytes) { 1 })
            modified?.let { setLastModified(it) }
        }

    /** An app-storage file's uri, as the list makes it. */
    fun appUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.recordings", file)

    /** What reading [uri]'s length will say, for a source that opens the file to ask. */
    fun lasts(context: Context, uri: Uri, millis: Long) =
        ShadowMediaMetadataRetriever.addMetadata(
            DataSource.toDataSource(context, uri), MediaMetadataRetriever.METADATA_KEY_DURATION, "$millis",
        )

    /** Reading [uri]'s length fails. */
    fun unreadable(context: Context, uri: Uri) =
        ShadowMediaMetadataRetriever.addException(DataSource.toDataSource(context, uri), IllegalArgumentException("not audio"))

    /**
     * FileProvider keeps each authority's folders in a static cache, and every
     * test has folders of its own: from the second test on, it refused the
     * files and the list dropped them. A phone has one set of folders.
     */
    fun forgetFileProviderFolders() {
        FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
            .let { (it.get(null) as HashMap<*, *>).clear() }
    }
}

/**
 * MediaStore's audio collection, as Recordings and Trash query it: rows in a
 * list, trashed or not, filtered by the RELATIVE_PATH pattern and the
 * MATCH_TRASHED argument each query passes - and each query's arguments
 * noted, so a test can say what was asked. With [writes] on it also takes
 * what RecordingStore and Trash write, as far as they ask: a row made pending
 * with a file of its own, IS_PENDING and IS_TRASHED changed, a row deleted,
 * and the two requests for the user's yes - none of it Android's behaviour,
 * which only the emulator checks can show: what is held here is what JemRec
 * asks for.
 */
internal class FakeMediaStore : ContentProvider() {

    class Row(
        val id: Long,
        var name: String,
        val sizeBytes: Long = 1000,
        val modifiedSeconds: Long = 0,
        val durationMillis: Long = 0,
        val relativePath: String = "Recordings/JemRec/",
        var trashed: Boolean = false,
        val expiresSeconds: Long = 0,
        var pending: Boolean = false,
        /** A row Android counts as nobody's: from before a reinstall. */
        val notOurs: Boolean = false,
        /** What reading or writing the row reaches. */
        var file: File? = null,
    )

    class Query(val selection: String?, val args: List<String>, val matchTrashed: Int)

    /** One write, with the MATCH_TRASHED it was asked with. */
    class Change(val id: Long, val values: Map<String, Any?>, val matchTrashed: Int)

    /** One of the requests for the user's yes: which, and for which rows. */
    class Asked(val method: String, val uris: List<Uri>, val trashed: Boolean?)

    companion object {
        val rows = mutableListOf<Row>()
        val queries = mutableListOf<Query>()
        val inserted = mutableListOf<Map<String, Any?>>()
        val changes = mutableListOf<Change>()
        val deleted = mutableListOf<Change>()
        val asked = mutableListOf<Asked>()
        val collection: Uri = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        /** Whether it takes writes. Off, it makes no row: where a test wants
         *  the fall-back to app storage. */
        var writes = false

        /** Where the rows' files go, with [writes] on. */
        var dir: File? = null

        /** The two requests can be made. */
        var asks = true

        /** Each update and delete fails: an [Exception] of [refuses]'s. */
        var refuses: Exception? = null

        /** An update or a delete says it changed nothing. */
        var changesNothing = false

        /** Asking for the rows fails, with this. */
        var listing: Exception? = null

        fun install(writesIn: File? = null) {
            rows.clear()
            queries.clear()
            inserted.clear()
            changes.clear()
            deleted.clear()
            asked.clear()
            writes = writesIn != null
            dir = writesIn?.apply { mkdirs() }
            asks = true
            refuses = null
            changesNothing = false
            listing = null
            Robolectric.setupContentProvider(FakeMediaStore::class.java, MediaStore.AUTHORITY)
        }

        fun uriOf(id: Long): Uri = Uri.withAppendedPath(collection, "$id")
    }

    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor = query(
        uri, projection,
        Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs?.map { it }?.toTypedArray())
        },
        null,
    )

    override fun query(uri: Uri, projection: Array<out String>?, queryArgs: Bundle?, cancellationSignal: CancellationSignal?): Cursor {
        val selection = queryArgs?.getString(ContentResolver.QUERY_ARG_SQL_SELECTION)
        val args = queryArgs?.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS)?.toList().orEmpty()
        val match = queryArgs?.getInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_EXCLUDE) ?: MediaStore.MATCH_EXCLUDE
        queries += Query(selection, args, match)
        listing?.let { throw it }
        // SQL's LIKE, for the one selection asked: % is anything.
        val like = args.firstOrNull()
            ?.takeIf { selection == "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" }
            ?.let { pattern -> Regex(pattern.split('%').joinToString(".*") { Regex.escape(it) }) }
        val columns = projection ?: arrayOf(MediaStore.MediaColumns._ID)
        val cursor = MatrixCursor(columns)
        rows.filter { row ->
            val trashedOk = when (match) {
                MediaStore.MATCH_ONLY -> row.trashed
                MediaStore.MATCH_INCLUDE -> true
                else -> !row.trashed
            }
            trashedOk && !row.pending && (like == null || like.matches(row.relativePath))
        }.forEach { row ->
            cursor.addRow(
                columns.map { column ->
                    when (column) {
                        MediaStore.MediaColumns._ID -> row.id
                        MediaStore.MediaColumns.DISPLAY_NAME -> row.name
                        MediaStore.MediaColumns.SIZE -> row.sizeBytes
                        MediaStore.MediaColumns.DATE_MODIFIED -> row.modifiedSeconds
                        MediaStore.MediaColumns.DURATION -> row.durationMillis
                        MediaStore.MediaColumns.DATE_EXPIRES -> row.expiresSeconds
                        else -> null
                    }
                }.toTypedArray<Any?>(),
            )
        }
        return cursor
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (!writes) return null
        val asked = values!!.keySet().associateWith { values.get(it) }
        inserted += asked
        val id = (rows.maxOfOrNull { it.id } ?: 0L) + 1
        rows += Row(
            id = id,
            name = asked[MediaStore.MediaColumns.DISPLAY_NAME] as String,
            relativePath = asked[MediaStore.MediaColumns.RELATIVE_PATH] as String + "/",
            pending = asked[MediaStore.MediaColumns.IS_PENDING] == 1,
            file = File(dir, "row-$id").apply { writeBytes(ByteArray(0)) },
        )
        return uriOf(id)
    }

    private fun rowOf(uri: Uri) = rows.firstOrNull { it.id == uri.lastPathSegment?.toLong() }

    private fun matched(uri: Uri, extras: Bundle?): Row? {
        val match = extras?.getInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_EXCLUDE) ?: MediaStore.MATCH_EXCLUDE
        return rowOf(uri)?.takeIf { !it.trashed || match != MediaStore.MATCH_EXCLUDE }
    }

    override fun update(uri: Uri, values: ContentValues?, extras: Bundle?): Int {
        refuses?.let { throw it }
        val row = matched(uri, extras) ?: return 0
        if (row.notOurs) throw SecurityException("not this app's: $uri")
        val asked = values!!.keySet().associateWith { values.get(it) }
        changes += Change(row.id, asked, extras?.getInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_EXCLUDE) ?: MediaStore.MATCH_EXCLUDE)
        asked[MediaStore.MediaColumns.IS_TRASHED]?.let { row.trashed = it == 1 }
        asked[MediaStore.MediaColumns.IS_PENDING]?.let { row.pending = it == 1 }
        return if (changesNothing) 0 else 1
    }

    override fun delete(uri: Uri, extras: Bundle?): Int {
        refuses?.let { throw it }
        val row = matched(uri, extras) ?: return 0
        if (row.notOurs) throw SecurityException("not this app's: $uri")
        deleted += Change(row.id, emptyMap(), extras?.getInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_EXCLUDE) ?: MediaStore.MATCH_EXCLUDE)
        rows -= row
        return if (changesNothing) 0 else 1
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = update(uri, values, null as Bundle?)
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = delete(uri, null as Bundle?)

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val row = rowOf(uri) ?: throw FileNotFoundException("$uri")
        if (row.notOurs && 'w' in mode) throw SecurityException("not this app's: $uri")
        val file = row.file ?: throw FileNotFoundException("$uri")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    /** MediaStore.createTrashRequest and createDeleteRequest arrive here. */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (!asks) throw IllegalStateException("no request today")
        val clip = BundleCompat.getParcelable(extras!!, "clip_data", ClipData::class.java)!!
        val values = BundleCompat.getParcelable(extras, "content_values", ContentValues::class.java)
        asked += Asked(
            method,
            List(clip.itemCount) { clip.getItemAt(it).uri },
            values?.getAsInteger(MediaStore.MediaColumns.IS_TRASHED)?.let { it == 1 },
        )
        val request = PendingIntent.getActivity(context!!, 0, Intent(), PendingIntent.FLAG_IMMUTABLE)
        return Bundle().apply { putParcelable("result", request) }
    }
}

/**
 * A folder the user picked, through its documents provider: the files of a
 * real directory, listed, renamed and deleted the way DocumentsContract asks
 * for them. Each file is shown as the folder provider showed one on the
 * Android 12 and 13 emulators - its name as it is, its ID "primary:Calls/<name>",
 * no flags - unless a test says otherwise in [shows].
 */
internal class FakeFolder : ContentProvider() {

    class Shown(val name: String, val id: String, val flags: Int = 0, val sizeKnown: Boolean = true)

    companion object {
        const val AUTHORITY = "com.jemcik.jemrec.test.documents"
        private const val TREE_ID = "primary:Calls"
        val tree: Uri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, TREE_ID)
        private val ordinary: (File) -> Shown = { Shown(it.name, "$TREE_ID/${it.name}") }

        lateinit var dir: File
        var shows: (File) -> Shown = ordinary
        private val byId = mutableMapOf<String, File>()

        /** Each thing asked of a document fails, with this. */
        var refuses: Exception? = null

        /** Listing the folder fails, with this. */
        var listing: Exception? = null

        /** A new document, what createDocument makes of its [String] name:
         *  null makes none, and the provider says so with no uri. */
        var creates: (String) -> String? = { it }

        /** How many times the folder has been listed. */
        var listed = 0

        /** The folder, made the one recordings go to. */
        fun install(context: Context): File {
            dir = File(context.cacheDir, "picked").apply { deleteRecursively(); mkdirs() }
            shows = ordinary
            refuses = null
            listing = null
            creates = { it }
            listed = 0
            byId.clear()
            Robolectric.setupContentProvider(FakeFolder::class.java, AUTHORITY)
            RecordingStore.setTree(context, tree)
            return dir
        }

        fun file(name: String, bytes: Int = 64, modified: Long? = null): File = File(dir, name).apply {
            writeBytes(ByteArray(bytes) { 1 })
            modified?.let { setLastModified(it) }
        }
    }

    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        listing?.let { throw it }
        listed++
        val columns = projection ?: arrayOf(Document.COLUMN_DOCUMENT_ID)
        val cursor = MatrixCursor(columns)
        dir.listFiles().orEmpty().sortedBy { it.name }.forEach { file ->
            val shown = shows(file)
            byId[shown.id] = file
            cursor.addRow(
                columns.map { column ->
                    when (column) {
                        Document.COLUMN_DOCUMENT_ID -> shown.id
                        Document.COLUMN_DISPLAY_NAME -> shown.name
                        Document.COLUMN_SIZE -> file.length().takeIf { shown.sizeKnown }
                        Document.COLUMN_LAST_MODIFIED -> file.lastModified()
                        Document.COLUMN_FLAGS -> shown.flags
                        else -> null
                    }
                }.toTypedArray<Any?>(),
            )
        }
        return cursor
    }

    /** DocumentsContract's createDocument, deleteDocument and renameDocument arrive here. */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val uri = extras?.let { BundleCompat.getParcelable(it, "uri", Uri::class.java) }
            ?: return super.call(method, arg, extras)
        refuses?.let { throw it }
        if (method == "android:createDocument") {
            val name = creates(extras.getString(Document.COLUMN_DISPLAY_NAME)!!) ?: return Bundle()
            val file = File(dir, name).apply { createNewFile() }
            val id = shows(file).id
            byId[id] = file
            return Bundle().apply {
                putParcelable("uri", DocumentsContract.buildDocumentUriUsingTree(tree, id))
            }
        }
        val file = byId[DocumentsContract.getDocumentId(uri)] ?: throw FileNotFoundException("$uri")
        return when (method) {
            "android:deleteDocument" -> Bundle().also { if (!file.delete()) throw FileNotFoundException("$uri") }
            "android:renameDocument" -> Bundle().also {
                val to = File(dir, extras.getString(Document.COLUMN_DISPLAY_NAME)!!)
                if (!file.renameTo(to)) throw FileNotFoundException("$uri")
            }
            else -> super.call(method, arg, extras)
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = byId[DocumentsContract.getDocumentId(uri)] ?: throw FileNotFoundException("$uri")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
