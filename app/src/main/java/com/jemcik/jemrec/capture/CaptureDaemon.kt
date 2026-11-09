package com.jemcik.jemrec.capture

import android.content.Context
import com.jemcik.jemrec.recorder.DaemonClient
import com.jemcik.jemrec.recorder.DaemonLauncher
import com.jemcik.jemrec.recorder.NetworkRevive
import com.jemcik.jemrec.recorder.PortChange
import com.jemcik.jemrec.recorder.RecorderLifecycle
import com.jemcik.jemrec.recorder.Revive
import com.jemcik.jemrec.recorder.StartWithoutWifi
import com.jemcik.jemrec.system.AdbdState
import kotlinx.coroutines.flow.SharedFlow
import java.net.Socket

/**
 * Lifecycle of the shell-side capture daemon.
 *
 * The daemon is a jar bundled in this APK. ADB is used to START it and for
 * nothing else afterwards, which is the whole shape of the design and was
 * forced by measurement rather than chosen: with Wi-Fi off adbd tears its
 * listener down completely, and the shell UID may not set
 * service.adb.tcp.port to pin a stable one - only adbd's own `tcpip:` service
 * can, which is what Start without Wi-Fi asks it for (StartWithoutWifi). So
 * there is no ADB at the moment a call arrives, and anything that needed ADB
 * then would simply not record.
 *
 * What there IS at that moment is a process, because a process spawned from an
 * adb shell outlives the adb connection. Start it once, talk to it over
 * loopback forever.
 *
 * The names here are the ones every caller has used. Each now leads to where
 * the work is done, in the recorder package: DaemonClient (the protocol),
 * DaemonLauncher (whether the recorder is up and current, and starting it),
 * Revive (bringing it back) with PortChange (Start without Wi-Fi's port), and
 * RecorderLifecycle (the recorder's lock, and whether this phone is set up).
 */
object CaptureDaemon {

    internal const val ADBD_SETTLE_MS = Revive.ADBD_SETTLE_MS
    internal const val CONNECT_MS = Revive.CONNECT_MS
    const val PORT = DaemonClient.PORT
    const val MODE_OFF = DaemonClient.MODE_OFF
    const val MODE_AUTOMATIC = DaemonClient.MODE_AUTOMATIC
    const val MODE_ON_DEMAND = DaemonClient.MODE_ON_DEMAND

    internal typealias Answer = DaemonClient.Answer
    internal typealias QuitReply = DaemonClient.QuitReply
    internal typealias Watch = DaemonClient.Watch
    typealias Sims = DaemonClient.Sims
    typealias Request = Revive.Request
    internal typealias WirelessStep = Revive.WirelessStep

    // Up, current, started (DaemonLauncher).
    val changed: SharedFlow<Unit> get() = DaemonLauncher.changed
    suspend fun isRunning(context: Context): Boolean = DaemonLauncher.isRunning(context)
    suspend fun isRunningConfirmed(context: Context): Boolean = DaemonLauncher.isRunningConfirmed(context)
    internal suspend fun answer(context: Context): Answer? = DaemonLauncher.answer(context)
    internal fun isCurrent(context: Context, answer: Answer): Boolean = DaemonLauncher.upToDate(context, answer)
    suspend fun standDown(context: Context) = DaemonLauncher.standDown(context)
    internal fun inCall(mode: Int): Boolean = DaemonLauncher.inCall(mode)
    internal fun callInProgress(context: Context): Boolean = DaemonLauncher.callInProgress(context)
    suspend fun readLog(): String = DaemonLauncher.readLog()

    // The lock and what takes it (RecorderLifecycle).
    suspend fun revive(
        context: Context,
        onPortChange: (() -> Unit)? = null,
        request: Request = Request.ROUTINE,
    ): Boolean = RecorderLifecycle.revive(context, onPortChange, request)
    suspend fun askToAllowNetwork(context: Context): Boolean = RecorderLifecycle.askToAllowNetwork(context)
    suspend fun ensureRunning(context: Context): Result<Unit> = RecorderLifecycle.ensureRunning(context)
    fun isSetUp(context: Context): Boolean = RecorderLifecycle.isSetUp(context)

