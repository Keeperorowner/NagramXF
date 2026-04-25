package tw.nekomimi.nekogram.filters;

import androidx.collection.LruCache;

import org.telegram.messenger.MessageObject;

import java.util.concurrent.ConcurrentHashMap;

final class AyuFilterCache {
    private static final int PER_DIALOG_LIMIT = 1000;
    private static final int PER_DIALOG_GROUP_LIMIT = 500;
    private static final ConcurrentHashMap<Long, LruCache<Integer, Boolean>> messageCaches = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, LruCache<Long, Boolean>> groupCaches = new ConcurrentHashMap<>();

    private AyuFilterCache() {
    }

    static Boolean get(long dialogId, MessageObject msg, MessageObject.GroupedMessages group) {
        if (msg == null) {
            return null;
        }
        long groupId = group != null ? group.groupId : msg.getGroupId();
        if (groupId != 0 && group != null) {
            LruCache<Long, Boolean> grpCache = groupCaches.get(dialogId);
            if (grpCache != null) {
                synchronized (grpCache) {
                    Boolean val = grpCache.get(groupId);
                    if (val != null) {
                        return val;
                    }
                }
            }
        }
        LruCache<Integer, Boolean> msgCache = messageCaches.get(dialogId);
        if (msgCache != null) {
            synchronized (msgCache) {
                Boolean val = msgCache.get(msg.getId());
                if (val != null) {
                    return val;
                }
            }
        }
        if (groupId != 0 && group == null) {
            LruCache<Long, Boolean> grpCache = groupCaches.get(dialogId);
            if (grpCache != null) {
                synchronized (grpCache) {
                    return grpCache.get(groupId);
                }
            }
        }
        return null;
    }

    static void put(long dialogId, MessageObject msg, MessageObject.GroupedMessages group, boolean value) {
        if (msg == null) {
            return;
        }
        LruCache<Integer, Boolean> msgCache = messageCaches.computeIfAbsent(dialogId, k -> new LruCache<>(PER_DIALOG_LIMIT));
        synchronized (msgCache) {
            msgCache.put(msg.getId(), value);
        }
        long groupId = group != null ? group.groupId : msg.getGroupId();
        if (groupId != 0) {
            LruCache<Long, Boolean> grpCache = groupCaches.computeIfAbsent(dialogId, k -> new LruCache<>(PER_DIALOG_GROUP_LIMIT));
            synchronized (grpCache) {
                grpCache.put(groupId, value);
            }
        }
    }

    static void invalidate(long dialogId, int msgId) {
        LruCache<Integer, Boolean> msgCache = messageCaches.get(dialogId);
        if (msgCache != null) {
            synchronized (msgCache) {
                msgCache.remove(msgId);
            }
        }
    }

    static void invalidateGroup(long dialogId, long groupId) {
        if (groupId == 0) {
            return;
        }
        LruCache<Long, Boolean> grpCache = groupCaches.get(dialogId);
        if (grpCache != null) {
            synchronized (grpCache) {
                grpCache.remove(groupId);
            }
        }
    }

    static void clearDialog(long dialogId) {
        messageCaches.remove(dialogId);
        groupCaches.remove(dialogId);
    }

    static void clearAll() {
        messageCaches.clear();
        groupCaches.clear();
    }
}
