package com.jemcik.jemrec.shell;

import com.genymobile.scrcpy.util.Ln;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * The helpers every part of the recorder uses: a sleep that keeps the
 * interrupt, Android's own tools run as children and what they printed, and
 * closing without a fuss.
 */
final class Shell {

    private Shell() {
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static Throwable rootCause(Throwable t) {
        return t.getCause() != null ? t.getCause() : t;
    }

    /** Run a command, return what it printed, or null if it could not run. */
    static @Nullable String runQuietly(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String said = slurp(process);
            process.waitFor();
            return said;
        } catch (Throwable t) {
            Ln.w("could not run " + String.join(" ", command) + ": " + t);
            return null;
        }
    }

    /** Everything a finished child printed, one line, trimmed. */
    static String slurp(Process process) throws IOException {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (out.length() > 0) {
                    out.append(" | ");
                }
                out.append(line);
            }
        }
        return out.toString().trim();
    }

    /** The whole of `dumpsys <service>`, newlines kept, for the parsers;
     *  null if it could not be run - not an answer to read as "none". */
    static @Nullable String dump(String service) {
        try {
            Process process = new ProcessBuilder("dumpsys", service).redirectErrorStream(true).start();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            try (java.io.InputStream in = process.getInputStream()) {
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
            process.waitFor();
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            Ln.w("could not run dumpsys " + service + ": " + t);
            return null;
        }
    }

    static void closeQuietly(AutoCloseable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
                // Nothing useful to do, and the caller is already on its way out.
            }
        }
    }
}
