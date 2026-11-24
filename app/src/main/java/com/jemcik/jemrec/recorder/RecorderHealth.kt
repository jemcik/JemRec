package com.jemcik.jemrec.recorder

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.edit
import com.jemcik.jemrec.DeepLink
import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.adb.AdbTransport
import com.jemcik.jemrec.calls.AfterCall
import com.jemcik.jemrec.capture.CaptureDaemon
import com.jemcik.jemrec.system.AdbdState
import com.jemcik.jemrec.system.GlobalSettings

/**
 * Says so when the recorder is down, instead of leaving the user to find out.
 *
 * WHY THE HEADER IS NOT ENOUGH
 *
 * The home screen has always said plainly when nothing is being recorded - to
 * whoever opens it. The app is dormant between calls by design, so the person
 * who most needs to know (just rebooted, on mobile data, about to make a call
 * that matters) is exactly the one not looking at it. Every ADB-based recorder
 * has the same failure in its issue tracker: a privilege helper that died
 * quietly and a day of calls with nothing to show.
 *
 * So whenever a revive ends with the recorder down, this keeps track of how
 * long it has been down, and past [GRACE_MS] posts one notification saying
 * why and what to do. It does not nag: one post per cause, updated in place
 * if the cause changes, and gone the moment the recorder is back.
 *
 * The grace period is what keeps it honest after a reboot on Wi-Fi, when the
 * recorder is back within seconds and an alarm would only be noise. Without
 * Wi-Fi it is the README's warning, delivered: nothing is recorded until the
 * phone has a few seconds of any Wi-Fi.
 *
 * It says nothing while recording is switched off, or before setup has
 * finished - a recorder the user turned off is not down.
 *
 * A WI-FI ANDROID HAS NOT ALLOWED looks, from here, like Wireless debugging
 * switched on and nothing advertising it. Android lets Wireless debugging
 * listen only on networks the user has allowed ("Always allow on this
 * network"). On any other network, switching it on, which is what a revive
 * does after a reboot, makes SystemUI ask "Allow wireless debugging on this
 * network?". Until someone answers Allow, adbd advertises nothing and opens
 * no port. Measured on the Android 17 QPR2 emulator: `dumpsys adb` said
 * is_trusted_network=false, tls_port=0. Cancel left the switch on and the
 * question gone, and the next revive, finding the switch already on, never
 * asked again. Nothing an app can read says "not allowed", so a revive on
 * Wi-Fi that found the switch on and no advertisement is taken to mean
 * exactly this - with adbd running ([notAllowed]). Not with adbd stopped:
 * after a restart of the Honor (2026-10-28) both switches were on, adbd was
 * not running, and the header asked to allow a Wi-Fi Android had allowed
 * long before; a tap that switched it off and on started adbd, and no
 * question came. Android 12 keeps the switch off while it asks, and after
 * Cancel (the Android 12 AVD, 2026-10-28), so there the revive's own note
 * that Android switched it back off stands for it
 * (NetworkRevive.noteWirelessRefused). The answer is to ask again: the alert's button switches
 * Wireless debugging off and on (see CaptureDaemon.askToAllowNetwork), and
 * Android puts the question back on screen.
 */
internal object RecorderHealth {

    private const val TAG = "JemRec"
    private const val NOTIFICATION_ID = 34
    private const val KEY_DOWN_SINCE = "recorder_down_since"
    private const val KEY_ALERTED = "recorder_down_alerted"
    private const val KEY_REFUSED = "adb_refused_in_a_row"
    private const val KEY_CAUSE = "recorder_down_cause"

    internal const val GRACE_MS = 2L * 60 * 1000

    /** Two refusals in a row from an adbd that is plainly there: the pairing
     *  is the likeliest reason, not a hiccup. */
    internal const val REFUSALS_FOR_PAIRING = 2

    enum class Problem { NEEDS_WIFI, NETWORK_NOT_ALLOWED, CANNOT_CONNECT, CANNOT_START }

    /** What Android said around a revive that failed, read before the
     *  alert's lock (onRevive). */
    internal class Down(
        val onWifi: Boolean,
        val unadvertised: Boolean,
        val inCall: Boolean,
    )

