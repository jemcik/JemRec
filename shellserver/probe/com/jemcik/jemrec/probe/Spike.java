package com.jemcik.jemrec.probe;

import com.genymobile.scrcpy.FakeContext;
import com.genymobile.scrcpy.Workarounds;

import android.app.NotificationManager;
import android.content.Context;
import android.net.Uri;
import android.os.Looper;
import android.telephony.TelephonyManager;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Answering-machine spike, run by hand as the shell user. NOT shipped.
 *
 *   CLASSPATH=/data/local/tmp/voip-probe.jar app_process / com.jemcik.jemrec.probe.Spike cf
 *   ... Spike dnd <number>
 *   ... Spike setcf <seconds>     re-registers no-reply forwarding to the SAME number
 *
 * cf reads the carrier's call forwarding (no reply, busy, not reachable,
 * unconditional) through TelephonyManager.getCallForwarding, a SystemApi under
 * READ_PRIVILEGED_PHONE_STATE. dnd asks NotificationManager.matchesCallFilter
 * whether Do Not Disturb would let a call from that number ring. setcf changes
 * only the no-reply timer, keeping the number the carrier already has.
 */
public final class Spike {

    private static final String[] REASONS = {"unconditional", "busy", "no reply", "not reachable"};

    private Spike() {
    }

