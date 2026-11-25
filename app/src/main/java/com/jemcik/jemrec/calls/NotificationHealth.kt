package com.jemcik.jemrec.calls

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.edit
import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.capture.RecordingMode

/**
 * Whether the notifications JemRec needs can show, and the way to put them
 * back - for the home screen's notice (HomeScreen), which says what is off and
 * what that costs, rather than leaving it to be found out.
 *
 * Android's guide: where a channel setting "inhibits the intended behavior
 * for your app, you can suggest that the user change it"; and with the
 * permission refused, "gracefully degrade your app so that the user can
 * continue using your app" (developer.android.com, Notification channels;
 * Request runtime permissions). Until 1.2, notifications refused after setup
 * put JemRec back on setup's first screen, recordings and all out of reach,
 * while the recorder went on recording; and Ask first's question turned off
 * in Android's settings left every call unrecorded without a word. Turned
 * down rather than off - below Urgent, the only importance that "appears as
 * a heads-up notification" (Notification channels) - it waited in the shade
 * unseen mid-call, and nothing said so either; nor that Android 14 lets the
 * user take full-screen intents away (canUseFullScreenIntent, Android 14
 * behaviour changes), which keep the question on screen (RecordPrompt).
 */
object NotificationHealth {

    /** What is off, most costly first: one notice at a time. */
    enum class Off {
        /** All of JemRec's: the permission refused (Android 13 and later -
         *  turning them off in Android's settings takes it back) or the app's
         *  switch off (Android 12). */
        ALL,

        /** Ask first's question: with it off, no call is recorded. */
        ASK_FIRST,

        /** Ask first's question set below Urgent: it no longer pops up, and
         *  waits in the shade, where mid-call it is missed. */
        ASK_FIRST_QUIET,

        /** Full-screen intents taken away (Android 14 and later): the
         *  question pops up briefly, then waits in the shade. */
        ASK_FIRST_BRIEF,

        /** Recording problems: an unrecorded call, the recorder stopped. */
        PROBLEMS,
    }

    private const val KEY_DISMISSED = "notifications_off_dismissed"


    /** What Android says is off of JemRec's: three questions to its
     *  notification service, four from Android 14, so read off the main
     *  thread with the rest of a reading (Reading's SETTINGS) - Android's
     *  ANR guide counts a "slow binder call" among the causes (review,
     *  2026-11-18). A channel not made yet is not off - it is made at the
     *  app's start (JemRecApp). */
    data class Switches(
        val all: Boolean,
        val askFirst: Boolean,
        val problems: Boolean,
        /** The question's channel below Urgent, not off. */
        val askFirstQuiet: Boolean = false,
        /** Full-screen intents not allowed (Android 14 and later). */
        val askFirstBrief: Boolean = false,
    )

    /** Android's answer on full-screen intents (Android 14 and later). The
     *  JVM tests' notification service says no to every app: theirs. */
    @androidx.annotation.VisibleForTesting
    internal var fullScreenAllowed: (NotificationManager) -> Boolean = {
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE || it.canUseFullScreenIntent()
    }

    fun switches(context: Context): Switches {
        val manager = context.getSystemService(NotificationManager::class.java)
        // The question's channel asked once: two answers could disagree - it
        // lowered between them read as neither off nor quiet (review round 12).
        val question = manager.getNotificationChannel(RecordPrompt.CHANNEL_ID)?.importance
        return Switches(
            all = !manager.areNotificationsEnabled(),
            askFirst = question == NotificationManager.IMPORTANCE_NONE,
            problems = manager.getNotificationChannel(AfterCall.PROBLEM_CHANNEL_ID)?.importance ==
                NotificationManager.IMPORTANCE_NONE,
            askFirstQuiet = question != null && question != NotificationManager.IMPORTANCE_NONE &&
                question < NotificationManager.IMPORTANCE_HIGH,
            askFirstBrief = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                !fullScreenAllowed(manager),
        )
    }

    /** What is off, most costly first: Ask first's question off matters only
     *  in Ask first. Pure. */
    internal fun off(switches: Switches, askFirst: Boolean): Off? = offs(switches, askFirst).firstOrNull()

    /** Everything off that says something the ones above it do not, most
     *  costly first. All of them off holds every channel's cost; the
     *  question off or turned down below Urgent holds full-screen's - below
     *  IMPORTANCE_HIGH SystemUI shows no heads-up at all
     *  (CommonVisualInterruptionSuppressors' PeekNotImportantSuppressor), so
     *  "stays on screen for a minute" would be false there, its Turn on no
     *  help (review rounds 10, 11). Pure. */
    internal fun offs(switches: Switches, askFirst: Boolean): List<Off> =
        if (switches.all) listOf(Off.ALL) else listOfNotNull(
            Off.ASK_FIRST.takeIf { askFirst && switches.askFirst },
            Off.ASK_FIRST_QUIET.takeIf { askFirst && switches.askFirstQuiet },
            Off.ASK_FIRST_BRIEF.takeIf {
                askFirst && switches.askFirstBrief && !switches.askFirst && !switches.askFirstQuiet
            },
            Off.PROBLEMS.takeIf { switches.problems },
        )

