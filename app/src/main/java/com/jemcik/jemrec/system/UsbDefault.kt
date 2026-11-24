package com.jemcik.jemrec.system

import android.app.KeyguardManager
import android.content.Context
import androidx.core.content.edit
import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.recorder.DaemonClient

/**
 * Developer options' Default USB configuration, when it stops the recorder
 * at every lock (#131): said on home while it does (HomeScreen), once in a
 * notification for whoever is not opening JemRec, and set to No data
 * transfer with one tap (setNone) - as BatteryHealth says Battery
 * Restricted.
 *
 * Why it stops the recorder. With a PIN, pattern or password and no cable,
 * AOSP's UsbDeviceManager switches the USB functions to charging at every
 * lock and back to this default at every unlock - "We are unlocked when the
 * keyguard is down or non-secure" (onKeyguardStateChanged,
 * MSG_UPDATE_SCREEN_LOCK). Where the default is a data mode the switch
 * restarts adbd, and the recorder, in adbd's cgroup, goes with it. Measured
 * 2026-11-23 with File transfer as the default (tmp/issue-131/FINDINGS.md):
 * the Android 12, 16 and 17 emulators lost the recorder at every lock and
 * unlock and nothing brought it back; a lock 5 s into a call left 5.64 s of
 * a 15.80 s call; the Honor (MagicOS 11, Android 17) lost it at both and had
 * it back 16 s later each time. With No data transfer, or a lock that is not
 * secure, nothing was lost. So it is said only with a secure lock
 * (KeyguardManager.isDeviceSecure), and on every phone: whether a phone with
 * a USB gadget HAL loses it was read in AOSP, not measured, and the Honor's
 * own charging stops adbd.
 *
 * An app cannot read the setting (IUsbManager.getScreenUnlockedFunctions
 * needs MANAGE_USB): the recorder reads it as the shell, at each of home's
 * readings and each keep-alive run, and the last answer is kept here for
 * while it is down - which is when it matters, as a lock just ended it.
 */
object UsbDefault {

    /** UsbManager's functions (GadgetFunction.aidl): ADB is debugging, the
     *  rest data. */
    internal const val FUNCTION_ADB = 1L
    internal const val FUNCTION_MTP = 1L shl 2
    internal const val FUNCTION_MIDI = 1L shl 3
    internal const val FUNCTION_PTP = 1L shl 4
    internal const val FUNCTION_RNDIS = 1L shl 5
    internal const val FUNCTION_UVC = 1L shl 7
    internal const val FUNCTION_NCM = 1L shl 10

    /** The recorder's last answer, UsbManager's bits; absent before any. */
    private const val KEY_FUNCTIONS = "usb_default_functions"

    /** The functions "Not now" put the notice away for: back once they change. */
    private const val KEY_DISMISSED = "usb_default_dismissed"
    /** The words the notification said, absent while nothing is said: a
     *  reading or a run that finds other words says them again, quietly. */
    private const val KEY_TOLD = "usb_default_told_words"

    /** The data mode a tap tried to set to none - asked, or refused as
     *  unreadable: read again as a data mode, the card says the way by hand. */
    private const val KEY_SET_FROM = "usb_default_set_from"

    /** Among the problems' ids (BatteryHealth's 38 and those before it). */
    private const val NOTIFICATION_ID = 39

    /** The notification's button's PendingIntent (see post). */
    internal const val SET_NONE_REQUEST = 3901

    const val TITLE = "Locking your phone stops the recorder"
    const val ACTION = "Set to No data transfer"

    /** Whether [functions] hold a data function: anything but debugging. */
    fun dataMode(functions: Long): Boolean = functions and FUNCTION_ADB.inv() != 0L

