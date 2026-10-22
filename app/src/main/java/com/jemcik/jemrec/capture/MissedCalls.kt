package com.jemcik.jemrec.capture

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Icon
import android.util.Log
import com.jemcik.jemrec.DeepLink
import com.jemcik.jemrec.Prefs
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Calls that should have been recorded and were not, said out loud.
 *
 * WHY THIS, ON TOP OF THE SELF-TEST
 *
 * The self-test answers "would a call be recorded right now?" when someone
 * thinks to ask. The complaint every call recorder's reviews are full of is
 * the other question - "was that call recorded?" - asked weeks later, on the
 * day the recording was needed, about a call that was not. Whatever the cause
 * (a recorder that died, a watch that went deaf, a hang-up that never reached
 * the app), the phone's own call log knows the call happened. So after each
 * save and at every keep-alive tick, the log is held against what was
 * recorded, and a call with nothing to show for it is reported once, while it
 * can still matter.
 *
 * ONLY WHERE A RECORDING WAS OWED
 *
 * Automatic mode only - in ask-first a call without a recording is usually one
 * the user said no to. Only calls since the user last changed the recorder
 * switch or the mode, because a call made while it was off is not a missed
 * one. Only calls long enough to have been worth it, and over long enough ago
 * that the save had its chance. Only with call-log access, which is optional;
 * without it this does nothing and the self-test says it could not check.
 *
 * What counts as recorded is the ledger (see CallLedger), not the files on
 * disk, so deleting a recording never makes its call look missed.
 */
internal object MissedCalls {

    private const val TAG = "JemRec"
    private const val NOTIFICATION_ID = 32
    private const val KEY_COVERED_SINCE = "calls_covered_since"
    private const val KEY_SEEN_UNTIL = "missed_seen_until"

    /** Shorter calls are mostly hang-ups, and a false alarm costs more than
     *  a five-second call. */
    internal const val MIN_SECONDS = 5

    /** The save gets this long after the call to land, plus a minute for the
     *  ringing the log does not count. */
    internal const val SETTLE_MS = 3L * 60 * 1000

    /** A call during a recording - call waiting - lands in that recording. */
    internal const val SPAN_SLACK_MS = 5_000L

    /** Reported calls stay listed this long unless the notification is cleared. */
    private const val LIST_FOR_MS = 24L * 60 * 60 * 1000

    /** The recorder switch or the mode changed: calls before now were made
     *  under other rules and are not this check's business. */
    fun coverageChanged(context: Context) {
        Prefs.of(context).edit().putLong(KEY_COVERED_SINCE, System.currentTimeMillis()).apply()
        // The Calls tab's record of it, for saying why a call went unrecorded.
        CoverageHistory.note(context)
    }

    private fun coveredSince(context: Context): Long {
        val prefs = Prefs.of(context)
        val stored = prefs.getLong(KEY_COVERED_SINCE, 0L)
        if (stored > 0L) return stored
        // First run of this check on this phone: nothing before it was ever
        // promised, so nothing before it is reported.
        val now = System.currentTimeMillis()
        prefs.edit().putLong(KEY_COVERED_SINCE, now).apply()
        return now
    }

    suspend fun check(context: Context) {
        if (!CallLogLookup.granted(context)) return
        if (!RecorderSwitch.isOn(context) || RecordingMode.of(context) != RecordingMode.AUTOMATIC) return
        // A call in progress is not in the log yet, and its recording is not
        // saved yet. Ask again after it.
        if (CaptureDaemon.callInProgress(context)) return
        val covered = coveredSince(context)
        // Anything still on the daemon will be saved; it counts as recorded.
        // A daemon that cannot be asked cannot vouch for anything, so the
        // check waits for one that can - the recorder-down alert covers the
        // meantime, and the calls it missed are reported once it is back.
        val pending = CaptureDaemon.listPendingOrNull(context) ?: return
        val now = System.currentTimeMillis()
        val calls = CallLogLookup.answeredCallsSince(context, maxOf(covered, now - CallLedger.KEEP_MS))
        // A messenger call's file is no phone call's recording.
        val pendingStarts = pending.filter { VoipApps.forRecfile(it) == null }.mapNotNull { name ->
            RecordingSaver.startMillis(name)?.let { CallLedger.Recorded(it, RecordingStore.isIncoming(name), -1) }
        }
        val missed = unrecorded(calls, CallLedger.read(context), pendingStarts, covered, now)
        if (missed.isEmpty()) return
        Log.w(TAG, "missed: ${missed.size} call(s) with no recording: ${missed.joinToString { it.at.toString() }}")
        missed.forEach { CallLedger.noteReported(context, it.at) }
        notify(context, calls, now)
    }

