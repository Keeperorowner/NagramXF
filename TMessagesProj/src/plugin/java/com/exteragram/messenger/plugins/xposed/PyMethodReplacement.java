package com.exteragram.messenger.plugins.xposed;

import com.chaquo.python.PyException;
import com.chaquo.python.PyObject;
import com.exteragram.messenger.plugins.PluginsConstants;
import com.exteragram.messenger.plugins.PluginsController;

import org.telegram.messenger.FileLog;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;

public class PyMethodReplacement extends XC_MethodReplacement implements AutoCloseable {
    private final String pluginId;
    private final PyObject pythonCallback;
    private final PyObject replaceHook;

    private volatile boolean disabled;
    private volatile boolean closed;

    public PyMethodReplacement(String pluginId, PyObject pythonCallback) {
        this(pluginId, pythonCallback, PRIORITY_DEFAULT);
    }

    public PyMethodReplacement(String pluginId, PyObject pythonCallback, int priority) {
        super(priority);
        if (pythonCallback == null) {
            throw new IllegalArgumentException("Python callback object cannot be null");
        }
        if (!pythonCallback.containsKey(PluginsConstants.Xposed.REPLACE_HOOKED_METHOD)) {
            throw new IllegalArgumentException("Python callback object must contain a method named 'replaceHookedMethod'");
        }
        this.pluginId = pluginId;
        this.pythonCallback = pythonCallback;
        this.replaceHook = pythonCallback.get(PluginsConstants.Xposed.REPLACE_HOOKED_METHOD);
        if (this.replaceHook == null) {
            throw new IllegalArgumentException("Python callback object must contain a method named 'replaceHookedMethod'");
        }
    }

    @Override
    protected Object replaceHookedMethod(XC_MethodHook.MethodHookParam param) {
        if (disabled || !PluginsController.getInstance().isPluginActive(pluginId)) {
            // Fall back to the original implementation, otherwise a primitive return type would be
            // unboxed from null and blow up inside the host method instead of here.
            return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
        }
        PyObject result = null;
        try {
            result = replaceHook.call(param);
            return result == null ? null : result.toJava(Object.class);
        } catch (Throwable t) {
            handleHookError(t);
            return null;
        } finally {
            if (result != null) {
                result.close();
            }
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        disabled = true;
        replaceHook.close();
    }

    private void handleHookError(Throwable t) {
        if (t instanceof PyException && t.getMessage() != null && t.getMessage().contains("closed")) {
            disabled = true;
            FileLog.e("Attempted to call a closed PyObject callback in " + pluginId);
            return;
        }
        FileLog.e("Plugin '" + pluginId + "' crashed in replaceHookedMethod: " + t.getMessage(), t);
    }
}
