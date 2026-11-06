package com.jemcik.jemrec.recordings

import android.content.Context
import android.os.ParcelFileDescriptor
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
        val target = File(file.parentFile, Trash.nameFor(name, expiresAtMillis))
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

    /** The files there, newest first, trashed or not. */
    fun files(context: Context): List<File> =
        File(context.getExternalFilesDir(null), "recordings")
            .listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /** The files there, less the empty and the trashed. */
    fun list(context: Context, files: List<File>): List<Recording> =
        files.filter { it.isFile && it.length() > 0 && !Trash.isTrashedName(it.name) }
            .mapNotNull { file ->
                runCatching {
                    Recordings.describe(context, file.name, uriOf(context, file), file.length(), whenMillis = file.lastModified())
                        .copy(filePath = file.absolutePath)
                }.getOrNull()
            }

    /** What is in the trash there. */
    fun trashed(context: Context, files: List<File>): List<Recording> =
        files.mapNotNull { file ->
            val (name, expires) = Trash.parse(file.name) ?: return@mapNotNull null
            runCatching {
                Recordings.describe(context, name, uriOf(context, file), file.length(), whenMillis = file.lastModified())
                    .copy(filePath = file.absolutePath, expiresAtMillis = expires)
            }.getOrNull()
        }

    /** Delete for good what has waited its time, by [now]. How many went. */
    fun purgeExpired(files: List<File>, now: Long): Int = files.count { file ->
        val (_, expires) = Trash.parse(file.name) ?: return@count false
        expires <= now && file.delete()
    }

    /** The content uri of a file there: who has it may read it, for a grant. */
    fun uriOf(context: Context, file: File) = FileProvider.getUriForFile(context, "${context.packageName}.recordings", file)
}
