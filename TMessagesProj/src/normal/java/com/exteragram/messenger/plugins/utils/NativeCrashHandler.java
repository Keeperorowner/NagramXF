package com.exteragram.messenger.plugins.utils;

/**
 * No-op stand-in for the plugin runtime in the normal flavor.
 */
public class NativeCrashHandler {

    public static void init(String path) {
    }

    public static String getCrashFlagPath() {
        return "";
    }

    private NativeCrashHandler() {
    }
}
