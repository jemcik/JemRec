package com.jemcik.jemrec.calls

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.CombinedVibration
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import androidx.core.content.edit
import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.RecordPromptActivity
import com.jemcik.jemrec.capture.CallMonitorService
import com.jemcik.jemrec.hex
import java.security.SecureRandom

/**
 * Asks, mid-call, whether to record this one.
 *
 * WHY THIS IS A NOTIFICATION AND NOT A DIALOG
 *
 * A dialog is an activity, and an app cannot start an activity from the
 * background. Holding a foreground service does not buy that back - Android 10
 * removed the exemption and Android 12 tightened what is left. During a call
 * this app is firmly in the background with the dialer on top, so the obvious
 * design is simply not available.
 *
 * There are two ways to get a real window there. SYSTEM_ALERT_WINDOW,
 * "display over other apps", allows one - the permission tapjacking needs, so
 * nobody is asked for it to be asked about a call: it is the Record button's
 * (CallButton), off until the user switches that on, and the question is a
 * notification with it too. A full-screen intent opens a window while the phone is asleep or
 * its lock screen is in front, and over a locked call's screen only where no
 * heads-up can show - in the calls measured, the call's own screen in front,
 * it came as a heads-up (RecordPromptActivity says how).
 *
 * A high-importance notification needs none of that. It arrives as a banner
 * over the dialer with the buttons on it, and if the banner is missed it is
 * still sitting in the shade, which is a surface this app already relies on and
 * has measured working over another app's screen.
 *
 * With the button over the call it is in the shade alone, no banner ([show]).
 *
 * It vibrates and makes no sound. Mid-call a sound is the one thing it must not
 * do, and a silent banner is easy to miss while the phone is at an ear. In a
 * call the vibration is JemRec's own ([vibrateInACall]).
 */
object RecordPrompt {

    private const val TAG = "JemRec"
    const val CHANNEL_ID = "jemrec.ask.v2"
    private const val LEGACY_CHANNEL_ID = "jemrec.ask"
    private const val NOTIFICATION_ID = 3
    private const val KEY_TICKET = "prompt_ticket"

    /** Two short ones. */
    private val VIBRATION = longArrayOf(0, 120, 80, 120)

    /** The Record on the call's own notification (CallMonitorService): a
     *  PendingIntent of its own beside the question's. */
    private const val KEPT_RECORD_REQUEST = 4

    /** The question's group with the button over the call, for its alert
     *  behaviour alone ([show]). */
    private const val QUIET_GROUP = "jemrec.ask.quiet"
    private val random = SecureRandom()

    /** What the prompt says about the call. A messenger call is named by
     *  its app: the daemon cannot tell which way one went, so incoming is
     *  always false for those and would say "outgoing" of every one. */
    internal fun callText(incoming: Boolean, app: String?): String =
        app?.let { "${VoipApps.byPackage(it)?.label ?: "Messenger"} call in progress." }
            ?: if (incoming) "Incoming call in progress." else "Outgoing call in progress."

