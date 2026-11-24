package com.jemcik.jemrec.system

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.edit
import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.capture.RecordingMode

/**
 * Whether the user's Battery setting for JemRec is *Restricted*, and the way
 * to lift it - on home while it is set (HomeScreen), and once in a
 * notification when JemRec's process starts with it set, for whoever is not
 * opening JemRec (as FolderAccess and NotificationHealth do).
 *
 * Android's word for it: "If true, any work that the app tries to do will be
 * aggressively restricted while it is in the background. At a minimum, jobs
 * and alarms will not execute and foreground services cannot be started
 * unless an app activity is in the foreground. Note that these restrictions
 * stay in effect even when the device is charging."
 * (ActivityManager.isBackgroundRestricted). And from Android 13 the boot
 * broadcast waits until the app is started for another reason
 * (developer.android.com, Background optimization).
 *
 * What that does to JemRec, measured on the Android 12 and 17 AVDs,
 * 2026-11-18, on battery with JemRec's uid idle:
 * - Its jobs wait - every one of the keep-alive's showed
 *   "BACKGROUND_NOT_RESTRICTED" unsatisfied in JobScheduler's own dump
 *   (Android 17): the heartbeat and the runs that bring a stopped recorder
 *   back do not run, and it may stay down until JemRec is opened. At a
 *   restart: not measured - the shell's Restricted does not outlive one on
 *   the AVDs, neither the app op written out nor the restriction level set
 *   (2026-11-18); a recorder back 114 s after one, measured earlier, was the
 *   setting already gone.
 * - With exact call tracking off, both of a call's wakes were refused ("no
 *   service started"): the call recorded by the recorder, but saved, and
 *   told of, only once JemRec was opened. With tracking on a phone call
 *   was saved at once: Telecom binds JemRec's call service for the call
 *   (CallTracker), which brings it out of the background first. A messenger
 *   call has no such bind - CallTracker does not take self-managed calls -
 *   so it is held back as tracking off is (inferred, not measured). In Ask
 *   first a call whose question cannot be put is not recorded.
 *
 * JemRec cannot lift the setting itself, and does not go round it - the
 * shell could allow each wake past it, and that would be undoing the user's
 * choice: it says so, and opens App info, where the user can.
 */
object BatteryHealth {

    /** The words "Not now" put away (words): the notice back once they say
     *  more - "some calls" become "every call" with tracking turned off. */
    private const val KEY_DISMISSED = "battery_restricted_dismissed_words"
    private const val KEY_TOLD = "battery_restricted_told"

    /** Among the problems' ids (StoppedEarly's 37 and those before it). */
    private const val NOTIFICATION_ID = 38

    /** The Open App info button's PendingIntent (see post). */
    internal const val APP_INFO_REQUEST = 3801

    const val TITLE = "Battery use is Restricted"

    /** What it costs, in the card's and the notification's words. With
     *  exact call tracking off Android refuses every call's wake (measured,
     *  above): in Ask first no call can be asked about, in Record every call
     *  every call is saved only once JemRec is opened. With it on, a phone
     *  call goes through and a messenger call most likely does not
     *  (inferred) - "some". */
    fun words(askFirst: Boolean, trackingOff: Boolean): String {
        val cost = when {
            askFirst && trackingOff -> "no call can be asked about, so none is recorded"
            askFirst -> "calls it cannot ask about are not recorded"
            trackingOff -> "every call is saved only when you open it"
            else -> "some calls are saved only when you open it"
        }
        return "Android does not let a Restricted app run in the background: a recorder that stops " +
            "may stay stopped until you open JemRec, missing calls meanwhile, and $cost. " +
            "Allow its background use on App info's battery page."
    }

    /** Android's word, a question to its activity manager. */
    fun restricted(context: Context): Boolean =
        context.getSystemService(ActivityManager::class.java).isBackgroundRestricted

    /** [restricted], for a reading (Reading's SETTINGS) and each keep-alive
     *  run, off the main thread: lifted, what the notification said goes -
     *  not at the process's next start, which could be days, its words in
     *  the shade meanwhile - and home's "Not now" with it, so a later
     *  Restricted is said on home too. Lifted from the notification's own
     *  button, which does not clear it as a tap on it does, the keep-alive's
     *  next run finds it so: Android asked, as a run may come while it is
     *  still set, JemRec on the screen (reviews, 2026-11-18). */
    fun read(context: Context): Boolean = restricted(context).also { if (!it) forget(context) }

