package com.jemcik.jemrec.capture

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.jemcik.jemrec.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What a saved recording is called, and what a name says when read back.
 *
 * Three ways to name one, chosen in Settings:
 *
 *   - DATE, the default and the only one before 0.12:
 *     20261020_165705_out.ogg - when the call began, and which way it went.
 *   - DATE_AND_CALLER: the same, then who it was with -
 *     20261020_165705_out_Priya Shah.ogg - for anyone who browses the folder
 *     in a file manager or copies it to a computer.
 *   - HONOR: Priya Shah@+447700900123_20261020165705.ogg, the name Honor's
 *     own call recorder uses. Honor's Phone app shows a play button on a call
 *     in its history when a file named like that is in Sounds/CallRecord.
 *     Tested on an Honor (see the note in [build]); offered only on a phone
 *     that has that app.
 *
 * The two with a caller in them need the call log, read when the call is
 * saved; without it, or for a call it does not have, the name falls back to
 * DATE. A name is chosen once, when the recording is saved, and never changed
 * afterwards: everything that remembers a recording - the keep mark, Recently
 * deleted, a link from a notification - remembers it by name.
 *
 * READING A NAME BACK
 *
 * The list, auto-delete and the Calls tab all need a recording's start time,
 * and the list its direction; both come from the name ([parse]), in every
 * format. Honor's format has no direction in it, so for those the saver
 * notes it here, by name ([noteDirection]) - and the list falls back to the
 * call log, matched on the number the name does carry.
 */
object FileNames {

    enum class Format { DATE, DATE_AND_CALLER, HONOR }

    private const val KEY = "file_names"
    private const val KEY_DIRECTIONS = "name_directions"
    private const val KEEP_DIRECTIONS = 2000

    /** Honor's Phone app, which plays recordings it finds in [HONOR_FOLDER]. */
    const val HONOR_DIALER = "com.hihonor.contacts"
    const val HONOR_FOLDER = "Sounds/CallRecord"
    private const val HONOR_FOLDER_ID = "primary:$HONOR_FOLDER"
    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

    fun format(context: Context): Format =
        runCatching { Format.valueOf(Prefs.of(context).getString(KEY, null)!!) }.getOrDefault(Format.DATE)

    fun setFormat(context: Context, format: Format) {
        Prefs.of(context).edit().putString(KEY, format.name).apply()
    }

    /** Whether this phone has Honor's Phone app, the only reason to offer [Format.HONOR]. */
    fun honorDialer(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(HONOR_DIALER, 0); true }.getOrDefault(false)

