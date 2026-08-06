package com.radolyn.ayugram.utils;

import com.radolyn.ayugram.database.AyuData;
import com.radolyn.ayugram.database.dao.SpyDao;
import com.radolyn.ayugram.database.entities.SpyLastSeen;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.support.LongSparseIntArray;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import xyz.nextalone.nagram.NaConfig;

public class LastSeenHelper {
    private static final int FLUSH_DELAY_MS = 5000;
    private static final int CLEANUP_DAYS = 7;
    private static final int MIN_LAST_SEEN_TIMESTAMP = 1397411401;
    private static final int ONLINE_PRIVACY_FRESHNESS_SECONDS = 30;

    private static final LongSparseIntArray cache = new LongSparseIntArray();
    private static final LongSparseIntArray pending = new LongSparseIntArray();
    private static volatile boolean flushScheduled;
    private static final AtomicBoolean preloadStarted = new AtomicBoolean(false);

    public static void preload() {
        if (!NaConfig.INSTANCE.getSaveLocalLastSeen().Bool()) {
            return;
        }
        if (!preloadStarted.compareAndSet(false, true)) {
            return;
        }
        AyuQueues.lastSeenQueue.postRunnable(() -> {
            int cutoff = (int) (System.currentTimeMillis() / 1000) - CLEANUP_DAYS * 24 * 60 * 60;
            SpyDao dao = AyuData.getSpyDao();
            if (dao == null) {
                return;
            }
            dao.deleteOldLastSeen(cutoff);
            List<SpyLastSeen> all = dao.getAllLastSeen();
            synchronized (cache) {
                for (SpyLastSeen e : all) {
                    int cached = cache.get(e.userId, 0);
                    if (cached < e.lastSeenDate) {
                        cache.put(e.userId, e.lastSeenDate);
                    }
                }
                for (int i = cache.size() - 1; i >= 0; i--) {
                    if (cache.valueAt(i) < cutoff) {
                        cache.removeAt(i);
                    }
                }
            }
        });
    }

    public static void saveLastSeen(long userId, int timestamp) {
        saveLastSeen(UserConfig.selectedAccount, userId, timestamp);
    }

