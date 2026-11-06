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
        rename(context, TrashNames.nameFor(name, expiresAtMillis))

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

    /** One file in the folder; [trashed] as [TrashNames.inFolder] reads it, null
     *  if it is not in the trash. */
    class Document(
        val name: String,
        val uri: Uri,
        /** Null where the provider does not say: unknown, not empty. */
        val sizeBytes: Long?,
        val modifiedMillis: Long,
        val trashed: Pair<String, Long?>?,
    )

    /** Somewhere to write [name], if a folder is picked and will take it. */
    fun open(context: Context, name: String): RecordingStore.Target? {
        val tree = RecordingStore.treeUri(context) ?: return null
        return runCatching {
            val parent = DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree),
            )
            val doc = DocumentsContract.createDocument(context.contentResolver, parent, RecordingStore.MIME, name)
                ?: error("createDocument returned null")
            // "rw" and not "w": MediaMuxer seeks back to patch headers when it
            // finalises, and a write-only descriptor cannot.
            val pfd = context.contentResolver.openFileDescriptor(doc, "rw")
                ?: error("openFileDescriptor returned null")
            RecordingStore.Target(name, pfd, doc, onFinish = {}, onAbandon = {
                DocumentsContract.deleteDocument(context.contentResolver, doc)
            })
        }.onFailure {
            Log.w(TAG, "store: chosen folder unusable, falling back", it)
        }.getOrNull()
    }

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
                    if (cursor.isNull(2)) null else cursor.getLong(2),
                    cursor.getLong(3),
                    TrashNames.inFolder(name, id, restorable),
                )
            }
        }
        out
    }.onFailure { Log.w(TAG, "recordings: chosen folder query failed", it) }.getOrDefault(emptyList())

    /**
     * The folder, less what is in the trash there, and less what is not a
     * recording yet. A folder has nothing like MediaStore's IS_PENDING: a save
     * creates the document at its first packet, and it stays empty until the
     * tags are copied in, so a save under way, or one killed half way, was a
     * row of 0 bytes. And filling it takes as long as the copy, or the whole
     * save where it is written in place: that part is known only here, in the
     * process that is writing it.
     */
    fun list(context: Context, documents: List<Document>): List<Recording> =
        documents.filter { it.trashed == null && it.sizeBytes != 0L && !Waveforms.isBeingWritten(it.name) }
            .map { Recordings.describe(context, it.name, it.uri, it.sizeBytes ?: 0L, whenMillis = it.modifiedMillis) }

    /** What is in the trash there. */
    fun trashed(context: Context, documents: List<Document>): List<Recording> =
        documents.mapNotNull { doc ->
            val (name, expires) = doc.trashed ?: return@mapNotNull null
            Recordings.describe(context, name, doc.uri, doc.sizeBytes ?: 0L, whenMillis = doc.modifiedMillis)
                .copy(expiresAtMillis = expires)
        }

    /** Delete for good what has waited its time, by [now]. How many went. */
    fun purgeExpired(context: Context, documents: List<Document>, now: Long): Int = documents.count { doc ->
        // Not one whose expiry nothing says: that waits for the user.
        val expires = doc.trashed?.second ?: return@count false
        expires <= now && runCatching { DocumentsContract.deleteDocument(context.contentResolver, doc.uri) }.getOrDefault(false)
    }
}
