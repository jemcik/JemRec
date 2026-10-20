package com.jemcik.jemrec.capture

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.util.Log
import com.jemcik.jemrec.DeepLink
import com.jemcik.jemrec.MainActivity
import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.adb.AdbTransport

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
 */
internal object RecorderHealth {

    private const val TAG = "JemRec"
    private const val NOTIFICATION_ID = 34
    private const val KEY_DOWN_SINCE = "recorder_down_since"
    private const val KEY_ALERTED = "recorder_down_alerted"
    private const val KEY_REFUSED = "adb_refused_in_a_row"

    internal const val GRACE_MS = 2L * 60 * 1000

    /** Two refusals in a row from an adbd that is plainly there: the pairing
     *  is the likeliest reason, not a hiccup. */
    internal const val REFUSALS_FOR_PAIRING = 2

    enum class Problem { NEEDS_WIFI, CANNOT_CONNECT, CANNOT_START }

    /** Called with the outcome of every revive. Cheap; never throws. */
    fun onRevive(context: Context, alive: Boolean) = runCatching {
        val prefs = Prefs.of(context)
        // Refusals count only in a row: a recorder that came up, or an adbd
        // that let us in, starts the count again.
        val refused = when {
            alive -> 0
            AdbTransport.lastConnect == AdbTransport.ConnectOutcome.REFUSED -> prefs.getInt(KEY_REFUSED, 0) + 1
            AdbTransport.lastConnect == AdbTransport.ConnectOutcome.NONE -> prefs.getInt(KEY_REFUSED, 0)
            else -> 0
        }
        prefs.edit().putInt(KEY_REFUSED, refused).apply()

        val watching = CaptureDaemon.isSetUp(context) && RecorderSwitch.isOn(context)
        if (alive || !watching) {
            if (prefs.contains(KEY_DOWN_SINCE) || prefs.contains(KEY_ALERTED)) {
                if (alive) Log.i(TAG, "health: recorder is back")
                prefs.edit().remove(KEY_DOWN_SINCE).remove(KEY_ALERTED).apply()
                cancel(context)
            }
            return@runCatching
        }

        val now = System.currentTimeMillis()
        val since = prefs.getLong(KEY_DOWN_SINCE, 0L).takeIf { it > 0 }
            ?: now.also { prefs.edit().putLong(KEY_DOWN_SINCE, it).apply() }
        val problem = diagnose(NetworkRevive.onWifi(context), refused)
        val alerted = prefs.getString(KEY_ALERTED, null)
        if (shouldAlert(now, since, alerted, problem)) {
            Log.w(TAG, "health: recorder down since $since, telling the user ($problem)")
            post(context, problem)
            prefs.edit().putString(KEY_ALERTED, problem.name).apply()
        }
    }.onFailure { Log.w(TAG, "health: could not judge the recorder", it) }

    internal fun diagnose(onWifi: Boolean, refusedInARow: Int): Problem = when {
        !onWifi -> Problem.NEEDS_WIFI
        refusedInARow >= REFUSALS_FOR_PAIRING -> Problem.CANNOT_CONNECT
        else -> Problem.CANNOT_START
    }

    internal fun shouldAlert(now: Long, downSince: Long, alerted: String?, problem: Problem): Boolean =
        now - downSince >= GRACE_MS && alerted != problem.name

    private fun post(context: Context, problem: Problem) {
        AfterCall.ensureChannels(context)
        val (text, button) = when (problem) {
            Problem.NEEDS_WIFI ->
                "Calls are not being recorded until the phone is on Wi-Fi for a few seconds. " +
                    "Any network will do - it does not need internet. JemRec restarts on its own." to null
            Problem.CANNOT_CONNECT ->
                "JemRec cannot connect to Wireless debugging. The phone may have forgotten " +
                    "its pairing. Pairing again takes a minute." to
                    ("Pair again" to DeepLink.intent(context, DeepLink.Kind.PAIR_AGAIN))
            Problem.CANNOT_START ->
                "JemRec could not restart the recorder on this network. Open JemRec, or try " +
                    "another Wi-Fi network." to null
        }
        val open = PendingIntent.getActivity(
            context, 3401,
            button?.second ?: Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(context, AfterCall.PROBLEM_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("JemRec is not recording calls")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
        button?.let { builder.addAction(Notification.Action.Builder(null as Icon?, it.first, open).build()) }
        runCatching {
            context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, builder.build())
        }.onFailure { Log.w(TAG, "health: could not post", it) }
    }

    fun cancel(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) }
    }
}
