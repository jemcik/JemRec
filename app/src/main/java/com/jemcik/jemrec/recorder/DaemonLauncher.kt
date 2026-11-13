package com.jemcik.jemrec.recorder

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.jemcik.jemrec.adb.AdbTransport
import com.jemcik.jemrec.answering.AnsweringMachine
import com.jemcik.jemrec.calls.CallTracking
import com.jemcik.jemrec.calls.VoipApps
import com.jemcik.jemrec.capture.CallMonitorService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Whether the recorder is up and the build this APK carries, and starting
 * it: the jar staged and copied, an out-of-date recorder asked to make way
 * (or, wedged, killed), the new one spawned over ADB and waited for. Over the
 * session a revive opens (Revive), under the recorder's lock
 * (RecorderLifecycle).
 */
object DaemonLauncher {

    private const val TAG = "JemRec"

    private const val ASSET = "jemrec-capture.jar"
    private const val DEVICE_PATH = "/data/local/tmp/jemrec-capture.jar"
    private const val LOG_PATH = "/data/local/tmp/jemrec-capture.out"
    private const val MAIN_CLASS = "com.jemcik.jemrec.shell.Main"
    private const val PORT = DaemonClient.PORT

    /**
     * Kill the daemon by its class name WITHOUT killing the shell that runs the
     * command. `pkill -f` matches against every process's full command line -
     * including the `sh -c "pkill -f com.jemcik..."` that adbd spawns to run
     * this, whose own command line contains the class name. Measured: the
     * shell died and nothing after the pkill ran. The bracket makes the
     * pattern a regex that matches "com.jemcik..." in the daemon's arguments
     * but not the literal "[c]om.jemcik..." in the shell's.
     */
    private const val PKILL = "pkill -f '[c]om.jemcik.jemrec.shell.Main'"

    /** A ping is lost to a busy phone often enough that one "no" means nothing. */
    // Sized for a throttled CPU, not a plugged-in one. A loopback round trip
    // is sub-millisecond when the phone is awake; the whole budget here exists
    // because it is not always awake, and because concluding "dead" wrongly
    // used to cost a working recorder.
    private const val PING_ATTEMPTS = 5
    private const val PING_RETRY_GAP_MS = 600L

    /** Up to three seconds for a pkill-ed daemon to let go of the port. */
    private const val PORT_FREE_ATTEMPTS = 20
    private const val PORT_FREE_GAP_MS = 150L

    /**
     * How long a BUSY is given to come true. A daemon really recording a call
     * lets go within a couple of seconds of the call's audio being released -
     * see retire() - so three BUSYs across twelve seconds with no call in
     * progress at any point is not a call.
     */
    private const val WEDGE_RECHECKS = 2
    private const val WEDGE_RECHECK_MS = 6_000L

    /**
     * Fires when a daemon has just been started, for whoever is showing the
     * recorder's state. Revival runs out of the screen's sight - the keep-alive
     * job, woken by the Wi-Fi watcher - and the header re-read the world only
     * on resume. Measured: after a reboot with no Wi-Fi the header said "needs
     * Wi-Fi", Wi-Fi came, the job revived the daemon, and the header went on
     * saying "needs Wi-Fi" until the app was left and reopened.
     */
    private val _changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val changed: SharedFlow<Unit> = _changed.asSharedFlow()

    /**
     * Is OUR daemon alive AND answering?
     *
     * Deliberately a ping and not merely a successful connect. Before the
     * daemon understood a command byte, asking this question started a
     * recording session - it span up an AudioRecord on the voice-call source
     * and tore it down again - so a watchdog polling every few minutes would
     * have been quietly churning audio sessions all day.
     *
     * A ping also answers a better question than an open port does: something
     * has to be running the accept loop and able to reply. And since the
     * handshake, it answers a better question still: whatever replied knows
     * this app's token, so it is this install's daemon - not one left behind
     * by a previous install, and not one from before the handshake existed.
     *
     * Asked over the socket rather than with `ps` or `ss`, because those need
     * ADB - and needing ADB to find out whether ADB is still needed is the
     * dependency this whole design exists to remove.
     */
    suspend fun isRunning(context: Context): Boolean = withContext(Dispatchers.IO) { DaemonClient.probe(context) != null }

