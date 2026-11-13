package com.jemcik.jemrec.adb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [block] run, with [onTimeout] called if it is still running after
 * [timeoutMs] - its caller cancelled meanwhile or not. The block blocks on a
 * socket (AdbTransport.exec), which a cancel cannot interrupt: only
 * [onTimeout], closing the connection, lets it go. The watchdog was a child
 * of the caller's, and a cancelled caller - a screen closed from Recents in
 * the middle of a command - took it down with it, and left the command
 * blocking, holding AdbTransport's lock, for as long as the stream stayed
 * up. On a thread of its own (Dispatchers.IO): the block holds the
 * caller's. Waited for before this returns, as a child was: a timeout that
 * fired as the block ended closes the session before the caller's next
 * command, not under it.
 */
internal suspend fun <T> watched(timeoutMs: Long, onTimeout: () -> Unit, block: () -> T): T = coroutineScope {
    val watchdog = launch(NonCancellable + Dispatchers.IO) {
        delay(timeoutMs)
        onTimeout()
    }
    try {
        block()
    } finally {
        withContext(NonCancellable) { watchdog.cancelAndJoin() }
    }
}
