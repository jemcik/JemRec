package com.jemcik.jemrec.capture

import android.app.ActivityManager
import android.app.ApplicationStartInfo
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.content.edit
import com.jemcik.jemrec.DeepLink
import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.ui.AutoStart
import com.jemcik.jemrec.ui.Setup

/**
 * Telling the user when this phone's own power management has STOPPED JemRec in
 * the background. The recorder runs on as shell, but a stopped JemRec cannot
 * start it again once it goes - after an unplug or a restart - so calls go
 * unrecorded until the app is next opened.
 *
 * Measured on the Honor (2026-10-25): with App launch on "Manage
 * automatically", unplugging and turning the screen off had the system kill
 * JemRec eight seconds later, mid-service, and then mark it stopped - which
 * cancels its keep-alive job, so nothing brought the recorder back. On "Manage
 * manually" the recorder returned on its own in fifteen seconds. JemRec cannot
 * read or change that setting, so the most it can do is notice the kill and ask
 * the user to change it - which is what this does, two ways: the daemon spots
 * it while the app is dead and wakes the app to say so (CallMonitorService),
 * and the app checks its own exit records whenever its process starts and
 * whenever it opens (checkOwnRecords) - from Android 15 with its own start
 * record too, which says whether it began stopped. Both share one "last
 * warned" time so a kill is reported once.
 *
 * The DECISION is pure and lives in [systemKill] so it can be tested off the
 * phone; everything around it is the plumbing that reads Android's records and
 * posts the notification.
 */
object BackgroundKill {

    private const val TAG = "JemRec"
    internal const val KEY_LAST = "background_kill_warned_ms"
    private const val NOTIFICATION_ID = 35

    /** Start records to ask for (startedStopped): more than the platform
     *  keeps - 16 on the Honor - so this process's own cold start is among
     *  them however many warm starts came after it. */
    private const val START_RECORDS = 64

    /** Before anything was ever warned, how old a kill the daemon's wake may
     *  report. The daemon looks every minute and wakes at once, so a kill it
     *  has just seen is minutes old; an old one presented as new - its dump
     *  stamp re-printed in a new time zone - is not news. */
    private const val RECENT_MS = 60L * 60 * 1000

    /**
     * One past process exit, reduced to what the decision needs: android.app.
     * ApplicationExitInfo's reason, timestamp and importance - how busy the
     * process was when it ended - as plain values so the rule has no Android
     * in it.
     */
    data class Exit(val reason: Int, val timestampMs: Long, val importance: Int)

    // ApplicationExitInfo reason codes (public, API 30+). A vendor power
    // manager killing a background app comes through as REASON_OTHER - the
    // Honor's record read "reason=13 (OTHER KILLS BY SYSTEM) ...
    // iAwareR[SmartClean]". REASON_FREEZER is the system reaping a frozen app,
    // the same family. Deliberately NOT treated as this: the user's own Force
    // stop or swipe-away (REASON_USER_REQUESTED = 10) and an app update
    // (REASON_PACKAGE_STATE_CHANGE = 15, REASON_PACKAGE_UPDATED = 16), which
    // are expected and not the phone fighting the recorder.
    const val REASON_OTHER = 13
    const val REASON_FREEZER = 14

    /** ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED: an exit at or
     *  above it found the process idle in the background. */
    const val IMPORTANCE_CACHED = 400

