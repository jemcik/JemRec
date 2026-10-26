package com.jemcik.jemrec.probe;

import com.genymobile.scrcpy.FakeContext;
import com.genymobile.scrcpy.Workarounds;

import android.content.Context;
import android.media.AudioManager;
import android.os.Looper;
import android.os.SystemClock;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;

import java.lang.reflect.Field;

/**
 * What Telecom says while a call app's call is up, as the shell sees it: is
 * there a call at all (isInCall), and is it a phone call (isInManagedCall),
 * beside the call state telephony reports and the audio mode. Google Meet's
 * calls go through Telecom as a self-managed account, and the call state
 * reports them as if they were phone calls (the Honor, 2026-10-26). NOT
 * shipped.
 *
 *   CLASSPATH=/data/local/tmp/voip-probe.jar app_process / \
 *       com.jemcik.jemrec.probe.ManagedCallProbe <seconds>
 *
 * Prints a line whenever any of the four changes, and once at the start.
 */
public final class ManagedCallProbe {

    private ManagedCallProbe() {
    }

    @SuppressWarnings("deprecation")
    public static void main(String[] args) throws Exception {
        int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 60;
        // Before ANY mention of Workarounds - see VoipProbe.
        Looper.prepare();
        Field mainLooper = Looper.class.getDeclaredField("sMainLooper");
        mainLooper.setAccessible(true);
        mainLooper.set(null, Looper.myLooper());
        Workarounds.apply();
        Context context = FakeContext.get();
        TelecomManager telecom = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
        TelephonyManager telephony = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        for (Object manager : new Object[] {telecom, telephony}) {
            Field field = manager.getClass().getDeclaredField("mContext");
            field.setAccessible(true);
            field.set(manager, context);
        }
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        String last = null;
        long end = SystemClock.elapsedRealtime() + seconds * 1000L;
        while (SystemClock.elapsedRealtime() < end) {
            String now = "mode=" + audio.getMode()
                    + " callState=" + ask(telephony::getCallState)
                    + " inCall=" + ask(telecom::isInCall)
                    + " inManagedCall=" + ask(telecom::isInManagedCall);
            if (!now.equals(last)) {
                System.out.println(System.currentTimeMillis() + " " + now);
                last = now;
            }
            SystemClock.sleep(250);
        }
        System.out.println("done");
    }

    private interface Question {
        Object ask() throws Exception;
    }

    private static String ask(Question q) {
        try {
            return String.valueOf(q.ask());
        } catch (Throwable t) {
            return "error(" + t + ")";
        }
    }
}
