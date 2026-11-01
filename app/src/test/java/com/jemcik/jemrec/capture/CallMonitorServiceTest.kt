package com.jemcik.jemrec.capture

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.provider.CallLog
import com.jemcik.jemrec.MainActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowLog

/**
 * The service the recorder wakes for each call (CallMonitorService), driven
 * the way the recorder drives it: intents carrying the token the app keeps in
 * its preferences (CaptureDaemon.token). What is pinned is what it does
 * today - which wakes it obeys, what its notification says, when it asks,
 * when it stands down, and what it tells the user after a save.
 *
 * No recorder runs here, so every question to it fails at once (nothing
 * listens on its port): a save ends "The recorder could not be reached.",
 * Talk to them and Record get no answer. The paths that need a recorder to
 * answer (a save that works, an empty recording) and the cable watch (a wake
 * lock and a restart of the recorder) are left out.
 *
 * Where a test must stop a save half way, the save is held inside its read of
 * the call log (FakeCallLog.hold): with a file name that has the number in it,
 * the save asks the log who the call was with, and waits there for the test.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class CallMonitorServiceTest {

    private val context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private lateinit var controller: ServiceController<CallMonitorService>
    private val service get() = controller.get()
    private var startId = 0

    /** A call a minute ago, inside the ledger's week. */
    private val start = System.currentTimeMillis() - 60_000

    @Before fun create() {
        ShadowLog.clear()
        controller = Robolectric.buildService(CallMonitorService::class.java).create()
    }

    @After fun destroy() {
        controller.destroy()
    }

    // ---- driving it --------------------------------------------------------

    /**
     * A wake as the recorder sends it (Main.wake): the service by name, the
     * action, the token, recfile ("" for none) and incoming, then the
     * action's own extras. The names are written out, not taken from the
     * service's constants: the recorder has its own copy of each.
     */
    private fun wake(
        action: String,
        recfile: String = "",
        incoming: Boolean = false,
        token: String? = CaptureDaemon.token(context),
        extras: Intent.() -> Unit = {},
    ) {
        val intent = Intent("com.jemcik.jemrec.$action")
            .setClassName(context, "com.jemcik.jemrec.capture.CallMonitorService")
            .putExtra("recfile", recfile)
            .putExtra("incoming", incoming)
        if (token != null) intent.putExtra("token", token)
        intent.extras()
        start(intent)
    }

    private fun start(intent: Intent?) {
        service.onStartCommand(intent, 0, ++startId)
    }

    /** A start from anywhere else: any app can send this exported service anything. */
    private fun stray(action: String?, ticket: String? = null) = start(
        Intent(context, CallMonitorService::class.java).setAction(action).apply {
            if (ticket != null) putExtra("ticket", ticket)
        },
    )

    private fun recfile(at: Long, incoming: Boolean, tag: String = "") =
        "jemrec_rec_${at}${tag}_${if (incoming) "in" else "out"}.dat"

    // ---- what it shows -----------------------------------------------------

    private fun ongoing(): Notification = shadowOf(service).lastForegroundNotification
    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    private fun Notification.buttons() = actions?.map { it.title.toString() }.orEmpty()

    /** IMMEDIATE or DEFERRED: the builder's choice, which Notification keeps
     *  in a field with no public getter. */
    private fun Notification.foregroundBehaviour(): Int =
        Notification::class.java.getDeclaredField("mFgsDeferBehavior").apply { isAccessible = true }.getInt(this)

    private fun prompt(): Notification? = shadowOf(manager).getNotification(3)
    private fun notRecorded(): Notification? = shadowOf(manager).getNotification(31)

    /** The intent a button of [n] sends when tapped. */
    private fun tap(n: Notification, label: String): Intent =
        shadowOf(n.actions.single { it.title.toString() == label }.actionIntent).savedIntent

    private val stopped get() = shadowOf(service).isStoppedBySelf

    private fun logs() = ShadowLog.getLogsForTag("JemRec").map { it.msg }
    private fun logged(part: String) = logs().any { part in it }

    /** Waits, up to ten seconds, for what the service does on its IO scope. */
    private fun eventually(what: String, done: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!done()) {
            if (System.nanoTime() > deadline) fail("never happened: $what")
            Thread.sleep(10)
        }
    }

    private fun awaitLog(part: String) = eventually("log \"$part\"") { logged(part) }

    /** For the rest of a job whose last log line has been seen: what it
     *  does after that line (a stop, its own end) takes microseconds. */
    private fun settle() = Thread.sleep(300)

    /** A save of a call at [at] that will wait in its call-log read (see the class comment). */
    private fun heldCallLog(at: Long, incoming: Boolean): FakeCallLog {
        shadowOf(context).grantPermissions(Manifest.permission.READ_CALL_LOG)
        FileNames.setFormat(context, FileNames.Format.DATE_AND_NUMBER)
        return FakeCallLog.install().apply {
            add(at, if (incoming) CallLog.Calls.INCOMING_TYPE else CallLog.Calls.OUTGOING_TYPE, seconds = 30, number = "+380441234567")
            hold()
        }
    }

    // ---- the service itself ------------------------------------------------

    @Test fun itsChannelIsQuietAndThePreV2OnesAreGone() {
        listOf("jemrec.monitor", "jemrec.ask", "jemrec.pairing").forEach {
            manager.createNotificationChannel(NotificationChannel(it, "old", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val second = Robolectric.buildService(CallMonitorService::class.java).create()
        try {
            val channel = manager.getNotificationChannel("jemrec.monitor.v2")!!
            assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
            assertFalse(channel.canShowBadge())
            listOf("jemrec.monitor", "jemrec.ask", "jemrec.pairing").forEach {
                assertNull("$it deleted", manager.getNotificationChannel(it))
            }
            // "Call not recorded" goes out on the problems channel, made here too.
            assertNotNull(manager.getNotificationChannel(AfterCall.PROBLEM_CHANNEL_ID))
        } finally {
            second.destroy()
        }
    }

    @Test fun runningIsTrueForTheServicesLife() {
        // CaptureDaemon.retire reads it, so as not to start the service by stopping it.
        assertTrue(CallMonitorService.running)
        controller.destroy()
        assertFalse(CallMonitorService.running)
        controller = Robolectric.buildService(CallMonitorService::class.java).create()
    }

    @Test fun everyStartPutsTheServiceInTheForegroundFirst() {
        // Every start of it is a startForegroundService, which owes this within
        // seconds - whatever it carries, and before any check.
        val starts = listOf<Pair<String, () -> Unit>>(
            "no intent" to { start(null) },
            "no action" to { stray(null) },
            "a wrong token" to { wake("CALL_STARTED", token = "forged") },
            "no token" to { wake("CALL_STARTED", token = null) },
            "Talk without a message" to { stray("com.jemcik.jemrec.TALK", ticket = "forged") },
            "Record without a prompt" to { stray("com.jemcik.jemrec.RECORD_NOW") },
            "a call" to { wake("CALL_STARTED") },
        )
        starts.forEach { (what, send) ->
            manager.cancel(1)
            send()
            assertNotNull("$what: notification 1 posted", shadowOf(manager).getNotification(1))
            assertEquals(1, shadowOf(service).lastForegroundNotificationId)
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, service.foregroundServiceType)
        }
    }

    // ---- the token ---------------------------------------------------------

    @Test fun aWakeWithAWrongTokenIsIgnored() {
        wake("CALL_STARTED", incoming = true, token = "forged")
        assertNull("no prompt", prompt())
        assertTrue(logged("monitor: com.jemcik.jemrec.CALL_STARTED with a bad token, ignoring"))
        // In the foreground anyway, as a call it knows nothing of; then gone.
        assertEquals("Call in progress", ongoing().title())
        assertTrue(stopped)
    }

    @Test fun aWakeWithNoTokenIsIgnored() {
        wake("CALL_STARTED", incoming = true, token = null)
        assertNull(prompt())
        assertTrue(logged("with a bad token, ignoring"))
        assertTrue(stopped)
    }

    @Test fun noWakeWithAWrongTokenChangesACallInProgress() {
        wake("CALL_STARTED", recfile(start, true), incoming = true)
        listOf("CALL_STARTED", "CALL_ENDED", "STOP_MONITOR", "MESSAGE_STARTED", "MESSAGE_TAKEN", "CABLE", "BACKGROUND_KILLED")
            .forEach { action ->
                wake(action, recfile(start, true), incoming = true, token = "forged") {
                    putExtra("test", true)
                    putExtra("started", start)
                    putExtra("cable", "in")
                }
                assertTrue(action, logged("monitor: com.jemcik.jemrec.$action with a bad token, ignoring"))
                assertEquals(action, "Recording call", ongoing().title())
                assertEquals(action, Notification.FOREGROUND_SERVICE_IMMEDIATE, ongoing().foregroundBehaviour())
                assertTrue(action, ongoing().buttons().isEmpty())
                assertNull(action, prompt())
                assertFalse(action, stopped)
            }
        assertTrue("no test call noted", CallLedger.read(context).testCalls.isEmpty())
    }

    @Test fun aCableOrBackgroundKillWakeWithAWrongTokenSaysNothingOfIt() {
        // Only the recorder's own wakes may make it say "Checking".
        wake("CABLE", token = "forged") { putExtra("cable", "out") }
        assertEquals("Call in progress", ongoing().title())
        assertEquals(Notification.FOREGROUND_SERVICE_IMMEDIATE, ongoing().foregroundBehaviour())
        wake("BACKGROUND_KILLED", token = "forged")
        assertEquals("Call in progress", ongoing().title())
        assertEquals(Notification.FOREGROUND_SERVICE_IMMEDIATE, ongoing().foregroundBehaviour())
        assertTrue(stopped)
    }

    @Test fun aStartWithNoActionMidCallDoesNotStopIt() {
        wake("CALL_STARTED", recfile(start, false))
        stray(null)
        assertTrue(logged("monitor: started with no action"))
        assertFalse("not a STOP that skips STOP's guard", stopped)
        assertEquals("Recording call", ongoing().title())
    }

    @Test fun aStartWithNoActionWhenIdleStandsDown() {
        start(null)
        assertTrue(logged("monitor: started with no action"))
        assertTrue(stopped)
    }

    @Test fun stopWithTheTokenStopsEvenMidCall() {
        wake("CALL_STARTED", recfile(start, false))
        // As the app sends it (CallMonitorService.stop).
        CallMonitorService.stop(context)
        val sent = shadowOf(context).nextStartedService
        assertEquals("com.jemcik.jemrec.STOP_MONITOR", sent.action)
        start(sent)
        assertTrue(stopped)
    }

    @Test fun stopWithAWrongTokenDoesNotStopACall() {
        wake("CALL_STARTED", recfile(start, false))
        wake("STOP_MONITOR", token = "forged")
        assertFalse(stopped)
    }

    // ---- a call starts -----------------------------------------------------

    @Test fun aCallTheRecorderIsNotRecordingAsksFirst() {
        wake("CALL_STARTED", "", incoming = true)
        val asking = prompt()!!
        assertEquals("Record this call?", asking.title())
        assertEquals("Incoming call in progress.", asking.text())
        assertEquals("jemrec.ask.v2", asking.channelId)
        assertEquals(listOf("Record", "Not now"), asking.buttons())
        val n = ongoing()
        assertEquals("Call in progress", n.title())
        assertEquals("JemRec is handling this call", n.text())
        assertEquals("jemrec.monitor.v2", n.channelId)
        assertTrue(n.buttons().isEmpty())
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(Notification.FOREGROUND_SERVICE_IMMEDIATE, n.foregroundBehaviour())
        // A tap opens the app.
        assertEquals(MainActivity::class.java.name, shadowOf(n.contentIntent).savedIntent.component?.className)
        // Both halves are what the emulator checks look for.
        assertTrue(logged("monitor: off-hook (incoming), asking whether to record"))
        assertFalse(stopped)
    }

    @Test fun thePromptNamesAnOutgoingOrMessengerCall() {
        wake("CALL_STARTED", "", incoming = false)
        assertEquals("Outgoing call in progress.", prompt()!!.text())
        wake("CALL_STARTED", "", incoming = false) { putExtra("app", "org.telegram.messenger") }
        assertEquals("Telegram call in progress.", prompt()!!.text())
        wake("CALL_STARTED", "", incoming = false) { putExtra("app", "com.example.unknown") }
        assertEquals("Messenger call in progress.", prompt()!!.text())
    }

    @Test fun aCallTheRecorderIsRecordingSaysSo() {
        wake("CALL_STARTED", recfile(start, false), incoming = false)
        assertNull("nothing to ask", prompt())
        assertEquals("Recording call", ongoing().title())
        assertEquals("Saving this call", ongoing().text())
        assertEquals(Notification.FOREGROUND_SERVICE_IMMEDIATE, ongoing().foregroundBehaviour())
        assertTrue(logged("monitor: off-hook (outgoing), the daemon is recording"))
        assertFalse(stopped)
    }

    // ---- the answering machine ---------------------------------------------

    @Test fun aMessageShowsTalkToThemAndHangUp() {
        wake("MESSAGE_STARTED", incoming = true) { putExtra("test", false) }
        val n = ongoing()
        assertEquals("Taking a message", n.title())
        assertEquals("Your greeting is playing; your microphone is muted", n.text())
        assertEquals(Notification.FOREGROUND_SERVICE_IMMEDIATE, n.foregroundBehaviour())
        assertEquals(listOf("Talk to them", "Hang up"), n.buttons())
        val tickets = n.actions.zip(listOf("com.jemcik.jemrec.TALK", "com.jemcik.jemrec.HANG_UP")).map { (button, action) ->
            val pending = shadowOf(button.actionIntent)
            assertTrue(pending.isServiceIntent)
            assertEquals(action, pending.savedIntent.action)
            assertEquals(CallMonitorService::class.java.name, pending.savedIntent.component?.className)
            assertEquals(action.hashCode(), pending.requestCode)
            pending.savedIntent.getStringExtra("ticket")
        }
        assertFalse(tickets[0].isNullOrEmpty())
        assertEquals("one ticket for both buttons", tickets[0], tickets[1])
        assertFalse(stopped)
    }

    @Test fun aTestCallOfTheMachineSaysTesting() {
        wake("MESSAGE_STARTED", incoming = true) { putExtra("test", true) }
        assertEquals("Testing the answering machine", ongoing().title())
        assertEquals("Your greeting is playing to the caller", ongoing().text())
        assertEquals(listOf("Talk to them", "Hang up"), ongoing().buttons())
    }

    @Test fun eachMessageHasANewTicket() {
        wake("MESSAGE_STARTED", incoming = true)
        // Read now: FLAG_UPDATE_CURRENT puts the next ticket into the same
        // PendingIntent, so the last notification's buttons carry it too.
        val old = tap(ongoing(), "Talk to them").getStringExtra("ticket")
        wake("MESSAGE_STARTED", incoming = true)
        assertNotEquals(old, tap(ongoing(), "Talk to them").getStringExtra("ticket"))
        stray("com.jemcik.jemrec.TALK", ticket = old)
        assertTrue(logged("monitor: com.jemcik.jemrec.TALK without this message's ticket, ignoring"))
        assertEquals("Taking a message", ongoing().title())
    }

    @Test fun theMessagesTitleComesBeforeTheRecordings() {
        wake("CALL_STARTED", recfile(start, true), incoming = true)
        wake("MESSAGE_STARTED", incoming = true)
        assertEquals("Taking a message", ongoing().title())
        wake("MESSAGE_STARTED", incoming = true) { putExtra("test", true) }
        assertEquals("Testing the answering machine", ongoing().title())
    }

    @Test fun aCallStartAfterAMessageEndsItAndAsks() {
        // Ask first, after a takeover: the message is over, the prompt asks
        // about the rest.
        wake("MESSAGE_STARTED", incoming = true) { putExtra("test", true) }
        val talk = tap(ongoing(), "Talk to them")
        wake("CALL_STARTED", "", incoming = true)
        assertEquals("Call in progress", ongoing().title())
        assertTrue(ongoing().buttons().isEmpty())
        assertNotNull(prompt())
        start(talk)
        assertTrue(logged("without this message's ticket, ignoring"))
    }

    @Test fun aTakeoverInRecordEveryCallSaysRecording() {
        wake("MESSAGE_STARTED", incoming = true)
        val talk = tap(ongoing(), "Talk to them")
        wake("MESSAGE_TAKEN", incoming = true)
        assertEquals("Recording call", ongoing().title())
        assertTrue(ongoing().buttons().isEmpty())
        start(talk)
        assertTrue("the message's buttons are spent", logged("without this message's ticket, ignoring"))
    }

    @Test fun aTakeoverInAskFirstWaitsForThePrompt() {
        RecordingMode.set(context, RecordingMode.ON_DEMAND)
        wake("MESSAGE_STARTED", incoming = true)
        wake("MESSAGE_TAKEN", incoming = true)
        // The recorder stopped at the takeover; its CALL_STARTED asks next.
        assertEquals("Call in progress", ongoing().title())
        assertTrue(ongoing().buttons().isEmpty())
        assertNull(prompt())
        assertFalse(stopped)
    }

    @Test fun aTakeoverMayBeTheFirstThisServiceHears() {
        wake("MESSAGE_TAKEN", incoming = true)
        assertEquals("Recording call", ongoing().title())
        wake("CALL_ENDED", "", incoming = true)
        assertTrue(stopped)
    }

    @Test fun talkAndHangUpWithNoMessageUpAreIgnored() {
        listOf("com.jemcik.jemrec.TALK", "com.jemcik.jemrec.HANG_UP").forEach { action ->
            stray(action, ticket = "anything")
            assertTrue(action, logged("monitor: $action without this message's ticket, ignoring"))
        }
        assertFalse(logged("talk to them ->"))
        assertFalse(logged("hang up ->"))
        assertTrue(stopped)
    }

    @Test fun talkAndHangUpWithAnotherTicketAreIgnored() {
        wake("MESSAGE_STARTED", incoming = true)
        listOf("com.jemcik.jemrec.TALK", "com.jemcik.jemrec.HANG_UP").forEach { action ->
            stray(action, ticket = "forged")
            stray(action)
        }
        assertEquals(4, logs().count { it.endsWith("without this message's ticket, ignoring") })
        assertEquals("Taking a message", ongoing().title())
        assertEquals(listOf("Talk to them", "Hang up"), ongoing().buttons())
        assertFalse(stopped)
    }

    @Test fun aTalkTheRecorderDoesNotConfirmLeavesTheMessageUp() {
        wake("MESSAGE_STARTED", incoming = true)
        start(tap(ongoing(), "Talk to them"))
        awaitLog("monitor: talk to them -> false")
        assertEquals("Taking a message", ongoing().title())
        // The ticket was not spent: the buttons still work.
        start(tap(ongoing(), "Hang up"))
        awaitLog("monitor: hang up -> false")
        assertFalse(logged("ignoring"))
        assertFalse(stopped)
    }

    // ---- the prompt's answer -----------------------------------------------

    @Test fun recordAndNotNowWithoutThePromptsTicketAreIgnored() {
        wake("CALL_STARTED", "", incoming = true)
        val record = tap(prompt()!!, "Record")
        listOf("com.jemcik.jemrec.RECORD_NOW", "com.jemcik.jemrec.DECLINE_RECORDING").forEach { action ->
            stray(action)
            stray(action, ticket = "forged")
            stray(action, ticket = "")
        }
        assertEquals(6, logs().count { it.endsWith("without this prompt's ticket, ignoring") })
        assertNotNull("the prompt stays up", prompt())
        assertFalse(stopped)
        // Its own ticket still answers it.
        assertTrue(RecordPrompt.claim(context, record.getStringExtra("ticket")))
    }

    @Test fun notNowTakesThePromptDownAndKeepsTheCall() {
        wake("CALL_STARTED", "", incoming = true)
        val notNow = tap(prompt()!!, "Not now")
        start(notNow)
        assertTrue(logged("monitor: user declined to record this call"))
        assertNull(prompt())
        assertFalse("the call is still up", stopped)
        assertEquals("Call in progress", ongoing().title())
        // One prompt, one answer.
        start(notNow)
        assertTrue(logged("without this prompt's ticket, ignoring"))
    }

    @Test fun recordThatTheRecorderCannotStartLeavesTheCallAsItWas() {
        wake("CALL_STARTED", "", incoming = true)
        start(tap(prompt()!!, "Record"))
        assertNull(prompt())
        awaitLog("monitor: on-demand start did not take (call over?)")
        assertEquals("Call in progress", ongoing().title())
        assertFalse(stopped)
    }

    @Test fun aRecordForACallThisServiceNeverSawKeepsItUp() {
        // The prompt outlives the service on purpose: its ticket is in the
        // preferences, so its answer lands in a service started afresh - the
        // process killed while the prompt sat waiting.
        RecordPrompt.show(context, incoming = true)
        start(tap(prompt()!!, "Record"))
        awaitLog("monitor: on-demand start did not take (call over?)")
        settle()
        // Pins today's behaviour, which looks like a bug: recordNow calls
        // finishIfIdle() without ending = its own job, which is still running,
        // so nothing stops the service and "Call in progress" stays up with
        // no call (to be fixed in its own commit)
        assertFalse(stopped)
        assertEquals("Call in progress", ongoing().title())
    }

    // ---- a call ends -------------------------------------------------------

    @Test fun anEndWithNothingRecordedTakesThePromptDownAndStandsDown() {
        wake("CALL_STARTED", "", incoming = true)
        val notNow = tap(prompt()!!, "Not now")
        wake("CALL_ENDED", "", incoming = true)
        assertTrue(logged("monitor: call ended, recfile="))
        assertNull(prompt())
        assertTrue(stopped)
        // The chance to answer went with the call.
        start(notNow)
        assertTrue(logged("without this prompt's ticket, ignoring"))
    }

    @Test fun theEndOfATestCallIsNotedInTheLedger() {
        val started = System.currentTimeMillis() - 42_000
        // As the recorder sends it: no recfile (the recording is deleted), test and started.
        wake("CALL_ENDED", "", incoming = true) {
            putExtra("test", true)
            putExtra("started", started)
        }
        val noted = CallLedger.read(context).testCalls.single()
        assertEquals(started, noted.startMillis)
        assertTrue(noted.incoming)
        assertTrue("about 42 s: ${noted.seconds}", noted.seconds in 42..45)
        assertTrue(logs().any { it.startsWith("monitor: a test call of the answering machine, ") && it.endsWith("s, noted") })
        assertTrue(stopped)
    }

    @Test fun anEndWithoutTestOrStartNotesNoTestCall() {
        wake("CALL_ENDED", "", incoming = true) { putExtra("test", true) }
        wake("CALL_ENDED", "", incoming = true) { putExtra("started", start) }
        wake("CALL_ENDED", "", incoming = true) {
            putExtra("test", true)
            putExtra("started", 0L)
        }
        assertTrue(CallLedger.read(context).testCalls.isEmpty())
    }

    @Test fun aRecordingMadeAfterTheSwitchWentOffIsDeletedNotSaved() {
        RecorderSwitch.set(context, false)
        val file = recfile(start, true)
        wake("CALL_STARTED", file, incoming = true)
        wake("CALL_ENDED", file, incoming = true)
        assertTrue(logged("monitor: recorder is off, discarding $file"))
        eventually("the service stands down") { stopped }
        assertFalse("not saved", logged("monitor: recording handled"))
        assertNull(notRecorded())
        assertTrue(CallLedger.read(context).accounted.isEmpty())
    }

    @Test fun aRecordingTheRecorderCannotHandOverIsReported() {
        val file = recfile(start, true)
        wake("CALL_STARTED", file, incoming = true)
        wake("CALL_ENDED", file, incoming = true)
        awaitLog("monitor: recording handled")
        val told = notRecorded()!!
        assertEquals("Call not recorded", told.title())
        assertEquals("The recorder could not be reached.", told.text())
        assertEquals(AfterCall.PROBLEM_CHANNEL_ID, told.channelId)
        assertEquals(MainActivity::class.java.name, shadowOf(told.contentIntent).savedIntent.component?.className)
        assertEquals("Not recording this call", ongoing().title())
        assertEquals("Tap to see why", ongoing().text())
        eventually("the service stands down") { stopped }
        // Left on the recorder for the reconcile: nothing in the ledger.
        assertTrue(CallLedger.read(context).accounted.isEmpty())
    }

    @Test fun aGreetingThatCouldNotPlayIsReported() {
        val file = recfile(start, true, tag = "_msgfail")
        wake("CALL_ENDED", file, incoming = true)
        awaitLog("monitor: recording handled")
        assertEquals("Call not recorded", notRecorded()!!.title())
        assertEquals(
            "The answering machine answered a call, but could not play your greeting, so it hung up. " +
                "Run the test call in Settings, Answering machine.",
            notRecorded()!!.text(),
        )
        assertEquals("Not recording this call", ongoing().title())
        assertEquals(listOf(CallLedger.Recorded(start, true, 0)), CallLedger.read(context).greetingFailures)
        eventually("the service stands down") { stopped }
    }

    @Test fun aCallerWhoLeftDuringTheGreetingIsNotReported() {
        val file = recfile(start, true, tag = "_msgnone")
        wake("CALL_ENDED", file, incoming = true)
        awaitLog("monitor: recording handled")
        assertTrue(logged("monitor: $file - the caller hung up during the greeting"))
        assertNull(notRecorded())
        // Not posted again: it says what it said when the end arrived.
        assertEquals("Call in progress", ongoing().title())
        assertEquals(listOf(CallLedger.Recorded(start, true, 0)), CallLedger.read(context).greetingHangUps)
        eventually("the service stands down") { stopped }
    }

    @Test fun aRecordingSavedBeforeIsNotReportedAgain() {
        CallLedger.noteRecording(context, start, false, 30)
        val file = recfile(start, false)
        wake("CALL_ENDED", file, incoming = false)
        awaitLog("monitor: recording handled")
        assertTrue(logged("monitor: $file was collected already"))
        assertNull(notRecorded())
        eventually("the service stands down") { stopped }
    }

    @Test fun aRecordingAlreadyBeingSavedIsLeftToThatSave() {
        val log = heldCallLog(start, incoming = true)
        try {
            val file = recfile(start, true)
            wake("CALL_ENDED", file, incoming = true)
            log.awaitHeld()
            // The same end again - a wake the recorder sent twice, say: this
            // process is saving that file already (RecordingSaver.Outcome.Busy).
            wake("CALL_ENDED", file, incoming = true)
            awaitLog("monitor: $file is being saved by the reconcile")
            awaitLog("monitor: recording handled")
            assertNull("not a failure to report", notRecorded())
            // The newer save is the current one, and it is done: the service
            // stands down with the first still running. On a phone onDestroy
            // would then cancel that save half way (inferred, not measured).
            eventually("the service stands down") { stopped }
            log.release()
            eventually("the first save") { logs().count { it == "monitor: recording handled" } == 2 }
        } finally {
            log.release()
        }
    }

    @Test fun aSaveDoesNotStopTheServiceUnderTheNextCall() {
        val log = heldCallLog(start, incoming = true)
        try {
            val file = recfile(start, true)
            wake("CALL_STARTED", file, incoming = true)
            wake("CALL_ENDED", file, incoming = true)
            log.awaitHeld()
            wake("CALL_STARTED", "", incoming = false)
            log.release()
            awaitLog("monitor: recording handled")
            settle()
            assertFalse(stopped)
            // That call's end, nothing else running, does.
            wake("CALL_ENDED", "", incoming = false)
            assertTrue(stopped)
        } finally {
            log.release()
        }
    }

    // ---- bugs, pinned as they are ------------------------------------------

    @Test fun aFailedSaveSaysNotRecordingOverTheNextCallBeingRecorded() {
        val log = heldCallLog(start, incoming = true)
        try {
            val first = recfile(start, true)
            wake("CALL_STARTED", first, incoming = true)
            wake("CALL_ENDED", first, incoming = true)
            log.awaitHeld()
            // The next call, recorded from its start, while the last one saves.
            wake("CALL_STARTED", recfile(start + 50_000, false), incoming = false)
            assertEquals("Recording call", ongoing().title())
            log.release()
            awaitLog("monitor: recording handled")
            assertEquals("The recorder could not be reached.", notRecorded()!!.text())
            // Pins today's behaviour, which looks like a bug: the first call's
            // failed save clears recordingNow and sets notRecording for the
            // call being recorded now, which then says "Not recording this
            // call" (to be fixed in its own commit)
            assertEquals("Not recording this call", ongoing().title())
            assertEquals("Tap to see why", ongoing().text())
            settle()
            assertFalse(stopped)
        } finally {
            log.release()
        }
    }

    @Test fun aFailedSaveSticksToTheCallsAfterIt() {
        val log = heldCallLog(start, incoming = true)
        try {
            // A fails while B is up, and B is saving when C starts.
            val a = recfile(start, true)
            val bAt = start + 40_000
            val b = recfile(bAt, false)
            log.add(bAt, CallLog.Calls.OUTGOING_TYPE, seconds = 10, number = "+380441234568")
            wake("CALL_STARTED", a, incoming = true)
            wake("CALL_ENDED", a, incoming = true)
            log.awaitHeld()
            wake("CALL_STARTED", b, incoming = false)
            log.release()
            awaitLog("monitor: recording handled")
            log.hold()
            wake("CALL_ENDED", b, incoming = false)
            log.awaitHeld()
            // C, in Ask first: never recorded, and nothing of it failed.
            wake("CALL_STARTED", "", incoming = true)
            assertNotNull(prompt())
            // Pins today's behaviour, which looks like a bug: notRecording is
            // never cleared, so C says "Not recording this call - Tap to see
            // why" for A's failure, two calls back, and goes on saying it
            // after its own Not now (to be fixed in its own commit)
            assertEquals("Not recording this call", ongoing().title())
            assertEquals("Tap to see why", ongoing().text())
            start(tap(prompt()!!, "Not now"))
            assertEquals("Not recording this call", ongoing().title())
            log.release()
            eventually("B's save") { logs().count { it == "monitor: recording handled" } == 2 }
        } finally {
            log.release()
        }
    }

    @Test fun aMessageAndThenARecordingOutrankNotRecording() {
        val log = heldCallLog(start, incoming = true)
        try {
            val first = recfile(start, true)
            wake("CALL_STARTED", first, incoming = true)
            wake("CALL_ENDED", first, incoming = true)
            log.awaitHeld()
            // The answering machine takes the next call while the last one saves.
            wake("MESSAGE_STARTED", incoming = true)
            log.release()
            awaitLog("monitor: recording handled")
            assertEquals("Taking a message", ongoing().title())
            assertEquals(listOf("Talk to them", "Hang up"), ongoing().buttons())
            // Taken over, in Record every call.
            wake("MESSAGE_TAKEN", incoming = true)
            assertEquals("Recording call", ongoing().title())
        } finally {
            log.release()
        }
    }

    @Test fun theBackgroundKillWakeSaysCheckingAndStandsDownAfter() {
        wake("BACKGROUND_KILLED")
        assertEquals("Checking JemRec", ongoing().title())
        assertEquals("Making sure it keeps running", ongoing().text())
        // Over in a moment, so it waits, and is never seen.
        assertEquals(Notification.FOREGROUND_SERVICE_DEFERRED, ongoing().foregroundBehaviour())
        eventually("the service stands down after the warning") { stopped }
    }

    @Test fun aCallsOwnNotificationBeatsTheWarningWake() {
        wake("CALL_STARTED", recfile(start, true), incoming = true)
        wake("BACKGROUND_KILLED")
        assertEquals("Recording call", ongoing().title())
        assertEquals(Notification.FOREGROUND_SERVICE_IMMEDIATE, ongoing().foregroundBehaviour())
        settle()
        assertFalse("the call keeps it", stopped)
    }

    @Test fun aStrayStartWaitsForTheBackgroundKillWarning() {
        // The warning is held where it starts: BackgroundKill's checks are
        // synchronized on it, and this thread holds it.
        synchronized(BackgroundKill) {
            wake("BACKGROUND_KILLED")
            stray(null)
            wake("CALL_STARTED", token = "forged")
            assertFalse("finishIfIdle counts the warning", stopped)
        }
        eventually("the service stands down after the warning") { stopped }
    }

    @Test fun aSaveThatEndsStopsTheServiceUnderTheBackgroundKillWarning() {
        val log = heldCallLog(start, incoming = true)
        try {
            synchronized(BackgroundKill) {
                wake("BACKGROUND_KILLED")
                val file = recfile(start, true)
                wake("CALL_ENDED", file, incoming = true)
                // Held until its job is in place, so that doneWith sees the
                // save as the one that counts.
                log.awaitHeld()
                log.release()
                awaitLog("monitor: recording handled")
                // Re-posted while the warning still runs: the warning's title
                // comes first, over "Not recording this call".
                assertEquals("Checking JemRec", ongoing().title())
                assertEquals(Notification.FOREGROUND_SERVICE_DEFERRED, ongoing().foregroundBehaviour())
                assertEquals("Call not recorded", notRecorded()!!.title())
                // Pins today's behaviour, which looks like a bug: doneWith
                // stops the service while the background-kill warning still
                // runs (held here), where finishIfIdle waits for it - the
                // freeze its comment describes (to be fixed in its own commit)
                eventually("the save's end stops the service") { stopped }
            }
            // The warning, let go, ends before the next test.
            settle()
        } finally {
            log.release()
        }
    }
}
