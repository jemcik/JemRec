package com.jemcik.jemrec.capture

import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.jemcik.jemrec.MainActivity
import com.jemcik.jemrec.R

/**
 * Plays a recording with the screen off and the app out of sight, under the
 * system's media controls - the notification, the lock screen, Quick
 * Settings, a headset's buttons, Honor's pill at the top of the screen.
 *
 * Android's way to play on in the background is a media session in a
 * service (Media3's background-playback guide); until 0.19 JemRec had none,
 * so a recording paused when the app went out of sight - nothing could have
 * stopped it from a pocket. Measured with a probe, 2026-10-23: on the Android
 * 17 emulator and on the Honor (MagicOS 11) playback went on with the screen
 * off, 59.2 s of it in 60.3 s on the Honor, and the Honor's pill showed it
 * from this standard session alone, with nothing Honor-specific.
 *
 * What it shows: the caller's name once the phone is unlocked, and
 * PlaybackRules.LOCKED_TITLE while it is locked - the user's choice. One
 * session feeds every surface, so the switch is made in the session itself,
 * when the screen goes off and when the phone is unlocked.
 *
 * Who may connect: Android lets any app ask. JemRec chooses what plays; the
 * system's controls may play, pause and seek; anyone else is turned away
 * (PlaybackRules.access). Exported because the system's controls must reach
 * it, which is how Media3's session service is declared.
 *
 * Swiping JemRec away in Recents leaves a playing recording playing, as
 * music apps do (Media3's default, and the user's choice); one that is
 * paused goes with the app.
 *
 * Its notification's channel and icon are set through Media3's unstable
 * API, pinned with Media3's version as SessionPlayer is.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private var player: SessionPlayer? = null

    /** What system_server sends: the screen going off and coming on, and
     *  headphones coming out. */
    private val events = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val player = player ?: return
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> player.locked = true
                // No lock screen at all, or one dismissed by Smart Lock:
                // what Android says, as the screen comes on.
                Intent.ACTION_SCREEN_ON -> player.locked = keyguard().isKeyguardLocked
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> player.becameNoisy()
            }
        }
    }

    /**
     * The phone unlocked. A receiver of its own, exported, because SystemUI
     * sends this, and SystemUI is an app: a receiver registered not exported
     * takes broadcasts only from the system and from JemRec. Measured on the
     * Honor, 2026-10-23: the unlock's record listed 55 receivers and not
     * this one, and the controls said "Call recording" after the unlock. The
     * emulators missed it - with no lock screen, the name came back as the
     * screen came on. Exported is safe for it: USER_PRESENT is a protected
     * broadcast, which only the phone's own system apps may send.
     */
    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_PRESENT) player?.locked = false
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        val focus = AndroidAudioFocus(this)
        val player = SessionPlayer(mainLooper, RecordingPlayer(this), focus)
        focus.listener = player::onFocusChange
        player.locked = keyguard().isKeyguardLocked
        this.player = player
        session = MediaSession.Builder(this, player)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setCallback(Gate())
            .build()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.playback_channel)
                .build()
                .apply { setSmallIcon(android.R.drawable.ic_media_play) },
        )
        // Stopped or failed, the notification goes: nothing is left to control.
        setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_NEVER)
        ContextCompat.registerReceiver(
            this, events,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            this, unlocked,
            IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        runCatching { unregisterReceiver(events) }
        runCatching { unregisterReceiver(unlocked) }
        session?.release()
        player?.release()
        session = null
        player = null
        running = false
        super.onDestroy()
    }

    private fun keyguard() = getSystemService(KeyguardManager::class.java)

    private inner class Gate : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val own = controller.packageName == packageName && controller.uid == Process.myUid()
            return when (PlaybackRules.access(own, controller.isTrusted)) {
                PlaybackRules.Access.ALL -> MediaSession.ConnectionResult.AcceptedResultBuilder(session).build()
                PlaybackRules.Access.TRANSPORT -> MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailablePlayerCommands(SessionPlayer.TRANSPORT)
                    .build()
                PlaybackRules.Access.NONE -> {
                    Log.w(TAG, "playback: turned away ${controller.packageName}")
                    MediaSession.ConnectionResult.reject()
                }
            }
        }

        /** A controller's items reach the session without their uri; JemRec
         *  sends it as the request's media uri, Media3's place for it. Only
         *  JemRec may set items at all (SessionPlayer.TRANSPORT). */
        override fun onAddMediaItems(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> = Futures.immediateFuture(
            mediaItems.map { item ->
                if (item.localConfiguration != null) item else item.buildUpon().setUri(item.requestMetadata.mediaUri).build()
            },
        )
    }

    companion object {
        private const val TAG = "JemRec"
        private const val CHANNEL_ID = "playback"

        /** Whether the service is up, so a screen opened while it plays
         *  connects to it and shows what is playing. Main thread. */
        @Volatile
        internal var running = false
            private set
    }
}

/** Android's audio focus for SessionPlayer: asked for as speech, which
 *  Android does not duck but tells to pause (setWillPauseWhenDucked). */
internal class AndroidAudioFocus(context: Context) : FocusHolder {

    var listener: (PlaybackRules.Focus) -> Unit = {}

    private val audio = context.getSystemService(AudioManager::class.java)

    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ change ->
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> PlaybackRules.Focus.GAIN
                AudioManager.AUDIOFOCUS_LOSS -> PlaybackRules.Focus.LOSS
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> PlaybackRules.Focus.LOSS_TRANSIENT
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> PlaybackRules.Focus.LOSS_TRANSIENT_CAN_DUCK
                else -> null
            }?.let(listener)
        }, Handler(Looper.getMainLooper()))
        .build()

    override fun request(): Boolean = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    override fun abandon() {
        audio.abandonAudioFocusRequest(request)
    }
}
