package com.jemcik.jemrec.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.adb.AdbTransport
import com.jemcik.jemrec.adb.AdbIdentity
import com.jemcik.jemrec.capture.CallMonitorService
import android.net.Uri
import com.jemcik.jemrec.capture.AudioAccess
import com.jemcik.jemrec.capture.CaptureDaemon
import com.jemcik.jemrec.capture.GlobalSettings
import com.jemcik.jemrec.capture.RecorderSwitch
import com.jemcik.jemrec.capture.RecordingMode
import com.jemcik.jemrec.capture.RecordingStore

/**
 * What still has to happen before this phone can record a call.
 *
 * Setup needs no computer, which is the point of the whole design and worth
 * saying out loud: the app pairs with the phone's own Wireless debugging, and
 * then grants ITSELF the one privileged permission it needs by running
 * `pm grant` over that session. Measured - the permission was revoked, the app
 * was asked to grant it, and it came back granted=true.
 *
 * So the user's part is small: Developer options, two switches, a six-digit
 * code. Everything after that is automatic.
 */
enum class SetupStep {
    /** Working it out. */
    CHECKING,

    /** The runtime permissions setup needs - notifications, from Android 13 -
     *  have not been granted yet. First, because without them nothing else is
     *  worth doing: a recorder whose notification cannot show is invisible. */
    NEEDS_PERMISSIONS,
    /** Developer options are off, so the Wireless debugging screen does not
     *  exist to send anyone to. Its own step, because the intent that opens
     *  Developer options silently does nothing while they are disabled - so
     *  the button that was supposed to start setup appeared broken. */
    NEEDS_DEVELOPER_OPTIONS,

    /**
     * Developer options are on but the app is not yet a trusted ADB peer, so
     * the phone cannot be reached. ONE step covers the two hand-actions that
     * get us there - turning Wireless debugging on and typing the pairing code
     * - because both happen inside the Wireless debugging screen, and splitting
     * them only sent the user back to the app in between for nothing. The
     * card's switches step covers both debugging switches at once - both must
     * be on, in any order - naming whichever is still off. USB debugging is
     * what makes Wireless debugging stay on at all on this phone (see
     * usbDebuggingOn).
     */
    NEEDS_PAIRING,

    /**
     * Paired once already - adbd trusts our key, and that trust survives reboots
     * - but there is no live session. Almost always because Wireless debugging
     * is off (it drops on its own on some ROMs); occasionally a connect that has
     * not caught yet. Either way the fix is "get Wireless debugging on and
     * connect", NOT "pair again" - so it is its own step, with a much shorter
     * card, instead of throwing a paired user back through the pairing flow.
     */
    NEEDS_CONNECTION,

    /** Paired. Granting the permission and starting the recorder. */
    FINISHING,

    /** A call would be recorded right now. */
    READY,
}

object Setup {

    private const val TAG = "JemRec"
    private const val KEY_COMPLETE = "setup_complete"
    private const val KEY_PAIRED = "setup_paired"
    private const val PERMISSION = android.Manifest.permission.WRITE_SECURE_SETTINGS

    fun hasSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Whether the Wireless debugging screen exists to send anyone to. */
    fun developerOptionsOn(context: Context): Boolean =
        GlobalSettings.isOn(context, GlobalSettings.DEVELOPMENT_SETTINGS_ENABLED)

    /**
     * Setup has run all the way through at least once.
     *
     * Needed because the obvious test - "do we hold WRITE_SECURE_SETTINGS" -
     * lies for the entire first session after setup. finish() takes that
     * permission with `pm grant`, and a granted permission does not reach the
     * process that asked for it until that process restarts, so
     * checkSelfPermission keeps answering DENIED with the grant already in
     * place. Gating "is this phone set up" on it therefore threw a freshly
     * paired, freshly granted phone back to the first step of the wizard,
     * which is precisely what it did.
     *
     * A flag written when finish() succeeds does not have that problem.
     *
     * NOTHING ELSE IS ACCEPTED AS PROOF, and both obvious substitutes were
     * tried and are wrong:
     *
     * Holding WRITE_SECURE_SETTINGS is not proof. It survives `pm clear` and
     * it survives the app's own Start fresh - reset() says so in as many words,
     * because an app cannot hand back a development permission. Treating it as
     * "setup happened" therefore declared a wiped phone ready.
     *
     * Owning a keypair is not proof either, though the name says otherwise.
     * AdbTransport builds one with AdbIdentity.getOrCreate() the first time it
     * so much as ATTEMPTS a connection, so the file appears on a phone that has
     * never paired with anything. Measured: cleared the app's data, opened it,
     * and found a certificate, a private key, and a setup screen claiming the
     * phone was ready to record.
     *
     * Installs that were set up before this flag existed are migrated where it
     * can be proven rather than assumed - see currentStep(), which writes the
     * flag when it finds a recorder actually running.
     */
    fun isComplete(context: Context): Boolean =
        Prefs.of(context)
            .getBoolean(KEY_COMPLETE, false)

