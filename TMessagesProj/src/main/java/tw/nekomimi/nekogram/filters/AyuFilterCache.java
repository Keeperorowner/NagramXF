package tw.nekomimi.nekogram.filters;

import androidx.collection.LruCache;

import org.telegram.messenger.MessageObject;

import java.util.concurrent.ConcurrentHashMap;

final class AyuFilterCache {
    private static final int PER_DIALOG_LIMIT = 1000;
    private static final ConcurrentHashMap<Long, LruCache<Integer, Boolean>> caches = new ConcurrentHashMap<>();

    private AyuFilterCache() {
    }

    static Boolean get(long dialogId, int msgId) {
        LruCache<Integer, Boolean> dialogCache = caches.get(dialogId);
        if (dialogCache == null) {
            return null;
        }
        synchronized (dialogCache) {
            return dialogCache.get(msgId);
        }
    }

    static void put(long dialogId, MessageObject msg, MessageObject.GroupedMessages group, boolean value) {
        if (msg == null) {
            return;
        }
        LruCache<Integer, Boolean> dialogCache = caches.computeIfAbsent(dialogId, k -> new LruCache<>(PER_DIALOG_LIMIT));
        synchronized (dialogCache) {
            dialogCache.put(msg.getId(), value);
            if (group != null && group.messages != null && !group.messages.isEmpty()) {
                for (var m : group.messages) {
                    dialogCache.put(m.getId(), value);
                }
            }
        }
    }

    static void invalidate(long dialogId, int msgId) {
        LruCache<Integer, Boolean> dialogCache = caches.get(dialogId);
        if (dialogCache == null) {
            return;
        }
        synchronized (dialogCache) {
            dialogCache.remove(msgId);
        }
    }

    static void clearDialog(long dialogId) {
        caches.remove(dialogId);
    }

    static void clearAll() {
        caches.clear();
    }
}
