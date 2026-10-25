package com.jemcik.jemrec.capture

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.jemcik.jemrec.Prefs
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Brings the recorder back after a USB cable takes adbd down with it.
 *
 * WHAT A CABLE DOES ON THIS PHONE
 *
 * Measured on the Honor (MagicOS 11, Android 17), 2026-10-24 18:44:
 * unplugged from a computer, the phone took "adb" out of its USB functions
 * three seconds later, init stopped adbd, and the recorder - in adbd's cgroup,
 * which shell cannot leave - died with it. Then nothing: adbd stayed stopped
 * with USB debugging and Wireless debugging both still on, so every revive
 * found Wireless debugging switched on and nothing listening, and took it
 * for a Wi-Fi Android had not allowed. Switching Wireless debugging off and
 * on is what started adbd again (18:47:13), and the recorder was back four
 * seconds later. Earlier that day nothing did, and the phone recorded nothing
 * for 74 minutes. A wall charger, plugged or pulled, left everything alone.
 *
 * HOW THIS IS WOKEN
 *
 * The daemon watches the cable (Main.watchCable) and wakes CallMonitorService
 * with CABLE while it still can: there are three seconds between the cable
 * and the kill, and the app, started by zygote, is in a cgroup of its own and
 * lives through it. This then watches the recorder for [WATCH_MS]. Still up:
 * nothing happened, and the service stops before Android would show its
 * notification. Gone: adbd is taken for stopped, Wireless debugging is
 * switched off and on to start it, and the recorder is started again.
 *
 * The note that a cable stopped adbd outlives this watch, for a revive that
 * cannot be done now - off Wi-Fi, say: the next revive on Wi-Fi, from
 * wherever it comes, starts adbd the same way, and the note goes once it has.
 */
internal object CableWatch {

    private const val TAG = "JemRec"
    private const val KEY_STOPPED_ADBD = "cable_stopped_adbd"

    /** Long enough for the kill, three seconds after the cable, to land with
     *  room to spare; short enough that a cable that changed nothing ends the
     *  service inside the ten seconds Android waits before showing its
     *  notification. */
    internal const val WATCH_MS = 8_000L
    internal const val CHECK_MS = 500L

    /** Revives right after the kill, before handing over to the keep-alive's
     *  backoff: adbd takes a few seconds to advertise once started. */
    internal const val ATTEMPTS = 3
    internal const val RETRY_MS = 3_000L

    /** How long the watch keeps the phone awake at most (CallMonitorService):
     *  the watch, a confirmed death of about three seconds, and every attempt
     *  at its slowest - Wireless debugging off and on, adbd's settling, a
     *  connect that times out - with room for the start itself. */
    internal const val AWAKE_MS = 90_000L

    enum class Step { WAIT, REVIVE, DONE }

    /**
     * Whether the phone held this app back: the recorder came back [tookMs]
     * after the cable, later than the slowest a restart takes by itself -
     * [AWAKE_MS], which the watch's wake lock is sized for. Measured on the
     * Honor, 2026-10-25: 12-16 s on App launch "Manage manually"; on "Manage
     * automatically" its power manager froze JemRec mid-restart and the
     * recorder came back 8.5 minutes later, when the screen was touched. A
     * restart that never comes back (off Wi-Fi, handed to the keep-alive) is
     * not timed at all, so a missing network never reads as this.
     */
    internal fun heldBack(tookMs: Long): Boolean = tookMs > AWAKE_MS

    /** Whether a [gapMs] between two looks warns: only with the recorder down
     *  ([recorderDown]) - see [warnIfHeldBack]. */
    internal fun warnsAfterGap(gapMs: Long, recorderDown: Boolean): Boolean = recorderDown && heldBack(gapMs)

    /** One look at the recorder, [sinceMs] into the watch. */
    internal fun next(sinceMs: Long, recorderUp: Boolean): Step = when {
        !recorderUp -> Step.REVIVE
        sinceMs >= WATCH_MS -> Step.DONE
        else -> Step.WAIT
    }

    /** Whether a cable stopped adbd and nothing has started it since. */
    fun stoppedAdbd(context: Context): Boolean = Prefs.of(context).getBoolean(KEY_STOPPED_ADBD, false)

    /** What a cable did is over: a revive switched Wireless debugging off
     *  and on, which starts adbd, or found the recorder up. */
    fun forget(context: Context) {
        val prefs = Prefs.of(context)
        if (prefs.getBoolean(KEY_STOPPED_ADBD, false)) prefs.edit().remove(KEY_STOPPED_ADBD).apply()
    }

