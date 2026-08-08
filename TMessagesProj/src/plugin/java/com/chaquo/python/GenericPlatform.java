package com.chaquo.python;

import android.util.Log;

/** Platform for a generic Python installation. */
public class GenericPlatform extends Python.Platform {
    private String mPath = System.getenv("PYTHONPATH");

    public GenericPlatform() {
        if (System.getProperty("java.vendor").toLowerCase().contains("android")) {
            Log.e("chaquopy", "Cannot use GenericPlatform on Android. Call Python.start(new " +
                              "AndroidPlatform(context)) before using Python, or use PyApplication " +
                              "to do this automatically.");
            throw new RuntimeException("Cannot use GenericPlatform on Android. Call Python.start" +
                                       "(new AndroidPlatform(context)) before using Python, or " +
                                       "use PyApplication to do this automatically.");
        }
        System.loadLibrary("chaquopy_java");
    }

    @Override
    public String getPath() {
        return mPath;
    }

    /** Sets the value to assign to {@code PYTHONPATH}. */
    public GenericPlatform setPath(String path) {
        mPath = path;
        return this;
    }
}
