package com.jemcik.jemrec.recordings

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
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
