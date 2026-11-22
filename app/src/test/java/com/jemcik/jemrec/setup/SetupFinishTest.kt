package com.jemcik.jemrec.setup

import com.jemcik.jemrec.capture.FakeRecorder
import com.jemcik.jemrec.recorder.KeepPaired
import com.jemcik.jemrec.recorder.NetworkRevive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Setup's last step, cancelled - the pairing notification's service stopped
 * at Android's cap for it: the cancellation goes on, it is not given back
 * as "could not finish", which the service put up over its own timed-out
 * words as "Paired, but the recorder did not start".
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class SetupFinishTest {

    private val context = RuntimeEnvironment.getApplication()

    /** Keep JemRec paired, answered before pairing with no recorder to
     *  carry it out, goes to the recorder setup's last step starts: nothing
     *  else would send it before JemRec is next opened. */
    @Test fun theKeepPairedAnswerGoesToTheRecorderSetupStarts() = runBlocking {
        org.robolectric.Shadows.shadowOf(context).grantPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS)
        assertEquals(KeepPaired.Outcome.WAITING, KeepPaired.choose(context, true))
        val recorder = FakeRecorder(context)
        recorder.replies["J"] = "PAIRING ok"
        try {
            assertTrue(Setup.finish(context).isSuccess)
            assertTrue(recorder.heard.any { it.key == "J" && it.payload.contentEquals(byteArrayOf(1)) })
            assertNull(KeepPaired.waiting(context))
        } finally {
            recorder.close()
        }
    }

    /** Row 30: Wireless debugging, switched on by the user to pair, is
     *  JemRec's, and the recorder setup starts switches it off once setup's
     *  session is closed; this phone is never taken over as an older
     *  install's. */
    @Test fun setupsEndHasTheRecorderSwitchWirelessDebuggingOff() = runBlocking {
        org.robolectric.Shadows.shadowOf(context).grantPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS)
        @Suppress("DEPRECATION") // NetworkInfo: Robolectric's Wi-Fi, as in RefusedWifiHealthTest
        org.robolectric.Shadows.shadowOf(context.getSystemService(android.net.ConnectivityManager::class.java))
            .setActiveNetworkInfo(
                org.robolectric.shadows.ShadowNetworkInfo.newInstance(
                    android.net.NetworkInfo.DetailedState.CONNECTED, android.net.ConnectivityManager.TYPE_WIFI, 0, true,
                    android.net.NetworkInfo.State.CONNECTED,
                ),
            )
        val recorder = FakeRecorder(context)
        recorder.replies["I"] = "WIRELESS off"
        try {
            assertTrue(Setup.finish(context).isSuccess)
            // Asked, then the guard's confirmed ping: still up.
            val keys = recorder.heard.map { it.key }
            assertEquals(listOf("I", "P"), keys.drop(keys.lastIndexOf("I")).take(2))
            assertFalse(NetworkRevive.jemRecs(context))
            // Taken over already: a Wireless debugging the user switches on
            // later is theirs.
            NetworkRevive.takeOverOnce(context)
            assertFalse(NetworkRevive.jemRecs(context))
        } finally {
            recorder.close()
        }
    }

    /** ...and where the recorder could not switch it off, it stays JemRec's,
     *  for the next look to ask again. */
    @Test fun setupsEndKeepsItJemRecsWhereTheRecorderDidNot() = runBlocking {
        org.robolectric.Shadows.shadowOf(context).grantPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS)
        @Suppress("DEPRECATION")
        org.robolectric.Shadows.shadowOf(context.getSystemService(android.net.ConnectivityManager::class.java))
            .setActiveNetworkInfo(
                org.robolectric.shadows.ShadowNetworkInfo.newInstance(
                    android.net.NetworkInfo.DetailedState.CONNECTED, android.net.ConnectivityManager.TYPE_WIFI, 0, true,
                    android.net.NetworkInfo.State.CONNECTED,
                ),
            )
        val recorder = FakeRecorder(context)
        recorder.replies["I"] = "WIRELESS refused"
        try {
            assertTrue(Setup.finish(context).isSuccess)
            assertTrue(NetworkRevive.jemRecs(context))
        } finally {
            recorder.close()
        }
    }

    /** Rows 30 and 37, through the real wiring: in a process without
     *  WRITE_SECURE_SETTINGS - setup's own, whose grant is live only from the
     *  next - setup's end asks nothing, and leaves it JemRec's for a later
     *  look; with it, the recorder is asked. */
    @Test fun setupsEndAsksOnlyInAProcessThatCanSwitchItOnAgain() = runBlocking {
        @Suppress("DEPRECATION")
        org.robolectric.Shadows.shadowOf(context.getSystemService(android.net.ConnectivityManager::class.java))
            .setActiveNetworkInfo(
                org.robolectric.shadows.ShadowNetworkInfo.newInstance(
                    android.net.NetworkInfo.DetailedState.CONNECTED, android.net.ConnectivityManager.TYPE_WIFI, 0, true,
                    android.net.NetworkInfo.State.CONNECTED,
                ),
            )
        val recorder = FakeRecorder(context)
        recorder.replies["I"] = "WIRELESS off"
        try {
            org.robolectric.Shadows.shadowOf(context).denyPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS)
            com.jemcik.jemrec.recorder.RecorderLifecycle.afterSetup(context)
            assertTrue("asked in setup's own process", recorder.heard.none { it.key == "I" })
            assertTrue(NetworkRevive.jemRecs(context))

            org.robolectric.Shadows.shadowOf(context).grantPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS)
            com.jemcik.jemrec.recorder.RecorderLifecycle.afterSetup(context)
            assertTrue(recorder.heard.any { it.key == "I" })
            assertFalse(NetworkRevive.jemRecs(context))
        } finally {
            recorder.close()
        }
    }

    @Test fun aCancelledFinishIsCancelledNotAFailure() = runBlocking {
        var returned: Result<Unit>? = null
        launch(Dispatchers.Default) {
            // Cancelled before it asks anything: its first suspension throws.
            coroutineContext.job.cancel()
            returned = Setup.finish(context)
        }.join()
        assertNull("finish gave back $returned for a cancellation", returned)
    }
}
