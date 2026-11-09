package com.jemcik.jemrec.ui

import android.app.Application
import android.os.SystemClock
import com.jemcik.jemrec.recordings.Recordings
import com.jemcik.jemrec.recordings.Waveforms
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** How long a recording the waveform fill-in could not decode waits before
 *  it is tried again: long enough that a file that will not decode does not
 *  log its warning at every refresh, short enough that one that failed for
 *  the moment gets its waveform the same day. */
private const val RETRY_UNDECODABLE_MS = 30 * 60_000L

/**
 * Rows of the list draw each recording's waveform, from its cache (see
 * rememberRowLevels). A call saved since the waveform player has one
 * (AfterCall); one recorded before has none until it is played - so they
 * are worked out here, newest first, one at a time, while the app is on
 * screen. Measured on the Honor (2026-10-23): 0.14 s a minute-long call,
 * 2.5 s a half-hour one - once each, then read from the cache.
 *
 * Only on screen: it stops with the screen ([stop]) and the next refresh -
 * each opening makes one - goes on from there. It ran on when the app was
 * left, with up to four decoders a call, during the next call too. Whether
 * a recording has its levels is a file's size, not a read of them; one that
 * cannot be decoded is tried again only after half an hour
 * (RETRY_UNDECODABLE_MS); and the levels of recordings no longer kept go
 * (Waveforms.prune). Asked again while it
 * runs, it goes over the list once more at the end, for the recordings the
 * list gained meanwhile.
 */
internal class WaveformFill(
    private val app: Application,
    private val scope: CoroutineScope,
    private val state: StateFlow<UiState>,
    /** Called as a run ends, on its thread: the tests' way to stand in the
     *  moment between a run's last look for a request and its end. */
    private val afterRun: () -> Unit = {},
) {
    private var fillingIn: Job? = null

    /**
     * The run that will still look for a request before it ends, if one
     * will, and the request ([fillAgain]); under [lock]. A run decides to end
     * and lets go of [looping] in one step, so a request either reaches the
     * run or starts a new one. It was a flag the run looked at last: one
     * set after that look, the run not yet ended, was taken as noted - and
     * nothing went over the list until the next reading.
     */
    private val lock = Any()
    private var looping: Any? = null
    private var fillAgain = false

    /** Recordings the fill-in could not decode, and when: tried on every
     *  refresh, each logged its warning again and again; never again for
     *  the life of the process, one that failed only for the moment - its
     *  codec not to be had while AfterCall decoded another - kept no
     *  waveform for days. Tried again after RETRY_UNDECODABLE_MS. */
    private val undecodable = ConcurrentHashMap<String, Long>()

    /** Over the list as it is now; asked while it runs, once more at its end. */
    fun start() {
        val run = Any()
        synchronized(lock) {
            if (looping != null && fillingIn?.isActive == true) {
                fillAgain = true
                return
            }
            looping = run
            fillAgain = false
        }
        fillingIn = scope.launch(Dispatchers.IO) {
            try {
                do {
                    pass { isActive }
                } while (again(run, isActive))
            } finally {
                synchronized(lock) { if (looping === run) looping = null }
            }
            afterRun()
        }
    }

    /** Whether [run] goes over the list once more: asked meanwhile, and not
     *  stopped. If not, it looks no more - in the same step. */
    private fun again(run: Any, active: Boolean): Boolean = synchronized(lock) {
        if (fillAgain && active) {
            fillAgain = false
            return true
        }
        if (looping === run) looping = null
        return false
    }

    /** Once over the list as it is now, while [keepGoing]. */
    private fun pass(keepGoing: () -> Boolean) {
        val now = state.value
        // Not over a list that is not the phone's: the debug build's
        // invented calls, or none for want of the audio permission -
        // every waveform would go, to be worked out again.
        if (Recordings.override == null && now.canReadAudio) {
            Waveforms.prune(app, (now.recordings + now.trashed).map { it.name })
        }
        for (recording in now.recordings) {
            if (!keepGoing()) break
            val name = recording.name
            val failedAt = undecodable[name]
            if (failedAt != null && SystemClock.elapsedRealtime() - failedAt < RETRY_UNDECODABLE_MS) continue
            if (Waveforms.isBeingWritten(name) || Waveforms.has(app, name)) continue
            val levels = Waveforms.levels(app, name, recording.uri, keepGoing = keepGoing)
            if (levels == null && keepGoing() && !Waveforms.isBeingWritten(name)) {
                undecodable[name] = SystemClock.elapsedRealtime()
            } else {
                undecodable.remove(name)
            }
        }
    }

    /** The fill-in stops with the screen. */
    fun stop() {
        fillingIn?.cancel()
    }
}