    /** Set up and on: only then is a recorder down a problem to tell. */
    private fun watching(context: Context) = CaptureDaemon.isSetUp(context) && RecorderSwitch.isOn(context)

    /** Called with the outcome of every revive - [connect], what its own
     *  connect found (CaptureDaemon.revive). Cheap; never throws. Android is
     *  asked first - a setting, the network, adbd's state, the audio mode -
     *  and the flags are judged under the alert's lock after, so a resume's
     *  [seen], on the main thread, waits on the flags, and on Android only
     *  where an alert is said or taken back under the lock - its channels and
     *  PendingIntents, a notify, a cancel: rare, the recorder down past its
     *  grace (review rounds 3 to 5: lock contention is an ANR cause,
     *  developer.android.com). */
    fun onRevive(context: Context, alive: Boolean, connect: AdbTransport.ConnectOutcome) = runCatching {
        val wireless = GlobalSettings.read(context, GlobalSettings.ADB_WIFI_ENABLED)
        val seen = meant(connect, wireless)
        val down = if (alive || !watching(context)) {
            null
        } else {
            Down(
                onWifi = NetworkRevive.onWifi(context),
                // Asked only when the connect found no service, as before
                // round 3: adbd's state is a getprop, up to 2 s, twice
                // (review round 4).
                unadvertised = seen == AdbTransport.ConnectOutcome.NOT_FOUND &&
                    notAllowed(
                        refusedHere = NetworkRevive.wirelessRefusedHere(context),
                        switchOn = wireless == GlobalSettings.Reading.ON,
                        adbd = AdbdState.read(),
                        switchedOnHere = NetworkRevive.switchedOnHere(context),
                    ),
                inCall = CaptureDaemon.callInProgress(context),
            )
        }
        judge(context, alive, seen, down)
    }.onFailure { Log.w(TAG, "health: could not judge the recorder", it) }

    /** [onRevive]'s judging, under the alert's lock: the flags only - and,
     *  when an alert is said, its posting (rare: the recorder down past the
     *  grace). [down] is null with the recorder up, or not watched. */
    @androidx.annotation.VisibleForTesting
    @Synchronized
    internal fun judge(
        context: Context,
        alive: Boolean,
        seen: AdbTransport.ConnectOutcome,
        down: Down?,
    ) {
        val prefs = Prefs.of(context)
        // Refusals count only in a row: a recorder that came up, or an adbd
        // that let us in, starts the count again.
        val refused = when {
            alive -> 0
            seen == AdbTransport.ConnectOutcome.REFUSED -> prefs.getInt(KEY_REFUSED, 0) + 1
            seen == AdbTransport.ConnectOutcome.NONE -> prefs.getInt(KEY_REFUSED, 0)
            else -> 0
        }
        prefs.edit { putInt(KEY_REFUSED, refused) }

        // Watching read again under the lock: JemRec switched off while
        // Android was asked, its standingDown would be undone - the recorder
        // down kept while off, and alerted at once when on again, with no
        // grace (review round 4). Preferences and a file.
        if (down == null || !watching(context)) {
            forgetDown(context, back = alive)
            return
        }

        val now = System.currentTimeMillis()
        val since = prefs.getLong(KEY_DOWN_SINCE, 0L).takeIf { it > 0 }
            ?: now.also { prefs.edit { putLong(KEY_DOWN_SINCE, it) } }
        val problem = diagnose(down.onWifi, refused, down.unadvertised)
        // Kept at every failed revive, not only once alerted: the home screen
        // shows it straight away, where the alert waits out the grace.
        prefs.edit { putString(KEY_CAUSE, problem.name) }
        val alerted = prefs.getString(KEY_ALERTED, null)
        alert(now, since, alerted, problem, down.inCall)?.let { alert ->
            Log.w(TAG, "health: recorder down since $since, telling the user ($problem${if (alert.silent) ", silently: a call is up" else ""})")
            say(context, problem, alert)
        }
    }

