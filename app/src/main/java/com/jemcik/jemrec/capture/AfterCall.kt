package com.jemcik.jemrec.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.jemcik.jemrec.DeepLink
import com.jemcik.jemrec.MainActivity
import com.jemcik.jemrec.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * What the user sees the moment a call has been saved.
 *
 * THE RECORDING, WHERE THEY ALREADY ARE
 *
 * The question people ask of a call recorder right after hanging up is "did
 * it get that?", and before this the only way to answer it was to open the
 * app and look. Google Phone keeps its own recordings in a database of its
 * own and has no way in for another app's - checked in its APK: the screen
 * that plays them and the call history behind it are not exported - so this
 * is the next best place: one silent notification with the call's name and
 * length, and Play, Share and Delete on it. It copies what Google's own
 * recorder does with the player it shows for the last call. (Honor's Phone
 * app is the exception, and has its own file names - see FileNames.)
 *
 * Visible but silent: DEFAULT importance, so it puts an icon in the status bar
 * and sits with the other notifications, with its sound and vibration taken
 * away, no banner and no badge. It was LOW first, and on MagicOS LOW means
 * folded into "Muted" at the foot of the shade with no status-bar icon - the
 * first real call on the developer's phone produced it and nobody saw it.
 * One at a time - the newest call replaces the last, so a day of calls is one
 * notification, not a stack of them. It goes away on its own after
 * [TIMEOUT_MS]. It can be turned off in Settings, and like any channel in
 * Android's own settings too.
 *
 * AND THE BAD NEWS, LOUDER
 *
 * When the recording turns out to hold nothing - see [AudioCheck] - this is
 * where the user hears about it, on the problems channel, with a sound and
 * whether or not they wanted the everyday notification. A silent recording is
 * the failure people otherwise discover months later, on the day they need it.
 *
 * Every button goes to this app only: Play and Share open JemRec at that
 * recording, through a guarded link (see DeepLink), and Delete goes to a
 * receiver that is not exported - and asks first, in the notification itself,
 * because a stray tap in the shade must not cost anyone a recording.
 */
internal object AfterCall {

    private const val TAG = "JemRec"
    /** v2: v1 was LOW, and a channel's importance cannot be changed once the
     *  user has it - only replaced. See ensureChannels. */
    private const val CHANNEL_ID = "jemrec.aftercall.v2"
    private const val LEGACY_CHANNEL_ID = "jemrec.aftercall.v1"
    const val PROBLEM_CHANNEL_ID = "jemrec.problem.v1"
    private const val NOTIFICATION_ID = 33
    private const val KEY_ENABLED = "after_call_notice"

    /** Long enough to come back to after the call; short enough not to sit in
     *  the shade from one day into the next. */
    private const val TIMEOUT_MS = 12L * 60 * 60 * 1000

    /** A recording the reconcile saved long after its call is history by
     *  then, not news - the list is where it belongs. */
    private const val FRESH_MS = 30L * 60 * 1000

    const val ACTION_DELETE_ASK = "com.jemcik.jemrec.aftercall.DELETE_ASK"
    const val ACTION_DELETE_CONFIRM = "com.jemcik.jemrec.aftercall.DELETE_CONFIRM"
    const val ACTION_DELETE_CANCEL = "com.jemcik.jemrec.aftercall.DELETE_CANCEL"

    /** On unless switched off: it is what most people want after a call. */
    fun enabled(context: Context): Boolean = Prefs.of(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, on: Boolean) {
        Prefs.of(context).edit().putBoolean(KEY_ENABLED, on).apply()
        if (!on) cancel(context)
    }

