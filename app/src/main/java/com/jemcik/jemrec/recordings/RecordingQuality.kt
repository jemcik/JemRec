package com.jemcik.jemrec.recordings

import android.content.Context
import androidx.core.content.edit
import com.jemcik.jemrec.Prefs

/**
 * Settings' "Quality": how many bits a second the recorder gives a call
 * (its Quality.java), for calls recorded from then on.
 *
 * Until 1.2 every recording was 128 kbps Opus - scrcpy's default - and with
 * the Ogg pages Android writes, the 101 recordings on the Honor (MagicOS 11,
 * Android 17) averaged 139.9 kbps, about 63 MB an hour (2026-11-16). In a
 * blind test there the same day - a phone call, a messenger call and a
 * message, each encoded on the phone at 24, 32, 48, 64 and 128 kbps - its
 * owner heard no difference through the speakers or through earbuds, but
 * for 24 kbps on the messenger call, which "sounds in mono" - as libopus's
 * source says its speech mode narrows the stereo when the bits run short. So
 * 32 is the default, and 24 is offered for those who want the smallest
 * files, saying that much.
 *
 * [mbPerHour]: what an hour came to at each rate in that test, the pages
 * joined (OggPages) - Opus gives a call's quieter stretches fewer bits, so a
 * recording is about this, not exactly.
 */
enum class RecordingQuality(val bitRate: Int, val mbPerHour: Int) {
    /** 17-23 kbps in the test: 8-10 MB an hour. */
    SMALLEST(24_000, 9),

    /** 22-30 kbps: 10-14 MB an hour. */
    STANDARD(32_000, 12),

    /** 63-65 kbps. */
    HIGH(64_000, 29),

    /** 125-130 kbps: what every recording was before 1.2, less the pages. */
    HIGHEST(128_000, 58),
}

/** The choice, stored by its name; [RecordingQuality.STANDARD] until one is
 *  made - for an update too, so recordings shrink without anyone asking. */
internal object QualitySetting {

    private const val KEY = "recording_quality"

    fun of(context: Context): RecordingQuality =
        Prefs.of(context).getString(KEY, null)
            ?.let { name -> RecordingQuality.entries.firstOrNull { it.name == name } }
            ?: RecordingQuality.STANDARD

    fun set(context: Context, quality: RecordingQuality) {
        Prefs.of(context).edit { putString(KEY, quality.name) }
    }

    /** What the recorder is told, at spawn and on a change: the rate in bits
     *  a second, "32000" (its Quality.parse). */
    fun wire(context: Context): String = of(context).bitRate.toString()
}
