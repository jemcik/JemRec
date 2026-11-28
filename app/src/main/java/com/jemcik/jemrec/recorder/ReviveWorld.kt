package com.jemcik.jemrec.recorder

import android.content.Context
import com.jemcik.jemrec.adb.AdbTransport
import com.jemcik.jemrec.system.AdbdState
import com.jemcik.jemrec.system.GlobalSettings

/**
 * What a revive reads and does, one call each, in the terms the revive uses:
 * the recorder, the debugging switches, adbd, the notes kept per Wi-Fi
 * connection, Start without Wi-Fi's port, and adbd's session. [Live] is the
 * phone, through the objects the app has always used; a test gives a phone
 * of its own, and runs a revive's whole sequence against it.
 */
internal interface ReviveWorld {
    /** Setup finished and an identity to revive with (RecorderLifecycle.isSetUp). */
    fun setUp(): Boolean

    /** The recorder's answer to a confirmed ping (DaemonLauncher.probeConfirmed). */
    suspend fun recorder(): DaemonClient.Answer?
    fun current(answer: DaemonClient.Answer): Boolean
    fun bundledBuild(): String

    /** Start it over the session, the lock held (DaemonLauncher.ensureRunningLocked). */
    suspend fun start(): Result<Unit>
    suspend fun pushMode()
    suspend fun standDown()
    fun afterRecorderStarted()

    /** The recorder's answer to Wireless debugging off (DaemonClient.wirelessOff). */
    suspend fun wirelessOff(): DaemonClient.WirelessOff?

    /** The app's own mDNS look, held open across a switch-on on Android 12
     *  and 13 (AdbTransport.holdMdns); null on other versions. */
    fun holdMdns(): AutoCloseable?

    /** Android 12 or 13, where NsdService stops mdnsd, and adbd's
     *  announcement with it, 10 s after the last app's request
     *  (AdbTransport.holdsMdns). */
    fun mdnsStops(): Boolean

    /** A line in revive.log (ReviveLog). */
    fun note(line: String)
    fun onWifi(): Boolean
    fun inCall(): Boolean

    /** A Settings.Global switch (GlobalSettings). */
    fun read(setting: String): GlobalSettings.Reading
    fun write(setting: String, on: Boolean): Boolean
    fun adbd(): AdbdState.State

    // What a revive keeps - per Wi-Fi connection, but for whose Wireless
    // debugging it is and the guard's note (NetworkRevive, CableWatch) -
    // and what When JemRec is off switched off (DebugSwitchesOff).
    fun cableStopped(): Boolean
    fun forgetCable()
    fun switchedOnHere(): Boolean
    fun noteSwitchedOn()
    fun jemRecs(): Boolean
    fun noteJemRecs()
    fun forgetJemRecs()
    fun takeOverOnce()

    /** The guard after an off (Revive.askWirelessOff): Wireless debugging is
     *  kept on this phone, as two offs in a row took the recorder; an off
     *  that did counted, true when it was the second; and the count cleared
     *  by one the recorder survived (NetworkRevive.noteOffLoss). */
    fun keepsAdbd(): Boolean
    fun noteOffLoss(): Boolean
    fun forgetOffLosses()

    /** An off in this revive took the recorder: none is asked in it again. */
    var offLost: Boolean

    /** This process can write the debugging switches (Setup.hasSecureSettings):
     *  not setup's own, whose permission is live only from the next. */
    fun canSwitchOn(): Boolean

    /** The recorder still answers, asked the confirmed way
     *  (DaemonLauncher.probeConfirmed): the guard after an off. */
    suspend fun recorderStillUp(): Boolean
    fun refusedHere(): Boolean
    fun stillRefusedHere(on: Boolean): Boolean
    fun noteRefused()
    fun forgetRefused()
    fun switchedOff(): Boolean

    // Start without Wi-Fi's port (StartWithoutWifi).
    fun portStep(): StartWithoutWifi.PortStep
    fun portReady(): Boolean

    /** service.adb.tcp.port: the port adbd is set to, 0 for none, null when
     *  it cannot be read (StartWithoutWifi.configuredPort). */
    fun configuredPort(): Int?

    /** A port is set, anyone's; null when it cannot be told. */
    fun portSet(): Boolean? = configuredPort()?.let { it > 0 }

    /** The classic ports to connect over (PortChange.classicPorts). */
    fun classicPorts(): List<Int>
    fun portAnswers(port: Int): Boolean

    /** JemRec's own number, if one was picked; and one, picked now if not. */
    fun ownPort(): Int?
    fun ensureOwnPort(): Int
    fun noteArmed()
    fun noteOpenFailed()
    fun noteClosed()
    fun noteCloseFailed()

