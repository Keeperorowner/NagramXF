
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

public final class FilterPrefsMigrator {

    private static final String MIGRATION_PREFS = "ayu_filter_migration";
    private static final String KEY_DONE = "jsonToRoomDone";
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

        int savedVersion = prefs.getInt(KEY_VERSION, 0);
        if (savedVersion >= MIGRATION_VERSION && prefs.getBoolean(KEY_DONE, false)) {
            return;
        }
        try {
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

            int existingCount = dao.getCount();
            if (existingCount > 0) {
                prefs.edit().putBoolean(KEY_DONE, true).putInt(KEY_VERSION, MIGRATION_VERSION).apply();
                return;
            }

            Gson gson = new Gson();

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
