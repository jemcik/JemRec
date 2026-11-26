package com.jemcik.jemrec.ui

import android.app.Notification
import android.app.NotificationManager
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.jemcik.jemrec.setup.PairingNotification
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The pairing notification's service stopped by Android at its cap
 * (onTimeout) while a pairing runs: the card says what was cut short there,
 * and offers the code field again only where nothing is paired yet. One
 * text, "Paired! I could not connect in time ... it will say which switch to
 * turn on", was false while still pairing and in setup's last step.
 *
 * And which of the card's words Android is given, in what order: what the
 * foreground posts comes out of a queue, so a notify() right after it may be
 * shown first (PairingService.onStartCommand).
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36], shadows = [PairingServiceTest.Told::class])
class PairingServiceTest {

    /** What the service gives Android, in order: the words it goes in front
     *  with, and those on the card as it leaves. */
    @Implements(Service::class)
    class Told : ShadowService() {
        @Implementation(minSdk = Build.VERSION_CODES.Q)
        override fun startForeground(id: Int, notification: Notification, foregroundServiceType: Int) {
            if (refused) throw IllegalStateException("Service.startForeground() not allowed")
            if (dropped) return
            told += "front: ${described(notification)}"
            super.startForeground(id, notification, foregroundServiceType)
        }

        @Implementation(minSdk = Build.VERSION_CODES.N)
        override fun stopForeground(notificationBehavior: Int) {
            told += "leave: ${card()?.let(::described)}"
            super.stopForeground(notificationBehavior)
        }
    }

    private val app = RuntimeEnvironment.getApplication()

    @Before fun nothingTold() {
        told.clear()
        refused = false
        dropped = false
    }

    /** Steps that go through to [holdAt] and wait there for good. */
    private class Held(val holdAt: String) : PairingService.Steps {
        val reached = CountDownLatch(1)
        private val never = CompletableDeferred<Unit>()

        private suspend fun step(name: String) {
            if (name == holdAt) {
                reached.countDown()
                never.await()
            }
        }

        override suspend fun discover(app: Context): Int? = 37_000.also { step("discover") }
        override suspend fun pair(app: Context, port: Int, code: String): Boolean = true.also { step("pair") }
        override suspend fun connect(service: PairingService, app: Context): Boolean = true.also { step("connect") }
        override suspend fun finish(app: Context): Boolean = true.also { step("finish") }
    }

    @After fun realSteps() {
        PairingService.steps = PairingService.RealSteps
    }

    /** A code typed into the card, as Android delivers it. */
    private fun typed(code: String) = Intent().also {
        RemoteInput.addResultsToIntent(
            arrayOf(RemoteInput.Builder(PairingNotification.KEY_CODE).build()),
            it,
            Bundle().apply { putCharSequence(PairingNotification.KEY_CODE, code) },
        )
    }

    /** The card's words after the cap at [step], and whether it takes a code. */
    private fun cappedAt(step: String): Pair<String, Boolean> {
        val held = Held(step)
        PairingService.steps = held
        val controller = Robolectric.buildService(PairingService::class.java, typed("123456")).create().startCommand(0, 1)
        assertTrue("the pairing reached $step", held.reached.await(5, TimeUnit.SECONDS))

        controller.get().onTimeout(1)
        // As Android ends the service after the cap: the run held in its
        // step is cancelled, and what its catch makes of that is on the card
        // too - "Something went wrong: Job was cancelled", before 1.0.
        val run = controller.get().run!!
        controller.destroy()
        runBlocking { withTimeout(5_000) { run.join() } }

        return words(card()!!) to takesACode(card()!!)
    }

    /** Anything but six digits, the code field left empty too: what was
     *  wrong, and the field again, are all the card is ever given - by the
     *  foreground itself, as it goes in front and as it leaves. */
    @Test fun aCodeThatIsNotSixDigitsIsAnsweredByTheForegroundsOwnPost() {
        for (code in listOf("12345", "1234567", "", "pin")) {
            told.clear()
            val controller = Robolectric.buildService(PairingService::class.java, typed(code)).create().startCommand(0, 1)
            assertEquals(code, listOf("front: $WRONG", "leave: $WRONG"), told)
            assertEquals(code, WRONG, described(card()!!))
            assertNull("nothing is paired", controller.get().run)
            assertTrue(shadowOf(controller.get()).isStoppedBySelf)
            controller.destroy()
        }
    }

    /** No code at all in the start: the same. */
    @Test fun aStartWithNoCodeIsAnsweredTheSame() {
        val controller = Robolectric.buildService(PairingService::class.java, Intent()).create().startCommand(0, 1)
        assertEquals(listOf("front: $WRONG", "leave: $WRONG"), told)
        controller.destroy()
    }

