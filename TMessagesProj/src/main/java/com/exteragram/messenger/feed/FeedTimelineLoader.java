package com.exteragram.messenger.feed;

import android.text.TextUtils;

import androidx.collection.LongSparseArray;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteException;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Loads the merged feed timeline directly from the messages database.
 * Enumerates eligible channel dialogs, then pages messages across them with
 * tuple-style (date, uid, mid) cursors. All queries run on the caller thread
 * (must be invoked from the storage queue).
 */
final class FeedTimelineLoader {
    private final AtomicInteger channelCacheEpoch = new AtomicInteger();
    private volatile ChannelSet channelSetCache;
    private final int currentAccount;

    public static final class ChannelEnumeration {
        int cacheEpoch;
        int configGeneration;
        boolean failed;
        boolean hasChannels;
        final ArrayList<ChannelSnapshot> included = new ArrayList<>();
        final ArrayList<TLRPC.Chat> channels = new ArrayList<>();
    }

    public static final class NewerPage {
        boolean failed;
        boolean hasMore;
        final ArrayList<TLRPC.Message> messages = new ArrayList<>();
        final ArrayList<TLRPC.User> users = new ArrayList<>();
        final ArrayList<TLRPC.Chat> chats = new ArrayList<>();
        final Cursor first = new Cursor();
    }

    public static final class OlderPage {
        boolean failed;
        boolean hasIncomplete;
        int lastChunkRowCount;
        final ArrayList<TLRPC.Message> messages = new ArrayList<>();
        final ArrayList<TLRPC.User> users = new ArrayList<>();
        final ArrayList<TLRPC.Chat> chats = new ArrayList<>();
        final ArrayList<long[]> backfillCandidates = new ArrayList<>();
        final Cursor last = new Cursor();
        final Cursor first = new Cursor();
    }

    public static final class WindowPage {
        boolean failed;
        boolean truncated;
        final ArrayList<TLRPC.Message> messages = new ArrayList<>();
        final ArrayList<TLRPC.User> users = new ArrayList<>();
        final ArrayList<TLRPC.Chat> chats = new ArrayList<>();
    }

    public FeedTimelineLoader(int account) {
        this.currentAccount = account;
    }

    public static final class Cursor {
        int date;
        int mid;
        long uid;

        public boolean isEmpty() {
            return date == 0;
        }

        public void set(int date, long uid, int mid) {
            this.date = date;
            this.uid = uid;
            this.mid = mid;
        }
    }

    public static final class ChannelSnapshot {
        int depthDate;
        int depthMid;
        final long dialogId;
        boolean hasCached;
        boolean hasHole;
        int holeEnd;
        boolean incomplete;
        boolean localStartReached;
        final int readInboxMax;
        final int topMessage;
        final int unreadCount;

        public ChannelSnapshot(long dialogId, int readInboxMax, int unreadCount, int topMessage) {
            this.dialogId = dialogId;
            this.readInboxMax = readInboxMax;
            this.unreadCount = unreadCount;
            this.topMessage = topMessage;
        }
    }

    public static final class ChannelSet {
        final int configGen;
        boolean failed;
        boolean hasChannels;
        final int sessionGen;
        final ArrayList<long[]> includedRows = new ArrayList<>();
        final ArrayList<TLRPC.Chat> channels = new ArrayList<>();

        public ChannelSet(int sessionGen, int configGen) {
            this.sessionGen = sessionGen;
            this.configGen = configGen;
        }
    }

    public synchronized void invalidateChannelCache() {
        channelCacheEpoch.incrementAndGet();
        channelSetCache = null;
    }

