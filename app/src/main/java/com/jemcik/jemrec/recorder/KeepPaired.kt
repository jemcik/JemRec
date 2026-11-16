package com.jemcik.jemrec.recorder

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.system.GlobalSettings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keep JemRec paired: Android's pairing timeout turned off at the user's
 * word - setup's question (SetupStep.NEEDS_KEEP_PAIRED_CHOICE), then the
 * switch in Settings - and put back when they take it back, and at Start
 * fresh.
 *
 * Android forgets a debugging pairing 7 days after it was made, however
 * often JemRec connects since: a Wireless debugging connection never renews
 * it (AdbDebuggingManager, read 2026-11-15). The recorder runs on; its next
 * start is refused, and JemRec has to be paired again. On the Honor,
 * 2026-11-15, every connection after a 19:44 unplug was refused and Android
 * no longer listed JemRec's key (when it went was not seen: the log had
 * rolled over); on the Android 17 emulator, with the time cut to two
 * minutes, JemRec's pairing went a second after JemRec had connected, and
 * with the timeout off it stayed. Off is what Developer options' "Disable
 * adb authorization timeout" writes, where a phone shows it (the Honor's
 * Settings has no such switch, read from its resources 2026-11-15), and it
 * keeps a computer's approval too.
 *
 * The recorder writes it, as the shell, and keeps what it was before, so it
 * can be put back after an uninstall (the recorder's PairingTimeout and
 * Custodian): the app keeps nothing the recorder can read once it is gone.
 * So a choice made with no recorder answering - setup asks before pairing -
 * waits, and goes to the next recorder found up (DaemonClient.pushMode).
 *
 * What the switch shows is Android's setting as read: turned back on by
 * hand in Developer options, it shows off, and nothing here turns it off
 * again.
 */
internal object KeepPaired {

    private const val TAG = "JemRec"

    /** Android's pairing timeout (GlobalSettings, which reads it). */
    const val SETTING = GlobalSettings.ADB_ALLOWED_CONNECTION_TIME

    /** What it is where nobody set it. */
    const val DEFAULT_MS = GlobalSettings.DEFAULT_PAIRING_TIMEOUT_MS

    private const val DAY_MS = 86_400_000L

    /** Setup's question answered, either way. */
    private const val KEY_ASKED = "keep_paired_asked"

    /** A choice no recorder has carried out yet: true to keep, false to put
     *  back. */
    private const val KEY_WISH = "keep_paired_wish"

    /** The recorder said it could not carry out the choice waiting, at its
     *  last try: said under the switch until a try that works, or a new
     *  choice. */
    private const val KEY_REFUSED = "keep_paired_refused"

    /** One send at a time: the screen's choice and a refresh's (pushMode)
     *  each read what waits and send it, and an older one sent after a newer
     *  would leave Android's setting against the switch. */
    private val sending = Mutex()

    /** The recorder's part (DaemonClient.keepPaired); the tests' answers
     *  instead. */
    internal var recorder: suspend (Context, Boolean) -> Boolean? = { context, keep ->
        DaemonClient.keepPaired(context, keep)
    }

    /** Android's pairing timeout as it is now, in milliseconds; 0 for none. */
    fun timeoutMs(context: Context): Long = GlobalSettings.pairingTimeoutMs(context)

    /** No pairing is forgotten: the timeout is off. Only 0 is off to Android;
     *  any other value forgets, a negative one at once. */
    fun kept(context: Context): Boolean = timeoutMs(context) == 0L

    /** [ms] in whole days, rounded up and at least one, for the words. */
    fun days(ms: Long): Int {
        if (ms <= 0) return 1
        val days = ms / DAY_MS + if (ms % DAY_MS != 0L) 1 else 0
        return days.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /** The recorder refused the choice waiting (KEY_REFUSED). */
    fun refused(context: Context): Boolean = Prefs.of(context).getBoolean(KEY_REFUSED, false)

    /** The choice waiting for a recorder, or null. */
    fun waiting(context: Context): Boolean? = Prefs.of(context).let {
        if (it.contains(KEY_WISH)) it.getBoolean(KEY_WISH, false) else null
    }

    /**
     * Setup's question is due: not answered yet, and the timeout is on - or
     * off only because JemRec turned it off before a Start fresh whose put
     * back is still waiting. Off by the user's own hand, there is nothing to
     * ask.
     */
    fun needsChoice(context: Context): Boolean =
        !Prefs.of(context).getBoolean(KEY_ASKED, false) && (!kept(context) || waiting(context) == false)

    /** What became of a choice. */
    enum class Outcome {
        /** Carried out, or nothing waited. */
        DONE,

        /** No recorder answered - none up, or one before 1.1 - so it waits
         *  for the next one found up. */
        WAITING,

        /** The recorder could not change the setting. It waits all the same:
         *  a phone that refused the shell the write (an OPPO-family phone's
         *  switch off, ShellGrant) may allow it later. */
        REFUSED,
    }

    /**
     * The user's [keep], from setup's question or the switch: the question
     * answered, and the choice sent to the recorder if one answers.
     */
    suspend fun choose(context: Context, keep: Boolean): Outcome {
        Prefs.of(context).edit {
            putBoolean(KEY_ASKED, true)
            putBoolean(KEY_WISH, keep)
            remove(KEY_REFUSED)
        }
        return carryOut(context)
    }

    /** A waiting choice sent to the recorder, and no longer waiting once it
     *  is carried out. */
    suspend fun carryOut(context: Context): Outcome = sending.withLock {
        val keep = waiting(context) ?: return@withLock Outcome.DONE
        val done = recorder(context, keep)
        Log.i(TAG, "keep paired: ${if (keep) "keep" else "put back"} -> ${done ?: "no answer"}")
        if (done == null) return@withLock Outcome.WAITING
        val prefs = Prefs.of(context)
        if (!done) {
            // Said only of the choice still waiting: one made since is not
            // what was refused.
            if (waiting(context) == keep) prefs.edit { putBoolean(KEY_REFUSED, true) }
            return@withLock Outcome.REFUSED
        }
        // Unless the user chose again meanwhile: theirs goes next.
        prefs.edit {
            remove(KEY_REFUSED)
            if (prefs.contains(KEY_WISH) && prefs.getBoolean(KEY_WISH, false) == keep) remove(KEY_WISH)
        }
        if (waiting(context) == null) Outcome.DONE else Outcome.WAITING
    }

    /**
     * Start fresh: the timeout put back, if JemRec turned it off, and the
     * question asked again. Asked of the recorder, which Start fresh has not
     * yet ended (RecorderLifecycle.endForReset); with none answering, the put back waits for the recorder
     * the next setup starts, and setup asks again before it.
     */
    suspend fun forget(context: Context) {
        Prefs.of(context).edit {
            remove(KEY_ASKED)
            remove(KEY_REFUSED)
            putBoolean(KEY_WISH, false)
        }
        carryOut(context)
    }
}
