package com.radolyn.ayugram;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;

import java.util.HashMap;

public abstract class AyuGhostConfig {

    private static final String PREFS_FILE = "ayughostconfig";
    private static final String KEY_GLOBAL_OVERRIDE = "useGlobalConfig";
    private static final String KEY_MIGRATED = "migratedFromNekoConfig";

    private static boolean configLoaded = false;
    private static SharedPreferences preferences;
    private static SharedPreferences.Editor editor;
    private static HashMap<Long, GhostModeSettings> settings = new HashMap<>();
    private static final Object sync = new Object();
    private static boolean useGlobalConfig = true;

    static {
        loadConfig();
    }

    public static void loadConfig() {
        synchronized (sync) {
            if (configLoaded) {
                return;
            }
            if (ApplicationLoader.applicationContext == null) {
                return;
            }
            preferences = ApplicationLoader.applicationContext
                    .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
            editor = preferences.edit();

            settings = new HashMap<>();
            settings.put(-1L, new GhostModeSettings(-1L));
            for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
                if (UserConfig.isValidAccount(i)) {
                    long userId = UserConfig.getInstance(i).getClientUserId();
                    if (userId != 0 && !settings.containsKey(userId)) {
                        settings.put(userId, new GhostModeSettings(userId));
                    }
                }
            }

            useGlobalConfig = preferences.getBoolean(KEY_GLOBAL_OVERRIDE, true);

            if (!preferences.getBoolean(KEY_MIGRATED, false)) {
                migrateFromNekoConfig();
                editor.putBoolean(KEY_MIGRATED, true).apply();
            }

            configLoaded = true;
        }
    }

    public static void reloadConfig() {
        synchronized (sync) {
            configLoaded = false;
            loadConfig();
        }
    }


    public static boolean isGlobalOverride() {
        return useGlobalConfig;
    }

    public static void setGlobalOverride(boolean z) {
        useGlobalConfig = z;
        editor.putBoolean(KEY_GLOBAL_OVERRIDE, z).apply();
    }


    public static GhostModeSettings getGhostModeSettings(long userId) {
        loadConfig();
        if (useGlobalConfig) {
            userId = -1;
        }
        GhostModeSettings s = settings.get(userId);
        if (s == null) {
            s = new GhostModeSettings(userId);
            settings.put(userId, s);
        }
        return s;
    }

    public static GhostModeSettings getGhostModeSettingsForAccount(int account) {
        long userId = UserConfig.getInstance(account).getClientUserId();
        return getGhostModeSettings(userId);
    }


    public static boolean isGhostModeActive(int account) {
        return getGhostModeSettingsForAccount(account).isGhostModeActive();
    }

    public static boolean isSendReadMessagePackets(int account) {
        return getGhostModeSettingsForAccount(account).sendReadMessagePackets;
    }

    public static boolean isSendReadStoriesPackets(int account) {
        return getGhostModeSettingsForAccount(account).sendReadStoryPackets;
    }

    public static boolean isSendOnlinePackets(int account) {
        return getGhostModeSettingsForAccount(account).sendOnlinePackets;
    }

    public static boolean isSendUploadProgress(int account) {
        return getGhostModeSettingsForAccount(account).sendUploadProgress;
    }

    public static boolean isSendOfflinePacketAfterOnline(int account) {
        return getGhostModeSettingsForAccount(account).sendOfflinePacketAfterOnline;
    }

    public static boolean isMarkReadAfterSend(int account) {
        return getGhostModeSettingsForAccount(account).markReadAfterSend;
    }

    public static boolean isUseScheduledMessages(int account) {
        GhostModeSettings s = getGhostModeSettingsForAccount(account);
        return s.useScheduledMessages && s.isGhostModeActive();
    }

    public static boolean isSendReadMessagePacketsLocked(int account) {
        return getGhostModeSettingsForAccount(account).sendReadMessagePacketsLocked;
    }

    public static boolean isSendReadStoriesPacketsLocked(int account) {
        return getGhostModeSettingsForAccount(account).sendReadStoryPacketsLocked;
    }

    public static boolean isSendOnlinePacketsLocked(int account) {
        return getGhostModeSettingsForAccount(account).sendOnlinePacketsLocked;
    }

    public static boolean isSendUploadProgressLocked(int account) {
        return getGhostModeSettingsForAccount(account).sendUploadProgressLocked;
    }

    public static boolean isSendOfflinePacketAfterOnlineLocked(int account) {
        return getGhostModeSettingsForAccount(account).sendOfflinePacketAfterOnlineLocked;
    }


    public static void setGhostMode(int account, boolean enabled) {
        getGhostModeSettingsForAccount(account).setGhostMode(enabled);
    }

    public static void toggleGhostMode(int account) {
        setGhostMode(account, !isGhostModeActive(account));
    }

    public static void setSendReadMessagePackets(int account, boolean v) {
        GhostModeSettings s = getGhostModeSettingsForAccount(account);
        s.sendReadMessagePackets = v;
        s.save();
    }

    public static void setSendReadStoriesPackets(int account, boolean v) {
        GhostModeSettings s = getGhostModeSettingsForAccount(account);
        s.sendReadStoryPackets = v;
        s.save();
    }

    public static void setSendOnlinePackets(int account, boolean v) {
        GhostModeSettings s = getGhostModeSettingsForAccount(account);
        s.sendOnlinePackets = v;
        s.save();
    }

    public static void setSendUploadProgress(int account, boolean v) {
        GhostModeSettings s = getGhostModeSettingsForAccount(account);
        s.sendUploadProgress = v;
        s.save();
    }

    public static void setSendOfflinePacketAfterOnline(int account, boolean v) {
        GhostModeSettings s = getGhostModeSettingsForAccount(account);
        s.sendOfflinePacketAfterOnline = v;
        s.save();
    }

    public static void setMarkReadAfterSend(int account, boolean v) {
        GhostModeSettings s = getGhostModeSettingsForAccount(account);
        s.markReadAfterSend = v;
        s.save();
    }

    public static void setUseScheduledMessages(int account, boolean v) {
        GhostModeSettings s = getGhostModeSettingsForAccount(account);
        s.useScheduledMessages = v;
        s.save();
    }

    public static void setSendReadMessagePacketsLocked(int account, boolean v) {
        GhostModeSettings s = getGhostModeSettingsForAccount(account);
        s.sendReadMessagePacketsLocked = v;
        s.save();
    }

    public static void setSendReadStoriesPacketsLocked(int account, boolean v) {
        GhostModeSettings s = getGhostModeSettingsForAccount(account);
        s.sendReadStoryPacketsLocked = v;
        s.save();
    }

    public static void setSendOnlinePacketsLocked(int account, boolean v) {
        GhostModeSettings s = getGhostModeSettingsForAccount(account);
        s.sendOnlinePacketsLocked = v;
        s.save();
    }

    public static void setSendUploadProgressLocked(int account, boolean v) {
        GhostModeSettings s = getGhostModeSettingsForAccount(account);
        s.sendUploadProgressLocked = v;
        s.save();
    }

    public static void setSendOfflinePacketAfterOnlineLocked(int account, boolean v) {
        GhostModeSettings s = getGhostModeSettingsForAccount(account);
        s.sendOfflinePacketAfterOnlineLocked = v;
        s.save();
    }


    private static void migrateFromNekoConfig() {
        try {
            SharedPreferences nekoPrefs = ApplicationLoader.applicationContext
                    .getSharedPreferences("nkmrcfg", Context.MODE_PRIVATE);
            GhostModeSettings global = settings.get(-1L);
            global.sendReadMessagePackets = nekoPrefs.getBoolean("sendReadMessagePackets", true);
            global.sendReadStoryPackets = nekoPrefs.getBoolean("sendReadStoriesPackets", true);
            global.sendOnlinePackets = nekoPrefs.getBoolean("sendOnlinePackets", true);
            global.sendUploadProgress = nekoPrefs.getBoolean("sendUploadProgress", true);
            global.sendOfflinePacketAfterOnline = nekoPrefs.getBoolean("sendOfflinePacketAfterOnline", false);
            global.sendReadMessagePacketsLocked = nekoPrefs.getBoolean("sendReadMessagePacketsLocked", false);
            global.sendReadStoryPacketsLocked = nekoPrefs.getBoolean("sendReadStoriesPacketsLocked", false);
            global.sendOnlinePacketsLocked = nekoPrefs.getBoolean("sendOnlinePacketsLocked", false);
            global.sendUploadProgressLocked = nekoPrefs.getBoolean("sendUploadProgressLocked", false);
            global.sendOfflinePacketAfterOnlineLocked = nekoPrefs.getBoolean("sendOfflinePacketAfterOnlineLocked", false);
            global.markReadAfterSend = nekoPrefs.getBoolean("markReadAfterSend", true);
            global.useScheduledMessages = nekoPrefs.getBoolean("useScheduledMessages", false);
            global.save();
            FileLog.d("AyuGhostConfig: migrated settings from nkmrcfg");
        } catch (Exception e) {
            FileLog.e("AyuGhostConfig: migration failed", e);
        }
    }


    public static class GhostModeSettings {
        public boolean sendReadMessagePackets;
        public boolean sendReadMessagePacketsLocked;
        public boolean sendReadStoryPackets;
        public boolean sendReadStoryPacketsLocked;
        public boolean sendOnlinePackets;
        public boolean sendOnlinePacketsLocked;
        public boolean sendUploadProgress;
        public boolean sendUploadProgressLocked;
        public boolean sendOfflinePacketAfterOnline;
        public boolean sendOfflinePacketAfterOnlineLocked;
        public boolean markReadAfterSend;
        public boolean useScheduledMessages;
        public final long userId;

        public GhostModeSettings(long userId) {
            this.userId = userId;
            String s = suffix();
            sendReadMessagePackets = preferences.getBoolean("sendReadMessagePackets" + s, true);
            sendReadMessagePacketsLocked = preferences.getBoolean("sendReadMessagePacketsLocked" + s, false);
            sendReadStoryPackets = preferences.getBoolean("sendReadStoryPackets" + s, true);
            sendReadStoryPacketsLocked = preferences.getBoolean("sendReadStoryPacketsLocked" + s, false);
            sendOnlinePackets = preferences.getBoolean("sendOnlinePackets" + s, true);
            sendOnlinePacketsLocked = preferences.getBoolean("sendOnlinePacketsLocked" + s, false);
            sendUploadProgress = preferences.getBoolean("sendUploadProgress" + s, true);
            sendUploadProgressLocked = preferences.getBoolean("sendUploadProgressLocked" + s, false);
            sendOfflinePacketAfterOnline = preferences.getBoolean("sendOfflinePacketAfterOnline" + s, false);
            sendOfflinePacketAfterOnlineLocked = preferences.getBoolean("sendOfflinePacketAfterOnlineLocked" + s, false);
            markReadAfterSend = preferences.getBoolean("markReadAfterSend" + s, true);
            useScheduledMessages = preferences.getBoolean("useScheduledMessages" + s, false);
        }

        private String suffix() {
            return userId == -1 ? "" : "_" + userId;
        }

        public void save() {
            String s = suffix();
            editor.putBoolean("sendReadMessagePackets" + s, sendReadMessagePackets).apply();
            editor.putBoolean("sendReadMessagePacketsLocked" + s, sendReadMessagePacketsLocked).apply();
            editor.putBoolean("sendReadStoryPackets" + s, sendReadStoryPackets).apply();
            editor.putBoolean("sendReadStoryPacketsLocked" + s, sendReadStoryPacketsLocked).apply();
            editor.putBoolean("sendOnlinePackets" + s, sendOnlinePackets).apply();
            editor.putBoolean("sendOnlinePacketsLocked" + s, sendOnlinePacketsLocked).apply();
            editor.putBoolean("sendUploadProgress" + s, sendUploadProgress).apply();
            editor.putBoolean("sendUploadProgressLocked" + s, sendUploadProgressLocked).apply();
            editor.putBoolean("sendOfflinePacketAfterOnline" + s, sendOfflinePacketAfterOnline).apply();
            editor.putBoolean("sendOfflinePacketAfterOnlineLocked" + s, sendOfflinePacketAfterOnlineLocked).apply();
            editor.putBoolean("markReadAfterSend" + s, markReadAfterSend).apply();
            editor.putBoolean("useScheduledMessages" + s, useScheduledMessages).apply();
        }

        public boolean isGhostModeActive() {
            if (sendReadMessagePackets && !sendReadMessagePacketsLocked) return false;
            if (sendReadStoryPackets && !sendReadStoryPacketsLocked) return false;
            if (sendOnlinePackets && !sendOnlinePacketsLocked) return false;
            if (!sendUploadProgress || sendUploadProgressLocked) {
                return sendOfflinePacketAfterOnline || sendOfflinePacketAfterOnlineLocked;
            }
            return false;
        }

        public void setGhostMode(boolean enabled) {
            if (!sendReadMessagePacketsLocked) sendReadMessagePackets = !enabled;
            if (!sendReadStoryPacketsLocked) sendReadStoryPackets = !enabled;
            if (!sendOnlinePacketsLocked) sendOnlinePackets = !enabled;
            if (!sendUploadProgressLocked) sendUploadProgress = !enabled;
            if (!sendOfflinePacketAfterOnlineLocked) sendOfflinePacketAfterOnline = enabled;
            save();
        }

        public int getSelectedCount() {
            int c = 0;
            if (!sendReadMessagePackets) c++;
            if (!sendReadStoryPackets) c++;
            if (!sendOnlinePackets) c++;
            if (!sendUploadProgress) c++;
            if (sendOfflinePacketAfterOnline) c++;
            return c;
        }

        public int getLockedCount() {
            int c = 0;
            if (sendReadMessagePacketsLocked) c++;
            if (sendReadStoryPacketsLocked) c++;
            if (sendOnlinePacketsLocked) c++;
            if (sendUploadProgressLocked) c++;
            if (sendOfflinePacketAfterOnlineLocked) c++;
            return c;
        }

        public void postChangedNotification(int account) {
            NotificationCenter.getInstance(account)
                    .postNotificationName(NotificationCenter.mainUserInfoChanged);
        }
    }
}
