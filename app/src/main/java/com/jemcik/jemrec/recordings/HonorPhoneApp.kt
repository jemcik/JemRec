package com.jemcik.jemrec.recordings

import java.text.SimpleDateFormat
import java.util.regex.Pattern

/**
 * How Honor's Phone app reads a recording's file name for the number and the
 * time it matches to a call: its CallRecordUtils.getRecordPhoneNumber and
 * getCallRecordCreatedDate, decompiled from HnContacts 19.0.8.606 on the
 * Honor (2026-11-17), line for line - Java's String.replaceAll and split
 * included, which read their first argument as a regular expression and drop
 * trailing empty parts. What the Honor reads is checked against JemRec's
 * names off the phone (FileNamesTest), and a name saved before 1.2 is given
 * another only where the app cannot read this one (FileNames.honorRenamed):
 * a name it reads was renamed for nothing (review, 2026-11-18). Pure.
 */
internal object HonorPhoneApp {

    /** What the app gives up with: no number it can match. */
    const val NO_NUMBER = "common-number"

    /** The number the app reads in [name], or [NO_NUMBER]. */
    fun number(name: String): String = try {
        read(name)
    } catch (e: Exception) {
        // The app catches it, logs "parse record file name failed", and
        // matches nothing.
        NO_NUMBER
    }

    private fun read(name: String): String {
        val dot = name.lastIndexOf(".")
        if (dot <= 0) return NO_NUMBER
        var stem = name.substring(0, dot)
        if (stem.contains("@")) {
            val head = stem.substring(0, stem.lastIndexOf("@") + 1)
            // String.replaceAll(head, head.replaceAll("_", "")): both patterns.
            stem = Pattern.compile(head).matcher(stem).replaceAll(Pattern.compile("_").matcher(head).replaceAll(""))
        }
        val parts = Pattern.compile("_").split(stem)
        if (parts.size != 2 && parts.size != 4) return NO_NUMBER
        if (parts.size == 4 && parts[2].length >= 3) return parts[2].replace(Regex("[^0-9|+]"), "")
        if (parts.size == 2) {
            val who = Pattern.compile("@").split(parts[0])
            if (who.isNotEmpty() && who.last().length >= 3) return who.last().replace(Regex("[^0-9|+]"), "")
        }
        return NO_NUMBER
    }

    /** The time the app reads in [name], or -1. Its formatter is lenient
     *  and reads past anything after the time. */
    fun time(name: String): Long = try {
        val dot = name.lastIndexOf(".")
        if (dot <= 0) {
            -1
        } else {
            val parts = Pattern.compile("_").split(name.substring(0, dot))
            // The Phone app's own formatter takes the phone's locale; digits
            // read the same in every locale that writes them as ASCII.
            if (parts.isEmpty()) -1 else SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.getDefault()).parse(parts.last())!!.time
        }
    } catch (e: Exception) {
        -1
    }
}
