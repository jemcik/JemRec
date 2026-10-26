package com.jemcik.jemrec.capture

import android.annotation.SuppressLint
import android.app.Notification
import android.app.Person
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import com.jemcik.jemrec.Prefs

/**
 * Who a messenger call was with, and which way it went, from the messenger's
 * own call notification - the only place Android says either. A messenger
 * call is not in the phone's call log, so without this its recording is
 * "Viber call" and nothing more.
 *
 * Only if the user turns it on ("Show who called" under Messenger calls),
 * because it needs notification access - the same access JemRec asks for to
 * hide Android's debugging notification, granted the same way, over its own
 * ADB session. With it, Android shows this app every notification; this reads
 * only the call notifications of the messengers whose calls are recorded, and
 * keeps of them only the name, the time and the direction, for the last
 * [KEEP] calls.
 *
 * WHAT A CALL NOTIFICATION SAYS
 *
 *   - Android 12's call style names the person outright (EXTRA_CALL_PERSON)
 *     and the kind of call (EXTRA_CALL_TYPE: incoming, ongoing, screening).
 *   - Not every app marks its call notification as one. Telegram's, during
 *     an outgoing call, has no call category and no call style: it is the
 *     ongoing notification of the foreground service that runs the call
 *     (measured, 2026-10-20). So that counts too - but never a media
 *     player's, which looks the same and is Telegram's voice messages.
 *   - The caller is the title, or - when the title names the app, as
 *     Telegram's "Ongoing Telegram call" does - the text. Viber's title is
 *     the caller; Telegram's text is (both measured).
 *   - A ringing call asks for the full screen; a call being placed does not.
 *     That, or the call style's type, is the direction - the type first:
 *     Google Meet's in-call notification asks for the full screen too, in
 *     calls both ways and in a meeting, and says it is ongoing (measured,
 *     2026-10-26). Its ring is a call style with the person, as WhatsApp's.
 */
internal object VoipCallNotes {

    private const val TAG = "JemRec"
    private const val KEY_ON = "voip_names"
    private const val KEY_NOTES = "voip_call_notes"
    private const val KEEP = 60

    /** How far before a recording's start its call's notifications can be:
     *  the ring of an incoming call, the dialling of an outgoing one. */
    private const val BEFORE_MS = 3 * 60 * 1000L
    private const val AFTER_MS = 2 * 60 * 1000L
    /** How long before a call starts its ringing can be: a ring earlier than
     *  this was a different call's. */
    private const val RING_MS = 90 * 1000L
    /**
     * An unchanged repeat of a note within this long adds nothing. Short:
     * it is there for the re-posts of one call's notification, and ten
     * minutes, which this was, swallowed the next call with the same person
     * whole - it was saved with no name and no direction (measured).
     */
    private const val SAME_NOTE_MS = 2 * 60 * 1000L

    data class Note(val pkg: String, val at: Long, val name: String?, val incoming: Boolean?)

    fun enabled(context: Context): Boolean = Prefs.of(context).getBoolean(KEY_ON, false)

    fun setEnabled(context: Context, on: Boolean) {
        Prefs.of(context).edit {
            putBoolean(KEY_ON, on)
            if (!on) remove(KEY_NOTES)
        }
    }