    public static void main(String[] args) throws Exception {
        Looper.prepare();
        Field mainLooper = Looper.class.getDeclaredField("sMainLooper");
        mainLooper.setAccessible(true);
        mainLooper.set(null, Looper.myLooper());
        Workarounds.apply();
        Context context = FakeContext.get();
        switch (args.length > 0 ? args[0] : "") {
            case "cf":
                TelephonyManager telephony = telephony(context);
                for (int reason = 0; reason < REASONS.length; reason++) {
                    log(REASONS[reason] + ": " + describe(read(telephony, reason)));
                }
                break;
            case "dnd":
                NotificationManager notifications =
                        (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                rebind(notifications, NotificationManager.class, context);
                log("interruption filter: " + notifications.getCurrentInterruptionFilter()
                        + " (1 = all, 2 = priority, 3 = none, 4 = alarms)");
                log("matchesCallFilter(" + tail(args[1]) + "): "
                        + notifications.matchesCallFilter(Uri.fromParts("tel", args[1], null)));
                break;
            case "setcf":
                setNoReply(telephony(context), Integer.parseInt(args[1]));
                break;
            case "cfon":
                write(telephony(context), true, args[1], Integer.parseInt(args[2]));
                break;
            case "cfoff":
                write(telephony(context), false, args[1], 0);
                break;
            default:
                System.err.println("usage: Spike cf | dnd <number> | setcf <seconds> | cfon <number> <seconds> | cfoff <number>");
                System.exit(2);
        }
        System.exit(0);
    }

    private static final int REASON_NO_REPLY = 2;

    private static void setNoReply(TelephonyManager telephony, int seconds) throws Exception {
        Object now = read(telephony, REASON_NO_REPLY);
        if (!isInfo(now) || !(boolean) call(now, "isEnabled")) {
            log("setcf: no-reply forwarding is not on (" + describe(now) + "); changing nothing");
            return;
        }
        String number = (String) call(now, "getNumber");
        Class<?> infoClass = Class.forName("android.telephony.CallForwardingInfo");
        Constructor<?> make = infoClass.getConstructor(boolean.class, int.class, String.class, int.class);
        Object wanted = make.newInstance(true, REASON_NO_REPLY, number, seconds);
        log("setcf: " + call(now, "getTimeoutSeconds") + " s -> " + seconds + " s, same number *" + tail(number));
        CountDownLatch done = new CountDownLatch(1);
        Consumer<Integer> result = code -> {
            log("setcf: result " + code + " (0 = success)");
            done.countDown();
        };
        TelephonyManager.class.getMethod("setCallForwarding", infoClass, Executor.class, Consumer.class)
                .invoke(telephony, wanted, Executors.newSingleThreadExecutor(), result);
        if (!done.await(30, TimeUnit.SECONDS)) {
            log("setcf: no answer in 30 s");
        }
        log("setcf: now " + describe(read(telephony, REASON_NO_REPLY)));
    }

    /** Turns no-reply forwarding on (to that number, after those seconds) or off. */
    private static void write(TelephonyManager telephony, boolean on, String number, int seconds) throws Exception {
        log("before: " + describe(read(telephony, REASON_NO_REPLY)));
        Class<?> infoClass = Class.forName("android.telephony.CallForwardingInfo");
        Object wanted = infoClass.getConstructor(boolean.class, int.class, String.class, int.class)
                .newInstance(on, REASON_NO_REPLY, number, seconds);
        CountDownLatch done = new CountDownLatch(1);
        Consumer<Integer> result = code -> {
            log((on ? "on" : "off") + ": result " + code + " (0 = success)");
            done.countDown();
        };
        TelephonyManager.class.getMethod("setCallForwarding", infoClass, Executor.class, Consumer.class)
                .invoke(telephony, wanted, Executors.newSingleThreadExecutor(), result);
        if (!done.await(30, TimeUnit.SECONDS)) {
            log("no answer in 30 s");
        }
        log("after: " + describe(read(telephony, REASON_NO_REPLY)));
    }

    /** The carrier's answer for one reason: a CallForwardingInfo, or an Integer error. */
    private static Object read(TelephonyManager telephony, int reason) throws Exception {
        Object[] answer = new Object[1];
        CountDownLatch done = new CountDownLatch(1);
        Class<?> callbackClass = Class.forName("android.telephony.TelephonyManager$CallForwardingInfoCallback");
        Object callback = Proxy.newProxyInstance(callbackClass.getClassLoader(), new Class<?>[] {callbackClass},
                (proxy, method, args) -> {
                    if (method.getName().equals("onCallForwardingInfoAvailable")
                            || method.getName().equals("onError")) {
                        answer[0] = args[0];
                        done.countDown();
                    }
                    return null;
                });
        TelephonyManager.class.getMethod("getCallForwarding", int.class, Executor.class, callbackClass)
                .invoke(telephony, reason, Executors.newSingleThreadExecutor(), callback);
        return done.await(30, TimeUnit.SECONDS) ? answer[0] : "no answer in 30 s";
    }

    private static boolean isInfo(Object answer) {
        return answer != null && answer.getClass().getName().equals("android.telephony.CallForwardingInfo");
    }

    private static Object call(Object target, String name) throws Exception {
        Method m = target.getClass().getMethod(name);
        return m.invoke(target);
    }

    private static String describe(Object answer) {
        try {
            if (isInfo(answer)) {
                return ((boolean) call(answer, "isEnabled") ? "on" : "off") + ", to *"
                        + tail((String) call(answer, "getNumber")) + ", after " + call(answer, "getTimeoutSeconds") + " s";
            }
        } catch (Exception e) {
            return "unreadable: " + e;
        }
        if (answer instanceof Integer) {
            return "error " + answer + " (1 unknown, 2 FDN, 3 not supported)";
        }
        return String.valueOf(answer);
    }

    private static TelephonyManager telephony(Context context) throws Exception {
        // As in CallProbe: the service manager set, the context repointed.
        Class<?> initializer = Class.forName("android.telephony.TelephonyFrameworkInitializer");
        Class<?> managerClass = Class.forName("android.os.TelephonyServiceManager");
        initializer.getDeclaredMethod("setTelephonyServiceManager", managerClass)
                .invoke(null, managerClass.getDeclaredConstructor().newInstance());
        TelephonyManager telephony = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        rebind(telephony, TelephonyManager.class, context);
        return telephony;
    }

    private static void rebind(Object manager, Class<?> type, Context context) {
        try {
            Field field = type.getDeclaredField("mContext");
            field.setAccessible(true);
            field.set(manager, context);
        } catch (ReflectiveOperationException e) {
            log("could not rebind " + type.getSimpleName() + ": " + e);
        }
    }

    private static String tail(String number) {
        if (number == null) {
            return "(none)";
        }
        String d = number.replaceAll("[^0-9]", "");
        return d.length() <= 4 ? d : d.substring(d.length() - 4);
    }

    private static void log(String line) {
        VoipProbe.log(line);
    }
}
