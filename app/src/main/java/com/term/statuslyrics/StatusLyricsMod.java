package com.term.statuslyrics;

import android.view.View;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Xposed entry point.
 *
 * Hook strategy (crash-safe): we hook ONLY the SystemUI clock class, and we
 * render lyrics on the clock view ITSELF. We never create additional views and
 * we never hook TextView globally, so the module cannot recurse into itself or
 * mutate the status-bar view tree — either of which would crash System UI and
 * cause a boot loop ("Phone is starting").
 */
public class StatusLyricsMod implements IXposedHookLoadPackage {

    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String CLOCK_CLASS =
            "com.android.systemui.statusbar.policy.Clock";

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lp) {
        if (lp == null || !SYSTEM_UI.equals(lp.packageName)) {
            return;
        }
        Class<?> clock = null;
        try {
            clock = XposedHelpers.findClass(CLOCK_CLASS, lp.classLoader);
        } catch (Throwable t) {
            XposedBridge.log("StatusLyrics: Clock class not found, disabling: " + t);
            return; // never fall back to a process-wide hook
        }
        hook(clock);
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
            if (param == null || param.thisObject == null) {
                return;
            }
            if (param.thisObject instanceof View) {
                StatusLyricsController.install((View) param.thisObject);
            }
        }
    }
}