    internal fun markComplete(context: Context) {
        Prefs.of(context)
            .edit().putBoolean(KEY_COMPLETE, true).apply()
    }

    /**
     * Pairing has succeeded at least once, so adbd trusts our key.
     *
     * Recorded the instant pair() returns - BEFORE the connect that may fail -
     * so that a connect which does not catch (Wireless debugging dropped, mDNS
     * late) is treated as "come back and finish", never "pair again". The trust
     * itself lives in adbd and outlives this flag; the flag only exists so the
     * wizard can tell a paired phone that merely needs a session from one that
     * has never paired at all, and show the right, shorter card.
     *
     * Distinct from isComplete: pairing is the halfway point, finish() is the
     * end. A phone can be paired and not complete - which is exactly the state
     * this was added to rescue.
     */
    fun hasPaired(context: Context): Boolean =
        Prefs.of(context)
            .getBoolean(KEY_PAIRED, false)

    internal fun markPaired(context: Context) {
        Prefs.of(context)
            .edit().putBoolean(KEY_PAIRED, true).apply()
    }

    /**
     * The permissions setup cannot proceed without, which the user taps
     * "Allow" for.
     *
     * POST_NOTIFICATIONS, from Android 13: it is what lets the per-call
     * notification, the start-of-call prompt and the pairing code box show at
     * all - and a call recorder whose notification is silently hidden is
     * worse than useless. Only where it exists: below Android 13 the platform
     * does not define it, checkSelfPermission answers DENIED for a permission
     * it has never heard of, and the wizard would sit on its first step for
     * ever on a minSdk phone - Android 12 - with nothing there to grant.
     *
     * READ_PHONE_STATE WAS HERE, AND WAS NEVER USED. It was for the app's own
     * TelephonyCallback, from when the app watched calls itself. The daemon
     * has watched them as shell for a while now and no code in the app read
     * the phone state - yet every user was still asked to allow it, under a
     * sentence saying it let JemRec notice calls. Asking for a permission is
     * a claim about what the app does, and this one was false.
     *
     * Not requested anywhere else, which is exactly the bug a genuine fresh
     * install turned up: every earlier test had them granted over adb.
     */
    val RUNTIME_PERMISSIONS: Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    /**
     * Everything worth asking for, asked in one breath at the start.
     *
     * Audio access is NOT in RUNTIME_PERMISSIONS and must not be: setup is
     * blocked until the two above are granted, and being unable to list
     * recordings from a previous install is no reason to refuse to record new
     * ones. But leaving it out of the ASKING was its own mistake - it surfaced
     * as a permission request sitting on the main screen the moment setup
     * finished, which is the one moment a person has earned the right to be
     * finished. Asked here, it costs one more tap in a place already about
     * tapping Allow; refused here, nothing breaks and the recordings list
     * still offers it later.
     */
    val ASK_PERMISSIONS = RUNTIME_PERMISSIONS + AudioAccess.PERMISSIONS

