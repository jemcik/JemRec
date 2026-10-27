package com.jemcik.jemrec.shell;

import com.genymobile.scrcpy.FakeContext;
import com.genymobile.scrcpy.util.Ln;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioRecordingConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * What is playing and capturing on the phone right now, read into a call's
 * report (CallReport.scene): Android's own lists of active players and
 * captures. Whose uid each is, what kind of player and every attribute flag
 * are hidden from apps, and read by reflection as the probes read them; the
 * shell is let see them. What a call's copy can take is in there: a player's
 * use and capture policy (the copy takes voice-call audio, Voip.Policy's one
 * rule, unless its app marked it for nothing to capture).
 */
final class AudioScene {

    private AudioScene() {
    }

    static void lookInto(CallReport report, AudioManager audio) {
        try {
            int me = android.os.Process.myUid();
            List<String> playing = new ArrayList<>();
            for (AudioPlaybackConfiguration p : audio.getActivePlaybackConfigurations()) {
                AudioAttributes a = p.getAudioAttributes();
                int uid = orMinusOne(Voip.uidOf(p));
                playing.add(CallReport.player(uid, who(uid, me), a.getUsage(), a.getContentType(),
                        a.getAllowedCapturePolicy(), hiddenInt(p, "getPlayerType"), hiddenInt(a, "getAllFlags"),
                        deviceOf(p)));
            }
            List<String> capturing = new ArrayList<>();
            for (AudioRecordingConfiguration r : audio.getActiveRecordingConfigurations()) {
                int uid = orMinusOne(Voip.uidOf(r));
                AudioDeviceInfo device = r.getAudioDevice();
                capturing.add(CallReport.capture(uid, who(uid, me), r.getClientAudioSource(), r.isClientSilenced(),
                        device == null ? -1 : device.getType()));
            }
            report.scene(audio.getMode(), Voip.armed(), playing, capturing);
        } catch (Throwable t) {
            // A look that fails must not cost the recording; the report says so.
            report.note("could not look at what was playing: " + t);
            Ln.w("report: could not look at what was playing: " + t);
        }
    }

    private static String who(int uid, int me) {
        String[] packages = null;
        // An app's: a system uid is named by CallReport.who, and uid 1000
        // alone shares a hundred packages.
        if (uid >= android.os.Process.FIRST_APPLICATION_UID) {
            try {
                packages = FakeContext.get().getPackageManager().getPackagesForUid(uid);
            } catch (Throwable ignored) {
                // Named by its uid alone.
            }
        }
        return CallReport.who(uid, me, packages == null ? new String[0] : packages);
    }

    /**
     * The type of the device a player plays to, -1 for none named. Android 16
     * lists them (getAudioDeviceInfos, a hidden system call) and deprecates
     * the single one, as "never populated"; before 16 the single one is all
     * there is, and it named the device of every player that had one on the
     * Honor and the emulators (2026-10-27). So the list where it names one,
     * else the single one - both by reflection, as the other hidden parts.
     */
    private static int deviceOf(AudioPlaybackConfiguration player) {
        try {
            Object list = player.getClass().getMethod("getAudioDeviceInfos").invoke(player);
            if (list instanceof List && !((List<?>) list).isEmpty()
                    && ((List<?>) list).get(0) instanceof AudioDeviceInfo) {
                return ((AudioDeviceInfo) ((List<?>) list).get(0)).getType();
            }
        } catch (Throwable ignored) {
            // Before Android 16, or not allowed: the single one, below.
        }
        try {
            Object one = player.getClass().getMethod("getAudioDeviceInfo").invoke(player);
            return one instanceof AudioDeviceInfo ? ((AudioDeviceInfo) one).getType() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int orMinusOne(@Nullable Integer value) {
        return value == null ? -1 : value;
    }

    /** A hidden int getter's answer, or -1 where it has none. */
    private static int hiddenInt(Object on, String getter) {
        try {
            Object value = on.getClass().getMethod(getter).invoke(on);
            return value instanceof Integer ? (Integer) value : -1;
        } catch (Throwable t) {
            return -1;
        }
    }
}