    public ChannelEnumeration enumerateChannels(FeedConfig feedConfig, int sessionGen, boolean forceRebuild) {
        ChannelSet channelSet;
        int epoch;
        boolean rebuild = forceRebuild;
        int attempts = 0;
        while (true) {
            synchronized (this) {
                channelSet = channelSetCache;
                epoch = channelCacheEpoch.get();
            }
            int generation = feedConfig.getGeneration();
            if (rebuild || channelSet == null || channelSet.sessionGen != sessionGen || channelSet.configGen != generation) {
                channelSet = buildChannelSet(feedConfig.getIncludeArchived(), new HashSet<>(feedConfig.getExcludedSnapshot()), sessionGen, generation);
                if (!channelSet.failed) {
                    synchronized (this) {
                        if (epoch == channelCacheEpoch.get()) {
                            channelSetCache = channelSet;
                        }
                    }
                }
            }
            if (channelSet.failed || attempts >= 3) {
                break;
            }
            synchronized (this) {
                if (epoch == channelCacheEpoch.get()) {
                    break;
                }
            }
            attempts++;
            rebuild = true;
        }
        ChannelEnumeration enumeration = new ChannelEnumeration();
        enumeration.hasChannels = channelSet.hasChannels;
        enumeration.failed = channelSet.failed;
        enumeration.configGeneration = channelSet.configGen;
        enumeration.cacheEpoch = epoch;
        enumeration.channels.addAll(channelSet.channels);
        for (int i = 0; i < channelSet.includedRows.size(); i++) {
            long[] row = channelSet.includedRows.get(i);
            enumeration.included.add(new ChannelSnapshot(row[0], (int) row[1], (int) row[2], (int) row[3]));
        }
        return enumeration;
    }

    public synchronized int getChannelCacheEpoch() {
        return channelCacheEpoch.get();
    }

    public synchronized boolean isEnumerationCurrent(ChannelEnumeration enumeration) {
        return enumeration != null && enumeration.cacheEpoch == channelCacheEpoch.get();
    }

