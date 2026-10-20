package com.jemcik.jemrec.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The trashed name, for the two places that trash by renaming: Android's own
 *  on-disk convention, read back exactly. */
class TrashTest {

    private val name = "20261020_161205_in.ogg"
    private val expires = 1_795_000_000_000L

    @Test fun aTrashedNameRoundTrips() {
        val trashed = Trash.nameFor(name, expires)
        assertEquals(".trashed-1795000000-$name", trashed)
        assertEquals(name to expires, Trash.parse(trashed))
    }

    @Test fun anOrdinaryNameIsNotTrashed() {
        assertFalse(Trash.isTrashedName(name))
        assertNull(Trash.parse(name))
    }

    @Test fun aNameWithDashesInItSurvives() {
        val odd = "call-with-dashes_out.ogg"
        assertEquals(odd, Trash.parse(Trash.nameFor(odd, expires))?.first)
    }

    @Test fun anHonorNameLosesItsAtInTheTrashAndGetsItBack() {
        // Honor's Phone app plays any name with "@<number>_<time>" in it,
        // dot or no dot: a trashed one must not have it.
        val honor = "Priya Shah@+447700900123_20261020165705.ogg"
        val trashed = Trash.nameFor(honor, expires)
        assertFalse(trashed.contains('@'))
        assertEquals(honor to expires, Trash.parse(trashed))
        assertEquals("50%@x.ogg", Trash.parse(Trash.nameFor("50%@x.ogg", expires))?.first)
    }

    @Test fun somethingThatOnlyLooksTrashedIsNot() {
        assertNull(Trash.parse(".trashed-soon-$name"))
        assertNull(Trash.parse(".trashed-1795000000-"))
        assertTrue(Trash.isTrashedName(".trashed-1-x.ogg"))
    }
}
