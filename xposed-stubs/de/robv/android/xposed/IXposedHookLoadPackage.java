package de.robv.android.xposed;

/**
 * Interface for Xposed modules (compile-time stub).
 * The real implementation is provided by the Xposed framework at runtime.
 */
public interface IXposedHookLoadPackage {

    void handleLoadPackage(de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam lpparam)
            throws Throwable;
}