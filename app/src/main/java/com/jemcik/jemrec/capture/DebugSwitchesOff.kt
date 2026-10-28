package com.jemcik.jemrec.capture

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.jemcik.jemrec.Prefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What turning JemRec off does besides stopping the recording - Settings,
 * "When JemRec is off". For the apps, banking ones mostly, that refuse to open
 * while Developer options or debugging is on (issue #37).
 *
 * Three choices, because what each switch costs is different, measured on the
 * Honor (MagicOS 11, Android 17), 2026-10-26:
 *
 * - Developer options' own switch (development_settings_enabled) is only the
 *   menu: written off, adbd and the recorder went on as before, and the menu
 *   was gone from Settings.
 * - Wireless debugging is needed only to START the recorder. Written off with
 *   USB debugging on, no cable, the recorder kept its process through Wi-Fi
 *   off and on, six minutes locked and a real call, recorded both sides - and
 *   nothing switched it back on.
 * - USB debugging is what keeps adbd up. Off, adbd stops and the recorder with
 *   it (on the Honor even with Wireless debugging on), and starting it again
 *   needs Wireless debugging, so Wi-Fi.
 *
 * So the first two can go off with the recorder kept, and On is instant; the
 * third takes the recorder with it, and On needs Wi-Fi - or adbd on Start
 * without Wi-Fi's port, measured on the Honor 2026-10-27 (StartWithoutWifi).
 */
enum class OffMode {
    /** Recording stops; the recorder stays up and debugging stays on. */
    KEEP_DEBUGGING,

    /** Developer options and Wireless debugging go off; USB debugging keeps
     *  the recorder up. */
    HIDE_DEVELOPER_OPTIONS,

    /** All three go off, and the recorder with them. */
    ALL_DEBUGGING_OFF,
}

/** The switches a choice can turn off, by the Settings.Global name each is. */
enum class DebugSwitch(val setting: String) {
    DEVELOPER_OPTIONS(GlobalSettings.DEVELOPMENT_SETTINGS_ENABLED),
    WIRELESS_DEBUGGING(GlobalSettings.ADB_WIFI_ENABLED),
    USB_DEBUGGING(GlobalSettings.ADB_ENABLED),
}

/** What turning JemRec off with this choice switches off. */
internal fun OffMode.switchesOff(): Set<DebugSwitch> = when (this) {
    OffMode.KEEP_DEBUGGING -> emptySet()
    OffMode.HIDE_DEVELOPER_OPTIONS -> setOf(DebugSwitch.DEVELOPER_OPTIONS, DebugSwitch.WIRELESS_DEBUGGING)
    OffMode.ALL_DEBUGGING_OFF -> DebugSwitch.entries.toSet()
}

/** Whether turning off asks first: only where On will need Wi-Fi and there
 *  is none, the one case where off costs more than it says - unless adbd is
 *  on Start without Wi-Fi's port, which On comes back over anywhere.
 *  [portSet] is asked last, and only then: it starts a process (getprop). */
internal inline fun OffMode.asksFirst(onWifi: Boolean, portSet: () -> Boolean): Boolean =
    this == OffMode.ALL_DEBUGGING_OFF && !onWifi && !portSet()

/** Whether the recorder goes with the switches. */
internal fun Set<DebugSwitch>.takesTheRecorder(): Boolean = DebugSwitch.USB_DEBUGGING in this

/**
 * The order to put switches back on in: USB debugging before Wireless
 * debugging, which on the Honor only sticks while USB debugging is on (see
 * CaptureDaemon.armShield), and Developer options last.
 */
internal fun Set<DebugSwitch>.inOrderBackOn(): List<DebugSwitch> =
    listOf(DebugSwitch.USB_DEBUGGING, DebugSwitch.WIRELESS_DEBUGGING, DebugSwitch.DEVELOPER_OPTIONS).filter { it in this }

/**
 * The choice, and what the last Off really switched off.
 *
 * On puts back exactly what was switched off, read from the record and not
 * from the choice: the choice may change while JemRec is off, and applies
 * from the next Off.
 */
object DebugSwitchesOff {

    private const val TAG = "JemRec"
    private const val KEY_MODE = "off_mode"
    private const val KEY_SWITCHED_OFF = "debug_switched_off"

    /** The recorder said "OFF AFTER CALL": an off it has still to finish. */
    private const val KEY_OFF_PENDING = "debug_off_pending"

    /** How long On waits for a recorder already writing the switches off to
     *  be gone: two `settings put`s, the 3 s it allows adbd after them, and
     *  the confirmed probe's own 2.4 s to say no (CaptureDaemon). */
    private const val RECORDER_GOING_MS = 8_000L

    fun mode(context: Context): OffMode =
        Prefs.of(context).getString(KEY_MODE, null)
            ?.let { name -> OffMode.entries.firstOrNull { it.name == name } }
            ?: OffMode.KEEP_DEBUGGING

    fun setMode(context: Context, mode: OffMode) {
        Prefs.of(context).edit { putString(KEY_MODE, mode.name) }
    }

    /** What the last Off switched off and On has not yet put back. */
    fun switchedOff(context: Context): Set<DebugSwitch> =
        Prefs.of(context).getStringSet(KEY_SWITCHED_OFF, emptySet()).orEmpty()
            .mapNotNull { name -> DebugSwitch.entries.firstOrNull { it.name == name } }
            .toSet()

    private fun record(context: Context, switches: Set<DebugSwitch>) {
        Prefs.of(context).edit { putStringSet(KEY_SWITCHED_OFF, switches.map { it.name }.toSet()) }
    }

    /**
     * Calls [onChange] with [recorderGone] whenever the record changes, from
     * anywhere. For the home screen, as RecorderSwitch.observe is: the tile
     * turns JemRec off over the screen without pausing it, and the header
     * said a plain "Off" while debugging had gone too (the Honor,
     * 2026-10-26). Returns the way to stop (Prefs.watch).
     */
    fun observe(context: Context, onChange: (Boolean) -> Unit): () -> Unit =
        Prefs.watch(context, KEY_SWITCHED_OFF) { onChange(recorderGone(context)) }

    /**
     * JemRec is off with the recorder gone by choice: nothing may start it
     * again until On. Every revive asks this first (CaptureDaemon.revive) -
     * the keep-alive, the Wi-Fi and cable watches, boot, opening the app, and
     * UsbDebuggingWatch, which would otherwise have USB debugging back on
     * within seconds.
     */
    fun recorderGone(context: Context): Boolean = switchedOff(context).takesTheRecorder()

    /**
     * Gone and meant to stay gone: [recorderGone] with JemRec still off. With
     * JemRec on, a record left behind is an On cut short - the switch saved,
     * the process gone before [switchBackOn] - and standing down then would
     * leave a JemRec that says it is on unable ever to record; the revive
     * finishes the On instead (CaptureDaemon.revive), as it does for any
     * record left under a JemRec that is on.
     */
    fun standingDown(context: Context): Boolean = recorderGone(context) && !RecorderSwitch.isOn(context)

    /**
     * Whether turning off asks first, here and now (OffMode.asksFirst): the
     * record control and the tile ask the same question in the same case.
     * [portReady]: whether Start without Wi-Fi's port would bring JemRec
     * back - asked only where it matters, and a process (getprop) unless
     * given: the record control gives the reading its screen shows, the
     * tile asks off the main thread.
     */
    fun asksBeforeOff(context: Context, portReady: () -> Boolean = { StartWithoutWifi.readyForUser(context) }): Boolean =
        mode(context).asksFirst(NetworkRevive.onWifi(context), portReady)

    /**
     * Switch off what the choice says. Recorded first, so a revive racing
     * this already stands down.
     *
     * With the recorder going too, the recorder switches Wireless and USB
     * debugging off itself (CaptureDaemon.debuggingOff): it is never frozen,
     * and it waits out a call in progress and gives the microphone back first.
     * Only if no recorder answers does the app write them. Usually that is no
     * recorder at all; it can also be one left by the build before this
     * command, in the minutes between an update and the revive that replaces
     * it, which then goes at once, mid-call or not.
     */
    suspend fun switchOff(context: Context) {
        // Only what is on now goes in the record, so On puts back no more
        // than was taken: Wireless debugging is already off away from Wi-Fi,
        // and switching it on for nothing has Android ask about the network.
        // A switch Android 17 hides from apps (UNKNOWN) counts as on. USB
        // debugging always goes in: it is what says the recorder is gone.
        val off = mode(context).switchesOff().filter {
            it == DebugSwitch.USB_DEBUGGING || GlobalSettings.read(context, it.setting) != GlobalSettings.Reading.OFF
        }.toSet()
        if (off.isEmpty()) return
        record(context, switchedOff(context) + off)
        if (DebugSwitch.DEVELOPER_OPTIONS in off) GlobalSettings.set(context, GlobalSettings.DEVELOPMENT_SETTINGS_ENABLED, false)
        if (off.takesTheRecorder()) {
            val reply = CaptureDaemon.debuggingOff(context, go = true)
            Log.i(TAG, "debugging off: the recorder says ${reply ?: "nothing"}")
            ReviveLog.note(context, "debugging off: the recorder says ${reply ?: "nothing"}")
            notePending(context, reply == "OFF AFTER CALL")
            if (reply == null) {
                GlobalSettings.set(context, GlobalSettings.ADB_WIFI_ENABLED, false)
                GlobalSettings.set(context, GlobalSettings.ADB_ENABLED, false)
            }
        } else if (DebugSwitch.WIRELESS_DEBUGGING in off) {
            GlobalSettings.set(context, GlobalSettings.ADB_WIFI_ENABLED, false)
        }
    }

    /**
     * Put back what the last Off switched off, before the recorder is looked
     * at. A recorder still waiting on a call to switch debugging off is told
     * not to.
     */
    suspend fun switchBackOn(context: Context) {
        val off = switchedOff(context)
        if (off.isEmpty()) return
        val reply = if (off.takesTheRecorder()) CaptureDaemon.debuggingOff(context, go = false) else null
        if (reply == "OFF TOO LATE") {
            // Too late to call off: the recorder is writing the switches off,
            // and its writes would land on ours - an off and a quick on, say,
            // since the off is done at "OFF NOW", before them. Gone means they
            // have landed: its second write is what stops adbd and it, and if
            // adbd lives on it exits 3 s later. Asked the confirmed way: one
            // missed ping is not a recorder gone, and ending the wait on one
            // would let its write land on ours after all.
            withTimeoutOrNull(RECORDER_GOING_MS) {
                while (CaptureDaemon.isRunningConfirmed(context)) delay(250)
            }
        }
        // The switches first, the record after: killed in between, the record
        // is still there to finish from (standingDown); cleared first, what
        // was switched off would be lost - Developer options hidden for good.
        off.inOrderBackOn().forEach { GlobalSettings.set(context, it.setting, true) }
        record(context, emptySet())
        Prefs.of(context).edit { remove(KEY_OFF_PENDING) }
        ReviveLog.note(
            context,
            "debugging back on: ${off.inOrderBackOn().joinToString { it.name }}" +
                (reply?.let { " (the recorder said $it)" } ?: ""),
        )
    }

    /** Whether the recorder has an off still to finish (finishOffLeftUndone). */
    internal fun notePending(context: Context, pending: Boolean) {
        Prefs.of(context).edit { putBoolean(KEY_OFF_PENDING, pending) }
    }

    /**
     * [standingDown], and if so, an off the recorder left unfinished finished
     * ([finishOffLeftUndone]): what the keep-alive, UsbDebuggingWatch and every
     * revive ask before anything else. True: leave the recorder alone.
     */
    suspend fun stayingOff(context: Context): Boolean {
        if (!standingDown(context)) return false
        finishOffLeftUndone(context)
        return true
    }

    /**
     * An off with all debugging the recorder never finished: it was waiting
     * out a call ("OFF AFTER CALL") and died first - a computer's cable, say -
     * so debugging is still on under a record and a header that say it is
     * off. Only that off: USB debugging the user switched on themselves while
     * JemRec is off is theirs, and the note is dropped the first time USB
     * debugging is seen off - the recorder finished. Left alone while a
     * recorder answers, asked the confirmed way (one missed ping is not a
     * dead recorder, and this one may be waiting on a call), and where
     * Android hides the setting from the app (UNKNOWN).
     */
    suspend fun finishOffLeftUndone(context: Context) {
        val prefs = Prefs.of(context)
        if (!prefs.getBoolean(KEY_OFF_PENDING, false)) return
        when (GlobalSettings.read(context, GlobalSettings.ADB_ENABLED)) {
            GlobalSettings.Reading.OFF -> {
                prefs.edit { remove(KEY_OFF_PENDING) }
                return
            }
            GlobalSettings.Reading.UNKNOWN -> return
            GlobalSettings.Reading.ON -> Unit
        }
        if (CaptureDaemon.isRunningConfirmed(context)) return
        GlobalSettings.set(context, GlobalSettings.ADB_WIFI_ENABLED, false)
        GlobalSettings.set(context, GlobalSettings.ADB_ENABLED, false)
        prefs.edit { remove(KEY_OFF_PENDING) }
        ReviveLog.note(context, "debugging off: finished what the recorder left undone")
    }

    /**
     * Start fresh: the choice back to its default, and Developer options
     * back if JemRec hid it - setup's second step sends the user there, and a
     * menu that is not there is a dead end. Debugging itself setup asks for.
     */
    fun forget(context: Context) {
        if (DebugSwitch.DEVELOPER_OPTIONS in switchedOff(context)) {
            GlobalSettings.set(context, GlobalSettings.DEVELOPMENT_SETTINGS_ENABLED, true)
        }
        Prefs.of(context).edit {
            remove(KEY_MODE)
            remove(KEY_SWITCHED_OFF)
            remove(KEY_OFF_PENDING)
        }
    }

    /**
     * A revive while JemRec is off with Developer options hidden - after a
     * reboot, or a cable from a computer - turns Wireless debugging on to
     * start the recorder. Once it is up, it goes off again, and into the
     * record if it was not there - off away from Wi-Fi, it was already off at
     * the time - so On puts it back. Only that off is recorded without USB
     * debugging; an empty record (both already off then) cannot be told from
     * no off at all, and is left alone. With JemRec on, a record is an On cut
     * short, which the revive has just put back (CaptureDaemon.revive).
     */
    fun afterRecorderStarted(context: Context) {
        if (RecorderSwitch.isOn(context)) return
        val off = switchedOff(context)
        if (off.isEmpty() || off.takesTheRecorder()) return
        if (!GlobalSettings.isOn(context, GlobalSettings.ADB_WIFI_ENABLED)) return
        record(context, off + DebugSwitch.WIRELESS_DEBUGGING)
        GlobalSettings.set(context, GlobalSettings.ADB_WIFI_ENABLED, false)
    }
}
