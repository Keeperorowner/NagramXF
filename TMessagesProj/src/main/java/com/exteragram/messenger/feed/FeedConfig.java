package com.exteragram.messenger.feed;

import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Per-account feed configuration: archived-chats inclusion and the set of
 * excluded channel dialog ids. Every mutation bumps a generation counter so
 * cached channel enumerations can detect staleness.
 */
public final class FeedConfig {

    private static final FeedConfig[] instances = new FeedConfig[16];
    private static final Object[] lockObjects = new Object[16];

    static {
        for (int i = 0; i < 16; i++) {
            lockObjects[i] = new Object();
        }
    }

    private volatile Set<Long> excludedChannels;
    private volatile int generation;
    private volatile boolean includeArchived;
    private final SharedPreferences preferences;

    public static FeedConfig getInstance(int num) {
        FeedConfig config = instances[num];
        if (config != null) {
            return config;
        }
        synchronized (lockObjects[num]) {
            config = instances[num];
            if (config == null) {
                config = new FeedConfig(num);
                instances[num] = config;
            }
        }
        return config;
    }

    private FeedConfig(int account) {
        preferences = ApplicationLoader.applicationContext.getSharedPreferences("feedconfig" + account, 0);
        includeArchived = preferences.getBoolean("includeArchived", false);
        Set<String> stored = preferences.getStringSet("excludedChannels", null);
        if (stored != null) {
            Set<Long> parsed = new HashSet<>();
            for (String s : stored) {
                try {
                    parsed.add(Long.parseLong(s));
                } catch (NumberFormatException ignored) {
                }
            }
            excludedChannels = parsed;
        } else {
            excludedChannels = new HashSet<>();
        }
    }

    public boolean getIncludeArchived() {
        return includeArchived;
    }

    public synchronized void setIncludeArchived(boolean value) {
        if (includeArchived == value) {
            return;
        }
        includeArchived = value;
        generation++;
        preferences.edit().putBoolean("includeArchived", value).apply();
    }

    public boolean isExcluded(long dialogId) {
        return excludedChannels.contains(dialogId);
    }

    public synchronized void setExcluded(long dialogId, boolean excluded) {
        HashSet<Long> updated = new HashSet<>(excludedChannels);
        boolean changed = excluded ? updated.add(dialogId) : updated.remove(dialogId);
        if (changed) {
            applyExcluded(updated);
        }
    }

    public Set<Long> getExcludedSnapshot() {
        return excludedChannels;
    }

    public synchronized void removeExcluded(Set<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        HashSet<Long> updated = new HashSet<>(excludedChannels);
        if (updated.removeAll(ids)) {
            applyExcluded(updated);
        }
    }

    public synchronized void clearExcluded() {
        if (excludedChannels.isEmpty()) {
            return;
        }
        applyExcluded(new HashSet<>());
    }

    public synchronized void excludeAll(Collection<Long> ids) {
        HashSet<Long> updated = new HashSet<>(excludedChannels);
        if (updated.addAll(ids)) {
            applyExcluded(updated);
        }
    }

    private void applyExcluded(Set<Long> updated) {
        excludedChannels = updated;
        generation++;
        SharedPreferences.Editor editor = preferences.edit();
        HashSet<String> stored = new HashSet<>();
        for (Long id : updated) {
            stored.add(String.valueOf(id));
        }
        editor.putStringSet("excludedChannels", stored);
        editor.apply();
    }

    public int getGeneration() {
        return generation;
    }
}
