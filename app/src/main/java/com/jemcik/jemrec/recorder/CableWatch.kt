package com.jemcik.jemrec.recorder

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.capture.CaptureDaemon
import com.jemcik.jemrec.system.AdbdState
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Brings the recorder back after a USB cable, or a USB mode chosen with one
 * in, takes adbd down with it.
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
 * A Galaxy S20 (One UI 5.1, Android 13), 2026-11-04, did it the other way
 * round: pulled out, nothing; plugged into a computer, adbd restarted by itself
 * a few seconds later and took the recorder with it. This brought it back 15.5
 * and 19.7 s after the plug-in, the same way, adbd running or not.
 *
 * HOW THIS IS WOKEN
 *
 * The daemon watches the cable (its own CableWatch.watchCable) and wakes CallMonitorService
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
 *
 * AND BY ANDROID, FOR A USB MODE CHANGED WITH THE CABLE IN
 *
 * The daemon's port poll sees a cable, not a mode: choosing file transfer
 * with the cable in leaves the port as it is, and where a change kills, the
 * kill is its first step (AOSP's legacy UsbDeviceManager sets the
 * configuration to none, and init stops adbd), before anything of the
 * daemon's could look. Measured on a Galaxy S23 (One UI 9, Android 17),
 * 2026-11-21: file transfer chosen 22 s after the plug-in, after this watch
 * had ended, killed the recorder, and nothing noticed it in the 109 s before
 * the test's next step started it again. On the
 * Honor, 2026-11-22, a change between file transfer, PTP and MTP kept adbd,
 * and charging only - whose functions leave adb out - stopped it for good,
 * as an unplug does. So Android's own USB_STATE broadcast, sent at each
 * change, wakes this too (UsbStateReceiver), and the same watch runs as a
 * job (UsbStateWatch), with the trigger its hand-over names - noting adbd
 * stopped only where it reads so ([notesStoppedAdbd]).
 */
internal object CableWatch {

    private const val TAG = "JemRec"
    private const val KEY_STOPPED_ADBD = "cable_stopped_adbd"

    /** Long enough for the kill, three seconds after the cable on the Honor
     *  and about five on the S20, to land; short enough that a cable that
     *  changed nothing ends the service inside the ten seconds Android waits
     *  before showing its notification. */
    internal const val WATCH_MS = 8_000L

    /**
     * A USB state's run's window ([windowFor]): three seconds, not eight.
     * Where a mode change kills, the kill comes with the broadcast or before
     * it - JemRec's receiver ran 86 to 143 ms after the kill on the four
     * emulators, whose old configuration set again about 1.1 s later killed
     * a second time, and 61 ms after the Honor's charging only was set
     * (2026-11-22) - and an unplug's kill, three seconds after its
     * broadcast, is the cable's own watch's, woken by the recorder. Each run
     * is an expedited job, and its time comes out of the expedited quota
     * JemRec's kicks share - five minutes in a day in the restricted standby
     * bucket (developer.android.com, Power management resource limits); a
     * real gadget's three broadcasts to a change make two runs: six seconds
     * where nothing died, not sixteen (review round 1, L5).
     */
    internal const val STATE_WATCH_MS = 3_000L

    /** How long [trigger]'s watch looks at a recorder that stays up. */
    internal fun windowFor(trigger: Trigger): Long = if (trigger == Trigger.USB_STATE) STATE_WATCH_MS else WATCH_MS
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

    /**
     * Whether a recorder found gone is noted as a cable's stopped adbd
     * ([stoppedAdbd]), which has the next revive switch Wireless debugging
     * off and on whatever it has done on this Wi-Fi before. A cable's watch,
     * always. A USB state's run only where [adbd] reads stopped now - that is
     * what the note is for: the Honor's charging only leaves adbd stopped, as
     * an unplug does, also with Wireless debugging on (review round 2). Not
     * otherwise: USB_STATE also comes as a boot completes, where no cable
     * stopped anything, and a run alongside the cable's watch would set the
     * note again after that watch's revive had used it up (review round 1);
     * a running adbd needs no switching, and one off between starts is
     * started by the revive's switch-on. [adbd] is read only for a USB state.
     */
    internal fun notesStoppedAdbd(trigger: Trigger, adbd: () -> AdbdState.State): Boolean =
        trigger == Trigger.CABLE || adbd() == AdbdState.State.STOPPED

    /** One look at the recorder, [sinceMs] into a watch of [windowMs]. */
    internal fun next(sinceMs: Long, recorderUp: Boolean, windowMs: Long = WATCH_MS): Step = when {
        !recorderUp -> Step.REVIVE
        sinceMs >= windowMs -> Step.DONE
        else -> Step.WAIT
    }

    /** Whether a cable stopped adbd and nothing has started it since. */
    fun stoppedAdbd(context: Context): Boolean = Prefs.of(context).getBoolean(KEY_STOPPED_ADBD, false)

    /** What a cable did is over: a revive switched Wireless debugging off
     *  and on, which starts adbd, or found the recorder up. */
    fun forget(context: Context) {
        val prefs = Prefs.of(context)
        if (prefs.getBoolean(KEY_STOPPED_ADBD, false)) prefs.edit { remove(KEY_STOPPED_ADBD) }
    }

    /** The daemon said a cable went in or came out ([what]), or Android said
     *  the USB state changed ([trigger] USB_STATE). Returns when the recorder
     *  is known to be fine, or has been given every chance to be. */
    suspend fun watch(context: Context, what: String?, trigger: Trigger = Trigger.CABLE) {
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
            when (next(since, up, windowFor(trigger))) {
                Step.WAIT -> delay(CHECK_MS)
                Step.DONE -> {
                    Log.i(TAG, "cable: the recorder is unaffected")
                    return
                }
                Step.REVIVE -> {
                    Log.w(TAG, "cable: the recorder went ${since / 1000.0} s into the watch; starting adbd and the recorder again")
                    if (notesStoppedAdbd(trigger) { AdbdState.read() }) {
                        Prefs.of(context).edit { putBoolean(KEY_STOPPED_ADBD, true) }
                    }
                    // Not to be cut off half way by a STOP to the service -
                    // the recorder switched off, say: between Wireless
                    // debugging's off and on, or between the old recorder's
                    // end and the new one's start.
                    withContext(NonCancellable) { bringBack(context, start, trigger) }
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
     *  be timed - see [heldBack]; [trigger] is what woke the watch, for the
     *  hand-over. */
    private suspend fun bringBack(context: Context, start: Long, trigger: Trigger) {
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
        RecorderLifecycle.followUp(context, trigger, alive = false)
    }
}
