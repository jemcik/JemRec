package com.jemcik.jemrec.shell;

import com.genymobile.scrcpy.util.Ln;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;

import java.lang.reflect.Field;

/**
 * Android as this process reaches it: every hidden or system API the recorder
 * calls by reflection, and the services it holds, rebound to the shell's
 * context - what an app process is handed at its start and this one, started
 * from a shell, never is. In one place because each can fail on a phone of
 * its own, and fails here alone: Voip, Forwarding, AudioScene and the
 * greeting's player keep theirs, and Main its one, the main looper set
 * before anything else may run (prepareMainLooper).
 */
final class Platform {

    private Platform() {
    }

    static @Nullable Handler mainHandler;
    static @Nullable AudioManager callAudio;
    static @Nullable TelecomManager telecom;
    static @Nullable TelephonyManager phone;

    /**
     * TelephonyManager reaches the phone service through a static "service
     * registerer" that a normal app process is handed during startup and this
     * process never is - it was started from a shell, not by the system. Left
     * unset it is null and registration dies in getITelephony() with an NPE
     * (measured). The class that holds it is hidden, so it is set the way
     * scrcpy sets every other piece of app-process furniture this daemon
     * lacks: by reflection.
     */
    static void bindTelephonyService() throws ReflectiveOperationException {
        Class<?> initializer = Class.forName("android.telephony.TelephonyFrameworkInitializer");
        Class<?> managerClass = Class.forName("android.os.TelephonyServiceManager");
        Object manager = managerClass.getDeclaredConstructor().newInstance();
        initializer.getDeclaredMethod("setTelephonyServiceManager", managerClass)
                .invoke(null, manager);
    }

    /**
     * The manager the system hands back is bound to the system context, whose
     * package is "android" - and telephony checks the calling package belongs
     * to the calling uid, so it throws "Package android does not belong to
     * 2000" (measured, the second wall this hit). Its mContext is repointed at
     * the FakeContext, whose package is com.android.shell and DOES belong to
     * shell - the identical trick FakeContext already plays for the clipboard,
     * and better than the hidden TelephonyManager(Context) constructor, which
     * is not in the platform jar this compiles against.
     */
    static void rebindTelephony(TelephonyManager telephony, Context context) {
        try {
            Field mContext = TelephonyManager.class.getDeclaredField("mContext");
            mContext.setAccessible(true);
            mContext.set(telephony, context);
        } catch (ReflectiveOperationException e) {
            Ln.w("callwatch: could not rebind telephony context: " + e.getMessage());
        }
    }

    /**
     * Handed out with no context at all: setMicrophoneMute reads the caller's
     * package from it, as setMode does - which died of an NPE in the
     * answering-machine probe (the Honor, 2026-10-22). getMode swallows the
     * same NPE, which is why the call watch never noticed. Only the answering
     * machine mutes, so a phone where this fails loses that, not the watch.
     * True once rebound: the microphone can be muted from here.
     */
    static boolean rebindAudio(AudioManager audio, Context context) {
        try {
            for (String name : new String[]{"mOriginalContext", "mApplicationContext"}) {
                Field field = AudioManager.class.getDeclaredField(name);
                field.setAccessible(true);
                field.set(audio, context);
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            Ln.w("answer: could not rebind the audio context, so no mute and no answering: " + e);
            return false;
        }
    }

    /** As telephony's (rebindTelephony), for the answering machine's accept
     *  and hang-up. */
    static void rebindTelecom(TelecomManager telecom, Context context) {
        try {
            Field mContext = TelecomManager.class.getDeclaredField("mContext");
            mContext.setAccessible(true);
            mContext.set(telecom, context);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // The answering machine's, not the watch's: whatever this
            // throws must not stop the call watch from registering.
            Ln.w("answer: could not rebind the telecom context: " + e);
        }
    }

    /** Whether the phone is in its emergency callback mode: a SystemApi
     *  under READ_PRIVILEGED_PHONE_STATE, the shell's. False if it cannot
     *  be asked. */
    static boolean emergencyCallbackMode(TelephonyManager phone) {
        try {
            return (boolean) TelephonyManager.class.getMethod("getEmergencyCallbackMode").invoke(phone);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Whether call screening can play into a phone call here: false before
     *  Android 13, where it is not there, or where it is not allowed. */
    static boolean pstnCallAudioInterceptable(@Nullable AudioManager audio) {
        try {
            return (boolean) AudioManager.class.getMethod("isPstnCallAudioInterceptable").invoke(audio);
        } catch (Throwable ignored) {
            // Not there before Android 13, or not allowed.
            return false;
        }
    }

    /** A system property, through the hidden SystemProperties; null if it
     *  cannot be read. */
    static @Nullable String property(String name) {
        try {
            return (String) Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class).invoke(null, name);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The USB service and its ports, read through the hidden IUsbManager:
     * shell's MANAGE_USB reads a port's status, which flips the moment the
     * cable does (see CableWatch.watchCable).
     */
    static final class Usb {
        private final Object service;
        private final java.lang.reflect.Method status;
        private final java.lang.reflect.Method mode;
        /** The ports' ids, as Android names them; empty when it has none yet. */
        final java.util.List<String> ports;

        private Usb(Object service, java.lang.reflect.Method status, java.lang.reflect.Method mode,
                java.util.List<String> ports) {
            this.service = service;
            this.status = status;
            this.mode = mode;
            this.ports = ports;
        }

        /** The service and its ports now; throws if it cannot be asked yet. */
        static Usb find() throws ReflectiveOperationException {
            Object binder = Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "usb");
            Object usb = Class.forName("android.hardware.usb.IUsbManager$Stub")
                    .getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
            java.util.List<?> found = (java.util.List<?>) usb.getClass().getMethod("getPorts").invoke(usb);
            java.lang.reflect.Method status = usb.getClass().getMethod("getPortStatus", String.class);
            java.lang.reflect.Method mode = Class.forName("android.hardware.usb.UsbPortStatus").getMethod("getCurrentMode");
            java.util.List<String> ports = new java.util.ArrayList<>();
            if (found != null) {
                for (Object port : found) {
                    ports.add(portId(port));
                }
            }
            return new Usb(usb, status, mode, ports);
        }

        /** UsbPortStatus.getCurrentMode for [port], or -1 if it could not be
         *  read. No status at all is a port the service does not know -
         *  unknown, not "no cable", which is a status with mode 0. */
        int mode(String port) {
            try {
                Object reading = status.invoke(service, port);
                return reading == null ? -1 : (Integer) mode.invoke(reading);
            } catch (Throwable t) {
                return -1;
            }
        }

        /** A port's id: the method where there is one, the field where Android
         *  17 has only that, and the Honor's one port's name failing both. */
        private static String portId(Object parcelable) {
            try {
                return (String) parcelable.getClass().getMethod("getId").invoke(parcelable);
            } catch (Throwable ignored) {
                // Android 17's ParcelableUsbPort has no getter.
            }
            try {
                Field field = parcelable.getClass().getDeclaredField("mId");
                field.setAccessible(true);
                return (String) field.get(parcelable);
            } catch (Throwable ignored) {
                return "port0";
            }
        }
    }

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
