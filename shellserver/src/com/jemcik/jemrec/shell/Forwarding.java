package com.jemcik.jemrec.shell;

import android.telephony.TelephonyManager;

import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The carrier's own voicemail timer: how long an unanswered call rings before
 * the network forwards it. The answering machine has to answer first, so the
 * diagnostics read it, and on the user's tap lengthen it.
 *
 * TelephonyManager.getCallForwarding and setCallForwarding are SystemApis
 * (READ_PRIVILEGED_PHONE_STATE and MODIFY_PHONE_STATE, both the shell's), not
 * in the SDK this compiles against, so they are reached by reflection.
 * Measured on an Honor with a Kyivstar SIM, 2026-10-22: a read takes 6 to 18 s
 * - it asks the network - and on 2G a set worked and read back.
 */
final class Forwarding {

    /** CallForwardingInfo.REASON_NO_REPLY. */
    static final int REASON_NO_REPLY = 2;
    private static final long ANSWER_MS = 30_000L;
    /** The answers are a value and a latch: taken on the binder thread
     *  they arrive on, not on an executor of their own that is never shut
     *  down - one per question, in a process that runs for weeks. */
    private static final Executor DIRECT = Runnable::run;

    private Forwarding() {
    }

    /** "on 25 *8250", "off 0", "error 3" (the carrier's code), or "none". */
    static String readNoReply(TelephonyManager telephony) {
        return describe(read(telephony));
    }

    /**
     * Keep the carrier's voicemail, but after [seconds]: re-registers no-reply
     * forwarding to the number it already has. Changes nothing when it is
     * off - there is nothing racing the answering machine then.
     */
    static String setNoReply(TelephonyManager telephony, int seconds) {
        Object now = read(telephony);
        try {
            if (now == null || !isInfo(now) || !(boolean) call(now, "isEnabled")) {
                return "unchanged " + describe(now);
            }
            Class<?> infoClass = Class.forName("android.telephony.CallForwardingInfo");
            Object wanted = infoClass.getConstructor(boolean.class, int.class, String.class, int.class)
                    .newInstance(true, REASON_NO_REPLY, call(now, "getNumber"), seconds);
            int[] code = {-1};
            CountDownLatch done = new CountDownLatch(1);
            Consumer<Integer> result = c -> {
                code[0] = c;
                done.countDown();
            };
            TelephonyManager.class.getMethod("setCallForwarding", infoClass, Executor.class, Consumer.class)
                    .invoke(telephony, wanted, DIRECT, result);
            if (!done.await(ANSWER_MS, TimeUnit.MILLISECONDS)) {
                return "error timeout";
            }
            return code[0] == 0 ? "set " + describe(read(telephony)) : "error " + code[0];
        } catch (Throwable t) {
            return "error " + t.getClass().getSimpleName();
        }
    }

    private static @Nullable Object read(TelephonyManager telephony) {
        try {
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
                    .invoke(telephony, REASON_NO_REPLY, DIRECT, callback);
            return done.await(ANSWER_MS, TimeUnit.MILLISECONDS) ? answer[0] : null;
        } catch (Throwable t) {
            return t;
        }
    }

    private static String describe(@Nullable Object answer) {
        try {
            if (answer != null && isInfo(answer)) {
                boolean on = (boolean) call(answer, "isEnabled");
                return (on ? "on " : "off ") + call(answer, "getTimeoutSeconds")
                        + (on ? " *" + tail((String) call(answer, "getNumber")) : "");
            }
        } catch (Throwable t) {
            return "error " + t.getClass().getSimpleName();
        }
        if (answer instanceof Integer) {
            return "error " + answer;
        }
        return answer == null ? "none" : "error " + answer.getClass().getSimpleName();
    }

    private static boolean isInfo(@Nullable Object answer) {
        return answer != null && answer.getClass().getName().equals("android.telephony.CallForwardingInfo");
    }

    private static Object call(Object target, String name) throws ReflectiveOperationException {
        return target.getClass().getMethod(name).invoke(target);
    }

    private static String tail(String number) {
        if (number == null) {
            return "";
        }
        String digits = number.replaceAll("[^0-9]", "");
        return digits.length() <= 4 ? digits : digits.substring(digits.length() - 4);
    }
}
