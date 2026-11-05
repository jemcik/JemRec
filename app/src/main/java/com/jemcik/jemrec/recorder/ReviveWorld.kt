package com.jemcik.jemrec.recorder

import android.content.Context
import com.jemcik.jemrec.adb.AdbIdentity
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

    /** A line in revive.log (ReviveLog). */
    fun note(line: String)
    fun onWifi(): Boolean
    fun inCall(): Boolean

    /** A Settings.Global switch (GlobalSettings). */
    fun read(setting: String): GlobalSettings.Reading
    fun write(setting: String, on: Boolean): Boolean
    fun adbd(): AdbdState.State

    // What a revive keeps per Wi-Fi connection (NetworkRevive, CableWatch),
    // and what When JemRec is off switched off (DebugSwitchesOff).
    fun cableStopped(): Boolean
    fun forgetCable()
    fun switchedOnHere(): Boolean
    fun noteSwitchedOn()
    fun refusedHere(): Boolean
    fun stillRefusedHere(on: Boolean): Boolean
    fun noteRefused()
    fun forgetRefused()
    fun switchedOff(): Boolean

    // Start without Wi-Fi's port (StartWithoutWifi).
    fun portStep(): StartWithoutWifi.PortStep
    fun portReady(): Boolean
    fun portSet(): Boolean?
    fun portAnswers(): Boolean
    fun noteOpened(opened: Boolean)
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
        override fun setUp() = RecorderLifecycle.setupComplete(context) && AdbIdentity.exists(context)
        override suspend fun recorder() = DaemonLauncher.probeConfirmed(context)
        override fun current(answer: DaemonClient.Answer) = DaemonLauncher.upToDate(context, answer)
        override fun bundledBuild() = DaemonLauncher.bundledBuild(context)
        override suspend fun start() = DaemonLauncher.ensureRunningLocked(context)
        override suspend fun pushMode() = DaemonClient.pushMode(context)
        override suspend fun standDown() = DaemonLauncher.standDown(context)
        override fun afterRecorderStarted() = DebugSwitchesOff.afterRecorderStarted(context)
        override fun note(line: String) = ReviveLog.note(context, line)
        override fun onWifi() = NetworkRevive.onWifi(context)
        override fun inCall() = DaemonLauncher.callInProgress(context)
        override fun read(setting: String) = GlobalSettings.read(context, setting)
        override fun write(setting: String, on: Boolean) = GlobalSettings.set(context, setting, on)
        override fun adbd() = AdbdState.read()
        override fun cableStopped() = CableWatch.stoppedAdbd(context)
        override fun forgetCable() = CableWatch.forget(context)
        override fun switchedOnHere() = NetworkRevive.switchedOnHere(context)
        override fun noteSwitchedOn() = NetworkRevive.noteSwitchedOn(context)
        override fun refusedHere() = NetworkRevive.wirelessRefusedHere(context)
        override fun stillRefusedHere(on: Boolean) = NetworkRevive.stillRefusedHere(context, on)
        override fun noteRefused() = NetworkRevive.noteWirelessRefused(context)
        override fun forgetRefused() = NetworkRevive.forgetWirelessRefused(context)
        override fun switchedOff() = DebugSwitchesOff.switchedOff(context).isNotEmpty()
        override fun portStep() = StartWithoutWifi.step(context)
        override fun portReady() = StartWithoutWifi.ready(context)
        override fun portSet() = StartWithoutWifi.portSet()
        override fun portAnswers() = StartWithoutWifi.answers()
        override fun noteOpened(opened: Boolean) = StartWithoutWifi.noteOpened(context, opened)
        override fun noteClosed() = StartWithoutWifi.noteClosed(context)
        override fun noteCloseFailed() = StartWithoutWifi.noteCloseFailed(context)
        override val connected get() = AdbTransport.isConnected
        override val connectedPort get() = AdbTransport.connectedPort
        override val lastConnect get() = AdbTransport.lastConnect
        override suspend fun connect(port: Int) = AdbTransport.connect(context, AdbTransport.LOOPBACK, port)
        override suspend fun autoConnect(timeoutMs: Long) = AdbTransport.autoConnect(context, timeoutMs = timeoutMs)
        override suspend fun restartAdbd(service: String) = AdbTransport.restartAdbd(service)
        override suspend fun close() = AdbTransport.close()
    }
}
