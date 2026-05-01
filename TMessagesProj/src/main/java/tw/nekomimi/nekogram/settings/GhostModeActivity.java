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

import java.util.Locale;

import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.config.CellGroup;
import tw.nekomimi.nekogram.config.ConfigItem;
import tw.nekomimi.nekogram.config.cell.AbstractConfigCell;
import tw.nekomimi.nekogram.config.cell.ConfigCellCustom;
import tw.nekomimi.nekogram.config.cell.ConfigCellHeader;
import xyz.nextalone.nagram.NaConfig;

public class GhostModeActivity extends BaseNekoXSettingsActivity {

    private ListAdapter listAdapter;
    private final CellGroup cellGroup = new CellGroup(this);

    private final AbstractConfigCell ghostEssentialsHeaderRow = new ConfigCellHeader(getString(R.string.GhostEssentialsHeader));
    private final AbstractConfigCell ghostModeToggleRow = new ConfigCellCustom("GhostMode", CellGroup.ITEM_TYPE_CHECK2, true);
    private final AbstractConfigCell sendReadMessagePacketsRow = new ConfigCellCustom("DontSendReadMessagePackets", CellGroup.ITEM_TYPE_CHECK_BOX, true);
    private final AbstractConfigCell sendReadStoriesPacketsRow = new ConfigCellCustom("DontReadStoriesPackets", CellGroup.ITEM_TYPE_CHECK_BOX, true);
    private final AbstractConfigCell sendOnlinePacketsRow = new ConfigCellCustom("DontSendOnlinePackets", CellGroup.ITEM_TYPE_CHECK_BOX, true);
    private final AbstractConfigCell sendUploadProgressRow = new ConfigCellCustom("DontSendUploadProgress", CellGroup.ITEM_TYPE_CHECK_BOX, true);
    private final AbstractConfigCell sendOfflinePacketAfterOnlineRow = new ConfigCellCustom("SendOfflinePacketAfterOnline", CellGroup.ITEM_TYPE_CHECK_BOX, true);
    private final AbstractConfigCell ghostModeNoticeRow = new ConfigCellCustom("GhostModeNotice", CellGroup.ITEM_TYPE_TEXT, false);
    private final AbstractConfigCell markReadAfterSendRow = new ConfigCellCustom("MarkReadAfterSend", CellGroup.ITEM_TYPE_TEXT_CHECK, true);
    private final AbstractConfigCell markReadAfterSendNoticeRow = new ConfigCellCustom("MarkReadAfterSendNotice", CellGroup.ITEM_TYPE_TEXT, false);
    private final AbstractConfigCell useScheduledMessagesRow = new ConfigCellCustom("UseScheduledMessages", CellGroup.ITEM_TYPE_TEXT_CHECK, true);
    private final AbstractConfigCell useScheduledMessagesNoticeRow = new ConfigCellCustom("UseScheduledMessagesDescription", CellGroup.ITEM_TYPE_TEXT, false);
    private final AbstractConfigCell sendWithoutSoundRow = new ConfigCellCustom("SilentMessageByDefault", CellGroup.ITEM_TYPE_TEXT_CHECK, true);
    private final AbstractConfigCell sendWithoutSoundNoticeRow = new ConfigCellCustom("SendWithoutSoundRowNotice", CellGroup.ITEM_TYPE_TEXT, false);
    private final AbstractConfigCell showGhostInDrawerRow = new ConfigCellCustom("GhostModeInDrawer", CellGroup.ITEM_TYPE_TEXT_CHECK, true);
    private final AbstractConfigCell showGhostModeStatusRow = new ConfigCellCustom("GhostModeStatusIndicator", CellGroup.ITEM_TYPE_TEXT_CHECK, true);
    private boolean ghostModeMenuExpanded;

    public GhostModeActivity() {
        rebuildRows();
    }

    private void addCell(AbstractConfigCell cell) {
        cell.bindCellGroup(cellGroup);
        cellGroup.rows.add(cell);
    }

