package com.jemcik.jemrec.recordings

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * A recording in app-private storage, as a file: a FileProvider uri can be
 * read, and refuses to be deleted (see Recording.filePath). Trashed by a
 * rename, as in a chosen folder (see Trash).
 */
internal class AppFile(private val file: File) : RecordingLocation {

    override fun trash(context: Context, name: String, expiresAtMillis: Long): DeleteResult {
        if (!file.exists()) return DeleteResult.Deleted
        val target = File(file.parentFile, TrashNames.nameFor(name, expiresAtMillis))
        return if (file.renameTo(target)) DeleteResult.Deleted
        else DeleteResult.Failed(IOException("could not move $file to the trash"))
    }

    override fun restore(context: Context, name: String): DeleteResult {
        val target = File(file.parentFile, name)
        return if (file.renameTo(target)) DeleteResult.Deleted
        else DeleteResult.Failed(IOException("could not restore $file"))
    }

    override fun deleteForGood(context: Context): Boolean = !file.exists() || file.delete()

    override fun read(context: Context): InputStream = FileInputStream(file)

    override fun change(context: Context): ParcelFileDescriptor? =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
}

/** App-private storage: recordings from before the standard folder was the
 *  default, and the last resort when neither other place will take one. */
internal object AppRecordings {

    private const val TAG = "JemRec"

    private fun folder(context: Context) = File(context.getExternalFilesDir(null), "recordings")

    /** Somewhere to write [name], the last resort. */
    fun open(context: Context, name: String): RecordingStore.Target? = runCatching {
        val dir = folder(context).apply { mkdirs() }
        val file = File(dir, name)
        // Truncated, as MediaStore's new row and a new document are empty: a
        // file of the name from before - a save that failed half way, then
        // tried again untagged and shorter - kept its tail past the new end.
        val pfd = ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE,
        )
        RecordingStore.Target(
            name,
            pfd,
            uriOf(context, file),
            filePath = file.absolutePath,
            onFinish = {},
            onAbandon = { file.delete() },
        )
    }.onFailure {
        Log.e(TAG, "store: nowhere at all to write a recording", it)
    }.getOrNull()

    /** The files there, newest first, trashed or not. */
    fun files(context: Context): List<File> =
        folder(context)
            .listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /** The files there, less the empty and the trashed. */
    fun list(context: Context, files: List<File>): List<Recording> =
        files.filter { it.isFile && it.length() > 0 && TrashNames.parse(it.name) == null }
            .mapNotNull { file ->
                runCatching {
                    Recordings.describe(context, file.name, uriOf(context, file), file.length(), whenMillis = file.lastModified())
                        .copy(filePath = file.absolutePath)
                }.getOrNull()
            }

    /** What is in the trash there. */
    fun trashed(context: Context, files: List<File>): List<Recording> =
        files.mapNotNull { file ->
            val (name, expires) = TrashNames.parse(file.name) ?: return@mapNotNull null
            runCatching {
                Recordings.describe(context, name, uriOf(context, file), file.length(), whenMillis = file.lastModified())
                    .copy(filePath = file.absolutePath, expiresAtMillis = expires)
            }.getOrNull()
        }

    /** Delete for good what has waited its time, by [now]. How many went. */
    fun purgeExpired(files: List<File>, now: Long): Int = files.count { file ->
        val (_, expires) = TrashNames.parse(file.name) ?: return@count false
        expires <= now && file.delete()
    }

    /** A file's content uri, as the list and the notification carry it, and
     *  a shared copy beside it (MainViewModel): file_paths.xml says which. */
    fun uriOf(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.recordings", file)
}