    /** One saved call, as much as the notification and its buttons need. */
    data class Call(
        val name: String,
        val uri: Uri,
        val filePath: String?,
        val seconds: Double,
        val incoming: Boolean,
        val startMillis: Long,
        val caller: String?,
        val verdict: AudioCheck.Verdict,
    ) {
        fun into(intent: Intent): Intent = intent.putExtras(
            Bundle().apply {
                putString("name", name)
                putString("uri", uri.toString())
                putString("path", filePath)
                putDouble("seconds", seconds)
                putBoolean("incoming", incoming)
                putLong("start", startMillis)
                putString("caller", caller)
                putString("verdict", verdict.name)
            },
        )

        companion object {
            fun from(intent: Intent): Call? = runCatching {
                Call(
                    name = intent.getStringExtra("name")!!,
                    uri = Uri.parse(intent.getStringExtra("uri")!!),
                    filePath = intent.getStringExtra("path"),
                    seconds = intent.getDoubleExtra("seconds", 0.0),
                    incoming = intent.getBooleanExtra("incoming", false),
                    startMillis = intent.getLongExtra("start", 0L),
                    caller = intent.getStringExtra("caller"),
                    verdict = AudioCheck.Verdict.valueOf(intent.getStringExtra("verdict")!!),
                )
            }.getOrNull()
        }
    }

    /**
     * A call has just been saved: check what is in it and say so. Blocking -
     * it decodes the file - so callers are on a background thread.
     */
    fun saved(context: Context, saved: RecordingSaver.Outcome.Saved, incoming: Boolean, startMillis: Long) {
        // Both save paths come through here, so this is where a call under
        // the user's short-call rule is caught: to the trash, and not a word.
        if (Retention.dropIfTooShort(context, saved)) return
        if (System.currentTimeMillis() - startMillis - (saved.seconds * 1000).toLong() > FRESH_MS) {
            Log.i(TAG, "aftercall: ${saved.name} is from a while ago, not announcing it")
            return
        }
        val verdict = AudioCheck.check(context, saved.uri)
        val caller = runCatching { callerFor(context, saved, incoming) }.getOrNull()
        show(context, Call(saved.name, saved.uri, saved.filePath, saved.seconds, incoming, startMillis, caller, verdict))
    }

    /** Who, the way the list would say it: the call log for a phone call,
     *  the name or the notes for a messenger call ("Viber call" at least). */
    private fun callerFor(context: Context, saved: RecordingSaver.Outcome.Saved, incoming: Boolean): String? {
        val probe = Recording(saved.name, saved.uri, "", incoming, "", "")
        return Recordings.withCallers(context, listOf(probe)).firstOrNull()?.caller
    }

