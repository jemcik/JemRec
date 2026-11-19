package com.jemcik.jemrec.contract

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The F-Droid listing within the lengths F-Droid keeps: what is past them is
 * cut, without a word, by fdroidserver as it reads the metadata - the title
 * at 50 characters, the summary at 80, the description at 4000, each
 * version's changes at 500 (CLAUDE.md). The description sat at 3996 and the
 * changelog at 499 while 1.2 was written, each edit a trade, and nothing
 * checked them (review, 2026-11-18).
 */
class ListingLimitsTest {

    private val listing = "fastlane/metadata/android/en-US"

    private fun within(path: String, most: Int) {
        val text = Repo.text(path).trimEnd('\n')
        assertTrue("$path: ${text.length} characters, at most $most", text.length <= most)
    }

    @Test fun theTitle() = within("$listing/title.txt", 50)

    @Test fun theSummary() = within("$listing/short_description.txt", 80)

    @Test fun theDescription() = within("$listing/full_description.txt", 4000)

    @Test fun eachVersionsChanges() {
        val changes = Repo.file("$listing/changelogs").listFiles().orEmpty().filter { it.extension == "txt" }
        assertTrue("no changelogs found", changes.isNotEmpty())
        for (each in changes) within("$listing/changelogs/${each.name}", 500)
    }
}