    private fun forget(context: Context) {
        takeBack(context)
        val prefs = Prefs.of(context)
        if (prefs.contains(KEY_DISMISSED)) prefs.edit { remove(KEY_DISMISSED) }
    }

    /** What the notification said, taken back: cancelled, and to be said
     *  again the next time it is due. */
    @Synchronized
    private fun takeBack(context: Context) {
        val prefs = Prefs.of(context)
        if (prefs.getBoolean(KEY_TOLD, false)) {
            prefs.edit { remove(KEY_TOLD) }
            com.jemcik.jemrec.calls.AfterCall.cancelProblem(context, NOTIFICATION_ID)
        }
    }

    /** Start fresh: nothing said, nothing put away. */
    fun forgetAll(context: Context) = forget(context)

    /**
     * Whether home says so: restricted ([restricted], as the last reading
     * found it; null before any), unless "Not now" put the notice away -
     * which it never is in Ask first, where a call may go unasked and so
     * unrecorded - and put away for its words then: once they say more, it
     * is said again. Once not restricted, a dismissal is forgotten, so the
     * next time it is, it is said. Home shows it only while JemRec is on, as
     * the notification (HomeScreen). Preferences only: quick enough for the
     * main thread, where a reading lands and the mode is switched.
     */
    fun notice(context: Context, restricted: Boolean?): Boolean {
        restricted ?: return false
        val askFirst = RecordingMode.of(context) == RecordingMode.ON_DEMAND
        val prefs = Prefs.of(context)
        if (!restricted) {
            if (prefs.contains(KEY_DISMISSED)) prefs.edit { remove(KEY_DISMISSED) }
            return false
        }
        return !(dismissable(askFirst) && prefs.getString(KEY_DISMISSED, null) == currentWords(context))
    }

    private fun currentWords(context: Context): String = words(
        askFirst = RecordingMode.of(context) == RecordingMode.ON_DEMAND,
        trackingOff = !com.jemcik.jemrec.calls.CallTracking.enabled(context),
    )

    fun dismissable(askFirst: Boolean): Boolean = !askFirst

    /**
     * Said once in a notification while the setting is Restricted, at a
     * start of JemRec's process (JemRecApp) - which then is rare: a phone
     * call Telecom binds JemRec for, a restart, an open - and as JemRec is
     * switched on (RecorderSwitch.turn). Once it is lifted what was said
     * goes, and the next time it is set it is said again. Only while JemRec
     * is on and set up, as BackgroundKill warns: switched off, or not set
     * up, nothing is held back that it records - and what was said is taken
     * back then, not left saying calls are being missed (review,
     * 2026-11-18). Off the main thread: a question to Android's activity
     * manager.
     */
    fun tellIfRestricted(context: Context) {
        if (!restricted(context)) {
            forget(context)
            return
        }
        if (!onAndSetUp(context)) {
            takeBack(context)
            return
        }
        val words = currentWords(context)
        if (toldOrPutAway(context, words)) return
        // Built before the lock - its PendingIntents ask Android's activity
        // manager - so Not now, on the main thread, waits on a notify at
        // most (review round 3: lock contention is an ANR cause,
        // developer.android.com).
        postOnce(context, build(context, words, quietly = false), words)
    }

    /** Told already, or put away with "Not now" for the [words] it would
     *  say: switched off, what was told is taken back and forgotten, the
     *  dismissal kept - so switched on again it was posted anew, with its
     *  sound, its tap opening home with the card still put away (review
     *  round 10). */
    private fun toldOrPutAway(context: Context, words: String): Boolean {
        val prefs = Prefs.of(context)
        if (prefs.getBoolean(KEY_TOLD, false)) return true
        val askFirst = RecordingMode.of(context) == RecordingMode.ON_DEMAND
        return dismissable(askFirst) && prefs.getString(KEY_DISMISSED, null) == words
    }

    /** JemRec on and set up: only then is it said. */
    private fun onAndSetUp(context: Context): Boolean =
        com.jemcik.jemrec.recorder.RecorderSwitch.isOn(context) && com.jemcik.jemrec.setup.Setup.isComplete(context)

