package com.jemcik.jemrec.recorder

import com.jemcik.jemrec.adb.AdbTransport.ConnectOutcome
import com.jemcik.jemrec.system.AdbdState
import com.jemcik.jemrec.system.GlobalSettings
import kotlinx.coroutines.delay
import java.io.IOException

/**
 * A phone for a revive's whole sequence (Revive.reviveLocked, through
 * ReviveWorld): its switches, adbd, a recorder, the notes a revive keeps,
 * Start without Wi-Fi's port and adbd's session, as fields a test sets.
 * What the revive does to it goes to [did], in order, and its revive.log
 * lines to [log]. It behaves as the phones measured do, where the revive
 * relies on it: a write of Wireless debugging Android refuses on this Wi-Fi
 * reads off again (the Android 12 AVD); switched off and on, a stopped adbd
 * starts (the Honor, a cable); notes are kept only on Wi-Fi (NetworkRevive);
 * asked for Wireless debugging off, the recorder writes it off only while
 * USB debugging is on, and says what it found (WirelessOff).
 */
internal class FakePhone : ReviveWorld {

    val did = mutableListOf<String>()
    val log = mutableListOf<String>()

    var setUp = true
    var wifi = true
    var call = false

    /** What a confirmed ping finds, and whether it is this APK's build. */
    var recorder: DaemonClient.Answer? = null
    var currentBuild = true
    var startResult: Result<Unit> = Result.success(Unit)
    /** How long a start takes: a jar copied and a recorder waited for. */
    var startMs = 0L

    var usb = GlobalSettings.Reading.ON
    var wireless = true

    /** Android will not have Wireless debugging on this Wi-Fi: switched on,
     *  it reads off. */
    var refusing = false

    /** adbd's readings, oldest first; the last one stays. */
    val adbd = mutableListOf(AdbdState.State.RUNNING)
    private var wirelessWentOff = false

    var cable = false
    var switchedOn = false

    /** Wireless debugging is JemRec's (NetworkRevive.noteJemRecs). */
    var jemRecs = false

    /** Installs from before 1.5 have not been taken over yet; a phone set
     *  up with 1.5 has (NetworkRevive.takeOverOnce). */
    var takenOver = true

    /** The recorder's answer to Wireless debugging off, where a test gives
     *  one - none from a recorder before 1.5, say; null, it answers as the
     *  recorder does: off only with USB debugging on. */
    var wirelessOffAnswer: (() -> DaemonClient.WirelessOff?)? = null

    /** Android 12 or 13, where the revive holds an mDNS look open
     *  (AdbTransport.holdMdns): its hold and its end go to [did]. */
    var holdsMdns = false
    var refused = false
    var switchedOff = false

    var step = StartWithoutWifi.PortStep.LEAVE
    var ready = false
    var set: Boolean? = false
    var answers = false

    override var connected = false
    override var connectedPort = 0
    override var lastConnect = ConnectOutcome.NONE

    /** The outcomes of the connects to come, in order: NOT_FOUND once there
     *  are none left. */
    val connects = ArrayDeque<ConnectOutcome>()

    /** The port autoConnect finds adbd on: Wireless debugging's own, or the
     *  classic 5555 it falls back on. */
    var autoPort = WIRELESS_PORT

    override fun setUp() = setUp
    override suspend fun recorder(): DaemonClient.Answer? = recorder.also { did += "probe" }
    override fun current(answer: DaemonClient.Answer) = currentBuild
    override fun bundledBuild() = "1a2b3c4d"
    override suspend fun start(): Result<Unit> {
        delay(startMs)
        return startResult.also { did += "start" }
    }
    override suspend fun pushMode() {
        did += "push"
    }
    /** The stand-down's one ping misses a recorder that is up, and the
     *  session stays open (DaemonLauncher.standDown). */
    var standDownMisses = false

    override suspend fun standDown() {
        did += "stand down"
        // The session that started it, closed (DaemonLauncher.standDown).
        if (!standDownMisses) connected = false
    }
    override fun afterRecorderStarted() {
        did += "after start"
    }
    override suspend fun wirelessOff(): DaemonClient.WirelessOff? {
        did += "wireless off"
        wirelessOffAnswer?.let { return it() }
        return when {
            usb != GlobalSettings.Reading.ON -> DaemonClient.WirelessOff.USB_OFF
            !wireless -> DaemonClient.WirelessOff.ALREADY_OFF
            else -> {
                wireless = false
                wirelessWentOff = true
                DaemonClient.WirelessOff.SWITCHED_OFF
            }
        }
    }
    /** Switching Wireless debugging off takes the recorder with it here, and
     *  leaves adbd stopped until it is switched on again: row 18, never seen.
     *  Counted by the guard into [offLosses]. */
    var offKillsRecorder = false

    /** A loss of the recorder in the guard's window comes with the cable's
     *  watch having noted a stopped adbd (CableWatch.stoppedAdbd). */
    var cableStoppedAtLoss = false

    /** Each loss of the recorder to come in the guard's 2 s after an off, with
     *  what adbd reads from there on, in order - RUNNING for a USB change,
     *  which restarts it. [offKillsRecorder] is the off's own. */
    val offLossAdbd = ArrayDeque<List<AdbdState.State>>()

