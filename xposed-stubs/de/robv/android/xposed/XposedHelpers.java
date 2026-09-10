package de.robv.android.xposed;

/**
 * Convenience helpers for finding classes/methods and hooking them
 * (compile-only stub). Real runtime implementation provided by framework.
 */
public final class XposedHelpers {

    private XposedHelpers() {
    }

    public interface UnknownTypeException {
    }

    public static Class<?> findClass(String className, ClassLoader classLoader)
            throws ClassNotFoundException {
        return Class.forName(className, false, classLoader);
    }

    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> clazz,
            String methodName, Object... parameterTypesAndCallback) {
        return null;
    }

    public static XC_MethodHook.Unhook findAndHookMethod(String className,
            ClassLoader classLoader, String methodName, Object... parameterTypesAndCallback) {
        return null;
    }
}