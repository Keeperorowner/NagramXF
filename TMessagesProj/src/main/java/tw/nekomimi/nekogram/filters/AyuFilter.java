package tw.nekomimi.nekogram.filters;

import android.text.Spannable;
import android.text.Spanned;
import android.text.TextUtils;

import com.google.gson.Gson;
import com.google.gson.annotations.Expose;
import com.radolyn.ayugram.database.AyuData;
import com.radolyn.ayugram.database.dao.RegexFilterDao;
import com.radolyn.ayugram.database.entities.RegexFilter;
import com.radolyn.ayugram.database.entities.RegexFilterGlobalExclusion;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.helpers.MessageHelper;
import xyz.nextalone.nagram.NaConfig;

/**
 * Regex message-filtering facade, now backed by Room ({@link RegexFilterDao}).
 * <p>
 * Public API and the {@link FilterModel}/{@link ChatFilterEntry}/{@link ExcludedFilterEntry}/
 * {@link CustomFilteredUser} data classes are preserved verbatim so the dozens of
 * activities/popups that reference them continue to compile. Under the hood, the
 * shared/chat filter rows and per-dialog exclusions live in the {@code RegexFilter} and
 * {@code RegexFilterGlobalExclusion} tables; reads go through an in-memory cache that is
 * rebuilt on demand and invalidated on every write.
 * <p>
 * {@code blockedChannels}, {@code customFilteredUsers}, {@code customFilteredUsersData},
 * and the legacy {@code excludedDialogs} list remain in SharedPreferences (they are
 * NagramXF-specific and do not map to ayuGram's filter tables).
 */
public class AyuFilter {
    private static final Object cacheLock = new Object();
    private static volatile ArrayList<FilterModel> filterModels;
    private static volatile ArrayList<ChatFilterEntry> chatFilterEntries;
    private static volatile HashMap<Long, HashSet<String>> excludedSharedFilterIdsByDialog;
    private static volatile HashSet<Long> blockedChannels;
    private static volatile HashSet<Long> customFilteredUsers;
    private static volatile HashMap<Long, CustomFilteredUser> customFilteredUsersData;

    // --- Shared / chat filters (Room-backed) ---

    public static ArrayList<FilterModel> getRegexFilters() {
        if (filterModels == null) {
            synchronized (cacheLock) {
                if (filterModels == null) {
                    filterModels = loadSharedFilters();
                }
            }
        }
        return filterModels;
    }

    private static ArrayList<FilterModel> loadSharedFilters() {
        ArrayList<FilterModel> out = new ArrayList<>();
        RegexFilterDao dao = AyuData.getRegexFilterDao();
        if (dao != null) {
            try {
                List<RegexFilter> rows = dao.getShared();
                for (RegexFilter row : rows) {
                    FilterModel m = new FilterModel();
                    m.id = row.id != null ? row.id : UUID.randomUUID().toString();
                    m.regex = row.text;
                    m.caseInsensitive = row.caseInsensitive;
                    m.reversed = row.reversed;
                    m.enabled = row.enabled;
                    m.buildPattern();
                    out.add(m);
                }
            } catch (Exception e) {
                FileLog.e("AyuFilter.loadSharedFilters", e);
            }
        }
        if (out.isEmpty()) {
            ArrayList<FilterModel> legacy = loadSharedFiltersFromPrefs();
            if (legacy != null && !legacy.isEmpty()) {
                saveFilter(legacy);
                return legacy;
            }
        }
        return out;
    }

    private static ArrayList<FilterModel> loadSharedFiltersFromPrefs() {
        try {
            String str = NaConfig.INSTANCE.getRegexFiltersData().String();
            if (TextUtils.isEmpty(str) || "[]".equals(str)) {
                return null;
            }
            FilterModel[] arr = new Gson().fromJson(str, FilterModel[].class);
            if (arr == null || arr.length == 0) {
                return null;
            }
            ArrayList<FilterModel> list = new ArrayList<>();
            for (FilterModel f : arr) {
                if (f == null) continue;
                f.ensureId();
                f.buildPattern();
                list.add(f);
            }
            return list;
        } catch (Exception e) {
            FileLog.e("AyuFilter.loadSharedFiltersFromPrefs", e);
            return null;
        }
    }

    public static void addFilter(String text, boolean caseInsensitive) {
        addFilter(text, caseInsensitive, false);
    }

    public static void addFilter(String text, boolean caseInsensitive, boolean reversed) {
        var list = new ArrayList<>(getRegexFilters());
        FilterModel filterModel = new FilterModel();
        filterModel.regex = text;
        filterModel.caseInsensitive = caseInsensitive;
        filterModel.reversed = reversed;
        filterModel.enabled = true;
        filterModel.buildPattern();
        list.add(0, filterModel);
        saveFilter(list);
    }

    public static void editFilter(int filterIdx, String text, boolean caseInsensitive) {
        editFilter(filterIdx, text, caseInsensitive, false);
    }

    public static void editFilter(int filterIdx, String text, boolean caseInsensitive, boolean reversed) {
        var list = getRegexFilters();
        if (filterIdx < 0 || filterIdx >= list.size()) {
            return;
        }
        FilterModel filterModel = list.get(filterIdx);
        filterModel.regex = text;
        filterModel.caseInsensitive = caseInsensitive;
        filterModel.reversed = reversed;
        filterModel.buildPattern();
        saveFilter(list);
    }

    public static void saveFilter(ArrayList<FilterModel> filterModels1) {
        RegexFilterDao dao = AyuData.getRegexFilterDao();
        if (dao == null) {
            return;
        }
        try {
            dao.deleteAllShared();
            for (FilterModel m : filterModels1) {
                m.ensureId();
                m.buildPattern();
                dao.insert(toRow(m, null));
            }
        } catch (Exception e) {
            FileLog.e("AyuFilter.saveFilter", e);
        }
        NaConfig.INSTANCE.getRegexFiltersData().setConfigString(new Gson().toJson(filterModels1));
        rebuildCache();
    }

