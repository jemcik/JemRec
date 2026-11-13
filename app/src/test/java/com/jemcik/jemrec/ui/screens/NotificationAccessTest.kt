package com.jemcik.jemrec.ui.screens

import android.app.NotificationManager
import android.content.ComponentName
import androidx.lifecycle.viewModelScope
import com.jemcik.jemrec.capture.DebugNotificationCleaner
import com.jemcik.jemrec.capture.FakeRecorder
import com.jemcik.jemrec.setup.Setup
import com.jemcik.jemrec.ui.grantProblem
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/**
 * Notification access for the app's listener, after setup: what Show who
 * called and Hide the debugging notification need
 * (Setup.grantNotificationAccess).
 * The recorder grants it - the shell, with no ADB session - and a session's
 * way is left for no answer. On the OnePlus, on Wi-Fi, Show who called said
 * "it needs Wi-Fi": the grant opened a session, which needs Wireless
 * debugging, and LineageOS had switched that off by itself (2026-11-12).
 * The recorder is a FakeRecorder, and the session a stand-in
 * (Setup.grantOverSession): no adbd is reached.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36], qualifiers = ScreenTest.PHONE)
class NotificationAccessTest : ScreenTest() {

    private val app = RuntimeEnvironment.getApplication()
    private var recorder: FakeRecorder? = null
    private val realSession = Setup.grantOverSession

    /** What the stand-in session's shell prints; null, no session. */
    private var sessionSays: String? = null

    /** Whether the stand-in session's grant reaches Android. */
    private var sessionGrants = false
    private val sessionAsked = java.util.concurrent.atomic.AtomicInteger()

    @Before fun aSessionStandIn() {
        Setup.grantOverSession = {
            sessionAsked.incrementAndGet()
            if (sessionGrants) grantAndroidsSide()
            sessionSays
        }
    }

    @After fun recorderGone() {
        recorder?.close()
        Setup.grantOverSession = realSession
    }

    private fun grantAndroidsSide() {
        shadowOf(app.getSystemService(NotificationManager::class.java))
            .setNotificationListenerAccessGranted(ComponentName(app, DebugNotificationCleaner::class.java), true)
    }

    /** A recorder that grants it as the recorder does: Android then lists
     *  the listener as allowed. */
    private fun grantingRecorder(): FakeRecorder = FakeRecorder(app).also { fake ->
        recorder = fake
        fake.replies["B"] = "LISTENER ok"
        fake.stall = { key -> if (key == "B") grantAndroidsSide() }
    }

    @Test fun theRecorderGrantsItWithNoAdbSession() = runBlocking {
        val fake = grantingRecorder()

        assertNull(Setup.grantNotificationAccess(app))

        assertEquals(listOf("B"), fake.heard.map { it.key })
        assertTrue(DebugNotificationCleaner.accessGranted(app))
        assertEquals("a session asked", 0, sessionAsked.get())
    }

    /** The recorder read Android's list before Android wrote it - the list
     *  is saved a moment after the grant - and said failed; Android has
     *  allowed it, and this process's own read says so: granted. */
    @Test fun androidsOwnWordDecidesOverTheRecordersReadOfItsList() = runBlocking {
        recorder = FakeRecorder(app).also { fake ->
            fake.replies["B"] = "LISTENER failed"
            fake.stall = { key -> if (key == "B") grantAndroidsSide() }
        }

        assertNull(Setup.grantNotificationAccess(app))
        assertEquals("a session asked", 0, sessionAsked.get())
    }

    /** The recorder said ok, and this process's read says Android has not
     *  allowed it - another user or profile than the one the shell granted
     *  it for, say: refused, and no session tried. */
    @Test fun aRecordersOkIsNotTakenWithoutAndroidsWord() = runBlocking {
        recorder = FakeRecorder(app).also { it.replies["B"] = "LISTENER ok" }
        sessionSays = "granted"
        sessionGrants = true

        assertEquals(Setup.GrantProblem.REFUSED, Setup.grantNotificationAccess(app))
        assertEquals("a session asked", 0, sessionAsked.get())
    }

    /** Asked, and Android said no to the recorder: that is the answer. A
     *  session's shell would be told the same, so none is tried. */
    @Test fun aRecorderTurnedDownIsTheAnswer() = runBlocking {
        recorder = FakeRecorder(app).also { it.replies["B"] = "LISTENER failed" }
        sessionSays = "granted"

        assertEquals(Setup.GrantProblem.REFUSED, Setup.grantNotificationAccess(app))
        assertEquals("a session asked", 0, sessionAsked.get())
    }

    /** No recorder: the session's way, as before 0.31 - granted where it
     *  can be had. */
    @Test fun noRecorderTakesTheSession() = runBlocking {
        sessionSays = "granted"
        sessionGrants = true

        assertNull(Setup.grantNotificationAccess(app))
        assertEquals(1, sessionAsked.get())
    }

    /** ...whose shell said granted - `cmd` ends well after an exception too -
     *  where Android has not allowed it: this process's read decides. */
    @Test fun aSessionsGrantedIsNotTakenWithoutAndroidsWord() = runBlocking {
        sessionSays = "granted"

        assertEquals(Setup.GrantProblem.REFUSED, Setup.grantNotificationAccess(app))
    }

    /** The recorder granted it and its reply was lost, and no session could
     *  be had: Android has allowed it, so it is granted, not "did not
     *  answer". */
    @Test fun aLostReplyAfterTheGrantIsGranted() = runBlocking {
        recorder = FakeRecorder(app).also { fake ->
            fake.replies.remove("B")
            fake.stall = { key -> if (key == "B") grantAndroidsSide() }
        }

        assertNull(Setup.grantNotificationAccess(app))
    }

    /** ...a session whose shell did not grant it: refused. */
    @Test fun aSessionThatDidNotGrantItIsARefusal() = runBlocking {
        sessionSays = "Error: java.lang.SecurityException"

        assertEquals(Setup.GrantProblem.REFUSED, Setup.grantNotificationAccess(app))
    }

    /** No recorder, and no session to be had: no answer. */
    @Test fun noRecorderAndNoSessionIsNoAnswer() = runBlocking {
        assertEquals(Setup.GrantProblem.NO_ANSWER, Setup.grantNotificationAccess(app))
        assertEquals(1, sessionAsked.get())
    }

    /** A recorder before 0.31 - up, recording - hangs up on the command: no
     *  answer, and the session's way is taken (none here). Not "the
     *  recorder is not running": it is. */
    @Test fun anOlderRecorderThatHangsUpIsNoAnswer() = runBlocking {
        val fake = FakeRecorder(app).also { recorder = it }
        fake.replies.remove("B")

        assertEquals(Setup.GrantProblem.NO_ANSWER, Setup.grantNotificationAccess(app))
        assertEquals(listOf("B"), fake.heard.map { it.key })
        assertEquals(1, sessionAsked.get())
    }

    /** The session's steps (Setup.grantOver), as AdbTransport takes them. */
    private class Session(override var open: Boolean, val connects: Boolean, val result: Result<String>) : Setup.GrantSession {
        val ran = mutableListOf<String>()
        var connected = 0
        var closed = 0
        override suspend fun connect(): Boolean = connects.also { connected++ }
        override suspend fun exec(command: String): Result<String> = result.also { ran += command }
        override suspend fun close() {
            closed++
        }
    }

    @Test fun aSessionOpenedForTheGrantIsClosedAfter() = runBlocking {
        val session = Session(open = false, connects = true, result = Result.success("granted"))

        assertEquals("granted", Setup.grantOver(session, "cmd notification allow_listener x"))

        assertEquals(listOf("cmd notification allow_listener x && echo granted"), session.ran)
        assertEquals(1, session.connected)
        assertEquals(1, session.closed)
    }

    @Test fun aSessionOpenAlreadyIsLeftOpen() = runBlocking {
        val session = Session(open = true, connects = true, result = Result.success("granted"))

        assertEquals("granted", Setup.grantOver(session, "x"))

        assertEquals(0, session.connected)
        assertEquals(0, session.closed)
    }

    /** No session to be had: no answer, and nothing to close. */
    @Test fun noSessionIsNoAnswer() = runBlocking {
        val session = Session(open = false, connects = false, result = Result.success("granted"))

        assertNull(Setup.grantOver(session, "x"))

        assertTrue(session.ran.isEmpty())
        assertEquals(0, session.closed)
    }

    /** A session whose connect and command each wait for the test, and whose
     *  close switches thread, as AdbTransport's does - which a cancelled
     *  coroutine is not let do unless the grant is shielded from the cancel. */
    private class HeldSession : Setup.GrantSession {
        val inConnect = kotlinx.coroutines.CompletableDeferred<Unit>()
        val connectGoes = kotlinx.coroutines.CompletableDeferred<Unit>()
        val inCommand = kotlinx.coroutines.CompletableDeferred<Unit>()
        val commandGoes = kotlinx.coroutines.CompletableDeferred<Unit>()
        val ran = java.util.concurrent.CopyOnWriteArrayList<String>()
        val closed = java.util.concurrent.atomic.AtomicInteger()

        @Volatile override var open = false
        override suspend fun connect(): Boolean {
            inConnect.complete(Unit)
            connectGoes.await()
            open = true
            return true
        }
        override suspend fun exec(command: String): Result<String> {
            ran += command
            inCommand.complete(Unit)
            commandGoes.await()
            return Result.success("granted")
        }
        override suspend fun close() {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                open = false
                closed.incrementAndGet()
            }
        }
    }

    /** The screen that asked closed from Recents in the command: the grant
     *  runs to its end, and closes the session it opened. */
    @Test fun aGrantCancelledInTheCommandRunsToItsEndAndCloses() = runBlocking {
        val session = HeldSession()
        session.connectGoes.complete(Unit)
        val said = java.util.concurrent.atomic.AtomicReference<String?>()
        val grant = launch(kotlinx.coroutines.Dispatchers.Default) { said.set(Setup.grantOver(session, "x")) }
        session.inCommand.await()

        grant.cancel()
        session.commandGoes.complete(Unit)
        grant.join()

        assertEquals("the command cut", "granted", said.get())
        assertEquals(1, session.closed.get())
        assertFalse(session.open)
    }

    /** ...closed in the connect, as it came up: the grant goes on, and the
     *  session is closed, not left open. */
    @Test fun aGrantCancelledInTheConnectRunsToItsEndAndCloses() = runBlocking {
        val session = HeldSession()
        session.commandGoes.complete(Unit)
        val grant = launch(kotlinx.coroutines.Dispatchers.Default) { Setup.grantOver(session, "x") }
        session.inConnect.await()

        grant.cancel()
        session.connectGoes.complete(Unit)
        grant.join()

        assertEquals(listOf("x && echo granted"), session.ran.toList())
        assertEquals(1, session.closed.get())
        assertFalse(session.open)
    }

    /** A session that broke - Wireless debugging going off mid-command - is
     *  no word from Android: no answer, not a refusal. */
    @Test fun aSessionThatBrokeIsNoAnswer() = runBlocking {
        val session = Session(open = false, connects = true, result = Result.failure(java.io.IOException("Stream closed")))

        assertNull(Setup.grantOver(session, "x"))

        assertEquals(1, session.closed)
    }

    /** The screen closed from Recents while the recorder grants it: Android
     *  allows it, and the switch is saved as on - not left off over an
     *  access Android gave. */
    @Test fun showWhoCalledIsSavedWhenTheScreenClosesInTheGrant() {
        val inGrant = java.util.concurrent.CountDownLatch(1)
        val grantGoes = java.util.concurrent.CountDownLatch(1)
        recorder = FakeRecorder(app).also { fake ->
            fake.replies["B"] = "LISTENER ok"
            fake.stall = { key ->
                if (key == "B") {
                    inGrant.countDown()
                    grantGoes.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    grantAndroidsSide()
                }
            }
        }
        val vm = viewModel()

        vm.setVoipNames(true)
        awaitMain("the grant asked") { inGrant.count == 0L }
        vm.viewModelScope.cancel()
        grantGoes.countDown()

        awaitMain("Show who called saved", timeoutMs = 5_000) { com.jemcik.jemrec.calls.VoipCallNotes.enabled(app) }
    }

    @Test fun showWhoCalledIsTurnedOnThroughTheRecorder() {
        grantingRecorder()
        val vm = viewModel()

        vm.setVoipNames(true)
        settle(vm)

        assertTrue(vm.state.value.voipNames)
        assertNull(vm.state.value.voipNamesProblem)
    }

    @Test fun showWhoCalledSaysTheRecorderDidNotAnswer() {
        val vm = viewModel()

        vm.setVoipNames(true)
        settle(vm)

        assertFalse(vm.state.value.voipNames)
        assertEquals(Setup.GrantProblem.NO_ANSWER, vm.state.value.voipNamesProblem)
    }

    @Test fun hidingTheDebuggingNotificationIsTurnedOnThroughTheRecorder() {
        Setup.markComplete(app)
        grantingRecorder()
        val vm = viewModel()

        vm.setHideDebugNotification(true)
        settle(vm)

        assertTrue(vm.state.value.debugNotificationHidden)
        assertNull(vm.state.value.debugNotificationProblem)
    }

    @Test fun hidingTheDebuggingNotificationSaysTheRecorderDidNotAnswer() {
        Setup.markComplete(app)
        val vm = viewModel()

        vm.setHideDebugNotification(true)
        settle(vm)

        assertFalse(vm.state.value.debugNotificationHidden)
        assertEquals(Setup.GrantProblem.NO_ANSWER, vm.state.value.debugNotificationProblem)
    }

    /** Under both switches: what is missing, and never Wi-Fi - the recorder
     *  needs none, and Wi-Fi was there when it said so. */
    @Test fun theWordsSayWhatIsMissing() {
        assertEquals(
            "Could not turn this on: the recorder did not answer. Try again once JemRec is ready to record.",
            grantProblem(Setup.GrantProblem.NO_ANSWER),
        )
        assertEquals("Could not turn this on: Android refused it.", grantProblem(Setup.GrantProblem.REFUSED))
        assertFalse(Setup.GrantProblem.entries.any { "Wi-Fi" in grantProblem(it) })
    }
}