    /** Posts it, unless the user has the everyday one switched off. Whether
     *  it posted. */
    fun show(context: Context, call: Call): Boolean {
        // Only silence. A one-sided verdict is not a missing voice on every
        // phone - see AudioCheck - and a false alarm after a call teaches
        // people to ignore the true one.
        val problem = call.verdict == AudioCheck.Verdict.SILENT
        if (!problem && !enabled(context)) return false
        ensureChannels(context)

        val who = call.caller ?: if (call.incoming) "Incoming call" else "Outgoing call"
        val length = length(call.seconds)
        val builder = Notification.Builder(context, if (problem) PROBLEM_CHANNEL_ID else CHANNEL_ID)
            .setSmallIcon(if (problem) android.R.drawable.stat_notify_error else android.R.drawable.ic_btn_speak_now)
            .setWhen(call.startMillis)
            .setShowWhen(true)
            .setContentIntent(open(context, DeepLink.Kind.PLAY, call.name, request = 0))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(TIMEOUT_MS)
            // A caller's name is not for the lock screen. The public version
            // says only that a call was recorded.
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(
                Notification.Builder(context, if (problem) PROBLEM_CHANNEL_ID else CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentTitle(if (problem) "A call recording needs a look" else "Call recorded")
                    .build(),
            )

        when (call.verdict) {
            AudioCheck.Verdict.SILENT -> {
                val text = "$who, $length. No sound came through. This happens on calls over " +
                    "Wi-Fi calling, and on some phones always. Run the self-test to check this phone."
                builder.setContentTitle("Last call recorded, but silent")
                    .setContentText(text)
                    .setStyle(Notification.BigTextStyle().bigText(text))
                    .addAction(action("Play", open(context, DeepLink.Kind.PLAY, call.name, request = 1)))
                    .addAction(action("Self-test", open(context, DeepLink.Kind.SELF_TEST, null, request = 3)))
                    .addAction(action("Delete", receiver(context, ACTION_DELETE_ASK, call)))
            }
            else -> {
                builder.setContentTitle(who)
                    .setContentText("Recorded, $length")
                    .addAction(action("Play", open(context, DeepLink.Kind.PLAY, call.name, request = 1)))
                    .addAction(action("Share", open(context, DeepLink.Kind.SHARE, call.name, request = 2)))
                    .addAction(action("Delete", receiver(context, ACTION_DELETE_ASK, call)))
            }
        }
        post(context, builder.build())
        Log.i(TAG, "aftercall: posted for ${call.name} (${call.verdict})")
        return true
    }

    /** The second tap: the notification turns into the question. */
    fun askDelete(context: Context, call: Call) {
        ensureChannels(context)
        val text = "Delete the recording of this call? It goes to Recently deleted, where it " +
            "can be restored for ${Trash.DAYS} days."
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_delete)
            .setContentTitle(call.caller ?: if (call.incoming) "Incoming call" else "Outgoing call")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setWhen(call.startMillis)
            .setShowWhen(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setTimeoutAfter(TIMEOUT_MS)
            .addAction(action("Delete", receiver(context, ACTION_DELETE_CONFIRM, call)))
            .addAction(action("Keep", receiver(context, ACTION_DELETE_CANCEL, call)))
            .build()
        post(context, notification)
    }

    fun delete(context: Context, call: Call) {
        val result = Trash.trash(context, call.uri, call.filePath, call.name)
        Log.i(TAG, "aftercall: delete ${call.name} -> ${result.javaClass.simpleName}")
        when (result) {
            is DeleteResult.Deleted -> {
                RecordingSaver.announce(call.name)
                cancel(context)
            }
            // MediaStore wants the user to confirm in a system dialog, which
            // only an activity can show. The list can.
            is DeleteResult.NeedsConsent -> note(context, "Open JemRec to delete this recording.")
            is DeleteResult.Failed -> note(context, "Could not delete the recording.")
        }
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun note(context: Context, text: String) {
        ensureChannels(context)
        post(
            context,
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("JemRec")
                .setContentText(text)
                .setContentIntent(
                    PendingIntent.getActivity(
                        context, 0, Intent(context, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun post(context: Context, notification: Notification) {
        runCatching {
            context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }.onFailure { Log.w(TAG, "aftercall: could not post", it) }
    }

    /** "3:21", or "45 s" for a call under a minute. */
    internal fun length(seconds: Double): String {
        val total = seconds.toLong()
        return if (total < 60) "$total s" else "%d:%02d".format(total / 60, total % 60)
    }

    private fun action(label: String, intent: PendingIntent): Notification.Action =
        Notification.Action.Builder(null as Icon?, label, intent).build()

    private fun open(context: Context, kind: DeepLink.Kind, name: String?, request: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_BASE + request,
            DeepLink.intent(context, kind, name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun receiver(context: Context, action: String, call: Call): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_BASE + action.hashCode(),
            call.into(Intent(context, AfterCallReceiver::class.java).setAction(action)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private const val REQUEST_BASE = 3300

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        runCatching { manager.deleteNotificationChannel(LEGACY_CHANNEL_ID) }
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "After a call", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "The recording of the call that just ended, with Play, Share and Delete"
                // Seen, not heard: the status-bar icon is the point, a chime
                // after every call is not.
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        // The same channel CallMonitorService creates for "Call not recorded":
        // one place in Android's settings for everything that went wrong.
        manager.createNotificationChannel(
            NotificationChannel(PROBLEM_CHANNEL_ID, "Recording problems", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }
}

/**
 * The Delete button's two steps, and Keep. Not exported: it is reached only
 * through this app's own immutable PendingIntents, so the recording it is told
 * to delete is one this app put in the notification.
 */
class AfterCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == MissedCalls.ACTION_SEEN) {
            MissedCalls.markSeen(context)
            return
        }
        val call = AfterCall.Call.from(intent) ?: return
        when (intent.action) {
            AfterCall.ACTION_DELETE_ASK -> AfterCall.askDelete(context, call)
            // Back to how it was - or, if the everyday notification has been
            // switched off since, away.
            AfterCall.ACTION_DELETE_CANCEL -> if (!AfterCall.show(context, call)) AfterCall.cancel(context)
            AfterCall.ACTION_DELETE_CONFIRM -> {
                // Deleting is a ContentResolver call: off the main thread, and
                // kept alive past onReceive's return until it is done.
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        AfterCall.delete(context.applicationContext, call)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}