    public static void saveLastSeen(int currentAccount, long userId, int timestamp) {
        if (!NaConfig.INSTANCE.getSaveLocalLastSeen().Bool() || timestamp < MIN_LAST_SEEN_TIMESTAMP) {
            return;
        }
        if (UserConfig.getInstance(currentAccount).getClientUserId() == userId) {
            return;
        }
        synchronized (cache) {
            int cached = cache.get(userId, 0);
            if (cached >= timestamp) return;
            cache.put(userId, timestamp);
        }
        synchronized (pending) {
            pending.put(userId, timestamp);
        }
        scheduleFlush();

        MessagesController messagesController = MessagesController.getInstance(currentAccount);
        int currentServerTime = ConnectionsManager.getInstance(currentAccount).getCurrentTime();
        if (timestamp >= currentServerTime - ONLINE_PRIVACY_FRESHNESS_SECONDS) {
            messagesController.onlinePrivacy.put(userId, timestamp);
        }

        TLRPC.User user = messagesController.getUser(userId);
        if (user != null && !user.bot && isBadStatus(user.status)) {
            final int account = currentAccount;
            AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(account)
                    .postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_STATUS));
        }
    }

    private static boolean isBadStatus(TLRPC.UserStatus status) {
        if (status == null) {
            return true;
        }
        if (status instanceof TLRPC.TL_userStatusRecently
                || status instanceof TLRPC.TL_userStatusLastWeek
                || status instanceof TLRPC.TL_userStatusLastMonth) {
            return true;
        }
        int expires = status.expires;
        return expires == -1 || expires == -100 || expires == -101 || expires == -102
                || expires == -1000 || expires == -1001 || expires == -1002;
    }

    private static void scheduleFlush() {
        synchronized (pending) {
            if (flushScheduled) {
                return;
            }
            flushScheduled = true;
        }
        AyuQueues.lastSeenQueue.postRunnable(LastSeenHelper::flushPending, FLUSH_DELAY_MS);
    }

    private static void flushPending() {
        if (!NaConfig.INSTANCE.getSaveLocalLastSeen().Bool()) {
            synchronized (pending) {
                pending.clear();
                flushScheduled = false;
            }
            return;
        }

        List<SpyLastSeen> toWrite;
        synchronized (pending) {
            if (pending.size() == 0) {
                flushScheduled = false;
                return;
            }
            toWrite = new ArrayList<>(pending.size());
            for (int i = 0; i < pending.size(); i++) {
                SpyLastSeen e = new SpyLastSeen();
                e.userId = pending.keyAt(i);
                e.lastSeenDate = pending.valueAt(i);
                toWrite.add(e);
            }
            pending.clear();
        }

        SpyDao dao = AyuData.getSpyDao();
        boolean ok = false;
        try {
            if (dao != null) {
                dao.upsertLastSeenAll(toWrite);
                ok = true;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        if (!ok) {
            requeuePending(toWrite);
        }

        synchronized (pending) {
            if (pending.size() > 0) {
                AyuQueues.lastSeenQueue.postRunnable(LastSeenHelper::flushPending, FLUSH_DELAY_MS);
            } else {
                flushScheduled = false;
            }
        }
    }

    private static void requeuePending(List<SpyLastSeen> toRequeue) {
        synchronized (pending) {
            for (int i = 0; i < toRequeue.size(); i++) {
                SpyLastSeen e = toRequeue.get(i);
                int existing = pending.get(e.userId, 0);
                if (existing < e.lastSeenDate) {
                    pending.put(e.userId, e.lastSeenDate);
                }
            }
        }
    }

    public static int getLastSeen(long userId) {
        if (!NaConfig.INSTANCE.getSaveLocalLastSeen().Bool()) {
            return 0;
        }
        synchronized (cache) {
            return cache.get(userId, 0);
        }
    }

    public static String getFormattedLastSeenOrDefault(TLRPC.User user, boolean[] madeShorter, String defaultValue) {
        int savedLastSeen = getLastSeen(user.id);
        if (savedLastSeen > 0) {
            return LocaleController.formatDateOnline(savedLastSeen, madeShorter);
        }
        return defaultValue;
    }

    private static long getPeerId(TLRPC.Peer peer) {
        if (peer == null) {
            return 0;
        }
        if (peer.user_id != 0) {
            return peer.user_id;
        }
        if (peer.chat_id != 0) {
            return -peer.chat_id;
        }
        if (peer.channel_id != 0) {
            return -peer.channel_id;
        }
        return 0;
    }

    private static int getLastMessageDate(long userId, ArrayList<MessageObject> messageObjects) {
        int lastMessageDate = 0;
        for (int i = 0, size = messageObjects.size(); i < size; i++) {
            MessageObject messageObject = messageObjects.get(i);
            if (messageObject == null || messageObject.messageOwner == null) {
                continue;
            }
            if (messageObject.getFromChatId() != userId) {
                continue;
            }
            int date = messageObject.messageOwner.date;
            if (date > lastMessageDate) {
                lastMessageDate = date;
            }
        }
        return lastMessageDate;
    }

    private static int getLastReactionDate(long userId, ArrayList<MessageObject> messageObjects) {
        int lastReactionDate = 0;
        for (int i = 0, size = messageObjects.size(); i < size; i++) {
            MessageObject messageObject = messageObjects.get(i);
            if (messageObject == null || messageObject.messageOwner == null) {
                continue;
            }
            if (messageObject.messageOwner.reactions == null || messageObject.messageOwner.reactions.recent_reactions == null) {
                continue;
            }
            for (int j = 0, rSize = messageObject.messageOwner.reactions.recent_reactions.size(); j < rSize; j++) {
                TLRPC.MessagePeerReaction reaction = messageObject.messageOwner.reactions.recent_reactions.get(j);
                if (reaction == null || reaction.peer_id == null || reaction.date <= 0) {
                    continue;
                }
                if (MessageObject.getPeerId(reaction.peer_id) != userId) {
                    continue;
                }
                if (reaction.date > lastReactionDate) {
                    lastReactionDate = reaction.date;
                }
            }
        }
        return lastReactionDate;
    }

    public static void saveLastSeenFromLoadedMessages(int currentAccount, long userId, long selfUserId, ArrayList<MessageObject> messages, ChatActivity.ChatActivityAdapter chatAdapter) {
        if (!NaConfig.INSTANCE.getSaveLocalLastSeen().Bool()) {
            return;
        }
        if (userId <= 0 || userId == selfUserId) {
            return;
        }
        ArrayList<MessageObject> messageObjects = chatAdapter != null ? chatAdapter.getMessages() : messages;
        if (messageObjects == null) {
            return;
        }
        int lastMessageDate = getLastMessageDate(userId, messageObjects);
        if (lastMessageDate > 0) {
            LastSeenHelper.saveLastSeen(currentAccount, userId, lastMessageDate);
        }
        int lastReactionDate = getLastReactionDate(userId, messageObjects);
        if (lastReactionDate > 0) {
            LastSeenHelper.saveLastSeen(currentAccount, userId, lastReactionDate);
        }
    }

    public static void saveLastSeenFromMessageReactions(int currentAccount, TLRPC.TL_messageReactions reactions, long selfUserId) {
        if (reactions == null || reactions.recent_reactions == null || reactions.recent_reactions.isEmpty()) {
            return;
        }
        saveLastSeenFromPeerReactions(currentAccount, reactions.recent_reactions, selfUserId);
    }

    public static void saveLastSeenFromPeerReactions(int currentAccount, List<TLRPC.MessagePeerReaction> reactions, long selfUserId) {
        if (!NaConfig.INSTANCE.getSaveLocalLastSeen().Bool()) {
            return;
        }
        if (reactions == null || reactions.isEmpty()) {
            return;
        }
        for (int i = 0; i < reactions.size(); i++) {
            TLRPC.MessagePeerReaction reaction = reactions.get(i);
            if (reaction == null || reaction.peer_id == null || reaction.date <= 0) {
                continue;
            }
            long peerId = getPeerId(reaction.peer_id);
            if (peerId <= 0 || peerId == selfUserId) {
                continue;
            }
            saveLastSeen(currentAccount, peerId, reaction.date);
        }
    }
}
