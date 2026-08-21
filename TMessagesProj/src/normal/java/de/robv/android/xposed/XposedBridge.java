package de.robv.android.xposed;

/**
 * No-op stand-in for the hook runtime in the normal flavor.
 * The real XposedBridge loads libaliuhook.so; this build ships neither.
 */
public final class XposedBridge {

    public static boolean isNativeAvailable() {
        return false;
    }

    public static void ensureInitialized() {
    }

    private XposedBridge() {
    }
}
