package com.jemcik.jemrec.contract

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The Python tools define each top-level function once. Python takes a
 * second `def` of a name for the one meant and says nothing: on 2026-11-17 a
 * new release check named ogg_pages replaced the quality check's helper of
 * that name, and quality failed on the Android 12 and 13 emulators, the app
 * not at fault. pyflakes would say "redefinition"; until the tools are linted,
 * this says it.
 */
class ToolScriptsTest {

    @Test fun everyFunctionInTheToolsIsDefinedOnce() {
        val tools = File(Repo.root, "tools").listFiles { f -> f.extension == "py" }.orEmpty()
        val twice = tools.flatMap { file ->
            Regex("""^def (\w+)\(""", RegexOption.MULTILINE).findAll(file.readText()).map { it.groupValues[1] }
                .groupingBy { it }.eachCount().filterValues { it > 1 }.keys.map { "${file.name}: $it" }
        }
        assertEquals(emptyList<String>(), twice)
    }
}
