package com.exteragram.messenger.feed.ui;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.feed.FeedChannelActions;
import com.exteragram.messenger.feed.FeedConfig;
import com.exteragram.messenger.feed.FeedController;
import com.exteragram.messenger.preferences.BasePreferencesActivity;
import com.exteragram.messenger.utils.system.VibratorUtils;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Feed settings: toggle the feed tab and unread counter, include archived
 * dialogs, and manage which channels are shown in or hidden from the feed.
 */
public class FeedChannelsActivity extends BasePreferencesActivity implements NotificationCenter.NotificationCenterDelegate {
    private static final int ID_BOTTOM_TAB = 1073741822;
    private static final int ID_UNREAD_COUNTER = 1073741820;
    private static final int ID_INCLUDE_ARCHIVED = 1073741823;
    private static final Comparator<TLRPC.Chat> BY_TITLE = Comparator.comparing(chat -> {
        String title = chat.title;
        return title == null ? "" : title.toLowerCase();
    });
    private final ArrayList<TLRPC.Chat> channels = new ArrayList<>();
    private ActionBarMenuItem otherItem;
    private String query;
    private boolean searching;

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.FeedSettings);
    }

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getInstance(this.currentAccount).addObserver(this, NotificationCenter.feedNeedReload);
        NotificationCenter.getInstance(this.currentAccount).addObserver(this, NotificationCenter.dialogDeleted);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getInstance(this.currentAccount).removeObserver(this, NotificationCenter.feedNeedReload);
        NotificationCenter.getInstance(this.currentAccount).removeObserver(this, NotificationCenter.dialogDeleted);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.feedNeedReload) {
            reloadChannels();
        } else if (id == NotificationCenter.dialogDeleted) {
            removeChannel((Long) args[0]);
        }
    }

    @Override
    public View createView(Context context) {
        View view = super.createView(context);
        this.actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    FeedChannelsActivity.this.finishFragment();
                } else if (id == 1) {
                    FeedChannelsActivity.this.setAllExcluded(false);
                } else if (id == 2) {
                    FeedChannelsActivity.this.setAllExcluded(true);
                }
            }
        });
        ActionBarMenu menu = this.actionBar.createMenu();
        menu.addItem(0, R.drawable.outline_header_search).setIsSearchField(true).setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
            @Override
            public void onSearchExpand() {
                FeedChannelsActivity.this.searching = true;
                if (FeedChannelsActivity.this.otherItem != null) {
                    FeedChannelsActivity.this.otherItem.setVisibility(View.GONE);
                }
            }

            @Override
            public void onSearchCollapse() {
                FeedChannelsActivity.this.searching = false;
                FeedChannelsActivity.this.query = null;
                if (FeedChannelsActivity.this.otherItem != null) {
                    FeedChannelsActivity.this.otherItem.setVisibility(View.VISIBLE);
                }
                if (FeedChannelsActivity.this.listView != null) {
                    FeedChannelsActivity.this.listView.adapter.update(true);
                }
            }

            @Override
            public void onTextChanged(EditText editText) {
                FeedChannelsActivity.this.query = editText.getText().toString().trim().toLowerCase();
                if (FeedChannelsActivity.this.listView != null) {
                    FeedChannelsActivity.this.listView.adapter.update(true);
                }
            }
        }).setSearchFieldHint(LocaleController.getString(R.string.Search));
        this.otherItem = menu.addItem(3, R.drawable.ic_ab_other);
        this.otherItem.addSubItem(1, R.drawable.msg_select, LocaleController.getString(R.string.SelectAll));
        this.otherItem.addSubItem(2, R.drawable.msg_cancel, LocaleController.getString(R.string.DeselectAll));
        reloadChannels();
        return view;
    }

    @Override
    public boolean onBackPressed(boolean last) {
        if (!this.searching) {
            return super.onBackPressed(last);
        }
        if (!last) {
            return false;
        }
        this.actionBar.closeSearchField();
        return false;
    }

    private void reloadChannels() {
        FeedController.getInstance(this.currentAccount).loadChannels(true, (channelList, includedCount, failed, configGeneration) -> {
            if (failed) {
                return;
            }
            this.channels.clear();
            for (int i = 0; i < channelList.size(); i++) {
                TLRPC.Chat chat = channelList.get(i);
                TLRPC.Chat knownChat = getMessagesController().getChat(Long.valueOf(chat.id));
                this.channels.add(knownChat != null ? knownChat : chat);
            }
            this.channels.sort(BY_TITLE);
            UniversalRecyclerView recyclerView = this.listView;
            if (recyclerView != null) {
                recyclerView.adapter.update(true);
            }
        });
    }

    private void removeChannel(long dialogId) {
        for (int i = 0; i < this.channels.size(); i++) {
            if ((-this.channels.get(i).id) == dialogId) {
                this.channels.remove(i);
                UniversalRecyclerView recyclerView = this.listView;
                if (recyclerView != null) {
                    recyclerView.adapter.update(true);
                }
                return;
            }
        }
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        FeedConfig feedConfig = FeedConfig.getInstance(this.currentAccount);
        boolean queryEmpty = TextUtils.isEmpty(this.query);
        if (queryEmpty) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.General)));
            items.add(UItem.asCheck(ID_BOTTOM_TAB, LocaleController.getString(R.string.FeedBottomTab), LocaleController.getString(R.string.FeedBottomTabInfo), true).setChecked(ExteraConfig.getShowFeedTab()));
            items.add(UItem.asCheck(ID_UNREAD_COUNTER, LocaleController.getString(R.string.FeedUnreadCounter)).setChecked(ExteraConfig.getShowFeedUnreadCounter()));
            items.add(UItem.asCheck(ID_INCLUDE_ARCHIVED, LocaleController.getString(R.string.FeedIncludeArchived)).setChecked(feedConfig.getIncludeArchived()));
            items.add(UItem.asShadow(LocaleController.getString(R.string.FeedIncludeArchivedInfo)));
        }
        ArrayList<UItem> shown = new ArrayList<>();
        ArrayList<UItem> hidden = new ArrayList<>();
        for (int i = 0; i < this.channels.size(); i++) {
            TLRPC.Chat chat = this.channels.get(i);
            String title = chat.title;
            if (queryEmpty || (title != null && title.toLowerCase().contains(this.query))) {
                boolean excluded = feedConfig.isExcluded(-chat.id);
                (!excluded ? shown : hidden).add(UItem.asUserCheckbox((int) chat.id, chat).setChecked(!excluded));
            }
        }
        if (!shown.isEmpty()) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.FeedShownChannels)));
            items.addAll(shown);
        }
        if (!hidden.isEmpty()) {
            if (!shown.isEmpty()) {
                items.add(UItem.asShadow(""));
            }
            items.add(UItem.asHeader(LocaleController.getString(R.string.FeedHiddenChannels)));
            items.addAll(hidden);
        }
        if (queryEmpty && !(shown.isEmpty() && hidden.isEmpty())) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.FeedChannelsInfo)));
        }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        Object object = item.object;
        if (object instanceof TLRPC.Chat) {
            final TLRPC.Chat chat = (TLRPC.Chat) object;
            toggleBooleanSettingAndRefresh(item, value -> FeedConfig.getInstance(FeedChannelsActivity.this.currentAccount).setExcluded(-chat.id, !value));
            return;
        }
        int id = item.id;
        if (id == ID_BOTTOM_TAB) {
            ExteraConfig.setShowFeedTab(!ExteraConfig.getShowFeedTab());
            UniversalRecyclerView recyclerView = this.listView;
            if (recyclerView != null) {
                recyclerView.adapter.update(true);
            }
            NotificationCenter.getInstance(this.currentAccount).postNotificationNameOnUIThread(NotificationCenter.feedTabVisibleToggled);
        } else if (id == ID_UNREAD_COUNTER) {
            toggleBooleanSettingAndRefresh(item, value -> {
                ExteraConfig.setShowFeedUnreadCounter(value);
                NotificationCenter.getInstance(FeedChannelsActivity.this.currentAccount).postNotificationNameOnUIThread(NotificationCenter.updateInterfaces, 0);
            });
        } else if (id == ID_INCLUDE_ARCHIVED) {
            FeedConfig.getInstance(this.currentAccount).setIncludeArchived(!FeedConfig.getInstance(this.currentAccount).getIncludeArchived());
            reloadChannels();
        }
    }

    private void toggleBooleanSettingAndRefresh(UItem item, Consumer<Boolean> apply) {
        boolean newValue = !item.checked;
        apply.accept(newValue);
        item.setChecked(newValue);
        View cell = listView.findViewByItemId(item.id);
        if (cell instanceof CheckBoxCell) {
            ((CheckBoxCell) cell).setChecked(newValue, true);
        } else if (cell instanceof TextCheckCell) {
            ((TextCheckCell) cell).setChecked(newValue);
        }
        listView.adapter.update(true);
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        Object object = item.object;
        if (!(object instanceof TLRPC.Chat)) {
            return false;
        }
        final TLRPC.Chat chat = (TLRPC.Chat) object;
        view.performHapticFeedback(VibratorUtils.getType(3), 1);
        ItemOptions.makeOptions(this, view).setScrimViewBackground(this.listView.getClipBackground(view)).add(R.drawable.msg_channel, LocaleController.getString(R.string.OpenChannel2), () -> presentFragment(ChatActivity.of(-chat.id)))
                .addIf(FeedChannelActions.canLeave(chat), R.drawable.msg_leave, (CharSequence) LocaleController.getString(R.string.LeaveChannelMenu), true, () -> FeedChannelActions.leaveChannel(FeedChannelsActivity.this, chat, null, null))
                .show();
        return false;
    }

    private void setAllExcluded(boolean excluded) {
        FeedConfig feedConfig = FeedConfig.getInstance(this.currentAccount);
        if (excluded) {
            ArrayList<Long> dialogIds = new ArrayList<>(this.channels.size());
            for (int i = 0; i < this.channels.size(); i++) {
                dialogIds.add(-this.channels.get(i).id);
            }
            feedConfig.excludeAll(dialogIds);
        } else {
            feedConfig.clearExcluded();
        }
        UniversalRecyclerView recyclerView = this.listView;
        if (recyclerView != null) {
            recyclerView.adapter.update(true);
        }
    }
}
