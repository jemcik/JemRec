package com.jemcik.jemrec.playback

/**
 * Where the sound of the recording playing is, read from its player itself:
 * what the screen's playhead and its time show while it plays. PlaybackService
 * plays in this process, so the screen asks the player here rather than
 * Media3's controller, which works the position out from the session's last
 * word. Paused, the controller's word is the player's (the session's paused
 * position), with a seek asked for already in it.
 *
 * WHY. The controller's position runs on from when it was told the recording
 * plays - Play's tap - and is put right at the session's next word. Measured
 * on the Honor with an earbud (OnePlus Buds Pro 3, Bluetooth), 2026-11-17:
 * the sound started 672 ms after Play; Media3 said so 2,142 ms after Play
 * (onAudioPositionAdvancing, late on Bluetooth); until then the controller
 * ran on from Play, and was put back 0.5 s - the playhead, which never goes
 * back, slowed to wait for the sound. The player's own position stood at
 * the start until the sound did start, and moved with it from there (read
 * every 50 ms: 17 ms at 680 ms after Play, 68 at 732, 114 at 782). With
 * PlayClock it holds where a pause stopped the sound, and runs smoothly
 * between ExoPlayer's steps. The controller stays for what it is for:
 * play, pause, seeking, and the system's controls.
 *
 * Main thread only: the player is ExoPlayer, made and read on it.
 */
internal object SoundPosition {

    /** PlaybackService's player while it is up: the position, in ms, of the
     *  recording of that name if it is the one open; null otherwise. */
    var source: ((name: String) -> Int?)? = null

    /** Where the sound of recording [name] is, or null if it is not the one
     *  open - not yet opened, or no player up. */
    fun of(name: String): Int? = source?.invoke(name)
}