    private void rebuildRows() {
        cellGroup.rows.clear();
        addCell(ghostEssentialsHeaderRow);
        addCell(ghostModeToggleRow);
        if (ghostModeMenuExpanded) {
            addCell(sendReadMessagePacketsRow);
            addCell(sendReadStoriesPacketsRow);
            addCell(sendOnlinePacketsRow);
            addCell(sendUploadProgressRow);
            addCell(sendOfflinePacketAfterOnlineRow);
            addCell(ghostModeNoticeRow);
        }
        addCell(markReadAfterSendRow);
        addCell(markReadAfterSendNoticeRow);
        addCell(useScheduledMessagesRow);
        addCell(useScheduledMessagesNoticeRow);
        addCell(sendWithoutSoundRow);
        addCell(sendWithoutSoundNoticeRow);
        addCell(showGhostInDrawerRow);
        addCell(showGhostModeStatusRow);
        addRowsToMap(cellGroup);
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

    private void insertCell(int index, AbstractConfigCell cell) {
        cell.bindCellGroup(cellGroup);
        cellGroup.rows.add(index, cell);
    }

    private void setGhostModeMenuExpanded(boolean expanded) {
        if (ghostModeMenuExpanded == expanded) {
            return;
        }
        int toggleIndex = rowIndex(ghostModeToggleRow);
        ghostModeMenuExpanded = expanded;
        if (listAdapter == null || toggleIndex < 0) {
            rebuildRows();
            return;
        }
        if (expanded) {
            int insertIndex = toggleIndex + 1;
            insertCell(insertIndex++, sendReadMessagePacketsRow);
            insertCell(insertIndex++, sendReadStoriesPacketsRow);
            insertCell(insertIndex++, sendOnlinePacketsRow);
            insertCell(insertIndex++, sendUploadProgressRow);
            insertCell(insertIndex++, sendOfflinePacketAfterOnlineRow);
            insertCell(insertIndex, ghostModeNoticeRow);
            addRowsToMap(cellGroup);
            listAdapter.notifyItemChanged(toggleIndex);
            listAdapter.notifyItemRangeInserted(toggleIndex + 1, 6);
        } else {
            int removeIndex = toggleIndex + 1;
            cellGroup.rows.remove(sendReadMessagePacketsRow);
            cellGroup.rows.remove(sendReadStoriesPacketsRow);
            cellGroup.rows.remove(sendOnlinePacketsRow);
            cellGroup.rows.remove(sendUploadProgressRow);
            cellGroup.rows.remove(sendOfflinePacketAfterOnlineRow);
            cellGroup.rows.remove(ghostModeNoticeRow);
            addRowsToMap(cellGroup);
            listAdapter.notifyItemChanged(toggleIndex);
            listAdapter.notifyItemRangeRemoved(removeIndex, 6);
        }
    }

    private void updateGhostViews() {
        notifyRow(ghostModeToggleRow);
        notifyRow(sendReadMessagePacketsRow);
        notifyRow(sendOnlinePacketsRow);
        notifyRow(sendUploadProgressRow);
        notifyRow(sendReadStoriesPacketsRow);
        notifyRow(sendOfflinePacketAfterOnlineRow);

        NotificationCenter.getInstance(UserConfig.selectedAccount).postNotificationName(NotificationCenter.mainUserInfoChanged);
    }

    private ConfigItem getGhostModeLockedItem(AbstractConfigCell row) {
        if (row == sendReadMessagePacketsRow) {
            return NekoConfig.sendReadMessagePacketsLocked;
        } else if (row == sendReadStoriesPacketsRow) {
            return NekoConfig.sendReadStoriesPacketsLocked;
        } else if (row == sendOnlinePacketsRow) {
            return NekoConfig.sendOnlinePacketsLocked;
        } else if (row == sendUploadProgressRow) {
            return NekoConfig.sendUploadProgressLocked;
        } else if (row == sendOfflinePacketAfterOnlineRow) {
            return NekoConfig.sendOfflinePacketAfterOnlineLocked;
        }
        return null;
    }


    @Override
    protected void onCustomCellClick(View view, int position, float x, float y) {
        AbstractConfigCell row = cellGroup.rows.get(position);
        if (row == ghostModeToggleRow) {
            setGhostModeMenuExpanded(!ghostModeMenuExpanded);
        } else if (row == sendReadMessagePacketsRow) {
            if (!view.isEnabled()) return;
            NekoConfig.sendReadMessagePackets.toggleConfigBool();
            ((CheckBoxCell) view).setChecked(NekoConfig.sendReadMessagePackets.Bool(), true);
            AyuState.setAllowReadPacket(false, -1);
            updateGhostViews();
        } else if (row == sendReadStoriesPacketsRow) {
            if (!view.isEnabled()) return;
            NekoConfig.sendReadStoriesPackets.toggleConfigBool();
            ((CheckBoxCell) view).setChecked(NekoConfig.sendReadStoriesPackets.Bool(), true);
            updateGhostViews();
        } else if (row == sendOnlinePacketsRow) {
            if (!view.isEnabled()) return;
            NekoConfig.sendOnlinePackets.toggleConfigBool();
            ((CheckBoxCell) view).setChecked(NekoConfig.sendOnlinePackets.Bool(), true);
            updateGhostViews();
        } else if (row == sendUploadProgressRow) {
            if (!view.isEnabled()) return;
            NekoConfig.sendUploadProgress.toggleConfigBool();
            ((CheckBoxCell) view).setChecked(NekoConfig.sendUploadProgress.Bool(), true);
            updateGhostViews();
        } else if (row == sendOfflinePacketAfterOnlineRow) {
            if (!view.isEnabled()) return;
            NekoConfig.sendOfflinePacketAfterOnline.toggleConfigBool();
            ((CheckBoxCell) view).setChecked(NekoConfig.sendOfflinePacketAfterOnline.Bool(), true);
            updateGhostViews();
        } else if (row == markReadAfterSendRow) {
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
            view.setEnabled(currentLocked);
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

    private int getGhostModeSelectedCount() {
        int count = 0;
        if (!NekoConfig.sendReadMessagePackets.Bool()) count++;
        if (!NekoConfig.sendReadStoriesPackets.Bool()) count++;
        if (!NekoConfig.sendOnlinePackets.Bool()) count++;
        if (!NekoConfig.sendUploadProgress.Bool()) count++;
        if (NekoConfig.sendOfflinePacketAfterOnline.Bool()) count++;
        return count;
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
            if (row == ghostModeToggleRow) {
                TextCheckCell2 checkCell = (TextCheckCell2) holder.itemView;
                int selectedCount = getGhostModeSelectedCount();
                boolean isActive = NekoConfig.isGhostModeActive();
                checkCell.setTextAndCheck(getString(R.string.GhostMode), isActive, true, true);
                checkCell.setCollapseArrow(String.format(Locale.US, "%d/5", selectedCount), !ghostModeMenuExpanded, () -> {
                    NekoConfig.toggleGhostMode();
                    String msg = isActive
                            ? getString(R.string.GhostModeDisabled)
                            : getString(R.string.GhostModeEnabled);
                    BulletinFactory.of(getLastFragment()).createSuccessBulletin(msg).show();
                    updateGhostViews();
                });
                checkCell.getCheckBox().setColors(Theme.key_switchTrack, Theme.key_switchTrackChecked, Theme.key_windowBackgroundWhite, Theme.key_windowBackgroundWhite);
                checkCell.getCheckBox().setDrawIconType(0);
            } else if (row == sendReadMessagePacketsRow) {
                bindPacketCell((CheckBoxCell) holder.itemView, NekoConfig.sendReadMessagePacketsLocked, !NekoConfig.sendReadMessagePackets.Bool(), getString(R.string.DontSendReadMessagePackets));
            } else if (row == sendReadStoriesPacketsRow) {
                bindPacketCell((CheckBoxCell) holder.itemView, NekoConfig.sendReadStoriesPacketsLocked, !NekoConfig.sendReadStoriesPackets.Bool(), getString(R.string.DontReadStoriesPackets));
            } else if (row == sendOnlinePacketsRow) {
                bindPacketCell((CheckBoxCell) holder.itemView, NekoConfig.sendOnlinePacketsLocked, !NekoConfig.sendOnlinePackets.Bool(), getString(R.string.DontSendOnlinePackets));
            } else if (row == sendUploadProgressRow) {
                bindPacketCell((CheckBoxCell) holder.itemView, NekoConfig.sendUploadProgressLocked, !NekoConfig.sendUploadProgress.Bool(), getString(R.string.DontSendUploadProgress));
            } else if (row == sendOfflinePacketAfterOnlineRow) {
                bindPacketCell((CheckBoxCell) holder.itemView, NekoConfig.sendOfflinePacketAfterOnlineLocked, NekoConfig.sendOfflinePacketAfterOnline.Bool(), getString(R.string.SendOfflinePacketAfterOnline));
            } else if (row == ghostModeNoticeRow) {
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

        private void bindPacketCell(CheckBoxCell checkBoxCell, ConfigItem lockedItem, boolean checkValue, String title) {
            boolean isLocked = lockedItem.Bool();
            checkBoxCell.setText(title, "", checkValue, true, true);
            checkBoxCell.setEnabled(!isLocked);
            checkBoxCell.setPad(1);
        }

        private void bindInfoCell(TextInfoPrivacyCell cell, String text) {
            cell.setBackground(Theme.getThemedDrawable(mContext, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
            cell.setText(text);
        }
    }
}