    public static void removeFilter(int filterIdx) {
        var list = getRegexFilters();
        if (filterIdx < 0 || filterIdx >= list.size()) {
            return;
        }
        FilterModel removed = list.remove(filterIdx);
        if (removed != null && !TextUtils.isEmpty(removed.id)) {
            removeExcludedSharedFilterEntries(removed.id);
        }
        saveFilter(list);
    }

    public static CharSequence getMessageText(MessageObject selectedObject, MessageObject.GroupedMessages selectedObjectGroup) {
        if (selectedObject == null) {
            return null;
        }
        // Follow ayuGram behavior: do not skip stickers/emoji; getMessageFilterMatchText always
        // appends a <type>N</type> tag so reversed expressions can still invert the "no-match" result.
        CharSequence messageText = MessageHelper.getMessageFilterMatchText(selectedObject, selectedObjectGroup);
        if (TextUtils.isEmpty(messageText)) {
            messageText = null;
        }
        if (selectedObject.translated || selectedObject.isRestrictedMessage) {
            messageText = null;
        }
        return messageText;
    }

    public static void rebuildCache() {
        synchronized (cacheLock) {
            filterModels = null;
            chatFilterEntries = null;
            excludedSharedFilterIdsByDialog = null;
            AyuFilterCache.clearAll();
        }
        AndroidUtilities.runOnUIThread(() -> {
            NotificationCenter.getInstance(UserConfig.selectedAccount).postNotificationName(NotificationCenter.regexFiltersUpdated);
        });
    }

    public static void invalidateFilteredCache() {
        synchronized (cacheLock) {
            AyuFilterCache.clearAll();
        }
    }

    private static boolean isFilterMatch(FilterModel filter, CharSequence text) {
        if (filter == null || !filter.enabled || filter.pattern == null || TextUtils.isEmpty(text)) {
            return false;
        }
        // Reversed expressions match "text does NOT contain pattern". Under mask mode that
        // would mask/hide messages lacking the keyword, which is nonsensical, so disable
        // reversed filters entirely while masking.
        if (filter.reversed && NaConfig.INSTANCE.getRegexFiltersMaskMessages().Bool()) {
            return false;
        }
        boolean matched = filter.pattern.matcher(text).find();
        return filter.reversed ? !matched : matched;
    }