    /** ...and with the foreground refused, or its post dropped without a
     *  word as under Battery Restricted, by the plain card. */
    @Test fun aWrongCodeIsAnsweredWithoutTheForeground() {
        for (silently in listOf(false, true)) {
            refused = !silently
            dropped = silently
            PairingNotification.dismiss(app)
            val controller = Robolectric.buildService(PairingService::class.java, typed("12345")).create().startCommand(0, 1)
            assertEquals(WRONG, described(card()!!))
            assertTrue(shadowOf(controller.get()).isStoppedBySelf)
            controller.destroy()
        }
    }

    /** A second code while the first is tried changes nothing on the card:
     *  not the words of the pairing under way, not a wrong code's either. */
    @Test fun aSecondCodeLeavesTheCardToThePairingUnderWay() {
        val held = Held("finish")
        PairingService.steps = held
        val controller = Robolectric.buildService(PairingService::class.java, typed("123456")).create().startCommand(0, 1)
        assertTrue(held.reached.await(5, TimeUnit.SECONDS))
        val first = controller.get().run
        val says = words(card()!!)
        assertEquals("Paired. Starting the recorder…", says)
        for (code in listOf("654321", "12")) {
            controller.withIntent(typed(code)).startCommand(0, 2)
            assertEquals(code, says, described(card()!!))
            assertSame("one pairing", first, controller.get().run)
            assertFalse(shadowOf(controller.get()).isStoppedBySelf)
        }
        assertEquals(listOf("front: ${PairingService.PAIRING}", "front: $says", "front: $says"), told)
        controller.destroy()
        runBlocking { withTimeout(5_000) { first!!.join() } }
    }

    /** However a pairing fails, its words are on the card by the time the
     *  foreground is left, which may post the card once more. */
    @Test fun aFailedPairingsWordsAreOnTheCardBeforeTheForegroundIsLeft() {
        val failures = mapOf(
            "No pairing dialog found. Open it in Settings and tap Enter code again. [field]" to steps(port = null),
            "Pairing failed. Reopen the dialog for a fresh code and try again. [field]" to steps(pairs = false),
            timedOut(PairingService.Stage.CONNECTING) to steps(connects = false),
            "Paired, but the recorder did not start. Reopen JemRec - it will finish on its own." to steps(finishes = false),
        )
        for ((said, steps) in failures) {
            told.clear()
            PairingService.steps = steps
            val controller = Robolectric.buildService(PairingService::class.java, typed("123456")).create().startCommand(0, 1)
            runBlocking { withTimeout(5_000) { controller.get().run!!.join() } }
            assertEquals(listOf("front: ${PairingService.PAIRING}", "leave: $said"), told)
            assertEquals(said, described(card()!!))
            controller.destroy()
        }
    }

    private fun steps(port: Int? = 37_000, pairs: Boolean = true, connects: Boolean = true, finishes: Boolean = true) =
        object : PairingService.Steps {
            override suspend fun discover(app: Context): Int? = port
            override suspend fun pair(app: Context, port: Int, code: String): Boolean = pairs
            override suspend fun connect(service: PairingService, app: Context): Boolean = connects
            override suspend fun finish(app: Context): Boolean = finishes
        }

    @Test fun cutShortPairingItSaysPairingDidNotFinish() {
        assertEquals(timedOut(PairingService.Stage.PAIRING) to true, cappedAt("pair"))
    }

    @Test fun cutShortConnectingItSaysItCouldNotConnect() {
        assertEquals(timedOut(PairingService.Stage.CONNECTING) to false, cappedAt("connect"))
    }

    @Test fun cutShortInSetupsLastStepItSaysSetupDidNotFinish() {
        assertEquals(timedOut(PairingService.Stage.FINISHING) to false, cappedAt("finish"))
    }

    private companion object {
        /** In order, by Robolectric's threads. */
        val told: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

        /** Whether Android refuses the foreground, with an exception. */
        @Volatile var refused = false

        /** Whether it drops the foreground's post and says nothing. */
        @Volatile var dropped = false

        fun card(): Notification? =
            shadowOf(RuntimeEnvironment.getApplication().getSystemService(NotificationManager::class.java))
                .getNotification(PairingNotification.NOTIFICATION_ID)

        fun words(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()

        fun takesACode(n: Notification) =
            n.actions.orEmpty().any { action -> action.remoteInputs.orEmpty().isNotEmpty() }

        fun described(n: Notification) = words(n) + if (takesACode(n)) " [field]" else ""

        const val WRONG = "${PairingService.NOT_SIX_DIGITS} [field]"
    }
}
