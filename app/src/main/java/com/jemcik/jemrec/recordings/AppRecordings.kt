package com.jemcik.jemrec.recordings

import android.content.Context
import android.os.ParcelFileDescriptor
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
