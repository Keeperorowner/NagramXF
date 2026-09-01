package com.exteragram.messenger.feed;

import org.telegram.messenger.MessageObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;

/**
 * In-memory feed timeline: holds the merged message list, paging cursors and
 * the set of hidden (excluded) dialogs. Rows keep a stable timeline order
 * (date desc, dialogId desc, id desc).
 */
public final class FeedStore {
    private int count;
    private boolean endReached;
    private final ArrayList<MessageObject> messages = new ArrayList<>();
    private final FeedMessageIdentityMap identityMap = new FeedMessageIdentityMap();
    private final HashSet<Long> hiddenDialogIds = new HashSet<>();
    private final FeedTimelineLoader.Cursor oldestCursor = new FeedTimelineLoader.Cursor();
    private final FeedTimelineLoader.Cursor newestCursor = new FeedTimelineLoader.Cursor();

    public ArrayList<MessageObject> getMessages() {
        return messages;
    }

    public ArrayList<MessageObject> getVisibleMessages() {
        if (hiddenDialogIds.isEmpty()) {
            return new ArrayList<>(messages);
        }
        ArrayList<MessageObject> result = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            MessageObject message = messages.get(i);
            if (message != null && !hiddenDialogIds.contains(message.getDialogId())) {
                result.add(message);
            }
        }
        return result;
    }

    public boolean isEmpty() {
        return messages.isEmpty();
    }

    public int getVisibleCount() {
        if (hiddenDialogIds.isEmpty()) {
            return messages.size();
        }
        int result = 0;
        for (int i = 0; i < messages.size(); i++) {
            MessageObject message = messages.get(i);
            if (message != null && !hiddenDialogIds.contains(message.getDialogId())) {
                result++;
            }
        }
        return result;
    }

    public boolean hasMessagesForDialog(long dialogId) {
        for (int i = 0; i < messages.size(); i++) {
            MessageObject message = messages.get(i);
            if (message != null && message.getDialogId() == dialogId) {
                return true;
            }
        }
        return false;
    }

    public HashSet<Long> getLoadedDialogIds() {
        HashSet<Long> result = new HashSet<>();
        for (int i = 0; i < messages.size(); i++) {
            MessageObject message = messages.get(i);
            if (message != null) {
                result.add(message.getDialogId());
            }
        }
        return result;
    }

    public HashSet<Long> getHiddenSnapshot() {
        return new HashSet<>(hiddenDialogIds);
    }

    public boolean setHidden(long dialogId, boolean hidden) {
        boolean changed = hidden ? hiddenDialogIds.add(dialogId) : hiddenDialogIds.remove(dialogId);
        if (changed) {
            updateCount();
        }
        return changed;
    }

    public boolean applyIncludedDialogs(HashSet<Long> includedDialogs) {
        HashSet<Long> loaded = getLoadedDialogIds();
        boolean changed = false;
        for (Long dialogId : loaded) {
            if (!includedDialogs.contains(dialogId)) {
                changed |= hiddenDialogIds.add(dialogId);
            }
        }
        java.util.Iterator<Long> it = hiddenDialogIds.iterator();
        while (it.hasNext()) {
            Long next = it.next();
            if (includedDialogs.contains(next) || !loaded.contains(next)) {
                it.remove();
                changed = true;
            }
        }
        if (changed) {
            updateCount();
        }
        return changed;
    }

    public FeedTimelineLoader.Cursor getOldestCursor() {
        return oldestCursor;
    }

    public FeedTimelineLoader.Cursor getNewestCursor() {
        return newestCursor;
    }

    public boolean isEndReached() {
        return endReached;
    }

    public void setEndReached(boolean value) {
        endReached = value;
        updateCount();
    }

    public int getCount() {
        return count;
    }

    private void updateCount() {
        if (!messages.isEmpty()) {
            count = (endReached ? 0 : 3) + getVisibleCount();
        } else {
            count = 0;
        }
    }

    public void clear() {
        messages.clear();
        identityMap.clear();
        hiddenDialogIds.clear();
        endReached = false;
        count = 0;
        oldestCursor.set(0, 0L, 0);
        newestCursor.set(0, 0L, 0);
    }

    public ArrayList<MessageObject> appendMessages(ArrayList<MessageObject> newMessages, boolean atTop) {
        ArrayList<MessageObject> appended = new ArrayList<>(newMessages.size());
        for (int i = 0; i < newMessages.size(); i++) {
            MessageObject message = newMessages.get(i);
            if (identityMap.register(message)) {
                appended.add(message);
            }
        }
        if (atTop) {
            ArrayList<MessageObject> reversed = new ArrayList<>(appended);
            Collections.reverse(reversed);
            messages.addAll(0, reversed);
        } else {
            messages.addAll(appended);
        }
        updateCount();
        return appended;
    }

    public ArrayList<MessageObject> mergeRows(ArrayList<MessageObject> newMessages) {
        ArrayList<MessageObject> merged = new ArrayList<>(newMessages.size());
        for (int i = 0; i < newMessages.size(); i++) {
            MessageObject message = newMessages.get(i);
            if (identityMap.register(message)) {
                merged.add(message);
            }
        }
        int insertIndex = 0;
        int i = 0;
        while (i < merged.size()) {
            MessageObject message = merged.get(i);
            int groupEnd = i + 1;
            long groupId = message.getGroupId();
            while (groupId != 0 && groupEnd < merged.size() && merged.get(groupEnd).getGroupId() == groupId && merged.get(groupEnd).getDialogId() == message.getDialogId()) {
                groupEnd++;
            }
            insertIndex = findMergeIndex(message, insertIndex);
            while (i < groupEnd) {
                messages.add(insertIndex, merged.get(i));
                i++;
                insertIndex++;
            }
        }
        updateCount();
        return merged;
    }

    private int findMergeIndex(MessageObject message, int startIndex) {
        while (startIndex < messages.size()) {
            MessageObject existing = messages.get(startIndex);
            if (existing == null) {
                startIndex++;
                continue;
            }
            if (compareTimeline(existing.messageOwner.date, existing.getDialogId(), existing.getRealId(), message.messageOwner.date, message.getDialogId(), message.getRealId()) < 0) {
                break;
            }
            startIndex++;
        }
        while (startIndex > 0 && startIndex < messages.size()) {
            MessageObject before = messages.get(startIndex - 1);
            MessageObject after = messages.get(startIndex);
            if (before == null || after == null || before.getGroupId() == 0 || before.getGroupId() != after.getGroupId() || before.getDialogId() != after.getDialogId()) {
                break;
            }
            startIndex++;
        }
        return startIndex;
    }

    public void replaceMessage(MessageObject oldMessage, MessageObject newMessage) {
        if (oldMessage == null || newMessage == null) {
            return;
        }
        int index = messages.indexOf(oldMessage);
        if (index >= 0) {
            messages.set(index, newMessage);
        }
        identityMap.replace(newMessage);
    }

    public ArrayList<Integer> deleteMessages(long dialogId, ArrayList<Integer> messageIds, boolean[] changedOut) {
        ArrayList<Integer> removedIds = new ArrayList<>();
        if (messageIds == null) {
            return removedIds;
        }
        HashSet<Integer> requested = new HashSet<>(messageIds);
        HashSet<Integer> reported = new HashSet<>();
        boolean changed = false;
        for (int i = 0; i < messageIds.size(); i++) {
            MessageObject byRealId = identityMap.getByRealId(dialogId, messageIds.get(i));
            if (byRealId != null) {
                changed |= messages.remove(byRealId);
                purgeRow(byRealId, removedIds, reported);
            }
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            MessageObject message = messages.get(i);
            if (message != null && message.getDialogId() == dialogId && requested.contains(message.getRealId())) {
                messages.remove(i);
                purgeRow(message, removedIds, reported);
                changed = true;
            }
        }
        if (changed) {
            onRowsRemoved();
        }
        changedOut[0] = changed;
        return removedIds;
    }

    public ArrayList<Integer> deleteHistory(long dialogId, int maxId, boolean[] changedOut) {
        ArrayList<Integer> removedIds = new ArrayList<>();
        HashSet<Integer> reported = new HashSet<>();
        boolean changed = false;
        for (int i = messages.size() - 1; i >= 0; i--) {
            MessageObject message = messages.get(i);
            if (message != null && message.getDialogId() == dialogId && message.getRealId() > 0 && message.getRealId() <= maxId) {
                messages.remove(i);
                purgeRow(message, removedIds, reported);
                changed = true;
            }
        }
        if (changed) {
            if (!hasMessagesForDialog(dialogId)) {
                hiddenDialogIds.remove(dialogId);
            }
            onRowsRemoved();
        }
        changedOut[0] = changed;
        return removedIds;
    }

    public boolean trim(int cap) {
        if (messages.size() <= cap) {
            return false;
        }
        MessageObject boundary = messages.get(cap - 1);
        int boundaryDate = boundary.messageOwner.date;
        long boundaryDialogId = boundary.getDialogId();
        int boundaryRealId = boundary.getRealId();
        boolean changed = false;
        for (int i = messages.size() - 1; i >= 0; i--) {
            MessageObject message = messages.get(i);
            if (message != null && compareTimeline(message.messageOwner.date, message.getDialogId(), message.getRealId(), boundaryDate, boundaryDialogId, boundaryRealId) < 0) {
                messages.remove(i);
                identityMap.releaseRow(message);
                changed = true;
            }
        }
        if (!changed) {
            return false;
        }
        if (messages.isEmpty()) {
            oldestCursor.set(0, 0L, 0);
        } else {
            int oldestDate = 0;
            int oldestRealId = 0;
            long oldestDialogId = 0;
            for (int i = 0; i < messages.size(); i++) {
                MessageObject message = messages.get(i);
                if (message != null && (oldestDate == 0 || compareTimeline(message.messageOwner.date, message.getDialogId(), message.getRealId(), oldestDate, oldestDialogId, oldestRealId) < 0)) {
                    oldestDate = message.messageOwner.date;
                    oldestDialogId = message.getDialogId();
                    oldestRealId = message.getRealId();
                }
            }
            oldestCursor.set(oldestDate, oldestDialogId, oldestRealId);
        }
        endReached = false;
        updateCount();
        return true;
    }

    private void onRowsRemoved() {
        if (!rebuildPagingCursorsFromLoadedRows()) {
            endReached = false;
        }
        updateCount();
    }

    /**
     * Recomputes the paging cursors from the rows currently held in memory.
     * Returns true when valid cursors were rebuilt, false when no paging rows
     * remain (caller should reset the endReached flag).
     */
    private boolean rebuildPagingCursorsFromLoadedRows() {
        int newestDate = 0;
        int newestMid = 0;
        long newestUid = 0;
        int oldestDate = 0;
        int oldestMid = 0;
        long oldestUid = 0;
        for (int i = 0; i < messages.size(); i++) {
            MessageObject message = messages.get(i);
            if (!isPagingRow(message)) {
                continue;
            }
            int date = message.messageOwner.date;
            long dialogId = message.getDialogId();
            int realId = message.getRealId();
            if (newestDate == 0 || compareTimeline(date, dialogId, realId, newestDate, newestUid, newestMid) > 0) {
                newestDate = date;
                newestUid = dialogId;
                newestMid = realId;
            }
            if (oldestDate == 0 || compareTimeline(date, dialogId, realId, oldestDate, oldestUid, oldestMid) < 0) {
                oldestDate = date;
                oldestUid = dialogId;
                oldestMid = realId;
            }
        }
        if (newestDate == 0) {
            oldestCursor.set(0, 0L, 0);
            newestCursor.set(0, 0L, 0);
            return false;
        }
        if (!oldestCursor.isEmpty() && compareTimeline(oldestDate, oldestUid, oldestMid, oldestCursor.date, oldestCursor.uid, oldestCursor.mid) > 0) {
            endReached = false;
        }
        newestCursor.set(newestDate, newestUid, newestMid);
        oldestCursor.set(oldestDate, oldestUid, oldestMid);
        return true;
    }

    private static boolean isPagingRow(MessageObject message) {
        return message != null && !message.isDateObject && message.messageOwner != null && message.getRealId() > 0;
    }

    public static int compareTimeline(int date1, long dialogId1, int id1, int date2, long dialogId2, int id2) {
        if (date1 != date2) {
            return Integer.compare(date1, date2);
        }
        if (dialogId1 != dialogId2) {
            return Long.compare(dialogId1, dialogId2);
        }
        return Integer.compare(id1, id2);
    }

    private void purgeRow(MessageObject message, ArrayList<Integer> removedIds, HashSet<Integer> reported) {
        identityMap.purge(message);
        if (reported.add(message.getId())) {
            removedIds.add(message.getId());
        }
    }

    public boolean hasNoSyntheticIds() {
        return identityMap.isEmpty();
    }

    public MessageObject getMessage(long dialogId, int id) {
        return identityMap.getByAnyId(dialogId, id);
    }

    public int resolveRealMessageId(long dialogId, int id) {
        return identityMap.resolveRealMessageId(dialogId, id);
    }

    public long resolveRealDialogId(int id) {
        return identityMap.resolveRealDialogId(id);
    }
}
