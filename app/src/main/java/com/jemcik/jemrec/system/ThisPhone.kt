package com.jemcik.jemrec.system

import android.os.Build

/**
 * This phone as every Copy a report names it: maker and model, Android's
 * version, API level and build. One place, so the call report and the setup
 * report say it the same way.
 */
internal object ThisPhone {
    val phone: String get() = "${Build.MANUFACTURER} ${Build.MODEL}"
    val android: String get() = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), build ${Build.DISPLAY}"

    /** A report's phone line, from what was read. */
    fun line(phone: String, android: String): String = "Phone: $phone, $android"
}
