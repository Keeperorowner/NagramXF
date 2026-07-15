package com.exteragram.messenger.pillstack.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.exteragram.messenger.pillstack.core.PillRegistry;
import com.exteragram.messenger.pillstack.core.PillStackConfig;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import androidx.recyclerview.widget.DiffUtil;

import tw.nekomimi.nekogram.settings.BaseNekoSettingsActivity;
import tw.nekomimi.nekogram.ui.cells.HeaderCell;

public class PillStackPreferencesActivity extends BaseNekoSettingsActivity {

    private static final int MENU_RESET = 1;

    private int settingsHeaderRow;
    private int infiniteScrollingRow;

    private int settingsShadowRow;

    private int activeHeaderRow;
    private int activeRowStart;
    private int activeRowEnd;

    private int activeShadowRow;

    private int hiddenHeaderRow;
    private int hiddenRowStart;
    private int hiddenRowEnd;

    private final HashMap<Integer, ItemInfo> itemDetails = new HashMap<>();

    private Drawable reorderIcon;
    private ActionBarMenuItem resetItem;
    private ItemTouchHelper itemTouchHelper;

    private static final class ItemInfo {
        final CharSequence name;
        final int iconRes;
        final int iconColorTop;
        final int iconColorBottom;

        ItemInfo(CharSequence name, int iconRes, int iconColorTop, int iconColorBottom) {
            this.name = name;
            this.iconRes = iconRes;
            this.iconColorTop = iconColorTop;
            this.iconColorBottom = iconColorBottom;
        }
    }

    @Override
    protected String getActionBarTitle() {
        return getString(R.string.PillStackPills);
    }

    @Override
    public boolean onFragmentCreate() {
        for (PillRegistry.PillInfo info : PillRegistry.getRegisteredPills()) {
            itemDetails.put(info.id(), new ItemInfo(info.name(), info.iconRes(), info.iconColorTop(), info.iconColorBottom()));
        }
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        View view = super.createView(context);
        reorderIcon = ContextCompat.getDrawable(context, R.drawable.list_reorder);

        resetItem = actionBar.createMenu().addItem(MENU_RESET, R.drawable.msg_reset);
        resetItem.setContentDescription(getString(R.string.Reset));
        resetItem.setOnClickListener(v -> resetToDefault());
        updateResetButtonVisibility();

        itemTouchHelper = new ItemTouchHelper(new ReorderCallback());
        itemTouchHelper.attachToRecyclerView(listView);

        return view;
    }

    @Override
    protected BaseListAdapter createAdapter(Context context) {
        return new ListAdapter(context);
    }

    @Override
    protected void updateRows() {
        rebuildRows();
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    private void rebuildRows() {
        super.updateRows();
        deduplicatePills();

        settingsHeaderRow = addRow();
        infiniteScrollingRow = addRow("pillStackInfiniteScrolling");

        boolean hasActive = !PillStackConfig.activePills.isEmpty();
        boolean hasHidden = !PillStackConfig.hiddenPills.isEmpty();
        settingsShadowRow = (hasActive || hasHidden) ? addRow() : -1;

        activeHeaderRow = -1;
        activeRowStart = -1;
        activeRowEnd = -1;
        if (hasActive) {
            activeHeaderRow = addRow();
            activeRowStart = rowCount;
            for (int i = 0; i < PillStackConfig.activePills.size(); i++) {
                addRow("active_" + PillStackConfig.activePills.get(i));
            }
            activeRowEnd = rowCount - 1;
        }

        activeShadowRow = (hasActive && hasHidden) ? addRow() : -1;

        hiddenHeaderRow = -1;
        hiddenRowStart = -1;
        hiddenRowEnd = -1;
        if (hasHidden) {
            hiddenHeaderRow = addRow();
            hiddenRowStart = rowCount;
            for (int i = 0; i < PillStackConfig.hiddenPills.size(); i++) {
                addRow("hidden_" + PillStackConfig.hiddenPills.get(i));
            }
            hiddenRowEnd = rowCount - 1;
        }
    }

    private void deduplicatePills() {
        HashSet<Integer> seen = new HashSet<>();
        boolean changed = deduplicate(PillStackConfig.hiddenPills, seen) | deduplicate(PillStackConfig.activePills, seen);
        if (changed) {
            PillStackConfig.savePillsLayout();
        }
    }

    private boolean deduplicate(ArrayList<Integer> list, HashSet<Integer> seen) {
        ArrayList<Integer> filtered = new ArrayList<>(list.size());
        boolean removed = false;
        for (Integer id : list) {
            if (seen.add(id)) {
                filtered.add(id);
            } else {
                removed = true;
            }
        }
        if (removed) {
            list.clear();
            list.addAll(filtered);
        }
        return removed;
    }

    @Override
    protected void onItemClick(View view, int position, float x, float y) {
        if (position == infiniteScrollingRow) {
            PillStackConfig.infiniteScrolling = !PillStackConfig.infiniteScrolling;
            PillStackConfig.editor.putBoolean("infiniteScrolling", PillStackConfig.infiniteScrolling).apply();
            ((TextCheckCell) view).setChecked(PillStackConfig.infiniteScrolling);
            return;
        }
        Integer pillId = getPillIdAtRow(position);
        if (pillId == null) return;

        List<String> oldItems = buildItemIdentifiers();

        if (PillStackConfig.activePills.contains(pillId)) {
            PillStackConfig.activePills.remove(pillId);
            if (!PillStackConfig.hiddenPills.contains(pillId)) {
                PillStackConfig.hiddenPills.add(0, pillId);
            }
        } else if (PillStackConfig.hiddenPills.contains(pillId)) {
            PillStackConfig.hiddenPills.remove(pillId);
            PillStackConfig.activePills.add(pillId);
        }

        PillStackConfig.savePillsLayout();
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.pillStackLayoutChanged);

        rebuildRows();

        List<String> newItems = buildItemIdentifiers();
        if (listAdapter != null) {
            DiffUtil.DiffResult result = DiffUtil.calculateDiff(new DiffUtil.Callback() {
                @Override public int getOldListSize() { return oldItems.size(); }
                @Override public int getNewListSize() { return newItems.size(); }
                @Override public boolean areItemsTheSame(int o, int n) { return oldItems.get(o).equals(newItems.get(n)); }
                @Override public boolean areContentsTheSame(int o, int n) { return false; }
            }, true);
            result.dispatchUpdatesTo(listAdapter);
        }
        updateResetButtonVisibility();
    }

