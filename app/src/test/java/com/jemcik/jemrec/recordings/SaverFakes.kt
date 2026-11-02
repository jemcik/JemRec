package com.jemcik.jemrec.recordings

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.ContactsContract
import com.jemcik.jemrec.capture.OggSink
import com.jemcik.jemrec.capture.RecordingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.robolectric.Robolectric
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The recorder, the writer and the storage, for the saver's tests: what
 * RecordingSaver's seams take in place of CaptureDaemon, OpusOggWriter and
 * RecordingStore.open. [install] puts them in, [restore] puts today's back.
 */
internal class SaverFakes(private val context: Context) {

    // ---- the recorder ------------------------------------------------------

    /** The recorder's files, by name, as the bytes it would stream. */
    val files = mutableMapOf<String, ByteArray>()
    val fetched = mutableListOf<String>()
    val deleted = mutableListOf<String>()
    var unreachable = false
    var deletes = true

    /** What LIST answers; null for a recorder that could not be asked. */
    var listing: List<String>? = emptyList()
    var listed = 0

    /** Called as each fetch begins, on the saving thread. */
    var onFetch: (String) -> Unit = {}

    /** Called as each delete begins. */
    var onDelete: (String) -> Unit = {}

    /** A fetch waits on this, when set, until [release]. */
    private var gate: CountDownLatch? = null
    private var entered: CountDownLatch? = null

    fun hold() {
        entered = CountDownLatch(1)
        gate = CountDownLatch(1)
    }

    fun awaitHeld() = check(entered!!.await(10, TimeUnit.SECONDS)) { "nothing fetched" }

    fun release() {
        gate?.countDown()
    }

    val daemon = object : RecordingSaver.DaemonFiles {
        override suspend fun fetch(context: Context, name: String): Socket {
            fetched += name
            onFetch(name)
            gate?.let { held ->
                entered?.countDown()
                held.await(10, TimeUnit.SECONDS)
            }
            // Where the recorder's own fetch, returning from its I/O thread,
            // finds the save cancelled meanwhile.
            currentCoroutineContext().ensureActive()
            if (unreachable) throw IOException("connection refused")
            // What the recorder answers in the codec id's place for a file it no longer has.
            return Canned(files[name] ?: "gone".toByteArray(Charsets.US_ASCII))
        }

        override suspend fun delete(context: Context, name: String): Boolean {
            onDelete(name)
            if (!deletes) return false
            deleted += name
            files.remove(name)
            return true
        }

        override suspend fun pending(context: Context): List<String>? {
            listed++
            return listing
        }
    }