    /** Its channel, made at the app's start too (JemRecApp), so it is there
     *  in Android's settings before the first call asks - to be found, and
     *  checked (NotificationHealth). */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        runCatching { manager.deleteNotificationChannel(LEGACY_CHANNEL_ID) }
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Record this call?",
                // HIGH is what makes it a banner rather than a line in the
                // shade, which matters when the user has a phone at their ear
                // and a few seconds to decide.
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                setSound(null, null)
                // A question about the call happening right now. Once it is
                // answered or the call ends there is nothing left to read, so
                // it has no business leaving a count on the launcher icon.
                setShowBadge(false)
                enableVibration(true)
                vibrationPattern = VIBRATION
                description = "Asked at the start of a call when automatic recording is off"
            }
        )
    }

    /**
     * Ask. [button] puts up the button over the call under the question's
     * ticket, and says whether it did (CallButton): with it there is no
     * banner, and the question waits in the shade. The two at once cluttered
     * the call's screen (the Honor, 2026-11-25; until then a banner of the
     * ordinary few seconds came too). Not on a phone asleep, where the lock
     * screen hides the button until something is over it - a call taken on a
     * headset, the phone in a pocket: there the question's window opens, as
     * it does without the button.
     *
     * Without a banner by its group's alert behaviour: a child of a group
     * whose summary is to do the alerting, with no summary there - what
     * AndroidX's setSilent does. SystemUI then shows no heads-up
     * (NotificationInterruptStateProviderImpl.canAlertCommon on Android 12 to
     * 14; CommonVisualInterruptionSuppressors, "suppressive group alert
     * behavior", on 15 and 16). On the one channel, so that there is none more
     * to find in Android's settings; where a phone's own SystemUI does not
     * mind it, the banner is the short one it was. The vibration is JemRec's
     * own either way ([vibrateInACall]).
     */
    fun show(context: Context, incoming: Boolean, app: String? = null, button: (ticket: String) -> Boolean = { false }) {
        val ticket = mintTicket(context)
        ensureChannel(context)
        val awake = context.getSystemService(PowerManager::class.java).isInteractive
        val overTheCall = button(ticket) && awake
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle("Record this call?")
            .setContentText(callText(incoming, app))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setCategory(Notification.CATEGORY_EVENT)
            // The banner otherwise vanishes after five seconds (SystemUI's
            // heads_up_notification_decay), not long enough to notice
            // mid-conversation. There is no API for that timeout - it belongs
            // to SystemUI - and a full-screen intent is the one supported
            // lever: a heads-up backed by one stays until it is answered, or,
            // with full-screen intents turned off for JemRec, a minute
            // (sticky_heads_up_notification_time). Measured on the Android 17
            // emulator in a call (2026-11-18): up all 100 s watched, and gone
            // between 57 and 60 s turned off. RecordPromptActivity: when its
            // window opens. Not with the button over the call, which stays.
            .apply {
                if (overTheCall) {
                    setGroup(QUIET_GROUP).setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
                } else {
                    setFullScreenIntent(fullScreen(context, incoming, ticket, app), true)
                }
            }
            .addAction(action(context, "Record", CallMonitorService.ACTION_RECORD_NOW, incoming, ticket))
            .addAction(action(context, "Not now", CallMonitorService.ACTION_DECLINE, incoming, ticket))
            // A tap anywhere else on it opens the question's window - which a
            // tap did by the full-screen intent alone, SystemUI's way where a
            // notification has no other (StatusBarNotificationActivityStarter),
            // so not at all with the button over the call. "Every notification
            // must respond to a tap" (Android, "Create a notification").
            .setContentIntent(fullScreen(context, incoming, ticket, app))
            // Deliberately dismissable: an unswipeable prompt during a call
            // would be an irritation with no upside. Swiped away it answers
            // nothing: Record is still on the call's own notification.
            .setOngoing(false)
            // Nor does that tap take it away: the window closed unanswered,
            // it still waits here.
            .setAutoCancel(false)

        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, builder.build())
        Log.i(TAG, "prompt: asking whether to record this call")
        vibrateInACall(context)
    }

    /**
     * The question's vibration in a call, as its channel has it.
     *
     * Android drops every notification's sound and vibration while telephony
     * is not idle - "callState" (NotificationAttentionHelper
     * .disableNotificationEffects, Android 16 QPR2) - and so this question's,
     * asked in a call by its nature: measured on the Honor (MagicOS 11,
     * Android 17, 2026-11-24), where a question posted 0.3 s after the answer
     * left nothing of JemRec's in the vibrator's log, and Gmail's and others'
     * outside a call are in it. Until 1.8 its KDoc said it vibrated. BCR does
     * this too, for the same reason.
     *
     * In any call, a messenger's too: one that goes through Telecom, as Google
     * Meet's do, makes telephony off-hook as a phone call does (measured on the
     * Honor, 2026-10-26; AOSP PhoneStateBroadcaster), and which do is not for
     * JemRec to know. Where Android does vibrate for the channel as well, the
     * later of the two takes over from the other - the same pattern, the same
     * usage (VibratorManagerService) - and the hand feels one.
     *
     * Only if its channel would: not turned off or down in Android's settings,
     * its vibration left on. As a notification's, so the phone's own rules
     * hold - silent mode, the notifications' vibration strength, Do Not
     * Disturb (VibrationSettings; ZenModeHelper.applyRestrictions).
     */
    private fun vibrateInACall(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(CHANNEL_ID) ?: return
        if (!manager.areNotificationsEnabled() || !channel.shouldVibrate() ||
            channel.importance < NotificationManager.IMPORTANCE_DEFAULT
        ) {
            return
        }
        // The manager's, not the Vibrator's own with audio attributes, which
        // Android 13 deprecated and which comes to this call in the end (its
        // bytecode on 13 and 16): one way on 12 to 17.
        val vibration = CombinedVibration.createParallel(
            VibrationEffect.createWaveform(channel.vibrationPattern ?: VIBRATION, -1),
        )
        val notification = VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_NOTIFICATION).build()
        runCatching { context.getSystemService(VibratorManager::class.java).vibrate(vibration, notification) }
            .onFailure { Log.w(TAG, "prompt: could not vibrate", it) }
    }

    private fun fullScreen(context: Context, incoming: Boolean, ticket: String, app: String?): PendingIntent =
        PendingIntent.getActivity(
            context,
            1,
            Intent(context, RecordPromptActivity::class.java)
                .putExtra(CallMonitorService.EXTRA_INCOMING, incoming)
                .putExtra(CallMonitorService.EXTRA_APP, app)
                .putExtra(CallMonitorService.EXTRA_TICKET, ticket)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Take the prompt down. Its ticket goes with it: a prompt that is not
     *  showing has no answer left to give. */
    fun dismiss(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        synchronized(this) { Prefs.of(context).edit { remove(KEY_TICKET) } }
    }

    /**
     * Whether an answer came from the prompt that is up right now. Spends the
     * ticket, so one prompt gives one answer.
     *
     * WHY THE ANSWER NEEDS A TICKET AT ALL
     *
     * The buttons go to CallMonitorService, and that service is exported so
     * the daemon can wake it. Exported means any installed app can send it an
     * intent, and before this a bare RECORD_NOW from anywhere started
     * recording the call in progress - in the mode where the user had said
     * they would decide each call themselves, and after they had said Not now.
     * Reported privately as GHSA-hf4v-7hwh-hx7w.
     *
     * The ticket is minted fresh for each prompt and travels only in this
     * app's own intents: the prompt's PendingIntents, whose extras no other
     * app can read, and the answer RecordPromptActivity sends. So only this
     * prompt's own buttons carry it, and the call's own notification's Record
     * ([keptRecord]), and only until it is answered, dismissed or the call
     * ends; a Not now puts a new one under that Record. Kept in preferences rather than in the service, so the
     * answer still lands if the service was stopped or the process was killed
     * while the prompt sat waiting.
     */
    fun claim(context: Context, presented: String?): Boolean = synchronized(this) {
        val prefs = Prefs.of(context)
        val expected = prefs.getString(KEY_TICKET, null)
        if (presented.isNullOrEmpty() || presented != expected) return false
        prefs.edit { remove(KEY_TICKET) }
        true
    }

    /** The ticket that would be taken now, or null: what the call's own
     *  notification puts under its Record. */
    fun ticket(context: Context): String? = synchronized(this) { Prefs.of(context).getString(KEY_TICKET, null) }

    /**
     * The question answered, and the call not recorded - Not now, or a Record
     * that did not take: it can still be, to its end, from the call's own
     * notification, under a ticket of its own. The one the question carried is
     * spent, so the question's Record - in a listener's copy of it - no longer
     * starts one: the user said no to that. None where one is live: another
     * call's question, put up meanwhile, whose buttons carry it.
     */
    fun keepRecord(context: Context) {
        synchronized(this) { if (ticket(context) == null) mintTicket(context) }
    }

    /** Calls [over] once [ticket] is no longer the one live - answered, or the
     *  call over - or at once if it is not now. Returns the way to stop, which
     *  the caller holds (Prefs.watch). */
    fun watch(context: Context, ticket: String?, over: () -> Unit): () -> Unit {
        // Registered first: gone between a look and the watch, it was missed.
        val stop = Prefs.watch(context, KEY_TICKET) { if (ticket(context) != ticket) over() }
        if (ticket(context) != ticket) over()
        return stop
    }

    /** The call's own notification's Record (CallMonitorService). */
    fun keptRecord(context: Context, ticket: String): Notification.Action =
        action(context, "Record", CallMonitorService.ACTION_RECORD_NOW, incoming = false, ticket, KEPT_RECORD_REQUEST)

    private fun mintTicket(context: Context): String = synchronized(this) {
        val ticket = hex(ByteArray(16).also { random.nextBytes(it) })
        Prefs.of(context).edit { putString(KEY_TICKET, ticket) }
        ticket
    }

    /**
     * The buttons target the monitor service directly.
     *
     * getForegroundService, not getService: the tap arrives while the app is in
     * the background, and a notification action is one of the few things that
     * is allowed to start a foreground service from there. The service is
     * already running, so this is really just a delivery, but asking for the
     * weaker form would be relying on that staying true.
     */
    private fun action(
        context: Context,
        label: String,
        action: String,
        incoming: Boolean,
        ticket: String,
        request: Int = action.hashCode(),
    ): Notification.Action {
        val intent = Intent(context, CallMonitorService::class.java)
            .setAction(action)
            .putExtra(CallMonitorService.EXTRA_INCOMING, incoming)
            .putExtra(CallMonitorService.EXTRA_TICKET, ticket)
        val pending = PendingIntent.getForegroundService(
            context,
            request,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(
            android.R.drawable.ic_btn_speak_now, label, pending,
        ).build()
    }
}