    /**
     * The newest background stop after [sinceMs] worth telling the user
     * about, or null if there is none. "After sinceMs" is what keeps one stop
     * from being reported twice: the caller advances sinceMs to the reported
     * stop's time.
     *
     * A system kill counts only if JemRec was WORKING when it came -
     * importance below [IMPORTANCE_CACHED]. Reason 13 is also how Android and
     * the vendors reclaim an idle app, which JemRec is between calls by
     * design, and that costs it nothing: the recorder runs on and wakes it for
     * the next call. Measured on the Honor (2026-10-25): 2,015 of its 2,062
     * reason-13 records were of idle apps, none of them left stopped; JemRec's
     * harmful kill was at 125, a foreground service.
     *
     * An idle kill counts too when it LEFT JEMREC STOPPED ([leftStopped]),
     * which costs its jobs and the boot broadcast, so nothing would bring a
     * recorder back. That lends to the newest exit alone, as in the daemon's
     * rule (Checks.backgroundKillStamp): the user's own Force stop leaves the
     * app stopped too, and must not make an older idle kill look like one.
     * [vouched] is the daemon's wake, which has judged with the stopped flag
     * already, so any system kill counts.
     */
    fun systemKill(
        exits: List<Exit>,
        sinceMs: Long,
        vouched: Boolean = false,
        leftStopped: Boolean = false,
    ): Exit? {
        val newest = exits.maxByOrNull { it.timestampMs }
        return exits
            .filter { it.timestampMs > sinceMs && (it.reason == REASON_OTHER || it.reason == REASON_FREEZER) }
            .filter { vouched || it.importance < IMPORTANCE_CACHED || (leftStopped && it === newest) }
            .maxByOrNull { it.timestampMs }
    }

    /**
     * One kept process start, reduced to what [ownStartWasStopped] needs:
     * android.app.ApplicationStartInfo's pid, whether it started a process
     * rather than an activity in a running one, when it was launched (one
     * clock for all of them; null when not filled in yet), and
     * wasForceStopped.
     */
    data class Start(val pid: Int, val processStart: Boolean, val launchedNs: Long?, val wasStopped: Boolean)

    /**
     * Whether THIS process began with JemRec stopped. Its own start is the
     * newest cold one - JemRec has one process, so no other can start while
     * it runs - and it must carry this process's pid. Matching on the pid
     * alone is not enough: pids wrap within hours on the Honor (30748 at
     * 18:14, 4354 at 15:45, 21597 at 13:52 on 2026-10-25), so a kept start
     * from days ago can share it. Nor is the newest record enough: an
     * activity opened in a process already running adds a warm start, never
     * marked, on top of the cold one.
     *
     * Read as the process starts (JemRecApp), its own record may still be in
     * progress, so a start counts as a process start unless it says warm or
     * hot, and one with no launch time yet is taken as the newest: every
     * finished start on the Honor carried one. A pid of 0 is one not filled
     * in, not another process's: measured on the Honor (2026-10-25 19:24),
     * the recorder's wake of a stopped JemRec left its start record at pid=0,
     * SERVICE, COLD, wasForceStopped=true - and matching the pid alone read
     * that as "not stopped".
     */
    internal fun ownStartWasStopped(starts: List<Start>, myPid: Int): Boolean {
        val own = starts.filter { it.processStart }.maxByOrNull { it.launchedNs ?: Long.MAX_VALUE } ?: return false
        return (own.pid == myPid || own.pid == 0) && own.wasStopped
    }

    /**
     * Whether this process began with JemRec stopped: Android 15's record of
     * its own starts (ApplicationStartInfo.wasForceStopped, API 35). Opening
     * the app clears the stopped flag, so this record is the only way it can
     * tell afterwards. Measured on the Honor (2026-10-25): the launcher's cold
     * start at about 14:00, eight minutes after the 13:52 iAwareR kill and
     * with no exit between, was marked wasForceStopped=true; the starts after
     * app updates were not. False before Android 15, where the gap stays (see
     * ARCHITECTURE, "Stopped in the background").
     */
    private fun startedStopped(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return false
        val am = context.getSystemService(ActivityManager::class.java) ?: return false
        return runCatching {
            val starts = am.getHistoricalProcessStartReasons(START_RECORDS).map {
                Start(
                    pid = it.pid,
                    processStart = it.startType != ApplicationStartInfo.START_TYPE_WARM &&
                        it.startType != ApplicationStartInfo.START_TYPE_HOT,
                    launchedNs = it.startupTimestamps[ApplicationStartInfo.START_TIMESTAMP_LAUNCH],
                    wasStopped = it.wasForceStopped(),
                )
            }
            ownStartWasStopped(starts, Process.myPid())
        }.getOrElse {
            Log.w(TAG, "backgroundkill: could not read start records", it)
            false
        }
    }

