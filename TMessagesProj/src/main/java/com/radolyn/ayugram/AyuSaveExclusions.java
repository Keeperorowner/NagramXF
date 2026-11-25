package com.radolyn.ayugram;

import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONTokener;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.tgnet.TLRPC;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import tw.nekomimi.nekogram.NekoConfig;

/**
 * Per-chat rules for keeping deleted messages and edit history.
 *
 * <p>Two modes decide what happens to chats without an explicit rule: "always save, except the
 * excluded chats" (the default) or "never save, except the included chats". Explicit rules live in
 * two id sets, and in addition each set carries a bitmask of chat kinds so whole categories (bot
 * chats, groups, channels, secret chats…) can be saved or skipped without listing every chat.
 */
public final class AyuSaveExclusions {

    public static final int TYPE_DEFAULT = 0;
    public static final int TYPE_NEVER = 1;
    public static final int TYPE_ALWAYS = 2;

    public static final int FLAG_BOT_USER = 1;
    public static final int FLAG_BOT_CHAT = 1 << 1;
    public static final int FLAG_USER = 1 << 2;
    public static final int FLAG_PUBLIC_CHANNEL = 1 << 3;
    public static final int FLAG_PRIVATE_CHANNEL = 1 << 4;
    public static final int FLAG_PUBLIC_GROUP = 1 << 5;
    public static final int FLAG_PRIVATE_GROUP = 1 << 6;
    public static final int FLAG_SECRET = 1 << 7;

    public static final int FLAG_GROUPS = FLAG_PUBLIC_GROUP | FLAG_PRIVATE_GROUP;
    public static final int FLAG_CHANNELS = FLAG_PUBLIC_CHANNEL | FLAG_PRIVATE_CHANNEL;

    private static final String KEY_WHITELIST = "ayuSaveExclusionsWhitelist";
    private static final String KEY_ALWAYS = "ayuSaveExclusionsAlways";
    private static final String KEY_NEVER = "ayuSaveExclusionsNever";
    private static final String KEY_ALWAYS_FLAGS = "ayuSaveExclusionsAlwaysFlags";
    private static final String KEY_NEVER_FLAGS = "ayuSaveExclusionsNeverFlags";
    private static final String KEY_MIGRATED_UNSIGNED = "ayuSaveExclusionsMigrated";
    private static final String KEY_MIGRATED = "ayuSaveExclusionsMigratedSigned";
    private static final String LEGACY_PREFIX = "saveDeletedExclusion_";

    private AyuSaveExclusions() {
    }

    /** True when saving only happens in the included chats. */
    public static boolean isWhitelist() {
        ensureMigrated();
        return NekoConfig.getPreferences().getBoolean(KEY_WHITELIST, false);
    }

    public static void setWhitelist(boolean whitelist) {
        NekoConfig.getPreferences().edit().putBoolean(KEY_WHITELIST, whitelist).apply();
    }

    /**
     * Ids explicitly saved ({@code always}) or skipped ({@code always == false}).
     * Unreadable lists temporarily return an empty set without changing the stored value.
     */
    public static Set<String> getIds(boolean always) {
        ensureMigrated();
        Set<String> ids = getStoredIds(NekoConfig.getPreferences(), always ? KEY_ALWAYS : KEY_NEVER);
        return ids == null ? new HashSet<>() : ids;
    }

    public static void setIds(boolean always, Set<String> ids) {
        SharedPreferences preferences = NekoConfig.getPreferences();
        String key = always ? KEY_ALWAYS : KEY_NEVER;
        SharedPreferences.Editor editor = preferences.edit();
        Map<String, ?> values = preferences.getAll();
        Object stored = values.get(key);
        if (stored instanceof String && getStoredIds(preferences, key) == null) {
            String backupKey = key + "_unparsed";
            for (int suffix = 1; values.containsKey(backupKey) && !stored.equals(values.get(backupKey)); suffix++) {
                backupKey = key + "_unparsed_" + suffix;
            }
            if (!values.containsKey(backupKey)) {
                editor.putString(backupKey, (String) stored);
            }
        }
        editor.putStringSet(key, new HashSet<>(ids)).apply();
    }

    /** Category bitmask that applies to the given set. */
    public static int getFlags(boolean always) {
        ensureMigrated();
        return NekoConfig.getPreferences().getInt(always ? KEY_ALWAYS_FLAGS : KEY_NEVER_FLAGS, 0);
    }