    /** From the listener: a notification was posted. Kept only if it is a
     *  call notification of a messenger whose calls are recorded. */
    fun observe(context: Context, pkg: String, postTime: Long, notification: Notification) {
        if (!enabled(context) || pkg !in VoipApps.enabled(context)) return
        val extras = notification.extras ?: return
        if (extras.getString(Notification.EXTRA_TEMPLATE)?.endsWith("MediaStyle") == true) return
        // Public from Android 14, but the key is older: Android 12's CallStyle
        // already writes "android.callType" (a hidden constant then - AOSP
        // android12-release, Notification.CallStyle.addExtras), and the
        // constant is compiled in as that string. So read on every version.
        @SuppressLint("InlinedApi")
        val callType = extras.getInt(Notification.EXTRA_CALL_TYPE, 0)
        val runningCall = notification.flags and ONGOING_SERVICE == ONGOING_SERVICE
        val isCall = notification.category == Notification.CATEGORY_CALL ||
            callType != 0 || extras.containsKey(Notification.EXTRA_CALL_PERSON) ||
            notification.fullScreenIntent != null || runningCall
        if (!isCall) return
        val person = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)
            } else {
                @Suppress("DEPRECATION")
                extras.getParcelable<Person>(Notification.EXTRA_CALL_PERSON)
            }
        }.getOrNull()
        val label = VoipApps.byPackage(pkg)?.label
        val name = clean(person?.name?.toString(), label)
            ?: clean(extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(), label)
            ?: clean(extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(), label)
        val incoming = when (callType) {
            Notification.CallStyle.CALL_TYPE_INCOMING, Notification.CallStyle.CALL_TYPE_SCREENING -> true
            Notification.CallStyle.CALL_TYPE_ONGOING -> null
            else -> if (notification.fullScreenIntent != null) true else null
        }
        // What kind of notification, never what it says: a caller's name does
        // not belong in the log.
        Log.i(TAG, "voip-notes: $pkg call notification, type=$callType fullScreen=" +
            "${notification.fullScreenIntent != null} name=${if (name != null) "yes" else "no"}")
        remember(context, Note(pkg, postTime, name, incoming))
    }

    /** A name, unless it is empty or names the app - "Viber", "Ongoing
     *  Telegram call" - which is the app talking about itself. */
    internal fun clean(text: String?, appLabel: String?): String? {
        val t = text?.trim()?.ifEmpty { null } ?: return null
        return if (appLabel != null && t.contains(appLabel, ignoreCase = true)) null else t
    }

    /** The flags of the notification a calling app keeps up for the call. */
    private const val ONGOING_SERVICE = Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE

    /**
     * The call that a recording starting at [start] came from: the name its
     * notifications gave (the one nearest the start), and incoming if any of
     * them rang before it began - or as [incoming] says, where the recorder
     * knew. Null if there were no notes and no direction.
     */
    fun find(context: Context, pkg: String, start: Long, incoming: Boolean? = null): Note? =
        pick(read(context), pkg, start, incoming)

    /** Whether [note] only repeats its app's latest note. Pure. */
    internal fun isRepeat(existing: List<Note>, note: Note): Boolean {
        val last = existing.filter { it.pkg == note.pkg }.maxByOrNull { it.at } ?: return false
        return last.name == note.name && last.incoming == note.incoming && note.at - last.at in 0..SAME_NOTE_MS
    }

    /** Pure, for the tests. [incoming]: which way, where the recorder knew. */
    internal fun pick(notes: List<Note>, pkg: String, start: Long, incoming: Boolean? = null): Note? {
        val near = notes.filter { it.pkg == pkg && it.at in (start - BEFORE_MS)..(start + AFTER_MS) }
        if (near.isEmpty()) return incoming?.let { Note(pkg, start, null, it) }
        val ring = near.filter { it.incoming == true && it.at in (start - RING_MS)..start }.maxByOrNull { it.at }
        // The call's own notification, from its ring on: the name it gives
        // is this call's.
        val since = ring?.at ?: (start - BEFORE_MS)
        val callName = near.filter { it.incoming != true && it.name != null && it.at >= since }
            .minByOrNull { kotlin.math.abs(it.at - start) }?.name
        // A ring is this call's - unless the call's own notification names
        // someone else: a ring from one person, missed, then a call placed to
        // another within the minute and a half, is the user's own call. Unless
        // the recorder knew: Google Meet names one caller two ways, its ring
        // by the person's name and its in-call notification by their account
        // (the Honor, 2026-10-26), and every incoming Meet call came out
        // outgoing.
        val rang = incoming ?: (ring != null && (callName == null || ring.name == null || ring.name == callName))
        // Where the recorder knew the call rang, the ring's name is the
        // caller's - the person's name, which the phone showed as it rang,
        // where Meet's in-call notification has their account.
        val name = ring?.name?.takeIf { incoming == true }
            ?: callName
            ?: ring?.name?.takeIf { rang }
            ?: near.filter { it.name != null && it.incoming != true }.minByOrNull { kotlin.math.abs(it.at - start) }?.name
        // A call with notifications and no ring before it was placed by the user.
        return Note(pkg, start, name, rang)
    }

    /**
     * An app updates its call notification through the call - Telegram's and
     * Viber's are re-posted as it goes - and every update kept would push the
     * ring that says the call was incoming out of the last [KEEP], and write
     * the preferences each time. So a note that says nothing new - same app,
     * same name, same direction as its latest, not long after - is not kept.
     */
    @Synchronized
    private fun remember(context: Context, note: Note) {
        val existing = read(context)
        if (isRepeat(existing, note)) return
        val notes = (existing + note).sortedBy { it.at }.takeLast(KEEP)
        Prefs.of(context).edit { putStringSet(KEY_NOTES, notes.map(::encode).toSet()) }
    }

    private fun read(context: Context): List<Note> =
        (Prefs.of(context).getStringSet(KEY_NOTES, emptySet()) ?: emptySet()).mapNotNull(::decode)

    private const val SEP = '\u0000'

    internal fun encode(n: Note): String =
        listOf(n.pkg, n.at.toString(), n.incoming?.toString() ?: "", n.name ?: "").joinToString(SEP.toString())

    internal fun decode(s: String): Note? {
        val p = s.split(SEP)
        if (p.size != 4) return null
        val at = p[1].toLongOrNull() ?: return null
        return Note(p[0], at, p[3].ifEmpty { null }, p[2].ifEmpty { null }?.toBooleanStrictOrNull())
    }
}
