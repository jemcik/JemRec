package com.jemcik.jemrec.capture

import android.content.Context
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.util.concurrent.ListenableFuture
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * WorkManager's test driver, for what the recorder's lifecycle enqueues
 * (RecorderKeepAlive, UsbDebuggingWatch, and the triggers that arm them).
 *
 * The driver starts a work the moment nothing holds it back - a kick, the
 * heartbeat's first period - and a real keep-alive run would revive the
 * recorder for real. So every work it starts is [Held] instead, and what
 * started it is kept: the worker class and its input. A held work never
 * finishes, as a real run still going would not, so a unique name stays
 * taken and KEEP, APPEND_OR_REPLACE and REPLACE each leave something
 * different behind. The USB watch waits on its content trigger, which no
 * test here reports met, so it stays ENQUEUED and is never started.
 *
 * A CoroutineWorker suspended for ever was tried first: by the time
 * MainActivity had been created in the same test its work was gone from the
 * database and a KEEP took a new kick in its place (its future collected,
 * most likely; not looked into). A future that is never done holds.
 */
internal class HeldWork(private val context: Context) {

    data class Start(val worker: String, val input: Map<String, Any?>, val id: UUID)

    /** Every work the driver started, in order. */
    val started: MutableList<Start> = CopyOnWriteArrayList()

    init {
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker {
                started += Start(workerClassName, workerParameters.inputData.keyValueMap, workerParameters.id)
                return Held(appContext, workerParameters)
            }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(factory)
                .build(),
        )
    }

    /** What is under a unique name now, in no order to rely on (the
     *  database's: two under one name came back either way round). */
    fun unique(name: String): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get()

    /** The input the work [id] was started with. */
    fun inputOf(id: UUID): Map<String, Any?> = started.single { it.id == id }.input

    fun close() = WorkManagerTestInitHelper.closeWorkDatabase()

    private class Held(context: Context, params: WorkerParameters) : ListenableWorker(context, params) {
        override fun startWork(): ListenableFuture<Result> = NeverDone
    }

    private object NeverDone : ListenableFuture<ListenableWorker.Result> {
        override fun addListener(listener: Runnable, executor: Executor) = Unit
        override fun cancel(mayInterruptIfRunning: Boolean) = false
        override fun isCancelled() = false
        override fun isDone() = false
        override fun get(): ListenableWorker.Result = throw IllegalStateException("held by the test")
        override fun get(timeout: Long, unit: TimeUnit): ListenableWorker.Result = throw TimeoutException()
    }

    companion object {
        // The unique names, as WorkManager's database keeps them (frozen:
        // a renamed one leaves the old work armed beside the new).
        const val PERIODIC = "jemrec.keepalive.periodic"
        const val NOW = "jemrec.keepalive.now"
        const val WIFI = "jemrec.keepalive.wifi"
        const val USB_WATCH = "jemrec.usbdebugging.watch"
        val ALL = listOf(PERIODIC, NOW, WIFI, USB_WATCH)

        const val KEEP_ALIVE = "com.jemcik.jemrec.capture.RecorderKeepAlive"
        const val WATCH = "com.jemcik.jemrec.capture.UsbDebuggingWatch"
    }
}