    // Bringing it back (Revive, PortChange).
    internal fun portChangeDue(
        context: Context,
        onWifi: Boolean = NetworkRevive.onWifi(context),
    ): StartWithoutWifi.PortStep? = PortChange.portChangeDue(context, onWifi)
    internal fun portChangeOver(change: StartWithoutWifi.PortStep?, connectedPort: Int): StartWithoutWifi.PortStep? =
        PortChange.portChangeOver(change, connectedPort)
    internal fun wirelessDebuggingStep(
        cableStoppedAdbd: Boolean,
        on: Boolean,
        onWifi: Boolean,
        refused: Boolean = false,
        adbdStopped: Boolean = false,
        switchedOnHere: Boolean = false,
        request: Request = Request.ROUTINE,
    ): WirelessStep = Revive.wirelessDebuggingStep(cableStoppedAdbd, on, onWifi, refused, adbdStopped, switchedOnHere, request)
    internal fun cycleAfterNotFound(
        adbd: AdbdState.State,
        step: WirelessStep,
        on: Boolean,
        onWifi: Boolean,
        switchedOnHere: Boolean,
        request: Request,
    ): Boolean = Revive.cycleAfterNotFound(adbd, step, on, onWifi, switchedOnHere, request)

    // The protocol (DaemonClient).
    fun token(context: Context): String = DaemonClient.token(context)
    internal fun parsePong(line: String?): Answer? = DaemonClient.parsePong(line)
    internal fun parseQuitReply(line: String?): QuitReply = DaemonClient.parseQuitReply(line)
    internal fun parseWatch(line: String?): Watch? = DaemonClient.parseWatch(line)
    internal fun parseSims(line: String?): Sims? = DaemonClient.parseSims(line)
    internal fun fields(line: String?, head: String): Map<String, String>? = DaemonClient.fields(line, head)
    internal fun currentMode(context: Context): Int = DaemonClient.currentMode(context)
    suspend fun quit(context: Context): Boolean = DaemonClient.quit(context)
    suspend fun pushMode(context: Context) = DaemonClient.pushMode(context)
    suspend fun sendMode(context: Context): Boolean = DaemonClient.sendMode(context)
    suspend fun setVoipApps(context: Context): Boolean = DaemonClient.setVoipApps(context)
    suspend fun setTracking(context: Context): Boolean = DaemonClient.setTracking(context)
    suspend fun setAnswering(context: Context): Boolean = DaemonClient.setAnswering(context)
    suspend fun skipCall(context: Context, skip: Boolean): Boolean = DaemonClient.skipCall(context, skip)
    suspend fun debuggingOff(context: Context, go: Boolean): DaemonClient.OffReply? = DaemonClient.debuggingOff(context, go)
    suspend fun goAhead(context: Context, delayMs: Int, rangAt: Long): String? = DaemonClient.goAhead(context, delayMs, rangAt)
    suspend fun handOver(context: Context, talk: Boolean): Boolean = DaemonClient.handOver(context, talk)
    suspend fun greetingBytes(context: Context): Long? = DaemonClient.greetingBytes(context)
    suspend fun startTake(context: Context): Boolean = DaemonClient.startTake(context)
    suspend fun stopTake(context: Context): Long? = DaemonClient.stopTake(context)
    suspend fun fetchTake(context: Context): ByteArray? = DaemonClient.fetchTake(context)
    suspend fun uploadGreeting(context: Context, pcm: ByteArray): Boolean = DaemonClient.uploadGreeting(context, pcm)
    suspend fun syncGreeting(context: Context): Boolean = DaemonClient.syncGreeting(context)
    suspend fun stageOne(context: Context): Map<String, String>? = DaemonClient.stageOne(context)
    suspend fun armTest(context: Context, incoming: Boolean, number: String = ""): DaemonClient.TestArming? =
        DaemonClient.armTest(context, incoming, number)
    suspend fun testResult(context: Context): Map<String, String>? = DaemonClient.testResult(context)
    suspend fun forwarding(context: Context): String? = DaemonClient.forwarding(context)
    suspend fun setForwarding(context: Context, seconds: Int): String? = DaemonClient.setForwarding(context, seconds)
    suspend fun sims(context: Context): Sims? = DaemonClient.sims(context)
    suspend fun connect(context: Context, timeoutMs: Int = 3_000): Socket = DaemonClient.connect(context, timeoutMs)
    suspend fun fetch(context: Context, name: String, timeoutMs: Int = 3_000): Socket =
        DaemonClient.fetch(context, name, timeoutMs)
    suspend fun listPendingOrNull(context: Context): List<String>? = DaemonClient.listPendingOrNull(context)
    suspend fun deleteRecording(context: Context, name: String): Boolean = DaemonClient.deleteRecording(context, name)
    suspend fun startOnDemand(context: Context): String? = DaemonClient.startOnDemand(context)
    internal suspend fun watch(context: Context): Watch? = DaemonClient.watch(context)
    internal suspend fun callReport(context: Context): String? = DaemonClient.callReport(context)
}
