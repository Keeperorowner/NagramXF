
package com.radolyn.ayugram.controllers;

import com.radolyn.ayugram.database.AyuData;
import com.radolyn.ayugram.database.dao.SpyDao;
import com.radolyn.ayugram.database.entities.SpyMessageContentsRead;
import com.radolyn.ayugram.database.entities.SpyMessageRead;
import com.radolyn.ayugram.utils.AyuQueues;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;

import java.util.List;

import xyz.nextalone.nagram.NaConfig;

public final class AyuSpyController {

    private AyuSpyController() {
    }

    public static boolean isEnabled() {
        return NaConfig.INSTANCE.getSaveReadDate().Bool();
    }

    public static void onOutboxRead(int account, long dialogId, List<Integer> messageIds) {
        if (!isEnabled() || messageIds == null || messageIds.isEmpty()) {
            return;
        }
        long selfUserId = UserConfig.getInstance(account).getClientUserId();
        int readTime = ConnectionsManager.getInstance(account).getCurrentTime();
        AyuQueues.spyQueue.postRunnable(() -> recordReads(selfUserId, dialogId, messageIds, readTime));
    }

    public static void onContentsRead(int account, long dialogId, List<Integer> messageIds) {
        if (!isEnabled() || messageIds == null || messageIds.isEmpty()) {
            return;
        }
        long selfUserId = UserConfig.getInstance(account).getClientUserId();
        int readTime = ConnectionsManager.getInstance(account).getCurrentTime();
        AyuQueues.spyQueue.postRunnable(() -> recordContentsReads(selfUserId, dialogId, messageIds, readTime));
    }

    private static void recordReads(long selfUserId, long dialogId, List<Integer> messageIds, int readTime) {
        SpyDao dao = AyuData.getSpyDao();
        if (dao == null) {
            return;
        }
        for (int i = 0; i < messageIds.size(); i++) {
            int mid = messageIds.get(i);
            try {
                if (dao.getMessageRead(selfUserId, dialogId, mid) == null) {
                    SpyMessageRead entry = new SpyMessageRead();
                    entry.userId = selfUserId;
                    entry.dialogId = dialogId;
                    entry.messageId = mid;
                    entry.entityCreateDate = readTime;
                    dao.insert(entry);
                }
            } catch (Exception e) {
                FileLog.e("AyuSpy: recordReads", e);
            }
        }
    }

    private static void recordContentsReads(long selfUserId, long dialogId, List<Integer> messageIds, int readTime) {
        SpyDao dao = AyuData.getSpyDao();
        if (dao == null) {
            return;
        }
        for (int i = 0; i < messageIds.size(); i++) {
            int mid = messageIds.get(i);
            try {
                if (dao.getMessageContentsRead(selfUserId, dialogId, mid) == null) {
                    SpyMessageContentsRead entry = new SpyMessageContentsRead();
                    entry.userId = selfUserId;
                    entry.dialogId = dialogId;
                    entry.messageId = mid;
                    entry.entityCreateDate = readTime;
                    dao.insert(entry);
                }
            } catch (Exception e) {
                FileLog.e("AyuSpy: recordContentsReads", e);
            }
        }
    }

    public static SpyMessageRead getMessageRead(int account, long dialogId, int messageId) {
        if (!isEnabled()) {
            return null;
        }
        long selfUserId = UserConfig.getInstance(account).getClientUserId();
        return AyuData.getSpyDao() == null ? null : AyuData.getSpyDao().getMessageRead(selfUserId, dialogId, messageId);
    }

    public static SpyMessageContentsRead getMessageContentsRead(int account, long dialogId, int messageId) {
        if (!isEnabled()) {
            return null;
        }
        long selfUserId = UserConfig.getInstance(account).getClientUserId();
        return AyuData.getSpyDao() == null ? null : AyuData.getSpyDao().getMessageContentsRead(selfUserId, dialogId, messageId);
    }

    public static void deleteForDialog(int account, long dialogId) {
        if (AyuData.getSpyDao() == null) {
            return;
        }
        long selfUserId = UserConfig.getInstance(account).getClientUserId();
        AyuQueues.spyQueue.postRunnable(() -> {
            try {
                SpyDao dao = AyuData.getSpyDao();
                if (dao == null) return;
                dao.deleteForDialog(selfUserId, dialogId);
                dao.deleteContentsForDialog(selfUserId, dialogId);
            } catch (Exception e) {
                FileLog.e("AyuSpy: deleteForDialog", e);
            }
        });
    }
}
