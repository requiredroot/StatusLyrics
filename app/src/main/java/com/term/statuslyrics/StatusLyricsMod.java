package com.term.statuslyrics;

import android.widget.TextView;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Xposed entry point. Hooks the System UI process and attaches the lyrics
 * controller to the status bar clock.
 */
public class StatusLyricsMod implements IXposedHookLoadPackage {

    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String CLOCK_CLASS =
            "com.android.systemui.statusbar.policy.Clock";

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lp) {
        if (!SYSTEM_UI.equals(lp.packageName)) {
            return;
        }
        hookClockClass(lp);
    }

    private void hookClockClass(final XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> clock = XposedHelpers.findClass(CLOCK_CLASS, lp.classLoader);
            XposedHelpers.findAndHookMethod(clock, "onAttachedToWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                TextView tv = (TextView) param.thisObject;
                                StatusLyricsController.install(tv);
                            } catch (Throwable t) {
                                XposedBridge.log("StatusLyrics: clock hook", t);
                            }
                        }
                    });
            XposedBridge.log("StatusLyrics: hooked " + CLOCK_CLASS);
        } catch (Throwable t) {
            XposedBridge.log("StatusLyrics: primary clock class not found: " + t);
            hookTextViewFallback(lp);
        }
    }

    /**
     * Fallback for ROMs where Clock was renamed or is an anonymous subclass.
     * Any TextView whose resource id matches SystemUI's R.id.clock is treated
     * as the status bar clock.
     */
    private void hookTextViewFallback(final XC_LoadPackage.LoadPackageParam lp) {
        final int clockId = resolveClockId(lp);
        if (clockId == Integer.MIN_VALUE) {
            XposedBridge.log("StatusLyrics: could not resolve clock resource id");
            return;
        }
        XposedHelpers.findAndHookMethod(TextView.class, "onAttachedToWindow",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        TextView tv = (TextView) param.thisObject;
                        if (tv.getId() == clockId) {
                            StatusLyricsController.install(tv);
                        }
                    }
                });
        XposedBridge.log("StatusLyrics: hooked TextView fallback (clockId=" + clockId + ")");
    }
private int resolveClockId(final XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> rId = XposedHelpers.findClass(SYSTEM_UI + ".R$id", lp.classLoader);
            java.lang.reflect.Field f = rId.getDeclaredField("clock");
            f.setAccessible(true);
            return f.getInt(null);
        } catch (Throwable t) {
            return Integer.MIN_VALUE;
        }
    }
}