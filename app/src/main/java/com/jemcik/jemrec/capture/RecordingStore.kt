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
import androidx.core.content.FileProvider
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

    private val MESSAGE_TAG = Regex("_msg(\\d+|none|fail)_")
    private val TAKEN_TAG = Regex("_from(\\d+)_")

    /**
     * Where a call the user took over from the answering machine starts, in
     * a daemon file named jemrec_rec_<millis>_from<start>_in.dat: ms from its
     * first sample. Null for any other recording. Such a call is trimmed like
     * a message and kept as an ordinary recording.
     */
    fun takenStartMs(recfile: String): Long? =
        TAKEN_TAG.find(recfile)?.groupValues?.get(1)?.toLongOrNull()

    /** [messageStartMs] for a caller who hung up during the greeting. */
    const val MESSAGE_NONE = -1L

    /** [messageStartMs] for a greeting that could not be played. */
    const val MESSAGE_FAILED = -2L

    /**
     * Where a message starts in a daemon file the answering machine named
     * (jemrec_rec_<millis>_msg<start>_in.dat), in ms from its first sample;
     * [MESSAGE_NONE] for "_msgnone", [MESSAGE_FAILED] for "_msgfail"; null for
     * any other recording.
     */
    fun messageStartMs(recfile: String): Long? {
        val tag = MESSAGE_TAG.find(recfile)?.groupValues?.get(1) ?: return null
        return when (tag) {
            "none" -> MESSAGE_NONE
            "fail" -> MESSAGE_FAILED
            else -> tag.toLongOrNull()
        }
    }

    private val collection: Uri
        get() = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private fun relativePath(): String = "${Environment.DIRECTORY_RECORDINGS}/$FOLDER"

    class Target(
        val name: String,
        val descriptor: ParcelFileDescriptor,
        /** Where the finished recording can be read back from - by the
         *  after-call check, which decodes it, and by the notification's Play,
         *  Share and Delete. */
        val uri: Uri,
        /** Set for app-storage files only, which are deleted as files. */
        val filePath: String? = null,
        private val onFinish: () -> Unit,
        private val onAbandon: () -> Unit,
    ) : AutoCloseable {

        /** Told once, when it is finished or abandoned (see open). */
        internal var whenDone: () -> Unit = {}
        private var done = false

        private fun ended() {
            if (done) return
            done = true
            runCatching { whenDone() }
        }

        /** Delete it: a call that produced nothing should leave nothing. */
        fun abandon() {
            runCatching { descriptor.close() }
            runCatching { onAbandon() }
            ended()
        }

        /** Publish it, so other apps can finally see it. */
        override fun close() {
            publish()
        }

        /** Publish it, and say whether that worked: a descriptor that would
         *  not close (its last bytes not written) or a pending row that would
         *  not be made visible is no saved recording. */
        fun publish(): Boolean {
            val closed = runCatching { descriptor.close() }.isSuccess
            val finished = runCatching { onFinish() }.isSuccess
            ended()
            return closed && finished
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
     * Open somewhere to record [name] into - the chosen folder, Recordings,
     * or app storage - falling back rather than failing. Mid-call is the
     * worst moment to discover a storage problem, so a deleted folder or a
     * revoked permission drops to the next option instead of losing the call.
     *
     * Until it is finished its waveform waits (Waveforms.writingStarted): in a
     * chosen folder or app storage it is listed while it fills, and a decode
     * then would keep the first minutes' levels as the whole call's.
     */
    fun open(context: Context, name: String): Target? {
        Waveforms.writingStarted(name)
        val target = openInChosenFolder(context, name)
            ?: openInRecordings(context, name)
            ?: openInAppStorage(context, name)
        if (target == null) {
            Waveforms.writingEnded(context, name)
        } else {
            target.whenDone = { Waveforms.writingEnded(context, name) }
        }
        return target
    }

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
            Target(name, pfd, doc, onFinish = {}, onAbandon = {
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
            uri = uri,
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
        Target(
            name,
            pfd,
            FileProvider.getUriForFile(context, "${context.packageName}.recordings", file),
            filePath = file.absolutePath,
            onFinish = {},
            onAbandon = { file.delete() },
        )
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
