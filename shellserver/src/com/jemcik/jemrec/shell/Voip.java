package com.jemcik.jemrec.shell;

import com.genymobile.scrcpy.FakeContext;
import com.genymobile.scrcpy.audio.AudioCapture;
import com.genymobile.scrcpy.audio.AudioCaptureException;
import com.genymobile.scrcpy.audio.AudioConfig;
import com.genymobile.scrcpy.util.Ln;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaCodec;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Messenger calls - WhatsApp, Viber, Telegram, Signal, any app that calls over
 * the internet - recorded by this process, to a file, both sides, the way phone
 * calls are. Only for the apps the user switched on (the app sends the list),
 * and nothing at all while the list is empty.
 *
 * THE OTHER PERSON: an audio policy copies every player using
 * USAGE_VOICE_COMMUNICATION into a stream this process reads, while the call
 * still plays in the user's ear (ROUTE_FLAG_LOOP_BACK_RENDER). Capturing that
 * usage needs CAPTURE_VOICE_COMMUNICATION_OUTPUT, which the shell holds, and
 * voiceCommunicationCaptureAllowed on the rule - set BEFORE build(); scrcpy's
 * playback capture sets it after, where it does nothing. The policy is
 * registered when the list stops being empty, long before any call, on the
 * belief that Android routes a stream only when the app creates it. Not so on
 * the Android 17 emulator (2026-10-27): a policy registered mid-call copied a
 * player already playing (AudioPolicyManager re-checks the players then,
 * checkSecondaryOutputs), and on a Galaxy S23 one registered at the answer
 * had the far side from the first half second. Armed early all the same: a
 * low-latency (MMAP) player opened before it would never be copied, and one
 * opened after it is refused that path. An app that marks its audio
 * ALLOW_CAPTURE_BY_NONE is not copied whatever this holds - Android
 * honouring the app, and nothing here tries to get round it.
 *
 * THE USER: the microphone, opened NOT privacy-sensitive, so it never takes
 * the input from the call app - a recorder that leaves the other person unable
 * to hear you is worse than none. As a guard on top, if Android ever reports
 * the call app's own capture silenced while this records, the microphone here
 * is dropped at once and the rest of the call has the other side only.
 *
 * Measured on an Honor BKQ-N49, MagicOS 11.0.0.120 / Android 17, 2026-10-20,
 * with Viber (first with a separate probe, then this), Telegram and WhatsApp:
 * both sides recorded clearly, both directions, and the other person heard
 * the user throughout every call.
 *
 * GOOGLE MEET is one of these too, though Android's call service carries its
 * calls and the call state reports them as phone calls: CallStates leaves
 * them here. On the same Honor, 2026-10-26, Meet played its calls as voice-call
 * audio (AAudio, flags 0) and this copy had the other side of two calls and a
 * meeting, while Meet's own microphone stayed unsilenced.
 *
 * WHEN IS IT A CALL: the audio mode at MODE_IN_COMMUNICATION, which every VoIP
 * app sets and TelephonyCallback reports for none of them but those whose
 * calls go through Telecom, Meet's (above) - AND an app on the list both
 * playing voice-call audio and capturing at once. Mode alone also comes from
 * a voice message played at the ear; capturing is what a call has and a
 * message does not. Its end is the mode leaving, confirmed after a moment,
 * because apps flip it while switching the route.
 */
final class Voip {

    interface Listener {
        void onVoipCallStart(String app);

        void onVoipCallEnd();
    }

    private Voip() {
    }

    private static final int SAMPLE_RATE = AudioConfig.SAMPLE_RATE;
    /** How often a call is looked for, and its end, while one may be up. */
    private static final long POLL_MS = 500L;
    /** The mode has to stay out of a call this long for the call to be over. */
    private static final long END_CONFIRM_MS = 1500L;

    /** The packages whose calls are recorded. Empty: nothing is watched. */
    private static volatile Set<String> apps = Collections.emptySet();
    private static @Nullable Policy policy;
    private static @Nullable Watch watch;
    /** What configure was last given, so a recording's end can disarm. */
    private static @Nullable Handler wiredHandler;
    private static @Nullable AudioManager wiredAudio;
    private static @Nullable Listener wiredListener;
    /** The capture of the call being recorded, for the guard. */
    private static volatile @Nullable Capture current;

