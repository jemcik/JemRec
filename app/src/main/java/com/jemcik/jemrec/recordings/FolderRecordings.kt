package com.jemcik.jemrec.recordings

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.util.Log
import java.io.IOException
import java.io.InputStream

/**
 * A recording in the folder the user picked, through its documents provider.
 * No trash exists there, so it is trashed by a rename (see Trash).
 */
internal class FolderDocument(private val uri: Uri) : RecordingLocation {

    override fun trash(context: Context, name: String, expiresAtMillis: Long): DeleteResult =
        rename(context, Trash.nameFor(name, expiresAtMillis))

    override fun restore(context: Context, name: String): DeleteResult = rename(context, name)

    private fun rename(context: Context, to: String): DeleteResult = runCatching {
        if (DocumentsContract.renameDocument(context.contentResolver, uri, to) != null) {
            DeleteResult.Deleted
        } else {
            DeleteResult.Failed(IOException("the folder would not rename $uri")) as DeleteResult
        }
    }.getOrElse { DeleteResult.Failed(it) }

    /** A failure of any kind, its access taken back too, is said as one:
     *  there is nobody to ask here, as there is for MediaStore. */
    override fun deleteForGood(context: Context): Boolean =
        DocumentsContract.deleteDocument(context.contentResolver, uri)

    override fun read(context: Context): InputStream =
        context.contentResolver.openInputStream(uri) ?: error("no stream")

    override fun change(context: Context): ParcelFileDescriptor? =
        context.contentResolver.openFileDescriptor(uri, "rw")
}

/** The folder the user picked, if they picked one. */
internal object FolderRecordings {

    private const val TAG = "JemRec"

    /** One file in the folder; [trashed] as [Trash.inFolder] reads it, null
     *  if it is not in the trash. */
    class Document(
        val name: String,
        val uri: Uri,
        val sizeBytes: Long,
        val modifiedMillis: Long,
        val trashed: Pair<String, Long?>?,
    )

    /** Every recording-shaped file in the folder, trashed or not. */
    fun documents(context: Context): List<Document> = runCatching {
        val tree = RecordingStore.treeUri(context) ?: return emptyList()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree),
        )
        val out = mutableListOf<Document>()
        context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_FLAGS,
            ),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1) ?: continue
                if (!name.endsWith(".${RecordingStore.EXTENSION}")) continue
                val id = cursor.getString(0) ?: continue
                // Before Android 17 the bit means nothing.
                val restorable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN &&
                    (cursor.getInt(4) and DocumentsContract.Document.FLAG_SUPPORTS_RESTORE) != 0
                out += Document(
                    name,
                    DocumentsContract.buildDocumentUriUsingTree(tree, id),
                    cursor.getLong(2),
                    cursor.getLong(3),
                    Trash.inFolder(name, id, restorable),
                )
            }
        }
        out
    }.onFailure { Log.w(TAG, "recordings: chosen folder query failed", it) }.getOrDefault(emptyList())

    /** The folder, less what is in the trash there. */
    fun list(context: Context, documents: List<Document>): List<Recording> =
        documents.filter { it.trashed == null }
            .map { Recordings.describe(context, it.name, it.uri, it.sizeBytes, whenMillis = it.modifiedMillis) }

    /** What is in the trash there. */
    fun trashed(context: Context, documents: List<Document>): List<Recording> =
        documents.mapNotNull { doc ->
            val (name, expires) = doc.trashed ?: return@mapNotNull null
            Recordings.describe(context, name, doc.uri, doc.sizeBytes, whenMillis = doc.modifiedMillis)
                .copy(expiresAtMillis = expires)
        }

    /** Delete for good what has waited its time, by [now]. How many went. */
    fun purgeExpired(context: Context, documents: List<Document>, now: Long): Int = documents.count { doc ->
        // Not one whose expiry nothing says: that waits for the user.
        val expires = doc.trashed?.second ?: return@count false
        expires <= now && runCatching { DocumentsContract.deleteDocument(context.contentResolver, doc.uri) }.getOrDefault(false)
    }
}
