package com.term.statuslyrics;

import android.os.Handler;
import android.os.Looper;
import android.view.View;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Xposed entry point.
 *
 * Crash-safety is the top priority: a single uncaught throwable escaping a
 * hook crashes SystemUI into a "Phone is starting" loop. So:
 *  - We hook ONLY the SystemUI clock class (never TextView globally).
 *  - The hook body is fully guarded and does NO work synchronously: it just
 *    posts the real install to the main thread with a delay, so we never run
 *    Binder IPC inside the view-attach path during boot.
 *  - Lyrics render on the clock view ITSELF via reflection; we never create
 *    or insert views.
 */
public class StatusLyricsMod implements IXposedHookLoadPackage {

    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String CLOCK_CLASS =
            "com.android.systemui.statusbar.policy.Clock";

    private static final long INSTALL_DELAY_MS = 2000L;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lp) {
        try {
            if (lp == null || !SYSTEM_UI.equals(lp.packageName)) {
                return;
            }
            Class<?> clock;
            try {
                clock = XposedHelpers.findClass(CLOCK_CLASS, lp.classLoader);
            } catch (Throwable t) {
                XposedBridge.log("StatusLyrics: Clock class not found, disabling: " + t);
                return; // never fall back to a process-wide hook
            }
            hook(clock);
        } catch (Throwable t) {
            try {
                XposedBridge.log("StatusLyrics: handleLoadPackage: " + t);
            } catch (Throwable ignore) {
            }
        }
    }

    private void hook(Class<?> clock) {
        // Primary: hook the constructor so we always catch the instance.
        try {
            XposedHelpers.findAndHookMethod(clock, "<init>", new InstallHook());
            XposedBridge.log("StatusLyrics: hooked Clock constructor");
        } catch (Throwable t) {
            XposedBridge.log("StatusLyrics: no Clock constructor: " + t);
        }

        // Secondary: hook onAttachedToWindow (fires once the view has a Context).
        try {
            XposedHelpers.findAndHookMethod(clock, "onAttachedToWindow", new InstallHook());
            XposedBridge.log("StatusLyrics: hooked Clock.onAttachedToWindow");
        } catch (Throwable t) {
            XposedBridge.log("StatusLyrics: no Clock.onAttachedToWindow: " + t);
        }
    }

    private static final class InstallHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (param == null || param.thisObject == null) {
                    return;
                }
                if (!(param.thisObject instanceof View)) {
                    return;
                }
                final View v = (View) param.thisObject;
                // Never do Binder IPC or session-manager work synchronously
                // inside the attach path (that can ANR/crash SystemUI during
                // boot and wedge the device on "Phone is starting").
                // Defer to the main thread after boot has settled.
                try {
                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                StatusLyricsController.install(v);
                            } catch (Throwable t) {
                                try {
                                    XposedBridge.log("StatusLyrics: deferred install: " + t);
                                } catch (Throwable ignore) {
                                }
                            }
                        }
                    }, INSTALL_DELAY_MS);
                } catch (Throwable t) {
                    try {
                        XposedBridge.log("StatusLyrics: schedule install: " + t);
                    } catch (Throwable ignore) {
                    }
                }
            } catch (Throwable t) {
                try {
                    XposedBridge.log("StatusLyrics: hook: " + t);
                } catch (Throwable ignore) {
                }
            }
        }
    }
}