    static Set<String> apps() {
        return apps;
    }

    static boolean armed() {
        return policy != null;
    }

    /** The switched-on app whose call the watch is following, or null. A
     *  call that began while a phone call was recorded is followed but not
     *  recorded; Main records it when the phone call ends. On the handler's
     *  thread, as the watch is. */
    static @Nullable String followedApp() {
        Watch w = watch;
        return w == null ? null : w.active;
    }

    /** "com.whatsapp,com.viber.voip" - the environment's form and the command's. */
    static Set<String> parse(String list) {
        Set<String> out = new LinkedHashSet<>();
        if (list != null) {
            for (String part : list.split(",")) {
                String app = part.trim();
                // Package names only: letters, digits, dots and underscores.
                if (app.matches("[A-Za-z0-9_.]{1,200}")) {
                    out.add(app);
                }
            }
        }
        return out;
    }

    /**
     * Take the list the app sent and act on it: arm the policy and start
     * watching when it gains its first app, disarm when it loses its last.
     * Never mid-recording: the copy feeding it would go with the policy.
     */
    static synchronized void configure(Set<String> newApps, Handler handler, AudioManager audio, Listener listener) {
        wiredHandler = handler;
        wiredAudio = audio;
        wiredListener = listener;
        apps = Collections.unmodifiableSet(new LinkedHashSet<>(newApps));
        Ln.i("voip: apps = " + (apps.isEmpty() ? "none" : String.join(",", apps)));
        if (!apps.isEmpty()) {
            if (policy == null) {
                try {
                    policy = Policy.register();
                } catch (Throwable t) {
                    Ln.e("voip: could not register the audio policy; messenger calls will not be recorded", t);
                }
            }
            if (watch == null && policy != null) {
                Watch started = new Watch(handler, audio, listener);
                watch = started;
                handler.post(started::start);
            }
        } else if (current == null) {
            if (watch != null) {
                Watch gone = watch;
                handler.removeCallbacks(gone);
                watch = null;
                // A messenger call it was following - one not being recorded,
                // or there would be a capture - still has to end, or the daemon
                // would go on believing it is in one and refuse every phone
                // call after it.
                handler.post(gone::stop);
            }
            if (policy != null) {
                policy.unregister();
                policy = null;
            }
        }
    }

    /** A recording just ended: if the list emptied while it ran, disarm now. */
    private static void recordingEnded() {
        Handler handler = wiredHandler;
        AudioManager audio = wiredAudio;
        Listener listener = wiredListener;
        if (handler != null && audio != null && listener != null && apps.isEmpty()) {
            handler.post(() -> configure(apps, handler, audio, listener));
        }
    }

    /** A capture for the call that just started, or null if not armed. */
    static @Nullable AudioCapture newCapture() {
        Policy p = policy;
        return p == null ? null : new Capture(p, false);
    }

    /**
     * A capture for a PHONE call that plays through the mixer (Checks.
     * phoneCallInMixer): the messenger capture, the copy armed for this call
     * if no messenger app has armed it, and disarmed when the recording ends
     * with none on the list (recordingEnded). Null when Android refuses the
     * copy; the call is then recorded as any other.
     */
    static synchronized @Nullable AudioCapture newPhoneCapture() {
        if (policy == null) {
            try {
                policy = Policy.register();
            } catch (Throwable t) {
                Ln.e("voip: could not copy the phone call's audio", t);
                return null;
            }
        }
        return new Capture(policy, true);
    }

    // --- the audio policy --------------------------------------------------------

    static final class Policy {
        private final Object policy;
        private final Object mix;
        private final Class<?> policyClass;
        private final Class<?> mixClass;

        private Policy(Object policy, Object mix, Class<?> policyClass, Class<?> mixClass) {
            this.policy = policy;
            this.mix = mix;
            this.policyClass = policyClass;
            this.mixClass = mixClass;
        }

