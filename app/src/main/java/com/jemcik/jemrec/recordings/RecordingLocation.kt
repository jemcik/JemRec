package com.jemcik.jemrec.recordings

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.io.InputStream

/**
 * Which of the three places one recording lives in, and what is done to it
 * there. Worked out here and nowhere else ([of]): a recording reaches the
 * app's other parts as a uri and, for app storage, a path - in a list row, in
 * the saver's answer, through an after-call notification's intent - and each
 * operation used to work the place out again from those two.
 *
 * The places have little in common (see Trash and RecordingStore for why);
 * what they do have is here, each kept with the rest of its place:
 * [MediaStoreItem] in MediaStoreRecordings.kt, [FolderDocument] in
 * FolderRecordings.kt, [AppFile] in AppRecordings.kt.
 */
internal sealed interface RecordingLocation {

    /** Move it to the trash, under [name], until [expiresAtMillis]. */
    fun trash(context: Context, name: String, expiresAtMillis: Long): DeleteResult

    /** Bring it back from the trash as [name]. */
    fun restore(context: Context, name: String): DeleteResult

    /** Delete it for good: whether it went, or null where the user must say. */
    fun deleteForGood(context: Context): Boolean?

    /** Its bytes, from the start. Throws if it cannot be read. */
    fun read(context: Context): InputStream

    /** Its bytes, to change in place; null if it cannot be opened so. */
    fun change(context: Context): ParcelFileDescriptor?

    companion object {
        /** The one rule: a path means app storage, which alone has one;
         *  otherwise the uri's provider says. */
        fun of(uri: Uri, filePath: String?): RecordingLocation = when {
            filePath != null -> AppFile(File(filePath))
            uri.authority == MediaStore.AUTHORITY -> MediaStoreItem(uri)
            else -> FolderDocument(uri)
        }
    }
}

/** A recording behind a content provider: read and written through its uri,
 *  whichever provider that is. */
internal abstract class ProviderItem(protected val uri: Uri) : RecordingLocation {

    override fun read(context: Context): InputStream =
        context.contentResolver.openInputStream(uri) ?: error("no stream")

    override fun change(context: Context): ParcelFileDescriptor? =
        context.contentResolver.openFileDescriptor(uri, "rw")
}
