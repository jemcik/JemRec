package com.jemcik.jemrec.capture

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
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
 * noted, so a test can say what was asked.
 */
internal class FakeMediaStore : ContentProvider() {

    class Row(
        val id: Long,
        val name: String,
        val sizeBytes: Long = 1000,
        val modifiedSeconds: Long = 0,
        val durationMillis: Long = 0,
        val relativePath: String = "Recordings/JemRec/",
        val trashed: Boolean = false,
        val expiresSeconds: Long = 0,
    )

    class Query(val selection: String?, val args: List<String>, val matchTrashed: Int)

    companion object {
        val rows = mutableListOf<Row>()
        val queries = mutableListOf<Query>()
        val collection: Uri = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        fun install() {
            rows.clear()
            queries.clear()
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
            trashedOk && (like == null || like.matches(row.relativePath))
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
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

/**
 * A folder the user picked, through its documents provider: the files of a
 * real directory, listed, renamed and deleted the way DocumentsContract asks
 * for them. Each file is shown as the folder provider showed one on the
 * Android 12 and 13 emulators - its name as it is, its ID "primary:Calls/<name>",
 * no flags - unless a test says otherwise in [shows].
 */
internal class FakeFolder : ContentProvider() {

    class Shown(val name: String, val id: String, val flags: Int = 0)

    companion object {
        const val AUTHORITY = "com.jemcik.jemrec.test.documents"
        private const val TREE_ID = "primary:Calls"
        val tree: Uri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, TREE_ID)
        private val ordinary: (File) -> Shown = { Shown(it.name, "$TREE_ID/${it.name}") }

        lateinit var dir: File
        var shows: (File) -> Shown = ordinary
        private val byId = mutableMapOf<String, File>()

        /** The folder, made the one recordings go to. */
        fun install(context: Context): File {
            dir = File(context.cacheDir, "picked").apply { deleteRecursively(); mkdirs() }
            shows = ordinary
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
                        Document.COLUMN_SIZE -> file.length()
                        Document.COLUMN_LAST_MODIFIED -> file.lastModified()
                        Document.COLUMN_FLAGS -> shown.flags
                        else -> null
                    }
                }.toTypedArray<Any?>(),
            )
        }
        return cursor
    }

    /** DocumentsContract's deleteDocument and renameDocument arrive here. */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val uri = extras?.let { BundleCompat.getParcelable(it, "uri", Uri::class.java) }
            ?: return super.call(method, arg, extras)
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