    fun missingRuntimePermissions(context: Context): List<String> =
        RUNTIME_PERMISSIONS.filter {
            context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

    fun wirelessDebuggingOn(context: Context): Boolean =
        GlobalSettings.isOn(context, GlobalSettings.ADB_WIFI_ENABLED)

    /**
     * USB debugging - and on this phone it is NOT optional for first setup.
     *
     * Wireless debugging's Settings toggle only STICKS when USB debugging is
     * already on. AdbService keeps adbd alive only while USB or Wireless
     * debugging is enabled; with USB off, the first transient Wireless-off tears
     * adbd - and its TLS connect server - down. What that looked like from the
     * outside: Wireless debugging came on just long enough for the pairing
     * dialog, pair() succeeded every single time (three JemRec keys ended up
     * under Paired devices from three attempts), and then the setting was 0 by
     * the time the connect ran, so there was never anything to reach. Setup was
     * stuck in a loop.
     *
     * Proven on the device: with USB debugging on, Honor's real toggle logged
     * `setAdbEnabled(true), mIsAdbUsbEnabled=true`, adbd started its wireless
     * server immediately, and the setting held at 1 under sampling. Writing the
     * setting directly over adb had always "stuck" for the same reason - USB
     * debugging was on whenever adb was in use - which is what hid this.
     *
     * The app already depends on adb_enabled=1 as its off-Wi-Fi shield, but
     * finish() set it AFTER the first connect that itself needs it. So step 2
     * has the user turn it on first - it is the row right above Wireless
     * debugging - and this is what lets the card show that line only while it
     * is still off. With no cable plugged in it does nothing, and "Always
     * prompt when connecting to USB" stays on, so a computer would still have
     * to be authorised.
     *
     * ONE MORE TWIST, from the first real fresh-install run: switched on from
     * the toggle with no cable attached, USB debugging turned ITSELF off again
     * shortly after - and the connect failed exactly as before, twice. Enabling
     * it a SECOND time, with Wireless debugging already on, held, and the
     * connect landed - and the steps prescribed that order for a while. A
     * later run needed several attempts in that order too: the toggles simply
     * do not hold reliably on this phone, in either order. So the steps no
     * longer prescribe one - both on, any order - and the real fix is
     * PairingService: after the code it watches both switches, says which is
     * off, and connects the moment both are on. The reason for the auto-off
     * is not in the log - this ROM emits no AdbService lines unless a
     * computer is attached.
     */
    fun usbDebuggingOn(context: Context): Boolean =
        GlobalSettings.isOn(context, GlobalSettings.ADB_ENABLED)

    /**
     * About phone, where Build number is, for turning Developer options on.
     *
     * A different destination from the plain-Settings openSettings() on purpose:
     * this jumps straight to About phone, where Build number is tapped to enable
     * Developer options. ACTION_DEVICE_INFO_SETTINGS is not blocked the way the
     * Developer-options deep-link is, so it opens for a new, unplugged user.
     */
    fun openAboutPhone(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    /**
     * Open plain Settings - NOT a direct link to Developer options.
     *
     * ACTION_APPLICATION_DEVELOPMENT_SETTINGS, the intent that jumps straight to
     * Developer options, is silently swallowed on this Honor ROM for an app that
     * is not adb-connected: it neither opens a screen nor throws, so even the
     * runCatching fallback never fired and the button just did nothing - exactly
     * when a new user, unplugged, needs it. Ordinary Settings opens fine (About
     * phone above is proof), so this lands there and the steps walk the user to
     * Wireless debugging from Settings' own search.
     */
    fun openSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * Everything currentStep() asks the world, behind one interface.
     *
     * The decision below has had more regressions than any other function in
     * the app - each block of it is a comment about one, and every one of
     * them was found on a phone. With the questions behind this interface
     * the decision itself runs on the JVM against a fake, and each of those
     * regressions is a test (SetupStepTest) against decide(). The live answers are in
     * LiveProbe; the decision does not know which it is talking to.
     */
    internal interface Probe {
        fun missingPermissions(): Boolean
        fun recorderOn(): Boolean
        fun isComplete(): Boolean
        fun markComplete()
        fun hasIdentity(): Boolean
        suspend fun daemonRunning(): Boolean
        suspend fun retireDaemon()
        fun adbConnected(): Boolean
        suspend fun connect()
        fun wirelessDebuggingOn(): Boolean
        fun developerOptionsOn(): Boolean
        fun hasPaired(): Boolean
    }

    private class LiveProbe(private val context: Context) : Probe {
        override fun missingPermissions() = missingRuntimePermissions(context).isNotEmpty()
        override fun recorderOn() = RecorderSwitch.isOn(context)
        override fun isComplete() = Setup.isComplete(context)
        override fun markComplete() = Setup.markComplete(context)
        override fun hasIdentity() = AdbIdentity.exists(context)
        override suspend fun daemonRunning() = CaptureDaemon.isRunningConfirmed(context)
        override suspend fun retireDaemon() {
            CaptureDaemon.quit(context)
        }
        override fun adbConnected() = AdbTransport.isConnected
        override suspend fun connect() {
            AdbTransport.autoConnect(context, timeoutMs = 4_000)
        }
        override fun wirelessDebuggingOn() = Setup.wirelessDebuggingOn(context)
        override fun developerOptionsOn() = Setup.developerOptionsOn(context)
        override fun hasPaired() = Setup.hasPaired(context)
    }

    /**
     * Where setup currently stands.
     *
     * Tries to connect first, because a previous pairing is remembered by adbd
     * and needs no code - so "already set up" must not be mistaken for "needs
     * pairing" just because this process is new.
     */
    suspend fun currentStep(context: Context): SetupStep = decide(LiveProbe(context))

    internal suspend fun decide(world: Probe): SetupStep {
        // Asked first. The rest of setup is pointless without these, and
        // finding out at the end would mean pairing before discovering the app
        // cannot see calls.
        if (world.missingPermissions()) {
            return SetupStep.NEEDS_PERMISSIONS
        }

        // SWITCHED OFF IS NOT "NOT SET UP".
        //
        // With the recorder stopped on purpose, every check below would fail in
        // turn and land the user in the pairing wizard - for a phone they had
        // set up perfectly well and then chosen to switch off. The completion
        // flag is what says setup has happened; the switch says whether it is
        // running. It was the keypair here, and a keypair is not proof - see
        // isComplete - so a phone that had never finished setup, with the
        // switch off, was called ready.
        if (!world.recorderOn() && world.isComplete()) {
            return SetupStep.READY
        }

        // THE RECORDER RUNNING IS WHAT "SET UP" MEANS. Asked before anything
        // about ADB, and that order is the whole point of the architecture.
        //
        // ADB is a bootstrap, not a runtime dependency: once the daemon is up
        // it serves loopback and needs no adbd, no mDNS and no Wi-Fi. So a
        // phone with the recorder running is READY even with no ADB session at
        // all - which is the normal state away from Wi-Fi.
        //
        // Getting this backwards showed a fully working app the setup wizard,
        // asking the user to turn on Wireless debugging while it was quietly
        // recording calls perfectly well. Alarming, and wrong.
        if (world.daemonRunning()) {
            // ...AND IT IS OURS, because the check is an authenticated ping:
            // only a daemon started with this install's token answers it.
            //
            // The daemon runs as shell in its own session, so it survives the
            // app being uninstalled. Install JemRec again within a few minutes
            // and there it is on the port, from the version that was removed.
            // That one does not know the new token, so it fails this check and
            // ensureRunning() retires it over ADB when setup gets that far -
            // rather than the wizard calling the phone set up and handing the
            // user an app recording through code they thought they had
            // deleted.
            //
            // What CAN answer without a keypair is our own daemon after a
            // Start fresh that failed to stop it: the token outlives the
            // identity. That one is ours to retire, and this is the place.
            if (world.hasIdentity()) {
                // A recorder that is up and answering is the proof the flag
                // wants, so installs from before it existed get it here rather
                // than being sent back through a wizard they finished long ago.
                world.markComplete()
                return SetupStep.READY
            }
            Log.i(TAG, "setup: found our recorder with no identity to go with it, retiring it")
            world.retireDaemon()
        }

        // A RECORDER THAT IS MERELY DOWN IS NOT AN UNCONFIGURED PHONE.
        //
        // The same rule as the switch above, for the same reason. Every check
        // past this point is about ADB, and ADB is deliberately turned off
        // between calls - so a phone whose daemon happened to be down answered
        // "needs Wireless debugging" and was shown step 2 of the setup wizard,
        // days after setup finished. Reported as the app starting "very
        // inconsistently": the home screen, a stopped recorder, or the wizard,
        // decided by nothing but whether the daemon was up at that instant.
        //
        // Both facts are required. A keypair says pairing happened; the granted
        // permission says setup FINISHED, since finish() is the only thing that
        // grants it - and it is also exactly what revive() needs to re-arm
        // Wireless debugging on its own. Being able to repair this without the
        // user is what makes it not their problem. Bringing the daemon back is
        // the keep-alive job's business, which recheckSetup() has already
        // kicked, and the home screen says whether the recorder is running.
        if (world.hasIdentity() && world.isComplete()) {
            Log.i(TAG, "setup: set up, recorder down - leaving it to the keep-alive")
            return SetupStep.READY
        }

        // Only worth attempting once Wireless debugging is on: adb over the
        // network has nothing to connect to otherwise, so trying just burns the
        // whole timeout. On the Developer-options step - Wireless debugging still
        // off - that was a four-second dead wait on every return from Settings,
        // during which the screen sat on the old step with its button greyed and
        // then jumped. With it gone, the auto-advance to the next step is instant.
        if (!world.adbConnected() && world.wirelessDebuggingOn()) {
            world.connect()
        }

        if (!world.adbConnected()) {
            // Developer options is the one gate that keeps its own step: enabling
            // it is a distinct ritual (Build number, seven taps) in a different
            // corner of Settings. Everything after it - turning Wireless
            // debugging on, then pairing - is one screen and one step. The
            // pairing card shows the "turn it on" lines while Wireless debugging
            // is still off, and drops them the moment it is on.
            return if (!world.developerOptionsOn()) {
                SetupStep.NEEDS_DEVELOPER_OPTIONS
            } else if (world.hasPaired()) {
                // Trust already exists in adbd's keystore and survives reboots,
                // so there is nothing to pair again - only a session to open,
                // which needs Wireless debugging on. Routing a paired phone back
                // through the pairing card is exactly what left setup going in
                // circles when Wireless debugging dropped after a good pairing.
                SetupStep.NEEDS_CONNECTION
            } else {
                SetupStep.NEEDS_PAIRING
            }
        }

        // Connected but no recorder yet: finish the job.
        return SetupStep.FINISHING
    }

    /**
     * The automatic half: take the permission, then start the recorder.
     *
     * Granting requires the app to be restarted before the permission takes
     * effect in this process, which is why the UI reports it as something that
     * happened rather than pretending it is instantly usable.
     */
    suspend fun finish(context: Context): Result<Unit> = runCatching {
        if (!hasSecureSettings(context)) {
            val out = AdbTransport.exec(
                "pm grant ${context.packageName} $PERMISSION && echo granted"
            ).getOrElse { "failed: ${it.message}" }
            Log.i(TAG, "setup: grant -> $out")
        }

        // Granted the same way and at the same moment as the permission above,
        // over the ADB shell rather than through a Settings switch a side-loaded
        // app cannot reach. It lets the cleaner listener hide the "Wireless
        // debugging connected" banner that the recorder cannot avoid keeping on.
        val notif = AdbTransport.exec(
            "cmd notification allow_listener " +
                "${context.packageName}/${context.packageName}.capture.DebugNotificationCleaner " +
                "&& echo granted"
        ).getOrElse { "failed: ${it.message}" }
        Log.i(TAG, "setup: notif listener -> $notif")

        // Re-assert the off-Wi-Fi shield, over the same ADB session (the app's
        // own WRITE_SECURE_SETTINGS is not live until this process restarts, so
        // it cannot set it directly yet). With USB debugging enabled, losing
        // Wi-Fi stops the Wireless server but no longer restarts adbd - so the
        // daemon keeps recording in the field, away from any network. See
        // CaptureDaemon.revive for the mechanism and the on-device proof.
        //
        // "Re-assert", because the user now turns this on in step 2, before
        // pairing: on this phone the FIRST connect needs it too (see
        // usbDebuggingOn), so it could not wait until here. Idempotent.
        val shield = AdbTransport.exec("settings put global adb_enabled 1 && echo shielded")
            .getOrElse { "failed: ${it.message}" }
        Log.i(TAG, "setup: off-Wi-Fi shield -> $shield")

        CaptureDaemon.ensureRunning(context).getOrThrow()
        // Recorded before standing down, because from here on this phone IS
        // set up - and the permission that would otherwise say so is not
        // readable in this process until it restarts.
        markComplete(context)
        // Through the door; put it back.
        CaptureDaemon.standDown(context)
    }

    /**
     * Put the app back to the state a fresh install is in.
     *
     * The test is not "some things were cleared", it is that the setup wizard
     * runs again from its FIRST step. Setup asks for three things, so a reset
     * that deserves the name has to undo all three:
     *
     *   1. the two runtime permissions      -> revoked here
     *   2. Wireless debugging being on      -> turned off here
     *   3. the pairing                      -> keypair discarded here
     *
     * Undoing only the third, which is what this did before, left the wizard
     * starting at step 3 - which is a re-pair, not a fresh start.
     *
     * ORDER IS FORCED. Stopping the daemon needs the ADB session; turning
     * Wireless debugging off destroys it. Revoking the permissions kills this
     * process, so it has to be last, and everything before it has to be on
     * disk by then.
     *
     * WHAT IT DELIBERATELY DOES NOT DO: touch the recordings. They are the one
     * thing here that cannot be recreated, they are recordings of the user's
     * phone calls, and a button called "Start fresh" must not be a way to lose
     * them. Deleting one stays a separate, per-recording, confirmed action.
     *
     * WHAT IT CANNOT DO, and the UI says so:
     *
     * WRITE_SECURE_SETTINGS stays granted. An app may revoke its own RUNTIME
     * permissions; this one is a development permission, granted over `pm
     * grant`, and there is no self-revoke for those. Doing it over ADB would
     * work but kills the process the moment it lands, halfway through the
     * reset and before Wireless debugging could be turned off - and turning
     * Wireless debugging off first destroys the session needed to send it.
     * Genuinely either/or, and turning the debugging off is worth more: it is
     * the part the user can see, and the leftover permission unlocks nothing
     * until they pair again.
     *
     * The phone also keeps trusting the old key forever. That list lives in
     * /data/misc/adb, which is root's. What changes is that this app no longer
     * holds the key, so it must pair again - amnesia rather than revocation.
     *
     * Uninstalling remains the only true zero, and that is fine: it is one tap
     * away and it is what it is for.
     */
    suspend fun reset(context: Context): Result<Unit> = runCatching {
        // Over loopback, NOT over ADB. There is usually no ADB session by
        // this point - there does not need to be one for the app to work - so
        // the old `pkill` route silently did nothing and left the daemon
        // running as an orphan through every reset.
        if (!CaptureDaemon.quit(context)) {
            runCatching { CaptureDaemon.stop() }
                .onFailure { Log.w(TAG, "reset: could not stop the recorder", it) }
        }

        CallMonitorService.stop(context)

        AdbTransport.close()
        AdbIdentity.forget(context)

        RecordingStore.clearTree(context)
        RecordingMode.set(context, RecordingMode.AUTOMATIC)
        // Or the very next launch reads a flag from the setup being undone.
        Prefs.of(context)
            .edit().remove(KEY_COMPLETE).remove(KEY_PAIRED).apply()

        if (hasSecureSettings(context) &&
            GlobalSettings.set(context, GlobalSettings.ADB_WIFI_ENABLED, false)
        ) {
            Log.i(TAG, "reset: Wireless debugging turned off")
        }

        revokeRuntimePermissions(context)
        Log.i(TAG, "reset: done, setup starts again from step 1")
    }

    /**
     * Hand back the permissions the user granted, so setup asks for them again.
     *
     * This kills the app, which is the documented behaviour and not a side
     * effect to work around: a process cannot keep running with permissions it
     * has just given up. The system does it "once it is safe", meaning once the
     * app is no longer in front of the user - which is why the caller closes
     * the activity rather than leaving it sitting there looking unchanged.
     */
    private fun revokeRuntimePermissions(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // Below Android 13 there is no way for an app to hand a runtime
            // permission back. Everything else in the reset still applies.
            Log.w(TAG, "reset: cannot revoke permissions before Android 13")
            return
        }
        val granted = RUNTIME_PERMISSIONS.filter {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        if (granted.isEmpty()) return
        runCatching { context.revokeSelfPermissionsOnKill(granted) }
            .onFailure { Log.w(TAG, "reset: could not revoke permissions", it) }
    }
}