    /** The daemon said a cable went in or came out ([what]). Returns when the
     *  recorder is known to be fine, or has been given every chance to be. */
    suspend fun watch(context: Context, what: String?) {
        Log.i(TAG, "cable: ${what ?: "changed"}; watching the recorder")
        ReviveLog.note(context, "cable: ${what ?: "changed"}; watching")
        val start = SystemClock.elapsedRealtime()
        var lastLook = start
        while (true) {
            val since = SystemClock.elapsedRealtime() - start
            // Confirmed, not one ping: a ping that fails against a live
            // recorder would end the watch before the kill lands, and leave
            // nothing watching when it does.
            val up = CaptureDaemon.isRunningConfirmed(context)
            ReviveLog.note(context, "cable: ${since} ms in, recorder ${if (up) "up" else "gone"}")
            lastLook = warnIfHeldBack(context, start, lastLook, recorderDown = !up)
            when (next(since, up)) {
                Step.WAIT -> delay(CHECK_MS)
                Step.DONE -> {
                    Log.i(TAG, "cable: the recorder is unaffected")
                    return
                }
                Step.REVIVE -> {
                    Log.w(TAG, "cable: the recorder went ${since / 1000.0} s into the watch; starting adbd and the recorder again")
                    Prefs.of(context).edit().putBoolean(KEY_STOPPED_ADBD, true).apply()
                    // Not to be cut off half way by a STOP to the service -
                    // the recorder switched off, say: between Wireless
                    // debugging's off and on, or between the old recorder's
                    // end and the new one's start.
                    withContext(NonCancellable) { bringBack(context, start) }
                    return
                }
            }
        }
    }

    /**
     * Warn the moment this process runs again after the phone held it back,
     * and return the time of this look for the next.
     *
     * Looks come every [CHECK_MS] and each takes a few seconds at most, so two
     * of them further apart than a whole restart may take ([heldBack]) can
     * only mean this app was not allowed to run between them. Measured on the
     * Honor, 2026-10-25 15:36-15:43: frozen three seconds after the cable,
     * thawed for half a minute at 15:38, frozen again mid-restart - so
     * waiting for the restart to finish before warning meant no warning
     * until the app was opened, seven minutes on. Warning at the first thaw
     * says so while it is happening; the finished restart then updates the
     * same notification with the whole time, without a second sound.
     *
     * Only with the recorder down ([recorderDown]): a watch frozen after a
     * cable that changed nothing - a charger pulled with the screen off, on
     * "Manage automatically" - held nothing back that mattered, and saying a
     * restart was held up when the recorder ran all along would be false.
     */
    private fun warnIfHeldBack(context: Context, start: Long, lastLook: Long, recorderDown: Boolean): Long {
        val now = SystemClock.elapsedRealtime()
        if (warnsAfterGap(now - lastLook, recorderDown)) {
            Log.w(TAG, "cable: ${(now - lastLook) / 1000} s went by between two looks; the phone held JemRec back")
            ReviveLog.note(context, "cable: held back ${now - lastLook} ms between looks")
            BackgroundKill.warnHeldBack(context, now - start)
        }
        return now
    }

    /** [start] is when the watch began (elapsedRealtime), so the restart can
     *  be timed - see [heldBack]. */
    private suspend fun bringBack(context: Context, start: Long) {
        var lastLook = SystemClock.elapsedRealtime()
        repeat(ATTEMPTS) { attempt ->
            ReviveLog.note(context, "cable: attempt ${attempt + 1}")
            if (CaptureDaemon.revive(context)) {
                // The whole time, from the cable: a restart thawed part-way is
                // in it too, so this one warning covers that case.
                val took = SystemClock.elapsedRealtime() - start
                Log.i(TAG, "cable: the recorder is back (attempt ${attempt + 1}, ${took / 1000} s after the cable)")
                ReviveLog.note(context, "cable: back (attempt ${attempt + 1}, ${took} ms)")
                if (heldBack(took)) BackgroundKill.warnHeldBack(context, took)
                return
            }
            // A failed attempt that was frozen part-way returns here when
            // thawed, the recorder still down: say so now.
            lastLook = warnIfHeldBack(context, start, lastLook, recorderDown = true)
            if (attempt < ATTEMPTS - 1) delay(RETRY_MS)
        }
        // Off Wi-Fi, most likely. The keep-alive retries with backoff, the
        // Wi-Fi watch fires when there is some, and either starts adbd the
        // way this would have (see stoppedAdbd).
        Log.w(TAG, "cable: the recorder is not back yet; leaving it to the keep-alive")
        ReviveLog.note(context, "cable: not back after $ATTEMPTS attempts; keep-alive")
        if (!NetworkRevive.onWifi(context)) NetworkRevive.arm(context)
        RecorderKeepAlive.kickNow(context)
    }
}
