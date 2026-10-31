package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.radolyn.ayugram.AyuSaveExclusions;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.RadioCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import tw.nekomimi.nekogram.config.CellGroup;
import tw.nekomimi.nekogram.config.cell.AbstractConfigCell;
import tw.nekomimi.nekogram.config.cell.ConfigCellCustom;
import tw.nekomimi.nekogram.config.cell.ConfigCellDivider;
import tw.nekomimi.nekogram.config.cell.ConfigCellHeader;

/**
 * Rules that decide where deleted messages and edit history are kept.
 *
 * <p>Two radio rows pick the mode: save everywhere except the listed chats, or save only in the
 * listed chats. The list row summarises the current selection and opens a picker where whole chat
 * categories and individual chats are chosen.
 */
@SuppressLint("RtlHardcoded")
public class AyuSaveExclusionsActivity extends BaseNekoXSettingsActivity {

    private ListAdapter listAdapter;

    private final CellGroup cellGroup = new CellGroup(this);

    private final AbstractConfigCell infoRow = cellGroup.appendCell(new ConfigCellCustom("AyuSaveExclusionsInfo", CellGroup.ITEM_TYPE_TEXT, false));
    private final AbstractConfigCell modeAllRow = cellGroup.appendCell(new ConfigCellCustom("AyuSaveModeAll", ConfigCellCustom.CUSTOM_ITEM_AyuSaveModeAll, true));
    private final AbstractConfigCell modeSelectedRow = cellGroup.appendCell(new ConfigCellCustom("AyuSaveModeSelected", ConfigCellCustom.CUSTOM_ITEM_AyuSaveModeSelected, true));

    private final AbstractConfigCell dividerList = cellGroup.appendCell(new ConfigCellDivider());
    private final AbstractConfigCell headerList = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.AyuSaveExclusionsHeader)));
    private final AbstractConfigCell listRow = cellGroup.appendCell(new ConfigCellCustom("AyuSaveExclusionsList", CellGroup.ITEM_TYPE_TEXT_SETTINGS_CELL, true));
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
        if (position == cellGroup.rows.indexOf(modeAllRow)) {
            setWhitelist(false);
            return;
        }
        if (position == cellGroup.rows.indexOf(modeSelectedRow)) {
            setWhitelist(true);
            return;
        }
        if (position == cellGroup.rows.indexOf(listRow)) {
            new AyuSaveExclusionsSheet(getParentActivity(), editingAlwaysList(), this::notifyChanged).show();
            return;
        }
        if (position == cellGroup.rows.indexOf(resetRow)) {
            showResetDialog();
            return;
        }
        super.onCustomCellClick(view, position, x, y);
    }

    /** The picker edits the included list in "only selected" mode, otherwise the excluded one. */
    private boolean editingAlwaysList() {
        return AyuSaveExclusions.isWhitelist();
    }

    private void setWhitelist(boolean whitelist) {
        if (AyuSaveExclusions.isWhitelist() == whitelist) {
            return;
        }
        AyuSaveExclusions.setWhitelist(whitelist);
        notifyChanged();
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

    /** Short summary of the active list: categories first, then the number of picked chats. */
    private CharSequence listValue() {
        final boolean always = editingAlwaysList();
        final int flags = AyuSaveExclusions.getFlags(always);
        final Set<String> ids = AyuSaveExclusions.getIds(always);
        final List<String> categories = new ArrayList<>();
        if ((flags & AyuSaveExclusions.FLAG_BOT_CHAT) != 0) {
            categories.add(getString(R.string.AyuSaveExclusionsBotChats));
        }
        if ((flags & AyuSaveExclusions.FLAG_BOT_USER) != 0) {
            categories.add(getString(R.string.AyuSaveExclusionsBotUsers));
        }
        if ((flags & AyuSaveExclusions.FLAG_USER) != 0) {
            categories.add(getString(R.string.AyuSaveExclusionsUsers));
        }
        if ((flags & AyuSaveExclusions.FLAG_GROUPS) != 0) {
            categories.add(getString(R.string.AyuSaveExclusionsGroups));
        }
        if ((flags & AyuSaveExclusions.FLAG_CHANNELS) != 0) {
            categories.add(getString(R.string.AyuSaveExclusionsChannels));
        }
        if ((flags & AyuSaveExclusions.FLAG_SECRET) != 0) {
            categories.add(getString(R.string.AyuSaveExclusionsSecret));
        }
        if (categories.isEmpty() && ids.isEmpty()) {
            return getString(R.string.AyuSaveExclusionsAddChats);
        }
        if (categories.isEmpty()) {
            return LocaleController.formatPluralString("Chats", ids.size());
        }
        if (categories.size() == 1 && ids.isEmpty()) {
            return categories.get(0);
        }
        return categories.get(0) + " +" + (categories.size() - 1 + ids.size());
    }

    private class ListAdapter extends BaseListAdapter {

        public ListAdapter(Context context) {
            super(context);
        }

        @Override
        protected View onCreateCustomViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == ConfigCellCustom.CUSTOM_ITEM_AyuSaveModeAll
                    || viewType == ConfigCellCustom.CUSTOM_ITEM_AyuSaveModeSelected) {
                return new RadioCell(mContext, resourceProvider);
            }
            return null;
        }

        @Override
        protected void onBindCustomViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            AbstractConfigCell row = cellGroup.rows.get(position);
            if (row == infoRow) {
                ((TextInfoPrivacyCell) holder.itemView).setText(getString(AyuSaveExclusions.isWhitelist()
                        ? R.string.AyuSaveExclusionsInfoNever : R.string.AyuSaveExclusionsInfoAlways));
            } else if (row == modeAllRow) {
                ((RadioCell) holder.itemView).setText(getString(R.string.AyuSaveExclusionsModeAlways),
                        !AyuSaveExclusions.isWhitelist(), true);
            } else if (row == modeSelectedRow) {
                ((RadioCell) holder.itemView).setText(getString(R.string.AyuSaveExclusionsModeNever),
                        AyuSaveExclusions.isWhitelist(), true);
            } else if (row == listRow) {
                ((TextSettingsCell) holder.itemView).setTextAndValue(
                        getString(AyuSaveExclusions.isWhitelist()
                                ? R.string.AyuSaveExclusionsAlwaysList : R.string.AyuSaveExclusionsNeverList),
                        listValue(), true);
            } else if (row == resetRow) {
                TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                cell.setText(getString(R.string.AyuSaveExclusionsReset), false);
                cell.setTextColor(Theme.getColor(Theme.key_text_RedRegular, resourceProvider));
            }
        }
    }
}
