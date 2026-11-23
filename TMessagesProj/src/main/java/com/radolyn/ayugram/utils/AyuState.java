/*
 * This is the source code of AyuGram for Android.
 *
 * We do not and cannot prevent the use of our code,
 * but be respectful and credit the original author.
 *
 * Copyright @Radolyn, 2023
 */

package com.radolyn.ayugram.utils;

import android.util.LongSparseArray;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;

import com.radolyn.ayugram.AyuGhostConfig;
import com.radolyn.ayugram.messages.AyuSavePreferences;

import java.util.ArrayList;

import tw.nekomimi.nekogram.NekoConfig;
import xyz.nextalone.nagram.NaConfig;

public class AyuState {
    /** 放行标记只在删除流程内短暂有效，过期后必须失效，否则同 id 的消息会被永久跳过归档。 */
    private static final long DELETE_PERMITTED_TTL_MS = 7L * 24 * 60 * 60 * 1000;
    private static final AyuStateVariable allowReadPacket = new AyuStateVariable();
    private static final AyuStateVariable hideSelection = new AyuStateVariable();
    private static final AyuStateVariable automaticallyScheduled = new AyuStateVariable();
    private static final AyuStateVariable allowDeleteDialogs = new AyuStateVariable();
    private static final LongSparseArray<ArrayList<PermittedDeletion>> deletePermitted = new LongSparseArray<>();

    private static final class PermittedDeletion {
        final int messageId;
        final long createdAt;

        PermittedDeletion(int messageId, long createdAt) {
            this.messageId = messageId;
            this.createdAt = createdAt;
        }
    }

    public static void setAllowReadPacket(boolean val, int resetAfter) {
        allowReadPacket.val = val;
        allowReadPacket.resetAfter = resetAfter;
    }

    public static boolean getAllowReadPacket() {
        return NekoConfig.sendReadMessagePackets.Bool() || allowReadPacket.process();
    }

    public static void setHideSelection(boolean val, int resetAfter) {
        hideSelection.val = val;
        hideSelection.resetAfter = resetAfter;
    }

    public static boolean getHideSelection() {
        return hideSelection.process();
    }

    /** 该账号是否允许发出已读回执（受幽灵模式总开关与一次性放行共同决定）。 */
    public static boolean getAllowReadMessage(long userId) {
        return AyuGhostConfig.getGhostModeSettings(userId).sendReadMessagePackets;
    }

    /**
     * 阅后即焚媒体是否允许被标记为已读。
     *
     * <p>会存档的会话必须让服务端收到已读，否则媒体不会被销毁；其余按幽灵模式的读状态例外决定。
     */
    public static boolean getAllowReadSecretMedia(int account, long dialogId) {
        if (AyuSavePreferences.saveDeletedMessageFor(account, dialogId, 0L)) {
            return false;
        }
        return !isGhostReadBlockedFor(account, dialogId);
    }

    /** 会话级「不发送已读」判定：例外优先，没有例外时跟随全局开关。 */
    private static boolean isGhostReadBlockedFor(int account, long dialogId) {
        boolean sendRead = AyuGhostConfig.isSendReadMessagePackets(account);
        int readType = AyuGhostPreferences.getReadException(dialogId);
        if (readType == AyuGhostPreferences.TYPE_FORCE_BLOCK) {
            return true;
        }
        if (readType == AyuGhostPreferences.TYPE_FORCE_ALLOW) {
            return false;
        }
        return !sendRead;
    }

    public static void setAutomaticallyScheduled(boolean val, int resetAfter) {
        automaticallyScheduled.val = val;
        automaticallyScheduled.resetAfter = resetAfter;
    }

    /** 切换幽灵模式后清掉一次性状态，避免残留"自动定时发送"。 */
    public static void resetGhostMode() {
        setAutomaticallyScheduled(false, -1);
    }

    public static boolean getAutomaticallyScheduled() {
        return automaticallyScheduled.process();
    }

    /**
     * 标记接下来的会话删除是用户主动发起的，此时不保存会话快照、并清除已有快照记录。
     * resetAfter 为剩余生效次数，deleteDialog 内部会递归调用，需要按调用层数放行。
     */
    public static void setAllowDeleteDialogs(boolean val, int resetAfter) {
        allowDeleteDialogs.val = val;
        allowDeleteDialogs.resetAfter = resetAfter;
    }

    public static boolean getAllowDeleteDialogs() {
        return allowDeleteDialogs.process();
    }

    public static void permitDeleteMessage(long dialogId, int messageId) {
        synchronized (deletePermitted) {
            var list = deletePermitted.get(dialogId);
            if (list == null) {
                list = new ArrayList<>();
                deletePermitted.put(dialogId, list);
            }

            list.add(new PermittedDeletion(messageId, System.currentTimeMillis()));
        }
    }

    public static boolean isDeletePermitted(long dialogId, int messageId) {
        synchronized (deletePermitted) {
            var list = deletePermitted.get(dialogId);
            if (list == null) {
                return false;
            }

            final long now = System.currentTimeMillis();
            for (int i = list.size() - 1; i >= 0; i--) {
                var entry = list.get(i);
                if (now - entry.createdAt >= DELETE_PERMITTED_TTL_MS) {
                    list.remove(i);
                } else if (entry.messageId == messageId) {
                    return true;
                }
            }

            if (list.isEmpty()) {
                deletePermitted.remove(dialogId);
            }
            return false;
        }
    }

    public static void messageDeleted(long dialogId, int messageId) {
        synchronized (deletePermitted) {
            var list = deletePermitted.get(dialogId);
            if (list == null) {
                return;
            }

            for (int i = list.size() - 1; i >= 0; i--) {
                if (list.get(i).messageId == messageId) {
                    list.remove(i);
                }
            }
            if (list.isEmpty()) {
                deletePermitted.remove(dialogId);
            }
        }
    }

    /**
     * 标记阅后即焚媒体已被查看过。
     */
    public static void setMessageBurned(int account, long dialogId, int messageId) {
        if (!NaConfig.INSTANCE.getEnableSaveDeletedMessages().Bool()) {
            return;
        }
        long clientUserId = UserConfig.getInstance(account).getClientUserId();
        MessagesController.getGlobalMainSettings().edit().putBoolean("messageBurned_" + clientUserId + "_" + dialogId + "_" + messageId, true).apply();
    }

    public static boolean isMessageBurned(int account, long dialogId, int messageId) {
        if (!NaConfig.INSTANCE.getEnableSaveDeletedMessages().Bool()) {
            return false;
        }
        long clientUserId = UserConfig.getInstance(account).getClientUserId();
        return MessagesController.getGlobalMainSettings().getBoolean("messageBurned_" + clientUserId + "_" + dialogId + "_" + messageId, false);
    }
}
