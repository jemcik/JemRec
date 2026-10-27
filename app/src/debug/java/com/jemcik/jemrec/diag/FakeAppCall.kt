package com.jemcik.jemrec.diag

import android.content.ComponentName
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager

/**
 * A stand-in for Google Meet's calls, for tools/emulator_checks.py: a call of
 * this debug build's own, put through Telecom on a self-managed account, as
 * Meet's are (the Honor, 2026-10-26: `dumpsys telecom` lists Meet's account as
 * SelfManaged, and the call state rang and went off-hook for its calls). The
 * check sees the recorder leave it alone, then rings a phone call in over it
 * and has that recorded as a phone call of its own. It asks to be in the call
 * log, as Meet's calls are, so the app's call-log readers meet one too: the
 * Android 12 and 13 emulators log it, the Android 17 one does not (2026-10-26),
 * and the check writes the row there itself. While up it plays voice-call
 * audio, silence, as Meet plays the other side: placed over a phone call
 * being recorded, the recorder must see an app's call in front and keep the
 * phone call off the mixer (app_call_in_front). NOT in a release build.
 *
 *   --es op appcall --es do start
 *   --es op appcall --es do end
 */
object FakeAppCall {

    private const val ACCOUNT = "jemrec-stand-in-app-call"

    @Volatile internal var connection: Connection? = null

    /** The call's audio, while it is up. */
    private var sound: AudioTrack? = null

    /** Silence, looped, as voice-call audio. A sound that will not play is
     *  said, never thrown: Telecom calls this on the app's main thread,
     *  where a throw would end the app and the call with it. */
    @Synchronized
    internal fun soundOn() {
        if (sound != null) return
        runCatching { newSound() }
            .onSuccess { sound = it }
            .onFailure { android.util.Log.w("JemRec", "appcall: no sound: $it") }
    }

    private fun newSound(): AudioTrack {
        val frames = 1600
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(16000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(frames * 2)
            .build()
            .apply {
                write(ShortArray(frames), 0, frames)
                setLoopPoints(0, frames, -1)
                play()
            }
    }

    @Synchronized
    internal fun soundOff() {
        sound?.run {
            runCatching { stop() }
            release()
        }
        sound = null
    }

    /** A call was asked of Telecom and has not ended: set before Telecom
     *  creates the connection, so a second start in between is turned away
     *  rather than placing a call end() would never reach. */
    @Volatile internal var placed = false

    private fun handle(context: Context) =
        PhoneAccountHandle(ComponentName(context, FakeAppCallService::class.java), ACCOUNT)

    fun start(context: Context): String {
        if (placed) return "already up"
        val telecom = context.getSystemService(TelecomManager::class.java)
        val handle = handle(context)
        telecom.registerPhoneAccount(
            PhoneAccount.builder(handle, "JemRec stand-in call")
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .setExtras(Bundle().apply { putBoolean(PhoneAccount.EXTRA_LOG_SELF_MANAGED_CALLS, true) })
                .build(),
        )
        placed = true
        // MANAGE_OWN_CALLS is a normal permission, granted at install; a
        // refusal all the same is said, not thrown.
        try {
            telecom.placeCall(
                Uri.fromParts(PhoneAccount.SCHEME_TEL, "5550100", null),
                Bundle().apply { putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle) },
            )
        } catch (e: SecurityException) {
            placed = false
            return "refused: $e"
        }
        return "placed through Telecom, self-managed"
    }

    fun end(): String {
        // None created: Telecom dropped the request, or has yet to answer it.
        // Either way the next start must not be turned away for it.
        val call = connection ?: run {
            placed = false
            return "no call up"
        }
        call.onDisconnect()
        return "ended"
    }
}

/** Telecom's way into the stand-in call. Debug manifest only. */
class FakeAppCallService : ConnectionService() {

    override fun onCreateOutgoingConnection(account: PhoneAccountHandle?, request: ConnectionRequest?): Connection {
        val call = object : Connection() {
            // A phone call answered over it holds it, as Telecom holds Meet's.
            override fun onHold() = setOnHold()

            override fun onUnhold() = setActive()

            override fun onDisconnect() {
                FakeAppCall.soundOff()
                setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
                destroy()
                FakeAppCall.connection = null
                FakeAppCall.placed = false
            }

            override fun onAbort() = onDisconnect()
        }
        call.connectionProperties = Connection.PROPERTY_SELF_MANAGED
        call.connectionCapabilities = Connection.CAPABILITY_HOLD or Connection.CAPABILITY_SUPPORT_HOLD
        // Telecom then puts the phone in MODE_IN_COMMUNICATION, as for Meet.
        call.setAudioModeIsVoip(true)
        call.setAddress(request?.address, TelecomManager.PRESENTATION_ALLOWED)
        call.setActive()
        FakeAppCall.connection = call
        FakeAppCall.soundOn()
        return call
    }

    override fun onCreateOutgoingConnectionFailed(account: PhoneAccountHandle?, request: ConnectionRequest?) {
        FakeAppCall.placed = false
    }
}