    /**
     * The same question, asked properly before acting on a "no".
     *
     * One ping is a fine health check and a terrible death certificate. The
     * loopback round trip is normally sub-millisecond, but it runs on a phone
     * that swaps, throttles and freezes background work, so a single short
     * connect can time out against a daemon that is perfectly alive.
     *
     * That false negative was expensive. ensureRunning() answers a "no" by
     * pkill-ing the daemon and starting another, so one unlucky ping killed a
     * working recorder - and if the port was not free 300ms later the
     * replacement died on EADDRINUSE and left NOTHING running. Measured, not
     * guessed: the daemon's own log recorded that bind failure interleaved with
     * the pings the previous instance was still happily answering.
     *
     * So anything destructive asks this instead, and only five failures in a
     * row count as gone.
     */
    suspend fun isRunningConfirmed(context: Context): Boolean =
        withContext(Dispatchers.IO) { probeConfirmed(context) != null }

    /** probe(), asked PING_ATTEMPTS times before concluding nothing is there. */
    internal suspend fun probeConfirmed(context: Context): DaemonClient.Answer? {
        repeat(PING_ATTEMPTS) { attempt ->
            DaemonClient.probe(context)?.let { return it }
            if (attempt < PING_ATTEMPTS - 1) delay(PING_RETRY_GAP_MS)
        }
        return null
    }

    /** Our daemon's answer to an authenticated ping, or null if it is not answering. */
    internal suspend fun answer(context: Context): DaemonClient.Answer? = withContext(Dispatchers.IO) { DaemonClient.probe(context) }

    @Volatile
    private var bundled: String? = null

    /** The build of the jar this APK carries, in the daemon's own terms. */
    internal fun bundledBuild(context: Context): String = bundled ?: run {
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open(ASSET).use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        digest.digest().take(4).joinToString("") { "%02x".format(it) }.also { bundled = it }
    }

    /** Whether the daemon that gave [answer] runs the jar this APK carries.
     *  A daemon that cannot say its build is left alone rather than churned:
     *  one that says none, from before the build was said, and one that
     *  says "unknown", which cannot read its own jar - the recorder's own
     *  word on it (Main.BUILD) is that the app takes that for current. Taken
     *  for out of date, it was replaced at every revive that had a session,
     *  and its replacement, reading the same jar, said the same. */
    internal fun upToDate(context: Context, answer: DaemonClient.Answer): Boolean =
        answer.build == null || answer.build == DaemonClient.UNKNOWN_BUILD || answer.build == bundledBuild(context)

    /**
     * Put the borrowed door back once we are through it: close the ADB session.
     *
     * ADB is a bootstrap here, not a transport: it exists to start this daemon,
     * and once the daemon is up it serves loopback and needs no ADB session, no
     * mDNS and no Wi-Fi. Wireless debugging itself stays on: switching it off
     * here killed the recorder just started, as below.
     *
     * Deliberately does nothing when the daemon is NOT running: that is exactly
     * when the way back in still has to be there.
     */
    suspend fun standDown(context: Context) {
        if (!isRunning(context)) return

        // IT USED TO TURN WIRELESS DEBUGGING OFF HERE. THAT KILLED THE DAEMON.
        //
        // Writing adb_wifi_enabled=0 restarts adbd, and this ROM kills
        // everything adbd spawned when it does - so the tidying-up destroyed
        // the recorder it had just started, seconds after starting it. Then the
        // keep-alive noticed the recorder was gone, re-armed Wireless debugging
        // to fix it (Android posting its notification each time), started a new
        // daemon, tidied up again, and killed that one too. Several times a
        // minute, forever, and only ever off USB.
        //
        // Measured, not deduced. A daemon, a shell script and a bare `sleep`,
        // all setsid-detached and all reparented to init, died at the same
        // instant adb_wifi_enabled went to 0; the log of the watcher stops mid
        // second. The comment above the setsid call says adbd "tears down the
        // whole process GROUP" and that a new session escapes it. A new session
        // does not: the kill is by cgroup, and shell cannot leave its cgroup.
        // /sys/fs/cgroup/cgroup.procs is system:system, shell is uid 2000 and
        // not in that group, and every tasks file on the device is read-only to
        // us. There is nowhere to escape to.
        //
        // So the session is closed and the setting is left alone. The cost is
        // real and worth stating: Wireless debugging stays on, which means
        // adbd keeps listening on whatever network the phone joins, and
        // Android keeps its own notification up. That is the exposure this
        // function was written to remove - but it was removing it by breaking
        // the recorder, and a call recorder that does not record is not a
        // safer call recorder.
        AdbTransport.close()
        Log.i(TAG, "daemon: up and serving loopback, ADB session closed")
    }

