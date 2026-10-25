package com.jemcik.jemrec.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BackgroundKill.ownStartWasStopped on the start records the Honor itself
 * printed (`dumpsys activity start-info com.jemcik.jemrec`, MagicOS 11,
 * 2026-10-25), parsed into the same Start values the app builds from
 * ApplicationStartInfo. Each moment is the record list as it stood then:
 * the records newer than it dropped. The times are the records' own launch
 * stamps (elapsed time), set against the wake BackgroundKillTest dates at
 * 19:24.
 *
 * What these records showed, and the tests hold: the Honor leaves the pid at
 * 0 in its cold starts - the launcher's included - so a pid alone never picks
 * this process's own; and it marks WARM activity starts wasForceStopped too,
 * after the process's own state, so only process starts may decide.
 */
class BackgroundKillHonorRecordsTest {

    private fun starts(file: String): List<BackgroundKill.Start> {
        val text = checkNotNull(javaClass.getResource("/honor/$file")) { "$file is not on the test classpath" }.readText()
        return text.split("ApplicationStartInfo #").drop(1).map { record ->
            fun field(name: String) =
                checkNotNull(Regex("""\b$name=(\w+)""").find(record)) { "a record in $file has no $name=" }.groupValues[1]
            BackgroundKill.Start(
                pid = field("pid").toInt(),
                processStart = field("startType") !in setOf("WARM", "HOT"),
                launchedNs = Regex("""timestamps:.*?\b0=(\d+)""").find(record)?.groupValues?.get(1)?.toLong(),
                wasStopped = field("wasForceStopped") == "true",
            )
        }
    }

    /** As the records stood when the start at [index] (newest first) was the newest. */
    private fun asAt(file: String, index: Int) = starts(file).drop(index)

    @Test fun theRecordersWakeOfAStoppedJemRecIsReadAsStopped() {
        // 19:30: force-stopped, then started by the recorder's exact wake -
        // SERVICE, COLD, pid 0, wasForceStopped=true.
        assertTrue(BackgroundKill.ownStartWasStopped(starts("start-info-after-wake.txt"), myPid = 4242))
    }

    @Test fun theStartJustBeforeItWasNot() {
        // A service's cold start 0.2 s earlier was not stopped.
        assertFalse(BackgroundKill.ownStartWasStopped(asAt("start-info-after-wake.txt", 1), myPid = 4242))
    }

    @Test fun theFirstOpenAfterThe1352KillIsReadAsStopped() {
        // 15:29: the launcher's COLD start, pid 0, wasForceStopped=true - the
        // first start after the 13:52 kill, the open that could tell of it.
        assertTrue(BackgroundKill.ownStartWasStopped(asAt("start-info-after-launch.txt", 12), myPid = 4242))
    }

    @Test fun aStoppedWarmStartWithThisPidDoesNotDecide() {
        // Newest process start: an activity's COLD start, not stopped. Older
        // WARM starts of process 30914 - the pid this test gives itself - say
        // stopped, after their process's state: a start is this process's by
        // being the newest process start, not by carrying its pid. (A newer
        // warm start never deciding is thisProcessesOwnColdStartDecides.)
        assertFalse(BackgroundKill.ownStartWasStopped(asAt("start-info-after-wake.txt", 4), myPid = 30914))
    }

    @Test fun aProcessStartedByAServiceAndThenOpenedWasNotStopped() {
        // 18:21: the newest record is an open in a running process (WARM);
        // the process itself was started by a service, unstopped.
        assertFalse(BackgroundKill.ownStartWasStopped(starts("start-info-after-launch.txt"), myPid = 24455))
    }
}
