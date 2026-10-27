package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.radolyn.ayugram.AyuSaveExclusions;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.RecyclerListView;

import tw.nekomimi.nekogram.config.CellGroup;
import tw.nekomimi.nekogram.config.cell.AbstractConfigCell;
import tw.nekomimi.nekogram.config.cell.ConfigCellCustom;
import tw.nekomimi.nekogram.config.cell.ConfigCellDivider;
import tw.nekomimi.nekogram.config.cell.ConfigCellHeader;

/**
 * Rules that decide where deleted messages and edit history are kept.
 *
 * <p>The page switches between "save everywhere except the excluded chats" and "save only in the
 * included chats", exposes the chat categories that are saved or skipped by default, and shows how
 * many chats carry an explicit rule. Individual chats are marked from the chat's menu.
 */
@SuppressLint("RtlHardcoded")
public class AyuSaveExclusionsActivity extends BaseNekoXSettingsActivity {

    private ListAdapter listAdapter;

    private final CellGroup cellGroup = new CellGroup(this);

    private final AbstractConfigCell headerRow = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.AyuSaveExclusions)));
    private final AbstractConfigCell infoRow = cellGroup.appendCell(new ConfigCellCustom("AyuSaveExclusionsInfo", CellGroup.ITEM_TYPE_TEXT, false));
    private final AbstractConfigCell modeRow = cellGroup.appendCell(new ConfigCellCustom("AyuSaveExclusionsMode", CellGroup.ITEM_TYPE_TEXT_SETTINGS_CELL, true));

    private final AbstractConfigCell dividerCategories = cellGroup.appendCell(new ConfigCellDivider());
    private final AbstractConfigCell headerCategories = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.AyuSaveExclusionsCategories)));
    private final AbstractConfigCell botChatsRow = cellGroup.appendCell(new ConfigCellCustom("AyuSaveBotChats", CellGroup.ITEM_TYPE_TEXT_CHECK, true));
    private final AbstractConfigCell botUsersRow = cellGroup.appendCell(new ConfigCellCustom("AyuSaveBotUsers", CellGroup.ITEM_TYPE_TEXT_CHECK, true));
    private final AbstractConfigCell usersRow = cellGroup.appendCell(new ConfigCellCustom("AyuSaveUsers", CellGroup.ITEM_TYPE_TEXT_CHECK, true));
    private final AbstractConfigCell groupsRow = cellGroup.appendCell(new ConfigCellCustom("AyuSaveGroups", CellGroup.ITEM_TYPE_TEXT_CHECK, true));
    private final AbstractConfigCell channelsRow = cellGroup.appendCell(new ConfigCellCustom("AyuSaveChannels", CellGroup.ITEM_TYPE_TEXT_CHECK, true));
    private final AbstractConfigCell secretRow = cellGroup.appendCell(new ConfigCellCustom("AyuSaveSecret", CellGroup.ITEM_TYPE_TEXT_CHECK, true));

    private final AbstractConfigCell dividerList = cellGroup.appendCell(new ConfigCellDivider());
    private final AbstractConfigCell countRow = cellGroup.appendCell(new ConfigCellCustom("AyuSaveExclusionsCount", CellGroup.ITEM_TYPE_TEXT_SETTINGS_CELL, true));
    private final AbstractConfigCell resetRow = cellGroup.appendCell(new ConfigCellCustom("AyuSaveExclusionsReset", CellGroup.ITEM_TYPE_TEXT_SETTINGS_CELL, false));

    public AyuSaveExclusionsActivity() {
        addRowsToMap(cellGroup);
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
    protected String getSettingsPrefix() {
        return "ayusaveexclusions";
    }

    @Override
    public View createView(Context context) {
        View superView = super.createView(context);
        listAdapter = new ListAdapter(context);
        listView.setAdapter(listAdapter);
        setupDefaultListeners();
        return superView;
    }

    @Override
    public int getBaseGuid() {
        return 15200;
    }

    @Override
    public int getDrawable() {
        return R.drawable.msg_discuss;
    }

    @Override
    public String getTitle() {
        return getString(R.string.AyuSaveExclusions);
    }

    @Override
    protected void onCustomCellClick(View view, int position, float x, float y) {
        if (position == cellGroup.rows.indexOf(modeRow)) {
            showModeDialog();
            return;
        }
        if (position == cellGroup.rows.indexOf(botChatsRow)) {
            toggleFlag(AyuSaveExclusions.FLAG_BOT_CHAT);
            return;
        }
        if (position == cellGroup.rows.indexOf(botUsersRow)) {
            toggleFlag(AyuSaveExclusions.FLAG_BOT_USER);
            return;
        }
        if (position == cellGroup.rows.indexOf(usersRow)) {
            toggleFlag(AyuSaveExclusions.FLAG_USER);
            return;
        }
        if (position == cellGroup.rows.indexOf(groupsRow)) {
            toggleFlag(AyuSaveExclusions.FLAG_GROUPS);
            return;
        }
        if (position == cellGroup.rows.indexOf(channelsRow)) {
            toggleFlag(AyuSaveExclusions.FLAG_CHANNELS);
            return;
        }
        if (position == cellGroup.rows.indexOf(secretRow)) {
            toggleFlag(AyuSaveExclusions.FLAG_SECRET);
            return;
        }
        if (position == cellGroup.rows.indexOf(resetRow)) {
            showResetDialog();
            return;
        }
        super.onCustomCellClick(view, position, x, y);
    }

    /** True when the list that the switches edit is the "always save" one. */
    private boolean editingAlwaysList() {
        return AyuSaveExclusions.isWhitelist();
    }

    private void toggleFlag(int flag) {
        boolean always = editingAlwaysList();
        AyuSaveExclusions.setFlag(always, flag, (AyuSaveExclusions.getFlags(always) & flag) == 0);
        notifyChanged();
    }

    private void showModeDialog() {
        String[] items = new String[]{
                getString(R.string.AyuSaveExclusionsModeAlways),
                getString(R.string.AyuSaveExclusionsModeNever)
        };
        showSingleChoiceDialog(getParentActivity(), getString(R.string.AyuSaveExclusions), items,
                AyuSaveExclusions.isWhitelist() ? 1 : 0, getResourceProvider(), which -> {
                    AyuSaveExclusions.setWhitelist(which == 1);
                    notifyChanged();
                });
    }

    private void showResetDialog() {
        boolean always = editingAlwaysList();
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(getString(R.string.AyuSaveExclusionsReset));
        builder.setMessage(getString(R.string.AyuSaveExclusionsResetInfo));
        builder.setPositiveButton(getString(R.string.Reset), (dialog, which) -> {
            AyuSaveExclusions.reset(always);
            notifyChanged();
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void notifyChanged() {
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    private boolean isChecked(int flag) {
        boolean always = editingAlwaysList();
        return (AyuSaveExclusions.getFlags(always) & flag) != 0;
    }

    private String countText() {
        return LocaleController.formatString(R.string.AyuSaveExclusionsCountValue,
                AyuSaveExclusions.getIds(editingAlwaysList()).size());
    }

    private class ListAdapter extends BaseListAdapter {

        public ListAdapter(Context context) {
            super(context);
        }

        @Override
        protected View onCreateCustomViewHolder(@NonNull ViewGroup parent, int viewType) {
            return null;
        }

        @Override
        protected void onBindCustomViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            AbstractConfigCell row = cellGroup.rows.get(position);
            if (row == infoRow) {
                TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                cell.setText(getString(AyuSaveExclusions.isWhitelist()
                        ? R.string.AyuSaveExclusionsInfoNever : R.string.AyuSaveExclusionsInfoAlways));
            } else if (row == modeRow) {
                TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                cell.setTextAndValue(getString(R.string.AyuSaveExclusionsMode),
                        getString(AyuSaveExclusions.isWhitelist()
                                ? R.string.AyuSaveExclusionsModeNever : R.string.AyuSaveExclusionsModeAlways), true);
            } else if (row == botChatsRow) {
                ((TextCheckCell) holder.itemView).setTextAndCheck(getString(R.string.AyuSaveExclusionsBotChats), isChecked(AyuSaveExclusions.FLAG_BOT_CHAT), true);
            } else if (row == botUsersRow) {
                ((TextCheckCell) holder.itemView).setTextAndCheck(getString(R.string.AyuSaveExclusionsBotUsers), isChecked(AyuSaveExclusions.FLAG_BOT_USER), true);
            } else if (row == usersRow) {
                ((TextCheckCell) holder.itemView).setTextAndCheck(getString(R.string.AyuSaveExclusionsUsers), isChecked(AyuSaveExclusions.FLAG_USER), true);
            } else if (row == groupsRow) {
                ((TextCheckCell) holder.itemView).setTextAndCheck(getString(R.string.AyuSaveExclusionsGroups), isChecked(AyuSaveExclusions.FLAG_GROUPS), true);
            } else if (row == channelsRow) {
                ((TextCheckCell) holder.itemView).setTextAndCheck(getString(R.string.AyuSaveExclusionsChannels), isChecked(AyuSaveExclusions.FLAG_CHANNELS), true);
            } else if (row == secretRow) {
                ((TextCheckCell) holder.itemView).setTextAndCheck(getString(R.string.AyuSaveExclusionsSecret), isChecked(AyuSaveExclusions.FLAG_SECRET), true);
            } else if (row == countRow) {
                ((TextSettingsCell) holder.itemView).setTextAndValue(getString(R.string.AyuSaveExclusionsCount), countText(), true);
            } else if (row == resetRow) {
                TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                cell.setText(getString(R.string.AyuSaveExclusionsReset), false);
                cell.setTextColor(Theme.getColor(Theme.key_text_RedRegular, resourceProvider));
            }
        }
    }
}
