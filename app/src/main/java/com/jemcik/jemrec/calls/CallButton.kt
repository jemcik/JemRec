package com.jemcik.jemrec.calls

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit
import com.jemcik.jemrec.Prefs
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * Ask first's Record button over the call's screen: off until switched on in
 * Settings, under Record every call.
 *
 * WHAT IT IS FOR. The question is a notification (RecordPrompt), and how long
 * a notification stays in front is the phone's: until it is answered on stock
 * Android, with a full-screen intent behind it - but a user on One UI 8.5
 * (Galaxy A17, 2026-11-24) found it "too small" and gone too fast, and hard to
 * get back to. A window of JemRec's own is nobody's banner: it stays until it
 * is answered or the call ends. ShizuCallRecorder came to the same (its #68,
 * from a Xiaomi), and Cube ACR has it.
 *
 * WHAT IT COSTS. "Display over other apps" (SYSTEM_ALERT_WINDOW), the
 * permission tapjacking needs. JemRec had decided against it once, for the
 * pairing code, where Android hides such windows over Settings anyway; here it
 * is a switch, off until the user wants the button, and nothing is drawn but
 * the button, while Ask first waits for an answer. The recorder allows it (the
 * daemon's Overlay), or Android's own page does.
 *
 * Measured on the Android 12, 16 and 17 emulators (2026-11-24, a throwaway
 * app): over the call's screen it shows and takes taps, unlocked and locked
 * with a PIN; on the lock screen itself Android hides it (DisplayPolicy,
 * shouldBeHiddenByKeyguard).
 */
object CallButton {

    private const val TAG = "JemRec"
    private const val KEY_ON = "call_button"
    private const val KEY_PLACE = "call_button_place"

    /** Where it starts: a third of the way down. Mid-height covered the
     *  Speaker key of Android 12's phone app (2026-11-24, the emulator); the
     *  caller's name and the call's time are above this, its keys below. */
    private const val FIRST_PLACE = 1f / 3

    /** Switched on in Settings - which does not say Android lets it show. */
    fun enabled(context: Context): Boolean = Prefs.of(context).getBoolean(KEY_ON, false)

    fun setEnabled(context: Context, on: Boolean) = Prefs.of(context).edit { putBoolean(KEY_ON, on) }

    /** Calls [changed] as it is switched on or off; returns the way to stop. */
    fun watch(context: Context, changed: () -> Unit): () -> Unit = Prefs.watch(context, KEY_ON, changed)

    /** Android's "Display over other apps", allowed for JemRec. A question to
     *  its app-ops service: not for the main thread but at a call. */
    fun allowed(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** How often what [allowed] says may have changed by JemRec's own doing -
     *  a flip, the recorder's answer to one. A reading asks Android at one
     *  time and lands at another, seconds later with the recorder down: what
     *  it found before a change is not shown after it (Reading). */
    internal val changes = AtomicInteger()

    /** Switched on, and allowed to show. */
    fun works(context: Context): Boolean = enabled(context) && allowed(context)

    /** Start fresh: as never switched on. */
    fun forget(context: Context) = Prefs.of(context).edit {
        remove(KEY_ON)
        remove(KEY_PLACE)
    }

    /** Android's own page for it: from Android 11 a list of every app,
     *  whichever one the intent names (developer.android.com, "Permissions
     *  updates in Android 11"). False if the phone has none. */
    fun openSettings(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure { Log.w(TAG, "button: no page to allow it on - ${it.javaClass.simpleName}") }.isSuccess

    // --- what it shows ------------------------------------------------------

    /** What the button shows; null for nothing. */
    enum class Face {
        /** Record, and a cross that is Not now. */
        ASK,

        /** Record said yes to, and the recorder not heard from yet. */
        STARTING,

        /** The recorder has answered: recording. Shown for [RECORDING_MS]. */
        RECORDING,
    }

    /** What happens to the call it stands over. */
    enum class Event {
        /** Ask first asks. */
        ASKED,

        /** Record, from anywhere: the button, the question, the call's own
         *  notification. */
        RECORD,

        /** The recorder records the call. */
        TAKEN,

        /** Record did not take. */
        FAILED,

        /** [Face.RECORDING] has been shown long enough. */
        SEEN,

        /** Nothing to ask any more: Not now, or the call over. */
        OVER,
    }

    /** How long [Face.RECORDING] is shown: to be seen, then out of the way. */
    internal const val RECORDING_MS = 3_000L

    /**
     * What it shows after [event]. A second call's question, over a first
     * whose Record is still on its way, is not changed by the first's answer:
     * an answer is for the face that waits for it.
     */
    internal fun next(face: Face?, event: Event): Face? = when (event) {
        Event.ASKED -> Face.ASK
        Event.RECORD -> if (face == Face.ASK) Face.STARTING else face
        Event.TAKEN -> if (face == Face.STARTING) Face.RECORDING else face
        Event.FAILED -> if (face == Face.STARTING) null else face
        Event.SEEN -> if (face == Face.RECORDING) null else face
        Event.OVER -> null
    }

    // --- where it is --------------------------------------------------------

    /** Where the user dragged it to: how far down the room it has, 0 to 1. */
    fun place(context: Context): Float = Prefs.of(context).getFloat(KEY_PLACE, FIRST_PLACE).coerceIn(0f, 1f)

    fun setPlace(context: Context, place: Float) = Prefs.of(context).edit { putFloat(KEY_PLACE, place.coerceIn(0f, 1f)) }

    /** The top of a button [height] tall in [room], at [place]: a share of
     *  what it can move through, so it stays on the screen however that
     *  turns, folds or splits. */
    internal fun top(place: Float, room: Int, height: Int): Int =
        (place * (room - height).coerceAtLeast(0)).roundToInt()

    /** The other way: where a button with its top at [top] is, kept inside. */
    internal fun placeOf(top: Int, room: Int, height: Int): Float {
        val travel = room - height
        return if (travel <= 0) 0f else (top.toFloat() / travel).coerceIn(0f, 1f)
    }
}
