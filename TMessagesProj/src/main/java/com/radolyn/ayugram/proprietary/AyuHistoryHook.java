package com.radolyn.ayugram.proprietary;

import android.text.TextUtils;

import androidx.collection.LongSparseArray;

import com.radolyn.ayugram.database.entities.DeletedMessageFull;
import com.radolyn.ayugram.database.entities.DeletedMessageReaction;
import com.radolyn.ayugram.messages.AyuMessagesController;
import com.radolyn.ayugram.utils.AyuMessageUtils;

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

public abstract class AyuHistoryHook {

    public static void doHookSync(
            int currentAccount,
            TLRPC.messages_Messages messagesRes,
            LongSparseArray<TLRPC.User> usersDict,
            LongSparseArray<TLRPC.Chat> chatsDict,
            long dialogId, long topicId, int loadType,
            boolean isChannelComment, long threadMessageId, boolean isTopic
    ) {
        int minId = Integer.MAX_VALUE;
        int maxId = Integer.MIN_VALUE;
        for (int i = 0; i < messagesRes.messages.size(); i++) {
            int id = messagesRes.messages.get(i).id;
            if (id > 0) {
                if (id < minId) minId = id;
                if (id > maxId) maxId = id;
            }
        }
        if (minId == Integer.MAX_VALUE) {
            minId = 0;
            maxId = Integer.MAX_VALUE;
        } else if (reachesDialogEnd(currentAccount, dialogId, topicId, loadType, isTopic, maxId)) {
            // 处于会话末尾时上界必须放开：id 比现存最新消息更大的已删除消息
            // 否则会被区间条件排除，直到有新消息把上界抬高才会显示
            maxId = Integer.MAX_VALUE;
        }

        long clientUserId = UserConfig.getInstance(currentAccount).clientUserId;
        AyuMessagesController ayuController = AyuMessagesController.getInstance();
        Set<Integer> existingIds = new HashSet<>();
        for (int i = 0; i < messagesRes.messages.size(); i++) {
            existingIds.add(messagesRes.messages.get(i).id);
        }

        List<DeletedMessageFull> deletedMessages;
        // monoForum（频道私信）也是按 topicId 归档的，但 ChatActivity 不会把它标成 isTopic，
        // 若不单独识别就会落进 isChannelComment 分支、拿 replyMessageId 列去匹配而永远查不到
        boolean isMonoForum = isMonoForum(currentAccount, dialogId);
        long resolvedTopicId = topicId != 0 ? topicId : (isMonoForum ? threadMessageId : 0);
        if (isMonoForum && resolvedTopicId != 0) {
            deletedMessages = ayuController.getTopicMessages(clientUserId, dialogId, resolvedTopicId, minId, maxId, 200);
        } else if (isChannelComment) {
            deletedMessages = ayuController.getThreadMessages(clientUserId, dialogId, threadMessageId, minId, maxId, 200);
        } else if (isTopic && topicId != 0) {
            deletedMessages = ayuController.getTopicMessages(clientUserId, dialogId, topicId, minId, maxId, 200);
        } else {
            deletedMessages = ayuController.getMessages(clientUserId, dialogId, minId, maxId, 200);
        }
        if (deletedMessages.isEmpty()) {
            return;
        }

        Set<Long> groupIds = new HashSet<>();
        Set<Integer> replyIds = new HashSet<>();
        ArrayList<Long> usersToLoad = new ArrayList<>();
        ArrayList<Long> chatsToLoad = new ArrayList<>();
        // 自定义 emoji 的 document id：不收集的话 messagesRes.animatedEmoji 为空，
        // MessagesController 就不会 processDocuments，气泡里的自定义 emoji 首屏显示为空白
        ArrayList<Long> emojiToLoad = new ArrayList<>();

        for (DeletedMessageFull full : deletedMessages) {
            if (!hasContent(full) || existingIds.contains(full.message.messageId)) {
                continue;
            }
            TLRPC.TL_message msg = map(full, currentAccount);
            existingIds.add(msg.id);
            messagesRes.messages.add(msg);
            if (msg.grouped_id != 0) groupIds.add(msg.grouped_id);
            collectReplyId(msg, replyIds);
            MessagesStorage.addUsersAndChatsFromMessage(msg, usersToLoad, chatsToLoad, emojiToLoad);
        }

        if (!groupIds.isEmpty()) {
            for (DeletedMessageFull full : ayuController.getMessagesGroupedIn(clientUserId, dialogId, new ArrayList<>(groupIds))) {
                if (!hasContent(full) || existingIds.contains(full.message.messageId)) {
                    continue;
                }
                TLRPC.TL_message msg = map(full, currentAccount);
                existingIds.add(msg.id);
                messagesRes.messages.add(msg);
                collectReplyId(msg, replyIds);
                MessagesStorage.addUsersAndChatsFromMessage(msg, usersToLoad, chatsToLoad, emojiToLoad);
            }
        }

        // 服务端仍存在的消息也可能回复了一条已删除消息，同样需要补全预览
        for (int i = 0; i < messagesRes.messages.size(); i++) {
            collectReplyId(messagesRes.messages.get(i), replyIds);
        }

        fixReplies(currentAccount, clientUserId, dialogId, messagesRes, replyIds, usersToLoad, chatsToLoad, emojiToLoad);

        appendAnimatedEmoji(currentAccount, messagesRes, emojiToLoad);

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

        messagesRes.messages.sort((a, b) -> Integer.compare(b.id, a.id));
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
     * <p>此前搜索只覆盖服务端与本地缓存，已删除消息即使存着也搜不到。
     *
     * @return 匹配的消息，按 id 倒序；出错返回空列表
     */
    public static ArrayList<MessageObject> searchDeletedMessages(int currentAccount, long dialogId, String query, int limit) {
        ArrayList<MessageObject> result = new ArrayList<>();
        if (TextUtils.isEmpty(query) || dialogId == 0) {
            return result;
        }
        try {
            long clientUserId = UserConfig.getInstance(currentAccount).clientUserId;
            List<DeletedMessageFull> found = AyuMessagesController.getInstance()
                    .searchByText(clientUserId, dialogId, query, limit);
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
     * <p>此前媒体页完全看不到已删除内容——文件还在磁盘上、库里也有记录，
     * 但只能从独立的"已删除消息"界面进入。这里按服务端本页的 id 区间去查已删除表，
     * 只挑与当前标签类型匹配的媒体补进去。
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

    private static void collectReplyId(TLRPC.Message msg, Set<Integer> replyIds) {
        if (msg == null || msg.reply_to == null) {
            return;
        }
        // 回复贴纸/故事没有目标消息 id，且已有预览的不必再补
        if (msg.reply_to.story_id != 0 || msg.replyMessage != null) {
            return;
        }
        int replyToMsgId = msg.reply_to.reply_to_msg_id;
        if (replyToMsgId > 0) {
            replyIds.add(replyToMsgId);
        }
    }

    /**
     * 给回复了已删除消息的气泡补上引用预览。
     *
     * <p>此前的做法是把被回复的已删除消息作为独立一行插进列表——用户看到的是一条散落的
     * 旧消息，而不是引用头。这里改成挂到 {@code reply_to} 上：
     * {@link org.telegram.messenger.MessageObject} 构造时若发现 {@code replyMessage}
     * 非空就会自动建出 {@code replyMessageObject}，因此设置 TL 字段即可。
     *
     * <p>两级兜底：先查已删除消息表，未命中的再查本地消息缓存（消息可能只是被本地清理、
     * 并未真正删除）。
     */
    private static void fixReplies(
            int currentAccount, long clientUserId, long dialogId,
            TLRPC.messages_Messages messagesRes, Set<Integer> replyIds,
            ArrayList<Long> usersToLoad, ArrayList<Long> chatsToLoad, ArrayList<Long> emojiToLoad
    ) {
        if (replyIds.isEmpty()) {
            return;
        }

        Map<Integer, TLRPC.Message> replyTargets = new HashMap<>();
        try {
            for (DeletedMessageFull full : AyuMessagesController.getInstance()
                    .getMessagesByIds(clientUserId, dialogId, new ArrayList<>(replyIds))) {
                if (!hasContent(full)) {
                    continue;
                }
                TLRPC.TL_message target = map(full, currentAccount);
                replyTargets.put(target.id, target);
            }
        } catch (Exception e) {
            FileLog.e("AyuHistoryHook.fixReplies#deleted", e);
        }

        // 已删除表没有的，退回本地消息缓存
        Set<Integer> missing = new HashSet<>();
        for (Integer replyId : replyIds) {
            if (!replyTargets.containsKey(replyId)) {
                missing.add(replyId);
            }
        }
        if (!missing.isEmpty()) {
            replyTargets.putAll(loadCachedReplies(currentAccount, dialogId, missing));
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
            TLRPC.Message target = replyTargets.get(msg.reply_to.reply_to_msg_id);
            if (target == null || target.id == msg.id) {
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
     * 从本地消息缓存取回复目标。{@link MessagesStorage#getMessage} 是 post-and-wait，
     * 已经在 storageQueue 上时会自锁死，故此时直接跳过——那条路径上被回复消息通常
     * 已随本批一起加载，上游的 loadReplyMessagesForMessages 也会再兜一次。
     */
    private static Map<Integer, TLRPC.Message> loadCachedReplies(int currentAccount, long dialogId, Set<Integer> messageIds) {
        Map<Integer, TLRPC.Message> result = new HashMap<>();
        MessagesStorage storage = MessagesStorage.getInstance(currentAccount);
        if (Thread.currentThread() == storage.getStorageQueue()) {
            return result;
        }
        for (Integer messageId : messageIds) {
            try {
                TLRPC.Message message = storage.getMessage(dialogId, messageId);
                if (message != null && !(message instanceof TLRPC.TL_messageEmpty)) {
                    if (message.dialog_id == 0) {
                        message.dialog_id = dialogId;
                    }
                    message.id = messageId;
                    MessageObject.normalizeFlags(message);
                    result.put(messageId, message);
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
            long dialogId, long topicId, int loadType, boolean isTopic, int maxId
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
        // 拿不到会话信息时退回加载类型判断：首屏与向下加载都可能停在末尾
        return loadType == 2 || loadType == 0;
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
        AyuMessageUtils.mapMedia(deletedMessageFull.message, tlMessage, accountId);
        // 必须放在 mapMedia 之后：flags 是从库里原样读回的，若某些字段这次没能重建
        // （媒体反序列化失败、reply 头缺失等），对应 flag 位会与实际内容不符，
        // 导致序列化或渲染异常。上游从存储读消息的每条路径都会做这一步。
        MessageObject.normalizeFlags(tlMessage);
        return tlMessage;
    }

    private static boolean hasContent(DeletedMessageFull messageFull) {
        return messageFull != null && AyuMessageUtils.hasContent(messageFull.message);
    }
}
