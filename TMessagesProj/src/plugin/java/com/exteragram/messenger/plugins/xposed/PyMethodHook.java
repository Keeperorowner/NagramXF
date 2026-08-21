package com.exteragram.messenger.plugins.xposed;

import com.chaquo.python.PyException;
import com.chaquo.python.PyObject;
import com.exteragram.messenger.plugins.PluginsConstants;
import com.exteragram.messenger.plugins.PluginsController;
import com.exteragram.messenger.plugins.hooks.HookFilter;

import org.telegram.messenger.FileLog;

import java.util.ArrayList;

import de.robv.android.xposed.XC_MethodHook;

public class PyMethodHook extends XC_MethodHook implements AutoCloseable {
    private ArrayList<HookFilter> beforeHookedFilters = new ArrayList<>();
    private ArrayList<HookFilter> afterHookedFilters = new ArrayList<>();
    private final String pluginId;
    private final PyObject pythonCallback;
    private final PyObject beforeHook;
    private final PyObject afterHook;

    private volatile boolean disabled;
    private volatile boolean closed;

    public PyMethodHook(String pluginId, PyObject pythonCallback) {
        this(pluginId, pythonCallback, PRIORITY_DEFAULT, true, true);
    }

    public PyMethodHook(String pluginId, PyObject pythonCallback, int priority) {
        this(pluginId, pythonCallback, priority, true, true);
    }

    public PyMethodHook(String pluginId, PyObject pythonCallback, boolean hasBeforeHook, boolean hasAfterHook) {
        this(pluginId, pythonCallback, PRIORITY_DEFAULT, hasBeforeHook, hasAfterHook);
    }

    public PyMethodHook(String pluginId, PyObject pythonCallback, int priority, boolean hasBeforeHook, boolean hasAfterHook) {
        super(priority);
        if (pythonCallback == null) {
            throw new IllegalArgumentException("Python callback object cannot be null");
        }
        this.pluginId = pluginId;
        this.pythonCallback = pythonCallback;
        this.beforeHook = getCallbackIfPresent(pythonCallback, PluginsConstants.Xposed.BEFORE_HOOKED_METHOD, hasBeforeHook);
        this.afterHook = getCallbackIfPresent(pythonCallback, PluginsConstants.Xposed.AFTER_HOOKED_METHOD, hasAfterHook);
    }

    public void setBeforeHookedFilters(ArrayList<HookFilter> filters) {
        this.beforeHookedFilters = filters;
    }

    public void setAfterHookedFilters(ArrayList<HookFilter> filters) {
        this.afterHookedFilters = filters;
    }

    public ArrayList<HookFilter> getBeforeHookedFilters() {
        return beforeHookedFilters;
    }

    public ArrayList<HookFilter> getAfterHookedFilters() {
        return afterHookedFilters;
    }

    @Override
    protected void beforeHookedMethod(MethodHookParam param) {
        if (disabled || beforeHook == null || !PluginsController.getInstance().isPluginActive(pluginId)) {
            return;
        }
        try {
            for (HookFilter filter : beforeHookedFilters) {
                if (!filter.execute(param, true)) {
                    return;
                }
            }
            PyObject result = beforeHook.call(param);
            if (result != null) {
                result.close();
            }
        } catch (Throwable t) {
            handleHookError("beforeHookedMethod", t);
        }
    }

    @Override
    protected void afterHookedMethod(MethodHookParam param) {
        if (disabled || afterHook == null || !PluginsController.getInstance().isPluginActive(pluginId)) {
            return;
        }
        try {
            for (HookFilter filter : afterHookedFilters) {
                if (!filter.execute(param, false)) {
                    return;
                }
            }
            PyObject result = afterHook.call(param);
            if (result != null) {
                result.close();
            }
        } catch (Throwable t) {
            handleHookError("afterHookedMethod", t);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        disabled = true;
        if (beforeHook != null) {
            beforeHook.close();
        }
        if (afterHook != null) {
            afterHook.close();
        }
    }

    private static PyObject getCallbackIfPresent(PyObject callbackObject, String name, boolean enabled) {
        return enabled && callbackObject.containsKey(name) ? callbackObject.get(name) : null;
    }

    private void handleHookError(String where, Throwable t) {
        if (t instanceof PyException && t.getMessage() != null && t.getMessage().contains("closed")) {
            disabled = true;
            FileLog.e("Attempted to call a closed PyObject callback in " + pluginId);
            return;
        }
        FileLog.e("Plugin '" + pluginId + "' crashed in " + where + ": " + t.getMessage(), t);
    }
}
