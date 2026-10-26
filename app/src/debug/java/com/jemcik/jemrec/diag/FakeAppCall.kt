package com.jemcik.jemrec.diag

import android.content.ComponentName
import android.content.Context
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
 * and the check writes the row there itself. NOT in a release build.
 *
 *   --es op appcall --es do start
 *   --es op appcall --es do end
 */
object FakeAppCall {

    private const val ACCOUNT = "jemrec-stand-in-app-call"

    @Volatile internal var connection: Connection? = null

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
        return call
    }

    override fun onCreateOutgoingConnectionFailed(account: PhoneAccountHandle?, request: ConnectionRequest?) {
        FakeAppCall.placed = false
    }
}