    /** [functions] in the words Developer options lists them in (AOSP
     *  Settings' "Use USB for" choices). */
    fun name(functions: Long): String = when {
        functions and FUNCTION_MTP != 0L -> "File transfer"
        functions and FUNCTION_PTP != 0L -> "PTP"
        functions and (FUNCTION_RNDIS or FUNCTION_NCM) != 0L -> "USB tethering"
        functions and FUNCTION_MIDI != 0L -> "MIDI"
        functions and FUNCTION_UVC != 0L -> "Webcam"
        dataMode(functions) -> "a data mode"
        else -> "No data transfer"
    }

    /**
     * What it costs and what to do, in the card's and the notification's
     * words. [didNotStay]: a tap tried to set it to none and it reads a data
     * mode still or again - the recorder could not ask Android, Android
     * refused the write, the phone kept its own, or the user's choice since -
     * so the way by hand is said too, on the card where it stays (a
     * snackbar's goes in seconds).
     */
    fun words(functions: Long, didNotStay: Boolean): String {
        val now = name(functions)
        val again = if (didNotStay) {
            " To set it by hand: Developer options, Default USB configuration, No data transfer."
        } else {
            ""
        }
        return "Developer options' Default USB configuration is $now. With your screen lock, Android " +
            "switches USB at every lock and unlock, and each switch stops JemRec's recorder, which may " +
            "stay stopped until you open JemRec. Calls while the phone is locked can go unrecorded, and locking it " +
            "during a call cuts the recording. With No data transfer, a computer only charges the phone " +
            "until you choose File transfer in the USB notification.$again"
    }

    /** The notification's one line, collapsed. */
    private fun collapsed(functions: Long): String =
        "Default USB configuration is ${name(functions)}: calls while the phone is locked can go unrecorded."

    /** The recorder's last answer; null before any. */
    fun stored(context: Context): Long? {
        val prefs = Prefs.of(context)
        return if (prefs.contains(KEY_FUNCTIONS)) prefs.getLong(KEY_FUNCTIONS, 0L) else null
    }

    /** [functions] kept as the recorder's answer; a none forgets a set
     *  that did not stay. */
    internal fun keep(context: Context, functions: Long) {
        Prefs.of(context).edit {
            putLong(KEY_FUNCTIONS, functions)
            if (!dataMode(functions)) remove(KEY_SET_FROM)
        }
    }

    /**
     * The default, asked of the recorder - kept, and returned - or, with no
     * answer (no recorder, or one before 1.7, which hangs up on it), the
     * last one kept. No longer at risk - set by hand, or the lock no longer
     * secure - what the notification said goes now, not at the next
     * keep-alive run, as BatteryHealth.read takes its own back. Off the main
     * thread: a loopback round trip.
     */
    suspend fun read(context: Context): Long? {
        val reply = DaemonClient.usbDefault(context)
        if (reply is DaemonClient.UsbDefaultReply.Default) keep(context, reply.functions)
        // Taken back if it no longer holds; said again in place if its words
        // changed; never posted anew from a reading (BatteryHealth's way).
        tell(context, postNew = false)
        return stored(context)
    }