        @SuppressLint("PrivateApi")
        static Policy register() throws Exception {
            Class<?> ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule");
            Class<?> ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
            Object ruleBuilder = ruleBuilderClass.getConstructor().newInstance();
            // Android 13 and later only; before it, the role follows from the
            // first rule - a usage rule makes a mix of players, which is what
            // this is. On Android 12 asking for the method threw before Android
            // was even asked.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ruleBuilderClass.getMethod("setTargetMixRole", int.class)
                        .invoke(ruleBuilder, ruleClass.getField("MIX_ROLE_PLAYERS").getInt(null));
            }
            AudioAttributes voice = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build();
            ruleBuilderClass.getMethod("addMixRule", int.class, Object.class)
                    .invoke(ruleBuilder, ruleClass.getField("RULE_MATCH_ATTRIBUTE_USAGE").getInt(null), voice);
            ruleBuilderClass.getMethod("voiceCommunicationCaptureAllowed", boolean.class).invoke(ruleBuilder, true);
            Object rule = ruleBuilderClass.getMethod("build").invoke(ruleBuilder);

            Class<?> mixClass = Class.forName("android.media.audiopolicy.AudioMix");
            Class<?> mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix$Builder");
            Object mixBuilder = mixBuilderClass.getConstructor(ruleClass).newInstance(rule);
            mixBuilderClass.getMethod("setFormat", AudioFormat.class).invoke(mixBuilder, AudioConfig.createAudioFormat());
            mixBuilderClass.getMethod("setRouteFlags", int.class)
                    .invoke(mixBuilder, mixClass.getField("ROUTE_FLAG_LOOP_BACK_RENDER").getInt(null));
            Object mix = mixBuilderClass.getMethod("build").invoke(mixBuilder);

            Class<?> policyClass = Class.forName("android.media.audiopolicy.AudioPolicy");
            Class<?> policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
            Object policyBuilder = policyBuilderClass.getConstructor(Context.class).newInstance(FakeContext.get());
            policyBuilderClass.getMethod("addMix", mixClass).invoke(policyBuilder, mix);
            Object policy = policyBuilderClass.getMethod("build").invoke(policyBuilder);

            Method register = AudioManager.class.getDeclaredMethod("registerAudioPolicyStatic", policyClass);
            register.setAccessible(true);
            int result = (int) register.invoke(null, policy);
            if (result != 0) {
                // Android's reason is in its own log (AudioService). Where the
                // shell lacks CAPTURE_VOICE_COMMUNICATION_OUTPUT - stock Android
                // before 14 - this is always the answer; the app checks for that
                // permission first (VoipApps.supported) and does not ask.
                throw new IllegalStateException("registerAudioPolicyStatic returned " + result
                        + " - Android refused to let the shell copy call audio");
            }
            Ln.i("voip: audio policy registered, copying voice-call playback");
            return new Policy(policy, mix, policyClass, mixClass);
        }

        AudioRecord sink() throws Exception {
            return (AudioRecord) policyClass.getMethod("createAudioRecordSink", mixClass).invoke(policy, mix);
        }

