
package org.telegram.ui.Adapters;

import android.content.Context;
import android.graphics.Canvas;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.DrawerLayoutContainer;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.DrawerActionCell;
import org.telegram.ui.Cells.DrawerAddCell;
import org.telegram.ui.Cells.DrawerProfileCell;
import org.telegram.ui.Cells.DrawerUserCell;
import org.telegram.ui.Cells.EmptyCell;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SideMenultItemAnimator;

import com.exteragram.messenger.plugins.PluginsConstants;
import com.exteragram.messenger.plugins.PluginsController;
import com.exteragram.messenger.plugins.hooks.MenuItemRecord;
import com.exteragram.messenger.plugins.utils.MenuContextBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.helpers.PasscodeHelper;
import xyz.nextalone.nagram.helper.DrawerMenuHelper;

public class DrawerLayoutAdapter extends RecyclerListView.SelectionAdapter implements NotificationCenter.NotificationCenterDelegate {

    private final Context mContext;
    private final ArrayList<Item> items = new ArrayList<>(11);
    private final ArrayList<Integer> accountNumbers = new ArrayList<>();
    private boolean accountsShown;
    private DrawerProfileCell profileCell;
    private SideMenultItemAnimator itemAnimator;

    public static int nkbtnSettings = 1001;
    public static int nkbtnQrLogin = 1002;
    public static int nkbtnArchivedChats = 1003;
    public static int nkbtnRestartApp = 1004;
    public static int nkbtnGhostMode = 1006;
    public static int nkbtnBrowser = 1007;
    public static int nkbtnBookmarks = 1008;
    public static int nkbtnRecentChats = 1009;
    public static int nkbtnSessions = 1010;
    public static int nkbtnMainTabsCustomize = 1011;
    public static final int PLUGIN_ITEM_ID_BASE = 20000;
    public DrawerLayoutAdapter(Context context, SideMenultItemAnimator animator, DrawerLayoutContainer drawerLayoutContainer) {
        mContext = context;
        itemAnimator = animator;
        accountsShown = MessagesController.getGlobalMainSettings().getBoolean("accountsShown", true);
        Theme.createCommonDialogResources(context);
        resetItems();
    }

    private int getAccountRowsCount() {
        return accountNumbers.size() + 2;
    }

    @Override
    public int getItemCount() {
        int count = items.size() + 2;
        if (accountsShown) {
            count += getAccountRowsCount();
        }
        return count;
    }

    public void setAccountsShown(boolean value, boolean animated) {
        if (accountsShown == value || itemAnimator.isRunning()) {
            return;
        }
        accountsShown = value;
        MessagesController.getGlobalMainSettings().edit().putBoolean("accountsShown", accountsShown).apply();
        if (profileCell != null) {
            profileCell.setAccountsShown(accountsShown, animated);
        }
        if (animated) {
            itemAnimator.setShouldClipChildren(false);
            if (accountsShown) {
                notifyItemRangeInserted(2, getAccountRowsCount());
            } else {
                notifyItemRangeRemoved(2, getAccountRowsCount());
            }
        } else {
            notifyDataSetChanged();
        }
    }

    public boolean isAccountsShown() {
        return accountsShown;
    }

    public void setProfileCell(DrawerProfileCell profileCell) {
        this.profileCell = profileCell;
        if (profileCell != null) {
            profileCell.setAccountsShown(accountsShown, false);
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.proxySettingsChanged) {
            notifyDataSetChanged();
        }
    }

    @Override
    public void notifyDataSetChanged() {
        resetItems();
        super.notifyDataSetChanged();
    }