    /**
     * Whether a call may be up, judged by the audio mode: the signal the
     * daemon itself trusts to end a recording (CallWatch.audioInCall), and
     * one this app can read without READ_PHONE_STATE, which it does not hold.
     * Anything but MODE_NORMAL counts - ringing, a call, a VoIP call,
     * screening - because getting this wrong in that direction only ever
     * leaves a daemon alone.
     */
    internal fun inCall(mode: Int): Boolean = mode != AudioManager.MODE_NORMAL

    internal fun callInProgress(context: Context): Boolean {
        val audio = context.getSystemService(AudioManager::class.java) ?: return true
        return inCall(audio.mode)
    }

    /** How asking an out-of-date daemon to make way turned out. */
    private enum class Retirement { QUIT, RECORDING, REFUSED, WEDGED }

    /**
     * ASK AN OUT-OF-DATE DAEMON TO MAKE WAY - AND DO NOT BELIEVE "BUSY" FOREVER.
     *
     * A daemon refuses to quit while it records a call, and that is right: a
     * call is worth more than an update being prompt. But it judges "recording"
     * by its own state, and that state can be stuck. Every build before the
     * call watch was held in a field (see CallWatch.callWatch) could lose its watch
     * to garbage collection - and one that lost it MID-CALL never hears that
     * call end, so its recording never stops and it answers BUSY to every quit
     * for the rest of its life. The update that fixes the bug could then never
     * replace the daemon that has it.
     *
     * A real BUSY cannot outlast its call by much: the daemon ends a recording
     * within about two seconds of telecom releasing the call's audio mode. So a
     * BUSY with no call in progress is asked again, twice, six seconds apart,
     * and only a daemon still saying BUSY with the audio mode normal at every
     * look is called wedged. Any sign of a call at any point and it is left
     * alone, as before.
     */
    private suspend fun retire(context: Context): Retirement {
        var reply = DaemonClient.quitReply(context)
        var asked = 1
        while (reply == DaemonClient.QuitReply.BUSY) {
            if (callInProgress(context)) return Retirement.RECORDING
            if (asked > WEDGE_RECHECKS) return Retirement.WEDGED
            delay(WEDGE_RECHECK_MS)
            if (callInProgress(context)) return Retirement.RECORDING
            reply = DaemonClient.quitReply(context)
            asked++
        }
        return if (reply == DaemonClient.QuitReply.BYE) Retirement.QUIT else Retirement.REFUSED
    }