    private Integer getPillIdAtRow(int position) {
        if (activeRowStart != -1 && position >= activeRowStart && position <= activeRowEnd) {
            int idx = position - activeRowStart;
            if (idx >= 0 && idx < PillStackConfig.activePills.size()) {
                return PillStackConfig.activePills.get(idx);
            }
        }
        if (hiddenRowStart != -1 && position >= hiddenRowStart && position <= hiddenRowEnd) {
            int idx = position - hiddenRowStart;
            if (idx >= 0 && idx < PillStackConfig.hiddenPills.size()) {
                return PillStackConfig.hiddenPills.get(idx);
            }
        }
        return null;
    }

    private void saveAndRefresh() {
        PillStackConfig.savePillsLayout();
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.pillStackLayoutChanged);
        updateRows();
        updateResetButtonVisibility();
    }

    private List<String> buildItemIdentifiers() {
        List<String> items = new ArrayList<>(rowCount);
        for (int i = 0; i < rowCount; i++) {
            if (i == settingsHeaderRow) items.add("h_settings");
            else if (i == infiniteScrollingRow) items.add("c_infinite");
            else if (i == activeHeaderRow) items.add("h_active");
            else if (i == hiddenHeaderRow) items.add("h_hidden");
            else {
                Integer id = getPillIdAtRow(i);
                items.add(id != null ? "p_" + id : "x_" + i);
            }
        }
        return items;
    }

    private void updateResetButtonVisibility() {
        if (resetItem == null) return;
        boolean isDefault = PillStackConfig.activePills.equals(PillStackConfig.getDefaultActivePills());
        if (!isDefault && resetItem.getVisibility() == View.GONE) {
            AndroidUtilities.updateViewVisibilityAnimated(resetItem, true, 0.5f, true);
        } else if (isDefault && resetItem.getVisibility() == View.VISIBLE) {
            AndroidUtilities.updateViewVisibilityAnimated(resetItem, false, 0.5f, true);
        }
    }

    private void resetToDefault() {
        PillStackConfig.activePills.clear();
        PillStackConfig.activePills.addAll(PillStackConfig.getDefaultActivePills());
        PillStackConfig.hiddenPills.clear();
        for (PillRegistry.PillInfo info : PillRegistry.getRegisteredPills()) {
            if (!PillStackConfig.activePills.contains(info.id())) {
                PillStackConfig.hiddenPills.add(info.id());
            }
        }
        saveAndRefresh();
    }

    private class ReorderCallback extends ItemTouchHelper.Callback {
        @Override
        public boolean isLongPressDragEnabled() {
            return true;
        }

        @Override
        public int getMovementFlags(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
            int position = viewHolder.getAdapterPosition();
            if (isInActiveSection(position) || isInHiddenSection(position)) {
                return makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
            }
            return 0;
        }

        @Override
        public boolean canDropOver(@NonNull RecyclerView recyclerView,
                                   @NonNull RecyclerView.ViewHolder current,
                                   @NonNull RecyclerView.ViewHolder target) {
            int from = current.getAdapterPosition();
            int to = target.getAdapterPosition();
            return (isInActiveSection(from) && isInActiveSection(to))
                    || (isInHiddenSection(from) && isInHiddenSection(to));
        }

        @Override
        public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
            int from = viewHolder.getAdapterPosition();
            int to = target.getAdapterPosition();

            if (isInActiveSection(from) && isInActiveSection(to)) {
                int a = from - activeRowStart;
                int b = to - activeRowStart;
                if (a >= 0 && a < PillStackConfig.activePills.size()
                        && b >= 0 && b < PillStackConfig.activePills.size()) {
                    Integer moved = PillStackConfig.activePills.remove(a);
                    PillStackConfig.activePills.add(b, moved);
                    listAdapter.notifyItemMoved(from, to);
                    return true;
                }
            } else if (isInHiddenSection(from) && isInHiddenSection(to)) {
                int a = from - hiddenRowStart;
                int b = to - hiddenRowStart;
                if (a >= 0 && a < PillStackConfig.hiddenPills.size()
                        && b >= 0 && b < PillStackConfig.hiddenPills.size()) {
                    Integer moved = PillStackConfig.hiddenPills.remove(a);
                    PillStackConfig.hiddenPills.add(b, moved);
                    listAdapter.notifyItemMoved(from, to);
                    return true;
                }
            }
            return false;
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
        }

        @Override
        public void onSelectedChanged(RecyclerView.ViewHolder viewHolder, int actionState) {
            super.onSelectedChanged(viewHolder, actionState);
            if (actionState != ItemTouchHelper.ACTION_STATE_IDLE) {
                listView.hideSelector(false);
                if (viewHolder != null) {
                    listView.setDraggingChild(viewHolder.itemView);
                    viewHolder.itemView.setBackground(Theme.createRoundRectDrawable(
                            AndroidUtilities.dp(16), getThemedColor(Theme.key_windowBackgroundWhite)));
                    viewHolder.itemView.setPressed(true);
                    viewHolder.itemView.bringToFront();
                }
            }
        }

        @Override
        public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
            super.clearView(recyclerView, viewHolder);
            listView.setDraggingChild(null);
            viewHolder.itemView.setPressed(false);
            viewHolder.itemView.setBackground(null);
            listView.hideSelector(false);
            PillStackConfig.savePillsLayout();
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.pillStackLayoutChanged);
            updateResetButtonVisibility();
            listAdapter.notifyDataSetChanged();
        }

        private boolean isInActiveSection(int position) {
            return activeRowStart != -1 && position >= activeRowStart && position <= activeRowEnd;
        }

        private boolean isInHiddenSection(int position) {
            return hiddenRowStart != -1 && position >= hiddenRowStart && position <= hiddenRowEnd;
        }
    }

    private class ListAdapter extends BaseListAdapter {
        ListAdapter(Context context) {
            super(context);
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            RecyclerView.ViewHolder holder = super.onCreateViewHolder(parent, viewType);
            if (viewType != TYPE_INFO_PRIVACY && viewType != TYPE_SHADOW) {
                holder.itemView.setBackground(null);
            }
            return holder;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == settingsHeaderRow || position == activeHeaderRow || position == hiddenHeaderRow) {
                return TYPE_HEADER;
            }
            if (position == infiniteScrollingRow) {
                return TYPE_CHECK;
            }
            if (position == settingsShadowRow || position == activeShadowRow) {
                return TYPE_SHADOW;
            }
            return TYPE_TEXT;
        }

        @SuppressLint("NotifyDataSetChanged")
        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, boolean partial) {
            int viewType = holder.getItemViewType();
            switch (viewType) {
                case TYPE_HEADER: {
                    HeaderCell headerCell = (HeaderCell) holder.itemView;
                    headerCell.applySeparatedHeadersStyle();
                    if (position == settingsHeaderRow) {
                        headerCell.setText(getString(R.string.Settings));
                    } else if (position == activeHeaderRow) {
                        headerCell.setText(getString(R.string.PillStackActivePills));
                    } else if (position == hiddenHeaderRow) {
                        headerCell.setText(getString(R.string.PillStackHiddenPills));
                    }
                    break;
                }
                case TYPE_CHECK: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    cell.setTextAndCheck(getString(R.string.PillStackInfiniteScrolling),
                            PillStackConfig.infiniteScrolling, false);
                    break;
                }
                case TYPE_TEXT: {
                    TextCell cell = (TextCell) holder.itemView;
                    Integer pillId = getPillIdAtRow(position);
                    ItemInfo info = pillId != null ? itemDetails.get(pillId) : null;
                    if (info != null) {
                        cell.setText(info.name, shouldDrawDivider(position));
                        cell.setColorfulIcon(info.iconColorTop, info.iconColorBottom, info.iconRes);
                        ImageView iv = cell.getValueImageView();
                        if (iv != null) {
                            iv.setVisibility(View.VISIBLE);
                            iv.setImageDrawable(reorderIcon);
                            iv.setColorFilter(new PorterDuffColorFilter(
                                    getThemedColor(Theme.key_stickers_menu), PorterDuff.Mode.MULTIPLY));
                        }
                    }
                    break;
                }
            }
        }

        private boolean shouldDrawDivider(int position) {
            if (position == activeRowEnd) return false;
            if (position == hiddenRowEnd) return false;
            return true;
        }
    }
}
