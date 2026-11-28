package com.exteragram.messenger.plugins.utils;

import com.exteragram.messenger.plugins.PluginsController;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.File;

import de.robv.android.xposed.XposedBridge;

public class NativeCrashHandler {
    private static final String CRASH_FLAG_FILENAME = "native_crash.flag";

    public static native void init(String path);

    public static boolean isNativeLibraryLoaded() {
        return XposedBridge.isNativeLibraryLoaded();
    }

    public static void checkAndHandleNativeCrash() {
        File flag = new File(ApplicationLoader.getFilesDirFixed(), CRASH_FLAG_FILENAME);
        if (!flag.exists()) {
            return;
        }
        FileLog.e("Native crash detected. Enabling safe mode for plugins.");
        PluginsController.markPendingSafeModeCrash(PluginsController.SafeModeReason.NATIVE_CRASH, null);
        //noinspection ResultOfMethodCallIgnored
        flag.delete();
    }

    public static String getCrashFlagPath() {
        return new File(ApplicationLoader.getFilesDirFixed(), CRASH_FLAG_FILENAME).getAbsolutePath();
    }
}
