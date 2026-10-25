package com.jemcik.jemrec

import android.app.Application
import android.util.Log
import com.jemcik.jemrec.capture.BackgroundKill

/**
 * What runs at every start of JemRec's process, whatever started it.
 *
 * Only the background-stop check (BackgroundKill.checkOwnRecords), because a
 * stopped JemRec is often started again with no activity at all. Measured on
 * the Honor (2026-10-25): a Force stop, and within a second Android had
 * started JemRec again for its notification listener - the start record read
 * SERVICE, COLD, wasForceStopped=true - and the stopped flag was already
 * clear when read, before the daemon's once-a-minute look could see it. The
 * process that knows it began stopped is that one, so it looks at once,
 * rather than waiting for the user to open JemRec, which could be days.
 * MainActivity looks again on each open.
 */
class JemRecApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Off the main thread: binder calls, and a call's wake may be what
        // started this process.
        val app = this
        Thread({
            runCatching { BackgroundKill.checkOwnRecords(app, "at process start") }
                .onFailure { Log.w("JemRec", "backgroundkill: start check failed", it) }
        }, "jemrec-startcheck").start()
    }
}
