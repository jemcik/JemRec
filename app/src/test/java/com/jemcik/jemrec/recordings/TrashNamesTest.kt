package com.jemcik.jemrec.recordings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The trashed name, read and written: which file is in the trash, under
 *  which name, and when it goes. */
class TrashNamesTest {

    private val name = "20261020_161205_in.ogg"
    private val expires = 1_795_000_000_000L

    @Test fun aTrashedNameIsThePrefixTheExpiryInSecondsAndTheName() {
        assertEquals(".trashed-1795000000-$name", TrashNames.nameFor(name, expires))
        assertEquals(".trashed-1795000000-$name", TrashNames.nameFor(name, expires + 999))
    }

    @Test fun itReadsBack() {
        assertEquals(name to expires, TrashNames.parse(TrashNames.nameFor(name, expires)))
    }

    @Test fun anAtAndAPercentAreWrittenOutAndReadBack() {
        val honor = "Priya 100%@+447700900123_20261020223141.ogg"
        val trashed = TrashNames.nameFor(honor, expires)
        assertEquals(".trashed-1795000000-Priya 100%25%40+447700900123_20261020223141.ogg", trashed)
        assertEquals(honor to expires, TrashNames.parse(trashed))
    }

    @Test fun aNameFromBeforeEscapingReadsAsItIs() {
        assertEquals("a@b.ogg" to expires, TrashNames.parse(".trashed-1795000000-a@b.ogg"))
    }

    @Test fun whatIsNotOneIsNotRead() {
        assertNull(TrashNames.parse(name))
        assertNull(TrashNames.parse(".trashed-"))
        assertNull(TrashNames.parse(".trashed-1795000000"))
        assertNull(TrashNames.parse(".trashed-1795000000-"))
        assertNull(TrashNames.parse(".trashed-soon-$name"))
        assertNull(TrashNames.parse("x.trashed-1795000000-$name"))
    }

    @Test fun aFolderSaysSoByTheNameItGives() {
        val trashed = TrashNames.nameFor(name, expires)
        assertEquals(name to expires, TrashNames.inFolder(trashed, "doc-1", restorable = false))
        assertEquals(name to expires, TrashNames.inFolder(trashed, "doc-1", restorable = true))
    }

    @Test fun orByTheEndOfItsIdWhereTheNameIsStripped() {
        val trashed = TrashNames.nameFor(name, expires)
        assertEquals(name to expires, TrashNames.inFolder(name, "primary:Calls/$trashed", restorable = true))
        // At a volume's root the ID has no slash.
        assertEquals(name to expires, TrashNames.inFolder(name, "primary:$trashed", restorable = true))
        assertEquals(name to expires, TrashNames.inFolder(name, trashed, restorable = false))
    }

    @Test fun orByTheMarkAloneWithNoExpiry() {
        val stripped = "Priya%40+447700900123_20261020223141.ogg"
        assertEquals(
            "Priya@+447700900123_20261020223141.ogg" to null,
            TrashNames.inFolder(stripped, "doc-1", restorable = true),
        )
    }

    @Test fun anOrdinaryFileIsNotInTheTrash() {
        assertNull(TrashNames.inFolder(name, "primary:Calls/$name", restorable = false))
    }
}
