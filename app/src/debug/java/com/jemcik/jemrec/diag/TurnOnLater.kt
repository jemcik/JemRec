package com.jemcik.jemrec.diag

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jemcik.jemrec.capture.RecorderSwitch
import java.util.concurrent.TimeUnit

/**
 * Debug builds only: JemRec on again after a delay, as the record control
 * would turn it on (SpikeReceiver's "switch"). Turn all debugging off takes
 * adb away with it, so an emulator check cannot send the on itself.
 */
class TurnOnLater(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        RecorderSwitch.set(applicationContext, true)
        RecorderSwitch.turn(applicationContext, true)
        return Result.success()
    }

    companion object {
        fun schedule(context: Context, afterSeconds: Int) {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<TurnOnLater>()
                    .setInitialDelay(afterSeconds.toLong(), TimeUnit.SECONDS)
                    .build(),
            )
        }
    }
}
