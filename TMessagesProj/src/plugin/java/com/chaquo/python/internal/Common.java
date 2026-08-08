package com.chaquo.python.internal;

import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runtime configuration for the plugin engine's bundled Python 3.11 (fork-specific, replaces
 * the class upstream Chaquopy generates per build).
 */
public class Common {
    public static final String ABI_COMMON = "common";
    public static final String ASSET_APP = "app";
    public static final String ASSET_BOOTSTRAP = "bootstrap";
    public static final String ASSET_BOOTSTRAP_NATIVE = "bootstrap-native";
    public static final String ASSET_BUILD_JSON = "build.json";
    public static final String ASSET_CACERT = "cacert.pem";
    public static final String ASSET_DIR = "chaquopy";
    public static final String ASSET_REQUIREMENTS = "requirements";
    public static final String ASSET_STDLIB = "stdlib";
    public static final int COMPILE_SDK_VERSION = 36;
    public static final String DEFAULT_PYTHON_VERSION = "3.11";
    public static final String MIN_AGP_VERSION = "7.3.0";
    public static final int MIN_SDK_VERSION = 24;
    public static final Map<String, String> PYTHON_VERSIONS;
    public static List<String> PYTHON_VERSIONS_SHORT;

    static {
        LinkedHashMap<String, String> versions = new LinkedHashMap<>();
        PYTHON_VERSIONS = versions;
        versions.put("3.11.10", "1");
        PYTHON_VERSIONS_SHORT = new ArrayList<>();
        for (String version : versions.keySet()) {
            PYTHON_VERSIONS_SHORT.add(version.substring(0, version.lastIndexOf('.')));
        }
    }

    public static String assetZip(String name) {
        return assetZip(name, null);
    }

    public static String assetZip(String name, String abi) {
        if (abi == null) {
            return name + ".imy";
        }
        return name + "-" + abi + ".imy";
    }

    public static String osName() {
        String property = System.getProperty("os.name");
        for (int i = 0; i < 3; i++) {
            String osName = new String[]{"linux", "mac", "windows"}[i];
            if (property.toLowerCase(Locale.ENGLISH).startsWith(osName)) {
                return osName;
            }
        }
        Log.e("chaquopy", "unknown os.name: " + property);
        return null;
    }

    public static List<String> supportedAbis(String pythonVersion) {
        if (!PYTHON_VERSIONS_SHORT.contains(pythonVersion)) {
            Log.e("chaquopy", "Unknown Python version: '" + pythonVersion + "'");
            return null;
        }
        List<String> abis = new ArrayList<>();
        abis.add("arm64-v8a");
        abis.add("x86_64");
        if (Arrays.asList("3.8", "3.9", "3.10", DEFAULT_PYTHON_VERSION).contains(pythonVersion)) {
            abis.add("armeabi-v7a");
            abis.add("x86");
        }
        abis.sort(null);
        return abis;
    }
}
