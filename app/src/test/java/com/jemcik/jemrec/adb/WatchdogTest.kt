package com.jemcik.jemrec.adb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The watchdog around an ADB command (watched): the command blocks on a
 * socket, and only the watchdog's close lets it go.
 */
class WatchdogTest {

    /** Its caller cancelled while the command blocks - a screen closed from
     *  Recents: the watchdog still fires and lets the command go. A child
     *  of the caller's, it was cancelled with it, and the command blocked on,
     *  holding AdbTransport's lock. The stand-in blocks for at most five
     *  seconds; let go by the watchdog, it is back within one. */
    @Test fun aCancelledCallersCommandIsStillLetGo() = runBlocking {
        val blocking = CountDownLatch(1)
        val inCommand = CountDownLatch(1)
        val timedOut = AtomicBoolean()
        val caller = launch(Dispatchers.IO) {
            watched(200, onTimeout = {
                timedOut.set(true)
                blocking.countDown()
            }) {
                inCommand.countDown()
                blocking.await(5, TimeUnit.SECONDS)
            }
        }
        assertTrue(inCommand.await(5, TimeUnit.SECONDS))

        val cancelledAt = System.nanoTime()
        caller.cancel()
        caller.join()

        assertTrue("the watchdog never fired", timedOut.get())
        assertTrue("let go only by the stand-in's own limit", (System.nanoTime() - cancelledAt) / 1_000_000 < 1_000)
    }

    /** A command still running when its time is up is let go. */
    @Test fun aCommandStillRunningAtItsTimeIsLetGo() = runBlocking {
        val blocking = CountDownLatch(1)

        val let = watched(100, onTimeout = { blocking.countDown() }) { blocking.await(5, TimeUnit.SECONDS) }

        assertTrue(let)
    }

    /** A timeout that fired as the command ended has done its work - the
     *  session closed - before the caller goes on to its next command, which
     *  would otherwise meet the close. */
    @Test fun aTimeoutUnderWayEndsBeforeTheCommandReturns() = runBlocking {
        val firing = CountDownLatch(1)
        val closed = AtomicBoolean()

        watched(50, onTimeout = {
            firing.countDown()
            Thread.sleep(300)
            closed.set(true)
        }) { firing.await(5, TimeUnit.SECONDS) }

        assertTrue("returned with the close still under way", closed.get())
    }

    /** One back in time is not touched afterwards. */
    @Test fun aCommandBackInTimeHasNoTimeout() = runBlocking {
        val timedOut = AtomicBoolean()

        assertEquals(42, watched(100, onTimeout = { timedOut.set(true) }) { 42 })
        Thread.sleep(300)

        assertFalse(timedOut.get())
    }
}
