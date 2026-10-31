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

import java.util.ArrayList;

import tw.nekomimi.nekogram.NekoConfig;
import xyz.nextalone.nagram.NaConfig;

public class AyuState {
    private static final AyuStateVariable allowReadPacket = new AyuStateVariable();
    private static final AyuStateVariable hideSelection = new AyuStateVariable();
    private static final AyuStateVariable automaticallyScheduled = new AyuStateVariable();
    private static final AyuStateVariable allowDeleteDialogs = new AyuStateVariable();
    private static final LongSparseArray<ArrayList<Integer>> deletePermitted = new LongSparseArray<>();

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

    public static void setAutomaticallyScheduled(boolean val, int resetAfter) {
        automaticallyScheduled.val = val;
        automaticallyScheduled.resetAfter = resetAfter;
    }

    /** 切换幽灵模式后清掉一次性状态，避免残留"自动定时发送"。 */
    public static void resetGhostMode() {
        setAutomaticallyScheduled(false, -1);
        setAllowReadPacket(false, -1);
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
        var list = deletePermitted.get(dialogId);
        if (list == null) {
            list = new ArrayList<>();
            deletePermitted.put(dialogId, list);
        }

        list.add(messageId);
    }

    public static boolean isDeletePermitted(long dialogId, int messageId) {
        var list = deletePermitted.get(dialogId);
        if (list == null) {
            return false;
        }

        return list.contains(messageId);
    }

    public static void messageDeleted(long dialogId, int messageId) {
        var list = deletePermitted.get(dialogId);
        if (list == null) {
            return;
        }

        list.remove((Object) messageId);
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
