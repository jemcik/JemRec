package com.jemcik.jemrec.contract

import com.jemcik.jemrec.recorder.DaemonClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The recorder's LIST reply, as both ends read it: names, then "end N" -
 *  how many it sent (Checks.listEnd). A reply without it is no answer
 *  (review round 10). */
class ListReplyTest {

    @Test fun aWholeReplyGivesItsNames() {
        assertEquals(
            DaemonClient.Listing(listOf("jemrec_rec_1_in.dat", "jemrec_rec_2_out.dat"), whole = true),
            DaemonClient.listingFrom(listOf("jemrec_rec_1_in.dat", "jemrec_rec_2_out.dat", "end 2")),
        )
    }

    @Test fun nothingPendingIsAnEmptyListNotNoAnswer() {
        assertEquals(DaemonClient.Listing(emptyList(), whole = true), DaemonClient.listingFrom(listOf("end 0")))
    }

    /** Cut off mid-way - the recorder ended, an unplug - or with nothing it
     *  could list: no answer, not "these are all". */
    @Test fun aReplyNotWholeIsNoAnswer() {
        assertNull(DaemonClient.listingFrom(listOf("jemrec_rec_1_in.dat", "jemrec_rec_2_out.dat")))
        assertNull(DaemonClient.listingFrom(listOf("jemrec_rec_1_in.dat", "end 2")))
        assertNull(DaemonClient.listingFrom(emptyList()))
    }

    /** An older recorder's reply - 1.1's, no end, no "list-end" in its
     *  pong - read to its end as before, as an app speaks to one for days
     *  after an update off Wi-Fi; its list is not known whole (review
     *  round 11: every list of one was no answer). */
    @Test fun anOlderRecordersReplyIsReadAsBefore() {
        assertEquals(
            DaemonClient.Listing(listOf("jemrec_rec_1_in.dat", "jemrec_rec_2_out.dat"), whole = false),
            DaemonClient.listingFrom(listOf("jemrec_rec_1_in.dat", "jemrec_rec_2_out.dat"), ends = false),
        )
        assertEquals(DaemonClient.Listing(emptyList(), whole = false), DaemonClient.listingFrom(emptyList(), ends = false))
    }

    /** A 1.2 recorder's reply read with no pong had - its ping timed out as
     *  it came up: whole by its own end, the end never a name - "end 1" was
     *  saved as a recording started then (review round 12). One whose end
     *  does not count right is not whole, and its end still no name. */
    @Test fun aReplyWithItsEndIsWholeWithNoPongHad() {
        assertEquals(
            DaemonClient.Listing(listOf("jemrec_rec_1_in.dat"), whole = true),
            DaemonClient.listingFrom(listOf("jemrec_rec_1_in.dat", "end 1"), ends = false),
        )
        assertEquals(
            DaemonClient.Listing(listOf("jemrec_rec_1_in.dat"), whole = false),
            DaemonClient.listingFrom(listOf("jemrec_rec_1_in.dat", "end 2"), ends = false),
        )
        // Cut inside its end, or inside a name: no name either (round 13).
        assertEquals(
            DaemonClient.Listing(listOf("jemrec_rec_1_in.dat"), whole = false),
            DaemonClient.listingFrom(listOf("jemrec_rec_1_in.dat", "jemrec_rec_2_o", "en"), ends = false),
        )
    }

    @Test fun theRecorderEndsItsReplySo() {
        assertTrue("the recorder's LIST end", "return \"end \" + count;" in Repo.recorder)
        assertTrue("sent after the names", "Checks.listEnd(count)" in Repo.recorder)
    }
}
