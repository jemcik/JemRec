package com.jemcik.jemrec.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The code the contract tests search: the sources without their comments. */
class RepoTest {

    @Test
    fun commentsGoAndStringsStay() {
        val source = listOf(
            "/** A KDoc quoting \"revive: start\". */",
            "val uri = \"content://call_log/calls\" // a trailing remark",
            "    // a whole line",
            "val mime = \"audio/*\"; val quote = '\"'; val apostrophe = '\\''",
            "/* a block */ val raw = \"\"\"a // b\"\"\"",
        ).joinToString("\n")
        val code = Repo.code(source, kotlin = true)
        listOf("revive: start", "remark", "whole line", "a block").forEach { assertFalse(it, it in code) }
        listOf("\"content://call_log/calls\"", "\"audio/*\"", "'\"'", "'\\''", "\"\"\"a // b\"\"\"").forEach {
            assertTrue(it, it in code)
        }
    }

    @Test
    fun aRawStringEndingInAQuoteEndsAtItsLastQuote() {
        val code = Repo.code("val s = \"\"\"say \"hi\"\"\"\" // a remark", kotlin = true)
        assertFalse("remark" in code)
        assertTrue("\"\"\"say \"hi\"\"\"\"" in code)
    }

    @Test
    fun kotlinsBlockCommentsNestAndJavasDoNot() {
        val nested = "/* outer /* inner */ still outer */ val x = 1"
        assertEquals("val x = 1", Repo.code(nested, kotlin = true).trim())
        // Java ends the comment at the first close, as javac does.
        assertTrue("still outer" in Repo.code(nested, kotlin = false))
    }
}