    /** Where the folder picker opens for the Honor format: the folder it needs. */
    val honorFolderUri: Uri get() = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE, HONOR_FOLDER_ID)

    /** Whether recordings are going to [HONOR_FOLDER] now. */
    fun inHonorFolder(context: Context): Boolean = RecordingStore.treeUri(context)
        ?.let { runCatching { DocumentsContract.getTreeDocumentId(it) }.getOrNull() }
        .equals(HONOR_FOLDER_ID, ignoreCase = true)

    /** Who a call was with, as the call log and contacts have it. */
    data class Party(val number: String?, val name: String?)

    /**
     * The name for a call that began at [startedAt]. Pure.
     *
     * HONOR's name, as tested on the phone with copies of one recording:
     *   - its time may be anywhere inside the call, from when the call log
     *     dates it to that plus its length. JemRec's start is when the call
     *     was answered or dialled, so it always is. A file six seconds after
     *     the call ended was not matched;
     *   - the number has to be the call's: "477@478_..." lost the button;
     *   - the part before the @ is not read at all: "Zed@477_..." kept it.
     * Both Opus in Ogg and AAC play from it.
     */
    internal fun build(format: Format, startedAt: Long, incoming: Boolean, party: Party?): String {
        val standard = RecordingStore.nameFor(Date(startedAt), incoming)
        return when (format) {
            Format.DATE -> standard
            Format.DATE_AND_CALLER -> {
                val who = clean(party?.name) ?: digits(party?.number) ?: return standard
                "${standard.removeSuffix(".${RecordingStore.EXTENSION}")}_$who.${RecordingStore.EXTENSION}"
            }
            Format.HONOR -> {
                // A hidden number has nothing for the Phone app to match.
                val number = digits(party?.number) ?: return standard
                val who = clean(party?.name) ?: number
                "$who@${number}_${honorStamp().format(Date(startedAt))}.${RecordingStore.EXTENSION}"
            }
        }
    }

    /**
     * A name made safe for any folder: no characters a file system refuses,
     * no @ (Honor's format splits on it), no % (Recently deleted escapes the
     * @ with it - see Trash.nameFor), no leading dot (that hides a file), and
     * not so long that the name hits the 255-byte limit.
     */
    internal fun clean(name: String?): String? =
        name?.map { c -> if (c in FORBIDDEN || c.isISOControl()) ' ' else c }
            ?.joinToString("")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.trimStart('.')
            ?.trim()
            ?.let(::shorten)
            ?.trim()
            ?.ifEmpty { null }

    private const val FORBIDDEN = "/\\:*?\"<>|@%"
    private const val LONGEST = 60

    /** At most [LONGEST] characters, never cutting an emoji's surrogate pair
     *  in two: half of one is not a character any file system will take. */
    private fun shorten(name: String): String {
        if (name.length <= LONGEST) return name
        val end = if (Character.isHighSurrogate(name[LONGEST - 1])) LONGEST - 1 else LONGEST
        return name.take(end)
    }

    /** A number as it goes into a name: digits and a leading +, nothing else. */
    internal fun digits(number: String?): String? {
        val n = number?.trim() ?: return null
        val kept = n.filter { it.isDigit() }
        if (kept.isEmpty()) return null
        return if (n.startsWith("+")) "+$kept" else kept
    }

    /** What a name says about its call. */
    internal data class Parsed(
        val startedAt: Long,
        /** Null for Honor's format, which does not say. */
        val incoming: Boolean?,
        val caller: String? = null,
        val number: String? = null,
    )

    private val HONOR_NAME = Regex("""^(.*)@(\+?\d+)_(\d{14})\.\w+$""")

    /** Read a recording's name back, in any of the formats; null if it is not one. Pure. */
    internal fun parse(name: String): Parsed? {
        HONOR_NAME.matchEntire(name)?.let { m ->
            val at = runCatching { honorStamp().parse(m.groupValues[3])?.time }.getOrNull() ?: return null
            return Parsed(at, null, m.groupValues[1].trim().ifEmpty { null }, m.groupValues[2])
        }
        // Exactly as the list always read it, so no name it read differently
        // before reads differently now.
        val at = runCatching {
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(name.take(15))?.time
        }.getOrNull() ?: return null
        val rest = name.drop(15)
        val incoming = when {
            rest.startsWith("_in.") || rest.startsWith("_in_") -> true
            rest.startsWith("_out.") || rest.startsWith("_out_") -> false
            else -> null
        }
        val caller = when (incoming) {
            true -> rest.removePrefix("_in")
            false -> rest.removePrefix("_out")
            null -> null
        }?.takeIf { it.startsWith("_") }?.drop(1)?.substringBeforeLast('.')?.trim()?.ifEmpty { null }
        return Parsed(at, incoming, caller)
    }

    private fun honorStamp() = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { isLenient = false }

    // --- direction, for the names that do not carry it -----------------------

    /** The saver just saved [name]: if its name will not say which way the
     *  call went, remember it here. */
    @Synchronized
    fun noteDirection(context: Context, name: String, incoming: Boolean) {
        if (parse(name)?.incoming != null) return
        val prefs = Prefs.of(context)
        val entries = (prefs.getStringSet(KEY_DIRECTIONS, emptySet()) ?: emptySet()).toMutableSet()
        entries.removeAll { it.drop(2) == name }
        entries += "${if (incoming) "i" else "o"}:$name"
        // The newest only: a direction outlives its file here at the cost of a
        // few bytes, and a bound keeps that from adding up.
        val kept = if (entries.size <= KEEP_DIRECTIONS) {
            entries
        } else {
            entries.sortedByDescending { parse(it.drop(2))?.startedAt ?: 0L }.take(KEEP_DIRECTIONS).toSet()
        }
        prefs.edit().putStringSet(KEY_DIRECTIONS, kept).apply()
    }

    /** Every direction noted, by name: true for incoming. */
    fun directions(context: Context): Map<String, Boolean> =
        (Prefs.of(context).getStringSet(KEY_DIRECTIONS, emptySet()) ?: emptySet())
            .associate { it.drop(2) to it.startsWith("i:") }
}
