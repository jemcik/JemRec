package com.jemcik.jemrec.setup

import com.jemcik.jemrec.capture.FakeRecorder
import com.jemcik.jemrec.recorder.KeepPaired
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
