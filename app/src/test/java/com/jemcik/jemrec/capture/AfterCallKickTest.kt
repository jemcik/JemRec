package com.jemcik.jemrec.capture

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * A call's end's kick of the keep-alive (RecorderKeepAlive.kickAfterCall):
 * never dropped. Behind one of its own name still running - its reconcile
 * may have met the next call, and gone on into the day's auto-delete - it
 * is queued after it, not dropped as a KEEP drops it (review round 28).
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class AfterCallKickTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test fun aCallsEndKickIsQueuedBehindOneStillRunning() {
        val work = HeldWork(context)
        try {
            RecorderKeepAlive.kickAfterCall(context)
            // Started, and held running for ever.
            assertEquals(1, work.started.size)
            RecorderKeepAlive.kickAfterCall(context)
            assertEquals("one running, one behind it", 2, work.unique(HeldWork.AFTER_CALL).size)
            // One behind it already does the job: no chain of them (round 29).
            RecorderKeepAlive.kickAfterCall(context)
            assertEquals(2, work.unique(HeldWork.AFTER_CALL).size)
        } finally {
            work.close()
        }
    }
}
