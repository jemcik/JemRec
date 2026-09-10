package com.genymobile.scrcpy.wrappers;

import android.annotation.SuppressLint;
import android.os.IBinder;
import android.os.IInterface;

import java.lang.reflect.Method;

/**
 * TRIMMED FORK of scrcpy's ServiceManager. See ../../../../../PATCHES.md.
 *
 * Upstream this is a facade over nine system services. The audio path uses
 * exactly one of them - ActivityManager - in exactly two places:
 *
 *   AudioDirectCapture  startActivity() / forceStopPackage(), the foreground
 *                       workaround that only a shell-UID process may perform
 *   FakeContext         getContentProviderExternal()
 *
 * Every other getter was reachable only from video, control or display code
 * that this fork does not contain. Keeping them would have pulled in
 * WindowManager, DisplayManager, InputManager, PowerManager, StatusBarManager,
 * ClipboardManager, ContentProvider, SurfaceControl and DisplayControl - about
 * 1,300 lines, none of it ever executed. Deleting them is what takes the fork
 * from 36 files to 23.
 *
 * getService() is kept as-is because ActivityManager.create() calls it.
 */
@SuppressLint("PrivateApi,DiscouragedPrivateApi")
public final class ServiceManager {

    private static final Method GET_SERVICE_METHOD;

    static {
        try {
            GET_SERVICE_METHOD = Class.forName("android.os.ServiceManager").getDeclaredMethod("getService", String.class);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static ActivityManager activityManager;

    private ServiceManager() {
        /* not instantiable */
    }

    static IInterface getService(String service, String type) {
        try {
            IBinder binder = (IBinder) GET_SERVICE_METHOD.invoke(null, service);
            Method asInterfaceMethod = Class.forName(type + "$Stub").getMethod("asInterface", IBinder.class);
            return (IInterface) asInterfaceMethod.invoke(null, binder);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    public static ActivityManager getActivityManager() {
        if (activityManager == null) {
            activityManager = ActivityManager.create();
        }
        return activityManager;
    }
}
