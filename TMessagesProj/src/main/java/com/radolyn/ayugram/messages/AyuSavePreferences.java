/*
 * This is the source code of AyuGram for Android.
 *
 * We do not and cannot prevent the use of our code,
 * but be respectful and credit the original author.
 *
 * Copyright @Radolyn, 2023
 */

package com.radolyn.ayugram.messages;


import org.telegram.messenger.MessageObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

import com.radolyn.ayugram.AyuSaveExclusions;

import me.vkryl.core.BitwiseUtils;

import tw.nekomimi.nekogram.NekoConfig;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

import xyz.nextalone.nagram.NaConfig;

public class AyuSavePreferences {
    private static final long USER_LOOKUP_TIMEOUT_MS = 1000L;
    public static final String saveExclusionPrefix = "saveDeletedExclusion_";
    private final TLRPC.Message message;
    private final int accountId;
    private final long userId;
    private long dialogId = -1;
    private long topicId = -1;
    private int messageId = -1;
    private int requestCatchTime = -1;

    public AyuSavePreferences(TLRPC.Message msg, int accountId, long dialogId, long topicId, int messageId, int requestCatchTime) {
        this.message = msg;
        this.accountId = accountId;
        this.userId = UserConfig.getInstance(accountId).getClientUserId();

        if (msg == null) {
            return;
        }

        this.dialogId = dialogId != 0 ? dialogId : MessageObject.getDialogId(msg);
        this.topicId = dialogId == 0 ? resolveTopicId(accountId, msg, this.dialogId) : topicId;
        this.messageId = messageId;
        this.requestCatchTime = requestCatchTime;
    }

    public AyuSavePreferences(TLRPC.Message msg, int accountId) {
        this.message = msg;
        this.accountId = accountId;
        this.userId = UserConfig.getInstance(accountId).getClientUserId();

        if (msg == null) {
            return;
        }

        this.dialogId = MessageObject.getDialogId(msg);
        this.topicId = resolveTopicId(accountId, msg);
        this.messageId = msg.id;
        this.requestCatchTime = (int) (System.currentTimeMillis() / 1000);
    }

    /**
     * 解析消息归属的话题 id。
     *
     * <p>必须显式告知对话类型：monoForum（频道私信）的归属信息在 {@code saved_peer_id}，
     * 不带该标记时 {@link MessageObject#getTopicId} 会直接返回 0，导致这些消息全部
     * 存到 topicId=0 下、读取时按错误的列去匹配而永远查不出来。
     */
    private static long resolveTopicId(int accountId, TLRPC.Message msg) {
        long dialogId = msg.dialog_id != 0 ? msg.dialog_id : MessageObject.getDialogId(msg);
        return resolveTopicId(accountId, msg, dialogId);
    }

    /**
     * 所有删除保存路径统一从这里解析话题 id。
     * 不要用 {@link MessageObject#getTopicId(int, TLRPC.Message, boolean)}：它不感知
     * monoForum，会把频道私信消息解析成 topicId=0，与读取侧查询不一致。
     */
    public static long resolveTopicId(int accountId, TLRPC.Message msg, long dialogId) {
        MessagesController messagesController = MessagesController.getInstance(accountId);
        int forumFlags = 0;
        try {
            if (messagesController.isMonoForum(dialogId)) {
                forumFlags = BitwiseUtils.setFlag(forumFlags, MessagesStorage.FORUM_TYPE_DIRECT, true);
            } else if (messagesController.isForum(dialogId)) {
                forumFlags = BitwiseUtils.setFlag(forumFlags, MessagesStorage.FORUM_TYPE_CHAT, true);
            }
        } catch (Exception ignored) {
            // 拿不到对话信息时退回不带标记的解析
        }
        return MessageObject.getTopicId(accountId, msg, forumFlags);
    }

    public static boolean saveDeletedMessageFor(int accountId, long dialogId, MessageObject messageObject) {
        if (messageObject != null && messageObject.messageOwner != null && messageObject.messageOwner.from_id != null) {
            return saveDeletedMessageFor(accountId, dialogId, messageObject.messageOwner.from_id.user_id);
        }
        return saveDeletedMessageFor(accountId, dialogId, 0);
    }

    /**
     * 阅后即焚 / 一次性媒体保护等入口统一用这个条件：
     * 与普通消息删除一致地尊重会话排除与机器人设置，而不是只看总开关。
     */
    public static boolean shouldKeepMediaFor(int accountId, long dialogId, MessageObject messageObject) {
        return saveDeletedMessageFor(accountId, dialogId, messageObject);
    }

