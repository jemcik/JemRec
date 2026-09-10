package com.jemcik.jemrec.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.util.Log
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
    private const val BEFORE_MS = 3 * 60 * 1000L
    private const val AFTER_MS = 30 * 1000L

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
            .edit().putBoolean(KEY_DISMISSED, true).apply()
    }

    internal class Entry(
        val at: Long,
        val incoming: Boolean,
        val label: String,
        val number: String?,
    )

    /** What is known about the other end of a call. */
    data class Caller(
        val label: String,
        val photo: Uri?,
        val number: String? = null,
        /** The contact this call was with is starred in the address book. */
        val favorite: Boolean = false,
    )

    /** What the contacts provider knows about a number: a name, a photo, and
     *  whether the contact is a favourite. Looked up together, cached per number.
     *  The name matters because the call log does not always cache one even for a
     *  saved contact, and a bare number under a photo reads as a stranger. */
    private data class Contact(val name: String?, val photo: Uri?, val starred: Boolean)

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
        val times = recordings.mapNotNull { r ->
            r.startedAtMillis?.let { at -> Triple(r.name, at, r.incoming) }
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
        times.forEach { (name, at, incoming) ->
            val match = closest(entries, at, incoming)
            if (match != null) {
                val contact = contactFor(context, match.number, contacts)
                out[name] = Caller(
                    // The live contact name wins over the call log's, which may
                    // be a stale cache or just a number.
                    contact.name ?: match.label,
                    contact.photo,
                    match.number,
                    contact.starred,
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
        )
        val out = mutableListOf<Entry>()
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            "${CallLog.Calls.DATE} >= ? AND ${CallLog.Calls.DATE} <= ?",
            arrayOf(from.toString(), to.toString()),
            "${CallLog.Calls.DATE} DESC",
        )?.use { cursor ->
            val dateCol = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val typeCol = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            val numberCol = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val nameCol = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
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
                )
            }
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
    private fun contactFor(
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
                    ),
                    null, null, null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val name = cursor.getString(0)?.takeIf { it.isNotBlank() }
                        val photo = cursor.getString(1)?.takeIf { it.isNotBlank() }?.let(Uri::parse)
                        val starred = cursor.getInt(2) != 0
                        Contact(name, photo, starred)
                    } else {
                        Contact(null, null, false)
                    }
                } ?: Contact(null, null, false)
            }.onFailure { Log.w(TAG, "contacts: lookup failed", it) }.getOrDefault(Contact(null, null, false))
        }
    }

    /** Recordings are named yyyyMMdd_HHmmss_in|out.ogg, which is the only
     *  timestamp available for one made before this feature existed. */
    /**
     * Closest entry of the same direction inside the window. Direction matters:
     * two calls a minute apart are common, and one of them being the wrong way
     * round is the obvious way to label a recording with the wrong person.
     * The window is BEFORE_MS back and AFTER_MS forward of the recording's
     * start - see those for why it is lopsided.
     */
    internal fun closest(entries: List<Entry>, at: Long, incoming: Boolean): Entry? =
        entries
            .filter { it.incoming == incoming && it.at in (at - BEFORE_MS)..(at + AFTER_MS) }
            .minByOrNull { kotlin.math.abs(it.at - at) }
}
