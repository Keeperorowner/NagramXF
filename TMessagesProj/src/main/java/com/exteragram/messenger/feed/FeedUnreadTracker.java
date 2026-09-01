package com.exteragram.messenger.feed;

import androidx.collection.LongSparseArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * Tracks read state for feed posts: exposes unread counts, detects the first
 * unread row and flushes locally-seen read positions to the server.
 */
final class FeedUnreadTracker {
    private final int currentAccount;
    private boolean flushScheduled;
    private final ArrayList<MessageObject> timeline;
    private final LongSparseArray<Integer> readInboxMaxByDialog = new LongSparseArray<>();
    private final LongSparseArray<Integer> pendingMaxReadId = new LongSparseArray<>();
    private final Runnable flushRunnable = this::flush;

    public FeedUnreadTracker(int account, ArrayList<MessageObject> timeline) {
        this.currentAccount = account;
        this.timeline = timeline;
    }

    public void clear() {
        if (flushScheduled) {
            AndroidUtilities.cancelRunOnUIThread(flushRunnable);
            flushScheduled = false;
        }
        flush();
        readInboxMaxByDialog.clear();
    }

    public void applyReadInboxMax(long dialogId, int maxId) {
        if (maxId > readInboxMaxByDialog.get(dialogId, 0)) {
            readInboxMaxByDialog.put(dialogId, maxId);
        }
    }

    public boolean isUnread(MessageObject message) {
        return message != null && !message.isSponsored() && message.getRealId() > getEffectiveReadInboxMax(message.getDialogId());
    }

    private int getEffectiveReadInboxMax(long dialogId) {
        return Math.max(readInboxMaxByDialog.get(dialogId, 0), pendingMaxReadId.get(dialogId, 0));
    }

    public int findFirstUnreadIndex(ArrayList<MessageObject> messages) {
        if (messages != null && !readInboxMaxByDialog.isEmpty()) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                if (isUnread(messages.get(i))) {
                    return i;
                }
            }
        }
        return -1;
    }

    public int countUnreadBelow(ArrayList<MessageObject> messages, int index) {
        if (messages == null || readInboxMaxByDialog.isEmpty()) {
            return 0;
        }
        int limit = Math.min(index, messages.size());
        int count = 0;
        for (int i = 0; i < limit; i++) {
            MessageObject message = messages.get(i);
            if (message != null && !message.isDateObject && message.type != MessageObject.TYPE_LOADING && !message.isSponsored() && isUnread(message)) {
                count++;
            }
        }
        return count;
    }

    public void onPostSeen(long dialogId, int messageId) {
        if (dialogId == 0 || messageId <= 0 || messageId <= getEffectiveReadInboxMax(dialogId)) {
            return;
        }
        Integer current = pendingMaxReadId.get(dialogId);
        if (current == null || current < messageId) {
            pendingMaxReadId.put(dialogId, messageId);
            if (!flushScheduled) {
                flushScheduled = true;
                AndroidUtilities.runOnUIThread(flushRunnable, 1000L);
            }
        }
    }

    private void flush() {
        flushScheduled = false;
        if (pendingMaxReadId.isEmpty()) {
            return;
        }
        MessagesController messagesController = MessagesController.getInstance(currentAccount);
        int currentTime = ConnectionsManager.getInstance(currentAccount).getCurrentTime();
        for (int i = 0; i < pendingMaxReadId.size(); i++) {
            long dialogId = pendingMaxReadId.keyAt(i);
            Integer maxReadId = pendingMaxReadId.valueAt(i);
            int pending = maxReadId;
            int known = readInboxMaxByDialog.get(dialogId, 0);
            if (pending > known) {
                readInboxMaxByDialog.put(dialogId, maxReadId);
                messagesController.markDialogAsRead(dialogId, pending, 0, currentTime, false, 0L, Math.max(countTimelineRows(dialogId, known, pending), 1), true, 0);
            }
        }
        pendingMaxReadId.clear();
    }

    private int countTimelineRows(long dialogId, int fromId, int toId) {
        int count = 0;
        for (int i = 0; i < timeline.size(); i++) {
            MessageObject message = timeline.get(i);
            if (message != null && message.getDialogId() == dialogId) {
                int realId = message.getRealId();
                if (realId > fromId && realId <= toId) {
                    count++;
                }
            }
        }
        return count;
    }

    public void markAllRead() {
        MessagesController messagesController = MessagesController.getInstance(currentAccount);
        HashSet<Long> touched = new HashSet<>();
        ArrayList<TLRPC.Dialog> unreadDialogs = collectUnreadFeedDialogs();
        for (int i = 0; i < unreadDialogs.size(); i++) {
            TLRPC.Dialog dialog = unreadDialogs.get(i);
            messagesController.markMentionsAsRead(dialog.id, 0L);
            messagesController.markDialogAsRead(dialog.id, dialog.top_message, dialog.top_message, dialog.last_message_date, false, 0L, 0, true, 0);
            readInboxMaxByDialog.put(dialog.id, dialog.top_message);
            touched.add(dialog.id);
        }
        FeedConfig feedConfig = FeedConfig.getInstance(currentAccount);
        boolean includeArchived = feedConfig.getIncludeArchived();
        for (int i = 0; i < timeline.size(); i++) {
            MessageObject message = timeline.get(i);
            if (message != null) {
                long dialogId = message.getDialogId();
                if (!feedConfig.isExcluded(dialogId) && (includeArchived || messagesController.dialogs_dict.get(dialogId) == null || messagesController.dialogs_dict.get(dialogId).folder_id != 1)) {
                    touched.add(dialogId);
                    int realId = message.getRealId();
                    if (realId > readInboxMaxByDialog.get(dialogId, 0)) {
                        readInboxMaxByDialog.put(dialogId, realId);
                    }
                }
            }
        }
        for (Long dialogId : touched) {
            pendingMaxReadId.remove(dialogId.longValue());
        }
        if (pendingMaxReadId.isEmpty() && flushScheduled) {
            AndroidUtilities.cancelRunOnUIThread(flushRunnable);
            flushScheduled = false;
        }
    }

    public int getUnreadCount() {
        ArrayList<TLRPC.Dialog> unreadDialogs = collectUnreadFeedDialogs();
        int count = 0;
        for (int i = 0; i < unreadDialogs.size(); i++) {
            count += unreadDialogs.get(i).unread_count;
        }
        return count;
    }

    private ArrayList<TLRPC.Dialog> collectUnreadFeedDialogs() {
        MessagesController messagesController = MessagesController.getInstance(currentAccount);
        FeedConfig feedConfig = FeedConfig.getInstance(currentAccount);
        boolean includeArchived = feedConfig.getIncludeArchived();
        LongSparseArray<TLRPC.Dialog> dialogs = messagesController.dialogs_dict;
        ArrayList<TLRPC.Dialog> result = new ArrayList<>();
        for (int i = 0; i < dialogs.size(); i++) {
            TLRPC.Dialog dialog = dialogs.valueAt(i);
            if (dialog != null && dialog.unread_count > 0) {
                long dialogId = dialog.id;
                if (DialogObject.isChatDialog(dialogId) && !feedConfig.isExcluded(dialogId) && (includeArchived || dialog.folder_id != 1) && FeedController.isEligibleChannel(messagesController.getChat(Long.valueOf(-dialogId)))) {
                    result.add(dialog);
                }
            }
        }
        return result;
    }
}
