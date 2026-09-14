package com.radolyn.ayugram.proprietary;

import android.text.TextUtils;

import androidx.collection.LongSparseArray;

import com.radolyn.ayugram.database.entities.DeletedMessageFull;
import com.radolyn.ayugram.database.entities.DeletedMessageReaction;
import com.radolyn.ayugram.messages.AyuMessagesController;
import com.radolyn.ayugram.utils.AyuMessageUtils;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.function.Predicate;

public abstract class AyuHistoryHook {

    public static AyuHistoryPagination.Page<DeletedMessageFull> doHookSync(
            int currentAccount,
            TLRPC.messages_Messages messagesRes,
            LongSparseArray<TLRPC.User> usersDict,
            LongSparseArray<TLRPC.Chat> chatsDict,
            long dialogId, long topicId, int loadType,
            int count, int offsetId, int offsetDate, boolean isCache,
            boolean isChannelComment, long threadMessageId, boolean isTopic
    ) {
        boolean encrypted = DialogObject.isEncryptedDialog(dialogId);
        int minId = Integer.MAX_VALUE;
        int maxId = Integer.MIN_VALUE;
        boolean onlyServiceMessages = true;
        Set<Integer> existingIds = new HashSet<>();
        for (int i = 0; i < messagesRes.messages.size(); i++) {
            TLRPC.Message message = messagesRes.messages.get(i);
            int id = message.id;
            existingIds.add(id);
            if ((encrypted ? id < 0 : id > 0) && !(message instanceof TLRPC.TL_messageEmpty)) {
                if (id < minId) minId = id;
                if (id > maxId) maxId = id;
                if (!(message instanceof TLRPC.TL_messageService)) {
                    onlyServiceMessages = false;
                }
            }
        }
        if (isCache && !encrypted && minId > maxId) {
            return null;
        }

        long clientUserId = UserConfig.getInstance(currentAccount).clientUserId;
        AyuMessagesController ayuController = AyuMessagesController.getInstance();
        boolean isMonoForum = isMonoForum(currentAccount, dialogId);
        long savedTopicId = isMonoForum ? (topicId != 0 ? topicId : threadMessageId) : (isTopic ? topicId : 0);
        long savedThreadId = savedTopicId == 0 && isChannelComment ? threadMessageId : 0;

        if (loadType == MessagesController.LOAD_AROUND_DATE && offsetDate > 0
                && ((encrypted ? offsetId >= 0 : offsetId <= 0) || onlyServiceMessages)) {
            Integer savedId = encrypted
                    ? ayuController.getEncryptedMessageIdAtDate(clientUserId, dialogId, offsetDate)
                    : ayuController.getMessageIdAtDate(clientUserId, dialogId, savedTopicId, savedThreadId, offsetDate);
            offsetId = savedId != null ? savedId : encrypted ? -1 : 1;
        }
        boolean hasAnchor = encrypted ? offsetId < 0 : offsetId > 0;
        AyuHistoryPagination.Direction direction;
        if (loadType == MessagesController.LOAD_FORWARD) {
            direction = AyuHistoryPagination.Direction.FORWARD;
        } else if (hasAnchor && (loadType == MessagesController.LOAD_FROM_UNREAD
                || loadType == MessagesController.LOAD_AROUND_MESSAGE || loadType == MessagesController.LOAD_AROUND_DATE)) {
            direction = AyuHistoryPagination.Direction.AROUND;
        } else {
            direction = AyuHistoryPagination.Direction.BACKWARD;
        }

        if (encrypted) {
            if (minId > maxId || onlyServiceMessages) {
                minId = Integer.MIN_VALUE;
                maxId = -1;
            }
            if (direction == AyuHistoryPagination.Direction.BACKWARD) {
                minId = hasAnchor ? offsetId + 1 : Integer.MIN_VALUE;
                if (messagesRes.messages.size() < count) maxId = -1;
            } else if (direction == AyuHistoryPagination.Direction.FORWARD) {
                maxId = offsetId > Integer.MIN_VALUE ? offsetId - 1 : Integer.MIN_VALUE;
                if (messagesRes.messages.size() < count) minId = Integer.MIN_VALUE;
            } else {
                minId = Math.min(minId, offsetId);
                maxId = Math.max(maxId, offsetId);
                if (messagesRes.messages.size() < count) {
                    int olderCount = 0;
                    int newerCount = 0;
                    for (TLRPC.Message message : messagesRes.messages) {
                        if (message.id < 0 && message.id > offsetId) olderCount++;
                        if (message.id <= offsetId) newerCount++;
                    }
                    if (olderCount < count / 2) maxId = -1;
                    if (newerCount < count / 2) minId = Integer.MIN_VALUE;
                }
            }
        } else {
            if (minId > maxId || (onlyServiceMessages && !isCache)) {
                minId = 1;
                maxId = Integer.MAX_VALUE;
            } else if (reachesDialogEnd(currentAccount, dialogId, savedTopicId, isTopic || isMonoForum, maxId)) {
                maxId = Integer.MAX_VALUE;
            }
            if (direction == AyuHistoryPagination.Direction.BACKWARD) {
                maxId = offsetId > 0 ? offsetId - 1 : Integer.MAX_VALUE;
                if (!isCache && messagesRes.messages.size() < count) {
                    minId = 1;
                }
            } else if (direction == AyuHistoryPagination.Direction.FORWARD) {
                minId = offsetId;
                if (!isCache && messagesRes.messages.size() < count) {
                    maxId = Integer.MAX_VALUE;
                }
            } else {
                minId = Math.min(minId, offsetId);
                maxId = Math.max(maxId, offsetId);
                if (!isCache && messagesRes.messages.size() < count) {
                    int olderCount = 0;
                    int newerCount = 0;
                    for (TLRPC.Message message : messagesRes.messages) {
                        if (message.id > 0 && message.id <= offsetId) olderCount++;
                        if (message.id > offsetId) newerCount++;
                    }
                    int requestedOlder = loadType == MessagesController.LOAD_AROUND_MESSAGE ? count / 2
                            : loadType == MessagesController.LOAD_AROUND_DATE ? 5
                            : threadMessageId != 0 && !isMonoForum ? 10 : 6;
                    requestedOlder = Math.min(count, requestedOlder);
                    if (olderCount < requestedOlder) minId = 1;
                    if (newerCount < count - requestedOlder) maxId = Integer.MAX_VALUE;
                }
            }
        }

        Map<Integer, TLRPC.TL_message> mappedMessages = new HashMap<>();
        AyuHistoryPagination.Source<DeletedMessageFull> source = (start, end, limit, ascending) -> ayuController.getHistoryMessages(
                clientUserId, dialogId, savedTopicId, savedThreadId, start, end, limit, ascending);
        Predicate<DeletedMessageFull> canDisplay = full -> {
            if (existingIds.contains(full.message.messageId)) {
                return true;
            }
            if (!hasContent(full)) {
                return false;
            }
            try {
                mappedMessages.put(full.message.messageId, map(full, currentAccount));
                return true;
            } catch (Exception e) {
                FileLog.e("AyuHistoryHook.map", e);
                return false;
            }
        };
        AyuHistoryPagination.Page<DeletedMessageFull> page = encrypted
                ? AyuHistoryPagination.loadEncrypted(direction, offsetId, minId, maxId, count, source, full -> full.message.messageId, canDisplay)
                : AyuHistoryPagination.load(direction, offsetId, minId, maxId, count, source, full -> full.message.messageId, canDisplay);
        Set<Long> groupIds = new HashSet<>();
        ArrayList<Long> usersToLoad = new ArrayList<>();
        ArrayList<Long> chatsToLoad = new ArrayList<>();
        // 自定义 emoji 的 document id：不收集的话 messagesRes.animatedEmoji 为空，
        // MessagesController 就不会 processDocuments，气泡里的自定义 emoji 首屏显示为空白
        ArrayList<Long> emojiToLoad = new ArrayList<>();

        if (!page.messages.isEmpty()) {
            messagesRes.messages.removeIf(message -> !page.contains(message.id));

            for (DeletedMessageFull full : page.messages) {
                if (existingIds.contains(full.message.messageId)) {
                    continue;
                }
                TLRPC.TL_message msg = mappedMessages.get(full.message.messageId);
                existingIds.add(msg.id);
                messagesRes.messages.add(msg);
                if (msg.grouped_id != 0) groupIds.add(msg.grouped_id);
                MessagesStorage.addUsersAndChatsFromMessage(msg, usersToLoad, chatsToLoad, emojiToLoad);
            }

            if (!groupIds.isEmpty()) {
                for (DeletedMessageFull full : ayuController.getMessagesGroupedIn(clientUserId, dialogId, new ArrayList<>(groupIds))) {
                    if (!hasContent(full) || existingIds.contains(full.message.messageId) || !page.contains(full.message.messageId)) {
                        continue;
                    }
                    TLRPC.TL_message msg = map(full, currentAccount);
                    existingIds.add(msg.id);
                    messagesRes.messages.add(msg);
                    MessagesStorage.addUsersAndChatsFromMessage(msg, usersToLoad, chatsToLoad, emojiToLoad);
                }
            }
        }

        // 服务端仍存在的消息也可能回复了一条已删除消息：即使本次没有可注入的归档消息，
        // 引用预览也得补上，否则气泡里引用头一直是空的
        fixReplies(currentAccount, clientUserId, dialogId, messagesRes, usersToLoad, chatsToLoad, emojiToLoad);

        appendAnimatedEmoji(currentAccount, messagesRes, emojiToLoad);

        appendDicts(currentAccount, messagesRes, usersDict, chatsDict, usersToLoad, chatsToLoad);

        messagesRes.messages.sort((a, b) -> encrypted ? Integer.compare(a.id, b.id) : Integer.compare(b.id, a.id));
        return page.messages.isEmpty() ? null : page;
    }

