package com.exteragram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import com.exteragram.messenger.badges.BadgesController;
import com.exteragram.messenger.plugins.PluginsConstants;
import com.exteragram.messenger.plugins.PluginsController;

import org.telegram.messenger.ApplicationLoader;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class ExteraConfig {
    private static final Object sync = new Object();
    private static boolean configLoaded;
    private static boolean initialized;

    public static SharedPreferences preferences;
    public static SharedPreferences.Editor editor;
    public static boolean pluginsEngine;
    public static boolean pluginsSafeMode;
    public static boolean pluginsDevMode;
    public static boolean pluginsCompactView;
    public static boolean pluginsDisableArtOpts;
    public static boolean pluginsPySdkAutoUpdate;
    public static boolean pluginsPySdkBetaVersions;
    public static long sdkUpdateScheduleTimestamp;
    public static Set<String> pinnedPlugins = Collections.emptySet();

    private ExteraConfig() {
    }

    public enum DrawerItem {
        PLUGINS(102);

        public final int id;

        DrawerItem(int id) {
            this.id = id;
        }

        public static DrawerItem getById(int id) {
            for (DrawerItem item : values()) {
                if (item.id == id) {
                    return item;
                }
            }
            return null;
        }
    }

    public static void loadConfig() {
        synchronized (sync) {
            if (configLoaded) {
                return;
            }
            Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return;
            }
            preferences = context.getSharedPreferences("exteraconfig", Context.MODE_PRIVATE);
            editor = preferences.edit();
            pluginsEngine = PluginsController.isPluginEngineSupported() && preferences.getBoolean("pluginsEngine", false);
            pluginsSafeMode = preferences.getBoolean("pluginsSafeMode", false);
            pluginsDevMode = preferences.getBoolean("pluginsDevMode", false);
            pluginsCompactView = preferences.getBoolean("pluginsCompactView", false);
            pluginsDisableArtOpts = preferences.getBoolean("pluginsDisableArtOpts", false);
            pluginsPySdkAutoUpdate = preferences.getBoolean("pluginsPySdkAutoUpdate", false);
            pluginsPySdkBetaVersions = preferences.getBoolean("pluginsPySdkBetaVersions", false);
            sdkUpdateScheduleTimestamp = preferences.getLong("sdkUpdateScheduleTimestamp", 0L);
            pinnedPlugins = new HashSet<>(preferences.getStringSet("pinnedPlugins", Collections.emptySet()));
            configLoaded = true;
        }
    }

    public static void reloadConfig() {
        synchronized (sync) {
            configLoaded = false;
        }
        loadConfig();
    }

    public static void init() {
        synchronized (sync) {
            if (initialized) {
                return;
            }
            initialized = true;
        }
        loadConfig();
        BadgesController.INSTANCE.init();
        PluginsController.getInstance().init(() -> PluginsController.getInstance().executeOnAppEvent(PluginsConstants.APP_START));
    }
}
