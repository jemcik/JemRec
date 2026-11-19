package com.jemcik.jemrec.recordings

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.edit
import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.calls.PhoneNumbers
import com.jemcik.jemrec.calls.VoipApps
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
 *     own call recorder uses - and +447700900123_20261020165705.ogg for a
 *     caller with no name. Honor's Phone app shows a play button on a call
 *     in its history when a file named like that is in Sounds/CallRecord.
 *     Tested on an Honor (see the note in [build]); offered only on a phone
 *     that has that app.
 *
 * The three with a caller in them need the call log, read when the call is
 * saved; without it, or for a call it does not have, the name falls back to
 * DATE. A name is chosen once, when the recording is saved, and never changed
 * afterwards - but for a recording restored onto a name taken meanwhile:
 * everything that remembers a recording - the keep mark, Recently deleted, a
 * link from a notification - remembers it by name.
 *
 * MESSENGER CALLS (VoipApps) carry the app instead of a number:
 * 20261020_203501_in_viber.ogg, with the caller after it in the formats that
 * name one - 20261020_203501_in_viber_Priya Raman.ogg - and no direction when
 * it is not known. Honor's format does not apply: its dialer has no messenger
 * calls to match, nor the number format: a messenger call has no number, and
 * a name in its place is what that format is for keeping out. Where the name
 * does not carry the caller - those two and the date format - it is noted
 * by name instead (RecordingMarks.Callers), since nothing else can say it:
 * messenger calls are not in the phone's call log. The recording's own tags
 * carry it too (RecordingTags), and a reinstall notes it here again from them.
 *
 * READING A NAME BACK
 *
 * The list, auto-delete and the Calls tab all need a recording's start time,
 * and the list its direction; both come from the name ([parse]), in every
 * format. Honor's format has no direction in it, so for those the saver
 * notes it by name (RecordingMarks.Directions), and in the file's tags for a
 * reinstall (RecordingTags) - and the list falls back to the call log,
 * matched on the number the name does carry.
 */
object FileNames {

    enum class Format { DATE, DATE_AND_CALLER, DATE_AND_NUMBER, HONOR }