    /** The guard's count of offs that took the recorder in a row, two of
     *  which keep Wireless debugging on (NetworkRevive). */
    var offLosses = 0
    override var offLost = false

    /** This process holds WRITE_SECURE_SETTINGS: not setup's own. */
    var canSwitchOn = true

    override fun keepsAdbd() = offLosses >= NetworkRevive.OFF_LOSSES_KEPT
    override fun noteOffLoss(): Boolean {
        did += "note off loss"
        offLosses++
        return keepsAdbd()
    }
    override fun forgetOffLosses() {
        if (offLosses == 0) return
        did += "forget off losses"
        offLosses = 0
    }
    override fun canSwitchOn() = canSwitchOn
    override suspend fun recorderStillUp(): Boolean {
        did += "still up?"
        val lost = offLossAdbd.removeFirstOrNull()
        if (lost != null) {
            adbd.clear()
            adbd += lost
            if (cableStoppedAtLoss) cable = true
            recorder = null
            return false
        }
        if (offKillsRecorder && !wireless) {
            adbd.clear()
            adbd += AdbdState.State.STOPPED
            recorder = null
            return false
        }
        return true
    }

    override fun mdnsStops() = holdsMdns
    override fun holdMdns(): AutoCloseable? {
        if (!holdsMdns) return null
        did += "hold mdns"
        return AutoCloseable { did += "release mdns" }
    }
    override fun note(line: String) {
        log += line
    }
    override fun onWifi() = wifi
    override fun inCall() = call

    override fun read(setting: String): GlobalSettings.Reading = when (setting) {
        GlobalSettings.ADB_ENABLED -> usb
        GlobalSettings.ADB_WIFI_ENABLED -> if (wireless) GlobalSettings.Reading.ON else GlobalSettings.Reading.OFF
        else -> GlobalSettings.Reading.ON
    }

    override fun write(setting: String, on: Boolean): Boolean {
        did += "$setting=${if (on) 1 else 0}"
        when (setting) {
            GlobalSettings.ADB_ENABLED -> usb = if (on) GlobalSettings.Reading.ON else GlobalSettings.Reading.OFF
            GlobalSettings.ADB_WIFI_ENABLED -> {
                if (!on) wirelessWentOff = true
                if (on && wirelessWentOff && adbd.last() == AdbdState.State.STOPPED) {
                    adbd.clear()
                    adbd += AdbdState.State.RUNNING
                }
                wireless = on && !refusing
            }
        }
        return true
    }

    override fun adbd(): AdbdState.State = if (adbd.size > 1) adbd.removeAt(0) else adbd.first()

    override fun cableStopped() = cable
    override fun forgetCable() {
        did += "forget cable"
        cable = false
    }
    override fun switchedOnHere() = switchedOn && wifi
    override fun noteSwitchedOn() {
        did += "note switched on"
        if (wifi) switchedOn = true
    }
    override fun jemRecs() = jemRecs
    override fun noteJemRecs() {
        did += "note jemrecs"
        jemRecs = true
    }
    override fun forgetJemRecs() {
        did += "forget jemrecs"
        jemRecs = false
    }
    override fun takeOverOnce() {
        if (takenOver) return
        did += "take over"
        jemRecs = true
        takenOver = true
    }
    override fun refusedHere() = refused && wifi
    override fun stillRefusedHere(on: Boolean): Boolean {
        if (!refusedHere()) return false
        if (on) refused = false
        return !on
    }
    override fun noteRefused() {
        did += "note refused"
        if (wifi) refused = true
    }
    override fun forgetRefused() {
        did += "forget refused"
        refused = false
    }
    override fun switchedOff() = switchedOff

    override fun portStep() = step
    override fun portReady() = ready
    override fun portSet() = set
    override fun portAnswers() = answers
    override fun noteOpened(opened: Boolean) {
        did += "note opened $opened"
    }
    override fun noteClosed() {
        did += "note closed"
    }
    override fun noteCloseFailed() {
        did += "note close failed"
    }

    override suspend fun connect(port: Int): Result<Unit> {
        did += "connect $port"
        return outcome(port)
    }
    override suspend fun autoConnect(timeoutMs: Long): Result<Unit> {
        did += "autoConnect $timeoutMs"
        return outcome(autoPort)
    }
    override suspend fun restartAdbd(service: String): Result<String> {
        did += "restart $service"
        // init starts it again with the property as asked, the port with it.
        set = service.startsWith("tcpip:")
        answers = set == true
        connected = false
        return Result.success("restarting in TCP mode port: 5555")
    }
    override suspend fun close() {
        did += "close"
        connected = false
    }

    private fun outcome(port: Int): Result<Unit> {
        val next = connects.removeFirstOrNull() ?: ConnectOutcome.NOT_FOUND
        lastConnect = next
        if (next != ConnectOutcome.CONNECTED) return Result.failure(IOException("no adbd ($next)"))
        connected = true
        connectedPort = port
        return Result.success(Unit)
    }

    companion object {
        /** A port Wireless debugging advertises; any but 5555. */
        const val WIRELESS_PORT = 37_211
    }
}
