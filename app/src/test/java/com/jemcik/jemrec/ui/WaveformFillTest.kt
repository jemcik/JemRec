package com.jemcik.jemrec.ui

import android.net.Uri
import com.jemcik.jemrec.recordings.Recording
import com.jemcik.jemrec.recordings.Waveforms
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The waveform fill-in asked again at the moment a run of it ends: the list
 * that arrived meanwhile is gone over. A run that had looked for a request
 * and found none, and not yet ended, took the new one as noted for it - and
 * then ended, and nothing went over the list until the next reading.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class WaveformFillTest {

    private val app = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun noStandIns() {
        scope.cancel()
        Waveforms.override = null
    }

    private fun recording(stamp: String) = Recording(
        name = "${stamp}_in.ogg",
        uri = Uri.parse("jemrec-test://$stamp"),
        whenLabel = "",
        incoming = true,
        durationLabel = "1:00",
        sizeLabel = "1.0 MB",
    )

    @Test fun aRequestAsARunEndsIsNotLost() {
        val first = recording("20261021_160200")
        val second = recording("20261021_152400")
        // Each recording the fill-in asks after, through the stand-in levels.
        val asked = CopyOnWriteArrayList<String>()
        Waveforms.override = { name -> asked += name; listOf(0.5f) }
        val ending = CountDownLatch(1)
        val goOn = CountDownLatch(1)
        val state = MutableStateFlow(UiState(recordings = listOf(first)))
        val fill = WaveformFill(app, scope, state, afterRun = {
            ending.countDown()
            goOn.await(10, TimeUnit.SECONDS)
        })

        fill.start()
        assertTrue("the run over the first list ends", ending.await(10, TimeUnit.SECONDS))
        state.value = UiState(recordings = listOf(first, second))
        fill.start()
        goOn.countDown()

        val until = System.currentTimeMillis() + 5_000
        while (second.name !in asked && System.currentTimeMillis() < until) Thread.sleep(10)
        assertTrue("the list that came as it ended: $asked", second.name in asked)
    }
}