    /**
     * JemRec is off, so a recorder down is no problem: switched off
     * (RecorderSwitch.turn), or standing down, off with all debugging and
     * the recorder gone by choice. Standing down, no revive reaches onRevive
     * - each stands down first - so a recorder down from before the Off, and
     * its alert, would stay until JemRec was on again; switched off, they
     * stayed until the next revive, up to 15 minutes later. They go now, as
     * they do at a revive while JemRec is off.
     */
    @Synchronized
    fun standingDown(context: Context) = runCatching { forgetDown(context, back = false) }
        .onFailure { Log.w(TAG, "health: could not forget the recorder down", it) }

    /** The recorder is up ([back]), or not watched: what was said of it down goes. */
    private fun forgetDown(context: Context, back: Boolean) {
        val prefs = Prefs.of(context)
        if (!prefs.contains(KEY_DOWN_SINCE) && !prefs.contains(KEY_ALERTED)) return
        if (back) Log.i(TAG, "health: recorder is back")
        prefs.edit {
            remove(KEY_DOWN_SINCE)
            remove(KEY_ALERTED)
            remove(KEY_CAUSE)
        }
        cancel(context)
    }

    /**
     * Nothing advertised, and this Wi-Fi not allowed the reason: Android
     * refused it here, or the switch is on and adbd runs. With adbd's state
     * unreadable, only once a revive has switched it off and on here - the
     * one try that would have started an adbd not running (CaptureDaemon's
     * cycleAfterNotFound). Never with adbd stopped: no Wi-Fi question
     * explains that, and a revive that switched it off and on and still
     * found it stopped has no name for why.
     */
    internal fun notAllowed(
        refusedHere: Boolean,
        switchOn: Boolean,
        adbd: AdbdState.State,
        switchedOnHere: Boolean,
    ): Boolean = when {
        refusedHere -> true
        !switchOn -> false
        else -> when (adbd) {
            AdbdState.State.RUNNING -> true
            AdbdState.State.STOPPED -> false
            AdbdState.State.UNKNOWN -> switchedOnHere
        }
    }

    /**
     * What a revive's connect [found] can mean, with Wireless debugging's
     * switch read as [wireless]. Refused with the switch off is no adbd
     * turning JemRec away - there is no wireless server to do it - but a port
     * mDNS still remembers from before the switch went off: nothing
     * advertised. On the Android 16 AVD, 2026-11-03, after Android refused
     * Wireless debugging on a Wi-Fi, three revives in a row were given the
     * port of a connection 45 s earlier, each turned away within 2 ms, and
     * the header said Pair again - whose tap forgets a pairing that works -
     * where it should have asked to allow the Wi-Fi. Unread (UNKNOWN), a
     * refusal is taken as it comes.
     */
    internal fun meant(found: AdbTransport.ConnectOutcome, wireless: GlobalSettings.Reading): AdbTransport.ConnectOutcome =
        if (found == AdbTransport.ConnectOutcome.REFUSED && wireless == GlobalSettings.Reading.OFF) {
            AdbTransport.ConnectOutcome.NOT_FOUND
        } else {
            found
        }

    /**
     * unadvertised: on Wi-Fi, the revive found no advertisement to connect
     * to, and [notAllowed]. See the class comment.
     */
    internal fun diagnose(onWifi: Boolean, refusedInARow: Int, unadvertised: Boolean = false): Problem = when {
        !onWifi -> Problem.NEEDS_WIFI
        refusedInARow >= REFUSALS_FOR_PAIRING -> Problem.CANNOT_CONNECT
        unadvertised -> Problem.NETWORK_NOT_ALLOWED
        else -> Problem.CANNOT_START
    }

    /** Why the recorder is down, from the last revive that failed; null while
     *  it is up, switched off, or not set up. */
    fun cause(context: Context): Problem? {
        val prefs = Prefs.of(context)
        if (!prefs.contains(KEY_DOWN_SINCE)) return null
        return prefs.getString(KEY_CAUSE, null)?.let { name -> Problem.entries.firstOrNull { it.name == name } }
    }

