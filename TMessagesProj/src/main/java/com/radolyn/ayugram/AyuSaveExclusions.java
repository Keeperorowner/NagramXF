package com.radolyn.ayugram;

import android.text.TextUtils;

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
    private static final String KEY_MIGRATED = "ayuSaveExclusionsMigrated";
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

    /** Ids explicitly saved ({@code always}) or skipped ({@code always == false}). */
    public static Set<String> getIds(boolean always) {
        ensureMigrated();
        Set<String> stored = NekoConfig.getPreferences().getStringSet(always ? KEY_ALWAYS : KEY_NEVER, null);
        return stored == null ? new HashSet<>() : new HashSet<>(stored);
    }

    public static void setIds(boolean always, Set<String> ids) {
        NekoConfig.getPreferences().edit()
                .putStringSet(always ? KEY_ALWAYS : KEY_NEVER, new HashSet<>(ids))
                .apply();
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
    private static void ensureMigrated() {
        if (NekoConfig.getPreferences().getBoolean(KEY_MIGRATED, false)) {
            return;
        }
        Set<String> never = new HashSet<>();
        Set<String> stored = NekoConfig.getPreferences().getStringSet(KEY_NEVER, null);
        if (stored != null) {
            never.addAll(stored);
        }
        for (Map.Entry<String, ?> entry : NekoConfig.getPreferences().getAll().entrySet()) {
            String key = entry.getKey();
            if (key == null || !key.startsWith(LEGACY_PREFIX) || !(entry.getValue() instanceof Boolean)) {
                continue;
            }
            if (!(Boolean) entry.getValue()) {
                continue;
            }
            String id = key.substring(LEGACY_PREFIX.length());
            if (!TextUtils.isEmpty(id)) {
                never.add(id);
            }
        }
        NekoConfig.getPreferences().edit()
                .putStringSet(KEY_NEVER, never)
                .putBoolean(KEY_MIGRATED, true)
                .apply();
    }
}