    // adbd's session (AdbTransport).
    val connected: Boolean
    val connectedPort: Int
    val lastConnect: AdbTransport.ConnectOutcome
    suspend fun connect(port: Int): Result<Unit>
    suspend fun autoConnect(timeoutMs: Long): Result<Unit>
    suspend fun restartAdbd(service: String): Result<String>
    suspend fun close()

    /** The phone: today's objects, each called as the revive always called it. */
    class Live(private val context: Context) : ReviveWorld {
        override fun setUp() = RecorderLifecycle.isSetUp(context)
        override suspend fun recorder() = DaemonLauncher.probeConfirmed(context)
        override fun current(answer: DaemonClient.Answer) = DaemonLauncher.upToDate(context, answer)
        override fun bundledBuild() = DaemonLauncher.bundledBuild(context)
        override suspend fun start() = DaemonLauncher.ensureRunningLocked(context)
        override suspend fun pushMode() = DaemonClient.pushMode(context)
        override suspend fun standDown() = DaemonLauncher.standDown(context)
        override fun afterRecorderStarted() = DebugSwitchesOff.afterRecorderStarted(context)
        override suspend fun wirelessOff() = DaemonClient.wirelessOff(context)
        override fun holdMdns() = AdbTransport.holdMdns(context)
        override fun mdnsStops() = AdbTransport.holdsMdns(android.os.Build.VERSION.SDK_INT)
        override fun note(line: String) = ReviveLog.note(context, line)
        override fun onWifi() = NetworkRevive.onWifi(context)
        override fun inCall() = DaemonLauncher.callInProgress(context)
        override fun read(setting: String) = GlobalSettings.read(context, setting)
        override fun write(setting: String, on: Boolean) = RecorderLifecycle.write(context, setting, on)
        override fun adbd() = AdbdState.read()
        override fun cableStopped() = CableWatch.stoppedAdbd(context)
        override fun forgetCable() = CableWatch.forget(context)
        override fun switchedOnHere() = NetworkRevive.switchedOnHere(context)
        override fun noteSwitchedOn() = NetworkRevive.noteSwitchedOn(context)
        override fun jemRecs() = NetworkRevive.jemRecs(context)
        override fun keepsAdbd() = NetworkRevive.wirelessKeepsAdbd(context)
        override fun noteOffLoss() = NetworkRevive.noteOffLoss(context)
        override fun forgetOffLosses() = NetworkRevive.forgetOffLosses(context)
        override var offLost = false
        override fun canSwitchOn() = com.jemcik.jemrec.setup.Setup.hasSecureSettings(context)
        override suspend fun recorderStillUp() = DaemonLauncher.probeConfirmed(context) != null
        override fun noteJemRecs() = NetworkRevive.noteJemRecs(context)
        override fun forgetJemRecs() = NetworkRevive.forgetJemRecs(context)
        override fun takeOverOnce() = NetworkRevive.takeOverOnce(context)
        override fun refusedHere() = NetworkRevive.wirelessRefusedHere(context)
        override fun stillRefusedHere(on: Boolean) = NetworkRevive.stillRefusedHere(context, on)
        override fun noteRefused() = NetworkRevive.noteWirelessRefused(context)
        override fun forgetRefused() = NetworkRevive.forgetWirelessRefused(context)
        override fun switchedOff() = DebugSwitchesOff.switchedOff(context).isNotEmpty()
        override fun portStep() = StartWithoutWifi.step(context)
        override fun portReady() = StartWithoutWifi.ready(context)
        override fun configuredPort() = StartWithoutWifi.configuredPort()
        override fun classicPorts() = PortChange.classicPorts(context)
        override fun portAnswers(port: Int) = StartWithoutWifi.answers(port)
        override fun ownPort() = StartWithoutWifi.ownPort(context)
        override fun ensureOwnPort() = StartWithoutWifi.ensureOwnPort(context)
        override fun noteArmed() = StartWithoutWifi.noteArmed(context)
        override fun noteOpenFailed() = StartWithoutWifi.noteOpenFailed(context)
        override fun noteClosed() = StartWithoutWifi.noteClosed(context)
        override fun noteCloseFailed() = StartWithoutWifi.noteCloseFailed(context)
        override val connected get() = AdbTransport.isConnected
        override val connectedPort get() = AdbTransport.connectedPort
        override val lastConnect get() = AdbTransport.lastConnect
        override suspend fun connect(port: Int) = AdbTransport.connect(context, AdbTransport.LOOPBACK, port)
        override suspend fun autoConnect(timeoutMs: Long) =
            AdbTransport.autoConnect(context, timeoutMs = timeoutMs, classicPorts = classicPorts())
        override suspend fun restartAdbd(service: String) = AdbTransport.restartAdbd(service)
        override suspend fun close() = AdbTransport.close()
    }
}
