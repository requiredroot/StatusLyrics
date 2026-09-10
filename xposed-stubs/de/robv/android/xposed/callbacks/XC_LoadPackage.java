package de.robv.android.xposed.callbacks;

import de.robv.android.xposed.XC_MethodHook;

/** Load package callback container (compile-only stub). */
public class XC_LoadPackage extends XC_MethodHook {

    /** Passed to handleLoadPackage. */
    public static class LoadPackageParam {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public boolean isFirstApplication;
    }
}