    private static boolean isFilteredInternal(CharSequence text, long dialogId) {
        if (chatFilterEntries != null) {
            for (var entry : chatFilterEntries) {
                if (entry.dialogId == dialogId) {
                    if (entry.filters != null) {
                        for (var pattern : entry.filters) {
                            if (isFilterMatch(pattern, text)) {
                                return true;
                            }
                        }
                    }
                    break;
                }
            }
        }

        boolean isPrivateDialog = dialogId > 0;
        if (isPrivateDialog && !NaConfig.INSTANCE.getRegexFiltersEnableInChats().Bool()) {
            return false;
        }

        if (filterModels != null) {
            HashSet<String> excludedFilterIds = getExcludedSharedFilterIds(dialogId);
            for (var pattern : filterModels) {
                if (!TextUtils.isEmpty(pattern.id) && excludedFilterIds.contains(pattern.id)) {
                    continue;
                }
                if (isFilterMatch(pattern, text)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static void collectMatchedRanges(ArrayList<int[]> ranges, FilterModel filter, CharSequence text) {
        if (filter == null || !filter.enabled || filter.pattern == null || filter.reversed) {
            return;
        }
        try {
            var matcher = filter.pattern.matcher(text);
            int length = text.length();
            while (matcher.find()) {
                int start = matcher.start();
                int end = matcher.end();
                if (start >= 0 && end > start && end <= length) {
                    ranges.add(new int[]{start, end});
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static ArrayList<int[]> findFilteredRanges(CharSequence text, long dialogId) {
        ArrayList<int[]> ranges = new ArrayList<>();
        if (TextUtils.isEmpty(text)) {
            return ranges;
        }
        if (chatFilterEntries != null) {
            for (var entry : chatFilterEntries) {
                if (entry.dialogId == dialogId) {
                    if (entry.filters != null) {
                        for (var filter : entry.filters) {
                            collectMatchedRanges(ranges, filter, text);
                        }
                    }
                    break;
                }
            }
        }

        boolean isPrivateDialog = dialogId > 0;
        if (isPrivateDialog && !NaConfig.INSTANCE.getRegexFiltersEnableInChats().Bool()) {
            return mergeRanges(ranges);
        }

        if (filterModels != null) {
            HashSet<String> excludedFilterIds = getExcludedSharedFilterIds(dialogId);
            for (var filter : filterModels) {
                if (!TextUtils.isEmpty(filter.id) && excludedFilterIds.contains(filter.id)) {
                    continue;
                }
                collectMatchedRanges(ranges, filter, text);
            }
        }
        return mergeRanges(ranges);
    }

    private static ArrayList<int[]> mergeRanges(ArrayList<int[]> ranges) {
        if (ranges.isEmpty()) {
            return ranges;
        }
        Collections.sort(ranges, (a, b) -> Integer.compare(a[0], b[0]));
        ArrayList<int[]> merged = new ArrayList<>();
        for (int[] range : ranges) {
            if (merged.isEmpty()) {
                merged.add(new int[]{range[0], range[1]});
                continue;
            }
            int[] last = merged.get(merged.size() - 1);
            if (range[0] <= last[1]) {
                last[1] = Math.max(last[1], range[1]);
            } else {
                merged.add(new int[]{range[0], range[1]});
            }
        }
        return merged;
    }

    public static boolean isFiltered(MessageObject msg, MessageObject.GroupedMessages group) {
        if (!NaConfig.INSTANCE.getRegexFiltersEnabled().Bool()) {
            return false;
        }

        if (msg == null || msg.isOutOwner() || msg.isOut()) {
            // Align with ayuGram: also skip channel posts the user authored (out=true, post=true)
            // which pass isOutOwner()==false but shouldn't be filtered as incoming content.
            return false;
        }

        // Per-session bypass toggled via ChatActivity's "show filtered" action. Checked BEFORE
        // the cache so the cache keeps the real filter verdict and survives toggling back.
        if (msg.skipAyuFiltering) {
            return false;
        }

        long dialogId = msg.getDialogId();
        if (isDialogExcluded(dialogId)) {
            return false;
        }

        Boolean cached = AyuFilterCache.get(dialogId, msg, group);
        if (cached != null) {
            return cached;
        }

        var text = getMessageText(msg, group);
        if (TextUtils.isEmpty(text)) {
            return false;
        }

        if (filterModels == null) {
            getRegexFilters();
        }
        if (chatFilterEntries == null) {
            getChatFilterEntries();
        }

        boolean result = isFilteredInternal(text, dialogId);
        // Only cache when we have full context. For grouped messages checked without
        // group context (e.g. from DialogCell), skip caching to prevent stale per-message
        // entries from overriding the correct group-aware result in ChatActivity.
        if (group != null || msg.getGroupId() == 0) {
            AyuFilterCache.put(dialogId, msg, group, result);
        }

        return result;
    }

    public static boolean shouldMaskFilteredMessages() {
        return NaConfig.INSTANCE.getRegexFiltersEnabled().Bool() && NaConfig.INSTANCE.getRegexFiltersMaskMessages().Bool();
    }

    public static boolean shouldHideOnlyMatched() {
        return NaConfig.INSTANCE.getRegexFiltersEnabled().Bool()
                && NaConfig.INSTANCE.getRegexFiltersMaskMessages().Bool()
                && NaConfig.INSTANCE.getRegexFiltersHideOnlyMatched().Bool();
    }

    public static boolean shouldHideFilteredMessages() {
        return NaConfig.INSTANCE.getRegexFiltersEnabled().Bool() && !NaConfig.INSTANCE.getRegexFiltersMaskMessages().Bool();
    }

    public static boolean shouldMaskIgnoredBlockedMessages() {
        return NaConfig.INSTANCE.getRegexFiltersEnabled().Bool()
            && NekoConfig.ignoreBlocked.Bool()
            && NaConfig.INSTANCE.getRegexFiltersMaskMessages().Bool();
    }

    public static boolean shouldHideIgnoredBlockedMessages() {
        return NaConfig.INSTANCE.getRegexFiltersEnabled().Bool()
            && NekoConfig.ignoreBlocked.Bool()
            && !NaConfig.INSTANCE.getRegexFiltersMaskMessages().Bool();
    }

    public static boolean shouldHideFilteredMessage(MessageObject msg, MessageObject.GroupedMessages group) {
        return shouldHideFilteredMessages() && isFiltered(msg, group);
    }

    public static boolean shouldMaskFilteredMessage(MessageObject msg, MessageObject.GroupedMessages group) {
        if (shouldHideOnlyMatched()) {
            return false;
        }
        return shouldMaskFilteredMessages() && isFiltered(msg, group);
    }

    public static boolean shouldMaskMessage(MessageObject msg, MessageObject.GroupedMessages group) {
        return shouldMaskFilteredMessage(msg, group) || (shouldMaskIgnoredBlockedMessages() && isIgnoredBlockedMessage(msg));
    }

    public static ArrayList<TLRPC.MessageEntity> addSpoilerEntities(MessageObject msg, ArrayList<TLRPC.MessageEntity> original, CharSequence text) {
        if (msg == null || TextUtils.isEmpty(text)) {
            return original;
        }

        // "Hide only matched content": put spoilers only on the regex-matched ranges
        // instead of masking the whole message. These spoilers stay revealable, so we
        // intentionally bypass the whole-message mask path (shouldMaskMessage).
        if (shouldHideOnlyMatched() && isFiltered(msg, null)) {
            ArrayList<int[]> ranges = findFilteredRanges(text, msg.getDialogId());
            if (!ranges.isEmpty()) {
                ArrayList<TLRPC.MessageEntity> result = original != null ? new ArrayList<>(original) : new ArrayList<>();
                for (int[] range : ranges) {
                    TLRPC.TL_messageEntitySpoiler spoiler = new TLRPC.TL_messageEntitySpoiler();
                    spoiler.offset = range[0];
                    spoiler.length = range[1] - range[0];
                    result.add(spoiler);
                }
                return result;
            }
            // No concrete ranges (reversed/type-tag-only match): fall back to whole-message mask.
        }

        if (!shouldMaskMessage(msg, null)) {
            return original;
        }

        ArrayList<TLRPC.MessageEntity> result = original != null ? new ArrayList<>(original) : new ArrayList<>();
        for (int i = 0, size = result.size(); i < size; i++) {
            TLRPC.MessageEntity entity = result.get(i);
            if (entity instanceof TLRPC.TL_messageEntitySpoiler && entity.offset == 0 && entity.length >= text.length()) {
                return result;
            }
        }
        TLRPC.TL_messageEntitySpoiler spoiler = new TLRPC.TL_messageEntitySpoiler();
        spoiler.offset = 0;
        spoiler.length = text.length();
        result.add(spoiler);
        return result;
    }

    public static void syncMaskedSpoilerRevealState(MessageObject msg, MessageObject.GroupedMessages group) {
        if (msg != null && shouldMaskMessage(msg, group)) {
            msg.isSpoilersRevealed = false;
        }
    }

    public static void syncMaskMarkerSpan(Spannable text, MessageObject msg, MessageObject.GroupedMessages group) {
        if (text == null) {
            return;
        }
        FilterMaskSpan[] spans = text.getSpans(0, text.length(), FilterMaskSpan.class);
        for (int i = 0; i < spans.length; i++) {
            text.removeSpan(spans[i]);
        }
        if (shouldMaskMessage(msg, group) && text.length() > 0) {
            text.setSpan(new FilterMaskSpan(), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    public static boolean hasMaskedFilterSpan(CharSequence text) {
        if (!(text instanceof Spanned spanned) || spanned.length() == 0) {
            return false;
        }
        return spanned.getSpans(0, spanned.length(), FilterMaskSpan.class).length > 0;
    }

    // Rebuild the FilterMaskSpan on a message's cached text buffers. Called when toggling
    // "Show Filtered" in ChatActivity so that already-rendered messages (and their cached
    // reply previews on adjacent non-filtered messages) drop the spoiler placeholder and
    // repaint with the real underlying text, instead of staying "ghosted".
    public static void refreshMaskStateForMessage(MessageObject msg) {
        if (msg == null) {
            return;
        }
        if (msg.messageText instanceof Spannable) {
            syncMaskMarkerSpan((Spannable) msg.messageText, msg, null);
        }
        if (msg.messageTextForReply instanceof Spannable) {
            syncMaskMarkerSpan((Spannable) msg.messageTextForReply, msg, null);
        }
        if (msg.caption instanceof Spannable) {
            syncMaskMarkerSpan((Spannable) msg.caption, msg, null);
        }
        syncMaskedSpoilerRevealState(msg, null);
    }

    public static boolean isIgnoredBlockedMessage(MessageObject msg) {
        if (msg == null || msg.isOutOwner() || msg.isOut() || !NekoConfig.ignoreBlocked.Bool()) {
            return false;
        }
        if (isBlockedPeer(msg.currentAccount, msg.getFromChatId())) {
            return true;
        }
        return msg.replyMessageObject != null && isBlockedPeer(msg.currentAccount, msg.replyMessageObject.getFromChatId());
    }

    private static boolean isBlockedPeer(int currentAccount, long peerId) {
        if (peerId == 0L) {
            return false;
        }
        return MessagesController.getInstance(currentAccount).blockePeers.indexOfKey(peerId) >= 0
            || isCustomFilteredPeer(peerId)
            || isBlockedChannel(peerId);
    }

    private static class FilterMaskSpan {
    }

    public static ArrayList<ChatFilterEntry> getChatFilterEntries() {
        if (chatFilterEntries == null) {
            synchronized (cacheLock) {
                if (chatFilterEntries == null) {
                    chatFilterEntries = loadChatFilterEntries();
                }
            }
        }
        return chatFilterEntries;
    }

    private static ArrayList<ChatFilterEntry> loadChatFilterEntries() {
        ArrayList<ChatFilterEntry> out = new ArrayList<>();
        RegexFilterDao dao = AyuData.getRegexFilterDao();
        if (dao != null) {
            try {
                // Group chat-level rows by dialogId. Each non-null dialogId row is a chat-specific filter.
                HashMap<Long, ChatFilterEntry> byDialog = new HashMap<>();
                List<RegexFilter> all = dao.getAll();
                for (RegexFilter row : all) {
                    if (row.dialogId == null) {
                        continue;
                    }
                    long did = row.dialogId;
                    ChatFilterEntry entry = byDialog.get(did);
                    if (entry == null) {
                        entry = new ChatFilterEntry();
                        entry.dialogId = did;
                        entry.filters = new ArrayList<>();
                        byDialog.put(did, entry);
                    }
                    FilterModel m = new FilterModel();
                    m.id = row.id != null ? row.id : UUID.randomUUID().toString();
                    m.regex = row.text;
                    m.caseInsensitive = row.caseInsensitive;
                    m.reversed = row.reversed;
                    m.enabled = row.enabled;
                    m.buildPattern();
                    entry.filters.add(m);
                }
                out.addAll(byDialog.values());
            } catch (Exception e) {
                FileLog.e("AyuFilter.loadChatFilterEntries", e);
            }
        }
        if (out.isEmpty()) {
            ArrayList<ChatFilterEntry> legacy = loadChatFilterEntriesFromPrefs();
            if (legacy != null && !legacy.isEmpty()) {
                saveChatFilterEntries(legacy);
                return legacy;
            }
        }
        return out;
    }

    private static ArrayList<ChatFilterEntry> loadChatFilterEntriesFromPrefs() {
        try {
            String str = NaConfig.INSTANCE.getRegexChatFiltersData().String();
            if (TextUtils.isEmpty(str) || "[]".equals(str)) {
                return null;
            }
            ChatFilterEntry[] arr = new Gson().fromJson(str, ChatFilterEntry[].class);
            if (arr == null || arr.length == 0) {
                return null;
            }
            ArrayList<ChatFilterEntry> list = new ArrayList<>();
            for (ChatFilterEntry entry : arr) {
                if (entry == null || entry.filters == null) continue;
                for (FilterModel f : entry.filters) {
                    if (f != null) {
                        f.ensureId();
                        f.buildPattern();
                    }
                }
                list.add(entry);
            }
            return list;
        } catch (Exception e) {
            FileLog.e("AyuFilter.loadChatFilterEntriesFromPrefs", e);
            return null;
        }
    }

    public static void saveChatFilterEntries(ArrayList<ChatFilterEntry> entries) {
        RegexFilterDao dao = AyuData.getRegexFilterDao();
        if (dao == null) {
            return;
        }
        try {
            // Delete all chat-level rows (dialogId NOT NULL) then re-insert from the list.
            // Shared rows (dialogId IS NULL) are untouched.
            List<RegexFilter> shared = dao.getShared();
            dao.deleteAllFilters();
            for (RegexFilter s : shared) {
                dao.insert(s);
            }
            for (ChatFilterEntry entry : entries) {
                if (entry == null || entry.filters == null) continue;
                for (FilterModel m : entry.filters) {
                    m.ensureId();
                    m.buildPattern();
                    dao.insert(toRow(m, entry.dialogId));
                }
            }
        } catch (Exception e) {
            FileLog.e("AyuFilter.saveChatFilterEntries", e);
        }
        NaConfig.INSTANCE.getRegexChatFiltersData().setConfigString(new Gson().toJson(entries));
        rebuildCache();
    }

    public static ArrayList<FilterModel> getChatFiltersForDialog(long dialogId) {
        var entries = getChatFilterEntries();
        for (var e : entries) {
            if (e.dialogId == dialogId) {
                return e.filters != null ? e.filters : new ArrayList<>();
            }
        }
        return new ArrayList<>();
    }

    public static void addChatFilter(long dialogId, String text, boolean caseInsensitive) {
        addChatFilter(dialogId, text, caseInsensitive, false);
    }

    public static void addChatFilter(long dialogId, String text, boolean caseInsensitive, boolean reversed) {
        var entries = new ArrayList<>(getChatFilterEntries());
        ChatFilterEntry target = null;
        for (var e : entries) {
            if (e.dialogId == dialogId) {
                target = e;
                break;
            }
        }
        if (target == null) {
            target = new ChatFilterEntry();
            target.dialogId = dialogId;
            target.filters = new ArrayList<>();
            entries.add(target);
        }
        FilterModel m = new FilterModel();
        m.regex = text;
        m.caseInsensitive = caseInsensitive;
        m.reversed = reversed;
        m.enabled = true;
        m.buildPattern();
        target.filters.add(0, m);
        saveChatFilterEntries(entries);
    }

    public static void editChatFilter(long dialogId, int filterIdx, String text, boolean caseInsensitive) {
        editChatFilter(dialogId, filterIdx, text, caseInsensitive, false);
    }

    public static void editChatFilter(long dialogId, int filterIdx, String text, boolean caseInsensitive, boolean reversed) {
        var entries = getChatFilterEntries();
        ChatFilterEntry target = null;
        for (var e : entries) {
            if (e.dialogId == dialogId) {
                target = e;
                break;
            }
        }
        if (target == null || target.filters == null) return;
        if (filterIdx < 0 || filterIdx >= target.filters.size()) return;
        FilterModel m = target.filters.get(filterIdx);
        m.regex = text;
        m.caseInsensitive = caseInsensitive;
        m.reversed = reversed;
        m.buildPattern();
        saveChatFilterEntries(entries);
    }

    public static void removeChatFilter(long dialogId, int filterIdx) {
        var entries = getChatFilterEntries();
        ChatFilterEntry target = null;
        for (var e : entries) {
            if (e.dialogId == dialogId) {
                target = e;
                break;
            }
        }
        if (target == null || target.filters == null) return;
        if (filterIdx < 0 || filterIdx >= target.filters.size()) return;
        target.filters.remove(filterIdx);
        if (target.filters.isEmpty()) {
            entries.remove(target);
        }
        saveChatFilterEntries(entries);
    }

    // --- Dialog-level exclusion list (SharedPreferences, NagramXF-specific) ---

    private static HashSet<Long> getExcludedDialogs() {
        // Load fresh each call: small set, rarely accessed, avoids stale state.
        HashSet<Long> set = new HashSet<>();
        try {
            String str = NaConfig.INSTANCE.getRegexFiltersExcludedDialogs().String();
            Long[] arr = new Gson().fromJson(str, Long[].class);
            if (arr != null) {
                set.addAll(Arrays.asList(arr));
            }
        } catch (Exception e) {
            FileLog.e("AyuFilter.getExcludedDialogs", e);
        }
        return set;
    }

    public static boolean isDialogExcluded(long dialogId) {
        return getExcludedDialogs().contains(dialogId);
    }

    public static void setDialogExcluded(long dialogId, boolean excluded) {
        HashSet<Long> set = new HashSet<>(getExcludedDialogs());
        boolean changed;
        if (excluded) {
            changed = set.add(dialogId);
        } else {
            changed = set.remove(dialogId);
        }
        if (changed) {
            Long[] arr = set.toArray(new Long[0]);
            String str = new Gson().toJson(arr);
            NaConfig.INSTANCE.getRegexFiltersExcludedDialogs().setConfigString(str);
            AyuFilterCache.clearDialog(dialogId);
        }
    }

    // --- Per-dialog shared-filter exclusions (Room-backed) ---

    public static ArrayList<ExcludedFilterEntry> getExcludedFilterEntries() {
        ArrayList<ExcludedFilterEntry> out = new ArrayList<>();
        RegexFilterDao dao = AyuData.getRegexFilterDao();
        if (dao == null) {
            return out;
        }
        try {
            List<RegexFilterGlobalExclusion> rows = dao.getAllExclusions();
            for (RegexFilterGlobalExclusion row : rows) {
                ExcludedFilterEntry entry = new ExcludedFilterEntry();
                entry.dialogId = row.dialogId;
                entry.filterId = row.filterId;
                out.add(entry);
            }
        } catch (Exception e) {
            FileLog.e("AyuFilter.getExcludedFilterEntries", e);
        }
        return out;
    }

    private static HashMap<Long, HashSet<String>> getExcludedSharedFilterIdsByDialog() {
        if (excludedSharedFilterIdsByDialog == null) {
            synchronized (cacheLock) {
                if (excludedSharedFilterIdsByDialog == null) {
                    excludedSharedFilterIdsByDialog = buildExcludedSharedFilterIdsMap(getExcludedFilterEntries());
                }
            }
        }
        return excludedSharedFilterIdsByDialog;
    }

    private static HashMap<Long, HashSet<String>> buildExcludedSharedFilterIdsMap(ArrayList<ExcludedFilterEntry> entries) {
        HashMap<Long, HashSet<String>> result = new HashMap<>();
        if (entries == null) {
            return result;
        }
        for (var entry : entries) {
            if (entry == null || entry.dialogId == 0L || TextUtils.isEmpty(entry.filterId)) {
                continue;
            }
            result.computeIfAbsent(entry.dialogId, k -> new HashSet<>()).add(entry.filterId);
        }
        return result;
    }

    public static HashSet<String> getExcludedSharedFilterIds(long dialogId) {
        HashSet<String> ids = getExcludedSharedFilterIdsByDialog().get(dialogId);
        return ids != null ? new HashSet<>(ids) : new HashSet<>();
    }

    public static boolean isSharedFilterExcluded(long dialogId, String filterId) {
        return !TextUtils.isEmpty(filterId) && getExcludedSharedFilterIds(dialogId).contains(filterId);
    }

    public static ArrayList<FilterModel> getExcludedSharedFiltersForDialog(long dialogId) {
        ArrayList<FilterModel> filters = new ArrayList<>();
        HashSet<String> excludedIds = getExcludedSharedFilterIds(dialogId);
        if (excludedIds.isEmpty()) {
            return filters;
        }
        for (var filter : getRegexFilters()) {
            if (filter != null && !TextUtils.isEmpty(filter.id) && excludedIds.contains(filter.id)) {
                filters.add(filter);
            }
        }
        return filters;
    }

    public static int getExcludedSharedFiltersCountForDialog(long dialogId) {
        return getExcludedSharedFiltersForDialog(dialogId).size();
    }

    public static String getFilterDisplayText(FilterModel filter) {
        if (filter == null) {
            return "";
        }
        String regex = filter.regex != null ? filter.regex : "";
        return filter.reversed ? "!= " + regex : regex;
    }

    public static void setSharedFilterExcluded(long dialogId, String filterId, boolean excluded) {
        if (dialogId == 0L || TextUtils.isEmpty(filterId)) {
            return;
        }
        if (excluded) {
            addSharedFilterExclusion(dialogId, filterId);
        } else {
            removeSharedFilterExclusion(dialogId, filterId);
        }
    }

    private static void addSharedFilterExclusion(long dialogId, String filterId) {
        RegexFilterDao dao = AyuData.getRegexFilterDao();
        if (dao == null) return;
        try {
            if (dao.isExcluded(dialogId, filterId)) {
                return;
            }
            RegexFilterGlobalExclusion row = new RegexFilterGlobalExclusion();
            row.dialogId = dialogId;
            row.filterId = filterId;
            dao.insertExclusion(row);
        } catch (Exception e) {
            FileLog.e("AyuFilter.addSharedFilterExclusion", e);
        }
        synchronized (cacheLock) {
            excludedSharedFilterIdsByDialog = null;
            AyuFilterCache.clearAll();
        }
    }

    private static void removeSharedFilterExclusion(long dialogId, String filterId) {
        RegexFilterDao dao = AyuData.getRegexFilterDao();
        if (dao == null) return;
        try {
            dao.deleteExclusion(dialogId, filterId);
        } catch (Exception e) {
            FileLog.e("AyuFilter.removeSharedFilterExclusion", e);
        }
        synchronized (cacheLock) {
            excludedSharedFilterIdsByDialog = null;
            AyuFilterCache.clearAll();
        }
    }

    private static void removeExcludedSharedFilterEntries(String filterId) {
        if (TextUtils.isEmpty(filterId)) {
            return;
        }
        RegexFilterDao dao = AyuData.getRegexFilterDao();
        if (dao != null) {
            try {
                dao.deleteExclusionsByFilterId(filterId);
            } catch (Exception e) {
                FileLog.e("AyuFilter.removeExcludedSharedFilterEntries", e);
            }
        }
        synchronized (cacheLock) {
            excludedSharedFilterIdsByDialog = null;
            AyuFilterCache.clearAll();
        }
    }

    public static void clearAllFilters() {
        RegexFilterDao dao = AyuData.getRegexFilterDao();
        if (dao != null) {
            try {
                dao.deleteAllFilters();
                dao.deleteAllExclusions();
            } catch (Exception e) {
                FileLog.e("AyuFilter.clearAllFilters", e);
            }
        }
        NaConfig.INSTANCE.getRegexFiltersData().setConfigString("[]");
        NaConfig.INSTANCE.getRegexChatFiltersData().setConfigString("[]");
        NaConfig.INSTANCE.getRegexFiltersExcludedEntriesData().setConfigString("[]");
        NaConfig.INSTANCE.getRegexFiltersExcludedDialogs().setConfigString("[]");
        NaConfig.INSTANCE.getCustomFilteredUsersData().setConfigString("[]");
        synchronized (cacheLock) {
            customFilteredUsers = new HashSet<>();
            customFilteredUsersData = new HashMap<>();
        }
        rebuildCache();
    }

    // --- Blocked channels (SharedPreferences, NagramXF-specific) ---

    private static HashSet<Long> getBlockedChannels() {
        if (blockedChannels == null) {
            synchronized (cacheLock) {
                if (blockedChannels == null) {
                    try {
                        String str = NaConfig.INSTANCE.getBlockedChannelsData().String();
                        Long[] arr = new Gson().fromJson(str, Long[].class);
                        blockedChannels = new HashSet<>();
                        if (arr != null) {
                            blockedChannels.addAll(Arrays.asList(arr));
                        }
                    } catch (Exception e) {
                        blockedChannels = new HashSet<>();
                    }
                }
            }
        }
        return blockedChannels;
    }

    public static boolean isBlockedChannel(long dialogId) {
        return NekoConfig.ignoreBlocked.Bool() && getBlockedChannels().contains(dialogId);
    }

    public static boolean isCustomFilteredPeer(long peerId) {
        return NekoConfig.ignoreBlocked.Bool() && peerId > 0L && getCustomFilteredUsers().contains(peerId);
    }

    public static void blockPeer(long dialogId) {
        HashSet<Long> set = new HashSet<>(getBlockedChannels());
        if (set.add(dialogId)) {
            Long[] arr = set.toArray(new Long[0]);
            String str = new Gson().toJson(arr);
            NaConfig.INSTANCE.getBlockedChannelsData().setConfigString(str);
            synchronized (cacheLock) {
                blockedChannels = set;
            }
        }
    }

    public static void unblockPeer(long dialogId) {
        HashSet<Long> set = new HashSet<>(getBlockedChannels());
        if (set.remove(dialogId)) {
            Long[] arr = set.toArray(new Long[0]);
            String str = new Gson().toJson(arr);
            NaConfig.INSTANCE.getBlockedChannelsData().setConfigString(str);
            synchronized (cacheLock) {
                blockedChannels = set;
            }
        }
    }

    public static ArrayList<Long> getBlockedChannelsList() {
        return checkBlockedChannels(getBlockedChannels());
    }

    public static int getBlockedChannelsCount() {
        return getBlockedChannels().size();
    }

    public static void clearBlockedChannels() {
        NaConfig.INSTANCE.getBlockedChannelsData().setConfigString("[]");
        synchronized (cacheLock) {
            blockedChannels = new HashSet<>();
        }
    }

    public static ArrayList<Long> checkBlockedChannels(HashSet<Long> blockedChannels) {
        if (blockedChannels == null || blockedChannels.isEmpty()) return new ArrayList<>();
        ArrayList<Long> filtered = new ArrayList<>();
        try {
            final MessagesController mc = MessagesController.getInstance(UserConfig.selectedAccount);
            final MessagesStorage ms = MessagesStorage.getInstance(UserConfig.selectedAccount);
            for (Long did : blockedChannels) {
                if (did == null) continue;
                if (did < 0) {
                    TLRPC.Chat chat = mc.getChat(-did);
                    if (chat == null) {
                        chat = ms.getChatSync(-did);
                    }
                    if (chat != null) {
                        filtered.add(did);
                        mc.putChat(chat, true);
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return filtered;
    }

    public static void onMessageEdited(int msgId, long dialogId) {
        AyuFilterCache.invalidate(dialogId, msgId);
    }

    // --- Custom filtered users / shadow ban (SharedPreferences, NagramXF-specific) ---

    private static void ensureCustomFilteredUsersLoaded() {
        if (customFilteredUsers != null && customFilteredUsersData != null) {
            return;
        }
        synchronized (cacheLock) {
            if (customFilteredUsers != null && customFilteredUsersData != null) {
                return;
            }
            HashSet<Long> ids = new HashSet<>();
            HashMap<Long, CustomFilteredUser> data = new HashMap<>();
            try {
                String str = NaConfig.INSTANCE.getCustomFilteredUsersData().String();
                CustomFilteredUser[] arr = new Gson().fromJson(str, CustomFilteredUser[].class);
                if (arr != null) {
                    for (CustomFilteredUser item : arr) {
                        if (item != null && item.id > 0L) {
                            ids.add(item.id);
                            data.put(item.id, item);
                        }
                    }
                }
            } catch (Exception ignore) {
            }
            customFilteredUsers = ids;
            customFilteredUsersData = data;
        }
    }

    private static HashSet<Long> getCustomFilteredUsers() {
        ensureCustomFilteredUsersLoaded();
        return customFilteredUsers;
    }

    private static HashMap<Long, CustomFilteredUser> getCustomFilteredUsersDataMap() {
        ensureCustomFilteredUsersLoaded();
        return customFilteredUsersData;
    }

    private static void saveCustomFilteredUsers(HashSet<Long> ids, HashMap<Long, CustomFilteredUser> dataMap) {
        ArrayList<Long> sorted = new ArrayList<>(ids);
        Collections.sort(sorted);
        ArrayList<CustomFilteredUser> out = new ArrayList<>(sorted.size());
        HashMap<Long, CustomFilteredUser> resultMap = new HashMap<>(sorted.size());
        for (Long id : sorted) {
            if (id == null || id <= 0L) {
                continue;
            }
            CustomFilteredUser user = dataMap.get(id);
            if (user == null) {
                user = new CustomFilteredUser();
                user.id = id;
            }
            out.add(user);
            resultMap.put(user.id, user);
        }
        String str = new Gson().toJson(out.toArray(new CustomFilteredUser[0]));
        NaConfig.INSTANCE.getCustomFilteredUsersData().setConfigString(str);
        synchronized (cacheLock) {
            customFilteredUsers = new HashSet<>(resultMap.keySet());
            customFilteredUsersData = resultMap;
        }
    }

    public static ArrayList<Long> getCustomFilteredUsersList() {
        ArrayList<Long> list = new ArrayList<>(getCustomFilteredUsers());
        Collections.sort(list);
        return list;
    }

    public static ArrayList<CustomFilteredUser> getCustomFilteredUsersDataList() {
        HashMap<Long, CustomFilteredUser> map = getCustomFilteredUsersDataMap();
        ArrayList<Long> sortedIds = getCustomFilteredUsersList();
        ArrayList<CustomFilteredUser> list = new ArrayList<>(sortedIds.size());
        for (Long id : sortedIds) {
            if (id == null || id <= 0L) {
                continue;
            }
            CustomFilteredUser item = map.get(id);
            if (item == null) {
                item = new CustomFilteredUser();
                item.id = id;
            }
            list.add(item);
        }
        return list;
    }

    public static CustomFilteredUser getCustomFilteredUser(long userId) {
        if (userId <= 0L) {
            return null;
        }
        return getCustomFilteredUsersDataMap().get(userId);
    }

    public static void setCustomFilteredUsersData(ArrayList<CustomFilteredUser> users) {
        HashSet<Long> ids = new HashSet<>();
        HashMap<Long, CustomFilteredUser> map = new HashMap<>();
        if (users != null) {
            for (CustomFilteredUser item : users) {
                if (item != null && item.id > 0L) {
                    ids.add(item.id);
                    map.put(item.id, item);
                }
            }
        }
        saveCustomFilteredUsers(ids, map);
    }

    public static void setCustomFilteredUsers(ArrayList<Long> ids) {
        HashSet<Long> set = new HashSet<>();
        HashMap<Long, CustomFilteredUser> current = new HashMap<>(getCustomFilteredUsersDataMap());
        HashMap<Long, CustomFilteredUser> data = new HashMap<>();
        if (ids != null) {
            for (Long id : ids) {
                if (id != null && id > 0L) {
                    set.add(id);
                    CustomFilteredUser item = current.get(id);
                    if (item == null) {
                        item = new CustomFilteredUser();
                        item.id = id;
                    }
                    data.put(id, item);
                }
            }
        }
        saveCustomFilteredUsers(set, data);
    }

    public static void updateCustomFilteredUserFromLocalUser(TLRPC.User user) {
        if (user == null || user.id <= 0L || !getCustomFilteredUsers().contains(user.id)) {
            return;
        }
        HashSet<Long> ids = new HashSet<>(getCustomFilteredUsers());
        HashMap<Long, CustomFilteredUser> map = new HashMap<>(getCustomFilteredUsersDataMap());
        CustomFilteredUser current = map.get(user.id);
        if (current == null) {
            current = new CustomFilteredUser();
            current.id = user.id;
        }
        boolean changed = false;
        String username = UserObject.getPublicUsername(user);
        String displayName = UserObject.getUserName(user);
        if (user.access_hash != 0L && current.accessHash != user.access_hash) {
            current.accessHash = user.access_hash;
            changed = true;
        }
        if (!TextUtils.equals(current.username, username)) {
            current.username = username;
            changed = true;
        }
        if (!TextUtils.equals(current.displayName, displayName)) {
            current.displayName = displayName;
            changed = true;
        }
        if (changed) {
            map.put(user.id, current);
            saveCustomFilteredUsers(ids, map);
        }
    }

    // --- Room row conversion ---

    private static RegexFilter toRow(FilterModel m, Long dialogId) {
        RegexFilter row = new RegexFilter();
        row.id = m.id;
        row.text = m.regex;
        row.dialogId = dialogId;
        row.enabled = m.enabled;
        row.caseInsensitive = m.caseInsensitive;
        row.reversed = m.reversed;
        return row;
    }

    private static void insertFilterRow(FilterModel m, Long dialogId) {
        RegexFilterDao dao = AyuData.getRegexFilterDao();
        if (dao == null) return;
        m.ensureId();
        try {
            dao.insert(toRow(m, dialogId));
        } catch (Exception e) {
            FileLog.e("AyuFilter.insertFilterRow", e);
        }
    }

    // --- Data classes (preserved for call-site compatibility) ---

    public static class FilterModel {
        @Expose
        public String id = UUID.randomUUID().toString();
        @Expose
        public String regex;
        @Expose
        public boolean caseInsensitive;
        @Expose
        public boolean enabled = true;
        @Expose
        public boolean reversed;
        public Pattern pattern;

        // Legacy fields for JSON-export migration only; never written to Room.
        public ArrayList<Long> enabledGroups;
        public ArrayList<Long> disabledGroups;

        public boolean ensureId() {
            if (!TextUtils.isEmpty(id)) {
                return false;
            }
            id = UUID.randomUUID().toString();
            return true;
        }

        public void buildPattern() {
            var flags = Pattern.MULTILINE;
            if (caseInsensitive) {
                flags |= Pattern.CASE_INSENSITIVE;
            }
            try {
                pattern = Pattern.compile(regex, flags);
            } catch (Exception e) {
                pattern = null;
                FileLog.e(e);
            }
        }

        /**
         * Migrate the legacy {@code enabledGroups}/{@code disabledGroups} per-dialog
         * enable map into the flat {@code enabled} boolean for the given dialogId.
         * Returns true if the field state changed.
         */
        public boolean migrateFromLegacy(long dialogId) {
            if (enabledGroups == null && disabledGroups == null) {
                return false;
            }
            boolean defaultEnabled = enabledGroups != null && enabledGroups.contains(0L);
            if (defaultEnabled) {
                enabled = disabledGroups == null || !disabledGroups.contains(dialogId);
            } else {
                enabled = enabledGroups != null && enabledGroups.contains(dialogId);
            }
            enabledGroups = null;
            disabledGroups = null;
            return true;
        }
    }

    public static class ChatFilterEntry {
        @Expose
        public long dialogId;
        @Expose
        public ArrayList<FilterModel> filters;
    }

    public static class ExcludedFilterEntry {
        @Expose
        public long dialogId;
        @Expose
        public String filterId;
    }

    public static class CustomFilteredUser {
        @Expose
        public long id;
        @Expose
        public long accessHash;
        @Expose
        public String username;
        @Expose
        public String displayName;
    }
}