    @Override
    public boolean isEnabled(RecyclerView.ViewHolder holder) {
        int itemType = holder.getItemViewType();
        return itemType == 3 || itemType == 4 || itemType == 5;
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view;
        switch (viewType) {
            case 0:
                view = new EmptyCell(mContext, 0);
                break;
            case 2:
                view = new DrawerDividerCell(mContext);
                break;
            case 3:
                view = new DrawerActionCell(mContext);
                break;
            case 4:
                view = new DrawerUserCell(mContext);
                break;
            case 5:
                view = new DrawerAddCell(mContext);
                break;
            case 1:
            default:
                view = new EmptyCell(mContext, AndroidUtilities.dp(8));
                break;
        }
        view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return new RecyclerListView.Holder(view);
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
        switch (holder.getItemViewType()) {
            case 3: {
                DrawerActionCell drawerActionCell = (DrawerActionCell) holder.itemView;
                position -= 2;
                if (accountsShown) {
                    position -= getAccountRowsCount();
                }
                items.get(position).bind(drawerActionCell);
                drawerActionCell.setPadding(0, 0, 0, 0);
                break;
            }
            case 4: {
                DrawerUserCell drawerUserCell = (DrawerUserCell) holder.itemView;
                drawerUserCell.invalidate();
                drawerUserCell.setAccount(accountNumbers.get(position - 2));
                break;
            }
        }
    }

    @Override
    public int getItemViewType(int i) {
        if (i == 0) {
            return 0;
        } else if (i == 1) {
            return 1;
        }
        i -= 2;
        if (accountsShown) {
            if (i < accountNumbers.size()) {
                return 4;
            } else {
                if (i == accountNumbers.size()) {
                    return 5;
                } else if (i == accountNumbers.size() + 1) {
                    return 2;
                }
            }
            i -= getAccountRowsCount();
        }
        if (i < 0 || i >= items.size() || items.get(i) == null) {
            return 2;
        }
        return 3;
    }

    public void swapElements(int fromIndex, int toIndex) {
        int idx1 = fromIndex - 2;
        int idx2 = toIndex - 2;
        if (idx1 < 0 || idx2 < 0 || idx1 >= accountNumbers.size() || idx2 >= accountNumbers.size()) {
            return;
        }
        final UserConfig userConfig1 = UserConfig.getInstance(accountNumbers.get(idx1));
        final UserConfig userConfig2 = UserConfig.getInstance(accountNumbers.get(idx2));
        final int tempLoginTime = userConfig1.loginTime;
        userConfig1.loginTime = userConfig2.loginTime;
        userConfig2.loginTime = tempLoginTime;
        userConfig1.saveConfig(false);
        userConfig2.saveConfig(false);
        Collections.swap(accountNumbers, idx1, idx2);
        notifyItemMoved(fromIndex, toIndex);
    }

    private void resetItems() {
        accountNumbers.clear();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (PasscodeHelper.isAccountHidden(a)) continue;
            if (UserConfig.getInstance(a).isClientActivated()) {
                accountNumbers.add(a);
            }
        }
        Collections.sort(accountNumbers, (o1, o2) -> {
            long l1 = UserConfig.getInstance(o1).loginTime;
            long l2 = UserConfig.getInstance(o2).loginTime;
            if (l1 > l2) {
                return 1;
            } else if (l1 < l2) {
                return -1;
            }
            return 0;
        });

        items.clear();
        if (!UserConfig.getInstance(UserConfig.selectedAccount).isClientActivated()) {
            return;
        }

        // Configurable rows, rendered in the order chosen in the sidebar manager.
        for (int id : DrawerMenuHelper.getLayout()) {
            if (id == DrawerMenuHelper.DIVIDER) {
                if (!items.isEmpty() && items.get(items.size() - 1) != null) {
                    items.add(null);
                }
                continue;
            }
            Item item = buildItemForId(id);
            if (item != null) {
                items.add(item);
            }
        }
        // Strip any dangling divider left at the end of the configurable section.
        while (!items.isEmpty() && items.get(items.size() - 1) == null) {
            items.remove(items.size() - 1);
        }

