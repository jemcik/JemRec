package com.jemcik.jemrec.diag

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jemcik.jemrec.capture.AdbdState
import com.jemcik.jemrec.capture.CaptureDaemon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Debug builds only: a routine revive once adbd has stopped, as the
 * keep-alive's first after the Honor's restart was (SpikeReceiver's
 * "revivelater"). The emulator check stops adbd itself, which takes adb away
 * with it, so it cannot send the revive itself; and one sent before adbd
 * stops would find it running and start the recorder through it.
 */
class ReviveLater(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // getprop is a process, waited on: IO's, not the worker's own Default.
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(WAIT_MS) {
                while (AdbdState.read() != AdbdState.State.STOPPED) delay(500)
            }
        } ?: return Result.success() // never stopped: nothing for this to check
        CaptureDaemon.revive(applicationContext)
        return Result.success()
    }

    companion object {
        private const val WAIT_MS = 60_000L

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<ReviveLater>().build())
        }
    }
}
