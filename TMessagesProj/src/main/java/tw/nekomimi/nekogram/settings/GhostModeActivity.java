package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.LocaleController.getString;
import static org.telegram.ui.LaunchActivity.getLastFragment;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.radolyn.ayugram.AyuGhostConfig;
import com.radolyn.ayugram.AyuWorker;
import com.radolyn.ayugram.preferences.components.AccountCell;
import com.radolyn.ayugram.utils.AyuGhostUtils;
import com.radolyn.ayugram.utils.AyuState;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.AccountSelectCell;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextCheckCell2;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import tw.nekomimi.nekogram.config.CellGroup;
import tw.nekomimi.nekogram.config.ConfigItem;
import tw.nekomimi.nekogram.config.cell.AbstractConfigCell;
import tw.nekomimi.nekogram.config.cell.ConfigCellCheckBox;
import tw.nekomimi.nekogram.config.cell.ConfigCellCustom;
import tw.nekomimi.nekogram.config.cell.ConfigCellHeader;
import tw.nekomimi.nekogram.config.cell.ConfigCellTextCheck2;
import xyz.nextalone.nagram.NaConfig;

public class GhostModeActivity extends BaseNekoXSettingsActivity {

    private ListAdapter listAdapter;
    private final CellGroup cellGroup = new CellGroup(this);


    private int currentViewingAccount = -1;

    private AyuGhostConfig.GhostModeSettings currentSettings;


    private final ConfigItem sendReadMessagePacketsItem = ghostBoolItem("sendReadMessagePackets",
            () -> currentSettings.sendReadMessagePackets,
            v -> { currentSettings.sendReadMessagePackets = v; currentSettings.save(); });

    private final ConfigItem sendReadStoriesPacketsItem = ghostBoolItem("sendReadStoriesPackets",
            () -> currentSettings.sendReadStoryPackets,
            v -> { currentSettings.sendReadStoryPackets = v; currentSettings.save(); });

    private final ConfigItem sendOnlinePacketsItem = ghostBoolItem("sendOnlinePackets",
            () -> currentSettings.sendOnlinePackets,
            v -> { currentSettings.sendOnlinePackets = v; currentSettings.save(); });

    private final ConfigItem sendUploadProgressItem = ghostBoolItem("sendUploadProgress",
            () -> currentSettings.sendUploadProgress,
            v -> { currentSettings.sendUploadProgress = v; currentSettings.save(); });

    private final ConfigItem sendOfflinePacketAfterOnlineItem = ghostBoolItem("sendOfflinePacketAfterOnline",
            () -> currentSettings.sendOfflinePacketAfterOnline,
            v -> { currentSettings.sendOfflinePacketAfterOnline = v; currentSettings.save(); });

    private final ConfigItem invSendReadMessagePackets = inverted("inv_sendReadMessagePackets", sendReadMessagePacketsItem);
    private final ConfigItem invSendReadStoriesPackets = inverted("inv_sendReadStoriesPackets", sendReadStoriesPacketsItem);
    private final ConfigItem invSendOnlinePackets = inverted("inv_sendOnlinePackets", sendOnlinePacketsItem);
    private final ConfigItem invSendUploadProgress = inverted("inv_sendUploadProgress", sendUploadProgressItem);


