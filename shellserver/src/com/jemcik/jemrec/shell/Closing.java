package com.jemcik.jemrec.shell;

/**
 * What is made over something just opened, with what was opened closed
 * again if the making fails. Plain Java, held by shellserver/tests.
 */
final class Closing {

    private Closing() {
    }

    interface Make<T> {
        T make() throws Exception;
    }

    /**
     * [make]'s result. If it throws, [opened] is closed first and the throw
     * goes on as it was; a close that fails too is added to it as
     * suppressed, so it cannot hide why the making failed.
     */
    static <T> T orClose(AutoCloseable opened, Make<T> make) throws Exception {
        try {
            return make.make();
        } catch (Throwable t) {
            try {
                opened.close();
            } catch (Exception closing) {
                t.addSuppressed(closing);
            }
            throw t;
        }
    }
}