    /** Post [alert] for [problem], and mark it said. */
    private fun say(context: Context, problem: Problem, alert: Alert) {
        if (alert.again) context.getSystemService(android.app.NotificationManager::class.java).cancel(NOTIFICATION_ID)
        post(context, problem, alert.silent)
        Prefs.of(context).edit { putString(KEY_ALERTED, problem.name + if (alert.silent) SILENT_MARK else "") }
    }

    /**
     * A call is over (CallMonitorService): an alert posted silent in it is
     * posted again with its sound now, not at the next revive - a run that
     * may wait for a network the phone does not have (RecorderKeepAlive), the
     * alert silent all that time (review round 13). Nothing with the mode
     * still not normal: a ring next, which would mute it. Cheap; never throws.
     */
    fun callOver(context: Context) = runCatching {
        // The flag first: the audio mode is asked only with a silent alert
        // standing, as before round 3 - not at every call's end (review
        // round 4) - and before the lock, as onRevive asks its own; the
        // flag is read again under it.
        val alerted = Prefs.of(context).getString(KEY_ALERTED, null) ?: return@runCatching
        if (!alerted.endsWith(SILENT_MARK)) return@runCatching
        callOverLocked(context, CaptureDaemon.callInProgress(context))
    }.onFailure { Log.w(TAG, "health: could not tell the alert again", it) }

    @Synchronized
    private fun callOverLocked(context: Context, inCall: Boolean) {
        val alerted = Prefs.of(context).getString(KEY_ALERTED, null) ?: return
        if (!alerted.endsWith(SILENT_MARK) || inCall) return
        val problem = Problem.entries.firstOrNull { it.name == alerted.removeSuffix(SILENT_MARK) } ?: return
        Log.w(TAG, "health: the call is over; telling the user again, with its sound ($problem)")
        say(context, problem, Alert(silent = false, again = true))
    }

    /** The alert put away by the user: said, silent or not - not posted
     *  again with its sound after a call they swiped it away in (review
     *  round 13). */
    fun dismissed(context: Context) = seen(context)

    /** The alert seen: swiped away ([dismissed]), or JemRec opened - its tap
     *  among the ways, which sends no delete intent (AOSP NMS: a click is
     *  REASON_CLICK, sendDelete false), and the header says what it says
     *  (review round 14). Not posted again with its sound. */
    @Synchronized
    fun seen(context: Context) {
        val prefs = Prefs.of(context)
        val alerted = prefs.getString(KEY_ALERTED, null) ?: return
        if (alerted.endsWith(SILENT_MARK)) prefs.edit { putString(KEY_ALERTED, alerted.removeSuffix(SILENT_MARK)) }
    }

    const val ACTION_DISMISSED = "com.jemcik.jemrec.health.DISMISSED"

    /** What the alert does at a revive: post, [silent] or not; [again]: the
     *  same alert, posted silent in a call, posted anew - cancelled first, as
     *  a notification still up is updated without a sound under
     *  setOnlyAlertOnce (AOSP NotificationAttentionHelper) - so it is heard
     *  once the call is over. Marked as said, it was never heard: the
     *  recorder lost mid-call and never back, the case the call watch is
     *  there for (review round 12). */
    internal data class Alert(val silent: Boolean, val again: Boolean = false)

    /** Whether to alert, once past the grace: a cause not said yet, or said
     *  silent in a call that is over now ([alerted]: the cause said, and
     *  [SILENT_MARK] if silently). Null for nothing to do. */
    internal fun alert(now: Long, downSince: Long, alerted: String?, problem: Problem, callUp: Boolean): Alert? {
        if (now - downSince < GRACE_MS) return null
        val saidSilent = alerted?.endsWith(SILENT_MARK) == true
        return when {
            alerted?.removeSuffix(SILENT_MARK) != problem.name -> Alert(silentNow(callUp), again = saidSilent && !callUp)
            saidSilent && !callUp -> Alert(silent = false, again = true)
            else -> null
        }
    }

    /** After a cause in KEY_ALERTED: said silently, a call up. */
    private const val SILENT_MARK = "|silent"