    public static boolean shouldKeepMediaFor(int accountId, long dialogId, TLRPC.Message message) {
        long fromUserId = message != null && message.from_id != null ? message.from_id.user_id : 0;
        return saveDeletedMessageFor(accountId, dialogId, fromUserId);
    }

    public static boolean shouldKeepMediaFor(int accountId, long dialogId, long userId) {
        return saveDeletedMessageFor(accountId, dialogId, userId);
    }

    public static boolean saveDeletedMessageFor(int accountId, long dialogId, long userId) {
        if (!NaConfig.INSTANCE.getEnableSaveDeletedMessages().Bool()) {
            return false;
        }
        // 会话规则（显式包含/排除、按类别、整体白名单）先决定是否保存。
        if (!AyuSaveExclusions.isSavingAllowedFor(accountId, dialogId, userId)) {
            return false;
        }
        // 发送者是机器人时，仍要服从「机器人消息」开关。
        if (userId != 0) {
            TLRPC.User fromUser = getCachedOrStoredUser(accountId, userId);
            if (fromUser != null && fromUser.bot && !NaConfig.INSTANCE.getSaveDeletedMessageForBotUser().Bool()) {
                return false;
            }
        }
        // 在机器人会话里保存，服从「机器人会话」开关。
        if (DialogObject.isUserDialog(dialogId)) {
            TLRPC.User dialogUser = getCachedOrStoredUser(accountId, dialogId);
            if (dialogUser != null && dialogUser.bot && !NaConfig.INSTANCE.getSaveDeletedMessageForBot().Bool()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 先查内存缓存，未命中再直读存储。
     *
     * <p>不能用"post 到存储队列再无限 await"：整段备份等链路本身就跑在存储队列上，
     * 那样会等一个排在自己后面的任务直接死锁；即便在别的线程上，无界等待也可能在
     * 存储队列繁忙时把调用方（含 UI 线程）拖住，所以这里带上限等待。
     */
    private static TLRPC.User getCachedOrStoredUser(int accountId, long userId) {
        var user = MessagesController.getInstance(accountId).getUser(userId);
        if (user != null) {
            return user;
        }
        var messagesStorage = MessagesStorage.getInstance(accountId);
        if (Thread.currentThread() == messagesStorage.getStorageQueue()) {
            return messagesStorage.getUser(userId);
        }
        final CountDownLatch countDownLatch = new CountDownLatch(1);
        final TLRPC.User[] stored = {null};
        messagesStorage.getStorageQueue().postRunnable(() -> {
            try {
                stored[0] = messagesStorage.getUser(userId);
            } finally {
                countDownLatch.countDown();
            }
        });
        try {
            if (!countDownLatch.await(USER_LOOKUP_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                FileLog.d("AyuSavePreferences: user lookup timed out, fall back to dialog rules userId=" + userId);
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return stored[0];
    }

    /** Sets the explicit rule for one chat; kept for the chat menu and backup restore. */
    public static void setSaveDeletedExclusion(long chatId, boolean value) {
        AyuSaveExclusions.setChatSavingType(chatId, value ? AyuSaveExclusions.TYPE_NEVER : AyuSaveExclusions.TYPE_DEFAULT);
    }

    public static boolean getSaveDeletedExclusion(long chatId) {
        return AyuSaveExclusions.getChatSavingType(chatId) == AyuSaveExclusions.TYPE_NEVER;
    }

    /** Loads the exclusion rules; triggers the one-time migration of the old per-chat booleans. */
    public static void loadAllExclusions() {
        Utilities.stageQueue.postRunnable(AyuSaveExclusions::isWhitelist);
    }

    public TLRPC.Message getMessage() {
        return message;
    }

    public int getAccountId() {
        return accountId;
    }

    public long getUserId() {
        return userId;
    }

    public long getDialogId() {
        return dialogId;
    }

    public void setDialogId(long dialogId) {
        if (dialogId == 0) {
            return;
        }
        this.dialogId = dialogId;
    }

    public long getTopicId() {
        return topicId;
    }

    public int getMessageId() {
        return messageId;
    }

    public int getRequestCatchTime() {
        return requestCatchTime;
    }

    public long getFromUserId() {
        if (message == null || message.from_id == null) {
            return 0;
        }
        return message.from_id.user_id;
    }

}
