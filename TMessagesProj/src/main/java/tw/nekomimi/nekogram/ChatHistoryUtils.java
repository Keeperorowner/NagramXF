package tw.nekomimi.nekogram;

import android.text.TextUtils;

import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Cells.UserCell;

import java.util.List;

public final class ChatHistoryUtils {

    // Official Telegram user IDs that should be filtered
    private static final long TELEGRAM_SERVICE_USER_ID = 777000L;
    private static final long STICKERS_BOT_USER_ID = 429000L;
    private static final long BOTFATHER_USER_ID = 136817688L;

    private static final int[] CATEGORY_STRING_IDS = {
        R.string.ChatCategoryAll,
        R.string.ChatCategoryChannels,
        R.string.ChatCategoryGroups,
        R.string.ChatCategoryUsers,
        R.string.ChatCategoryBots
    };

    private ChatHistoryUtils() {}

    /**
     * Checks if a dialog ID belongs to an official/system account that should be hidden.
     */
    public static boolean isOfficialDialog(long dialogId, int account) {
        if (dialogId > 0) {
            TLRPC.User user = MessagesController.getInstance(account).getUser(dialogId);
            if (user != null) {
                if (UserObject.isUserSelf(user) || UserObject.isReplyUser(user)) {
                    return true;
                }
            }
            if (dialogId == TELEGRAM_SERVICE_USER_ID ||
                dialogId == STICKERS_BOT_USER_ID ||
                dialogId == BOTFATHER_USER_ID) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if a HistoryItem belongs to a given category index.
     * Category 0=All, 1=Channels, 2=Groups, 3=Users, 4=Bots
     */
    public static boolean shouldIncludeInCategory(ChatHistoryActivity.HistoryItem item, int categoryIndex) {
        if (item == null) return false;
        if (categoryIndex == 0) return true;

        if (item.user != null) {
            return item.user.bot ? categoryIndex == 4 : categoryIndex == 3;
        } else if (item.chat != null) {
            return item.chat.broadcast ? categoryIndex == 1 : categoryIndex == 2;
        }
        return false;
    }

    /**
     * Binds data to a UserCell from a HistoryItem.
     */
    public static void bindUserCell(UserCell cell, ChatHistoryActivity.HistoryItem item) {
        if (cell == null || item == null) return;

        String title;
        String subtitle;

        if (item.user != null) {
            title = UserObject.getUserName(item.user);
            String username = UserObject.getPublicUsername(item.user);
            subtitle = !TextUtils.isEmpty(username) ? "@" + username : "ID: " + item.user.id;
            cell.setData(item.user, null, title, subtitle, 0, false);
        } else if (item.chat != null) {
            title = item.chat.title;
            String username = ChatObject.getPublicUsername(item.chat);
            if (!TextUtils.isEmpty(username)) {
                subtitle = "@" + username;
            } else if (ChatObject.isChannel(item.chat) && !item.chat.megagroup) {
                subtitle = LocaleController.getString(R.string.ChannelPrivate);
            } else {
                subtitle = LocaleController.getString(R.string.MegaPrivate);
            }
            cell.setData(item.chat, null, title, subtitle, 0, false);
        }
    }

    public static String getCategoryDisplayName(int categoryIndex) {
        if (categoryIndex >= 0 && categoryIndex < CATEGORY_STRING_IDS.length) {
            return LocaleController.getString(CATEGORY_STRING_IDS[categoryIndex]);
        }
        return LocaleController.getString(R.string.ChatCategoryAll);
    }

    public static int getCategoryCount(List<ChatHistoryActivity.HistoryItem> items, int categoryIndex) {
        if (items == null || items.isEmpty()) return 0;
        if (categoryIndex == 0) return items.size();
        int count = 0;
        for (ChatHistoryActivity.HistoryItem item : items) {
            if (shouldIncludeInCategory(item, categoryIndex)) {
                count++;
            }
        }
        return count;
    }

    public static String getCategoryTabTitle(List<ChatHistoryActivity.HistoryItem> items, int categoryIndex) {
        return getCategoryDisplayName(categoryIndex) + " (" + getCategoryCount(items, categoryIndex) + ")";
    }
}