    /**
     * 把注入消息引用到的用户/会话补进 messagesRes 和渲染用的 dict。
     */
    private static void appendDicts(
            int currentAccount, TLRPC.messages_Messages messagesRes,
            LongSparseArray<TLRPC.User> usersDict, LongSparseArray<TLRPC.Chat> chatsDict,
            ArrayList<Long> usersToLoad, ArrayList<Long> chatsToLoad
    ) {
        MessagesController messagesController = MessagesController.getInstance(currentAccount);
        try {
            for (Long uid : usersToLoad) {
                if (usersDict.indexOfKey(uid) >= 0) continue;
                TLRPC.User user = messagesController.getUser(uid);
                if (user != null) {
                    usersDict.put(user.id, user);
                    messagesRes.users.add(user);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        try {
            for (Long cid : chatsToLoad) {
                if (chatsDict.indexOfKey(cid) >= 0) continue;
                TLRPC.Chat chat = messagesController.getChat(cid);
                if (chat != null) {
                    chatsDict.put(chat.id, chat);
                    messagesRes.chats.add(chat);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /**
     * 把注入消息引用到的用户/会话补进 messagesRes，避免渲染时拿不到发送者信息。
     * 用于没有现成 dict 的场景（如媒体页）。
     */
    private static void appendUsersAndChats(
            int currentAccount, TLRPC.messages_Messages messagesRes,
            ArrayList<Long> usersToLoad, ArrayList<Long> chatsToLoad
    ) {
        MessagesController messagesController = MessagesController.getInstance(currentAccount);
        Set<Long> existingUsers = new HashSet<>();
        for (int i = 0; i < messagesRes.users.size(); i++) {
            existingUsers.add(messagesRes.users.get(i).id);
        }
        Set<Long> existingChats = new HashSet<>();
        for (int i = 0; i < messagesRes.chats.size(); i++) {
            existingChats.add(messagesRes.chats.get(i).id);
        }
        try {
            for (Long uid : usersToLoad) {
                if (!existingUsers.add(uid)) continue;
                TLRPC.User user = messagesController.getUser(uid);
                if (user != null) {
                    messagesRes.users.add(user);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        try {
            for (Long cid : chatsToLoad) {
                if (!existingChats.add(cid)) continue;
                TLRPC.Chat chat = messagesController.getChat(cid);
                if (chat != null) {
                    messagesRes.chats.add(chat);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /**
     * 按关键词搜索已删除消息，供聊天内搜索合并进结果列表。
     *
     * @param topicId 非 0 时只搜该话题，避免话题内混进别的讨论串结果
     * @return 匹配的消息，按 id 倒序；出错返回空列表
     */
    public static ArrayList<MessageObject> searchDeletedMessages(int currentAccount, long dialogId, long topicId, String query, int limit) {
        ArrayList<MessageObject> result = new ArrayList<>();
        if (TextUtils.isEmpty(query) || dialogId == 0) {
            return result;
        }
        try {
            long clientUserId = UserConfig.getInstance(currentAccount).clientUserId;
            List<DeletedMessageFull> found = topicId != 0
                    ? AyuMessagesController.getInstance().searchByTextTopic(clientUserId, dialogId, topicId, query, limit)
                    : AyuMessagesController.getInstance().searchByText(clientUserId, dialogId, query, limit);
            if (found == null || found.isEmpty()) {
                return result;
            }
            for (DeletedMessageFull full : found) {
                if (!hasContent(full)) {
                    continue;
                }
                try {
                    TLRPC.TL_message msg = map(full, currentAccount);
                    msg.dialog_id = dialogId;
                    MessageObject messageObject = new MessageObject(currentAccount, msg, true, false);
                    messageObject.createStrippedThumb();
                    result.add(messageObject);
                } catch (Exception e) {
                    FileLog.e("AyuHistoryHook.searchDeletedMessages#map", e);
                }
            }
        } catch (Exception e) {
            FileLog.e("AyuHistoryHook.searchDeletedMessages", e);
        }
        return result;
    }

    /**
     * 把已删除的媒体消息注入媒体页（共享媒体标签）。
     *
     * <p>按服务端本页的 id 区间查已删除表，只挑与当前标签类型匹配的媒体补进去。
     *
     * @param type {@code MediaDataController.MEDIA_*}
     */
    public static void injectDeletedMedia(
            int currentAccount, TLRPC.messages_Messages messagesRes,
            long dialogId, long topicId, int type, int maxId, int minId
    ) {
        if (messagesRes == null || messagesRes.messages == null) {
            return;
        }
        try {
            long clientUserId = UserConfig.getInstance(currentAccount).clientUserId;
            AyuMessagesController ayuController = AyuMessagesController.getInstance();

            Set<Integer> existingIds = new HashSet<>();
            int pageMinId = Integer.MAX_VALUE;
            int pageMaxId = Integer.MIN_VALUE;
            for (int i = 0; i < messagesRes.messages.size(); i++) {
                int id = messagesRes.messages.get(i).id;
                existingIds.add(id);
                if (id > 0) {
                    if (id < pageMinId) pageMinId = id;
                    if (id > pageMaxId) pageMaxId = id;
                }
            }

            // 媒体页按 id 倒序翻页：max_id 是本页上界，min_id 用于向新方向加载。
            // 首屏（两者都为 0）放开整个区间，其余场景以本页实际拿到的 id 区间为准。
            int queryStart;
            int queryEnd;
            if (maxId == 0 && minId == 0) {
                queryStart = 0;
                queryEnd = Integer.MAX_VALUE;
            } else if (minId != 0) {
                queryStart = minId;
                queryEnd = pageMaxId == Integer.MIN_VALUE ? Integer.MAX_VALUE : pageMaxId;
            } else {
                queryStart = pageMinId == Integer.MAX_VALUE ? 0 : pageMinId;
                queryEnd = maxId;
            }
            if (queryStart > queryEnd) {
                return;
            }

            List<DeletedMessageFull> deleted = topicId != 0
                    ? ayuController.getTopicMessages(clientUserId, dialogId, topicId, queryStart, queryEnd, 200)
                    : ayuController.getMessages(clientUserId, dialogId, queryStart, queryEnd, 200);
            if (deleted == null || deleted.isEmpty()) {
                return;
            }

            ArrayList<TLRPC.Message> injected = new ArrayList<>();
            Set<Long> groupIds = new HashSet<>();
            for (DeletedMessageFull full : deleted) {
                if (!hasContent(full) || existingIds.contains(full.message.messageId)) {
                    continue;
                }
                TLRPC.TL_message msg = map(full, currentAccount);
                if (MediaDataController.getMediaType(msg) != type) {
                    continue;
                }
                existingIds.add(msg.id);
                injected.add(msg);
                if (msg.grouped_id != 0) {
                    groupIds.add(msg.grouped_id);
                }
            }

            // 相册要整组补齐，否则网格里只出现其中一张
            if (!groupIds.isEmpty()) {
                for (DeletedMessageFull full : ayuController.getMessagesGroupedIn(clientUserId, dialogId, new ArrayList<>(groupIds))) {
                    if (!hasContent(full) || existingIds.contains(full.message.messageId)) {
                        continue;
                    }
                    TLRPC.TL_message msg = map(full, currentAccount);
                    if (MediaDataController.getMediaType(msg) != type) {
                        continue;
                    }
                    existingIds.add(msg.id);
                    injected.add(msg);
                }
            }

            if (injected.isEmpty()) {
                return;
            }

            ArrayList<Long> usersToLoad = new ArrayList<>();
            ArrayList<Long> chatsToLoad = new ArrayList<>();
            for (TLRPC.Message msg : injected) {
                msg.dialog_id = dialogId;
                messagesRes.messages.add(msg);
                MessagesStorage.addUsersAndChatsFromMessage(msg, usersToLoad, chatsToLoad, null);
            }
            appendUsersAndChats(currentAccount, messagesRes, usersToLoad, chatsToLoad);

            // 媒体页自身按 date 倒序渲染，这里与之保持一致
            messagesRes.messages.sort((a, b) -> {
                int byDate = Integer.compare(b.date, a.date);
                return byDate != 0 ? byDate : Integer.compare(b.id, a.id);
            });
            messagesRes.count = Math.max(messagesRes.count, messagesRes.messages.size());
        } catch (Exception e) {
            FileLog.e("AyuHistoryHook.injectDeletedMedia", e);
        }
    }

    /**
     * 从归档里取回复目标，供 {@code loadReplyMessagesForMessages} 使用。
     *
     * @return 找到的消息；其 {@code dialog_id} 已补齐
     */
    public static ArrayList<TLRPC.Message> loadDeletedRepliesForLoad(int currentAccount, long dialogId, List<Integer> messageIds) {
        ArrayList<TLRPC.Message> result = new ArrayList<>();
        if (messageIds == null || messageIds.isEmpty() || dialogId == 0
                || !xyz.nextalone.nagram.NaConfig.INSTANCE.getEnableSaveDeletedMessages().Bool()) {
            return result;
        }
        try {
            long clientUserId = UserConfig.getInstance(currentAccount).clientUserId;
            List<DeletedMessageFull> found = AyuMessagesController.getInstance().getMessagesByIds(clientUserId, dialogId, new ArrayList<>(messageIds));
            if (found == null || found.isEmpty()) {
                return result;
            }
            Set<Integer> seen = new HashSet<>();
            for (DeletedMessageFull full : found) {
                if (!hasContent(full)) {
                    continue;
                }
                try {
                    TLRPC.TL_message msg = map(full, currentAccount);
                    if (msg.dialog_id == 0) {
                        msg.dialog_id = dialogId;
                    }
                    if (seen.add(msg.id)) {
                        result.add(msg);
                    }
                } catch (Exception e) {
                    FileLog.e("AyuHistoryHook.loadDeletedRepliesForLoad", e);
                }
            }
        } catch (Exception e) {
            FileLog.e("AyuHistoryHook.loadDeletedRepliesForLoad", e);
        }
        return result;
    }

    private static boolean isMonoForum(int currentAccount, long dialogId) {
        try {
            return MessagesController.getInstance(currentAccount).isMonoForum(dialogId);
        } catch (Exception e) {
            FileLog.e("AyuHistoryHook.isMonoForum", e);
            return false;
        }
    }

    /**
     * 按 id 取一条已删除消息并包成 {@link MessageObject}，供 UI 侧补引用预览。
     * 取不到返回 null。
     */
    public static MessageObject findDeletedReply(int currentAccount, long dialogId, int messageId) {
        if (messageId == 0) {
            return null;
        }
        try {
            long clientUserId = UserConfig.getInstance(currentAccount).clientUserId;
            DeletedMessageFull full = AyuMessagesController.getInstance().getMessage(clientUserId, dialogId, messageId);
            if (!hasContent(full)) {
                return null;
            }
            return new MessageObject(currentAccount, map(full, currentAccount), false, false);
        } catch (Exception e) {
            FileLog.e("AyuHistoryHook.findDeletedReply", e);
            return null;
        }
    }

    /** 回复目标的消息键：跨会话回复（reply_to_peer_id）必须连会话 id 一起比 */
    private static final class ReplyKey {
        final long dialogId;
        final int messageId;

        ReplyKey(long dialogId, int messageId) {
            this.dialogId = dialogId;
            this.messageId = messageId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ReplyKey)) return false;
            ReplyKey other = (ReplyKey) o;
            return dialogId == other.dialogId && messageId == other.messageId;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(dialogId) * 31 + messageId;
        }
    }

    private static ReplyKey replyKeyOf(TLRPC.Message msg, long defaultDialogId) {
        long targetDialogId = MessageObject.getReplyToDialogId(msg);
        if (targetDialogId == 0) {
            targetDialogId = msg.dialog_id != 0 ? msg.dialog_id : defaultDialogId;
        }
        return new ReplyKey(targetDialogId, msg.reply_to.reply_to_msg_id);
    }

    private static void collectReplyId(TLRPC.Message msg, Set<ReplyKey> replyIds, long defaultDialogId) {
        if (msg == null || msg.reply_to == null) {
            return;
        }
        // 回复贴纸/故事没有目标消息 id，且已有预览的不必再补
        if (msg.reply_to.story_id != 0 || msg.replyMessage != null) {
            return;
        }
        int replyToMsgId = msg.reply_to.reply_to_msg_id;
        if (replyToMsgId > 0) {
            replyIds.add(replyKeyOf(msg, defaultDialogId));
        }
    }

    /**
     * 给回复了已删除消息的气泡补上引用预览。
     *
     * <p>直接写 {@code reply_to}：{@link org.telegram.messenger.MessageObject} 构造时若发现
     * {@code replyMessage} 非空会自动建出 {@code replyMessageObject}。
     *
     * <p>目标按 {@code (dialogId, messageId)} 复合键取：跨会话回复只按 messageId 查
     * 会取到同 id 的另一会话消息。
     *
     * <p>两级兜底：先查已删除消息表，未命中的再查本地消息缓存。
     */
    private static void fixReplies(
            int currentAccount, long clientUserId, long dialogId,
            TLRPC.messages_Messages messagesRes,
            ArrayList<Long> usersToLoad, ArrayList<Long> chatsToLoad, ArrayList<Long> emojiToLoad
    ) {
        Set<ReplyKey> replyIds = new HashSet<>();
        for (int i = 0; i < messagesRes.messages.size(); i++) {
            collectReplyId(messagesRes.messages.get(i), replyIds, dialogId);
        }
        if (replyIds.isEmpty()) {
            return;
        }

        // 按目标会话分组，跨会话回复去各自的会话里找
        Map<Long, ArrayList<Integer>> idsByDialog = new HashMap<>();
        for (ReplyKey key : replyIds) {
            idsByDialog.computeIfAbsent(key.dialogId, k -> new ArrayList<>()).add(key.messageId);
        }

        Map<ReplyKey, TLRPC.Message> replyTargets = new HashMap<>();
        try {
            for (Map.Entry<Long, ArrayList<Integer>> entry : idsByDialog.entrySet()) {
                for (DeletedMessageFull full : AyuMessagesController.getInstance()
                        .getMessagesByIds(clientUserId, entry.getKey(), entry.getValue())) {
                    if (!hasContent(full)) {
                        continue;
                    }
                    TLRPC.TL_message target = map(full, currentAccount);
                    replyTargets.put(new ReplyKey(entry.getKey(), target.id), target);
                }
            }
        } catch (Exception e) {
            FileLog.e("AyuHistoryHook.fixReplies#deleted", e);
        }

        // 已删除表没有的，退回本地消息缓存
        Set<ReplyKey> missing = new HashSet<>();
        for (ReplyKey replyId : replyIds) {
            if (!replyTargets.containsKey(replyId)) {
                missing.add(replyId);
            }
        }
        for (Map.Entry<ReplyKey, TLRPC.Message> entry : loadCachedReplies(currentAccount, missing).entrySet()) {
            replyTargets.putIfAbsent(entry.getKey(), entry.getValue());
        }

        if (replyTargets.isEmpty()) {
            return;
        }

        for (int i = 0; i < messagesRes.messages.size(); i++) {
            TLRPC.Message msg = messagesRes.messages.get(i);
            if (msg == null || msg.reply_to == null || msg.replyMessage != null) {
                continue;
            }
            if (msg.reply_to.story_id != 0) {
                continue;
            }
            TLRPC.Message target = replyTargets.get(replyKeyOf(msg, dialogId));
            if (target == null || target.id == msg.id && MessageObject.getDialogId(target) == MessageObject.getDialogId(msg)) {
                continue;
            }
            msg.replyMessage = target;
            if (msg.reply_to.reply_to_peer_id == null && target.peer_id != null) {
                msg.reply_to.reply_to_peer_id = target.peer_id;
            }
            MessagesStorage.addUsersAndChatsFromMessage(target, usersToLoad, chatsToLoad, emojiToLoad);
        }
    }

    /**
     * 把自定义 emoji 的 document 补进 messagesRes，供
     * {@code MessagesController.processLoadedMessages} 里的 processDocuments 使用。
     *
     * <p>{@link MessagesStorage#getAnimatedEmoji} 直接读库，必须在 storageQueue 上跑。
     */
    private static void appendAnimatedEmoji(int currentAccount, TLRPC.messages_Messages messagesRes, ArrayList<Long> emojiToLoad) {
        if (emojiToLoad.isEmpty()) {
            return;
        }
        MessagesStorage storage = MessagesStorage.getInstance(currentAccount);
        ArrayList<TLRPC.Document> documents = new ArrayList<>();
        try {
            if (Thread.currentThread() == storage.getStorageQueue()) {
                storage.getAnimatedEmoji(TextUtils.join(",", emojiToLoad), documents);
            } else {
                CountDownLatch latch = new CountDownLatch(1);
                storage.getStorageQueue().postRunnable(() -> {
                    try {
                        storage.getAnimatedEmoji(TextUtils.join(",", emojiToLoad), documents);
                    } catch (Exception e) {
                        FileLog.e("AyuHistoryHook.appendAnimatedEmoji#query", e);
                    } finally {
                        latch.countDown();
                    }
                });
                latch.await();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (Exception e) {
            FileLog.e("AyuHistoryHook.appendAnimatedEmoji", e);
            return;
        }

        if (documents.isEmpty()) {
            return;
        }
        if (messagesRes.animatedEmoji == null) {
            messagesRes.animatedEmoji = new ArrayList<>();
        }
        Set<Long> existing = new HashSet<>();
        for (int i = 0; i < messagesRes.animatedEmoji.size(); i++) {
            existing.add(messagesRes.animatedEmoji.get(i).id);
        }
        for (TLRPC.Document document : documents) {
            if (existing.add(document.id)) {
                messagesRes.animatedEmoji.add(document);
            }
        }
    }

    /**
     * 从本地消息缓存取回复目标。用 {@link MessagesStorage#getMessageLegit} 直读，
     * 不能走 {@code getMessage} 的 post-and-wait：本方法可能在存储队列上被调用，
     * 那样会等一个排在自己后面的任务。
     */
    private static Map<ReplyKey, TLRPC.Message> loadCachedReplies(int currentAccount, Set<ReplyKey> keys) {
        Map<ReplyKey, TLRPC.Message> result = new HashMap<>();
        MessagesStorage storage = MessagesStorage.getInstance(currentAccount);
        for (ReplyKey key : keys) {
            try {
                TLRPC.Message message = storage.getMessageLegit(key.dialogId, key.messageId);
                if (message != null && !(message instanceof TLRPC.TL_messageEmpty)) {
                    if (message.dialog_id == 0) {
                        message.dialog_id = key.dialogId;
                    }
                    message.id = key.messageId;
                    MessageObject.normalizeFlags(message);
                    result.put(key, message);
                }
            } catch (Exception e) {
                FileLog.e("AyuHistoryHook.loadCachedReplies", e);
            }
        }
        return result;
    }

    /**
     * 判断本次加载的消息窗口是否已经触达会话（或话题）末尾。
     * 末尾场景下必须允许查询 id 大于当前最新消息的已删除消息。
     */
    private static boolean reachesDialogEnd(
            int currentAccount,
            long dialogId, long topicId, boolean isTopic, int maxId
    ) {
        try {
            MessagesController messagesController = MessagesController.getInstance(currentAccount);
            if (isTopic && topicId != 0) {
                TLRPC.TL_forumTopic topic = messagesController.getTopicsController().findTopic(-dialogId, topicId);
                if (topic != null) {
                    return topic.top_message <= maxId;
                }
            } else {
                TLRPC.Dialog dialog = messagesController.getDialog(dialogId);
                if (dialog != null) {
                    return dialog.top_message <= maxId;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    private static TLRPC.TL_message map(DeletedMessageFull deletedMessageFull, int accountId) {
        TLRPC.Reaction reaction;
        TLRPC.TL_message tlMessage = new TLRPC.TL_message();
        AyuMessageUtils.map(deletedMessageFull.message, tlMessage, accountId);
        List<DeletedMessageReaction> reactionsList = deletedMessageFull.reactions;
        if (reactionsList != null && !reactionsList.isEmpty()) {
            tlMessage.reactions = new TLRPC.TL_messageReactions();
            int orderIndex = 0;
            for (DeletedMessageReaction deletedMessageReaction : deletedMessageFull.reactions) {
                TLRPC.TL_reactionCount reactionCount = new TLRPC.TL_reactionCount();
                reactionCount.count = deletedMessageReaction.count;
                reactionCount.chosen = deletedMessageReaction.selfSelected;
                orderIndex++;
                reactionCount.chosen_order = orderIndex;
                if (deletedMessageReaction.isPaid) {
                    reaction = new TLRPC.TL_reactionPaid();
                } else if (deletedMessageReaction.isCustom) {
                    var customEmoji = new TLRPC.TL_reactionCustomEmoji();
                    customEmoji.document_id = deletedMessageReaction.documentId;
                    reaction = customEmoji;
                } else {
                    var emoji = new TLRPC.TL_reactionEmoji();
                    emoji.emoticon = deletedMessageReaction.emoticon;
                    reaction = emoji;
                }
                reactionCount.reaction = reaction;
                tlMessage.reactions.results.add(reactionCount);
            }
        }
        tlMessage.ayuDeleted = true;
        tlMessage.ayuDeleteDate = deletedMessageFull.message.entityCreateDate;
        AyuMessageUtils.mapMedia(deletedMessageFull.message, tlMessage, accountId);
        // 必须放在 mapMedia 之后：flags 是从库里原样读回的，若某些字段这次没能重建
        // （媒体反序列化失败、reply 头缺失等），对应 flag 位会与实际内容不符，
        // 导致序列化或渲染异常。
        MessageObject.normalizeFlags(tlMessage);
        return tlMessage;
    }

    private static boolean hasContent(DeletedMessageFull messageFull) {
        return messageFull != null && AyuMessageUtils.hasContent(messageFull.message);
    }
}
