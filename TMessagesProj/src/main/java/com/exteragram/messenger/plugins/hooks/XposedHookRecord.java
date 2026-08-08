package com.exteragram.messenger.plugins.hooks;

import de.robv.android.xposed.XC_MethodHook;

import org.telegram.messenger.FileLog;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class XposedHookRecord implements HookRecord {
    // One callback instance can back several unhooks (addXposedHooks registers N of them), so it
    // must only be closed once the last record referencing it has been cleaned up.
    private static final Map<AutoCloseable, Integer> callbackReferences = Collections.synchronizedMap(new IdentityHashMap<>());

    final XC_MethodHook.Unhook unhookObject;
    private final AtomicBoolean cleanedUp = new AtomicBoolean();

    public XposedHookRecord(XC_MethodHook.Unhook unhookObject) {
        this.unhookObject = unhookObject;
        XC_MethodHook callback = unhookObject != null ? unhookObject.getCallback() : null;
        retainCallback(callback instanceof AutoCloseable closeable ? closeable : null);
    }

    @Override
    public void cleanup() {
        if (!cleanedUp.compareAndSet(false, true) || unhookObject == null) {
            return;
        }
        try {
            unhookObject.unhook();
        } catch (Throwable t) {
            FileLog.e("Error during Xposed unhook cleanup", t);
        } finally {
            try {
                XC_MethodHook callback = unhookObject.getCallback();
                releaseCallback(callback instanceof AutoCloseable closeable ? closeable : null);
            } catch (Throwable t) {
                FileLog.e("Error during Xposed unhook cleanup", t);
            }
        }
    }

    private static void retainCallback(AutoCloseable callback) {
        if (callback == null) {
            return;
        }
        synchronized (callbackReferences) {
            Integer count = callbackReferences.get(callback);
            callbackReferences.put(callback, (count != null ? count : 0) + 1);
        }
    }

    private static void releaseCallback(AutoCloseable callback) throws Exception {
        if (callback == null) {
            return;
        }
        boolean shouldClose;
        synchronized (callbackReferences) {
            Integer count = callbackReferences.get(callback);
            int value = count != null ? count : 0;
            if (value <= 1) {
                callbackReferences.remove(callback);
                shouldClose = true;
            } else {
                callbackReferences.put(callback, value - 1);
                shouldClose = false;
            }
        }
        if (shouldClose) {
            callback.close();
        }
    }

    @Override
    public boolean matches(Object obj) {
        return obj instanceof XC_MethodHook.Unhook && unhookObject == obj;
    }

    @Override
    public boolean equals(Object obj) {
        return this == obj || (obj instanceof XposedHookRecord other && unhookObject == other.unhookObject);
    }

    @Override
    public int hashCode() {
        return unhookObject != null ? unhookObject.hashCode() : 0;
    }
}