    /** Posted silent while a call is up or rings ([callUp]: any mode but
     *  normal): Android mutes every notification while telephony is not
     *  idle (AOSP NotificationAttentionHelper.disableNotificationEffects),
     *  and in an app's call outside Telecom plays it as a tone in the
     *  earpiece (playInCallNotification) - posted to sound, it was marked
     *  said and never heard (review round 13). Silent, not held: held to a
     *  call's end it went untold when no service watched the call - a
     *  recorder down before it starts none - until a heartbeat, up to a day
     *  in the restricted bucket (review rounds 10 and 11). Posted again with
     *  its sound once the mode is normal: the call's end ([callOver]), or
     *  the next revive (alert). A mode another app leaves set keeps it
     *  silent, and shown. */
    internal fun silentNow(callUp: Boolean): Boolean = callUp

    /** The group a silent alert is put in (post). */
    private const val SILENT_GROUP = "jemrec.health.silent"

    @androidx.annotation.VisibleForTesting
    internal fun post(context: Context, problem: Problem, silent: Boolean = false) {
        val (text, button) = when (problem) {
            Problem.NEEDS_WIFI ->
                "Calls are not being recorded until the phone is on Wi-Fi for a few seconds. " +
                    "Any network will do - it does not need internet. JemRec restarts on its own." to null
            Problem.NETWORK_NOT_ALLOWED ->
                "Android has not allowed Wireless debugging on this Wi-Fi, and JemRec needs it " +
                    "for a few seconds to restart the recorder. Tap Allow this Wi-Fi, tick Always " +
                    "allow on this network, and tap Allow." to
                    ("Allow this Wi-Fi" to DeepLink.intent(context, DeepLink.Kind.ALLOW_WIFI))
            Problem.CANNOT_CONNECT ->
                "JemRec cannot connect to Wireless debugging. The phone may have forgotten " +
                    "its pairing. Pairing again takes a minute." to
                    ("Pair again" to DeepLink.intent(context, DeepLink.Kind.PAIR_AGAIN))
            Problem.CANNOT_START ->
                "JemRec could not restart the recorder on this network. Open JemRec, or try " +
                    "another Wi-Fi network." to null
        }
        // The tap: home, whose header says why; the button: the fix.
        val open = PendingIntent.getActivity(context, 0, DeepLink.home(context), PendingIntent.FLAG_IMMUTABLE)
        val fix = button?.let { (_, intent) ->
            PendingIntent.getActivity(context, 3401, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        // Swiped away: said, silent or not (dismissed).
        val gone = PendingIntent.getBroadcast(
            context, 3402,
            android.content.Intent(context, com.jemcik.jemrec.capture.AfterCallReceiver::class.java).setAction(ACTION_DISMISSED),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = AfterCall.problem(context, "JemRec is not recording calls", text, open, button?.first?.let { it to fix!! })
            .setOnlyAlertOnce(true)
            .setDeleteIntent(gone)
            .apply {
                // Silent as AndroidX's NotificationCompat.setSilent makes one:
                // in a group whose summary alone may alert (there is none),
                // as the platform builder has no such switch.
                if (silent) setGroup(SILENT_GROUP).setGroupAlertBehavior(android.app.Notification.GROUP_ALERT_SUMMARY)
            }
            .build()
        AfterCall.notifyProblem(context, NOTIFICATION_ID, notification, "health")
    }

    /**
     * The user is pairing again (MainViewModel.pairAgain): what the refusals
     * said is being acted on, so their count and the cause start over and the
     * alert goes. Setup is unfinished meanwhile, and an unfinished setup has
     * no alerts; left, the old cause sent home straight back to the pairing
     * step once setup was done, before any revive had read the new state.
     */
    @Synchronized
    fun pairingAgain(context: Context) {
        Prefs.of(context).edit {
            remove(KEY_REFUSED)
            remove(KEY_DOWN_SINCE)
            remove(KEY_ALERTED)
            remove(KEY_CAUSE)
        }
        cancel(context)
    }

    fun cancel(context: Context) = AfterCall.cancelProblem(context, NOTIFICATION_ID)
}
