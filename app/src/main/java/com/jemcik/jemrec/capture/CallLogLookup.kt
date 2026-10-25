package com.jemcik.jemrec.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import com.jemcik.jemrec.Prefs

/**
 * Who each call was with, if the user has said we may look.
 *
 * WHY THIS IS OPTIONAL, AND STAYS OPTIONAL
 *
 * Nothing the app already holds can answer it. The call-state listener reports
 * that a call started and nothing else - no number, by design. The only source
 * is the call log, and reading it means READ_CALL_LOG: a dangerous permission,
 * on an app that records phone calls. That is worth a moment's thought from the
 * user rather than a line in a manifest they never see, so it is asked for at
 * the point it buys something and the app works perfectly well without it.
 *
 * The log caches a contact NAME, so a name alone would cost this one
 * permission. The photo, the favourite star and a name the log has not
 * cached live in the contacts provider, so READ_CONTACTS is asked for with it
 * - see PERMISSIONS for why the two are offered as one choice.
 *
 * MATCHED BY TIME, NOT STORED AT RECORD TIME
 *
 * The log entry is written when the call ENDS, which can be after the recording
 * has been closed - so reading it during the recording is a race. Matching
 * afterwards avoids that, costs one query per refresh, and has the useful side
 * effect of filling in recordings made before the permission was ever granted.
 *
 * The window is generous in one direction only. A log entry is dated when the
 * call began, which for an incoming call is when it started RINGING - possibly
 * a minute before the recording, which starts when it is answered. Ahead of the
 * recording it can only be seconds.
 */
object CallLogLookup {

    private const val TAG = "JemRec"
    private const val KEY_DISMISSED = "caller_banner_dismissed"

    /**
     * Two permissions, asked for together, for one feature.
     *
     * The call log carries the contact NAME, cached, so names cost only the
     * first. It does not carry the photo - it carries a POINTER to one, into
     * the contacts provider, and following that pointer needs READ_CONTACTS.
     * So a picture costs a second dangerous permission, which is why they are
     * offered as one choice rather than smuggled in one at a time.
     */
    val PERMISSIONS = arrayOf(
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS,
    )

    /** Ringing can be long. Being answered cannot happen before dialling. */
    internal const val BEFORE_MS = 3 * 60 * 1000L
    internal const val AFTER_MS = 30 * 1000L

    /** Whether a call the log dates at [callAt] can be the one a recording
     *  started at [recordingAt] belongs to. The same window labels the list. */
    internal fun sameCall(callAt: Long, recordingAt: Long): Boolean =
        callAt in (recordingAt - BEFORE_MS)..(recordingAt + AFTER_MS)

    /** Names work on the call log alone. */
    fun granted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    /** Photos need the contacts provider the log only points at. */
    fun photosGranted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    fun anythingLeftToOffer(context: Context): Boolean =
        !granted(context) || !photosGranted(context)

    fun bannerDismissed(context: Context): Boolean =
        Prefs.of(context)
            .getBoolean(KEY_DISMISSED, false)

    fun dismissBanner(context: Context) {
        Prefs.of(context)
            .edit { putBoolean(KEY_DISMISSED, true) }
    }

    internal class Entry(
        val at: Long,
        val incoming: Boolean,
        val label: String,
        val number: String?,
        /** How long the call lasted; 0 for one nobody answered. */
        val seconds: Int = 0,
        /** The name the log cached for it, if any. */
        val name: String? = null,
    )

    /** What is known about the other end of a call. */
    data class Caller(
        val label: String,
        val photo: Uri?,
        val number: String? = null,
        /** The contact this call was with is starred in the address book. */
        val favorite: Boolean = false,
        /** Which way the call went, as the log has it - for a recording whose
         *  name does not say (see FileNames.parse). */
        val incoming: Boolean? = null,
    )

    /** What the contacts provider knows about a number: a name, a photo, and
     *  whether the contact is a favourite. Looked up together, cached per number.
     *  The name matters because the call log does not always cache one even for a
     *  saved contact, and a bare number under a photo reads as a stranger. */
    internal data class Contact(
        val name: String?,
        val photo: Uri?,
        val starred: Boolean,
        /** The contact's id, for the rules on whose calls to keep (CallRules). */
        val id: Long? = null,
        /** The lookup itself failed, so "not in the contacts" is not known -
         *  which the rules must not take for a stranger. */
        val failed: Boolean = false,
        /** Every contact with this number - a shared landline, or one person
         *  saved twice. The rules keep a call if they keep any of them. */
        val ids: List<Long> = listOfNotNull(id),
    )

