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
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.RadioCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.HashSet;
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
 * listed chats. Both lists stay reachable, and each row summarises what it currently holds and
 * opens the picker for that list.
 */
@SuppressLint("RtlHardcoded")
public class AyuSaveExclusionsActivity extends BaseNekoXSettingsActivity {

    private ListAdapter listAdapter;

    private final CellGroup cellGroup = new CellGroup(this);

    private final AbstractConfigCell infoRow = cellGroup.appendCell(new ConfigCellCustom("AyuSaveExclusionsInfo", CellGroup.ITEM_TYPE_TEXT, false));
    private final AbstractConfigCell headerMode = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.AyuSaveExclusionsMode)));
    private final AbstractConfigCell modeAllRow = cellGroup.appendCell(new ConfigCellCustom("AyuSaveModeAll", ConfigCellCustom.CUSTOM_ITEM_AyuSaveModeAll, true));
    private final AbstractConfigCell modeSelectedRow = cellGroup.appendCell(new ConfigCellCustom("AyuSaveModeSelected", ConfigCellCustom.CUSTOM_ITEM_AyuSaveModeSelected, true));

    private final AbstractConfigCell dividerList = cellGroup.appendCell(new ConfigCellDivider());
    private final AbstractConfigCell headerList = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.AyuSaveExclusionsHeader)));
    private final AbstractConfigCell alwaysListRow = cellGroup.appendCell(new ConfigCellCustom("AyuSaveExclusionsAlwaysList", CellGroup.ITEM_TYPE_TEXT_SETTINGS_CELL, true));
    private final AbstractConfigCell neverListRow = cellGroup.appendCell(new ConfigCellCustom("AyuSaveExclusionsNeverList", CellGroup.ITEM_TYPE_TEXT_SETTINGS_CELL, false));

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
        if (position == cellGroup.rows.indexOf(alwaysListRow)) {
            presentFragment(new AyuSaveExclusionsSelectFragment(true, this::notifyChanged));
            return;
        }
        if (position == cellGroup.rows.indexOf(neverListRow)) {
            presentFragment(new AyuSaveExclusionsSelectFragment(false, this::notifyChanged));
            return;
        }
        super.onCustomCellClick(view, position, x, y);
    }

    private void setWhitelist(boolean whitelist) {
        if (AyuSaveExclusions.isWhitelist() == whitelist) {
            return;
        }
        AyuSaveExclusions.setWhitelist(whitelist);
        notifyChanged();
    }

    private void notifyChanged() {
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    /** Short summary of one list: categories first, then the number of picked chats. */
    private CharSequence listValue(boolean always) {
        final int flags = AyuSaveExclusions.getFlags(always);
        final Set<String> ids = AyuSaveExclusions.getIds(always);
        final List<String> categories = new ArrayList<>();
        if ((flags & AyuSaveExclusions.FLAG_USER) != 0) {
            categories.add(getString(R.string.AyuSaveExclusionsUsers));
        }
        if ((flags & AyuSaveExclusions.FLAG_BOT_USER) != 0) {
            categories.add(getString(R.string.AyuSaveExclusionsBotUsers));
        }
        if ((flags & AyuSaveExclusions.FLAG_BOT_CHAT) != 0) {
            categories.add(getString(R.string.AyuSaveExclusionsBotsInGroups));
        }
        if ((flags & AyuSaveExclusions.FLAG_PRIVATE_GROUP) != 0) {
            categories.add(getString(R.string.AyuSaveExclusionsPrivateGroups));
        }
        if ((flags & AyuSaveExclusions.FLAG_PUBLIC_GROUP) != 0) {
            categories.add(getString(R.string.AyuSaveExclusionsPublicGroups));
        }
        if ((flags & AyuSaveExclusions.FLAG_PRIVATE_CHANNEL) != 0) {
            categories.add(getString(R.string.AyuSaveExclusionsPrivateChannels));
        }
        if ((flags & AyuSaveExclusions.FLAG_PUBLIC_CHANNEL) != 0) {
            categories.add(getString(R.string.AyuSaveExclusionsPublicChannels));
        }
        if ((flags & AyuSaveExclusions.FLAG_SECRET) != 0) {
            categories.add(getString(R.string.AyuSaveExclusionsSecret));
        }
        if (categories.isEmpty() && ids.isEmpty()) {
            return getString(R.string.AyuSaveExclusionsAddChats);
        }
        if (categories.isEmpty()) {
            return LocaleController.formatPluralString("Chats", chatCount(ids));
        }
        if (categories.size() == 1 && ids.isEmpty()) {
            return categories.get(0);
        }
        return categories.get(0) + " +" + (categories.size() - 1 + chatCount(ids));
    }

    /** 同一个会话可能同时以正负两种 id 存在，统计时按绝对值去重。 */
    private static int chatCount(Set<String> ids) {
        Set<Long> distinct = new HashSet<>();
        for (String id : ids) {
            try {
                distinct.add(Math.abs(Long.parseLong(id)));
            } catch (NumberFormatException ignored) {
            }
        }
        return distinct.size();
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
            } else if (row == alwaysListRow) {
                TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                cell.setTextAndValue(getString(R.string.AyuSaveExclusionsAlwaysList), listValue(true), true);
                cell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourceProvider));
            } else if (row == neverListRow) {
                TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                cell.setTextAndValue(getString(R.string.AyuSaveExclusionsNeverList), listValue(false), cellGroup.needSetDivider(row));
                cell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourceProvider));
            }
        }
    }
}
