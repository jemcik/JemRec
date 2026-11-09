package com.jemcik.jemrec.recorder

import androidx.core.content.edit
import com.jemcik.jemrec.Prefs
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread

/** The secret the app shares with the recorder, made on first use. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class RecorderTokenTest {

    private val context = RuntimeEnvironment.getApplication()

    /**
     * Asked for by several at once before there is one - the answering
     * machine screen's checks and its test call's watch, say: one token. Two
     * were made, and a command proved with the one not kept was refused by
     * the recorder; a recorder started with it would refuse the app until
     * its next start.
     */
    @Test fun firstAskedForBySeveralAtOnceItIsOne() {
        val threads = 8
        repeat(20) { round ->
            Prefs.of(context).edit(commit = true) { remove("daemon_token") }
            val start = CyclicBarrier(threads)
            val seen = ConcurrentHashMap.newKeySet<String>()
            (1..threads).map { thread { start.await(); seen += DaemonClient.token(context) } }.forEach { it.join() }
            assertEquals("round $round: $seen", 1, seen.size)
            assertEquals(seen.single(), DaemonClient.token(context))
        }
    }
}