    /**
     * A caller label per recording name, for the ones that can be matched.
     *
     * One query for the whole list rather than one per recording: the log is a
     * content provider, and forty cursors to answer forty rows would be forty
     * times the work for the same answer.
     */
    fun labelsFor(context: Context, recordings: List<Recording>): Map<String, Caller> {
        if (recordings.isEmpty() || !granted(context)) return emptyMap()

        // The Recording already knows when it was and which way it went; this
        // used to parse the name again for both, with its own copy of the rules.
        // Not a messenger call: it is never in the phone's call log, and a
        // phone call at about the same time is somebody else.
        val times = recordings.filter { it.parsedName?.app == null }.mapNotNull { r ->
            r.startedAtMillis?.let { at -> Triple(r, at, r.incoming) }
        }
        if (times.isEmpty()) return emptyMap()

        val from = times.minOf { it.second } - BEFORE_MS
        val to = times.maxOf { it.second } + AFTER_MS

        val entries = runCatching { query(context, from, to) }
            .onFailure { Log.w(TAG, "call log: could not read", it) }
            .getOrDefault(emptyList())
        if (entries.isEmpty()) return emptyMap()

        val out = mutableMapOf<String, Caller>()
        // One lookup per distinct number rather than per recording: several
        // calls with the same person is the normal case, not the exception.
        val contacts = mutableMapOf<String, Contact>()
        times.forEach { (recording, at, incoming) ->
            val name = recording.name
            // A name with the number in it (Honor's format) is matched on that
            // number, whichever way the call went - its name does not say.
            val number = recording.parsedName?.number
            val match = if (number != null) withNumber(entries, number, at) else closest(entries, at, incoming)
            if (match != null) {
                val contact = contactFor(context, match.number, contacts)
                out[name] = Caller(
                    // The live contact name wins over the call log's, which may
                    // be a stale cache or just a number.
                    contact.name ?: match.label,
                    contact.photo,
                    match.number,
                    contact.starred,
                    match.incoming,
                )
            }
        }
        return out
    }