    private const val KEY = "file_names"

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
     *
     * BUT IT IS READ AS A REGULAR EXPRESSION. To take the _ out of a name
     * before the @, the Phone app runs String.replaceAll with that part, @
     * and all, as the pattern (CallRecordUtils.getRecordPhoneNumber, read in
     * HnContacts 19.0.8.606 on the Honor, 2026-11-17). A pattern that does
     * not compile - "+380954388250@", the number JemRec put there for a
     * caller with no name, starts with a dangling + - throws, the name reads
     * as having no number, and the call gets no play button: a real call on
     * the Honor that day, and copies of two older recordings, showed none;
     * the same recording named "+380954388250_20261117155459.ogg" got one
     * at once. So a caller with no name gets "<number>_<time>", which the
     * Phone app reads too, and a name loses what a pattern or its
     * replacement could choke on ([honorWho]).
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
                // CallMatch.withNumber reads the name back digit for digit.
                val number = digits(party?.number) ?: return standard
                "${honorStem(party?.name, number, honorStamp().format(Date(startedAt)))}.${RecordingStore.EXTENSION}"
            }
        }
    }

    /** Honor's name for a call with [number] at [stamp] (yyyyMMddHHmmss),
     *  with no extension: "<who>@<number>_<time>", or "<number>_<time>" for
     *  a caller with no name the Phone app can take ([honorWho]) - or whose
     *  name is the number itself, as a contact saved by its digits is: written
     *  "<number>@<number>", the name was one [honorRenamed] renamed at the
     *  next reading, under its own notification (review, 2026-11-18). */
    private fun honorStem(who: String?, number: String, stamp: String): String =
        honorWho(who?.takeUnless { isTheNumber(it, number) })?.let { "$it@${number}_$stamp" } ?: "${number}_$stamp"

    /** Whether a caller's name is only [number] written out: no letters, and
     *  its digits the number's - "+38 050 123 45 67" for +380501234567. */
    private fun isTheNumber(who: String, number: String): Boolean =
        who.none { it.isLetter() } && who.filter { it.isDigit() } == number.filter { it.isDigit() }

    /**
     * The name this version gives the call an Honor-format [name] stands for,
     * where the Phone app cannot read the number in [name] (HonorPhoneApp):
     * the files saved before 1.2 with a "+" before the @ - a caller with no
     * name, written with the number there, or a name that starts with one -
     * or a bracket left open there (see [build]). Null for any other name,
     * one the app reads included, and for one already right. A provider's
     * " (1)" stays where it was. Pure.
     */
    internal fun honorRenamed(name: String): String? {
        val stem = name.substringBeforeLast('.', "")
        if (stem.isEmpty()) return null
        val ext = name.substringAfterLast('.')
        val taken = TAKEN.find(stem)?.value.orEmpty()
        val m = HonorNames.WITH_NAME.matchEntire("${stem.removeSuffix(taken)}.$ext") ?: return null
        val (who, number, stamp) = m.destructured
        // A name the Phone app reads its number in is left as it is: "Dr.
        // Who" or "Mom (work)" compile as its pattern and match nothing, so
        // their names read; only one the app throws on, or reads another
        // number in, is renamed (HonorPhoneApp, its own code transcribed).
        if (HonorPhoneApp.number(name) == number) return null
        // Before 1.2 a caller with no name had the number written as the
        // name; honorStem writes that as no name.
        val renamed = "${honorStem(who, number, stamp)}$taken.$ext"
        return renamed.takeIf { it != name }
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

    /** A caller's name for Honor's format: [clean], and without the
     *  characters a regular expression or its replacement gives a meaning
     *  to, which Honor's Phone app would read it with (see [build]); null
     *  when nothing is left, for "<number>_<time>". */
    internal fun honorWho(name: String?): String? =
        clean(name)?.filterNot { it in REGEX_MEANT }?.replace(Regex("\\s+"), " ")?.trim()?.ifEmpty { null }

    private const val REGEX_MEANT = "\\^$.|?*+()[]{}"

    /** At most [longest] characters, never cutting an emoji's surrogate pair
     *  in two: half of one is not a character any file system will take, nor
     *  any tag reader (RecordingTags). */
    internal fun shorten(name: String, longest: Int = LONGEST): String {
        if (name.length <= longest) return name
        val end = if (Character.isHighSurrogate(name[longest - 1])) longest - 1 else longest
        return name.take(end)
    }

    /** A number with its country code, as the number format writes it
     *  (PhoneNumbers.international). */
    internal fun international(number: String?, country: String?): String? = PhoneNumbers.international(number, country)

    private fun dialled(number: String?): String? = PhoneNumbers.dialled(number)

    /** A number as it goes into a name (PhoneNumbers.digits). */
    internal fun digits(number: String?): String? = PhoneNumbers.digits(number)

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


    /** What a provider adds to a name that is taken (RecordingStore.Target.name). */
    private val TAKEN = Regex(""" \(\d+\)$""")

    /** Read a recording's name back, in any of the formats; null if it is not one. Pure. */
    internal fun parse(name: String): Parsed? {
        // A name its place had to change says the same of its call. Honor's
        // has its time last, so the addition is cut first.
        val stem = name.substringBeforeLast('.')
        val untaken = TAKEN.replace(stem, "") + name.removePrefix(stem)
        HonorNames.WITH_NAME.matchEntire(untaken)?.let { m ->
            val at = runCatching { honorStamp().parse(m.groupValues[3])?.time }.getOrNull() ?: return null
            return Parsed(at, null, m.groupValues[1].trim().ifEmpty { null }, m.groupValues[2])
        }
        HonorNames.NUMBER_ONLY.matchEntire(untaken)?.let { m ->
            val at = runCatching { honorStamp().parse(m.groupValues[2])?.time }.getOrNull() ?: return null
            return Parsed(at, null, null, m.groupValues[1])
        }
        // Exactly as the list always read it, so no name it read differently
        // before reads differently now.
        val at = runCatching {
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(name.take(15))?.time
        }.getOrNull() ?: return null
        // "_in_viber_Priya Raman" from "..._in_viber_Priya Raman.ogg": an
        // optional direction, an optional messenger, an optional caller.
        var rest = name.drop(15).substringBeforeLast('.').removePrefix("_")
        // After a caller the addition stays: it may be the caller's own.
        val cut = TAKEN.replace(rest, "")
        val incoming = when {
            cut == "in" || rest.startsWith("in_") -> true
            cut == "out" || rest.startsWith("out_") -> false
            else -> null
        }
        if (incoming != null) rest = rest.removePrefix(if (incoming) "in" else "out").removePrefix("_")
        val app = TAKEN.replace(rest, "").substringBefore('_').takeIf { it in VoipApps.TAGS }
        if (app != null) rest = rest.removePrefix(app).removePrefix("_")
        // A caller only where the name has said what it is; anything else
        // after the time is a name that is not one of ours.
        val caller = rest.takeUnless { TAKEN.matches(it) }?.trim()?.ifEmpty { null }?.takeIf { incoming != null || app != null }
        return Parsed(at, incoming, caller, app = app)
    }

    private fun honorStamp() = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { isLenient = false }

    // --- what the name does not carry: RecordingMarks' ---------------------

    fun noteDirection(context: Context, name: String, incoming: Boolean) = RecordingMarks.Directions.note(context, name, incoming)

    fun noteDirections(context: Context, directions: Map<String, Boolean>) = RecordingMarks.Directions.note(context, directions)

    fun noteCaller(context: Context, name: String, caller: String) = RecordingMarks.Callers.note(context, name, caller)

    fun noteCallers(context: Context, callers: Map<String, String>) = RecordingMarks.Callers.note(context, callers)

    fun callers(context: Context): Map<String, String> = RecordingMarks.Callers.all(context)

    fun directions(context: Context): Map<String, Boolean> = RecordingMarks.Directions.all(context)
}
