package com.jemcik.jemrec

import java.util.Locale

// Two small formats the app prints in several places, each written once, so
// that a change made to one copy cannot miss the others. Pure: compiled and
// tested on its own in app/pure-tests.

/** [bytes] in lower-case hex, two digits each: a token, a ticket, the start
 *  of a hash. */
internal fun hex(bytes: Iterable<Byte>): String = bytes.joinToString("") { "%02x".format(it) }

internal fun hex(bytes: ByteArray): String = hex(bytes.asIterable())

/**
 * "3:21": whole minutes, then the seconds in two digits; an hour is "60:00".
 * In Latin digits in any language, as every time the app draws (Locale.US):
 * the phone's own would be Arabic-Indic in Arabic or Persian, which the
 * looks' fonts do not have. What comes before - "45 s" under a minute,
 * "--:--" for a length not known - is each caller's.
 */
internal fun minutesSeconds(totalSeconds: Long): String =
    "%d:%02d".format(Locale.US, totalSeconds / 60, totalSeconds % 60)