        void unregister() {
            try {
                Method m = AudioManager.class.getDeclaredMethod("unregisterAudioPolicyAsyncStatic", policyClass);
                m.setAccessible(true);
                m.invoke(null, policy);
                Ln.i("voip: audio policy unregistered");
            } catch (Throwable t) {
                Ln.w("voip: could not unregister the audio policy: " + t);
            }
        }
    }

    // --- spotting a call -------------------------------------------------------------

    /**
     * Spots a messenger call, and its end. Android says when the audio mode
     * changes, and only while it is "communication" - or a call is still being
     * followed - is anything polled. Asking for the mode twice a second, as
     * this did first, was a log line every time in system_server
     * ("AS.AudioService: getMode mode = 0") and another in this process on
     * MagicOS: on an Honor (2026-10-20) the daemon's own lines were gone from
     * the 256 KiB log buffer within a minute, before anyone could read why a
     * call went wrong. Polling stays the fallback where the callback cannot be
     * had. Everything here runs on [handler]'s thread.
     */
    static final class Watch implements Runnable {
        private final Handler handler;
        private final AudioManager audio;
        private final Listener listener;
        /** The mode as Android last said; read once at the start. */
        private int mode;
        /** Null when Android would not take it: then the mode is polled. */
        private @Nullable AudioManager.OnModeChangedListener modeChanges;
        /** A tick is posted, so a mode change does not start a second loop. */
        private boolean ticking;
        private @Nullable String active;
        /** The uid of [active]'s app, to ask whether it is still in its call. */
        private int activeUid = -1;
        /** The mode left "communication" while the app still talked; said once. */
        private boolean heldOver;
        private long leftAt;
        /** The app last reported as calling but not switched on, so it is
         *  said once per call, not twice a second. */
        private @Nullable String noted;

        Watch(Handler handler, AudioManager audio, Listener listener) {
            this.handler = handler;
            this.audio = audio;
            this.listener = listener;
        }

        void start() {
            try {
                AudioManager.OnModeChangedListener changes = changed -> {
                    mode = changed;
                    wake();
                };
                audio.addOnModeChangedListener(handler::post, changes);
                modeChanges = changes;
                Ln.i("voip: watching for a call (told of audio mode changes)");
            } catch (Throwable t) {
                Ln.w("voip: no audio mode callback; polling the mode instead: " + t);
            }
            // After registering, not before: a change in between would
            // otherwise never be heard, and nothing would poll for it.
            mode = audio.getMode();
            wake();
        }

        /** Stopped watching: the call is over as far as this goes. */
        void stop() {
            if (modeChanges != null) {
                try {
                    audio.removeOnModeChangedListener(modeChanges);
                } catch (Throwable t) {
                    Ln.w("voip: could not drop the audio mode callback: " + t);
                }
                modeChanges = null;
            }
            endNow();
        }

        private void wake() {
            if (!ticking && watch == this) {
                ticking = true;
                handler.post(this);
            }
        }

        /** Stopped watching mid-call: the call is over as far as this goes. */
        void endNow() {
            if (active != null) {
                Ln.i("voip: no longer watching; ending the call in " + active);
                active = null;
                activeUid = -1;
                heldOver = false;
                listener.onVoipCallEnd();
            }
        }

        @Override
        public void run() {
            ticking = false;
            try {
                tick();
            } catch (Throwable t) {
                Ln.w("voip: watch tick failed: " + t);
            }
            boolean callPossible = active != null || mode == AudioManager.MODE_IN_COMMUNICATION;
            if (watch == this && (modeChanges == null || callPossible)) {
                ticking = true;
                handler.postDelayed(this, POLL_MS);
            }
        }

        private void tick() {
            if (modeChanges == null) {
                mode = audio.getMode();
            }
            boolean inCommunication = mode == AudioManager.MODE_IN_COMMUNICATION;
            if (active == null) {
                if (!inCommunication) {
                    noted = null;
                    return;
                }
                String app = callingApp();
                if (app != null) {
                    active = app;
                    leftAt = 0;
                    Ln.i("voip: call in " + app);
                    listener.onVoipCallStart(app);
                }
                return;
            }
            if (inCommunication) {
                leftAt = 0;
                heldOver = false;
                guard(active);
                return;
            }
            boolean stillCalling = activeUid >= 0 && callingUids(audio, false).contains(activeUid);
            if (Checks.messengerCallGoesOn(mode, stillCalling)) {
                // A phone call ringing over it took the mode; the messenger
                // call itself goes on, and so does its recording.
                leftAt = 0;
                if (!heldOver) {
                    heldOver = true;
                    Ln.i("voip: the audio mode left the call (" + mode + ") but " + active
                            + " still plays and captures call audio - recording on");
                }
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (leftAt == 0) {
                leftAt = now;
            } else if (now - leftAt >= END_CONFIRM_MS) {
                Ln.i("voip: call in " + active + " ended (audio mode " + mode + ")");
                active = null;
                activeUid = -1;
                heldOver = false;
                leftAt = 0;
                listener.onVoipCallEnd();
            }
        }

        /** An app on the list that is playing voice-call audio and capturing:
         *  a call, not a voice message. */
        private @Nullable String callingApp() {
            for (int uid : callingUids(audio, false)) {
                String[] packages = FakeContext.get().getPackageManager().getPackagesForUid(uid);
                if (packages == null) {
                    continue;
                }
                for (String pkg : packages) {
                    if (apps.contains(pkg)) {
                        activeUid = uid;
                        return pkg;
                    }
                }
                String who = String.join(",", packages);
                if (!who.equals(noted)) {
                    noted = who;
                    Ln.i("voip: a call in " + who + ", which is not switched on");
                }
            }
            return null;
        }

        /** The call app's own capture silenced while we record: give the
         *  microphone back at once. */
        private void guard(String app) {
            Capture capture = current;
            if (capture == null || !capture.hasMic()) {
                return;
            }
            Integer silenced = silencedCapture(audio, uid -> {
                String[] packages = FakeContext.get().getPackageManager().getPackagesForUid(uid);
                return packages != null && java.util.Arrays.asList(packages).contains(app);
            });
            if (silenced != null) {
                Ln.w("voip: " + app + "'s microphone was silenced while recording - dropping ours");
                capture.dropMic();
            }
        }
    }

    /**
     * The apps in a call right now - any app, switched on here or not: each
     * playing voice-call audio and capturing at once, which a call does and a
     * voice message does not. This process too only when [withThisProcess].
     */
    static Set<Integer> callingUids(AudioManager audio, boolean withThisProcess) {
        Set<Integer> playing = new HashSet<>();
        for (AudioPlaybackConfiguration p : audio.getActivePlaybackConfigurations()) {
            if (p.getAudioAttributes().getUsage() == AudioAttributes.USAGE_VOICE_COMMUNICATION) {
                Integer uid = uidOf(p);
                if (uid != null) {
                    playing.add(uid);
                }
            }
        }
        Set<Integer> calling = new HashSet<>();
        for (AudioRecordingConfiguration r : audio.getActiveRecordingConfigurations()) {
            Integer uid = uidOf(r);
            if (uid != null && (withThisProcess || uid != android.os.Process.myUid()) && playing.contains(uid)) {
                calling.add(uid);
            }
        }
        return calling;
    }

    /**
     * Whether anyone is in a call - playing voice-call audio and capturing at
     * once, this process too - for the answering machine, which must never
     * answer a phone call ringing over one (Main's otherAppCallUp). The rule
     * is Checks.someoneInCall; this reads the phone into it, -1 for an owner
     * Android does not name.
     */
    static boolean someoneInCall(AudioManager audio) {
        java.util.List<Integer> players = new java.util.ArrayList<>();
        for (AudioPlaybackConfiguration p : audio.getActivePlaybackConfigurations()) {
            if (p.getAudioAttributes().getUsage() == AudioAttributes.USAGE_VOICE_COMMUNICATION) {
                Integer uid = uidOf(p);
                players.add(uid == null ? -1 : uid);
            }
        }
        java.util.List<AudioRecordingConfiguration> records = audio.getActiveRecordingConfigurations();
        int[] captureUids = new int[records.size()];
        int[] captureSources = new int[records.size()];
        for (int i = 0; i < records.size(); i++) {
            Integer uid = uidOf(records.get(i));
            captureUids[i] = uid == null ? -1 : uid;
            captureSources[i] = records.get(i).getClientAudioSource();
        }
        return Checks.someoneInCall(players.stream().mapToInt(Integer::intValue).toArray(), captureUids,
                captureSources);
    }

    // getClientUid is a system API: by reflection, or null.
    /** The uid of an active capture Android has silenced - not this
     *  process's - that [which] picks; null for none. The guards' one look:
     *  a call's own microphone silenced while we record means ours goes. */
    private static @Nullable Integer silencedCapture(AudioManager audio, java.util.function.IntPredicate which) {
        for (AudioRecordingConfiguration r : audio.getActiveRecordingConfigurations()) {
            Integer uid = uidOf(r);
            if (uid != null && uid != android.os.Process.myUid() && r.isClientSilenced() && which.test(uid)) {
                return uid;
            }
        }
        return null;
    }

    static @Nullable Integer uidOf(Object config) {
        try {
            return (Integer) config.getClass().getMethod("getClientUid").invoke(config);
        } catch (Throwable t) {
            return null;
        }
    }

    // --- the capture -----------------------------------------------------------------

    /**
     * Both sides as one stereo stream: the user on the left, the other person
     * on the right (Checks.stereo says why).
     *
     * The microphone is the clock. Each read takes the next block of it and
     * whatever the copy of the call has delivered: silence where the copy is
     * short (the app paused its player, a route change), and the oldest of it
     * dropped if it ever runs too far ahead, so two clocks that drift apart
     * over a long call cannot pull the sides out of step by more than that.
     * Without a microphone - dropped by the guard, or never started - a timer
     * paces the reads instead, so the encoder never stalls.
     */
    static final class Capture implements AudioCapture {
        private static final int FRAMES = 960;              // 20 ms
        private static final int MAX_AHEAD = SAMPLE_RATE / 5; // 200 ms

        /** Reads between two looks for the phone call's own microphone
         *  silenced: about a second (phoneGuard). */
        private static final int GUARD_READS = 50;

        private final Policy policy;
        /** A phone call's recording, not a messenger call's (newPhoneCapture). */
        private final boolean phone;
        private int reads;
        /** For phoneGuard: looked up once, at start. */
        private @Nullable AudioManager audio;
        private @Nullable AudioRecord far;
        private volatile @Nullable AudioRecord near;
        private @Nullable Pump farPump;
        private @Nullable Pump nearPump;
        private volatile boolean running;
        private long framesOut;
        private long startUs;
        /** One read's worth of each side, reused: fifty reads a second. */
        private final short[] mine = new short[FRAMES];
        private final short[] theirs = new short[FRAMES * 2];

        Capture(Policy policy, boolean phone) {
            this.policy = policy;
            this.phone = phone;
        }

        boolean hasMic() {
            return near != null;
        }

        @Override
        public void checkCompatibility() throws AudioCaptureException {
            if (policy == null) {
                throw new AudioCaptureException();
            }
        }

        @Override
        public void start() throws AudioCaptureException {
            try {
                far = policy.sink();
                far.startRecording();
            } catch (Throwable t) {
                Ln.e("voip: could not read the call's audio", t);
                throw new AudioCaptureException();
            }
            farPump = new Pump("far", far, 2);
            farPump.start();
            try {
                AudioRecord mic = mic();
                mic.startRecording();
                near = mic;
                nearPump = new Pump("near", mic, 1);
                nearPump.start();
            } catch (Throwable t) {
                // The other side alone is still worth having.
                Ln.w("voip: no microphone, recording the other side only: " + t);
            }
            if (phone) {
                audio = (AudioManager) FakeContext.get().getSystemService(Context.AUDIO_SERVICE);
            }
            running = true;
            // The monotonic clock, as every other capture stamps with
            // (AudioRecordReader, AudioEncoder.fixTimestamp) and the app
            // places packets by (PacketClock): elapsedRealtime, which this
            // used, runs on through the phone's sleep, and would put the
            // first packet of a phone call recorded here that far past its
            // place - found reading the code (2026-10-24), not seen.
            startUs = System.nanoTime() / 1000;
            current = this;
            Ln.i("voip: recording both sides (" + (near != null ? "mic on" : "mic off") + ")");
        }

        @SuppressLint({"WrongConstant", "MissingPermission"})
        private static AudioRecord mic() {
            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build();
            return new AudioRecord.Builder()
                    .setContext(FakeContext.get())
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(format)
                    .setPrivacySensitive(false)
                    .setBufferSizeInBytes(8 * AudioRecord.getMinBufferSize(
                            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))
                    .build();
        }

        /** The guard's call: the call app needs the microphone more. */
        void dropMic() {
            AudioRecord mic = near;
            near = null;
            if (mic != null && nearPump != null) {
                nearPump.close();
            }
        }

        @Override
        public int read(ByteBuffer out, MediaCodec.BufferInfo info) {
            if (!running) {
                return -1;
            }
            Pump np = nearPump;
            boolean fromMic = near != null && np != null && np.take(mine, FRAMES, true);
            if (!running) {
                return -1;
            }
            if (!fromMic) {
                // No microphone - never started, or dropped by the guard while
                // this read waited on it. The call goes on with the other
                // side only, paced by the clock: failing the read here would
                // end the whole recording, theirs included.
                java.util.Arrays.fill(mine, (short) 0);
                long due = startUs + (framesOut + FRAMES) * 1_000_000L / SAMPLE_RATE;
                long wait = due - System.nanoTime() / 1000;
                if (wait > 0) {
                    SystemClock.sleep(wait / 1000 + 1);
                }
            }
            Pump fp = farPump;
            if (fp == null) {
                return -1; // start() sets it before running, or throws
            }
            fp.dropBeyond(MAX_AHEAD * 2);
            fp.take(theirs, FRAMES * 2, false);
            Checks.stereo(mine, theirs, FRAMES, out);
            int bytes = FRAMES * AudioConfig.CHANNELS * AudioConfig.BYTES_PER_SAMPLE;
            long pts = startUs + framesOut * 1_000_000L / SAMPLE_RATE;
            framesOut += FRAMES;
            info.set(0, bytes, pts, 0);
            if (phone && ++reads % GUARD_READS == 0) {
                phoneGuard();
            }
            return bytes;
        }

        /**
         * A phone call's own microphone silenced while we record: give ours
         * back at once. A messenger's is an app on the list (Watch.guard); a
         * phone call's is the call stack's - uid 1000 on the Galaxy S23 - so
         * any system capture silenced counts, and no app's. Not seen: the
         * call's capture outranks ours, and the S23's ran unsilenced through
         * a whole call recorded this way (2026-10-24). But a recording must
         * never cost the call its voice.
         */
        private void phoneGuard() {
            if (near == null) {
                return;
            }
            AudioManager am = audio;
            try {
                if (am == null) {
                    throw new IllegalStateException("no audio service");
                }
                Integer uid = silencedCapture(am, u -> u < android.os.Process.FIRST_APPLICATION_UID);
                if (uid != null) {
                    Ln.w("recording: the call's microphone (uid " + uid + ") was silenced while recording - dropping ours");
                    dropMic();
                }
            } catch (Throwable t) {
                // Unable to look: the microphone goes, as the safe answer.
                Ln.w("recording: could not check the call's microphone, dropping ours: " + t);
                dropMic();
            }
        }

        @Override
        public void stop() {
            running = false;
            if (current == this) {
                current = null;
            }
            dropMic();
            if (farPump != null) {
                farPump.close();
            }
            recordingEnded();
        }
    }

    /**
     * One AudioRecord drained on its own thread into a bounded queue of
     * samples, so neither side's reads ever wait on the other's.
     */
    static final class Pump implements Runnable {
        private final String name;
        private final AudioRecord record;
        private final Thread thread;
        private final short[] ring;
        private int head;
        private int size;
        private volatile boolean running = true;

        Pump(String name, AudioRecord record, int channels) {
            this.name = name;
            this.record = record;
            this.ring = new short[SAMPLE_RATE * channels * 2]; // two seconds
            this.thread = new Thread(this, "jemrec-voip-" + name);
        }

        void start() {
            thread.start();
        }

        void halt() {
            running = false;
            synchronized (this) {
                notifyAll();
            }
        }

        /**
         * In this order: stop reading, stop the record (which wakes a read
         * blocked in it), wait for the thread to leave read(), and only then
         * release. Releasing an AudioRecord another thread is still reading
         * frees it under that read.
         */
        void close() {
            halt();
            try {
                record.stop();
            } catch (Throwable ignored) {
                // already stopped
            }
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (thread.isAlive()) {
                Ln.w("voip: " + name + " reader did not stop; leaving the record unreleased");
                return;
            }
            record.release();
        }

        @Override
        public void run() {
            short[] buf = new short[SAMPLE_RATE / 50 * 2];
            while (running) {
                int n = record.read(buf, 0, buf.length);
                if (n < 0) {
                    Ln.w("voip: " + name + " read error " + n);
                    break;
                }
                if (n > 0) {
                    put(buf, n);
                }
            }
            // However it ended, say so: a read waiting on this side must not
            // wait for samples that will never come.
            halt();
        }

        private synchronized void put(short[] src, int n) {
            for (int i = 0; i < n; i++) {
                if (size == ring.length) {
                    // Full: the oldest goes, nothing blocks the reader.
                    head = (head + 1) % ring.length;
                    size--;
                }
                ring[(head + size) % ring.length] = src[i];
                size++;
            }
            notifyAll();
        }

        /** Fill dst with n samples. Waiting for them if [wait]; otherwise what
         *  there is, and silence for the rest. False if it stopped first. */
        synchronized boolean take(short[] dst, int n, boolean wait) {
            if (wait) {
                while (size < n && running) {
                    try {
                        wait(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                if (size < n) {
                    return false;
                }
            }
            int got = Math.min(n, size);
            for (int i = 0; i < got; i++) {
                dst[i] = ring[head];
                head = (head + 1) % ring.length;
            }
            size -= got;
            for (int i = got; i < n; i++) {
                dst[i] = 0;
            }
            return true;
        }

        /** Keep at most [max] samples waiting: the rest is the past. */
        synchronized void dropBeyond(int max) {
            if (size > max) {
                int drop = size - max;
                head = (head + drop) % ring.length;
                size = max;
            }
        }
    }
}
