package com.jemcik.jemrec.recorder

import com.jemcik.jemrec.contract.Repo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The debugging switches have one writer in the app, RecorderLifecycle: its
 * write, through which the revive, When JemRec is off and Start fresh write
 * them, and setup's shield over its session. A write anywhere else - a
 * GlobalSettings.set, or a `settings put global` sent over ADB - shows here
 * by file, comments left out.
 */
class SwitchWritersTest {

    @Test fun onlyTheLifecycleWritesTheDebuggingSwitches() {
        val writes = Repo.file("app/src/main/java").walk().filter { it.extension == "kt" }
            .flatMap { file ->
                val code = Repo.code(file.readText(), kotlin = true)
                listOf("GlobalSettings.set(", "settings put global").filter { it in code }.map { "${file.name}: $it" }
            }
            .sorted()
            .toList()
        assertEquals(listOf("RecorderLifecycle.kt: GlobalSettings.set(", "RecorderLifecycle.kt: settings put global"), writes)
    }
}