    /**
     * The notice to show, or null: the most costly thing off that "Not now"
     * has not put away - which Ask first's question off never is, as no call
     * is recorded while it is off, nor all of them in Ask first. Each put
     * away stays away while it is off, and the next one down is shown: one
     * notice put away hid every one below it (review round 9). Once it is no
     * longer off its dismissal is forgotten, so the next time it is, it is
     * said - a dismissal kept past that hid a later return (reviews,
     * 2026-11-18). Preferences only, on top of what Android said
     * ([switches]): quick enough for the main thread, where a reading lands
     * and the mode is switched.
     */
    fun notice(context: Context, switches: Switches?, overlayAllowed: Boolean): Off? {
        switches ?: return null
        val askFirst = asksInNotifications(context, overlayAllowed)
        // Off in either mode, read from the switches themselves: a dismissal
        // outlives a switch to Ask first and back, its cause unchanged - and
        // one covered by a bigger notice (all off; the question turned down)
        // is kept, not forgotten while hidden and said again once the cover
        // goes (review round 11).
        val stillOff = listOfNotNull(
            Off.ALL.takeIf { switches.all },
            Off.ASK_FIRST.takeIf { switches.askFirst },
            Off.ASK_FIRST_QUIET.takeIf { switches.askFirstQuiet },
            Off.ASK_FIRST_BRIEF.takeIf { switches.askFirstBrief },
            Off.PROBLEMS.takeIf { switches.problems },
        ).map { it.name }.toSet()
        val prefs = Prefs.of(context)
        val dismissed = dismissed(context)
        val kept = dismissed intersect stillOff
        if (kept != dismissed) prefs.edit { if (kept.isEmpty()) remove(KEY_DISMISSED) else putString(KEY_DISMISSED, kept.joinToString(",")) }
        return offs(switches, askFirst).firstOrNull { !(dismissable(it, askFirst) && it.name in kept) }
    }

    /** Ask first, and its notification all it asks with: with its button over
     *  calls working (CallButton), the question's notification off or turned
     *  down leaves no call unasked. [overlayAllowed] as the last reading
     *  found it - Android's to answer, off the main thread. */
    private fun asksInNotifications(context: Context, overlayAllowed: Boolean): Boolean =
        RecordingMode.of(context) == RecordingMode.ON_DEMAND && !(CallButton.enabled(context) && overlayAllowed)

    private fun dismissed(context: Context): Set<String> =
        Prefs.of(context).getString(KEY_DISMISSED, null)?.split(',')?.filter { it.isNotEmpty() }?.toSet().orEmpty()

    /** Not Ask first's question off, nor all in Ask first: no call is
     *  recorded then. Turned down or brief, it still asks - in the shade -
     *  and may be the user's choice. */
    internal fun dismissable(off: Off, askFirst: Boolean): Boolean =
        off == Off.PROBLEMS || off == Off.ASK_FIRST_QUIET || off == Off.ASK_FIRST_BRIEF || (off == Off.ALL && !askFirst)

    fun dismiss(context: Context, off: Off) =
        Prefs.of(context).edit { putString(KEY_DISMISSED, (dismissed(context) + off.name).joinToString(",")) }

    /** Start fresh: nothing put away. */
    fun forget(context: Context) = Prefs.of(context).edit { remove(KEY_DISMISSED) }

    /** Whether the permission is what is missing (Android 13 and later), so
     *  asking for it - Android's own dialog - comes before its settings. */
    fun permissionMissing(context: Context): Boolean =
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Android's screen for what is off: the channel's own, or the app's
     *  notification settings (where the permission is too). */
    fun openSettings(context: Context, off: Off) {
        val channel = when (off) {
            Off.ALL, Off.ASK_FIRST_BRIEF -> null
            Off.ASK_FIRST, Off.ASK_FIRST_QUIET -> RecordPrompt.CHANNEL_ID
            Off.PROBLEMS -> AfterCall.PROBLEM_CHANNEL_ID
        }
        // Full-screen intents' own page, Android 14 and later: "the settings
        // page where users can grant the permission" (Android 14 behaviour
        // changes, ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).
        val fullScreen = if (off == Off.ASK_FIRST_BRIEF &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        ) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, android.net.Uri.fromParts("package", context.packageName, null))
        } else {
            null
        }
        val intents = listOfNotNull(
            fullScreen,
            channel?.let {
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, it)
            },
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        )
        for (intent in intents) {
            if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }
}
