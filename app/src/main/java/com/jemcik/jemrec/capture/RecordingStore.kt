package com.jemcik.jemrec.capture

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import com.jemcik.jemrec.Prefs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where recordings go, in order of preference.
 *
 *   1. A folder the user picked, if they picked one.
 *   2. THE PHONE'S STANDARD RECORDINGS FOLDER - Recordings/JemRec.
 *   3. App-private storage, only if both of those fail.
 *
 * The middle one is the default and matters more than it looks. Recordings used
 * to land in the app's own external files directory, which is invisible to file
 * managers, unreachable from a computer without digging, and DELETED when the
 * app is uninstalled. That is a fine place for a cache and a bad place for
 * someone's phone calls.
 *
 * Android 12 added Environment.DIRECTORY_RECORDINGS as the conventional home
 * for exactly this, and MediaStore can write there with NO permission at all -
 * an app may always create its own media. So the default needs nothing granted,
 * puts files where a person would look for them, and leaves them there.
 *
 * IS_PENDING is set while writing, so a half-finished recording does not appear
 * in anyone's music app mid-call.
 */
object RecordingStore {

    private const val TAG = "JemRec"
    private const val KEY_TREE = "recordings_tree_uri"

    const val MIME = "audio/ogg"
    const val EXTENSION = "ogg"

    /** Subfolder of the system Recordings directory. */
    const val FOLDER = "JemRec"

    /**
     * Which way a call went, from the "_in." / "_out." tag every name carries -
     * the daemon's jemrec_rec_<millis>_in.dat and the saved
     * yyyyMMdd_HHmmss_in.ogg alike. One reader for both, because two had
     * drifted: one matched "_in" and would have called an "_input" incoming.
     */
    fun isIncoming(name: String): Boolean = name.contains("_in.")

    private val collection: Uri
        get() = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private fun relativePath(): String = "${Environment.DIRECTORY_RECORDINGS}/$FOLDER"

    class Target(
        val name: String,
        val descriptor: ParcelFileDescriptor,
        private val onFinish: () -> Unit,
        private val onAbandon: () -> Unit,
    ) : AutoCloseable {

        /** Delete it: a call that produced nothing should leave nothing. */
        fun abandon() {
            runCatching { descriptor.close() }
            runCatching { onAbandon() }
        }

        /** Publish it, so other apps can finally see it. */
        override fun close() {
            runCatching { descriptor.close() }
            runCatching { onFinish() }
        }
    }

    fun treeUri(context: Context): Uri? =
        Prefs.of(context)
            .getString(KEY_TREE, null)
            ?.let(Uri::parse)

    /** What to show the user, in words they can act on. */
    fun describe(context: Context): String {
        treeUri(context)?.let { tree ->
            val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
            // Tree ids look like "primary:Documents/Calls"; the half after the
            // colon is the part a person recognises.
            val readable = id?.substringAfter(':')?.takeIf { it.isNotBlank() }
            return readable ?: "the folder you chose"
        }
        return relativePath()
    }

    fun setTree(context: Context, uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            .onFailure { Log.w(TAG, "store: could not persist folder permission", it) }
        Prefs.of(context)
            .edit().putString(KEY_TREE, uri.toString()).apply()
        Log.i(TAG, "store: recordings will go to $uri")
    }

    /** Go back to the standard Recordings folder. */
    fun clearTree(context: Context) {
        Prefs.of(context)
            .edit().remove(KEY_TREE).apply()
    }

    fun nameFor(startedAt: Date, incoming: Boolean): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(startedAt)
        // Braces are not optional: "$stamp_" would name a variable "stamp_".
        return "${stamp}_${if (incoming) "in" else "out"}.$EXTENSION"
    }

    /**
     * Open somewhere to record into, falling back rather than failing.
     *
     * Mid-call is the worst moment to discover a storage problem, so a deleted
     * folder or a revoked permission drops to the next option instead of
     * losing the call.
     */
    fun open(context: Context, name: String): Target? =
        openInChosenFolder(context, name)
            ?: openInRecordings(context, name)
            ?: openInAppStorage(context, name)

    private fun openInChosenFolder(context: Context, name: String): Target? {
        val tree = treeUri(context) ?: return null
        return runCatching {
            val parent = DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree),
            )
            val doc = DocumentsContract.createDocument(context.contentResolver, parent, MIME, name)
                ?: error("createDocument returned null")
            // "rw" and not "w": MediaMuxer seeks back to patch headers when it
            // finalises, and a write-only descriptor cannot.
            val pfd = context.contentResolver.openFileDescriptor(doc, "rw")
                ?: error("openFileDescriptor returned null")
            Target(name, pfd, onFinish = {}, onAbandon = {
                DocumentsContract.deleteDocument(context.contentResolver, doc)
            })
        }.onFailure {
            Log.w(TAG, "store: chosen folder unusable, falling back", it)
        }.getOrNull()
    }

    private fun openInRecordings(context: Context, name: String): Target? = runCatching {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, MIME)
            put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath())
            // Hidden from other apps until the recording is finished, so a
            // half-written file never shows up in a music app mid-call.
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("MediaStore insert returned null")
        val pfd = resolver.openFileDescriptor(uri, "rw")
            ?: error("openFileDescriptor returned null")

        Target(
            name = name,
            descriptor = pfd,
            onFinish = {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
                    null, null,
                )
            },
            onAbandon = { resolver.delete(uri, null, null) },
        )
    }.onFailure {
        Log.w(TAG, "store: could not write to ${relativePath()}", it)
    }.getOrNull()

    private fun openInAppStorage(context: Context, name: String): Target? = runCatching {
        val dir = File(context.getExternalFilesDir(null), "recordings").apply { mkdirs() }
        val file = File(dir, name)
        val pfd = ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE,
        )
        Target(name, pfd, onFinish = {}, onAbandon = { file.delete() })
    }.onFailure {
        Log.e(TAG, "store: nowhere at all to write a recording", it)
    }.getOrNull()

    /** Older recordings, from before the standard folder was the default. */
    fun listAppStorage(context: Context): List<File> =
        File(context.getExternalFilesDir(null), "recordings")
            .listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
}