    private fun query(context: Context, from: Long, to: Long): List<Entry> {
        val projection = arrayOf(
            CallLog.Calls.DATE,
            CallLog.Calls.TYPE,
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.DURATION,
        )
        val out = mutableListOf<Entry>()
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            // Never a call that did not go off-hook here: a recording cannot
            // be one. Every type but INCOMING used to count as outgoing, so a
            // missed call just before a call-back could name the call-back.
            // The types are listed out rather than kept in, so a vendor's own
            // type for an answered call is not lost.
            "${CallLog.Calls.DATE} >= ? AND ${CallLog.Calls.DATE} <= ? AND " +
                "${CallLog.Calls.TYPE} NOT IN (?, ?, ?, ?, ?)",
            arrayOf(
                from.toString(), to.toString(),
                CallLog.Calls.MISSED_TYPE.toString(),
                CallLog.Calls.VOICEMAIL_TYPE.toString(),
                CallLog.Calls.REJECTED_TYPE.toString(),
                CallLog.Calls.BLOCKED_TYPE.toString(),
                CallLog.Calls.ANSWERED_EXTERNALLY_TYPE.toString(),
            ),
            "${CallLog.Calls.DATE} DESC",
        )?.use { cursor ->
            val dateCol = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val typeCol = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            val numberCol = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val nameCol = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
            val durationCol = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
            while (cursor.moveToNext()) {
                val type = cursor.getInt(typeCol)
                val name = cursor.getString(nameCol)?.takeIf { it.isNotBlank() }
                val number = cursor.getString(numberCol)?.takeIf { it.isNotBlank() }
                // A name is what a person recognises; the number is the
                // fallback. Neither means the entry is useless - a withheld
                // number is still worth saying so.
                val label = name ?: number ?: "Unknown number"
                out += Entry(
                    at = cursor.getLong(dateCol),
                    incoming = type == CallLog.Calls.INCOMING_TYPE,
                    label = label,
                    number = number,
                    seconds = cursor.getInt(durationCol),
                    name = name,
                )
            }
        }
        return out
    }

    /**
     * When each call since a moment began, for the self-test's check that the
     * daemon heard them (see SelfTest.unheardCall).
     *
     * Only answered incoming calls and outgoing ones: the two kinds that
     * certainly went off-hook on this phone, so the daemon's call watch cannot
     * have missed them and still be working. A missed or rejected call usually
     * rings, but call screening can turn one away before it ever does, and a
     * check that cries wolf teaches people to ignore it.
     */
    internal fun callStartsSince(context: Context, since: Long): List<Long> {
        val out = mutableListOf<Long>()
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.DATE),
            "${CallLog.Calls.DATE} >= ? AND ${CallLog.Calls.TYPE} IN (?, ?)",
            arrayOf(
                since.toString(),
                CallLog.Calls.INCOMING_TYPE.toString(),
                CallLog.Calls.OUTGOING_TYPE.toString(),
            ),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) out += cursor.getLong(0)
        }
        return out
    }

    /**
     * The contact's photo, looked up by number.
     *
     * NOT taken from the call log's cached_photo_uri, which is where it ought
     * to be and where the first version looked. On this phone that column is
     * never written: twenty-three calls over a week, every one with a null
     * photo and a zero photo id, while the cached NAME was filled in for all of
     * them. The dialer caches half of what the schema offers, and nothing
     * requires it to cache the other half.
     *
     * PhoneLookup does not depend on anyone having cached anything. It asks the
     * contacts provider directly, matching however that provider normalises
     * numbers - which is also better than comparing strings, since the same
     * person appears in a call log with and without a country code.
     */
    internal fun contactFor(
        context: Context,
        number: String?,
        cache: MutableMap<String, Contact>,
    ): Contact {
        if (number.isNullOrBlank() || !photosGranted(context)) return Contact(null, null, false)
        return cache.getOrPut(number) {
            runCatching {
                val uri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(number),
                )
                context.contentResolver.query(
                    uri,
                    arrayOf(
                        ContactsContract.PhoneLookup.DISPLAY_NAME,
                        ContactsContract.PhoneLookup.PHOTO_URI,
                        // STARRED is the address book's own "favourite" flag, so
                        // the list can mark a call as being with a favourite
                        // contact - the same star the dialer shows.
                        ContactsContract.PhoneLookup.STARRED,
                        ContactsContract.PhoneLookup._ID,
                    ),
                    null, null, null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val name = cursor.getString(0)?.takeIf { it.isNotBlank() }
                        val photo = cursor.getString(1)?.takeIf { it.isNotBlank() }?.toUri()
                        val starred = cursor.getInt(2) != 0
                        val first = cursor.getLong(3)
                        // Every contact with the number, not the first the
                        // provider happens to return: the rules on whose calls
                        // to keep may name any of them.
                        val ids = mutableListOf(first)
                        while (cursor.moveToNext()) ids += cursor.getLong(3)
                        Contact(name, photo, starred, first, ids = ids.distinct())
                    } else {
                        Contact(null, null, false)
                    }
                }
                    // No cursor is the provider not answering, which is not
                    // "no such contact": the rules must not take it for a
                    // stranger and leave a contact's call out.
                    ?: Contact(null, null, false, failed = true)
            }.onFailure { Log.w(TAG, "contacts: lookup failed", it) }
                .getOrDefault(Contact(null, null, false, failed = true))
        }
    }

    /**
     * Closest entry of the same direction inside the window. Direction matters:
     * two calls a minute apart are common, and one of them being the wrong way
     * round is the obvious way to label a recording with the wrong person.
     * The window is BEFORE_MS back and AFTER_MS forward of the recording's
     * start - see those for why it is lopsided.
     */
    internal fun closest(entries: List<Entry>, at: Long, incoming: Boolean): Entry? =
        entries
            .filter { it.incoming == incoming && sameCall(it.at, at) }
            .minByOrNull { kotlin.math.abs(it.at - at) }

    /**
     * The call with [number] that a recording starting at [at] fell inside:
     * from the log's date to its end, the way Honor's Phone app matches its
     * own recordings (see FileNames.build). Compared digit for digit, as
     * FileNames.digits wrote the number into the name.
     */
    internal fun withNumber(entries: List<Entry>, number: String, at: Long): Entry? {
        val wanted = FileNames.digits(number) ?: return null
        return entries
            .filter { FileNames.digits(it.number) == wanted }
            .filter { at in (it.at - AFTER_MS)..(it.at + it.seconds * 1000L + AFTER_MS) }
            .minByOrNull { kotlin.math.abs(it.at - at) }
    }

    /**
     * The log's entry for a call whose recording began at [startMillis], for
     * the saver to name it and apply the rules by (see RecordingSaver). Null
     * if the log does not have it - yet: it is written as the call ends, so
     * the saver asks again for a few seconds. [others] are the starts of the
     * recordings already made (CallLedger), whose calls this cannot be.
     * Blocking.
     */
    /**
     * Whether two ways of writing a number are the same number - Telecom's
     * "+13034997111" and the call log's "3034997111", say. Loosely, the way
     * Android matches numbers to contacts: the digits alone, and the last
     * seven of them when both have that many. Pure.
     */
    internal fun sameNumber(a: String?, b: String?): Boolean {
        val x = a?.filter { it.isDigit() }.orEmpty()
        val y = b?.filter { it.isDigit() }.orEmpty()
        if (x.isEmpty() || y.isEmpty()) return false
        if (x.length < MIN_MATCH || y.length < MIN_MATCH) return x == y
        return x.takeLast(MIN_MATCH) == y.takeLast(MIN_MATCH)
    }

    /** Android's own loose match for numbers looks at the last seven digits. */
    private const val MIN_MATCH = 7

    /** Every incoming and outgoing call the log dates from [from] to [to]. */
    internal fun entriesBetween(context: Context, from: Long, to: Long): List<Entry> {
        if (!granted(context)) return emptyList()
        return runCatching { query(context, from, to) }
            .onFailure { Log.w(TAG, "call log: could not read", it) }
            .getOrDefault(emptyList())
    }

    internal fun callFor(
        context: Context,
        startMillis: Long,
        incoming: Boolean,
        others: List<CallLedger.Recorded>,
    ): Entry? {
        if (!granted(context)) return null
        // Back twice the window: the calls the other recordings claim can be
        // a window before them.
        val entries = runCatching { query(context, startMillis - 2 * BEFORE_MS, startMillis + AFTER_MS) }
            .onFailure { Log.w(TAG, "call log: could not read", it) }
            .getOrDefault(emptyList())
        return unclaimed(entries, startMillis, incoming, others)
    }

    /**
     * Closest entry for a recording at [at], leaving out the entries that are
     * other recordings' calls. Pure.
     *
     * Asked right after a hang-up, the log may not have this call yet, and
     * then the closest entry inside the window is the call BEFORE it - up to
     * three minutes earlier. Naming this call after that one, or keeping or
     * deleting it by that one's rule, is the mistake: so an entry another
     * recording's start matches better is not this call's, and with nothing
     * else in the window the answer is "not yet".
     */
    internal fun unclaimed(
        entries: List<Entry>,
        at: Long,
        incoming: Boolean,
        others: List<CallLedger.Recorded>,
    ): Entry? {
        val claimed = others
            .filter { it.startMillis != at }
            .mapNotNullTo(HashSet()) { closest(entries, it.startMillis, it.incoming) }
        return closest(entries.filterNot { it in claimed }, at, incoming)
    }

    /** A call as the missed-call check needs it. */
    internal data class LoggedCall(
        val at: Long,
        val seconds: Int,
        val incoming: Boolean,
        val label: String,
    )

    /**
     * Every answered incoming and every outgoing call since [since], with how
     * long it lasted - the calls that went off-hook here and so had to be
     * recorded, if recording was on. The same two kinds callStartsSince asks
     * for, for the same reason.
     */
    internal fun answeredCallsSince(context: Context, since: Long): List<LoggedCall> {
        val out = mutableListOf<LoggedCall>()
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
                CallLog.Calls.TYPE,
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
            ),
            "${CallLog.Calls.DATE} >= ? AND ${CallLog.Calls.TYPE} IN (?, ?)",
            arrayOf(
                since.toString(),
                CallLog.Calls.INCOMING_TYPE.toString(),
                CallLog.Calls.OUTGOING_TYPE.toString(),
            ),
            "${CallLog.Calls.DATE} ASC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(4)?.takeIf { it.isNotBlank() }
                val number = cursor.getString(3)?.takeIf { it.isNotBlank() }
                out += LoggedCall(
                    at = cursor.getLong(0),
                    seconds = cursor.getInt(1),
                    incoming = cursor.getInt(2) == CallLog.Calls.INCOMING_TYPE,
                    label = name ?: number ?: "Unknown number",
                )
            }
        }
        return out
    }
}
