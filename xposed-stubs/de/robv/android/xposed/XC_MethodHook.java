package de.robv.android.xposed;

/**
 * Base class for method hooks (compile-only stub).
 * The real implementation is provided by the Xposed framework at runtime.
 */
public class XC_MethodHook {

    public XC_MethodHook() {
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    /** Parameters / state for a single hooked invocation. */
    public static class MethodHookParam {
        public Object thisObject;
        public Object[] args;
        public Object result;

        public Object getResult() {
            return result;
        }

        public Object setResult(Object result) {
            return this.result = result;
        }

        public Object thisObject() {
            return thisObject;
        }
    }

    /** Handle used to remove a hook later (stub). */
    public static class Unhook {
        public void unhook() {
        }
    }
}