    /** This process's recent exits, as plain [Exit]s. Empty if unavailable. */
    private fun recentExits(context: Context): List<Exit> {
        val am = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        // max=0 means "all it keeps" (up to the platform cap); pid=0, any pid.
        return runCatching {
            am.getHistoricalProcessExitReasons(context.packageName, 0, 0)
                .map { Exit(it.reason, it.timestamp, it.importance) }
        }.getOrElse {
            Log.w(TAG, "backgroundkill: could not read exit reasons", it)
            emptyList()
        }
    }

    /**
     * At every start of JemRec's process (JemRecApp) and every open
     * (MainActivity): warn once if the system has killed JemRec in the
     * background since the last warning - while it was working, or (Android
     * 15 and later, startedStopped) leaving it stopped. Silent where there is
     * no vendor screen to fix it (a Pixel, the emulator), and on first run it
     * only records a baseline so a kill from before this version is never
     * reported. Binder calls (the vendor screens, the exit and start
     * records): both callers run it off the main thread. [where] names the
     * caller in the debug build's revive log.
     */
    fun checkOwnRecords(context: Context, where: String) = warnNewKill(context, fromDaemon = false, where)

    /**
     * The daemon saw a background stop while the app was dead and woke us to
     * say so (CallMonitorService). We read our OWN exit records here - the
     * daemon carries only the news that there is something to look at, judged
     * with the stopped flag the running app cannot read (systemKill's vouched)
     * - and warn about the
     * newest kill not already reported, sharing KEY_LAST with checkOwnRecords so
     * the same kill is never told twice.
     */
    fun warnFromDaemon(context: Context) = warnNewKill(context, fromDaemon = true, "woken by the recorder")

    /**
     * Kills after this time count, or null when none do yet and a baseline is
     * to be taken. The two callers differ only before anything was ever warned
     * ([lastWarnedMs] null): the app's open takes a baseline and says nothing,
     * so a kill from before this version is never reported; the daemon's wake,
     * which comes because a kill was just seen, counts the last [RECENT_MS].
     */
    internal fun countFrom(lastWarnedMs: Long?, fromDaemon: Boolean, nowMs: Long): Long? = when {
        lastWarnedMs != null -> lastWarnedMs
        fromDaemon -> nowMs - RECENT_MS
        else -> null
    }

    /**
     * Both of the above. Only while a stop can cost a call ([watching]). The
     * exit records first - one call into the system -
     * and the vendor screens (up to fifteen) only when there is something to
     * tell. Synchronized: the open check and the daemon's wake each run on a
     * thread of their own, and both read and advance KEY_LAST.
     */
    @Synchronized
    private fun warnNewKill(context: Context, fromDaemon: Boolean, where: String) {
        if (!watching(context)) return
        val prefs = Prefs.of(context)
        val exits = recentExits(context)
        val lastWarned = if (prefs.contains(KEY_LAST)) prefs.getLong(KEY_LAST, 0L) else null
        val since = countFrom(lastWarned, fromDaemon, System.currentTimeMillis()) ?: run {
            val baseline = exits.maxOfOrNull { it.timestampMs } ?: System.currentTimeMillis()
            prefs.edit { putLong(KEY_LAST, baseline) }
            return
        }
        // The daemon's wake has judged with the stopped flag already.
        val leftStopped = !fromDaemon && startedStopped(context)
        val kill = systemKill(exits, since, vouched = fromDaemon, leftStopped = leftStopped)
        val newest = exits.maxByOrNull { it.timestampMs }
        ReviveLog.note(context, "backgroundkill: $where; " +
            "newest exit ${newest?.let { "reason ${it.reason} importance ${it.importance} at ${it.timestampMs}" }}; " +
            "started stopped ${if (fromDaemon) "not read (the recorder judged)" else leftStopped}; " +
            (if (kill != null) "warning of ${kill.timestampMs}" else "nothing new"))
        if (kill == null) return
        // Advanced before the screen check, so a phone with no vendor screen
        // (nothing to point at, so no warning) does not look again every time.
        prefs.edit { putLong(KEY_LAST, kill.timestampMs) }
        val screen = AutoStart.screen(context) ?: return
        Log.i(TAG, "backgroundkill: system killed JemRec at ${kill.timestampMs} (reason ${kill.reason}, $where); warning")
        post(
            context, screen,
            "JemRec was stopped in the background",
            "This phone stopped JemRec in the background, so a call could go unrecorded.",
        )
    }

