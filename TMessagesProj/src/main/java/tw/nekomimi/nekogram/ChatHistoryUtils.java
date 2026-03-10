
package tw.nekomimi.nekogram;

import android.os.Parcelable;
import android.text.TextUtils;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Cells.UserCell;

public final class ChatHistoryUtils {

    private ChatHistoryUtils() {
    }

    public static class ChatDisplayInfo {
        public final String title;
        public final String subtitle;

        public ChatDisplayInfo(String title, String subtitle) {
            this.title = title;
            this.subtitle = subtitle;
        }
    }

    public static ChatDisplayInfo getChatDisplayInfo(ChatHistoryActivity.HistoryItem item) {
        if (item == null) {
            return new ChatDisplayInfo("", "");
        }

        String title;
        String subtitle;

        if (item.user != null) {
            title = UserObject.getUserName(item.user);
            String username = UserObject.getPublicUsername(item.user);
            if (!TextUtils.isEmpty(username)) {
                subtitle = "@" + username;
            } else {
                subtitle = "ID: " + item.user.id;
            }
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
        } else {
            title = "";
            subtitle = "";
        }

        return new ChatDisplayInfo(title, subtitle);
    }

    public static void bindUserCell(UserCell cell, ChatHistoryActivity.HistoryItem item) {
        if (cell == null || item == null) {
            return;
        }

        ChatDisplayInfo info = getChatDisplayInfo(item);

        if (item.user != null) {
            cell.setData(item.user, null, info.title, info.subtitle, 0, false);
        } else if (item.chat != null) {
            cell.setData(item.chat, null, info.title, info.subtitle, 0, false);
        }
    }

    public static void saveListPosition(
            RecyclerView listView,
            int tabIndex,
            SparseIntArray savedFirstVisible,
            SparseIntArray savedTopOffset,
            SparseArray<Parcelable> savedLayoutState) {
        
        if (listView == null) return;

        RecyclerView.LayoutManager lm = listView.getLayoutManager();
        if (!(lm instanceof LinearLayoutManager)) return;

        LinearLayoutManager llm = (LinearLayoutManager) lm;
        int pos = llm.findFirstVisibleItemPosition();
        View first = llm.findViewByPosition(pos);
        int offset = first == null ? 0 : first.getTop() - listView.getPaddingTop();

        savedFirstVisible.put(tabIndex, pos);
        savedTopOffset.put(tabIndex, offset);

        try {
            Parcelable state = llm.onSaveInstanceState();
            if (state != null) {
                savedLayoutState.put(tabIndex, state);
            }
        } catch (Exception ignore) {
        }
    }

    public static void restoreListPosition(
            RecyclerView listView,
            int tabIndex,
            SparseIntArray savedFirstVisible,
            SparseIntArray savedTopOffset,
            SparseArray<Parcelable> savedLayoutState) {
        
        if (listView == null) return;

        RecyclerView.LayoutManager lm = listView.getLayoutManager();
        if (!(lm instanceof LinearLayoutManager)) return;

        LinearLayoutManager llm = (LinearLayoutManager) lm;
        Parcelable state = savedLayoutState.get(tabIndex);
        int pos = savedFirstVisible.get(tabIndex, -1);
        int offset = savedTopOffset.get(tabIndex, 0);

        if (state != null) {
            try {
                llm.onRestoreInstanceState(state);
                return;
            } catch (Exception ignore) {
            }
        }

        if (pos >= 0) {
            llm.scrollToPositionWithOffset(pos, offset);
        }
    }

    public static boolean shouldIncludeInCategory(
            ChatHistoryActivity.HistoryItem item,
            int categoryIndex) {
        
        if (item == null) return false;

        if (categoryIndex == 0) {
            return true;
        }

        if (item.user != null) {
            if (item.user.bot) {
                return categoryIndex == 4;
            } else {
                return categoryIndex == 3;
            }
        } else if (item.chat != null) {
            if (item.chat.broadcast) {
                return categoryIndex == 1;
            } else {
                return categoryIndex == 2;
            }
        }

        return false;
    }

    private static final int[] CATEGORY_STRING_IDS = {
        R.string.ChatCategoryAll,
        R.string.ChatCategoryChannels,
        R.string.ChatCategoryGroups,
        R.string.ChatCategoryUsers,
        R.string.ChatCategoryBots
    };

    public static String getCategoryDisplayName(int categoryIndex) {
        if (categoryIndex >= 0 && categoryIndex < CATEGORY_STRING_IDS.length) {
            return LocaleController.getString(CATEGORY_STRING_IDS[categoryIndex]);
        }
        return LocaleController.getString(R.string.ChatCategoryAll);
    }
}
