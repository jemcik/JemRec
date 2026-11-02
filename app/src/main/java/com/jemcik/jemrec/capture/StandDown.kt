package com.jemcik.jemrec.capture

/**
 * Whether the call service may stand down (CallMonitorService): the one rule
 * every job's end and every start it turns away goes through. Nothing keeps
 * it once no call is up and none of its jobs - a save, a cable watch, the
 * background-kill warning - is still running; a job stored and not yet
 * started counts as running, being not [done].
 *
 * One look at a time, and a job that asks as it ends - still running until it
 * returns - is marked as ending before it looks at the others: two jobs
 * ending at once, a save and the warning, say, each saw the other still
 * running, and neither stopped the service (made certain by a 1.5 s pause
 * after a save's own check, 2026-11-02). Now the second of the two finds the
 * first marked, and stops it. Pure: [done] says whether a job has completed.
 */
internal class StandDown<J : Any>(private val done: (J) -> Boolean) {

    /** Jobs past their look at the others, still returning. */
    private val ending = mutableSetOf<J>()

    /**
     * Whether nothing keeps the service: [end], the job asking as it ends, if
     * one is; [callUp] and [jobs] read under the lock, after [end] is marked.
     */
    fun idle(end: J?, callUp: () -> Boolean, jobs: () -> List<J?>): Boolean = synchronized(this) {
        if (end != null) ending += end
        ending.removeAll(done)
        jobs().none { it != null && !done(it) && it !in ending } && !callUp()
    }
}