    /**
     * The phone did not kill JemRec but FROZE it while it was restarting the
     * recorder after a cable - the commoner harm, measured on the Honor
     * (2026-10-25): two tries, frozen both times, never killed, and in one the
     * recorder stayed down 8.5 minutes until the screen was touched. A freeze
     * leaves no exit record, so [checkOwnRecords] cannot see it; CableWatch times
     * its own restart and calls this when it took longer than a restart can
     * (CableWatch.heldBack). Each stall is its own event, so no dedup here.
     */
    fun warnHeldBack(context: Context, tookMs: Long) {
        // The restart runs on through a STOP (CableWatch.bringBack), so the
        // switch may have gone off while the phone held it.
        if (!watching(context)) return
        val screen = AutoStart.screen(context) ?: return
        Log.i(TAG, "backgroundkill: the phone held JemRec back ${tookMs / 1000} s; warning")
        post(
            context, screen,
            "JemRec was held back in the background",
            "This phone kept JemRec from restarting the recorder for about " +
                "${heldBackMinutes(tookMs)} min, so a call could go unrecorded.",
        )
    }

    /** Whether a stop can cost a call at all: recording switched on and
     *  setup finished. With the switch off, "a call could go unrecorded"
     *  would be false. */
    private fun watching(context: Context) = RecorderSwitch.isOn(context) && Setup.isComplete(context)

    /** Whole minutes for the warning, rounded, and never "0 min": the shortest
     *  stall that warns is over CableWatch.AWAKE_MS, a minute and a half. */
    internal fun heldBackMinutes(tookMs: Long): Long = maxOf(1L, (tookMs + 30_000L) / 60_000L)

    /**
     * One notification for both causes - a stop and a freeze ask the same of
     * the user - so the newer simply replaces the older. [what] says what
     * happened; the fix follows in this phone's words, as the setup card's own
     * two steps: the button, labelled as [screen], then what to do there.
     */
    private fun post(context: Context, screen: AutoStart.Screen, title: String, what: String) {
        val open = PendingIntent.getActivity(
            context, 3402,
            DeepLink.intent(context, DeepLink.Kind.BACKGROUND_SETTINGS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = "$what Tap ${screen.label} below. ${screen.instruction}"
        // Quiet when it only updates - the held-back warning is re-posted
        // with the whole time once the recorder is back.
        val notification = AfterCall.problem(context, title, text, open, screen.label)
            .setOnlyAlertOnce(true)
            .build()
        AfterCall.notifyProblem(context, NOTIFICATION_ID, notification, "backgroundkill")
    }

    fun cancel(context: Context) = AfterCall.cancelProblem(context, NOTIFICATION_ID)

    /**
     * The recorder switch went from off to on (RecorderSwitch.set): a stop
     * that happened while it was off cost no call, so only stops after now
     * count. Without this, the first open after switching back on reported a
     * kill from the days it was off, as "a call could go unrecorded".
     */
    @Synchronized
    fun recordingSwitchedOn(context: Context) {
        val prefs = Prefs.of(context)
        val now = System.currentTimeMillis()
        if (prefs.getLong(KEY_LAST, 0L) < now) prefs.edit { putLong(KEY_LAST, now) }
    }

    /** Start fresh (Setup.reset): no warning left in the shade for a setup the
     *  user is about to be taken through again, and the next open starts a new
     *  baseline. */
    fun forget(context: Context) {
        cancel(context)
        Prefs.of(context).edit { remove(KEY_LAST) }
    }
}
