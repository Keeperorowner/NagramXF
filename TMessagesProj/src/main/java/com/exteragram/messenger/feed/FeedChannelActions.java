package com.exteragram.messenger.feed;

import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.ItemOptions;

import java.util.ArrayList;
import java.util.function.Consumer;

/**
 * Channel-level actions shown from the feed UI: open, hide (with undo) and
 * leave. Leaving a channel also removes its rows from the feed timeline.
 */
public abstract class FeedChannelActions {
    public static boolean canLeave(TLRPC.Chat chat) {
        return chat != null && !chat.creator && !ChatObject.isNotInChat(chat);
    }

    public static void showAvatarMenu(ChatActivity chatActivity, ChatMessageCell cell, TLRPC.Chat chat, Runnable onOpen, Runnable onLeft, Consumer<ArrayList<Integer>> onRowsDeleted) {
        if (chatActivity == null || cell == null || chat == null) {
            return;
        }
        ItemOptions options = ItemOptions.makeOptions(chatActivity, cell);
        boolean broadcast = chat.broadcast;
        options.add(broadcast ? R.drawable.msg_channel : R.drawable.msg_discussion, LocaleController.getString(broadcast ? R.string.OpenChannel2 : R.string.OpenGroup2), onOpen)
                .add(R.drawable.menu_hide_gift, LocaleController.getString(R.string.FeedHideChannel), () -> chatActivity.hideFeedChannelWithUndo(-chat.id, chat.title))
                .addIf(canLeave(chat), R.drawable.msg_leave, (CharSequence) LocaleController.getString(chat.broadcast ? R.string.LeaveChannelMenu : R.string.LeaveMegaMenu), true, () -> leaveChannel(chatActivity, chat, onLeft, onRowsDeleted))
                .setDrawScrim(false)
                .setGravity(3)
                .forceBottom(true)
                .show();
    }

    public static void leaveChannel(BaseFragment fragment, TLRPC.Chat chat, Runnable onLeft, Consumer<ArrayList<Integer>> onRowsDeleted) {
        if (fragment == null || chat == null || fragment.getParentActivity() == null) {
            return;
        }
        AlertsCreator.createClearOrDeleteDialogAlert(fragment, false, chat, null, false, true, false, false, cleared -> {
            long dialogId = -chat.id;
            if (ChatObject.isNotInChat(chat)) {
                fragment.getMessagesController().deleteDialog(dialogId, 0, cleared);
            } else {
                fragment.getMessagesController().deleteParticipantFromChat(chat.id, fragment.getMessagesController().getUser(Long.valueOf(fragment.getUserConfig().getClientUserId())), null, cleared, cleared);            }
            deleteFeedRows(fragment, dialogId, onRowsDeleted);
            if (onLeft != null) {
                onLeft.run();
            }
        });
    }

    private static void deleteFeedRows(BaseFragment fragment, long dialogId, Consumer<ArrayList<Integer>> onRowsDeleted) {
        ArrayList<Integer> removedIds = FeedController.getInstance(fragment.getCurrentAccount()).deleteHistory(dialogId, Integer.MAX_VALUE);
        if (onRowsDeleted != null) {
            onRowsDeleted.accept(removedIds);
        }
    }
}
