package com.jemcik.jemrec.recordings

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Where a recording's levels are kept: in the app's cache, a byte a level,
 * under a hash of the file's name, which can hold a caller's; the latest in
 * memory; and which recordings are being written, whose levels must wait.
 */
internal object WaveformCache {

    private const val TAG = "JemRec"

    /** The cache's folder; a new name for a new way of working them out,
     *  and the names before it, to clear. */
    private const val DIR = "waveforms-v2"
    private val OLD_DIRS = listOf("waveforms-v1")

    /**
     * The recordings being written now (RecordingStore.open, until publish or
     * abandon). In a chosen folder or app storage a recording is there under
     * its final name while it fills: it is left out of the list until it is
     * finished, and its levels, worked out then, were the first minutes', kept
     * as the whole call's for good.
     */
    private val writing: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun writingStarted(name: String) {
        writing += name
    }

    /** Finished, or abandoned: the name's levels are worked out from what it
     *  holds now, not from any file it had before. */
    fun writingEnded(context: Context, name: String) {
        writing -= name
        forget { it == name }
        runCatching { cacheFile(context, name).delete() }
    }

    /** Its place gave it another name (RecordingStore.Target.name): that is
     *  the one not finished. Whatever is kept under the first is left. */
    fun writingRenamed(from: String, to: String) {
        writing += to
        writing -= from
    }

    fun isBeingWritten(name: String): Boolean = name in writing

    /**
     * The levels read or worked out lately, kept in memory: a row scrolled
     * back into view read its file again - a hash, a read, 800 numbers - and
     * drew its strip a frame late. The last [IN_MEMORY], about 13 KB each.
     */
    private val inMemory = object : LinkedHashMap<String, List<Float>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Float>>?) = size > IN_MEMORY
    }
    private const val IN_MEMORY = 100

    fun remembered(name: String): List<Float>? = synchronized(inMemory) { inMemory[name] }

    fun remember(name: String, levels: List<Float>) = synchronized(inMemory) { inMemory[name] = levels }

    private fun forget(names: (String) -> Boolean) = synchronized(inMemory) { inMemory.keys.removeAll(names) }

    private val _kept = MutableStateFlow(0)

    /**
     * Moves each time levels are kept, whoever worked them out - the list's
     * fill-in, the player, AfterCall: the rows still without theirs look
     * again. Only the fill-in's used to wake them, and a call saved while the
     * list was open kept a blank strip until its row was drawn again.
     */
    val kept: StateFlow<Int> = _kept.asStateFlow()

    /** What is kept for [name] on the disk; null if nothing that reads as levels. */
    fun read(context: Context, name: String): List<Float>? =
        runCatching { fromBytes(cacheFile(context, name).readBytes()) }.getOrNull()

    /** Keep [levels] for [name], in memory and on the disk, and say so ([kept]). */
    fun keep(context: Context, name: String, levels: List<Float>) {
        remember(name, levels)
        val cached = cacheFile(context, name)
        runCatching {
            cached.parentFile?.mkdirs()
            cached.writeBytes(toBytes(levels))
        }.onSuccess { _kept.update { it + 1 } }
            .onFailure { Log.w(TAG, "waveform: could not keep it: $it") }
    }

    /** Whether [name]'s levels are kept, without reading them. */
    fun has(context: Context, name: String): Boolean = cacheFile(context, name).length() == Waveforms.LEVELS.toLong()

    /** How long levels kept stay though their recording is not listed. */
    const val GRACE_MS = 10 * 60_000L

    /**
     * Forget the levels of recordings no longer kept: every cache file but
     * [names]', and but those kept in the last [graceMs] - a call saved since
     * the list was read. Nothing else removed them: a deleted recording's
     * loudness stayed in the cache until Android cleared it.
     */
    fun prune(context: Context, names: Collection<String>, graceMs: Long = GRACE_MS) {
        val dir = File(context.cacheDir, DIR)
        val listed = names.toHashSet()
        forget { it !in listed }
        val keep = names.mapTo(HashSet()) { "${hashOf(it)}.lvl" }
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { file ->
            if (file.name !in keep && now - file.lastModified() > graceMs) runCatching { file.delete() }
        }
    }

    internal fun cacheFile(context: Context, name: String): File {
        for (old in OLD_DIRS) {
            val dir = File(context.cacheDir, old)
            if (dir.exists()) runCatching { dir.deleteRecursively() }
        }
        return File(File(context.cacheDir, DIR), "${hashOf(name)}.lvl")
    }

    private fun hashOf(name: String): String =
        MessageDigest.getInstance("SHA-256").digest(name.toByteArray())
            .take(12).joinToString("") { "%02x".format(it) }

    /** A byte a level: what the cache keeps. */
    internal fun toBytes(levels: List<Float>): ByteArray =
        ByteArray(levels.size) { (levels[it] * 255).roundToInt().coerceIn(0, 255).toByte() }

    /** The cache's bytes as levels; null for a file that is not one. */
    internal fun fromBytes(bytes: ByteArray): List<Float>? =
        if (bytes.size != Waveforms.LEVELS) null else bytes.map { (it.toInt() and 0xff) / 255f }
}
