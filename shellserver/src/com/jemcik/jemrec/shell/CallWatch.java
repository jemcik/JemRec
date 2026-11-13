package com.jemcik.jemrec.shell;

import com.genymobile.scrcpy.FakeContext;
import com.genymobile.scrcpy.util.Ln;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.telecom.TelecomManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The phone's call state as this process hears it, fed to CallStates; and the
 * start of both watches, the phone's and the messenger apps'.
 */
final class CallWatch extends TelephonyCallback
        implements TelephonyCallback.CallStateListener {

    /**
     * Watch call state from HERE, so the app does not have to stay alive to.
     *
     * WHY THE APP CANNOT SIMPLY BE WOKEN BY A BROADCAST
     *
     * The obvious design is a manifest receiver for PHONE_STATE. On this phone
     * it does not work: Honor's iAware drops that broadcast to any app whose
     * process is not already running, measured twice, and "not already
     * running" is precisely the state a dormant app is in when a call arrives.
     * The first answer to that was a permanent foreground service in the app -
     * alive at all times so the broadcast has somewhere to land - with the
     * permanent notification Android requires for one.
     *
     * That was the heaviest tool reached for first. This process already runs
     * permanently, is invisible, and runs as shell - which holds
     * READ_PRIVILEGED_PHONE_STATE and can register for call state directly. So
     * the daemon watches the phone, and when a call starts it wakes the app's
     * recording service with `am start-foreground-service`: something shell is
     * allowed to do from anywhere, and not a broadcast, so nothing filters it.
     * The app then holds a foreground service, and shows a notification, for
     * exactly the length of the call.
     *
     * THE TOKEN
     *
     * Waking the service means exporting it, and an exported service that
     * starts recording phone calls must not be startable by any app on the
     * phone. The app generates a random token, hands it to this process in its
     * environment at launch, and refuses the intent without it. The environment
     * of a shell-uid process is readable only by shell and root.
     */
    static void startCallWatcher(String token) {
        if (token == null || token.isEmpty()) {
            Ln.w("callwatch: no token in the environment, not watching calls");
            return;
        }
        try {
            // TelephonyManager reaches the phone service through a static
            // "service registerer" that a normal app process is handed during
            // startup and this process never is - it was started from a shell,
            // not by the system. Left unset it is null and registration dies
            // in getITelephony() with an NPE (measured). The class that holds
            // it is hidden, so it is set the way scrcpy sets every other piece
            // of app-process furniture this daemon lacks: by reflection.
            Class<?> initializer = Class.forName("android.telephony.TelephonyFrameworkInitializer");
            Class<?> managerClass = Class.forName("android.os.TelephonyServiceManager");
            Object manager = managerClass.getDeclaredConstructor().newInstance();
            initializer.getDeclaredMethod("setTelephonyServiceManager", managerClass)
                    .invoke(null, manager);

            // The manager the system hands back is bound to the system
            // context, whose package is "android" - and telephony checks the
            // calling package belongs to the calling uid, so it throws
            // "Package android does not belong to 2000" (measured, the second
            // wall this hit). Its mContext is repointed at the FakeContext,
            // whose package is com.android.shell and DOES belong to shell -
            // the identical trick FakeContext already plays for the clipboard,
            // and better than the hidden TelephonyManager(Context) constructor,
            // which is not in the platform jar this compiles against.
            Context context = FakeContext.get();
            TelephonyManager telephony =
                    (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            try {
                Field mContext = TelephonyManager.class.getDeclaredField("mContext");
                mContext.setAccessible(true);
                mContext.set(telephony, context);
            } catch (ReflectiveOperationException e) {
                Ln.w("callwatch: could not rebind telephony context: " + e.getMessage());
            }
            AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            // Handed out with no context at all: setMicrophoneMute reads the
            // caller's package from it, as setMode does - which died of an NPE
            // in the answering-machine probe (the Honor, 2026-10-22). getMode
            // swallows the same NPE, which is why the call watch never
            // noticed. Only the answering machine mutes, so a phone where this
            // fails loses that, not the watch.
            try {
                for (String name : new String[]{"mOriginalContext", "mApplicationContext"}) {
                    Field field = AudioManager.class.getDeclaredField(name);
                    field.setAccessible(true);
                    field.set(audio, context);
                }
                Answering.muteReady = true;
            } catch (ReflectiveOperationException | RuntimeException e) {
                Ln.w("answer: could not rebind the audio context, so no mute and no answering: " + e);
            }
            Handler handler = new Handler(Looper.getMainLooper());
            Platform.mainHandler = handler;
            Platform.callAudio = audio;
            Platform.phone = telephony;
            TelecomManager telecomManager = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
            try {
                Field mContext = TelecomManager.class.getDeclaredField("mContext");
                mContext.setAccessible(true);
                mContext.set(telecomManager, context);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // The answering machine's, not the watch's: whatever this
                // throws must not stop the call watch below from registering.
                Ln.w("answer: could not rebind the telecom context: " + e);
            }
            Platform.telecom = telecomManager;
            Answering.answerMachine = new AnswerMachine(new AnswerEffects(handler, audio, telecomManager, telephony));
            AnswerSettings settings = Answering.answerSettings;
            Answering.answerMachine.configure(settings.on, settings.maxMessageSeconds * 1000L);
            Ln.i("answer: " + (settings.on
                    ? "on, " + (settings.maxMessageSeconds == AnswerSettings.NO_LIMIT ? "no limit" : "up to " + settings.maxMessageSeconds + " s")
                        + ", route " + settings.route
                    : "off"));
            Answering.giveBackStaleMute();
            // Into a field, never inline: the framework only holds it weakly.
            // See callWatch.
            callWatch = new CallWatch(audio, handler);
            telephony.registerTelephonyCallback(handler::post, callWatch);
            watching = true;
            Ln.i("callwatch: watching call state as uid " + android.os.Process.myUid());
        } catch (Throwable t) {
            Ln.e("callwatch: could not register for call state", t);
        }
    }

    /**
     * THE CALL WATCH, HELD FOR THE LIFE OF THE PROCESS.
     *
     * The framework does not keep a TelephonyCallback alive. The binder stub it
     * registers refers to the callback through a WeakReference
     * (TelephonyCallback.IPhoneStateListenerStub), and once that is cleared
     * every call state change is dropped inside the stub - silently, before any
     * code here runs. This used to be registered inline with nothing else
     * referring to it, so the first garbage collection that found it unpinned
     * took it, and the daemon went deaf: answering pings, passing the
     * self-test, logging heartbeats, and never seeing another call. The
     * telephony registry still lists the listener, so nothing on the system
     * side shows it either.
     *
     * Reported as "only the first call is recorded" (Honor 90, MagicOS 9), and
     * measured on 2026-10-09. On an Android 15 emulator the daemon's first GC
     * came nine minutes after spawn, unprompted, and the next call was missed.
     * Forcing one with `kill -USR1` (ART answers it with a full GC) did the same
     * on an Honor running Android 17, with real calls. Held here, the watch
     * survived every forced GC and heard every call.
     */
    static @Nullable CallWatch callWatch;

    /**
     * WHAT THE CALL WATCH HAS HEARD, FOR THE APP'S SELF-TEST. See COMMAND_WATCH.
     *
     * A deaf watch is otherwise invisible: the daemon answers pings and the
     * self-test's live capture works, because neither involves the watch. These
     * let the app ask whether a call the phone logged ever reached it.
     */
    static volatile boolean watching;
    static final AtomicInteger callEvents = new AtomicInteger();
    static final AtomicInteger offHooks = new AtomicInteger();
    static volatile long lastCallEventAt;
    static volatile long lastOffHookAt;

    /** For Voip.configure from a session thread: set once the watcher starts. */
    private static @Nullable Handler voipHandler;
    private static @Nullable AudioManager voipAudio;

    private static final Voip.Listener VOIP_LISTENER = new Voip.Listener() {
        @Override
        public void onVoipCallStart(String app) {
            Main.onAppCallStart(app);
        }

        @Override
        public void onVoipCallEnd() {
            Main.onVoipCallEnd();
        }
    };

    /** Messenger calls: armed from the environment, if it names any apps. */
    static void startVoipWatcher(String token) {
        if (token == null || token.isEmpty()) {
            return;
        }
        voipHandler = new Handler(Looper.getMainLooper());
        voipAudio = (AudioManager) FakeContext.get().getSystemService(Context.AUDIO_SERVICE);
        configureVoip(Voip.parse(System.getenv(Main.VOIP_ENV)));
    }

    static void configureVoip(Set<String> apps) {
        if (voipHandler == null || voipAudio == null) {
            Ln.w("voip: no watcher (no token), ignoring the app list");
            return;
        }
        Voip.configure(apps, voipHandler, voipAudio, VOIP_LISTENER);
    }

    private final AudioManager audio;
    /** What the events mean - see CallStates; this class only feeds it. */
    private final CallStates states;

    boolean phoneCallUp() {
        return states.inCall();
    }

    boolean phoneCallRang() {
        return states.rang();
    }

    @Nullable Boolean appCallIncoming() {
        return states.appCallIncoming();
    }
    private final Runnable endCheck;

    CallWatch(AudioManager audio, Handler handler) {
        this.audio = audio;
        this.states = new CallStates(new CallStates.Effects() {
            @Override
            public void callStarted(boolean incoming) {
                Main.onCallStart(incoming);
            }

            @Override
            public void callEnded() {
                Main.onCallEnd();
            }

            @Override
            public void scheduleEndCheck(long delayMs) {
                handler.postDelayed(endCheck, delayMs);
            }

            @Override
            public void cancelEndCheck() {
                handler.removeCallbacks(endCheck);
            }

            @Override
            public boolean audioInCall() {
                return CallWatch.this.audioInCall();
            }

            @Override
            public @Nullable Boolean phoneCallInTelecom() {
                return Platform.phoneCallInTelecom();
            }

            @Override
            public void callGoesOn() {
                // The answering machine stopped listening at that idle.
                AnswerMachine machine = Answering.answerMachine;
                if (machine != null) {
                    machine.callGoesOn();
                }
            }

            @Override
            public void log(String message) {
                Ln.i(message);
            }
        });
        this.endCheck = states::endCheck;
        // A phone call that ends into another app's call never gets its
        // idle; the audio mode changing says (CallStates.audioModeChanged).
        // AudioManager holds its listeners itself, unlike telephony.
        try {
            audio.addOnModeChangedListener(handler::post, mode -> states.audioModeChanged());
        } catch (Throwable t) {
            Ln.w("callwatch: no audio mode callback, so a phone call that ends into an app's call "
                    + "records on until that one ends: " + t);
        }
    }

    @Override
    public void onCallStateChanged(int state) {
        // Before anything else, so the self-test sees every event that
        // arrived, whatever came of it.
        callEvents.incrementAndGet();
        lastCallEventAt = System.currentTimeMillis();
        AnswerMachine machine = Answering.answerMachine;
        switch (state) {
            case TelephonyManager.CALL_STATE_RINGING:
                // Meet's ring is not the answering machine's either: it
                // could not play a greeting into an app's call.
                if (states.appsRinging()) {
                    break;
                }
                states.ringing();
                if (machine != null) {
                    machine.ringing();
                }
                break;
            case TelephonyManager.CALL_STATE_OFFHOOK:
                offHooks.incrementAndGet();
                lastOffHookAt = System.currentTimeMillis();
                if (states.appsOffHook()) {
                    break;
                }
                // First: the call start that follows asks whether this
                // machine answered it.
                if (machine != null) {
                    machine.offHook(states.rang());
                }
                states.offHook();
                break;
            case TelephonyManager.CALL_STATE_IDLE:
                if (machine != null) {
                    machine.idleSignal();
                }
                states.idle();
                break;
            default:
                break;
        }
    }

    /**
     * Whether telecom still holds the call's audio mode. This is the
     * reliable "is a call up" signal on this device; getCallState() is not.
     * Logged at each poll so a premature or late stop can be read straight
     * from the daemon log.
     */
    private boolean audioInCall() {
        int mode;
        try {
            mode = audio.getMode();
        } catch (Throwable t) {
            Ln.w("callwatch: could not read the audio mode; treating the call as over");
            return false;
        }
        boolean up = mode == AudioManager.MODE_IN_CALL
                || mode == AudioManager.MODE_IN_COMMUNICATION;
        Ln.i("callwatch: idle seen, audio mode=" + mode + (up ? " (still in a call)" : " (call is over)"));
        return up;
    }
}