    internal suspend fun ensureRunningLocked(context: Context): Result<Unit> {
        var retired = false
        val running = probeConfirmed(context)
        ReviveLog.note(context, "start: old recorder ${if (running != null) "answers" else "does not answer"}")
        if (running != null) {
            if (upToDate(context, running)) {
                Log.i(TAG, "daemon: already running")
                return Result.success(Unit)
            }
            if (!AdbTransport.isConnected) {
                Log.i(
                    TAG,
                    "daemon: running build ${running.build}, this app carries ${bundledBuild(context)}; " +
                        "replaced when there is a session",
                )
                return Result.success(Unit)
            }
            // Asked, not killed: it says BUSY while recording a call, and a
            // call is worth more than the update being prompt. The next
            // revive asks again - unless the BUSY cannot be true; see retire().
            Log.i(TAG, "daemon: replacing build ${running.build} with ${bundledBuild(context)}")
            when (retire(context)) {
                Retirement.QUIT -> retired = true
                Retirement.RECORDING -> {
                    Log.i(TAG, "daemon: it is recording a call, keeping the old build for now")
                    return Result.success(Unit)
                }
                Retirement.REFUSED -> {
                    Log.i(TAG, "daemon: it would not quit, keeping the old build for now")
                    return Result.success(Unit)
                }
                Retirement.WEDGED -> {
                    Log.w(
                        TAG,
                        "daemon: build ${running.build} still says BUSY with no call in progress - " +
                            "it lost the end of a call and will never quit; retiring it over ADB",
                    )
                    exec(PKILL)
                    // Its call never ended for the app either: CALL_ENDED was
                    // never sent, so a "Recording call" notification from it
                    // may still be up. Its file is collected like any orphan,
                    // by the reconcile that follows a revive.
                    if (CallMonitorService.running) CallMonitorService.stop(context)
                    retired = true
                }
            }
        }
        return runCatching {
            check(AdbTransport.isConnected) {
                "ADB is not connected, so the daemon cannot be started. " +
                    "Connect once over Wi-Fi; after that recording needs no network."
            }

            // ONLY KILL SOMETHING THAT IS ACTUALLY IN THE WAY.
            //
            // This killed unconditionally, and the reasoning was sound for the
            // case it was written for: a stale daemon from a previous version
            // owns port 28472 while failing the health check, so a fresh one
            // would fail to bind and exit, and the repair would loop against a
            // port that is never free.
            //
            // What it missed is that getting here does not mean the daemon is
            // gone - it means the ping did not come back. Off USB those are not
            // the same thing at all. Measured on this phone: with the recorder
            // switched on and the phone on battery, the daemon died within
            // twenty seconds, over and over, and each restart re-armed Wireless
            // debugging and posted Android's notification. With the recorder
            // switched off - nothing else changed, same battery, same phone -
            // the same daemon ran for minutes untouched. The system was never
            // killing it. This line was.
            //
            // So the question is no longer "did it answer" but "is anything
            // holding the port". Nothing there: nothing to kill, and the start
            // below has a clear run. Something there that will not answer even
            // now: that is the stale-daemon case this was written for, and it
            // still gets killed.
            if (retired) {
                // Ours, and it said BYE a moment ago: the port-free wait below
                // is all it needs, and the re-ask would only catch it on its
                // way out and call that "answers after all".
            } else if (DaemonClient.portInUse()) {
                // SOMETHING HOLDS THE PORT. ASK IT AGAIN BEFORE KILLING IT.
                //
                // Getting here means the confirmed ping failed - but a held port
                // is strong evidence of a live daemon, and the pings can fail
                // against a live one: its accept loop was single-threaded and
                // its listen backlog was one, so two callers at once (the
                // keep-alive and a resume, say) had the second connect refused,
                // which reads as "dead" here and "port free" a moment later.
                // Measured: the daemon's log held twelve "port still held"
                // lines from a duplicate this spawned, interleaved with pings
                // the original was answering. The duplicate died on EADDRINUSE
                // and, worse, its spawn truncated the original's log - the one
                // record of what had happened. So a held port gets one more
                // patient ask, and an answer means there is nothing to do.
                if (isRunningConfirmed(context)) {
                    Log.i(TAG, "daemon: port $PORT is held by our daemon, which answers after all")
                    return@runCatching Unit
                }
                // Not ours, then. Say which kind of not-ours before retiring
                // it: the log is the only place this will ever be readable.
                val speaks = DaemonClient.version()
                Log.w(
                    TAG,
                    when {
                        speaks == null ->
                            "daemon: port $PORT held by something that will not answer, retiring it"
                        speaks.version != DaemonClient.PROTOCOL_VERSION ->
                            "daemon: port $PORT held by an out-of-date recorder (protocol ${speaks.version}), replacing it"
                        else ->
                            "daemon: port $PORT held by a recorder that does not know this app's token " +
                                "(a previous install?), replacing it"
                    },
                )
                exec(PKILL)
            } else {
                Log.i(TAG, "daemon: port $PORT is free, nothing to retire")
            }

            // WAIT FOR THE PORT, RATHER THAN BETTING ON A SLEEP.
            //
            // This was a flat 300ms, which is a wager that the kernel has
            // finished tearing the old process down. Lose it and the new daemon
            // binds onto a port its predecessor still holds, takes EADDRINUSE,
            // quits its accept loop and exits - leaving no daemon where a
            // working one stood a second earlier.
            var freed = false
            repeat(PORT_FREE_ATTEMPTS) {
                if (!freed) {
                    if (!DaemonClient.portInUse()) freed = true else Thread.sleep(PORT_FREE_GAP_MS)
                }
            }
            if (!freed) Log.w(TAG, "daemon: port $PORT still held after pkill, starting anyway")
            ReviveLog.note(context, "start: port free: $freed")

            val staged = stageAsset(context)
            ReviveLog.note(context, "start: jar staged")
            Log.i(TAG, "daemon: staged jar at $staged")

            // The shell UID cannot read this app's private files, but it CAN
            // read the app's external files directory, so that is the handoff
            // point. From there the jar is copied onto /data/local/tmp, which
            // is shell-owned and a normal filesystem - dex loading from the
            // sdcard mount is not something to rely on.
            exec("cp '$staged' $DEVICE_PATH && chmod 600 $DEVICE_PATH && echo staged")
                .also { check(it.contains("staged")) { "could not copy the jar: $it" } }
            ReviveLog.note(context, "start: jar copied")

            // setsid is doing the real work here, and `nohup ... &` alone is
            // not enough - measured, not guessed. The identical command run
            // from a USB `adb shell` started the daemon fine, while through
            // this one-shot `shell:` service it left a zero-byte log and no
            // process.
            //
            // The difference is who closes the stream and when. exec() reads to
            // EOF and closes; stdout here is redirected to a file, so EOF is
            // immediate, the stream closes at once and adbd tears down the
            // whole process GROUP - taking the freshly backgrounded app_process
            // with it. nohup only blocks SIGHUP, which is not what arrives.
            //
            // setsid puts the daemon in a new session with its own process
            // group, so there is nothing left for that teardown to kill.
            //
            // The token and the mode ride in the ENVIRONMENT, not the
            // arguments: a process's arguments stay in /proc/<pid>/cmdline as
            // long as it runs - the recorder's, for days - and its environment
            // is readable only by its own uid and root. Android shows an app
            // only its own processes there (measured on the Honor,
            // 2026-11-10), but the environment does not rest on that. The
            // token is what lets the daemon wake this app's service without
            // that service being startable by anything else on the phone, and
            // what every loopback command is proved with. The mode is there
            // so the daemon knows from its first millisecond whether this
            // call should be recorded, asked about, or left alone - a push
            // over the socket a moment later used to be the only word it
            // got, and a call in that gap was recorded whatever the user had
            // chosen.
            //
            // KEEP THE PREVIOUS INSTANCE'S LOG. It is the only durable record of
            // what happened before this spawn - logcat's main buffer rolls in
            // about a minute on this phone - and a spawn that truncated it has
            // already destroyed the one piece of evidence that mattered, once.
            // One generation back is enough to read a death; two would just be
            // clutter in /data/local/tmp.
            exec(
                "mv -f $LOG_PATH $LOG_PATH.1 2>/dev/null; " +
                    "JEMREC_TOKEN=${DaemonClient.token(context)} JEMREC_MODE=${DaemonClient.currentMode(context)} " +
                    "JEMREC_VOIP=${VoipApps.wire(context)} " +
                    "JEMREC_TRACK=${if (CallTracking.enabled(context)) 1 else 0} " +
                    "JEMREC_ANSWER=${AnsweringMachine.wire(context)} " +
                    "CLASSPATH=$DEVICE_PATH setsid nohup " +
                    "app_process / $MAIN_CLASS $PORT < /dev/null > $LOG_PATH 2>&1 &"
            )
            ReviveLog.note(context, "start: launch command returned")

            // Starting is not the same as listening, and listening is not the
            // same as working: app_process has a runtime to bring up and
            // Workarounds to apply first.
            //
            // Waiting on a ping rather than on a bare connect. A plain connect
            // proves only that something owns the port - it would be satisfied
            // by a stale daemon from a previous version - and it left
            // "session: unknown command -1" in the daemon's log every time,
            // because a connection that sends no command byte looks like a
            // client that hung up.
            var started = false
            var tries = 0
            repeat(20) {
                if (!started) {
                    Thread.sleep(250)
                    tries++
                    if (DaemonClient.probe(context) != null) started = true
                }
            }
            ReviveLog.note(context, "start: ${if (started) "listening after $tries pings" else "not listening after $tries pings"}")
            if (!started) {
                val log = exec("cat $LOG_PATH")
                error("daemon did not start listening on $PORT. Its log said:\n$log")
            }
            Log.i(TAG, "daemon: listening on 127.0.0.1:$PORT")
            _changed.tryEmit(Unit)
            Unit
        }
    }

    suspend fun readLog(): String = exec("cat $LOG_PATH")

    suspend fun stop(): String = exec("$PKILL; echo stopped")

    /**
     * Copy the bundled jar somewhere the shell UID can read it.
     *
     * Rewritten every time rather than cached: it costs 85 KB and a few
     * milliseconds, and it means an app update cannot leave a stale daemon jar
     * behind to be launched against mismatched app code.
     */
    private fun stageAsset(context: Context): String {
        val dir = context.getExternalFilesDir(null)
            ?: error("no external files directory, so there is nowhere the shell UID can read from")
        val out = File(dir, ASSET)
        context.assets.open(ASSET).use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        return out.absolutePath
    }

    private suspend fun exec(command: String): String =
        AdbTransport.exec(command).getOrElse { "<failed: ${it.message}>" }
}