    public static void setFlags(boolean always, int flags) {
        NekoConfig.getPreferences().edit()
                .putInt(always ? KEY_ALWAYS_FLAGS : KEY_NEVER_FLAGS, flags)
                .apply();
    }

    /** Applies a category bit to the given set. */
    public static void setFlag(boolean always, int flag, boolean enabled) {
        int flags = getFlags(always);
        setFlags(always, enabled ? (flags | flag) : (flags & ~flag));
    }

    /**
     * True when deleted messages and edit history should be kept for {@code dialogId}.
     * {@code botUserId} is the sender when the caller knows it, so bot chats can follow their bit.
     */
    public static boolean isSavingAllowedFor(int accountId, long dialogId, long botUserId) {
        final String key = String.valueOf(dialogId);
        if (getIds(false).contains(key)) {
            return false;
        }
        if (getIds(true).contains(key)) {
            return true;
        }
        final int alwaysFlags = getFlags(true);
        final int neverFlags = getFlags(false);
        final int union = alwaysFlags | neverFlags;
        if ((union & FLAG_BOT_CHAT) != 0 && DialogObject.isChatDialog(dialogId) && botUserId > 0) {
            TLRPC.User user = getUser(accountId, botUserId);
            if (user != null && user.bot) {
                return (neverFlags & FLAG_BOT_CHAT) == 0;
            }
        }
        if ((union & ~FLAG_BOT_CHAT) != 0) {
            int flag = getDialogFlag(accountId, dialogId);
            if ((neverFlags & flag) != 0) {
                return false;
            }
            if ((alwaysFlags & flag) != 0) {
                return true;
            }
        }
        return !isWhitelist();
    }

    /** Explicit rule for a chat: {@link #TYPE_DEFAULT}, {@link #TYPE_NEVER} or {@link #TYPE_ALWAYS}. */
    public static int getChatSavingType(long dialogId) {
        String key = String.valueOf(dialogId);
        if (getIds(false).contains(key)) {
            return TYPE_NEVER;
        }
        return getIds(true).contains(key) ? TYPE_ALWAYS : TYPE_DEFAULT;
    }

    public static void setChatSavingType(long dialogId, int type) {
        final String key = String.valueOf(dialogId);
        Set<String> always = getIds(true);
        Set<String> never = getIds(false);
        if (type == TYPE_ALWAYS) {
            always.add(key);
            never.remove(key);
        } else if (type == TYPE_NEVER) {
            never.add(key);
            always.remove(key);
        } else {
            always.remove(key);
            never.remove(key);
        }
        setIds(true, always);
        setIds(false, never);
    }

    /** Moves {@code ids} into the given set and applies {@code flags} to it. */
    public static void setExclusions(boolean always, Set<String> ids, int flags) {
        Set<String> target = getIds(always);
        Set<String> other = getIds(!always);
        target.addAll(ids);
        other.removeAll(ids);
        setIds(always, target);
        setFlags(always, flags);
        setIds(!always, other);
        setFlags(!always, getFlags(!always) & ~flags);
    }

    public static void reset(boolean always) {
        setIds(always, new HashSet<>());
        setFlags(always, 0);
    }

    /** Bit that describes the kind of dialog, or 0 when the peer is not known yet. */
    private static int getDialogFlag(int accountId, long dialogId) {
        if (DialogObject.isEncryptedDialog(dialogId)) {
            return FLAG_SECRET;
        }
        if (DialogObject.isUserDialog(dialogId)) {
            TLRPC.User user = getUser(accountId, dialogId);
            return user != null && user.bot ? FLAG_BOT_USER : FLAG_USER;
        }
        TLRPC.Chat chat = getChat(accountId, -dialogId);
        if (chat == null) {
            return 0;
        }
        boolean isPublic = ChatObject.isPublic(chat);
        if (ChatObject.isChannelAndNotMegaGroup(chat)) {
            return isPublic ? FLAG_PUBLIC_CHANNEL : FLAG_PRIVATE_CHANNEL;
        }
        return isPublic ? FLAG_PUBLIC_GROUP : FLAG_PRIVATE_GROUP;
    }

