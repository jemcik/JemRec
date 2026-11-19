package com.jemcik.jemrec.recordings

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Log
import java.io.IOException

/**
 * A recording in the folder the user picked, through its documents provider.
 * No trash exists there, so it is trashed by a rename (see Trash).
 */
internal class FolderDocument(uri: Uri) : ProviderItem(uri) {

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
            // finalises, and a write-only descriptor cannot. A file made and
            // not opened is let go here: empty, under the recording's own
            // name, it would make the retry "(1)" (review, 2026-11-18).
            val pfd = runCatching { context.contentResolver.openFileDescriptor(doc, "rw") ?: error("openFileDescriptor returned null") }
                .onFailure { runCatching { DocumentsContract.deleteDocument(context.contentResolver, doc) } }
                .getOrThrow()
            val made = RecordingStore.nameOf(context, doc) ?: name
            RecordingStore.Target(made, pfd, doc, onFinish = {}, onAbandon = {
                DocumentsContract.deleteDocument(context.contentResolver, doc)
            })
        }.onFailure {
            Log.w(TAG, "store: chosen folder unusable, falling back", it)
        }.getOrNull()
    }

    /**
     * What is at [uri] of a save cut short; null where it would not say -
     * the folder itself not answering, or its grant let go. Android's own
     * provider tells a file gone no other way than a folder it cannot reach:
     * its query swallows FileNotFoundException into a null cursor
     * (DocumentsProvider.query), and a tree uri for a file gone throws
     * IllegalArgumentException (enforceTree, FileSystemProvider
     * .isChildDocument). So the folder is asked: answering, the file is
     * gone. Read as "would not say", a file trashed or deleted after the
     * save was cut short left it waiting for good (review round 10). A size
     * not given, or 0 - COLUMN_SIZE is "null if unknown", and a provider
     * may say 0 for one it does not know - is read, as the listing does.
     */
    fun leftAt(context: Context, uri: android.net.Uri): SaveJournal.Left? {
        val size = try {
            context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)
                ?.use { c -> if (!c.moveToFirst()) null else if (c.isNull(0)) 0L else c.getLong(0) }
        } catch (e: SecurityException) {
            Log.w(TAG, "store: the folder of a file a save left is no longer granted", e)
            return null
        } catch (_: Exception) {
            null
        }
        if (size == null) return if (folderAnswers(context, uri)) SaveJournal.Left.MISSING else null
        if (size > 0L) return SaveJournal.Left.THERE
        return try {
            context.contentResolver.openInputStream(uri)?.use { if (it.read() < 0) SaveJournal.Left.MISSING else SaveJournal.Left.THERE }
        } catch (_: java.io.FileNotFoundException) {
            if (folderAnswers(context, uri)) SaveJournal.Left.MISSING else null
        } catch (e: Exception) {
            Log.w(TAG, "store: could not read a file a save left", e)
            null
        }
    }

    /** Whether the folder [uri] was made under still answers: its tree's
     *  own document, asked under the same grant. */
    private fun folderAnswers(context: Context, uri: android.net.Uri): Boolean = runCatching {
        val treeId = DocumentsContract.getTreeDocumentId(uri)
        val tree = DocumentsContract.buildTreeDocumentUri(uri.authority, treeId)
        context.contentResolver.query(
            DocumentsContract.buildDocumentUriUsingTree(tree, treeId),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null,
        )?.use { it.moveToFirst() } == true
    }.getOrDefault(false)

    /** Every recording-shaped file in the folder, trashed or not. */
    fun documents(context: Context): List<Document> = listing(context) ?: emptyList()

    /** The same, or null if the folder could not be listed - which an
     *  empty list would hide from a pass that marks the folder gone through
     *  (OldRecordingPages; review, 2026-11-18). Empty with none chosen. */
    fun listing(context: Context): List<Document>? = runCatching {
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
        } ?: error("the folder gave no listing")
        out
    }.onFailure { Log.w(TAG, "recordings: chosen folder query failed", it) }.getOrNull()

    /**
     * The folder, less what is in the trash there, and less what is empty. A
     * folder has nothing like MediaStore's IS_PENDING: a save creates the
     * document at its first packet, and it stays empty until the tags are
     * copied in, so a save under way, or one killed half way, was a row of 0 B.
     */
    fun list(context: Context, documents: List<Document>): List<Recording> =
        documents.filter { it.trashed == null && (it.sizeBytes != 0L || !RecordingStore.holdsNothing(context, it.uri)) }
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
