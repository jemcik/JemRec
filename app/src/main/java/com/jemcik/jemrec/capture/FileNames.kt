package com.jemcik.jemrec.capture

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.telephony.PhoneNumberUtils
import androidx.core.content.edit
import com.jemcik.jemrec.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What a saved recording is called, and what a name says when read back.
 *
 * Four ways to name one, chosen in Settings:
 *
 *   - DATE, the default and the only one before 0.12:
 *     20261020_165705_out.ogg - when the call began, and which way it went.
 *   - DATE_AND_CALLER: the same, then who it was with -
 *     20261020_165705_out_Priya Shah.ogg - for anyone who browses the folder
 *     in a file manager or copies it to a computer.
 *   - DATE_AND_NUMBER: the same, then the number, even a contact's -
 *     20261020_165705_out_+447700900123.ogg - for a program that sorts the
 *     files by who the call was with (issue #71): that place in the name
 *     holds a number or nothing, never a name. Written with its country
 *     code ([international]), so one person's calls carry one number.
 *   - HONOR: Priya Shah@+447700900123_20261020165705.ogg, the name Honor's
 *     own call recorder uses. Honor's Phone app shows a play button on a call
 *     in its history when a file named like that is in Sounds/CallRecord.
 *     Tested on an Honor (see the note in [build]); offered only on a phone
 *     that has that app.
 *
 * The three with a caller in them need the call log, read when the call is
 * saved; without it, or for a call it does not have, the name falls back to
 * DATE. A name is chosen once, when the recording is saved, and never changed
 * afterwards: everything that remembers a recording - the keep mark, Recently
 * deleted, a link from a notification - remembers it by name.
 *
 * MESSENGER CALLS (VoipApps) carry the app instead of a number:
 * 20261020_203501_in_viber.ogg, with the caller after it in the formats that
 * name one - 20261020_203501_in_viber_Priya Raman.ogg - and no direction when
 * it is not known. Honor's format does not apply: its dialer has no messenger
 * calls to match, nor the number format: a messenger call has no number, and
 * a name in its place is what that format is for keeping out. Where the name
 * does not carry the caller - those two and the date format - it is noted
 * here by name instead ([noteCaller]), since nothing else can say it:
 * messenger calls are not in the phone's call log. The recording's own tags
 * carry it too (RecordingTags), and a reinstall notes it here again from them.
 *
 * READING A NAME BACK
 *
 * The list, auto-delete and the Calls tab all need a recording's start time,
 * and the list its direction; both come from the name ([parse]), in every
 * format. Honor's format has no direction in it, so for those the saver
 * notes it here, by name ([noteDirection]), and in the file's tags for a
 * reinstall (RecordingTags) - and the list falls back to the call log,
 * matched on the number the name does carry.
 */
object FileNames {

    enum class Format { DATE, DATE_AND_CALLER, DATE_AND_NUMBER, HONOR }

    private const val KEY = "file_names"
    private const val KEY_DIRECTIONS = "name_directions"
    private const val KEY_CALLERS = "name_callers"
    private const val KEEP_DIRECTIONS = 2000

    /** Honor's Phone app, which plays recordings it finds in [HONOR_FOLDER]. */
    const val HONOR_DIALER = "com.hihonor.contacts"
    const val HONOR_FOLDER = "Sounds/CallRecord"
    private const val HONOR_FOLDER_ID = "primary:$HONOR_FOLDER"
    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

    fun format(context: Context): Format =
        runCatching { Format.valueOf(Prefs.of(context).getString(KEY, null)!!) }.getOrDefault(Format.DATE)

    fun setFormat(context: Context, format: Format) {
        Prefs.of(context).edit { putString(KEY, format.name) }
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

    /** Who a call was with, as the call log and contacts have it; [country]
     *  where the call was, to read a number dialled without its country code. */
    data class Party(val number: String?, val name: String?, val country: String? = null)

    /**
     * The name for a call that began at [startedAt]. Pure - though the number
     * format's country code comes from Android's own phone number library,
     * which a plain test does not have: its tests are in NumberFileNamesTest.
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
        fun followedBy(who: String) = "${standard.removeSuffix(".${RecordingStore.EXTENSION}")}_$who.${RecordingStore.EXTENSION}"
        return when (format) {
            Format.DATE -> standard
            Format.DATE_AND_CALLER -> followedBy(clean(party?.name) ?: digits(dialled(party?.number)) ?: return standard)
            Format.DATE_AND_NUMBER -> {
                val number = dialled(party?.number)
                followedBy(international(number, party?.country) ?: digits(number) ?: return standard)
            }
            Format.HONOR -> {
                // A hidden number has nothing for the Phone app to match. Every
                // digit of the log's number, a code's or an extension's too: the
                // Phone app wanted the call's own number (above), and
                // CallLogLookup.withNumber reads the name back digit for digit.
                val number = digits(party?.number) ?: return standard
                val who = clean(party?.name) ?: number
                "$who@${number}_${honorStamp().format(Date(startedAt))}.${RecordingStore.EXTENSION}"
            }
        }
    }

    /**
     * A name made safe for any folder: no characters a file system refuses,
     * no @ (Honor's format splits on it), no % (Recently deleted escapes the
     * @ with it - see Trash.nameFor), no _ (it separates a name's parts, and
     * a caller "viber_support" would read back as a Viber call), no leading
     * dot (that hides a file), and not so long that the name hits the
     * 255-byte limit.
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

    private const val FORBIDDEN = "/\\:*?\"<>|@%_"
    private const val LONGEST = 60

    /** At most [longest] characters, never cutting an emoji's surrogate pair
     *  in two: half of one is not a character any file system will take, nor
     *  any tag reader (RecordingTags). */
    internal fun shorten(name: String, longest: Int = LONGEST): String {
        if (name.length <= longest) return name
        val end = if (Character.isHighSurrogate(name[longest - 1])) longest - 1 else longest
        return name.take(end)
    }

    /**
     * A number with its country code, +380501234567, as the number format
     * writes it. The call log has a number as it was dialled (Android:
     * CallLog.Calls.NUMBER, "as the user entered it"), and an incoming one as
     * the network sent it - all 60 incoming calls on the Honor began with +,
     * 2026-11-01 - so one person can be 0501234567 one way and +380501234567
     * the other. [country] reads the first: the call log keeps one with each
     * call (UA on all 178 of the Honor's). Null where Android does not take
     * it for a valid number - a short code like 477, no country to read a
     * local number in, or a range newer than the phone's own copy of the
     * numbering plan - and the name then has its [digits].
     */
    internal fun international(number: String?, country: String?): String? {
        if (number.isNullOrBlank()) return null
        return runCatching { PhoneNumberUtils.formatNumberToE164(number, country?.uppercase(Locale.ROOT)) }.getOrNull()
    }

    /**
     * The number a call was dialled to, as a name may carry it. What follows a
     * pause (,) or a wait (;) is not the number but an extension, dialled once
     * the call is up - the network part ends there, as
     * PhoneNumberUtils.extractNetworkPortion has it - and "With the caller"
     * named 0501234567,123 as 0501234567123 (FileNamesTest). Caller ID
     * hidden or shown for one call is dialled in front of the number, #31# or
     * *31# (3GPP TS 22.030): the number is what follows. Any other * or #
     * makes it a code - *86 - whose digits were never a number (Android gives
     * it no country code either): null rather than those.
     */
    private fun dialled(number: String?): String? =
        number?.substringBefore(PhoneNumberUtils.PAUSE)?.substringBefore(PhoneNumberUtils.WAIT)?.trim()
            ?.removePrefix("#31#")?.removePrefix("*31#")?.takeIf { '*' !in it && '#' !in it }

    /** A number as it goes into a name: digits and a leading +, nothing else. */
    internal fun digits(number: String?): String? {
        val n = number?.trim() ?: return null
        val kept = n.filter { it.isDigit() }
        if (kept.isEmpty()) return null
        return if (n.startsWith("+")) "+$kept" else kept
    }

    /**
     * A recording's name as a log line may show it: its date and time, never
     * the caller. With the caller in it, a file name is a person's name and
     * number - not for logcat, which bug reports and anything with shell
     * access can read.
     */
    internal fun forLog(name: String): String =
        parse(name)?.let { "%1\$tY%1\$tm%1\$td_%1\$tH%1\$tM%1\$tS".format(it.startedAt) } ?: "a recording"

    /** What a name says about its call. */
    internal data class Parsed(
        val startedAt: Long,
        /** Null for Honor's format, which does not say. */
        val incoming: Boolean?,
        val caller: String? = null,
        val number: String? = null,
        /** A messenger call's app, by tag (VoipApps); null for a phone call. */
        val app: String? = null,
    )

    /** A messenger call's name. Pure. */
    internal fun buildVoip(format: Format, startedAt: Long, incoming: Boolean?, tag: String, caller: String?): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(startedAt))
        val direction = when (incoming) {
            true -> "_in"
            false -> "_out"
            null -> ""
        }
        val who = when (format) {
            Format.DATE, Format.DATE_AND_NUMBER -> null
            Format.DATE_AND_CALLER, Format.HONOR -> clean(caller)
        }
        return "$stamp${direction}_$tag${who?.let { "_$it" } ?: ""}.${RecordingStore.EXTENSION}"
    }

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
        // "_in_viber_Priya Raman" from "..._in_viber_Priya Raman.ogg": an
        // optional direction, an optional messenger, an optional caller.
        var rest = name.drop(15).substringBeforeLast('.').removePrefix("_")
        val incoming = when {
            rest == "in" || rest.startsWith("in_") -> true
            rest == "out" || rest.startsWith("out_") -> false
            else -> null
        }
        if (incoming != null) rest = rest.removePrefix(if (incoming) "in" else "out").removePrefix("_")
        val app = rest.substringBefore('_').takeIf { it in VoipApps.TAGS }
        if (app != null) rest = rest.removePrefix(app).removePrefix("_")
        // A caller only where the name has said what it is; anything else
        // after the time is a name that is not one of ours.
        val caller = rest.trim().ifEmpty { null }?.takeIf { incoming != null || app != null }
        return Parsed(at, incoming, caller, app = app)
    }

    private fun honorStamp() = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { isLenient = false }

    // --- direction, for the names that do not carry it -----------------------

    /** The saver just saved [name]: if its name will not say which way the
     *  call went, remember it here. */
    fun noteDirection(context: Context, name: String, incoming: Boolean) =
        noteDirections(context, mapOf(name to incoming))

    /** The same, for many at once: one write of the preferences. */
    @Synchronized
    fun noteDirections(context: Context, directions: Map<String, Boolean>) {
        val unsaid = directions.filterKeys { parse(it)?.incoming == null }
        if (unsaid.isEmpty()) return
        val prefs = Prefs.of(context)
        val entries = (prefs.getStringSet(KEY_DIRECTIONS, emptySet()) ?: emptySet()).toMutableSet()
        entries.removeAll { it.drop(2) in unsaid }
        unsaid.forEach { (name, incoming) -> entries += "${if (incoming) "i" else "o"}:$name" }
        // The newest only: a direction outlives its file here at the cost of a
        // few bytes, and a bound keeps that from adding up.
        val kept = if (entries.size <= KEEP_DIRECTIONS) {
            entries
        } else {
            entries.sortedByDescending { parse(it.drop(2))?.startedAt ?: 0L }.take(KEEP_DIRECTIONS).toSet()
        }
        prefs.edit { putStringSet(KEY_DIRECTIONS, kept) }
    }

    /** A messenger call's caller, by the recording's name, when the name
     *  itself does not carry it (the date and number formats). */
    fun noteCaller(context: Context, name: String, caller: String) = noteCallers(context, mapOf(name to caller))

    /** The same, for many at once: one write of the preferences. */
    @Synchronized
    fun noteCallers(context: Context, callers: Map<String, String>) {
        if (callers.isEmpty()) return
        val prefs = Prefs.of(context)
        val entries = (prefs.getStringSet(KEY_CALLERS, emptySet()) ?: emptySet()).toMutableSet()
        entries.removeAll { it.substringBefore('\u0000') in callers }
        callers.forEach { (name, caller) -> entries += "$name\u0000$caller" }
        val kept = if (entries.size <= KEEP_DIRECTIONS) {
            entries
        } else {
            entries.sortedByDescending { parse(it.substringBefore('\u0000'))?.startedAt ?: 0L }.take(KEEP_DIRECTIONS).toSet()
        }
        prefs.edit { putStringSet(KEY_CALLERS, kept) }
    }

    /** Every caller noted, by name. */
    fun callers(context: Context): Map<String, String> =
        (Prefs.of(context).getStringSet(KEY_CALLERS, emptySet()) ?: emptySet())
            .associate { it.substringBefore('\u0000') to it.substringAfter('\u0000') }

    /** Every direction noted, by name: true for incoming. */
    fun directions(context: Context): Map<String, Boolean> =
        (Prefs.of(context).getStringSet(KEY_DIRECTIONS, emptySet()) ?: emptySet())
            .associate { it.drop(2) to it.startsWith("i:") }
}
