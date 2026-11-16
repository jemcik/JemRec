package com.jemcik.jemrec.ui

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.jemcik.jemrec.playback.AtTheEar
import com.jemcik.jemrec.playback.Output
import com.jemcik.jemrec.playback.PlayThrough
import com.jemcik.jemrec.playback.PlayThroughSetting

/**
 * Listening at the ear, on the phone: the proximity sensor, the lock that
 * darkens the screen by it, whether headphones are in, and Android's audio
 * mode - for a call the user is on - read into AtTheEar, and what it decides
 * done on the session's player through [controls]. Main thread, all of it.
 *
 * The screen goes dark by Android's own PROXIMITY_SCREEN_OFF_WAKE_LOCK, the
 * one a phone app holds in a call: "Turns the screen off when the proximity
 * sensor activates" (PowerManager docs). Measured on the Honor, 2026-11-15:
 * dark at the ear, JemRec not paused or stopped by it, and the power key at
 * the ear lit the screen rather than locking the phone. WAKE_LOCK, which it
 * needs, the app already holds.
 */
internal class EarWatch(
    private val app: Application,
    private val controls: Controls,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    /** What the watch does on the player, and tells the screen. */
    interface Controls {
        fun moveTo(output: Output)
        fun pause()

        /** Whether the player offers the earpiece: the phone has one, and
         *  no headphones are in. */
        fun offered(offered: Boolean)

        /** Whether JemRec covers its screen: at the earpiece, at the ear
         *  (EarCover). */
        fun cover(covered: Boolean)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val audio: AudioManager? = app.getSystemService(AudioManager::class.java)
    private val sensors: SensorManager? = app.getSystemService(SensorManager::class.java)
    private val power: PowerManager? = app.getSystemService(PowerManager::class.java)
    private val proximity: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val earpiece = hasEarpiece(app)
    private val canDarken = earpiece && canDarkenScreen(app)

    private val ear = AtTheEar()
    private var facts = AtTheEar.Facts(
        recording = null,
        wanted = false,
        onScreen = false,
        headphones = false,
        playThrough = if (earpiece) PlayThroughSetting.of(app) else PlayThrough.LOUDSPEAKER,
        sensor = canDarken,
    )

    /** Where the session plays now, as it last said. */
    private var playingOn = Output.SPEAKER
    private var lastOffered: Boolean? = null
    private var covered = false

    /** The session holds its recording back, to play on after - a call has
     *  the audio - as it last said. */
    private var heldBack = false

    /** Android's audio mode is that of a call the user is on (inCall), as
     *  last told. */
    private var callUnderWay = false

    /** Closed for good: a device or mode change already posted when close()
     *  ran must not start the sensor or take the lock again - nothing would
     *  let them go, and the lock renews itself. */
    private var closed = false

    private var sensing = false
    private val lock: PowerManager.WakeLock? = if (canDarken) {
        power?.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, LOCK_TAG)?.apply { setReferenceCounted(false) }
    } else {
        null
    }

    private val readings = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!sensing) return
            follow(ear.sensed(AtTheEar.isNear(event.values[0], event.sensor.maximumRange), facts, clock()))
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val due = Runnable { follow(ear.update(facts, clock())) }

    /** The lock taken again before it runs out, while it is wanted. */
    private val renewLock = Runnable { holdLock() }

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = devicesChanged()

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = devicesChanged()
    }
    private var watchingDevices = false

    /** Told on the main thread, as the rest. */
    private val modes = AudioManager.OnModeChangedListener { mode -> modeChanged(mode) }
    private var watchingModes = false

    init {
        // From the start, not the first resume: a Play reached before it - a
        // notification's, as JemRec opens - asks startsOnEarpiece, and
        // headphones not yet read opened it at the earpiece with buds in
        // (the 1.1 review, 2026-11-15).
        watchDevices()
        watchModes()
    }

    /** What the session plays: [recording] or nothing, [wanted] its Play,
     *  [heldBack] whether a call holds that Play back, [output] where it
     *  sounds. */
    fun player(recording: String?, wanted: Boolean, output: Output, heldBack: Boolean) {
        // Opened while a recording plays at the earpiece the button chose
        // with this screen gone: it stays there - never to the loudspeaker
        // because a screen came back.
        val arrived = recording != null && recording != facts.recording
        playingOn = output
        this.heldBack = heldBack
        val now = clock()
        val updated = facts.copy(recording = recording, wanted = wanted, heldByCall = heldByCall())
        if (arrived && output == Output.EARPIECE) {
            ear.update(updated, now)
            facts = updated
            follow(ear.choose(Output.EARPIECE, facts, now))
        } else {
            set(updated)
        }
    }

    /** JemRec's screen came to the front, or left it. */
    fun screen(resumed: Boolean) = set(facts.copy(onScreen = resumed))

    /** Settings' "Play recordings". */
    fun playThrough(playThrough: PlayThrough) = set(facts.copy(playThrough = if (earpiece) playThrough else PlayThrough.LOUDSPEAKER))

    /** Whether a recording about to open should open at the earpiece:
     *  Settings says so, and no headphones are in. */
    fun startsOnEarpiece(): Boolean =
        !facts.headphones && AtTheEar.startsOn(facts.playThrough) == Output.EARPIECE

    /** The player's button. */
    fun choose(output: Output) {
        if (!earpiece) return
        follow(ear.choose(output, facts, clock()))
    }

    /**
     * The screen is gone for good: nothing watched, nothing held. A recording
     * at the earpiece because the phone was at the ear is paused and put back
     * on the loudspeaker: with nothing left to see it lowered, it would play
     * on at the earpiece, and reopened, look like the button's choice; nor
     * may it go on out loud at an ear.
     */
    fun close() {
        closed = true
        if (ear.raisedNow()) {
            controls.pause()
            controls.moveTo(Output.SPEAKER)
        }
        handler.removeCallbacks(due)
        stopSensing()
        releaseLock()
        stopWatchingDevices()
        stopWatchingModes()
    }

    private fun heldByCall(): Boolean = heldBack && callUnderWay

    private fun set(updated: AtTheEar.Facts) {
        facts = updated
        follow(ear.update(facts, clock()))
    }

    private fun follow(wants: AtTheEar.Wants) {
        if (closed) return
        if (facts.recording != null && wants.output != playingOn) {
            controls.moveTo(wants.output)
            // Taken as done until the session says where it plays: asked
            // once, not again at each reading before it answers.
            playingOn = wants.output
        }
        if (wants.sensor) startSensing() else stopSensing()
        if (wants.screenOffLock) {
            if (lock?.isHeld != true) holdLock()
        } else {
            releaseLock()
        }
        handler.removeCallbacks(due)
        ear.due()?.let { at -> handler.postDelayed(due, (at - clock()).coerceAtLeast(0)) }
        val offered = earpiece && !facts.headphones
        if (offered != lastOffered) {
            lastOffered = offered
            controls.offered(offered)
        }
        if (wants.cover != covered) {
            covered = wants.cover
            controls.cover(covered)
        }
    }

    private fun startSensing() {
        if (sensing) return
        val sensor = proximity ?: return
        sensing = sensors?.registerListener(readings, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler) == true
    }

    private fun stopSensing() {
        if (!sensing) return
        sensing = false
        sensors?.unregisterListener(readings)
    }

    /** Held a quarter of an hour at a time, taken again every ten minutes
     *  while it is wanted: a long call at the ear keeps it, and a release
     *  this app never got to still runs out. */
    private fun holdLock() {
        val lock = lock ?: return
        lock.acquire(LOCK_HOLD_MS)
        handler.removeCallbacks(renewLock)
        handler.postDelayed(renewLock, LOCK_RENEW_MS)
    }

    /** Let go only once the phone is away from the ear: let go at it - the
     *  recording's end - the screen would light against the cheek. */
    private fun releaseLock() {
        handler.removeCallbacks(renewLock)
        val lock = lock ?: return
        if (lock.isHeld) lock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
    }

    private fun watchDevices() {
        if (watchingDevices) return
        val audio = audio ?: return
        audio.registerAudioDeviceCallback(devices, handler)
        watchingDevices = true
        // Android tells the callback what is in now as well; read here so
        // the screen does not wait for it.
        devicesChanged()
    }

    private fun stopWatchingDevices() {
        if (!watchingDevices) return
        audio?.unregisterAudioDeviceCallback(devices)
        watchingDevices = false
    }

    private fun devicesChanged() {
        val outputs = audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
        set(facts.copy(headphones = outputs.any { it.type in LISTENING }))
    }

    /** Android's audio mode, for a call's answer and end: the session says
     *  only that its Play is held back, which a call's ringing does too. */
    private fun watchModes() {
        if (watchingModes) return
        val audio = audio ?: return
        audio.addOnModeChangedListener({ handler.post(it) }, modes)
        watchingModes = true
        modeChanged(audio.mode)
    }

    private fun stopWatchingModes() {
        if (!watchingModes) return
        audio?.removeOnModeChangedListener(modes)
        watchingModes = false
    }

    private fun modeChanged(mode: Int) {
        callUnderWay = inCall(mode)
        set(facts.copy(heldByCall = heldByCall()))
    }

    companion object {
        private const val LOCK_TAG = "jemrec:ear"
        private const val LOCK_HOLD_MS = 15 * 60_000L
        private const val LOCK_RENEW_MS = 10 * 60_000L

        /** Something a recording plays in other than the phone itself, so
         *  the earpiece is not offered. Each is a plain number to compare:
         *  BLE_BROADCAST is Android 13's, and Android 12 never reports it, so
         *  there it matches nothing. Not BLUETOOTH_SCO alone: buds list it
         *  beside A2DP (measured, the Honor, 2026-11-15), and a car kit or
         *  headset connected for calls only - inferred from the Bluetooth
         *  profiles, not tried - carries no media, so the recording plays
         *  on the loudspeaker there. Whether voice-call audio would then go
         *  to that kit rather than the earpiece is not known either. */
        @SuppressLint("InlinedApi")
        private val LISTENING = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_AUX_LINE,
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_HDMI_EARC,
            AudioDeviceInfo.TYPE_DOCK,
            AudioDeviceInfo.TYPE_DOCK_ANALOG,
        )

        /**
         * Debug builds only: act as if the phone had an earpiece, on an
         * emulator, which has none (the Android 17 AVD lists a speaker and
         * Telephony Tx, 2026-11-15), so the emulator checks can raise a
         * recording to an ear. Nothing in the release build sets it.
         */
        @Volatile
        internal var actAsEarpiece = false

        /** The audio mode of a call the user is on at the phone: a phone
         *  call made or answered - its ringing has a mode of its own - or a
         *  messenger call. Not call screening, the mode of a call JemRec's
         *  answering machine takes, nor a call whose audio goes to another
         *  device (the redirect modes): the phone may be in a pocket. Measured
         *  on the Honor, 2026-11-16: ringing, then in call at the answer,
         *  normal again 0.26 s before the recording was given its audio back. */
        fun inCall(mode: Int): Boolean = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION

        /** Whether the phone has an earpiece - a tablet has none. */
        fun hasEarpiece(context: Context): Boolean =
            actAsEarpiece ||
                context.getSystemService(AudioManager::class.java)
                    ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    ?.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE } == true

        /** Whether the earpiece at the ear can work here: an earpiece, and
         *  the screen darkened by a proximity sensor. */
        fun canRaise(context: Context): Boolean = hasEarpiece(context) && canDarkenScreen(context)

        /** A proximity sensor, and the lock that darkens the screen by it. */
        fun canDarkenScreen(context: Context): Boolean =
            context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_PROXIMITY) != null &&
                context.getSystemService(PowerManager::class.java)
                    ?.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) == true
    }
}