    /** Whether the lock is a PIN, pattern or password: only then does
     *  Android switch USB as it locks. */
    fun secureLock(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    /** The rule: a data mode as the default, and a secure lock. */
    fun atRisk(functions: Long?, secure: Boolean?): Boolean =
        functions != null && dataMode(functions) && secure == true

    /** Whether a tap tried to set it to none and it reads a data mode still. */
    fun didNotStay(context: Context, functions: Long?): Boolean =
        functions != null && dataMode(functions) && Prefs.of(context).contains(KEY_SET_FROM)

    /**
     * Whether home says so: [atRisk] with what the last reading found, unless
     * "Not now" put it away for these very functions. Not at risk, a
     * dismissal is forgotten, so the next time it is, it is said. Nothing
     * read yet - either null - says nothing and forgets nothing, as
     * BatteryHealth.notice: every view model Android makes starts unread,
     * and forgetting there undid Not now at each open after the process
     * ended (review round 2). Preferences only: for the main thread, where a
     * reading lands.
     */
    fun notice(context: Context, functions: Long?, secure: Boolean?): Boolean {
        if (functions == null || secure == null) return false
        val prefs = Prefs.of(context)
        if (!atRisk(functions, secure)) {
            forgetNotNow(prefs)
            return false
        }
        return !(prefs.contains(KEY_DISMISSED) && prefs.getLong(KEY_DISMISSED, -1L) == functions)
    }

    /** No longer at risk: Not now forgotten, so the next time it is, it is
     *  said - by home's reading (notice) or by any other look (tell). */
    private fun forgetNotNow(prefs: android.content.SharedPreferences) {
        if (prefs.contains(KEY_DISMISSED)) prefs.edit { remove(KEY_DISMISSED) }
    }

    /** "Not now": put away for [functions], and the notification with it -
     *  under the telling's lock, so a telling cannot post just after it. */
    @Synchronized
    fun dismiss(context: Context, functions: Long) {
        Prefs.of(context).edit { putLong(KEY_DISMISSED, functions) }
        com.jemcik.jemrec.calls.AfterCall.cancelProblem(context, NOTIFICATION_ID)
    }

    /**
     * A keep-alive run, the recorder up: read it, and say it once in a
     * notification - or take back what was said once it no longer holds.
     * The run is where JemRec learns of it while nobody opens it.
     */
    suspend fun check(context: Context) {
        read(context)
        tellIfAtRisk(context)
    }

    /**
     * Said once in a notification while it holds, from the last answer
     * kept: at a start of JemRec's process, as JemRec is switched on, and at
     * each keep-alive run (check). Only while JemRec is on and set up, as
     * BatteryHealth says its own: switched off, nothing it records is lost.
     * Not after "Not now" for these functions.
     */
    fun tellIfAtRisk(context: Context) = tell(context, postNew = true)

    /**
     * [tellIfAtRisk], and a reading's look ([postNew] false: taken back or
     * said again, never posted anew). Told other words - another data mode,
     * or the way by hand now added - they are said again in place, quietly,
     * while it is still up; one the user put away stays away, as
     * BatteryHealth.refresh does (review round 1). Android is asked, and the
     * notification built, before the lock, which holds only the check, the
     * notify and the flag (review round 3: lock contention with the main
     * thread is an ANR cause, developer.android.com).
     */
    private fun tell(context: Context, postNew: Boolean) {
        val functions = stored(context)
        val prefs = Prefs.of(context)
        val atRiskNow = functions != null && atRisk(functions, secureLock(context))
        // Not at risk on a real answer forgets Not now here too, as notice()
        // does: with only a keep-alive run looking - File transfer, Not now,
        // then none, then File transfer again, home never opened - it stayed
        // put away and nothing was said (review round 4).
        if (functions != null && !atRiskNow) forgetNotNow(prefs)
        if (!onAndSetUp(context) || functions == null || !atRiskNow) {
            takeBack(context)
            return
        }
        if (putAway(prefs, functions)) return
        val words = words(functions, didNotStay(context, functions))
        val told = prefs.getString(KEY_TOLD, null)
        if (told == words || (told == null && !postNew)) return
        if (told != null && !showing(context)) return
        postLocked(context, functions, words, told, build(context, functions, words, quietly = told != null))
    }

    /** JemRec on and set up: only then is it said. */
    private fun onAndSetUp(context: Context): Boolean =
        com.jemcik.jemrec.recorder.RecorderSwitch.isOn(context) && com.jemcik.jemrec.setup.Setup.isComplete(context)

    /** Put away with "Not now" for these very [functions]. */
    private fun putAway(prefs: android.content.SharedPreferences, functions: Long): Boolean =
        prefs.contains(KEY_DISMISSED) && prefs.getLong(KEY_DISMISSED, -1L) == functions

    /** Whether the notification is up - not put away from the shade. */
    private fun showing(context: Context): Boolean = runCatching {
        context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.any { it.id == NOTIFICATION_ID }
    }.getOrDefault(false)

    /** Said, under the lock the taking back holds - unless, since [tell]
     *  looked, another thread said it or took it back, Not now put it away,
     *  JemRec was switched off, or a tap set it (review round 4): each
     *  preference the decision read is read again here; the secure lock, a
     *  question to Android, is left to the next look. */
    @androidx.annotation.VisibleForTesting
    @Synchronized
    internal fun postLocked(
        context: Context,
        functions: Long,
        words: String,
        toldBefore: String?,
        notification: android.app.Notification,
    ) {
        val prefs = Prefs.of(context)
        if (prefs.getString(KEY_TOLD, null) != toldBefore || putAway(prefs, functions)) return
        if (!onAndSetUp(context) || stored(context) != functions) return
        if (words(functions, didNotStay(context, functions)) != words) return
        val seen = com.jemcik.jemrec.calls.AfterCall.notifyProblem(context, NOTIFICATION_ID, notification, "usb default")
        if (seen) prefs.edit { putString(KEY_TOLD, words) }
    }

    /** What the notification said, taken back: cancelled, and to be said
     *  again the next time it holds. Under the same lock as the telling:
     *  each reads the flag and then writes it, from the process's start
     *  thread, the switch, a keep-alive run and a reading. */
    @Synchronized
    private fun takeBack(context: Context) {
        val prefs = Prefs.of(context)
        if (prefs.contains(KEY_TOLD)) {
            prefs.edit { remove(KEY_TOLD) }
            com.jemcik.jemrec.calls.AfterCall.cancelProblem(context, NOTIFICATION_ID)
        }
    }

    /** The notification, built with no lock held: its PendingIntents ask
     *  Android's activity manager. */
    private fun build(context: Context, functions: Long, words: String, quietly: Boolean): android.app.Notification {
        // The tap: home, whose card says it too; the button: the same tap
        // as the card's, through JemRec's own activity (DeepLink, as
        // BatteryHealth's Open App info goes).
        val home = android.app.PendingIntent.getActivity(
            context, 0, com.jemcik.jemrec.DeepLink.home(context), android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val setNone = android.app.PendingIntent.getActivity(
            context, SET_NONE_REQUEST,
            com.jemcik.jemrec.DeepLink.intent(context, com.jemcik.jemrec.DeepLink.Kind.USB_DEFAULT_NONE),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return com.jemcik.jemrec.calls.AfterCall.problem(
            context, TITLE, words, home, ACTION to setNone, collapsed = collapsed(functions),
        ).setOnlyAlertOnce(quietly).build()
    }

    /** Start fresh: nothing kept, said or put away. */
    fun forgetAll(context: Context) {
        takeBack(context)
        Prefs.of(context).edit {
            remove(KEY_FUNCTIONS)
            remove(KEY_DISMISSED)
            remove(KEY_SET_FROM)
        }
    }

    /** What a tap on "Set to No data transfer" came to (setNone). */
    enum class Outcome {
        /** Asked: the recorder writes none after its answer, and the
         *  USB-state watch brings it back if Android ended it for that. */
        SETTING,

        /** No data transfer already: nothing written. */
        NONE_ALREADY,

        /** A call ringing or up, or the app fetching a recording: nothing written. */
        BUSY,

        /** The recorder could not ask Android's USB service. */
        UNREADABLE,

        /** No recorder answered. */
        NO_ANSWER,

        /** The recorder was down and could not be started; why is the
         *  revive's cause (RecorderHealth.cause), said as Start recorder
         *  says it. */
        NOT_STARTED,
    }

    /** The recorder's answer to a set, as an [Outcome]. */
    internal fun outcomeOf(reply: DaemonClient.UsbDefaultReply?): Outcome = when (reply) {
        DaemonClient.UsbDefaultReply.Setting -> Outcome.SETTING
        DaemonClient.UsbDefaultReply.Busy -> Outcome.BUSY
        DaemonClient.UsbDefaultReply.Unreadable -> Outcome.UNREADABLE
        // Set to none when the recorder answers with a data mode would be a
        // recorder that did not write it: said as unreadable, not as done.
        is DaemonClient.UsbDefaultReply.Default -> if (dataMode(reply.functions)) Outcome.UNREADABLE else Outcome.NONE_ALREADY
        null -> Outcome.NO_ANSWER
    }

    /** What the screen says of each outcome, in a snackbar; [cause] is the
     *  last revive's (RecorderHealth.cause), for [Outcome.NOT_STARTED]. */
    internal fun said(outcome: Outcome, cause: com.jemcik.jemrec.recorder.RecorderHealth.Problem?): String = when (outcome) {
        Outcome.SETTING -> "Setting Default USB configuration to No data transfer"
        Outcome.NONE_ALREADY -> "Default USB configuration is No data transfer already"
        // Busy is a call ringing or up, recorded or not, or a fetch - the
        // app collecting a recording, as right after every call.
        Outcome.BUSY -> "Not during a call, or while a recording is saved: try again in a moment"
        Outcome.UNREADABLE -> "JemRec could not change it: set it in Developer options, Default USB configuration"
        Outcome.NO_ANSWER -> "JemRec's recorder did not answer: try again in a moment"
        // Not "did not answer, try again in a moment": no retry starts it
        // until its cause goes - Wi-Fi, the network allowed, the pairing -
        // so in Start recorder's words for each (reviews, rounds 2 and 4).
        Outcome.NOT_STARTED -> com.jemcik.jemrec.ui.couldNotStart(cause)
    }

    /**
     * "Set to No data transfer", from the card or the notification: the
     * recorder started first if it is down (a revive the user asked for),
     * then asked to write none. Asked, the answer is kept as none - the
     * next reading says what Android kept - the notification taken back,
     * and the USB-state watch queued: the write can restart adbd and end
     * the recorder, and Android sends no USB_STATE for it (the Android 12
     * emulator, 2026-11-23: adbd restarted, nothing came). Off the main
     * thread.
     */
    suspend fun setNone(context: Context): Outcome {
        if (!com.jemcik.jemrec.capture.CaptureDaemon.isRunningConfirmed(context)) {
            val alive = com.jemcik.jemrec.capture.CaptureDaemon.revive(
                context, request = com.jemcik.jemrec.capture.CaptureDaemon.Request.USER,
            )
            if (!alive) return Outcome.NOT_STARTED
        }
        val outcome = outcomeOf(DaemonClient.usbDefaultNone(context))
        when (outcome) {
            Outcome.SETTING -> {
                noteSet(context)
                com.jemcik.jemrec.capture.UsbStateWatch.queue(context)
            }
            Outcome.NONE_ALREADY -> {
                keep(context, 0L)
                takeBack(context)
            }
            // The card keeps it and says the way by hand (row 13).
            Outcome.UNREADABLE -> noteTried(context)
            else -> Unit
        }
        return outcome
    }

    /** A tap that could not set it: the data mode noted, so the card says
     *  the way by hand while it reads so (didNotStay). */
    internal fun noteTried(context: Context) {
        val now = stored(context) ?: return
        if (dataMode(now)) Prefs.of(context).edit { putLong(KEY_SET_FROM, now) }
    }

    /** A set asked: none kept as the answer, the data mode it replaced kept
     *  to tell whether it stays (didNotStay), what was said taken back. */
    internal fun noteSet(context: Context) {
        val before = stored(context)
        Prefs.of(context).edit {
            if (before != null && dataMode(before)) putLong(KEY_SET_FROM, before)
            putLong(KEY_FUNCTIONS, 0L)
        }
        takeBack(context)
    }
}
