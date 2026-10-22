package com.jemcik.jemrec.shell;

/**
 * The answering machine's settings as the app sends them, "on,seconds,route":
 * "1,60,music", the seconds being the longest message, 0 for no limit. In the spawn environment (JEMREC_ANSWER) and over the socket
 * (COMMAND_ANSWER_SETTINGS). Anything unreadable is OFF - the same fail-closed
 * rule as the recording mode: a call answered against the user's choice is
 * worse than one the carrier's voicemail takes.
 */
final class AnswerSettings {

    /** The greeting's routes: see GreetingPlayer. */
    static final String ROUTE_MUSIC = "music";
    static final String ROUTE_SCREENING = "screening";

    /** A message as long as the caller wants - until they are silent for a
     *  while (AnswerMachine.SILENCE_HANGUP_MS). */
    static final int NO_LIMIT = 0;

    static final AnswerSettings OFF = new AnswerSettings(false, 60, ROUTE_MUSIC);

    final boolean on;
    final int maxMessageSeconds;
    final String route;

    AnswerSettings(boolean on, int maxMessageSeconds, String route) {
        this.on = on;
        this.maxMessageSeconds = maxMessageSeconds;
        this.route = route;
    }

    static AnswerSettings parse(String wire) {
        if (wire == null) {
            return OFF;
        }
        String[] parts = wire.trim().split(",");
        if (parts.length != 3) {
            return OFF;
        }
        int seconds;
        try {
            seconds = Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            return OFF;
        }
        String route = parts[2].trim();
        if (!route.equals(ROUTE_MUSIC) && !route.equals(ROUTE_SCREENING)) {
            return OFF;
        }
        if (seconds != NO_LIMIT && (seconds < 10 || seconds > 600)) {
            return OFF;
        }
        return new AnswerSettings("1".equals(parts[0].trim()), seconds, route);
    }

    String wire() {
        return (on ? "1" : "0") + "," + maxMessageSeconds + "," + route;
    }
}
