package com.jemcik.jemrec.ui

import android.app.Notification
import android.app.NotificationManager
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.jemcik.jemrec.setup.PairingNotification
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The pairing notification's service stopped by Android at its cap
 * (onTimeout) while a pairing runs: the card says what was cut short there,
 * and offers the code field again only where nothing is paired yet. One
 * text, "Paired! I could not connect in time ... it will say which switch to
 * turn on", was false while still pairing and in setup's last step.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class PairingServiceTest {

    private val app = RuntimeEnvironment.getApplication()

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

    /** The card's words after the cap at [step], and whether it takes a code. */
    private fun cappedAt(step: String): Pair<String, Boolean> {
        val held = Held(step)
        PairingService.steps = held
        val code = Intent().also {
            RemoteInput.addResultsToIntent(
                arrayOf(RemoteInput.Builder(PairingNotification.KEY_CODE).build()),
                it,
                Bundle().apply { putCharSequence(PairingNotification.KEY_CODE, "123456") },
            )
        }
        val controller = Robolectric.buildService(PairingService::class.java, code).create().startCommand(0, 1)
        assertTrue("the pairing reached $step", held.reached.await(5, TimeUnit.SECONDS))

        controller.get().onTimeout(1)
        // As Android ends the service after the cap: the run held in its
        // step is cancelled, and what its catch makes of that is on the card
        // too - "Something went wrong: Job was cancelled", before 1.0.
        val run = controller.get().run!!
        controller.destroy()
        runBlocking { withTimeout(5_000) { run.join() } }

        val card = shadowOf(app.getSystemService(NotificationManager::class.java))
            .getNotification(PairingNotification.NOTIFICATION_ID)
        val takesACode = card.actions.orEmpty().any { action -> action.remoteInputs.orEmpty().isNotEmpty() }
        return card.extras.getCharSequence(Notification.EXTRA_TEXT).toString() to takesACode
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
}
