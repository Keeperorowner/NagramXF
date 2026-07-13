/*
 * This is the source code of AyuGram for Android.
 *
 * We do not and cannot prevent the use of our code,
 * but be respectful and credit the original author.
 *
 * Copyright @Radolyn, 2023
 */

package com.radolyn.ayugram.database;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.annotations.Expose;
import com.google.gson.reflect.TypeToken;

import com.radolyn.ayugram.database.dao.RegexFilterDao;
import com.radolyn.ayugram.database.entities.RegexFilter;
import com.radolyn.ayugram.database.entities.RegexFilterGlobalExclusion;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import xyz.nextalone.nagram.NaConfig;

/**
 * One-time migration of the legacy JSON-in-SharedPreferences filter storage
 * ({@code NaConfig.regexFiltersData}, {@code regexChatFiltersData},
 * {@code regexFiltersExcludedEntriesData}) into the Room tables
 * ({@code RegexFilter}, {@code RegexFilterGlobalExclusion}).
 * <p>
 * Runs at most once per install, gated by a dedicated SharedPreferences boolean so it
 * survives across NaConfig resets. The legacy prefs are intentionally left in place
 * (not cleared) so users can roll back; they simply become unread after the migration.
 * <p>
 * Idempotent: if the Room tables already contain rows, the migration is a no-op —
 * this guards against the case where the user re-imports an old backup after the
 * migration flag was already set.
 */
public final class FilterPrefsMigrator {

    private static final String MIGRATION_PREFS = "ayu_filter_migration";
    private static final String KEY_DONE = "jsonToRoomDone";
    /** Bump to force re-migration (e.g. after a bug fix). Old markers are ignored. */
    private static final int MIGRATION_VERSION = 2;
    private static final String KEY_VERSION = "version";

    private FilterPrefsMigrator() {
    }

    public static void runIfNeeded() {
        Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null) {
            return;
        }
        android.content.SharedPreferences prefs = ctx.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE);

        // Skip only if done with a current version; stale markers force re-migration.
        int savedVersion = prefs.getInt(KEY_VERSION, 0);
        if (savedVersion >= MIGRATION_VERSION && prefs.getBoolean(KEY_DONE, false)) {
            return;
        }
        try {
            // NaConfig must be initialized so legacy JSON getters return real data, not defaults.
            try {
                NaConfig.INSTANCE.init();
            } catch (Exception e) {
                FileLog.e("FilterPrefsMigrator: NaConfig.init failed", e);
            }

            RegexFilterDao dao = AyuData.getRegexFilterDao();
            if (dao == null) {
                return;
            }

            String sharedJson = NaConfig.INSTANCE.getRegexFiltersData().String();
            String chatJson = NaConfig.INSTANCE.getRegexChatFiltersData().String();
            String exclusionsJson = NaConfig.INSTANCE.getRegexFiltersExcludedEntriesData().String();

            // If Room already has data, never delete it. The new version writes filters
            // exclusively to Room, so the legacy SharedPreferences (regexFiltersData etc.)
            // become stale after the first migration. Re-importing from stale prefs would
            // either restore long-deleted filters or — if the prefs were never populated
            // (default "[]") — wipe everything. Just update the marker and move on.
            int existingCount = dao.getCount();
            if (existingCount > 0) {
                prefs.edit().putBoolean(KEY_DONE, true).putInt(KEY_VERSION, MIGRATION_VERSION).apply();
                return;
            }

            Gson gson = new Gson();

            // 1. Shared filters (dialogId == null)
            ArrayList<LegacyFilterModel> shared = readList(gson, sharedJson, new TypeToken<ArrayList<LegacyFilterModel>>(){}.getType());
            if (shared != null) {
                for (LegacyFilterModel m : shared) {
                    if (m == null) continue;
                    RegexFilter row = new RegexFilter();
                    row.id = !isEmpty(m.id) ? m.id : UUID.randomUUID().toString();
                    row.text = m.regex;
                    row.dialogId = null;
                    row.enabled = m.enabled;
                    row.caseInsensitive = m.caseInsensitive;
                    row.reversed = m.reversed;
                    dao.insert(row);
                }
            }

            // 2. Chat-level filters
            ArrayList<LegacyChatFilterEntry> chats = readList(gson, chatJson, new TypeToken<ArrayList<LegacyChatFilterEntry>>(){}.getType());
            if (chats != null) {
                for (LegacyChatFilterEntry entry : chats) {
                    if (entry == null || entry.filters == null) continue;
                    for (LegacyFilterModel m : entry.filters) {
                        if (m == null) continue;
                        RegexFilter row = new RegexFilter();
                        row.id = !isEmpty(m.id) ? m.id : UUID.randomUUID().toString();
                        row.text = m.regex;
                        row.dialogId = entry.dialogId;
                        row.enabled = m.enabled;
                        row.caseInsensitive = m.caseInsensitive;
                        row.reversed = m.reversed;
                        dao.insert(row);
                    }
                }
            }

            // 3. Exclusions
            ArrayList<LegacyExcludedFilterEntry> exclusions = readList(gson, exclusionsJson, new TypeToken<ArrayList<LegacyExcludedFilterEntry>>(){}.getType());
            if (exclusions != null) {
                for (LegacyExcludedFilterEntry e : exclusions) {
                    if (e == null || e.dialogId == 0L || isEmpty(e.filterId)) continue;
                    RegexFilterGlobalExclusion row = new RegexFilterGlobalExclusion();
                    row.dialogId = e.dialogId;
                    row.filterId = e.filterId;
                    dao.insertExclusion(row);
                }
            }

            prefs.edit().putBoolean(KEY_DONE, true).putInt(KEY_VERSION, MIGRATION_VERSION).apply();
            FileLog.d("FilterPrefsMigrator: legacy JSON filters imported into Room");
        } catch (Exception e) {
            FileLog.e("FilterPrefsMigrator: migration failed", e);
        }
    }

    private static <T> ArrayList<T> readList(Gson gson, String json, Type type) {
        if (isEmpty(json)) {
            return null;
        }
        try {
            ArrayList<T> list = gson.fromJson(json, type);
            return list;
        } catch (Exception e) {
            FileLog.e("FilterPrefsMigrator.readList", e);
            return null;
        }
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    // --- Legacy shapes (mirror AyuFilter.FilterModel/ChatFilterEntry/ExcludedFilterEntry as they were in prefs) ---

    private static class LegacyFilterModel {
        @Expose
        public String id;
        @Expose
        public String regex;
        @Expose
        public boolean caseInsensitive;
        @Expose
        public boolean enabled = true;
        @Expose
        public boolean reversed;
        // Legacy migration-only fields (ignored on read)
        public ArrayList<Long> enabledGroups;
        public ArrayList<Long> disabledGroups;
    }

    private static class LegacyChatFilterEntry {
        @Expose
        public long dialogId;
        @Expose
        public ArrayList<LegacyFilterModel> filters;
    }

    private static class LegacyExcludedFilterEntry {
        @Expose
        public long dialogId;
        @Expose
        public String filterId;
    }
}
