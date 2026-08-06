package com.radolyn.ayugram.proprietary;

import android.text.TextUtils;

import androidx.collection.LongSparseArray;

import com.radolyn.ayugram.database.entities.DeletedMessageFull;
import com.radolyn.ayugram.database.entities.DeletedMessageReaction;
import com.radolyn.ayugram.messages.AyuMessagesController;
import com.radolyn.ayugram.utils.AyuMessageUtils;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public abstract class AyuHistoryHook {

    public static void doHookSync(
            int currentAccount,
            TLRPC.messages_Messages messagesRes,
            LongSparseArray<TLRPC.User> usersDict,
            LongSparseArray<TLRPC.Chat> chatsDict,
            long dialogId, long topicId, int loadType,
            boolean isChannelComment, long threadMessageId, boolean isTopic
    ) {
        if (messagesRes.messages.isEmpty()) {
            return;
        }
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
        }

        long clientUserId = UserConfig.getInstance(currentAccount).clientUserId;
        AyuMessagesController ayuController = AyuMessagesController.getInstance();
        Set<Integer> existingIds = new HashSet<>();
        for (int i = 0; i < messagesRes.messages.size(); i++) {
            existingIds.add(messagesRes.messages.get(i).id);
        }

        List<DeletedMessageFull> deletedMessages;
        if (isChannelComment) {
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

        for (DeletedMessageFull full : deletedMessages) {
            if (!hasContent(full) || existingIds.contains(full.message.messageId)) {
                continue;
            }
            TLRPC.TL_message msg = map(full, currentAccount);
            existingIds.add(msg.id);
            messagesRes.messages.add(msg);
            if (msg.grouped_id != 0) groupIds.add(msg.grouped_id);
            if (msg.reply_to != null) replyIds.add(msg.reply_to.reply_to_msg_id);
            MessagesStorage.addUsersAndChatsFromMessage(msg, usersToLoad, chatsToLoad, null);
        }

        if (!groupIds.isEmpty()) {
            for (DeletedMessageFull full : ayuController.getMessagesGroupedIn(clientUserId, dialogId, new ArrayList<>(groupIds))) {
                if (!hasContent(full) || existingIds.contains(full.message.messageId)) {
                    continue;
                }
                TLRPC.TL_message msg = map(full, currentAccount);
                existingIds.add(msg.id);
                messagesRes.messages.add(msg);
                if (msg.reply_to != null) replyIds.add(msg.reply_to.reply_to_msg_id);
                MessagesStorage.addUsersAndChatsFromMessage(msg, usersToLoad, chatsToLoad, null);
            }
        }

        if (!replyIds.isEmpty()) {
            for (DeletedMessageFull full : ayuController.getMessagesByIds(clientUserId, dialogId, new ArrayList<>(replyIds))) {
                if (!hasContent(full) || existingIds.contains(full.message.messageId)) {
                    continue;
                }
                TLRPC.TL_message msg = map(full, currentAccount);
                existingIds.add(msg.id);
                messagesRes.messages.add(msg);
                MessagesStorage.addUsersAndChatsFromMessage(msg, usersToLoad, chatsToLoad, null);
            }
        }

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
        return tlMessage;
    }

    private static boolean hasContent(DeletedMessageFull messageFull) {
        return messageFull != null && messageFull.message != null && (!TextUtils.isEmpty(messageFull.message.text) || !TextUtils.isEmpty(messageFull.message.mediaPath) || messageFull.message.documentSerialized != null);
    }
}
