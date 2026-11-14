package com.jemcik.jemrec.recorder

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A time limit, and a caller cancelled, around the recorder's sockets: the
 * self-test's limit (timed) gives its own words, not a failure of the
 * capture, and a socket opened as its caller went is closed, not dropped
 * (DaemonClient.handedOver).
 */
class TimeLimitsTest {

    @Test fun theLimitRunningOutIsNoAnswerNotAFailure() = runBlocking {
        assertNull(timed(50) { delay(5_000) })
    }

    /** The self-test's own case: on the IO dispatcher already, the connect's
     *  withContext(IO) does not suspend, and blocks past the limit; its end
     *  throws the limit's cancellation, which was caught and given back as
     *  the outcome - "Timed out waiting for 6000 ms" as the capture's failure. */
    @Test fun theLimitRunningOutInABlockingConnectIsNoAnswer() = runBlocking(Dispatchers.IO) {
        assertNull(timed(50) { withContext(Dispatchers.IO) { Thread.sleep(400); 7 } })
    }

    @Test fun whatTheBlockGivesOrThrowsIsItsOutcome() = runBlocking {
        assertEquals(Result.success(7), timed(5_000) { 7 })
        val failed = timed(5_000) { error("no config packet") }
        assertTrue(failed!!.isFailure)
        assertEquals("no config packet", failed.exceptionOrNull()?.message)
    }

    /** A caller cancelled from outside is cancelled, not told of a failure. */
    @Test fun aCancelledCallerIsCancelled() = runBlocking {
        val inside = CountDownLatch(1)
        val job = async(Dispatchers.Default) { timed(5_000) { inside.countDown(); delay(5_000) } }
        assertTrue(inside.await(5, TimeUnit.SECONDS))
        job.cancel()
        try {
            job.await()
            fail("the cancellation was swallowed")
        } catch (_: CancellationException) {
        }
    }

    private class Counted : Socket() {
        @Volatile var closed = 0
        override fun close() {
            closed++
        }
    }

    @Test fun aSocketMadeIsHandedOverOpen() = runBlocking {
        val socket = Counted()
        assertEquals(socket, DaemonClient.handedOver { socket })
        assertEquals(0, socket.closed)
    }

    /** The caller cancelled while the socket was being opened: once it is
     *  open, it is closed - no one else has it. */
    @Test fun aSocketOpenedAsItsCallerWentIsClosed() = runBlocking {
        val socket = Counted()
        val opening = CountDownLatch(1)
        val opens = CountDownLatch(1)
        val job = async(Dispatchers.Default) {
            DaemonClient.handedOver {
                opening.countDown()
                opens.await(5, TimeUnit.SECONDS)
                socket
            }
        }
        assertTrue(opening.await(5, TimeUnit.SECONDS))
        job.cancel()
        opens.countDown()
        try {
            job.await()
            fail("handed over to a caller that went")
        } catch (_: CancellationException) {
        }
        val until = System.currentTimeMillis() + 5_000
        while (socket.closed == 0 && System.currentTimeMillis() < until) Thread.sleep(5)
        assertEquals(1, socket.closed)
        assertFalse(job.isActive)
    }
}
