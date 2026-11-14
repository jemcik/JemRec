package com.jemcik.jemrec.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import java.io.IOException;
import org.junit.Test;

/**
 * What is made over a file just opened (Closing), as a recording's start
 * makes its encoder over the call's file: the file closed again when the
 * making fails, and only then.
 */
public class ClosingTest {

    /** Counts its closes, and can be made to fail one. */
    private static final class Opened implements AutoCloseable {
        int closed;
        final Exception failing;

        Opened(Exception failing) {
            this.failing = failing;
        }

        @Override
        public void close() throws Exception {
            closed++;
            if (failing != null) {
                throw failing;
            }
        }
    }

    @Test
    public void whatIsMadeComesBackAndNothingIsClosed() throws Exception {
        Opened opened = new Opened(null);

        assertEquals("encoder", Closing.orClose(opened, () -> "encoder"));

        assertEquals(0, opened.closed);
    }

    @Test
    public void aMakingThatThrowsClosesWhatWasOpenedAndThrowsOn() {
        Opened opened = new Opened(null);
        IllegalStateException why = new IllegalStateException("no policy");
        try {
            Closing.orClose(opened, () -> {
                throw why;
            });
            fail("the throw went on");
        } catch (Exception e) {
            assertSame(why, e);
        }
        assertEquals(1, opened.closed);
    }

    /** A thread the phone cannot make is an Error, not an Exception: the
     *  file is closed for it too. */
    @Test
    public void anErrorClosesItToo() throws Exception {
        Opened opened = new Opened(null);
        OutOfMemoryError why = new OutOfMemoryError("unable to create native thread");
        try {
            Closing.orClose(opened, () -> {
                throw why;
            });
            fail("the throw went on");
        } catch (OutOfMemoryError e) {
            assertSame(why, e);
        }
        assertEquals(1, opened.closed);
    }

    @Test
    public void aCloseThatFailsTooDoesNotHideWhy() {
        IOException closing = new IOException("close failed");
        Opened opened = new Opened(closing);
        IllegalStateException why = new IllegalStateException("no policy");
        try {
            Closing.orClose(opened, () -> {
                throw why;
            });
            fail("the throw went on");
        } catch (Exception e) {
            assertSame(why, e);
            assertEquals(1, e.getSuppressed().length);
            assertSame(closing, e.getSuppressed()[0]);
        }
        assertEquals(1, opened.closed);
    }
}
