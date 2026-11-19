package com.jemcik.jemrec.recordings

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import com.jemcik.jemrec.audio.OggPages
import com.jemcik.jemrec.audio.OggPages.HEADER
import com.jemcik.jemrec.audio.OggPages.LONGEST_PAGE
import com.jemcik.jemrec.audio.OggPages.Page
import com.jemcik.jemrec.audio.OggPages.readPage
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * What JemRec knows of a recording, written in the recording itself.
 *
 * The marks a row shows - a message, kept, a messenger call's caller, and for
 * Honor's names the direction - were kept only in the app's preferences, by
 * name, and an uninstall deletes those. The recordings stay, and after a
 * reinstall every message was an ordinary call and every messenger call "Viber
 * call" (the Honor, 2026-10-24). The file is the one thing all three places a
 * recording can live have in common, and the one thing that outlives the app;
 * so each recording now carries its own, and the list reads them back for any
 * recording the preferences know nothing about (recover).
 *
 * WHERE IN THE FILE
 *
 * An Ogg Opus file's second page is its tags (OpusTags, RFC 7845 section
 * 5.2): a vendor string and a list of NAME=value fields, which every reader
 * skips past or shows. MediaMuxer writes it with "libopus" and no fields, and
 * cannot be told to write any (measured on the Honor). So the saver lets the
 * muxer write the file somewhere else and copies it into place with that one
 * page swapped for one of these, and its audio pages joined and given their
 * positions on the way (OggPages; [splice]): the audio itself is the
 * muxer's, byte for byte.
 *
 * The fields are JemRec's own names, JEMREC_*: Android's media index reads
 * only the standard ones (TITLE, ARTIST...), so these show in no music app.
 * Measured on the Honor, 2026-10-24, with a 20 s tone: tagged like this, the
 * media scanner read it as 20 s, like the untagged copy, and with an ARTIST
 * added showed that artist - Android reads this page, padding and all - while
 * the JEMREC_* fields showed nowhere.
 *
 * ROOM FOR THE KEEP MARK
 *
 * The keep mark changes after the save. The packet is written at a fixed
 * size, its fields followed by zeros (the RFC's padding), big enough to hold
 * every field with the keep mark on: a toggle rewrites this one page in place
 * at the same length ([rewrite]) - a few hundred bytes, not the file. A file
 * from before this, or one another program has retagged, has no room; its
 * mark stays in the preferences only, as every mark did before.
 */
internal object RecordingTags {

    private const val TAG = "JemRec"

    /** What a recording carries. */
    data class Facts(
        /** A message the answering machine took (RecordingMarks.Messages). */
        val message: Boolean = false,
        /** Which way the call went; null where nobody said - a messenger
         *  call whose notification did not. */
        val incoming: Boolean? = null,
        /** A messenger call's caller, as its notification named them
         *  (VoipCallNotes): never in the phone's call log. */
        val caller: String? = null,
        /** Marked to keep (RecordingMarks.Keep). */
        val kept: Boolean = false,
        /** When the recording began, in UTC ms (RecordingMarks.Starts): its
         *  name holds only the wall clock. */
        val startedAt: Long? = null,
        /** One of a call's recordings - a recorder lost mid-call having left
         *  more, one started mid-call (joined), or one stopped before its call
         *  ended (RecordingMarks.Parts, SavePlan.isPart): no short call. */
        val part: Boolean = false,
        /** A part joined mid-call: its call's start, in UTC ms
         *  (Recording.callAt). */
        val callAt: Long? = null,
    )

    /** Says the fields are JemRec's, and which version of them. */
    private const val MARK = "JEMREC"
    private const val VERSION = "1"
    private const val MESSAGE = "JEMREC_MESSAGE"
    private const val DIRECTION = "JEMREC_DIRECTION"
    private const val CALLER = "JEMREC_CALLER"
    private const val KEPT = "JEMREC_KEEP"
    private const val START = "JEMREC_START"
    private const val PART = "JEMREC_PART"
    private const val CALL = "JEMREC_CALL"

    /** A caller's name is cut to this many characters in the tags: a name,
     *  not a paragraph, and the page must stay [PACKET_SIZE]. */
    private const val LONGEST_CALLER = 120

    /** The tags packet's size, unless its fields need more. */
    internal const val PACKET_SIZE = 512

    internal fun fields(facts: Facts): List<String> = buildList {
        add("$MARK=$VERSION")
        if (facts.message) add("$MESSAGE=1")
        facts.incoming?.let { add("$DIRECTION=${if (it) "in" else "out"}") }
        facts.caller?.trim()?.ifEmpty { null }?.let { add("$CALLER=${FileNames.shorten(it, LONGEST_CALLER)}") }
        if (facts.kept) add("$KEPT=1")
        facts.startedAt?.let { add("$START=$it") }
        if (facts.part) add("$PART=1")
        facts.callAt?.let { add("$CALL=$it") }
    }

    /** What [fields] say; null if they are not JemRec's. Field names are
     *  read in any case, as the RFC has them. */
    internal fun facts(fields: List<String>): Facts? {
        val byName = fields.filter { '=' in it }
            .associate { it.substringBefore('=').uppercase() to it.substringAfter('=') }
        if (byName[MARK] == null) return null
        return Facts(
            message = byName[MESSAGE] == "1",
            incoming = when (byName[DIRECTION]) {
                "in" -> true
                "out" -> false
                else -> null
            },
            caller = byName[CALLER]?.trim()?.ifEmpty { null },
            kept = byName[KEPT] == "1",
            startedAt = byName[START]?.toLongOrNull(),
            part = byName[PART] == "1",
            callAt = byName[CALL]?.toLongOrNull(),
        )
    }

    /** [fields] with the keep mark set as [kept], every other field as it was. */
    internal fun withKept(fields: List<String>, kept: Boolean): List<String> =
        fields.filterNot { it.substringBefore('=').equals(KEPT, ignoreCase = true) } +
            (if (kept) listOf("$KEPT=1") else emptyList())

    // --- Ogg -----------------------------------------------------------------

    private val OPUS_HEAD = "OpusHead".toByteArray(Charsets.US_ASCII)
    private val OPUS_TAGS = "OpusTags".toByteArray(Charsets.US_ASCII)

    /** The two header pages, if they are what an Ogg Opus file starts with:
     *  OpusHead, then OpusTags complete on its own page, as the RFC has it. */
    private class Headers(val head: Page, val tags: Page) {
        val tagsOffset: Int get() = head.bytes.size
    }

    /** The headers, if they are there, and every byte read looking for them:
     *  a file that is not what they say is copied whole from those. */
    private fun readHeaders(input: InputStream): Pair<Headers?, ByteArray> {
        val seen = Kept(input)
        val head = readPage(seen)
        val tags = head?.let { readPage(seen) }
        val ok = head != null && tags != null &&
            head.startsWith(OPUS_HEAD) && head.headerType and 2 != 0 &&
            tags.startsWith(OPUS_TAGS) && tags.headerType == 0 && tags.onePacket
        return (if (ok) Headers(head!!, tags!!) else null) to seen.bytes()
    }

    /** [input], keeping a copy of what is read from it. */
    private class Kept(private val input: InputStream) : InputStream() {
        private val copy = java.io.ByteArrayOutputStream()
        fun bytes(): ByteArray = copy.toByteArray()
        override fun read(): Int = input.read().also { if (it >= 0) copy.write(it) }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            input.read(b, off, len).also { if (it > 0) copy.write(b, off, it) }
    }

    /** An OpusTags packet: vendor, fields, and the size it was written at. */
    private class Tags(val vendor: ByteArray, val fields: List<String>, val size: Int)

    private fun parseTags(packet: ByteArray): Tags? = runCatching {
        val b = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        b.position(OPUS_TAGS.size)
        val vendor = b.lengthPrefixed()
        val count = b.int
        require(count in 0..(b.remaining() / 4))
        val fields = List(count) { b.lengthPrefixed().toString(Charsets.UTF_8) }
        Tags(vendor, fields, packet.size)
    }.getOrNull()

    /** A length, then that many bytes - the length checked against what is
     *  left first: another program's tags can say anything, and a length
     *  taken on trust is an allocation of up to 2 GB. */
    private fun ByteBuffer.lengthPrefixed(): ByteArray {
        val length = int
        require(length in 0..remaining())
        return ByteArray(length).also { get(it) }
    }

    /** The packet for [fields] at [size] bytes, zeros after the fields; null
     *  if they do not fit. */
    private fun tagsPacket(vendor: ByteArray, fields: List<String>, size: Int): ByteArray? {
        val encoded = fields.map { it.toByteArray(Charsets.UTF_8) }
        val needed = needed(vendor, encoded)
        if (needed > size) return null
        val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        b.put(OPUS_TAGS).putInt(vendor.size).put(vendor).putInt(encoded.size)
        encoded.forEach { b.putInt(it.size).put(it) }
        return b.array()
    }

    private fun needed(vendor: ByteArray, encoded: List<ByteArray>): Int =
        OPUS_TAGS.size + 4 + vendor.size + 4 + encoded.sumOf { 4 + it.size }

    /** A page holding [packet] alone, numbered as [like] was. */
    private fun pageFor(like: Page, packet: ByteArray): ByteArray {
        require(packet.size < 255 * 255) { "a tags packet of ${packet.size} bytes" }
        val lacing = ByteArray(packet.size / 255 + 1) { i -> if (i < packet.size / 255) 255.toByte() else (packet.size % 255).toByte() }
        val page = like.bytes.copyOfRange(0, HEADER) + lacing + packet
        page[26] = lacing.size.toByte()
        return OggPages.sealed(page)
    }

    /**
     * Copy an Ogg Opus file from [input] to [output] with its tags page
     * replaced by one carrying [facts], at a size that leaves room to mark it
     * kept later, and its audio pages joined about a second to a page
     * (OggPages: the muxer's page a packet cost about 11 kbps), each with the
     * position RFC 7845 gives it and the last marked the stream's end. The
     * first page, OpusHead, is copied as it was. A file that does not start
     * as an Ogg Opus file should is copied untouched. Returns whether the
     * tags went in.
     *
     * This copy is the only pass over a recording after the muxer, so a save
     * retried without a work file (RecordingSaver: storage for one copy, not
     * two) keeps the muxer's pages, as it keeps no tags - given their
     * positions where they lie ([mendInPlace]), not joined.
     */
    fun splice(input: InputStream, output: OutputStream, facts: Facts): Boolean {
        val (headers, read) = readHeaders(input)
        val tags = headers?.let { parseTags(it.tags.body()) }
        val replaced = if (headers != null && tags != null) {
            val fields = fields(facts)
            val roomy = withKept(fields, kept = true).map { it.toByteArray(Charsets.UTF_8) }
            val size = maxOf(PACKET_SIZE, needed(tags.vendor, roomy))
            tagsPacket(tags.vendor, fields, size)?.let { pageFor(headers.tags, it) }
        } else {
            null
        }
        if (replaced != null) {
            output.write(headers!!.head.bytes)
            output.write(replaced)
            OggPages.join(input, output)
        } else {
            output.write(read)
            input.copyTo(output)
        }
        return replaced != null
    }

    /** What the file starting with [input] says; null if nothing of ours. */
    fun read(input: InputStream): Facts? {
        val (headers, _) = readHeaders(input)
        val tags = headers?.let { parseTags(it.tags.body()) } ?: return null
        return facts(tags.fields)
    }

    /**
     * The tags page of the file whose first bytes are [start], rewritten with
     * the keep mark as [kept], at the same length: where it goes, and its
     * bytes. Null where that cannot be done - not our tags, or no room left.
     */
    internal fun rewrite(start: InputStream, kept: Boolean): Pair<Int, ByteArray>? {
        val (headers, _) = readHeaders(start)
        headers ?: return null
        val tags = parseTags(headers.tags.body()) ?: return null
        facts(tags.fields) ?: return null
        val packet = tagsPacket(tags.vendor, withKept(tags.fields, kept), tags.size) ?: return null
        val page = pageFor(headers.tags, packet)
        // The same packet size gives the same lacing, so the same length;
        // anything else would overwrite the audio after it.
        if (page.size != headers.tags.bytes.size) return null
        return headers.tagsOffset to page
    }

    // --- the phone ---------------------------------------------------------

    /** Copy the muxer's finished [from] into a recording's [to], with its
     *  tags. The descriptor is left open: publishing closes it. */
    fun copyTagged(from: File, to: FileDescriptor, facts: Facts) {
        FileInputStream(from).buffered().use { input ->
            // Not closed: a stream made on a descriptor does not own it here,
            // and it is the Target's to close.
            val output = FileOutputStream(to).buffered()
            if (!splice(input, output, facts)) Log.w(TAG, "tags: not an Ogg Opus start; copied without tags")
            output.flush()
        }
        synced(to)
    }

    /** The muxer's file, written into a recording's [to] itself where no
     *  work file fit: its pages given their positions and the last mark
     *  where they lie (OggPages.mend). A file that would not take them plays
     *  as the muxer left it, so this only says so. */
    fun mendInPlace(to: FileDescriptor) {
        // Positional reads and writes on the one descriptor: neither moves it.
        val reading = FileInputStream(to).channel
        val writing = FileOutputStream(to).channel
        runCatching {
            OggPages.mend(
                read = { offset, into, at, length -> reading.read(ByteBuffer.wrap(into, at, length), offset) },
                write = { offset, page -> writing.write(ByteBuffer.wrap(page), offset) },
            )
        }.onFailure { Log.w(TAG, "tags: the muxer's pages could not be given their positions: ${it.javaClass.simpleName}") }
        synced(to)
    }

    /** [to] written through to the storage before the recorder's copy may
     *  go: a power cut after an unsynced save could leave the published file
     *  without its sound and the recorder's copy deleted (AtomicFile syncs
     *  before it commits for the same reason). One that cannot be synced -
     *  not every provider's descriptor can - is only logged. */
    private fun synced(to: FileDescriptor) {
        runCatching { to.sync() }.onFailure { Log.w(TAG, "tags: a saved recording could not be synced: ${it.javaClass.simpleName}") }
    }

    /**
     * Where the muxer writes a recording before it is copied into place. Not
     * the cache: Android empties that when storage runs low - the moment a
     * save is already at risk - and the copy reopens this file by name.
     */
    fun workFile(context: Context): File {
        val dir = File(context.noBackupFilesDir, "saving").apply { mkdirs() }
        // Any left from a save that was killed half way: a day is far past
        // the longest copy, so none of these is one in progress.
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > DAY_MS }?.forEach { it.delete() }
        return File.createTempFile("rec", ".ogg", dir)
    }

    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** What [recording]'s own file says - null if nothing of ours - or a
     *  failure, if it could not be read. */
    fun read(context: Context, recording: Recording): Result<Facts?> = runCatching {
        recording.location.read(context).buffered().use { read(it) }
    }

    private val keeping = Any()

    /**
     * Write each of [recordings]' keep marks into its file, as the
     * preferences have it now - not as the tap that asked for the write had
     * it: two taps in quick succession are two writes on other threads, in
     * either order, and the file must end as the list does. Those with no
     * room for it, and those this app may not write - after a reinstall
     * Android counts the files the old install made as nobody's - keep it in
     * the preferences alone, as before. How many took it.
     */
    fun writeKept(context: Context, recordings: List<Recording>): Int = synchronized(keeping) {
        val marked = RecordingMarks.Keep.all(context)
        var written = 0
        for (recording in recordings) {
            val kept = recording.name in marked
            val ok = runCatching { writeKept(context, recording, kept) }
                .onFailure { Log.w(TAG, "tags: could not write the keep mark into ${FileNames.forLog(recording.name)}: ${it.javaClass.simpleName}") }
                .getOrDefault(false)
            if (ok) written++
        }
        if (written < recordings.size) Log.i(TAG, "tags: keep mark in ${recordings.size - written} of ${recordings.size} kept by the app only")
        return written
    }

    private fun writeKept(context: Context, recording: Recording, kept: Boolean): Boolean {
        val pfd = recording.location.change(context) ?: return false
        return pfd.use { rewriteIn(it, kept) }
    }

    private fun rewriteIn(pfd: ParcelFileDescriptor, kept: Boolean): Boolean {
        // Positional reads and writes on the one descriptor: neither moves it.
        val reading = FileInputStream(pfd.fileDescriptor).channel
        val writing = FileOutputStream(pfd.fileDescriptor).channel
        val (offset, page) = rewrite(headStart(reading), kept) ?: return false
        writing.write(ByteBuffer.wrap(page), offset.toLong())
        pfd.fileDescriptor.sync()
        return true
    }

    /** The file's first two pages' worth, at most, read without moving it. */
    private fun headStart(channel: FileChannel): InputStream {
        val buffer = ByteBuffer.allocate(2 * LONGEST_PAGE)
        var at = 0L
        while (buffer.hasRemaining()) {
            val n = channel.read(buffer, at)
            if (n <= 0) break
            at += n
        }
        return buffer.array().inputStream(0, buffer.position())
    }

    /** RecordingMarks.recover's, under the names from before it. */
    fun recover(context: Context, recordings: List<Recording>) = RecordingMarks.recover(context, recordings)

    internal fun readNames(context: Context): Set<String> = RecordingMarks.readNames(context)
}
