package com.jemcik.jemrec.ui.screens

import android.os.Looper
import androidx.lifecycle.viewModelScope
import com.jemcik.jemrec.recordings.Recording
import com.jemcik.jemrec.recordings.Recordings
import com.jemcik.jemrec.ui.MainViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Waiting for the view model's own work in a Robolectric test. Its
 * coroutines start on the main thread, which is the test's, read on IO and
 * come back to the main looper - which Robolectric turns only when asked. So
 * a wait turns it until [done], as ScreenTest.stopViewModels does; capped,
 * so work that never ends fails the test instead of hanging the run.
 */
internal fun awaitMain(what: String, timeoutMs: Long = 30_000, done: () -> Boolean) {
    val main = shadowOf(Looper.getMainLooper())
    val until = System.currentTimeMillis() + timeoutMs
    while (true) {
        main.idle()
        if (done()) return
        check(System.currentTimeMillis() < until) { "gave up after $timeoutMs ms waiting for $what" }
        Thread.sleep(5)
    }
}

/** Until [job] - a refresh, say - has finished. */
internal fun awaitJob(job: Job, what: String = "the job") = awaitMain(what) { job.isCompleted }

/** Until nothing [vm] started is still running: its refreshes, its actions,
 *  the ones queued behind busy, the waveform fill-in. Anything queued
 *  is one of these, so once none is left nothing more runs by itself. */
internal fun settle(vm: MainViewModel) =
    awaitMain("the view model's work") { vm.viewModelScope.coroutineContext.job.children.all { it.isCompleted } }

/**
 * The screen busy until [release]: Settings' auto-delete, its closing
 * refresh held in its reading of the list (Recordings.override), which then
 * reads as [list]. Each opening's setup check holds busy for seconds on a
 * phone; this holds it for as long as a test needs.
 */
internal class HeldBusy(list: List<Recording>) {
    private val reading = CountDownLatch(1)
    private val gate = CountDownLatch(1)

    init {
        Recordings.override = {
            reading.countDown()
            gate.await(30, TimeUnit.SECONDS)
            list
        }
    }

    fun hold(vm: MainViewModel) {
        vm.setAutoDelete()
        awaitMain("auto-delete's refresh to be reading") { reading.count == 0L }
        assertTrue("busy while auto-delete runs", vm.state.value.busy)
    }

    fun release() = gate.countDown()
}