    private static TLRPC.User getUser(int accountId, long userId) {
        MessagesController messagesController = MessagesController.getInstance(accountId);
        TLRPC.User user = messagesController.getUser(userId);
        if (user != null) {
            return user;
        }
        user = MessagesStorage.getInstance(accountId).getUserSync(userId);
        if (user != null) {
            messagesController.putUser(user, true);
        }
        return user;
    }

    private static TLRPC.Chat getChat(int accountId, long chatId) {
        MessagesController messagesController = MessagesController.getInstance(accountId);
        TLRPC.Chat chat = messagesController.getChat(chatId);
        if (chat != null) {
            return chat;
        }
        chat = MessagesStorage.getInstance(accountId).getChatSync(chatId);
        if (chat != null) {
            messagesController.putChat(chat, true);
        }
        return chat;
    }

    /** Moves the old per-chat booleans into the excluded set once. */
    private static synchronized void ensureMigrated() {
        SharedPreferences preferences = NekoConfig.getPreferences();
        if (preferences.getBoolean(KEY_MIGRATED, false)) {
            return;
        }
        Set<String> never = getStoredIds(preferences, KEY_NEVER);
        Set<String> always = getStoredIds(preferences, KEY_ALWAYS);
        if (never == null || always == null) {
            return;
        }
        addLegacyIds(preferences.getAll(), never, always, preferences.getBoolean(KEY_MIGRATED_UNSIGNED, false));
        preferences.edit()
                .putStringSet(KEY_NEVER, never)
                .putBoolean(KEY_MIGRATED, true)
                .apply();
    }

    /** Returns null for unreadable data so migration leaves the stored value intact. */
    private static Set<String> getStoredIds(SharedPreferences preferences, String key) {
        Object stored;
        try {
            stored = preferences.getStringSet(key, null);
        } catch (ClassCastException e) {
            stored = preferences.getAll().get(key);
        }
        Set<String> ids = new HashSet<>();
        if (stored == null) {
            return ids;
        }
        if (stored instanceof Set<?>) {
            for (Object item : (Set<?>) stored) {
                if (!(item instanceof String)) {
                    return null;
                }
                ids.add((String) item);
            }
            return ids;
        }
        if (!(stored instanceof String)) {
            return null;
        }
        String serialized = (String) stored;
        if (serialized.indexOf('\0') >= 0) {
            return null;
        }
        serialized = serialized.trim();
        if (!serialized.startsWith("[") || !serialized.endsWith("]")) {
            return null;
        }
        String body = serialized.substring(1, serialized.length() - 1).trim();
        String[] items = body.isEmpty() ? new String[0] : body.split(",", -1);
        try {
            for (String item : items) {
                String id = item.trim();
                if (id.startsWith("\"")) {
                    if (!id.matches("\"(?:[-0-9]|\\\\u[0-9a-fA-F]{4})+\"")) {
                        return null;
                    }
                    JSONTokener tokener = new JSONTokener(id);
                    Object decoded = tokener.nextValue();
                    if (!(decoded instanceof String) || tokener.more()) {
                        return null;
                    }
                    id = (String) decoded;
                }
                if (!id.matches("-?[0-9]+")) {
                    return null;
                }
                ids.add(String.valueOf(Long.parseLong(id)));
            }
        } catch (JSONException | NumberFormatException e) {
            return null;
        }
        preferences.edit().putStringSet(key, ids).apply();
        return ids;
    }

    /**
     * Legacy keys held Math.abs(dialogId), so one entry covers both the user and the chat. The first
     * migration stored only the unsigned id: on repair one the user has since removed stays removed.
     */
    static void addLegacyIds(Map<String, ?> preferences, Set<String> never, Set<String> always, boolean repair) {
        for (Map.Entry<String, ?> entry : preferences.entrySet()) {
            String key = entry.getKey();
            if (key == null || !key.startsWith(LEGACY_PREFIX) || !Boolean.TRUE.equals(entry.getValue())) {
                continue;
            }
            long id;
            try {
                id = Long.parseLong(key.substring(LEGACY_PREFIX.length()));
            } catch (NumberFormatException e) {
                continue;
            }
            if (repair && !never.contains(String.valueOf(id))) {
                continue;
            }
            for (String dialogId : new String[]{String.valueOf(id), String.valueOf(-id)}) {
                if (!always.contains(dialogId)) {
                    never.add(dialogId);
                }
            }
        }
    }
}