        // Plugin-provided drawer items (MAIN_MENU / DRAWER_MENU), appended as a section.
        if (PluginsController.isPluginEngineAvailable()) {
            int account = UserConfig.selectedAccount;
            TLRPC.User currentUser = UserConfig.getInstance(account).getCurrentUser();
            MenuContextBuilder builder = MenuContextBuilder.create().withAccount(account);
            if (currentUser != null) {
                builder.withUser(currentUser);
            }
            Map<String, Object> pluginContext = builder.build();
            List<MenuItemRecord> pluginMenuItems = PluginsController.getInstance().getMenuItemsForLocation(
                    PluginsConstants.MenuItemTypes.MAIN_MENU, pluginContext);
            boolean addedPluginDivider = false;
            for (MenuItemRecord record : pluginMenuItems) {
                if (record == null || TextUtils.isEmpty(record.text)) {
                    continue;
                }
                if (!addedPluginDivider) {
                    items.add(null);
                    addedPluginDivider = true;
                }
                int iconRes = record.iconResId != 0 ? record.iconResId : R.drawable.msg_plugins;
                items.add(new Item(PLUGIN_ITEM_ID_BASE + items.size(), record.text, iconRes).onClick(v -> {
                    try {
                        record.executeClick(pluginContext);
                    } catch (Throwable t) {
                        org.telegram.messenger.FileLog.e("Failed to execute plugin drawer item " + record.itemId, t);
                    }
                }));
            }
        }

