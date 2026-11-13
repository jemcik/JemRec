package com.jemcik.jemrec.shell;

import com.genymobile.scrcpy.util.Ln;

import android.media.AudioManager;
import android.os.Handler;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;

/**
 * Android's services as this process holds them, bound to the shell's
 * context, and what is asked of Telecom.
 */
final class Platform {

    private Platform() {
    }

    static @Nullable Handler mainHandler;
    static @Nullable AudioManager callAudio;
    static @Nullable TelecomManager telecom;
    static @Nullable TelephonyManager phone;

    /**
     * Whether an app is in a call of its own through Telecom (a self-managed
     * call, as Google Meet's are): what "communication" mode means in the
     * middle of a phone call when it is not the phone call's - an app outside
     * Telecom cannot take the mode from Telecom. Every app with an account in
     * Telecom is asked, whatever it plays: the stand-in call of the emulator
     * check could not always make a sound (the Android 17 AVD, 2026-10-27),
     * and an app's call held off the mixer by what it plays alone would have
     * left the microphone recording it. True when it cannot be told - the
     * call then stays as 0.23 recorded it.
     */
    static boolean appInCall() {
        TelecomManager t = telecom;
        if (t == null) {
            return true;
        }
        try {
            // SystemApis: the accounts under MODIFY_PHONE_STATE, the calls
            // from Android 14 under READ_PRIVILEGED_PHONE_STATE - the
            // shell's both; the mixer is never asked about before 14.
            java.util.List<?> accounts = (java.util.List<?>) TelecomManager.class
                    .getMethod("getAllPhoneAccountHandles").invoke(t);
            java.lang.reflect.Method inCall = TelecomManager.class.getMethod("isInSelfManagedCall",
                    String.class, android.os.UserHandle.class);
            if (accounts == null) {
                return true;
            }
            for (Object account : accounts) {
                android.telecom.PhoneAccountHandle handle = (android.telecom.PhoneAccountHandle) account;
                String pkg = handle.getComponentName().getPackageName();
                if (Boolean.TRUE.equals(inCall.invoke(t, pkg, handle.getUserHandle()))) {
                    return true;
                }
            }
            return false;
        } catch (Throwable e) {
            Throwable why = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null
                    ? e.getCause() : e;
            Ln.w("recording: could not tell whether another app's call is up: " + why);
            return true;
        }
    }

    /** Whether Telecom holds a phone call - not an app's through it, as
     *  Google Meet's are; null if it cannot be asked. */
    static @Nullable Boolean phoneCallInTelecom() {
        TelecomManager t = telecom;
        if (t == null) {
            return null;
        }
        try {
            // READ_PHONE_STATE, the shell's: asked as the shell on the Honor,
            // 2026-10-26, it answered with no error.
            return t.isInManagedCall();
        } catch (Throwable e) {
            Ln.w("callwatch: could not ask Telecom whether a phone call is up: " + e);
            return null;
        }
    }
}