    private ChannelSet buildChannelSet(boolean includeArchived, HashSet<Long> excludedDialogs, int sessionGen, int configGen) {
        MessagesStorage storage = MessagesStorage.getInstance(currentAccount);
        ChannelSet channelSet = new ChannelSet(sessionGen, configGen);
        ArrayList<long[]> dialogRows = new ArrayList<>();
        ArrayList<Long> chatIds = new ArrayList<>();
        try {
            SQLiteCursor cursor = storage.getDatabase().queryFinalized("SELECT did, inbox_max, unread_count, last_mid, folder_id FROM dialogs WHERE did < 0", new Object[0]);
            while (cursor.next()) {
                long dialogId = cursor.longValue(0);
                if (DialogObject.isChatDialog(dialogId)) {
                    dialogRows.add(new long[]{dialogId, cursor.intValue(1), cursor.intValue(2), cursor.intValue(3), cursor.intValue(4)});
                    chatIds.add(-dialogId);
                }
            }
            cursor.dispose();
            if (!dialogRows.isEmpty()) {
                ArrayList<TLRPC.Chat> chats = new ArrayList<>();
                try {
                    storage.getChatsInternal(TextUtils.join(",", chatIds), chats);
                    LongSparseArray<TLRPC.Chat> chatsById = new LongSparseArray<>();
                    for (int i = 0; i < chats.size(); i++) {
                        chatsById.put(chats.get(i).id, chats.get(i));
                    }
                    for (int i = 0; i < dialogRows.size(); i++) {
                        long[] row = dialogRows.get(i);
                        long dialogId = row[0];
                        TLRPC.Chat chat = chatsById.get(-dialogId);
                        if (FeedController.isEligibleChannel(chat) && (row[4] != 1 || includeArchived)) {
                            channelSet.hasChannels = true;
                            channelSet.channels.add(chat);
                            if (!excludedDialogs.contains(dialogId)) {
                                channelSet.includedRows.add(new long[]{dialogId, row[1], row[2], row[3]});
                            }
                        }
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                    channelSet.failed = true;
                    return channelSet;
                }
            }
            return channelSet;
        } catch (Exception e) {
            FileLog.e(e);
            channelSet.failed = true;
            return channelSet;
        }
    }

    public OlderPage loadOlderPage(ArrayList<ChannelSnapshot> channels, Cursor oldestCursor, HashSet<Long> exhaustedDialogs) {
        OlderPage page = new OlderPage();
        boolean initialLoad = oldestCursor.isEmpty();
        page.last.set(oldestCursor.date, oldestCursor.uid, oldestCursor.mid);
        try {
            ArrayList<Long> dialogIds = new ArrayList<>(channels.size());
            for (int i = 0; i < channels.size(); i++) {
                dialogIds.add(channels.get(i).dialogId);
            }
            String dialogIdsSql = TextUtils.join(",", dialogIds);
            MessagesStorage storage = MessagesStorage.getInstance(currentAccount);
            ArrayList<Long> usersToLoad = new ArrayList<>();
            ArrayList<Long> chatsToLoad = new ArrayList<>();
            HashMap<Long, Integer> holeEndsByDialog = new HashMap<>();
            SQLiteCursor cursor = storage.getDatabase().queryFinalized("SELECT uid, max(end) FROM messages_holes WHERE uid IN (" + dialogIdsSql + ") GROUP BY uid", new Object[0]);
            while (cursor.next()) {
                holeEndsByDialog.put(cursor.longValue(0), cursor.intValue(1));
            }
            cursor.dispose();
            for (int i = 0; i < channels.size(); i++) {
                ChannelSnapshot snapshot = channels.get(i);
                Integer holeEnd = holeEndsByDialog.get(snapshot.dialogId);
                snapshot.hasHole = holeEnd != null;
                snapshot.holeEnd = holeEnd != null ? holeEnd : 0;
            }
            loadChannelDepths(storage, channels);
            // Lower date bound shared by all incomplete channels. Stays 0 when every
            // channel already has its full local history, which means "no date filter".
            int minDepthDate = 0;
            for (int i = 0; i < channels.size(); i++) {
                ChannelSnapshot snapshot = channels.get(i);
                snapshot.incomplete = !snapshot.localStartReached && !exhaustedDialogs.contains(snapshot.dialogId);
                if (snapshot.incomplete) {
                    page.hasIncomplete = true;
                    minDepthDate = Math.max(minDepthDate, snapshot.depthDate);
                    long backfillMid;
                    if (snapshot.hasCached) {
                        backfillMid = snapshot.depthMid;
                    } else {
                        int maxKnownId = Math.max(snapshot.holeEnd, snapshot.topMessage);
                        backfillMid = maxKnownId <= 0 ? 0L : (maxKnownId + 1);
                    }
                    page.backfillCandidates.add(new long[]{snapshot.dialogId, backfillMid, snapshot.depthDate});
                }
            }
            page.backfillCandidates.sort(Comparator.comparingLong((long[] row) -> row[2]).reversed());
            // An incomplete channel without any cached message reports MAX_VALUE depth:
            // nothing can be paged locally until the backfill round brings history in.
            if (minDepthDate == Integer.MAX_VALUE) {
                return page;
            }
            Cursor unreadBoundary = initialLoad ? findUnreadBoundary(storage, channels, minDepthDate) : null;
            int loadedRows = 0;
            do {
                int chunkRows = loadChunk(storage, dialogIdsSql, minDepthDate, page, usersToLoad, chatsToLoad);
                page.lastChunkRowCount = chunkRows;
                loadedRows += chunkRows;
                if (chunkRows < 30 || unreadBoundary == null || loadedRows >= 200) {
                    break;
                }
            } while (compareDesc(page.last, unreadBoundary) < 0);
            completeTrailingAlbum(storage, page, usersToLoad, chatsToLoad);
            for (int i = 0; i < dialogIds.size(); i++) {
                long chatId = -dialogIds.get(i);
                if (!chatsToLoad.contains(chatId)) {
                    chatsToLoad.add(chatId);
                }
            }
            if (!usersToLoad.isEmpty()) {
                storage.getUsersInternal(usersToLoad, page.users);
            }
            if (!chatsToLoad.isEmpty()) {
                storage.getChatsInternal(TextUtils.join(",", chatsToLoad), page.chats);
            }
        } catch (Exception e) {
            FileLog.e(e);
            page.failed = true;
        }
        clusterGroupedMessages(page.messages);
        return page;
    }

    private int loadChunk(MessagesStorage storage, String dialogIdsSql, int minDepthDate, OlderPage page, ArrayList<Long> usersToLoad, ArrayList<Long> chatsToLoad) throws SQLiteException {
        StringBuilder sb = new StringBuilder("SELECT data, mid, date, uid FROM messages_v2 WHERE uid IN (");
        sb.append(dialogIdsSql);
        sb.append(") AND mid > 0");
        if (minDepthDate > 0) {
            sb.append(" AND date >= ");
            sb.append(minDepthDate);
        }
        if (!page.last.isEmpty()) {
            appendCursorBound(sb, page.last, true, false);
        }
        sb.append(" ORDER BY date DESC, uid DESC, mid DESC LIMIT ");
        sb.append(30);
        int rowCount = 0;
        SQLiteCursor cursor = storage.getDatabase().queryFinalized(sb.toString(), new Object[0]);
        while (cursor.next()) {
            rowCount++;
            page.last.set(cursor.intValue(2), cursor.longValue(3), cursor.intValue(1));
            if (page.first.isEmpty()) {
                page.first.set(page.last.date, page.last.uid, page.last.mid);
            }
            TLRPC.Message message = readMessage(cursor);
            if (message != null) {
                page.messages.add(message);
                MessagesStorage.addUsersAndChatsFromMessage(message, usersToLoad, chatsToLoad, null);
            }
        }
        cursor.dispose();
        return rowCount;
    }

    private Cursor findUnreadBoundary(MessagesStorage storage, ArrayList<ChannelSnapshot> channels, int minDepthDate) {
        StringBuilder channelConditions = new StringBuilder();
        Cursor result = null;
        int conditionCount = 0;
        for (int i = 0; i < channels.size(); i++) {
            ChannelSnapshot snapshot = channels.get(i);
            if (snapshot.topMessage > snapshot.readInboxMax || snapshot.unreadCount > 0) {
                if (channelConditions.length() > 0) {
                    channelConditions.append(" OR ");
                }
                channelConditions.append("uid = ");
                channelConditions.append(snapshot.dialogId);
                channelConditions.append(" AND mid > ");
                channelConditions.append(snapshot.readInboxMax);
                conditionCount++;
            }
            if (conditionCount > 0 && (conditionCount == 64 || i == channels.size() - 1)) {
                Cursor boundary = queryUnreadBoundary(storage, channelConditions, minDepthDate);
                if (boundary != null && (result == null || compareDesc(boundary, result) > 0)) {
                    result = boundary;
                }
                channelConditions.setLength(0);
                conditionCount = 0;
            }
        }
        return result;
    }

    private Cursor queryUnreadBoundary(MessagesStorage storage, StringBuilder channelConditions, int minDepthDate) {
        StringBuilder sb = new StringBuilder("SELECT date, uid, mid FROM messages_v2 WHERE mid > 0 AND (");
        sb.append(channelConditions);
        sb.append(")");
        if (minDepthDate > 0) {
            sb.append(" AND date >= ");
            sb.append(minDepthDate);
        }
        sb.append(" ORDER BY date ASC, uid ASC, mid ASC LIMIT 1");
        try {
            SQLiteCursor cursor = storage.getDatabase().queryFinalized(sb.toString(), new Object[0]);
            try {
                if (!cursor.next()) {
                    cursor.dispose();
                    return null;
                }
                Cursor result = new Cursor();
                result.set(cursor.intValue(0), cursor.longValue(1), cursor.intValue(2));
                cursor.dispose();
                return result;
            } catch (Throwable th) {
                cursor.dispose();
                throw th;
            }
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private static void appendCursorBound(StringBuilder sb, Cursor cursor, boolean olderBound, boolean inclusive) {
        String operator = olderBound ? "<" : ">";
        sb.append(" AND (date ");
        sb.append(operator);
        sb.append(' ');
        sb.append(cursor.date);
        sb.append(" OR date = ");
        sb.append(cursor.date);
        sb.append(" AND (uid ");
        sb.append(operator);
        sb.append(' ');
        sb.append(cursor.uid);
        sb.append(" OR uid = ");
        sb.append(cursor.uid);
        sb.append(" AND mid ");
        sb.append(operator);
        sb.append(inclusive ? "= " : " ");
        sb.append(cursor.mid);
        sb.append("))");
    }

    private static int compareDesc(Cursor cursor, Cursor other) {
        if (cursor.date != other.date) {
            return cursor.date > other.date ? -1 : 1;
        }
        if (cursor.uid != other.uid) {
            return cursor.uid > other.uid ? -1 : 1;
        }
        return -Integer.compare(cursor.mid, other.mid);
    }

    public NewerPage loadNewerPage(ArrayList<ChannelSnapshot> channels, Cursor newestCursor) {
        NewerPage page = new NewerPage();
        page.first.set(newestCursor.date, newestCursor.uid, newestCursor.mid);
        try {
            ArrayList<Long> dialogIds = new ArrayList<>(channels.size());
            for (int i = 0; i < channels.size(); i++) {
                dialogIds.add(channels.get(i).dialogId);
            }
            MessagesStorage storage = MessagesStorage.getInstance(currentAccount);
            ArrayList<Long> usersToLoad = new ArrayList<>();
            ArrayList<Long> chatsToLoad = new ArrayList<>();
            StringBuilder sb = new StringBuilder("SELECT data, mid, date, uid FROM messages_v2 WHERE uid IN (");
            sb.append(TextUtils.join(",", dialogIds));
            sb.append(") AND mid > 0");
            appendCursorBound(sb, newestCursor, false, false);
            sb.append(" ORDER BY date ASC, uid ASC, mid ASC LIMIT ");
            sb.append(50);
            SQLiteCursor cursor = storage.getDatabase().queryFinalized(sb.toString(), new Object[0]);
            int rowCount = 0;
            while (cursor.next()) {
                rowCount++;
                page.first.set(cursor.intValue(2), cursor.longValue(3), cursor.intValue(1));
                TLRPC.Message message = readMessage(cursor);
                if (message != null) {
                    page.messages.add(message);
                    MessagesStorage.addUsersAndChatsFromMessage(message, usersToLoad, chatsToLoad, null);
                }
            }
            cursor.dispose();
            page.hasMore = rowCount == 50;
            if (!usersToLoad.isEmpty()) {
                storage.getUsersInternal(usersToLoad, page.users);
            }
            if (!chatsToLoad.isEmpty()) {
                storage.getChatsInternal(TextUtils.join(",", chatsToLoad), page.chats);
            }
        } catch (Exception e) {
            FileLog.e(e);
            page.failed = true;
        }
        clusterGroupedMessages(page.messages);
        return page;
    }

    public WindowPage loadChannelWindow(ArrayList<Long> dialogIds, Cursor newestCursor, Cursor oldestCursor) {
        WindowPage page = new WindowPage();
        if (dialogIds.isEmpty() || newestCursor.isEmpty() || oldestCursor.isEmpty()) {
            return page;
        }
        try {
            MessagesStorage storage = MessagesStorage.getInstance(currentAccount);
            ArrayList<Long> usersToLoad = new ArrayList<>();
            ArrayList<Long> chatsToLoad = new ArrayList<>();
            StringBuilder sb = new StringBuilder("SELECT data, mid, date, uid FROM messages_v2 WHERE uid IN (");
            sb.append(TextUtils.join(",", dialogIds));
            sb.append(") AND mid > 0");
            appendCursorBound(sb, newestCursor, true, true);
            appendCursorBound(sb, oldestCursor, false, true);
            sb.append(" ORDER BY date ASC, uid ASC, mid ASC LIMIT ");
            sb.append(501);
            SQLiteCursor cursor = storage.getDatabase().queryFinalized(sb.toString(), new Object[0]);
            int rowCount = 0;
            while (true) {
                if (!cursor.next()) {
                    break;
                }
                rowCount++;
                if (rowCount > 500) {
                    page.truncated = true;
                    break;
                }
                TLRPC.Message message = readMessage(cursor);
                if (message != null) {
                    page.messages.add(message);
                    MessagesStorage.addUsersAndChatsFromMessage(message, usersToLoad, chatsToLoad, null);
                }
            }
            cursor.dispose();
            if (!usersToLoad.isEmpty()) {
                storage.getUsersInternal(usersToLoad, page.users);
            }
            if (!chatsToLoad.isEmpty()) {
                storage.getChatsInternal(TextUtils.join(",", chatsToLoad), page.chats);
            }
        } catch (Exception e) {
            FileLog.e(e);
            page.failed = true;
            page.messages.clear();
            page.users.clear();
            page.chats.clear();
        }
        clusterGroupedMessages(page.messages);
        return page;
    }

    private void completeTrailingAlbum(MessagesStorage storage, OlderPage page, ArrayList<Long> usersToLoad, ArrayList<Long> chatsToLoad) throws SQLiteException {
        if (page.messages.isEmpty()) {
            return;
        }
        TLRPC.Message last = page.messages.get(page.messages.size() - 1);
        if (last.grouped_id == 0) {
            return;
        }
        SQLiteCursor cursor = storage.getDatabase().queryFinalized("SELECT data, mid, date, uid FROM messages_v2 WHERE uid = " + last.dialog_id + " AND mid > 0 AND mid < " + last.id + " ORDER BY date DESC, mid DESC LIMIT 9", new Object[0]);
        while (cursor.next()) {
            TLRPC.Message message = readMessage(cursor);
            if (message == null || message.grouped_id != last.grouped_id) {
                break;
            }
            page.messages.add(message);
            MessagesStorage.addUsersAndChatsFromMessage(message, usersToLoad, chatsToLoad, null);
        }
        cursor.dispose();
    }

    private TLRPC.Message readMessage(SQLiteCursor cursor) throws SQLiteException {
        NativeByteBuffer data = cursor.byteBufferValue(0);
        if (data == null) {
            return null;
        }
        TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
        if (message == null) {
            data.reuse();
            return null;
        }
        message.readAttachPath(data, UserConfig.getInstance(currentAccount).clientUserId);
        data.reuse();
        if (message instanceof TLRPC.TL_messageEmpty || message.action != null) {
            return null;
        }
        message.id = cursor.intValue(1);
        message.date = cursor.intValue(2);
        message.dialog_id = cursor.longValue(3);
        return message;
    }

    private static void loadChannelDepths(MessagesStorage storage, ArrayList<ChannelSnapshot> channels) throws SQLiteException {
        LongSparseArray<ChannelSnapshot> snapshotsByDialog = new LongSparseArray<>(channels.size());
        for (int i = 0; i < channels.size(); i++) {
            ChannelSnapshot snapshot = channels.get(i);
            snapshot.depthMid = 0;
            snapshot.depthDate = Integer.MAX_VALUE;
            snapshot.hasCached = false;
            snapshot.localStartReached = false;
            snapshotsByDialog.put(snapshot.dialogId, snapshot);
        }
        int index = 0;
        while (index < channels.size()) {
            int end = Math.min(index + 64, channels.size());
            StringBuilder sb = new StringBuilder();
            while (index < end) {
                if (sb.length() > 0) {
                    sb.append(" UNION ALL ");
                }
                ChannelSnapshot snapshot = channels.get(index);
                sb.append("SELECT uid, mid, date FROM (SELECT uid, mid, date FROM messages_v2 WHERE uid = ");
                sb.append(snapshot.dialogId);
                sb.append(" AND mid >= ");
                sb.append(Math.max(snapshot.holeEnd, 1));
                sb.append(" ORDER BY date ASC, mid ASC LIMIT 1)");
                index++;
            }
            SQLiteCursor cursor = storage.getDatabase().queryFinalized(sb.toString(), new Object[0]);
            while (cursor.next()) {
                try {
                    ChannelSnapshot snapshot = snapshotsByDialog.get(cursor.longValue(0));
                    if (snapshot != null) {
                        snapshot.depthMid = cursor.intValue(1);
                        snapshot.depthDate = cursor.intValue(2);
                        snapshot.hasCached = true;
                    }
                } catch (Throwable th) {
                    cursor.dispose();
                    throw th;
                }
            }
            cursor.dispose();
        }
        for (int i = 0; i < channels.size(); i++) {
            ChannelSnapshot snapshot = channels.get(i);
            snapshot.localStartReached = !snapshot.hasHole && snapshot.hasCached;
        }
    }

    private static void clusterGroupedMessages(ArrayList<TLRPC.Message> messages) {
        if (messages.size() < 3) {
            return;
        }
        HashMap<Long, ArrayList<TLRPC.Message>> byGroup = new HashMap<>();
        boolean hasGroup = false;
        for (int i = 0; i < messages.size(); i++) {
            long groupedId = messages.get(i).grouped_id;
            if (groupedId != 0) {
                ArrayList<TLRPC.Message> group = byGroup.get(groupedId);
                if (group == null) {
                    group = new ArrayList<>();
                    byGroup.put(groupedId, group);
                } else {
                    hasGroup = true;
                }
                group.add(messages.get(i));
            }
        }
        if (hasGroup) {
            ArrayList<TLRPC.Message> clustered = new ArrayList<>(messages.size());
            HashSet<Long> emitted = new HashSet<>();
            for (int i = 0; i < messages.size(); i++) {
                TLRPC.Message message = messages.get(i);
                long groupedId = message.grouped_id;
                if (groupedId == 0) {
                    clustered.add(message);
                } else if (emitted.add(groupedId)) {
                    clustered.addAll(byGroup.get(groupedId));
                }
            }
            messages.clear();
            messages.addAll(clustered);
        }
    }
}
