package com.jemcik.jemrec.recordings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import com.jemcik.jemrec.Prefs
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

    /** Which way a call went, from the "_in." / "_out." tag of a saved
     *  name or a recorder's file alike (DaemonFile.isIncoming). */
    fun isIncoming(name: String): Boolean = DaemonFile.isIncoming(name)

    /**
     * Where a call the user took over from the answering machine starts, in
     * a daemon file named jemrec_rec_<millis>_from<start>_in.dat: ms from its
     * first sample. Null for any other recording. Such a call is trimmed like
     * a message and kept as an ordinary recording.
     */
    fun takenStartMs(recfile: String): Long? = DaemonFile.parse(recfile).takenFromMs

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
    fun messageStartMs(recfile: String): Long? = when (val message = DaemonFile.parse(recfile).message) {
        null -> null
        DaemonFile.Message.None -> MESSAGE_NONE
        DaemonFile.Message.Failed -> MESSAGE_FAILED
        is DaemonFile.Message.Starts -> message.ms
    }

    /** Where the standard folder is, as MediaStore and a person write it. */
    internal fun relativePath(): String = "${Environment.DIRECTORY_RECORDINGS}/$FOLDER"

    class Target(
        /** The name it was made under, as its place gave it. */
        name: String,
        val descriptor: ParcelFileDescriptor,
        /** Where the finished recording can be read back from - by the
         *  after-call check, which decodes it, and by the notification's Play,
         *  Share and Delete. */
        val uri: Uri,
        /** Set for app-storage files only, which are deleted as files. */
        val filePath: String? = null,
        private val onFinish: () -> Unit,
        private val onAbandon: () -> Unit,
        /** The name its place gave it once finished, where that can differ
         *  from the one it was made under: see [name]. */
        private val nameWhenFinished: () -> String? = { null },
    ) : AutoCloseable {

        /**
         * What the file is called. A provider asked for a name that is taken
         * makes another, " (1)" before the extension - MediaStore and the
         * folder provider alike, on the Android 12 and 17 emulators,
         * 2026-11-06 - and says so only when asked: this is read back, and
         * everything that goes by a recording's name goes by this one, or by
         * the other file's. MediaStore's is read once it is finished, the
         * last it can change; when it changes was not measured, only what it
         * is then. So after [publish] it is the last word.
         */
        var name: String = name
            private set

        /** Told once, with its name, when it is finished or abandoned (see open). */
        internal var whenDone: (String) -> Unit = {}
        private var done = false

        private fun ended() {
            if (done) return
            done = true
            runCatching { whenDone(name) }
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
            if (finished) runCatching { nameWhenFinished() }.getOrNull()?.let { name = it }
            ended()
            return closed && finished
        }
    }

    fun treeUri(context: Context): Uri? =
        Prefs.of(context)
            .getString(KEY_TREE, null)
            ?.toUri()

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
            .edit { putString(KEY_TREE, uri.toString()) }
        Log.i(TAG, "store: recordings will go to $uri")
    }

    /** Go back to the standard Recordings folder. */
    fun clearTree(context: Context) {
        Prefs.of(context)
            .edit { remove(KEY_TREE) }
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
     * Until it is finished it is not listed, and its waveform waits
     * (Waveforms.writingStarted): in a chosen folder or app storage the file
     * is there under its name while it fills, and a decode then would keep
     * the first minutes' levels as the whole call's.
     */
    fun open(context: Context, name: String): Target? {
        Waveforms.writingStarted(name)
        val target = FolderRecordings.open(context, name)
            ?: MediaStoreRecordings.open(context, name)
            ?: AppRecordings.open(context, name)
        if (target == null) {
            Waveforms.writingEnded(context, name)
            return null
        }
        // A name its place changed: the file of the one asked for is another
        // recording, finished, and its levels are its own.
        val opened = target.name
        if (opened != name) Waveforms.writingRenamed(name, opened)
        target.whenDone = { finished ->
            if (finished != opened) Waveforms.writingRenamed(opened, finished)
            Waveforms.writingEnded(context, finished)
        }
        return target
    }

    /** Whether a file that says it is empty is: a provider may say 0 for a size
     *  it does not know, an index may be behind its file, and a recording left
     *  out for that is in neither list. One that cannot be opened stays listed. */
    internal fun holdsNothing(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.read() < 0 } ?: false
    }.getOrDefault(false)

    /** What a provider calls the file at [uri]; null if it will not say. */
    internal fun nameOf(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

}
