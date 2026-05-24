package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.LocaleController.getString;
import static org.telegram.ui.LaunchActivity.getLastFragment;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.radolyn.ayugram.utils.AyuState;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextCheckCell2;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import tw.nekomimi.nekogram.NekoConfig;
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

    private final AbstractConfigCell ghostEssentialsHeaderRow = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.GhostEssentialsHeader)));

    private final ConfigItem invSendReadMessagePackets = inverted(NekoConfig.sendReadMessagePackets);
    private final ConfigItem invSendReadStoriesPackets = inverted(NekoConfig.sendReadStoriesPackets);
    private final ConfigItem invSendOnlinePackets = inverted(NekoConfig.sendOnlinePackets);
    private final ConfigItem invSendUploadProgress = inverted(NekoConfig.sendUploadProgress);

    private final AbstractConfigCell ghostModeNoticeRow = new ConfigCellCustom("GhostModeNotice", CellGroup.ITEM_TYPE_TEXT, false);

    private final AbstractConfigCell ghostModeToggleRow = cellGroup.appendCell(
            new ConfigCellTextCheck2("GhostMode", getString(R.string.GhostMode), new ArrayList<>() {{
                add(new ConfigCellCheckBox(invSendReadMessagePackets, "DontSendReadMessagePackets", getString(R.string.DontSendReadMessagePackets), 0, true));
                add(new ConfigCellCheckBox(invSendReadStoriesPackets, "DontReadStoriesPackets", getString(R.string.DontReadStoriesPackets), 0, true));
                add(new ConfigCellCheckBox(invSendOnlinePackets, "DontSendOnlinePackets", getString(R.string.DontSendOnlinePackets), 0, true));
                add(new ConfigCellCheckBox(invSendUploadProgress, "DontSendUploadProgress", getString(R.string.DontSendUploadProgress), 0, true));
                add(new ConfigCellCheckBox(NekoConfig.sendOfflinePacketAfterOnline, "SendOfflinePacketAfterOnline", getString(R.string.SendOfflinePacketAfterOnline), 0, false));
            }}, null) {
                @Override
                public void onBindViewHolder(RecyclerView.ViewHolder holder) {
                    TextCheckCell2 checkCell = (TextCheckCell2) holder.itemView;
                    this.cell = checkCell;
                    cell.setEnabled(isEnabled());
                    cell.setTextAndCheck(getTitle(), NekoConfig.isGhostModeActive(), cellGroup.needSetDivider(this), true);
                    cell.setCollapseArrow(String.format(Locale.US, "%d/%d", getSelectedCount(), getVisibleCheckBox().size()), isCollapsed(), this::onCheckClick);
                    cell.getCheckBox().setColors(Theme.key_switchTrack, Theme.key_switchTrackChecked, Theme.key_windowBackgroundWhite, Theme.key_windowBackgroundWhite);
                    cell.getCheckBox().setDrawIconType(0);
                }

                @Override
                public void onCheckClick() {
                    NekoConfig.toggleGhostMode();
                    boolean isActive = NekoConfig.isGhostModeActive();
                    String msg = isActive
                            ? getString(R.string.GhostModeEnabled)
                            : getString(R.string.GhostModeDisabled);
                    BulletinFactory.of(getLastFragment()).createSuccessBulletin(msg).show();
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
    private final AbstractConfigCell sendWithoutSoundRow = cellGroup.appendCell(new ConfigCellCustom("SilentMessageByDefault", CellGroup.ITEM_TYPE_TEXT_CHECK, true));
    private final AbstractConfigCell sendWithoutSoundNoticeRow = cellGroup.appendCell(new ConfigCellCustom("SendWithoutSoundRowNotice", CellGroup.ITEM_TYPE_TEXT, false));
    private final AbstractConfigCell showGhostInDrawerRow = cellGroup.appendCell(new ConfigCellCustom("GhostModeInDrawer", CellGroup.ITEM_TYPE_TEXT_CHECK, true));
    private final AbstractConfigCell showGhostModeStatusRow = cellGroup.appendCell(new ConfigCellCustom("GhostModeStatusIndicator", CellGroup.ITEM_TYPE_TEXT_CHECK, true));

    public GhostModeActivity() {
        addRowsToMap(cellGroup);
    }

    private ConfigItem inverted(ConfigItem original) {
        return new ConfigItem("inv_" + original.key, ConfigItem.configTypeBool, !(boolean) original.defaultValue) {
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
        return true;
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
        return view;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
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
        NotificationCenter.getInstance(UserConfig.selectedAccount).postNotificationName(NotificationCenter.mainUserInfoChanged);
    }

    private ConfigItem getGhostModeLockedItem(AbstractConfigCell row) {
        if (!(row instanceof ConfigCellCheckBox checkBox)) return null;
        ConfigItem bindConfig = checkBox.getBindConfig();
        if (bindConfig == invSendReadMessagePackets) return NekoConfig.sendReadMessagePacketsLocked;
        if (bindConfig == invSendReadStoriesPackets) return NekoConfig.sendReadStoriesPacketsLocked;
        if (bindConfig == invSendOnlinePackets) return NekoConfig.sendOnlinePacketsLocked;
        if (bindConfig == invSendUploadProgress) return NekoConfig.sendUploadProgressLocked;
        if (bindConfig == NekoConfig.sendOfflinePacketAfterOnline) return NekoConfig.sendOfflinePacketAfterOnlineLocked;
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
            updateGhostViews();
        }
    }

    @Override
    protected void onCustomCellClick(View view, int position, float x, float y) {
        AbstractConfigCell row = cellGroup.rows.get(position);
        if (row == markReadAfterSendRow) {
            NekoConfig.markReadAfterSend.toggleConfigBool();
            ((TextCheckCell) view).setChecked(NekoConfig.markReadAfterSend.Bool());
            AyuState.setAllowReadPacket(false, -1);
        } else if (row == useScheduledMessagesRow) {
            NekoConfig.useScheduledMessages.toggleConfigBool();
            ((TextCheckCell) view).setChecked(NekoConfig.useScheduledMessages.Bool());
        } else if (row == sendWithoutSoundRow) {
            NaConfig.INSTANCE.getSilentMessageByDefault().toggleConfigBool();
            ((TextCheckCell) view).setChecked(NaConfig.INSTANCE.getSilentMessageByDefault().Bool());
        } else if (row == showGhostInDrawerRow) {
            NekoConfig.showGhostInDrawer.toggleConfigBool();
            ((TextCheckCell) view).setChecked(NekoConfig.showGhostInDrawer.Bool());
            NotificationCenter.getInstance(UserConfig.selectedAccount).postNotificationName(NotificationCenter.mainUserInfoChanged);
        } else if (row == showGhostModeStatusRow) {
            NekoConfig.showGhostModeStatus.toggleConfigBool();
            ((TextCheckCell) view).setChecked(NekoConfig.showGhostModeStatus.Bool());
            NotificationCenter.getInstance(UserConfig.selectedAccount).postNotificationName(NotificationCenter.mainUserInfoChanged);
        }
    }

    @Override
    protected boolean onItemLongClick(View view, int position, float x, float y) {
        AbstractConfigCell row = position >= 0 && position < cellGroup.rows.size() ? cellGroup.rows.get(position) : null;
        ConfigItem lockedItem = getGhostModeLockedItem(row);

        if (lockedItem != null) {
            boolean currentLocked = lockedItem.Bool();
            if (!currentLocked && getGhostModeLockedCount() >= 4) {
                AndroidUtilities.shakeViewSpring(view, -4);
                return true;
            }
            lockedItem.setConfigBool(!currentLocked);
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

    private int getGhostModeLockedCount() {
        int count = 0;
        if (NekoConfig.sendReadMessagePacketsLocked.Bool()) count++;
        if (NekoConfig.sendReadStoriesPacketsLocked.Bool()) count++;
        if (NekoConfig.sendOnlinePacketsLocked.Bool()) count++;
        if (NekoConfig.sendUploadProgressLocked.Bool()) count++;
        if (NekoConfig.sendOfflinePacketAfterOnlineLocked.Bool()) count++;
        return count;
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
                textCheckCell.setTextAndCheck(getString(R.string.MarkReadAfterSend), NekoConfig.markReadAfterSend.Bool(), true);
            } else if (row == useScheduledMessagesRow) {
                TextCheckCell textCheckCell = (TextCheckCell) holder.itemView;
                textCheckCell.setEnabled(true, null);
                textCheckCell.setTextAndCheck(getString(R.string.UseScheduledMessages), NekoConfig.useScheduledMessages.Bool(), true);
            } else if (row == sendWithoutSoundRow) {
                TextCheckCell textCheckCell = (TextCheckCell) holder.itemView;
                textCheckCell.setEnabled(true, null);
                textCheckCell.setTextAndCheck(getString(R.string.SilentMessageByDefault), NaConfig.INSTANCE.getSilentMessageByDefault().Bool(), true);
            } else if (row == showGhostInDrawerRow) {
                TextCheckCell textCheckCell = (TextCheckCell) holder.itemView;
                textCheckCell.setEnabled(true, null);
                textCheckCell.setTextAndCheck(getString(R.string.GhostModeInDrawer), NekoConfig.showGhostInDrawer.Bool(), true);
            } else if (row == showGhostModeStatusRow) {
                TextCheckCell textCheckCell = (TextCheckCell) holder.itemView;
                textCheckCell.setEnabled(true, null);
                textCheckCell.setTextAndCheck(getString(R.string.GhostModeStatusIndicator), NekoConfig.showGhostModeStatus.Bool(), false);
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