    /** Said, under the lock the taking back holds: each preference the
     *  decision read is read again there, as since then another thread may
     *  have said it, Not now put it away, JemRec been switched off or the
     *  mode changed (review round 4); Battery Restricted, a question to
     *  Android, is left to the next look. */
    @androidx.annotation.VisibleForTesting
    @Synchronized
    internal fun postOnce(context: Context, notification: android.app.Notification, words: String) {
        if (toldOrPutAway(context, words) || !onAndSetUp(context) || currentWords(context) != words) return
        val seen = com.jemcik.jemrec.calls.AfterCall.notifyProblem(context, NOTIFICATION_ID, notification, "battery")
        // Told once - if it could be seen; else at the next start.
        if (seen) Prefs.of(context).edit { putBoolean(KEY_TOLD, true) }
    }

    /** The mode or call tracking changed: what the notification says, if it
     *  is still up, said again in the words that hold now - quietly, the
     *  same notification - not left saying "some calls" where it is every
     *  one (review, 2026-11-18). One the user put away stays away: re-posted
     *  it came back, and with its sound, as setOnlyAlertOnce holds only for
     *  one "already showing" (Notification.Builder; review round 9). Under
     *  tellIfRestricted's gate. Off the main thread. */
    fun refresh(context: Context) {
        if (!Prefs.of(context).getBoolean(KEY_TOLD, false)) return
        if (!restricted(context)) {
            forget(context)
            return
        }
        if (!onAndSetUp(context)) {
            takeBack(context)
            return
        }
        val showing = runCatching {
            context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.any { it.id == NOTIFICATION_ID }
        }.getOrDefault(false)
        if (!showing) return
        val words = currentWords(context)
        repost(context, build(context, words, quietly = true), words)
    }

    /** Said again in place, quietly - unless taken back, or put away with
     *  Not now, since refresh looked: Not now keeps it told and cancels it,
     *  and a repost after that brought it back, with its sound (review
     *  round 4). A swipe in that same moment, milliseconds, still brings it
     *  back with its sound: setOnlyAlertOnce holds only while it shows, and
     *  the platform builder has no setSilent (RecorderHealth's silent group
     *  is not used here for a race that narrow). */
    @androidx.annotation.VisibleForTesting
    @Synchronized
    internal fun repost(context: Context, notification: android.app.Notification, words: String) {
        val prefs = Prefs.of(context)
        if (!prefs.getBoolean(KEY_TOLD, false)) return
        val askFirst = RecordingMode.of(context) == RecordingMode.ON_DEMAND
        if (dismissable(askFirst) && prefs.getString(KEY_DISMISSED, null) == words) return
        // The words again, as postOnce: a mode changed twice in between left
        // the older words over the newer (review round 5).
        if (currentWords(context) != words) return
        com.jemcik.jemrec.calls.AfterCall.notifyProblem(context, NOTIFICATION_ID, notification, "battery")
    }

    /** The notification, built with no lock held. */
    private fun build(context: Context, words: String, quietly: Boolean): android.app.Notification {
        // The tap: home, whose card says it too; the button: App info.
        val home = android.app.PendingIntent.getActivity(
            context, 0, com.jemcik.jemrec.DeepLink.home(context), android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        // Through JemRec's own activity, which opens App info (openSettings):
        // an implicit intent in a notification's PendingIntent can be
        // answered by another app's activity that claims the same action
        // (CodeQL java/android/implicit-pendingintents, PR #105). Its own
        // request code: PendingIntents differ by intent without extras, and
        // every deep link is the same intent but for them.
        val appInfo = android.app.PendingIntent.getActivity(
            context, APP_INFO_REQUEST,
            com.jemcik.jemrec.DeepLink.intent(context, com.jemcik.jemrec.DeepLink.Kind.BATTERY_SETTINGS),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return com.jemcik.jemrec.calls.AfterCall.problem(context, TITLE, words, home, "Open App info" to appInfo)
            .setOnlyAlertOnce(quietly)
            .build()
    }

    /** "Not now": the notice put away for the words it says now - and the
     *  notification with it, still told: its tap opened home, where the card
     *  it pointed to was put away (review round 9). */
    @Synchronized
    fun dismiss(context: Context) {
        Prefs.of(context).edit { putString(KEY_DISMISSED, currentWords(context)) }
        com.jemcik.jemrec.calls.AfterCall.cancelProblem(context, NOTIFICATION_ID)
    }

    /** App info, whose battery page has the setting - Android has no public
     *  screen for that page alone. */
    fun openSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
