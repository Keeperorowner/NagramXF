package de.robv.android.xposed;

import de.robv.android.xposed.callbacks.IXUnhook;
import de.robv.android.xposed.callbacks.XCallback;

import java.lang.reflect.Member;

public abstract class XC_MethodHook extends XCallback {
    protected void beforeHookedMethod(MethodHookParam param) {
    }

    protected void afterHookedMethod(MethodHookParam param) {
    }

    public XC_MethodHook() {
    }

    public XC_MethodHook(int priority) {
        super(priority);
    }

    public static final class MethodHookParam extends XCallback.Param {
        public Member method;
        public Object thisObject;
        public Object[] args;
        private Object result;
        private Throwable throwable;
        boolean returnEarly;

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
            this.throwable = null;
            this.returnEarly = true;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        public boolean hasThrowable() {
            return throwable != null;
        }

        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
            this.result = null;
            this.returnEarly = true;
        }

        public Object getResultOrThrowable() throws Throwable {
            if (throwable != null) {
                throw throwable;
            }
            return result;
        }
    }

    public class Unhook implements IXUnhook<XC_MethodHook> {
        private final Member hookedMethod;

        Unhook(Member hookedMethod) {
            this.hookedMethod = hookedMethod;
        }

        public Member getHookedMethod() {
            return hookedMethod;
        }

        @Override
        public XC_MethodHook getCallback() {
            return XC_MethodHook.this;
        }

        @Override
        public void unhook() {
            XposedBridge.unhookMethod(hookedMethod, XC_MethodHook.this);
        }
    }
}
