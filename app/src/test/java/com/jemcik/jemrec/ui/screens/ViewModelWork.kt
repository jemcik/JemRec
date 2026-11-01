package com.jemcik.jemrec.ui.screens

import android.os.Looper
import androidx.lifecycle.viewModelScope
import com.jemcik.jemrec.ui.MainViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import org.robolectric.Shadows.shadowOf

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