    /**
     * The calls in [calls] that should have a recording and do not.
     *
     * A recording accounts for a call when it went the same way and started
     * where the call log's date says it could have (see CallLogLookup.sameCall),
     * or when the call began while it was running - call waiting puts the
     * second call into the first one's recording. A pending one ([pending],
     * length unknown) accounts by its start only.
     */
    internal fun unrecorded(
        calls: List<CallLogLookup.LoggedCall>,
        ledger: CallLedger.Ledger,
        pending: List<CallLedger.Recorded>,
        coveredSince: Long,
        now: Long,
    ): List<CallLogLookup.LoggedCall> {
        // A call the rules left out was recorded and thrown away on purpose.
        val known = ledger.accounted + pending
        return calls.filter { call ->
            call.at > coveredSince &&
                call.seconds >= MIN_SECONDS &&
                call.at + call.seconds * 1000L + SETTLE_MS <= now &&
                call.at !in ledger.reported &&
                known.none { r ->
                    (r.incoming == call.incoming && CallLogLookup.sameCall(call.at, r.startMillis)) ||
                        (r.seconds > 0 && call.at in r.startMillis..(r.startMillis + r.seconds * 1000L + SPAN_SLACK_MS))
                }
        }
    }

    /** The reported calls the user has not cleared yet, newest first. */
    private fun notify(context: Context, calls: List<CallLogLookup.LoggedCall>, now: Long) {
        val seenUntil = Prefs.of(context).getLong(KEY_SEEN_UNTIL, 0L)
        val reported = CallLedger.read(context).reported
        val listed = calls
            .filter { it.at in reported && it.at > seenUntil && it.at > now - LIST_FOR_MS }
            .sortedByDescending { it.at }
        if (listed.isEmpty()) return
        AfterCall.ensureChannels(context)

        val title = if (listed.size == 1) "A call was not recorded" else "${listed.size} calls were not recorded"
        val lines = listed.map { "${it.label}, ${at(it.at)}, ${AfterCall.length(it.seconds.toDouble())}" }
        val body = lines.take(5).joinToString("\n") +
            (if (lines.size > 5) "\n…and ${lines.size - 5} more" else "") +
            "\n\nRun the self-test to see why."
        val selfTest = PendingIntent.getActivity(
            context, 3201, DeepLink.intent(context, DeepLink.Kind.SELF_TEST),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, AfterCall.PROBLEM_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(lines.first())
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(selfTest)
            .setDeleteIntent(seen(context))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(
                Notification.Builder(context, AfterCall.PROBLEM_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle(title)
                    .build(),
            )
            .addAction(Notification.Action.Builder(null as Icon?, "Self-test", selfTest).build())
            .build()
        runCatching {
            context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }.onFailure { Log.w(TAG, "missed: could not post", it) }
    }

    /** Dismissing the notification clears its list; the next miss starts a
     *  new one rather than bringing the old calls back. */
    private fun seen(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 3202,
            android.content.Intent(context, AfterCallReceiver::class.java).setAction(ACTION_SEEN),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    const val ACTION_SEEN = "com.jemcik.jemrec.missed.SEEN"

    fun markSeen(context: Context) {
        Prefs.of(context).edit().putLong(KEY_SEEN_UNTIL, System.currentTimeMillis()).apply()
    }

    /** The user acted on it - its Self-test button does not take it down on
     *  its own the way a tap on its body does. */
    fun dismiss(context: Context) {
        markSeen(context)
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) }
    }

    /** "14:05" today, "3 Oct 14:05" before that. */
    private fun at(millis: Long): String {
        val then = Calendar.getInstance().apply { timeInMillis = millis }
        val now = Calendar.getInstance()
        val today = then.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
            then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
        return SimpleDateFormat(if (today) "HH:mm" else "d MMM HH:mm", Locale.getDefault()).format(Date(millis))
    }
}
