package com.jemcik.jemrec.shell;

/**
 * The messenger copy's audio policy (Voip.Policy): whether one is registered,
 * and how many captures are reading through it - in plain Java, under one
 * lock, so daemon-tests can hold it to the races it is here for.
 *
 * It goes only when nothing holds it. A capture handed out by Voip claims it
 * as the capture starts, and lets it go as the capture stops; a phone call's
 * capture finding none by then - a messenger list emptied in between, its
 * policy gone with it - registers one, as handing it out would have. Before,
 * a policy was let go once no capture had STARTED, read without a lock: one
 * handed out but not yet started - a phone call through the mixer flipping
 * back and forth, or the app emptying the list as a messenger call began -
 * was left with a policy unregistered under it, and that stretch of the call
 * on the plain capture: silent, for Wi-Fi Calling (read from the code).
 */
final class PolicyUse<P> {

    /** How a policy is made and let go of: Android's, through Voip.Policy. */
    interface Registry<P> {
        P register() throws Exception;

        void unregister(P policy);
    }

    private final Registry<P> registry;
    private @Nullable P policy;
    private int claims;

    PolicyUse(Registry<P> registry) {
        this.registry = registry;
    }

    /** The policy registered now, or null. */
    synchronized @Nullable P armed() {
        return policy;
    }

    /** The policy, registered now if there is none; throws if Android
     *  refuses it. */
    synchronized P arm() throws Exception {
        P p = policy;
        if (p == null) {
            p = registry.register();
            policy = p;
        }
        return p;
    }

    /** A capture starting: the policy it reads through, held until
     *  [release]. [register]: a phone call's capture registers one if there
     *  is none; a messenger capture finds none when the list was emptied -
     *  the user's off - and gets null, holding nothing. */
    synchronized @Nullable P claim(boolean register) throws Exception {
        P p = register ? arm() : policy;
        if (p != null) {
            claims++;
        }
        return p;
    }

    /** A capture that claimed it has stopped. */
    synchronized void release() {
        if (claims > 0) {
            claims--;
        }
    }

    /** Let the policy go if no capture holds it: true then (none was
     *  registered, or it is unregistered now), false while one does. */
    synchronized boolean disarmIfIdle() {
        if (claims > 0) {
            return false;
        }
        P p = policy;
        if (p != null) {
            policy = null;
            registry.unregister(p);
        }
        return true;
    }
}
