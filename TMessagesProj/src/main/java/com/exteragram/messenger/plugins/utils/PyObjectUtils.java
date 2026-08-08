package com.exteragram.messenger.plugins.utils;

import android.text.TextUtils;

import com.chaquo.python.PyException;
import com.chaquo.python.PyObject;

public final class PyObjectUtils {
    private PyObjectUtils() {
    }

    public static <T> T toJavaCompat(PyObject pyObject, Class<T> clazz) {
        if (pyObject == null) {
            return null;
        }
        try {
            return pyObject.toJava(clazz);
        } catch (PyException | ClassCastException ignored) {
            // Fallback: class-proxy peers expose the generated Java instance via a "java"
            // attribute; resolve it when plain toJava() fails.
            PyObject converted = null;
            try {
                converted = pyObject.callAttr("__getattribute__", "java");
                return converted == null ? null : converted.toJava(clazz);
            } catch (PyException | ClassCastException ignored2) {
                return null;
            } finally {
                closeQuietly(converted);
            }
        }
    }

    public static String getString(PyObject object, String key, String defaultValue) {
        return getString(object, key, defaultValue, false);
    }

    public static String getString(PyObject object, String key, String defaultValue, boolean dictGet) {
        if (object == null || TextUtils.isEmpty(key)) {
            return defaultValue;
        }
        PyObject value = null;
        try {
            value = dictGet ? object.callAttr("get", key) : object.get(key);
            if (value == null) {
                return defaultValue;
            }
            return value.toString();
        } catch (PyException | ClassCastException ignored) {
            return defaultValue;
        } finally {
            closeQuietly(value);
        }
    }

    public static boolean getBoolean(PyObject object, String key, boolean defaultValue) {
        if (object == null || TextUtils.isEmpty(key)) {
            return defaultValue;
        }
        PyObject value = null;
        try {
            value = object.get(key);
            if (value == null) {
                return defaultValue;
            }
            return value.toBoolean();
        } catch (PyException | ClassCastException ignored) {
            return defaultValue;
        } finally {
            closeQuietly(value);
        }
    }

    public static int getInt(PyObject object, String key, int defaultValue) {
        return getInt(object, key, defaultValue, false);
    }

    public static int getInt(PyObject object, String key, int defaultValue, boolean dictGet) {
        if (object == null || TextUtils.isEmpty(key)) {
            return defaultValue;
        }
        PyObject value = null;
        try {
            value = dictGet ? object.callAttr("get", key) : object.get(key);
            if (value == null) {
                return defaultValue;
            }
            return value.toInt();
        } catch (PyException | ClassCastException ignored) {
            return defaultValue;
        } finally {
            closeQuietly(value);
        }
    }

    public static String[] getStringArray(PyObject object, String key, String[] defaultValue) {
        if (object == null || TextUtils.isEmpty(key)) {
            return defaultValue;
        }
        PyObject value = null;
        try {
            value = object.get(key);
            if (value == null) {
                return defaultValue;
            }
            String[] result = value.toJava(String[].class);
            return result == null || result.length == 0 ? defaultValue : result;
        } catch (Throwable ignored) {
            return defaultValue;
        } finally {
            closeQuietly(value);
        }
    }

    private static void closeQuietly(PyObject pyObject) {
        if (pyObject != null) {
            try {
                pyObject.close();
            } catch (PyException ignored) {
            }
        }
    }
}