    private final AbstractConfigCell ghostEssentialsHeaderRow = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.GhostEssentialsHeader)));

    private final AbstractConfigCell ghostModeNoticeRow = new ConfigCellCustom("GhostModeNotice", CellGroup.ITEM_TYPE_TEXT, false);

    private boolean ghostModeMenuExpanded = false;

    private final AbstractConfigCell ghostModeToggleRow = cellGroup.appendCell(
            new ConfigCellTextCheck2("GhostMode", getString(R.string.GhostMode), new ArrayList<>() {{
                add(new ConfigCellCheckBox(invSendReadMessagePackets, "DontSendReadMessagePackets", getString(R.string.DontSendReadMessagePackets), 0, true));
                add(new ConfigCellCheckBox(invSendReadStoriesPackets, "DontReadStoriesPackets", getString(R.string.DontReadStoriesPackets), 0, true));
                add(new ConfigCellCheckBox(invSendOnlinePackets, "DontSendOnlinePackets", getString(R.string.DontSendOnlinePackets), 0, true));
                add(new ConfigCellCheckBox(invSendUploadProgress, "DontSendUploadProgress", getString(R.string.DontSendUploadProgress), 0, true));
                add(new ConfigCellCheckBox(sendOfflinePacketAfterOnlineItem, "SendOfflinePacketAfterOnline", getString(R.string.SendOfflinePacketAfterOnline), 0, false));
            }}, null) {
                @Override
                public void onBindViewHolder(RecyclerView.ViewHolder holder) {
                    TextCheckCell2 checkCell = (TextCheckCell2) holder.itemView;
                    this.cell = checkCell;
                    cell.setEnabled(isEnabled());
                    cell.setTextAndCheck(getTitle(), currentSettings.isGhostModeActive(), cellGroup.needSetDivider(this), true);
                    cell.setCollapseArrow(String.format(Locale.US, "%d/%d", currentSettings.getSelectedCount(), getVisibleCheckBox().size()), isCollapsed(), this::onCheckClick);
                    cell.getCheckBox().setColors(Theme.key_switchTrack, Theme.key_switchTrackChecked, Theme.key_windowBackgroundWhite, Theme.key_windowBackgroundWhite);
                    cell.getCheckBox().setDrawIconType(0);
                }

                @Override
                public void onCheckClick() {
                    boolean newState = !currentSettings.isGhostModeActive();
                    currentSettings.setGhostMode(newState);
                    String msg = newState
                            ? getString(R.string.GhostModeEnabled)
                            : getString(R.string.GhostModeDisabled);
                    BulletinFactory.of(getLastFragment()).createSuccessBulletin(msg).show();

                    if (currentViewingAccount >= 0) {
                        if (newState) {
                            if (!currentSettings.sendOfflinePacketAfterOnlineLocked
                                    && currentSettings.sendOfflinePacketAfterOnline) {
                                AyuWorker.setOnline(currentViewingAccount, true);
                            }
                            AyuGhostUtils.performStatusRequest(currentViewingAccount, true);
                        } else {
                            AyuWorker.clearOnline(currentViewingAccount);
                            AyuGhostUtils.performStatusRequest(currentViewingAccount, false);
                        }
                        currentSettings.postChangedNotification(currentViewingAccount);
                    }

                    updateGhostViews();
                }

                @Override
                public void onClick() {
                    if (!isEnabled()) return;
                    setCollapsed(!isCollapsed());
                    RecyclerListView.SelectionAdapter adapter = cellGroup.getListAdapter();
                    int toggleRowIndex = cellGroup.rows.indexOf(this);
                    ArrayList<ConfigCellCheckBox> visibleCheckBox = getVisibleCheckBox();
                    if (!isCollapsed()) {
                        List<AbstractConfigCell> boundNewRows = new ArrayList<>(visibleCheckBox.size() + 1);
                        for (AbstractConfigCell checkBoxItem : visibleCheckBox) {
                            checkBoxItem.bindCellGroup(cellGroup);
                            boundNewRows.add(checkBoxItem);
                        }
                        ghostModeNoticeRow.bindCellGroup(cellGroup);
                        boundNewRows.add(ghostModeNoticeRow);
                        cellGroup.rows.addAll(toggleRowIndex + 1, boundNewRows);
                        addRowsToMap(cellGroup);
                        adapter.notifyItemRangeInserted(toggleRowIndex + 1, visibleCheckBox.size() + 1);
                    } else {
                        cellGroup.rows.removeAll(getCheckBox());
                        cellGroup.rows.remove(ghostModeNoticeRow);
                        addRowsToMap(cellGroup);
                        adapter.notifyItemRangeRemoved(toggleRowIndex + 1, visibleCheckBox.size() + 1);
                    }
                    adapter.notifyItemChanged(toggleRowIndex);
                }
            });

    private final ArrayList<ConfigCellCheckBox> ghostModeCheckBoxRows = ((ConfigCellTextCheck2) ghostModeToggleRow).getCheckBox();

    private final AbstractConfigCell markReadAfterSendRow = cellGroup.appendCell(new ConfigCellCustom("MarkReadAfterSend", CellGroup.ITEM_TYPE_TEXT_CHECK, true));
    private final AbstractConfigCell markReadAfterSendNoticeRow = cellGroup.appendCell(new ConfigCellCustom("MarkReadAfterSendNotice", CellGroup.ITEM_TYPE_TEXT, false));
    private final AbstractConfigCell useScheduledMessagesRow = cellGroup.appendCell(new ConfigCellCustom("UseScheduledMessages", CellGroup.ITEM_TYPE_TEXT_CHECK, true));
    private final AbstractConfigCell useScheduledMessagesNoticeRow = cellGroup.appendCell(new ConfigCellCustom("UseScheduledMessagesDescription", CellGroup.ITEM_TYPE_TEXT, false));
    private final AbstractConfigCell sendWithoutSoundRow = cellGroup.appendCell(new ConfigCellCustom("SendWithoutSoundByDefault", CellGroup.ITEM_TYPE_TEXT_SETTINGS_CELL, true));
    private final AbstractConfigCell sendWithoutSoundNoticeRow = cellGroup.appendCell(new ConfigCellCustom("SendWithoutSoundRowNotice", CellGroup.ITEM_TYPE_TEXT, false));
    private final AbstractConfigCell suggestGhostBeforeStoryRow = cellGroup.appendCell(new ConfigCellCustom("SuggestGhostModeBeforeViewingStory", CellGroup.ITEM_TYPE_TEXT_CHECK, false));


    private ActionBarMenuItem switchItem;
    private BackupImageView avatarImageView;
    private AccountCell globalAccountCell;
    private final Map<Integer, FrameLayout> accountSelectorItems = new HashMap<>();
    private static final int ID_GLOBAL_SETTINGS = 100;
    private static final int ID_ACCOUNT_BASE = 200;

    public GhostModeActivity() {
        addRowsToMap(cellGroup);
    }


    private ConfigItem ghostBoolItem(String key, java.util.function.BooleanSupplier getter, java.util.function.Consumer<Boolean> setter) {
        return new ConfigItem(key, ConfigItem.configTypeBool, true) {
            @Override
            public boolean Bool() {
                return currentSettings != null && getter.getAsBoolean();
            }

            @Override
            public void setConfigBool(boolean v) {
                if (currentSettings != null) setter.accept(v);
            }

            @Override
            public boolean toggleConfigBool() {
                boolean n = !Bool();
                setConfigBool(n);
                return n;
            }

            @Override
            public void saveConfig() {
                if (currentSettings != null) currentSettings.save();
            }

            @Override
            public void changed(Object o) {
                if (o instanceof Boolean) {
                    setConfigBool((boolean) o);
                }
            }
        };
    }

    private ConfigItem inverted(String key, ConfigItem original) {
        return new ConfigItem(key, ConfigItem.configTypeBool, !(boolean) original.defaultValue) {
            @Override
            public boolean Bool() {
                return !original.Bool();
            }

            @Override
            public boolean toggleConfigBool() {
                original.toggleConfigBool();
                return Bool();
            }

            @Override
            public void setConfigBool(boolean v) {
                original.setConfigBool(!v);
            }

            @Override
            public void saveConfig() {
                original.saveConfig();
            }

            @Override
            public void changed(Object o) {
                if (o instanceof Boolean) {
                    original.changed(!(boolean) o);
                } else {
                    original.changed(o);
                }
            }
        };
    }


    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        if (AyuGhostConfig.isGlobalOverride() || UserConfig.getActivatedAccountsCount() <= 1) {
            currentViewingAccount = -1;
        } else {
            currentViewingAccount = UserConfig.selectedAccount;
        }
        refreshCurrentSettings();
        return true;
    }

    private void refreshCurrentSettings() {
        if (currentViewingAccount < 0) {
            currentSettings = AyuGhostConfig.getGhostModeSettings(-1);
        } else {
            long userId = UserConfig.getInstance(currentViewingAccount).getClientUserId();
            currentSettings = AyuGhostConfig.getGhostModeSettings(userId);
        }
    }

    @Override
    protected RecyclerListView.SelectionAdapter getListAdapter() {
        return listAdapter;
    }

    @Override
    protected CellGroup getCellGroup() {
        return cellGroup;
    }

    @Override
    public View createView(Context context) {
        View view = super.createView(context);
        listAdapter = new ListAdapter(context);
        listView.setAdapter(listAdapter);
        setupDefaultListeners();
        setupAccountSelector(context);
        return view;
    }


    private void setupAccountSelector(Context context) {
        if (UserConfig.getActivatedAccountsCount() <= 1) {
            if (!AyuGhostConfig.isGlobalOverride()) {
                AyuGhostConfig.setGlobalOverride(true);
            }
            currentViewingAccount = -1;
            refreshCurrentSettings();
            return;
        }

        switchItem = actionBar.createMenu().addItemWithWidth(ID_GLOBAL_SETTINGS, 0, AndroidUtilities.dp(56));

        AvatarDrawable avatarDrawable = new AvatarDrawable();
        avatarDrawable.setTextSize(AndroidUtilities.dp(12));
        avatarImageView = new BackupImageView(context);
        avatarImageView.setRoundRadius(AndroidUtilities.dp(18));
        switchItem.addView(avatarImageView, LayoutHelper.createFrame(36, 36, 17));

        accountSelectorItems.clear();

        globalAccountCell = new AccountCell(context);
        switchItem.addSubItem(ID_GLOBAL_SETTINGS, globalAccountCell, AndroidUtilities.dp(268), AndroidUtilities.dp(48));
        accountSelectorItems.put(ID_GLOBAL_SETTINGS, globalAccountCell);

        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            TLRPC.User currentUser = AccountInstance.getInstance(i).getUserConfig().getCurrentUser();
            if (currentUser != null) {
                AccountSelectCell accountCell = new AccountSelectCell(context, false);
                accountCell.setAccount(i, true);
                int itemId = ID_ACCOUNT_BASE + i;
                switchItem.addSubItem(itemId, accountCell, AndroidUtilities.dp(268), AndroidUtilities.dp(48));
                accountSelectorItems.put(itemId, accountCell);
            }
        }

        int initialSelectedId = AyuGhostConfig.isGlobalOverride() ? ID_GLOBAL_SETTINGS : (ID_ACCOUNT_BASE + currentViewingAccount);
        updateSelectorSelection(initialSelectedId);
        updateSelectorAvatar(context);

        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                    return;
                }
                if (id == ID_GLOBAL_SETTINGS) {
                    AyuGhostConfig.setGlobalOverride(true);
                    currentViewingAccount = -1;
                } else if (id >= ID_ACCOUNT_BASE) {
                    int account = id - ID_ACCOUNT_BASE;
                    if (UserConfig.isValidAccount(account)) {
                        AyuGhostConfig.setGlobalOverride(false);
                        currentViewingAccount = account;
                    }
                } else {
                    return;
                }
                refreshCurrentSettings();
                updateSelectorSelection(id);
                updateSelectorAvatar(context);
                if (listAdapter != null) {
                    listAdapter.notifyDataSetChanged();
                }
                NotificationCenter.getInstance(UserConfig.selectedAccount)
                        .postNotificationName(NotificationCenter.mainUserInfoChanged);
            }
        });
    }

    private void updateSelectorAvatar(Context context) {
        if (avatarImageView == null) return;
        if (AyuGhostConfig.isGlobalOverride() || currentViewingAccount < 0) {
            if (globalAccountCell != null) {
                avatarImageView.setImageDrawable(globalAccountCell.getAvatarDrawable());
            }
        } else {
            TLRPC.User user = UserConfig.getInstance(currentViewingAccount).getCurrentUser();
            if (user != null) {
                AvatarDrawable avatarDrawable = new AvatarDrawable();
                avatarDrawable.setInfo(currentViewingAccount, user);
                Drawable placeholder = user.photo != null && user.photo.strippedBitmap != null ? user.photo.strippedBitmap : avatarDrawable;
                avatarImageView.setImage(ImageLocation.getForUserOrChat(user, ImageLocation.TYPE_SMALL), "50_50", ImageLocation.getForUserOrChat(user, ImageLocation.TYPE_STRIPPED), "50_50", placeholder, user);
            }
        }
    }

    private void updateSelectorSelection(int clickedId) {
        for (Map.Entry<Integer, FrameLayout> entry : accountSelectorItems.entrySet()) {
            int id = entry.getKey();
            boolean selected = (id == clickedId);
            entry.getValue().setSelected(selected);
        }
    }


    private int rowIndex(AbstractConfigCell row) {
        return cellGroup.rows.indexOf(row);
    }

    private void notifyRow(AbstractConfigCell row) {
        int index = rowIndex(row);
        if (listAdapter != null && index >= 0) {
            listAdapter.notifyItemChanged(index);
        }
    }

    private void updateGhostViews() {
        notifyRow(ghostModeToggleRow);
        for (ConfigCellCheckBox cb : ghostModeCheckBoxRows) {
            notifyRow(cb);
        }
        int notifyAccount = currentViewingAccount >= 0 ? currentViewingAccount : UserConfig.selectedAccount;
        NotificationCenter.getInstance(notifyAccount).postNotificationName(NotificationCenter.mainUserInfoChanged);
    }

    private void showSendWithoutSoundDialog(View view) {
        if (getParentActivity() == null) return;
        int currentState = currentSettings.sendWithoutSound;
        String[] items = {
                getString(R.string.SendWithoutSoundByDefaultNever),
                getString(R.string.SendWithoutSoundByDefaultInGhostMode),
                getString(R.string.SendWithoutSoundByDefaultAlways)
        };
        showSingleChoiceDialog(getParentActivity(), getString(R.string.SilentMessageByDefault), items, currentState, getResourceProvider(), which -> {
            if (which == currentState) return;
            currentSettings.sendWithoutSound = which;
            currentSettings.save();
            notifyRow(sendWithoutSoundRow);
        });
    }

    private ConfigItem getGhostModeLockedItem(AbstractConfigCell row) {
        if (!(row instanceof ConfigCellCheckBox checkBox)) return null;
        ConfigItem bindConfig = checkBox.getBindConfig();
        if (bindConfig == invSendReadMessagePackets) {
            return ghostBoolItem("sendReadMessagePacketsLocked",
                    () -> currentSettings.sendReadMessagePacketsLocked,
                    v -> { currentSettings.sendReadMessagePacketsLocked = v; currentSettings.save(); });
        }
        if (bindConfig == invSendReadStoriesPackets) {
            return ghostBoolItem("sendReadStoriesPacketsLocked",
                    () -> currentSettings.sendReadStoryPacketsLocked,
                    v -> { currentSettings.sendReadStoryPacketsLocked = v; currentSettings.save(); });
        }
        if (bindConfig == invSendOnlinePackets) {
            return ghostBoolItem("sendOnlinePacketsLocked",
                    () -> currentSettings.sendOnlinePacketsLocked,
                    v -> { currentSettings.sendOnlinePacketsLocked = v; currentSettings.save(); });
        }
        if (bindConfig == invSendUploadProgress) {
            return ghostBoolItem("sendUploadProgressLocked",
                    () -> currentSettings.sendUploadProgressLocked,
                    v -> { currentSettings.sendUploadProgressLocked = v; currentSettings.save(); });
        }
        if (bindConfig == sendOfflinePacketAfterOnlineItem) {
            return ghostBoolItem("sendOfflinePacketAfterOnlineLocked",
                    () -> currentSettings.sendOfflinePacketAfterOnlineLocked,
                    v -> { currentSettings.sendOfflinePacketAfterOnlineLocked = v; currentSettings.save(); });
        }
        return null;
    }

    @Override
    protected void onCheckBoxCellClick(View view, int position) {
        AbstractConfigCell row = cellGroup.rows.get(position);
        if (row instanceof ConfigCellCheckBox checkBox) {
            ConfigItem lockedItem = getGhostModeLockedItem(checkBox);
            if (lockedItem != null && lockedItem.Bool()) return;
            checkBox.onClick((CheckBoxCell) view);
            if (checkBox.getBindConfig() == invSendReadMessagePackets) {
                AyuState.setAllowReadPacket(false, -1);
            }
            if (checkBox.getBindConfig() == sendOfflinePacketAfterOnlineItem && currentViewingAccount >= 0) {
                if (currentSettings.sendOfflinePacketAfterOnline && currentSettings.isGhostModeActive()) {
                    AyuWorker.setOnline(currentViewingAccount, true);
                } else {
                    AyuWorker.clearOnline(currentViewingAccount);
                }
            }
            updateGhostViews();
        }
    }

    @Override
    protected void onCustomCellClick(View view, int position, float x, float y) {
        AbstractConfigCell row = cellGroup.rows.get(position);
        if (row == markReadAfterSendRow) {
            currentSettings.markReadAfterSend = !currentSettings.markReadAfterSend;
            currentSettings.save();
            ((TextCheckCell) view).setChecked(currentSettings.markReadAfterSend);
            AyuState.setAllowReadPacket(false, -1);
            if (currentSettings.markReadAfterSend && currentSettings.useScheduledMessages) {
                currentSettings.useScheduledMessages = false;
                currentSettings.save();
                notifyRow(useScheduledMessagesRow);
            }
        } else if (row == useScheduledMessagesRow) {
            currentSettings.useScheduledMessages = !currentSettings.useScheduledMessages;
            currentSettings.save();
            AyuState.setAutomaticallyScheduled(false, -1);
            ((TextCheckCell) view).setChecked(currentSettings.useScheduledMessages);
            if (currentSettings.useScheduledMessages && currentSettings.markReadAfterSend) {
                currentSettings.markReadAfterSend = false;
                currentSettings.save();
                notifyRow(markReadAfterSendRow);
            }
        } else if (row == sendWithoutSoundRow) {
            showSendWithoutSoundDialog(view);
        } else if (row == suggestGhostBeforeStoryRow) {
            boolean v = !currentSettings.suggestGhostModeBeforeViewingStory;
            currentSettings.suggestGhostModeBeforeViewingStory = v;
            currentSettings.save();
            ((TextCheckCell) view).setChecked(v);
        }
    }

    @Override
    protected boolean onItemLongClick(View view, int position, float x, float y) {
        AbstractConfigCell row = position >= 0 && position < cellGroup.rows.size() ? cellGroup.rows.get(position) : null;
        ConfigItem lockedItem = getGhostModeLockedItem(row);

        if (lockedItem != null) {
            boolean currentLocked = lockedItem.Bool();
            if (!currentLocked && currentSettings.getLockedCount() >= 4) {
                AndroidUtilities.shakeViewSpring(view, -4);
                return true;
            }
            lockedItem.toggleConfigBool();
            if (row instanceof ConfigCellCheckBox checkBox) {
                checkBox.setEnabled(currentLocked);
            }
            notifyRow(ghostModeToggleRow);
            return true;
        }
        return super.onItemLongClick(view, position, x, y);
    }

    @Override
    public String getTitle() {
        return getString(R.string.GhostMode);
    }

    @Override
    protected String getSettingsPrefix() {
        return "ghostmode";
    }


    private class ListAdapter extends BaseListAdapter {

        public ListAdapter(Context context) {
            super(context);
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            AbstractConfigCell row = position >= 0 && position < cellGroup.rows.size() ? cellGroup.rows.get(position) : null;
            ConfigItem lockedItem = getGhostModeLockedItem(row);
            if (lockedItem != null) {
                return !lockedItem.Bool();
            }
            return super.isEnabled(holder);
        }

        @Override
        protected void onBindCustomViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            AbstractConfigCell row = cellGroup.rows.get(position);
            if (row == ghostModeNoticeRow) {
                bindInfoCell((TextInfoPrivacyCell) holder.itemView, getString(R.string.GhostModeNotice));
            } else if (row == markReadAfterSendNoticeRow) {
                bindInfoCell((TextInfoPrivacyCell) holder.itemView, getString(R.string.MarkReadAfterSendNotice));
            } else if (row == useScheduledMessagesNoticeRow) {
                bindInfoCell((TextInfoPrivacyCell) holder.itemView, getString(R.string.UseScheduledMessagesDescription));
            } else if (row == sendWithoutSoundNoticeRow) {
                bindInfoCell((TextInfoPrivacyCell) holder.itemView, getString(R.string.SendWithoutSoundRowNotice));
            } else if (row == markReadAfterSendRow) {
                TextCheckCell textCheckCell = (TextCheckCell) holder.itemView;
                textCheckCell.setEnabled(true, null);
                textCheckCell.setTextAndCheck(getString(R.string.MarkReadAfterSend), currentSettings.markReadAfterSend, true);
            } else if (row == useScheduledMessagesRow) {
                TextCheckCell textCheckCell = (TextCheckCell) holder.itemView;
                textCheckCell.setEnabled(true, null);
                textCheckCell.setTextAndCheck(getString(R.string.UseScheduledMessages), currentSettings.useScheduledMessages, true);
            } else if (row == sendWithoutSoundRow) {
                TextSettingsCell textSettingsCell = (TextSettingsCell) holder.itemView;
                textSettingsCell.setEnabled(true, null);
                int state = currentSettings.sendWithoutSound;
                String value;
                if (state == AyuGhostConfig.SEND_WITHOUT_SOUND_ALWAYS) {
                    value = getString(R.string.SendWithoutSoundByDefaultAlways);
                } else if (state == AyuGhostConfig.SEND_WITHOUT_SOUND_IN_GHOST_MODE) {
                    value = getString(R.string.SendWithoutSoundByDefaultInGhostMode);
                } else {
                    value = getString(R.string.SendWithoutSoundByDefaultNever);
                }
                textSettingsCell.setTextAndValue(getString(R.string.SilentMessageByDefault), value, true);
            } else if (row == suggestGhostBeforeStoryRow) {
                TextCheckCell textCheckCell = (TextCheckCell) holder.itemView;
                textCheckCell.setEnabled(true, null);
                textCheckCell.setTextAndCheck(getString(R.string.SuggestGhostModeBeforeViewingStory), currentSettings.suggestGhostModeBeforeViewingStory, false);
            }
        }

        @Override
        protected void onBindDefaultViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            AbstractConfigCell row = cellGroup.rows.get(position);
            ConfigItem lockedItem = getGhostModeLockedItem(row);
            if (lockedItem != null && row instanceof ConfigCellCheckBox checkBox) {
                checkBox.setEnabled(!lockedItem.Bool());
            }
        }

        private void bindInfoCell(TextInfoPrivacyCell cell, String text) {
            cell.setBackground(Theme.getThemedDrawable(mContext, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
            cell.setText(text);
        }
    }
}