    /** An unconnected socket whose input is [bytes]: all AudioStream asks of one. */
    private class Canned(private val bytes: ByteArray) : Socket() {
        override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)
    }

    // ---- the writer --------------------------------------------------------

    /** Every writer made, with where it wrote. */
    val sinks = mutableListOf<Sink>()
    var finishes = true
    var writerFails = false

    /** Writers that take nothing: a muxer that never started. */
    var silent = false

    /** Writes each packet's bytes, one after another: what the part's file
     *  then holds says which packets went where. */
    inner class Sink(val fd: FileDescriptor) : OggSink {
        private val out = FileOutputStream(fd)
        override var packets = 0L
        override val durationSeconds: Double get() = packets * 0.02

        override fun write(data: ByteArray, pts: Long) {
            if (silent) return
            out.write(data)
            packets++
        }

        override fun finish(): Boolean {
            out.flush()
            return finishes
        }
    }

    // ---- where a part goes ---------------------------------------------------

    private val dir = File(context.cacheDir, "saved").apply { deleteRecursively(); mkdirs() }
    val opened = mutableListOf<String>()
    val published = mutableListOf<String>()
    val abandoned = mutableListOf<String>()
    var nowhere = false

    /** Names whose file will not publish. */
    var unpublishable = setOf<String>()

    /** Names whose file cannot be written into once opened. */
    var unwritable = setOf<String>()
    val descriptors = mutableMapOf<String, FileDescriptor>()

    fun file(name: String) = File(dir, name)

    private fun open(name: String): RecordingStore.Target? {
        if (nowhere) return null
        opened += name
        val file = file(name)
        val pfd = ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE,
        )
        descriptors[name] = pfd.fileDescriptor
        if (name in unwritable) pfd.close()
        return RecordingStore.Target(
            name, pfd, Uri.fromFile(file), file.path,
            onFinish = {
                if (name in unpublishable) throw IOException("not published")
                published += name
            },
            onAbandon = {
                abandoned += name
                file.delete()
            },
        )
    }

    // ---- in and out ----------------------------------------------------------

    private val before = Seams(RecordingSaver.daemon, RecordingSaver.sinks, RecordingSaver.targets, RecordingSaver.clock)

    private class Seams(
        val daemon: RecordingSaver.DaemonFiles,
        val sinks: (FileDescriptor, ByteArray) -> OggSink,
        val targets: (Context, String) -> RecordingStore.Target?,
        val clock: () -> Long,
    )

    fun install(now: Long) {
        RecordingSaver.daemon = daemon
        RecordingSaver.sinks = { fd, _ ->
            if (writerFails) throw IOException("no muxer")
            Sink(fd).also { sinks += it }
        }
        RecordingSaver.targets = { _, name -> open(name) }
        RecordingSaver.clock = { now }
        RecordingSaver.io = Dispatchers.Unconfined
    }

    fun restore() {
        RecordingSaver.daemon = before.daemon
        RecordingSaver.sinks = before.sinks
        RecordingSaver.targets = before.targets
        RecordingSaver.clock = before.clock
        RecordingSaver.io = Dispatchers.IO
        release()
    }

    companion object {
        /** An OpusHead, as the recorder's config packet carries one. */
        val OPUS_HEAD = byteArrayOf(
            0x4f, 0x70, 0x75, 0x73, 0x48, 0x65, 0x61, 0x64, 1, 1, 0x38, 0x01, 0x80.toByte(), 0xbb.toByte(), 0, 0, 0, 0, 0,
        )
        private const val CONFIG = 1L shl 62

        /** Packet [i]'s bytes: two, so a file's content reads back as the packets it got. */
        fun payload(i: Int) = byteArrayOf((i shr 8).toByte(), i.toByte())

        /**
         * A recording as the recorder streams it (AudioStream): the codec id,
         * the config packet unless [config] is false, then [packets] packets
         * stamped [ptsUs], 20 ms apart unless told otherwise, then [tail].
         */
        fun stream(
            packets: Int,
            config: Boolean = true,
            codec: String = "opus",
            ptsUs: (Int) -> Long = { it * 20_000L },
            tail: DataOutputStream.() -> Unit = {},
        ): ByteArray {
            val buf = ByteArrayOutputStream()
            DataOutputStream(buf).apply {
                write(codec.toByteArray(Charsets.US_ASCII))
                if (config) {
                    writeLong(CONFIG); writeInt(OPUS_HEAD.size); write(OPUS_HEAD)
                }
                for (i in 0 until packets) {
                    val data = payload(i)
                    writeLong(ptsUs(i)); writeInt(data.size); write(data)
                }
                tail()
                flush()
            }
            return buf.toByteArray()
        }

        /** The bytes a part holding packets [range] was written with. */
        fun content(range: IntRange): ByteArray = range.fold(ByteArray(0)) { acc, i -> acc + payload(i) }

        /** A config packet in the middle of the stream. */
        fun DataOutputStream.configPacket() {
            writeLong(CONFIG); writeInt(OPUS_HEAD.size); write(OPUS_HEAD)
        }

        /** A packet length no recorder writes: the stream out of step. */
        fun DataOutputStream.outOfStep() {
            writeLong(0); writeInt(Int.MAX_VALUE)
        }
    }
}

/**
 * The contacts provider for Robolectric, which has none: a number is a
 * contact's when [known] has it, and in no contact otherwise - an answer,
 * not the failed lookup no provider gives.
 */
class FakeContacts : ContentProvider() {

    val known = mutableMapOf<String, Pair<Long, String>>()

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val columns = projection ?: arrayOf(
            ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.PHOTO_URI,
            ContactsContract.PhoneLookup.STARRED, ContactsContract.PhoneLookup._ID,
        )
        val cursor = MatrixCursor(columns)
        known[uri.lastPathSegment]?.let { (id, name) ->
            cursor.addRow(
                columns.map<String, Any?> {
                    when (it) {
                        ContactsContract.PhoneLookup.DISPLAY_NAME -> name
                        ContactsContract.PhoneLookup.STARRED -> 0
                        ContactsContract.PhoneLookup._ID -> id
                        else -> null
                    }
                }.toTypedArray(),
            )
        }
        return cursor
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        fun install(): FakeContacts =
            Robolectric.setupContentProvider(FakeContacts::class.java, ContactsContract.AUTHORITY)
    }
}