        // Attach-menu bots are server-driven (not user-configurable): appended after the configurable rows.
        TLRPC.TL_attachMenuBots menuBots = MediaDataController.getInstance(UserConfig.selectedAccount).getAttachMenuBots();
        if (menuBots != null && menuBots.bots != null) {
            boolean addedDivider = false;
            for (int i = 0; i < menuBots.bots.size(); i++) {
                TLRPC.TL_attachMenuBot bot = menuBots.bots.get(i);
                if (!bot.show_in_side_menu) {
                    continue;
                }
                if (!addedDivider) {
                    items.add(null);
                    addedDivider = true;
                }
                items.add(new Item(bot));
            }
        }
    }

    /**
     * Builds a drawer {@link Item} for a configurable row id, or {@code null} when the
     * row should not be shown (unknown id, or a premium-only row for a non-premium user).
     * Shared by the drawer and the sidebar manager.
     */
    public static Item buildItemForId(int id) {
        if (id == nkbtnGhostMode) {
            return new Item(
                    nkbtnGhostMode,
                    NekoConfig.isGhostModeActive()
                            ? LocaleController.getString(R.string.DisableGhostMode)
                            : LocaleController.getString(R.string.EnableGhostMode),
                    R.drawable.ayu_ghost
            );
        } else if (id == 16) {
            return new Item(16, LocaleController.getString(R.string.MyProfile), R.drawable.left_status_profile);
        } else if (id == 15) {
            UserConfig me = UserConfig.getInstance(UserConfig.selectedAccount);
            if (me == null || !me.isPremium()) {
                return null;
            }
            return new Item(
                    15,
                    me.getEmojiStatus() != null
                            ? LocaleController.getString(R.string.ChangeEmojiStatus)
                            : LocaleController.getString(R.string.SetEmojiStatus),
                    me.getEmojiStatus() != null ? R.drawable.msg_status_edit : R.drawable.msg_status_set
            );
        } else if (id == nkbtnArchivedChats) {
            return new Item(nkbtnArchivedChats, LocaleController.getString(R.string.ArchivedChats), R.drawable.msg_archive);
        } else if (id == 2) {
            return new Item(2, LocaleController.getString(R.string.NewGroup), R.drawable.msg_groups);
        } else if (id == 4) {
            return new Item(4, LocaleController.getString(R.string.NewChannel), R.drawable.msg_channel);
        } else if (id == 6) {
            return new Item(6, LocaleController.getString(R.string.Contacts), R.drawable.msg_contacts);
        } else if (id == 10) {
            return new Item(10, LocaleController.getString(R.string.Calls), R.drawable.msg_calls);
        } else if (id == nkbtnRecentChats) {
            return new Item(nkbtnRecentChats, LocaleController.getString(R.string.RecentChats), R.drawable.msg_recent);
        } else if (id == 11) {
            return new Item(11, LocaleController.getString(R.string.SavedMessages), R.drawable.msg_saved);
        } else if (id == nkbtnBookmarks) {
            return new Item(nkbtnBookmarks, LocaleController.getString(R.string.BookmarksManager), R.drawable.msg_fave);
        } else if (id == 8) {
            return new Item(8, LocaleController.getString(R.string.Settings), R.drawable.msg_settings_old);
        } else if (id == nkbtnSettings) {
            return new Item(nkbtnSettings, LocaleController.getString(R.string.NekoSettings), R.drawable.nagramx_outline);
        } else if (id == nkbtnBrowser) {
            return new Item(nkbtnBrowser, LocaleController.getString(R.string.InappBrowser), R.drawable.web_browser);
        } else if (id == nkbtnQrLogin) {
            return new Item(nkbtnQrLogin, LocaleController.getString(R.string.ImportLogin), R.drawable.msg_qrcode);
        } else if (id == nkbtnSessions) {
            return new Item(nkbtnSessions, LocaleController.getString(R.string.Devices), R.drawable.msg2_devices);
        } else if (id == nkbtnMainTabsCustomize) {
            return new Item(nkbtnMainTabsCustomize, LocaleController.getString(R.string.MainTabsCustomize), R.drawable.tabs_reorder);
        } else if (id == nkbtnRestartApp) {
            return new Item(nkbtnRestartApp, LocaleController.getString(R.string.RestartApp), R.drawable.msg_retry);
        }
        return null;
    }

    public boolean click(View view, int position) {
        position -= 2;
        if (accountsShown) {
            position -= getAccountRowsCount();
        }
        if (position < 0 || position >= items.size()) {
            return false;
        }
        Item item = items.get(position);
        if (item != null && item.listener != null) {
            item.listener.onClick(view);
            return true;
        }
        return false;
    }

    public int getId(int position) {
        position -= 2;
        if (accountsShown) {
            position -= getAccountRowsCount();
        }
        if (position < 0 || position >= items.size()) {
            return -1;
        }
        Item item = items.get(position);
        return item != null ? item.id : -1;
    }

    public int getFirstAccountPosition() {
        if (!accountsShown) {
            return RecyclerView.NO_POSITION;
        }
        return 2;
    }

    public int getLastAccountPosition() {
        if (!accountsShown) {
            return RecyclerView.NO_POSITION;
        }
        return 1 + accountNumbers.size();
    }

    public TLRPC.TL_attachMenuBot getAttachMenuBot(int position) {
        position -= 2;
        if (accountsShown) {
            position -= getAccountRowsCount();
        }
        if (position < 0 || position >= items.size()) {
            return null;
        }
        Item item = items.get(position);
        return item != null ? item.bot : null;
    }

    public static class Item {
        public int icon;
        public CharSequence text;
        public int id;
        TLRPC.TL_attachMenuBot bot;
        View.OnClickListener listener;
        public boolean error;

        public Item(int id, CharSequence text, int icon) {
            this.icon = icon;
            this.id = id;
            this.text = text;
        }

        public Item(TLRPC.TL_attachMenuBot bot) {
            this.bot = bot;
            this.id = (int) (100 + (bot.bot_id >> 16));
        }

        public void bind(DrawerActionCell actionCell) {
            if (this.bot != null) {
                actionCell.setBot(bot);
            } else {
                actionCell.setTextAndIcon(id, text, icon);
            }
            actionCell.setError(error);
        }

        public Item onClick(View.OnClickListener listener) {
            this.listener = listener;
            return this;
        }

        public Item withError() {
            this.error = true;
            return this;
        }
    }

    private static class DrawerDividerCell extends View {

        public DrawerDividerCell(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), AndroidUtilities.dp(8));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int y = getMeasuredHeight() / 2;
            canvas.drawLine(0, y, getMeasuredWidth(), y, Theme.dividerPaint);
        }
    }

}
