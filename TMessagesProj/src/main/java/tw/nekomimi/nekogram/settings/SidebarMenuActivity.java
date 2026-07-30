package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UndoView;

import java.util.ArrayList;
import java.util.List;

import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.ui.cells.HeaderCell;
import xyz.nextalone.nagram.helper.DrawerMenuHelper;

/**
 * ayuGram-style sidebar ("main menu") manager.
 *
 * Top section toggles the navigation drawer itself; below it the visible drawer rows
 * and the hidden rows live in two reorderable sections. Long-press drags to reorder,
 * tapping a row moves it between the two sections, dividers can be added and removed.
 * Built on {@link BaseReorderManagerActivity} (same base as PillStackPreferencesActivity).
 */
public class SidebarMenuActivity extends BaseReorderManagerActivity {

    private static final int MENU_RESET = 1;
    private static final String KEY_NAV_DRAWER = "navigationDrawerEnabled";

    private ActionBarMenuItem resetItem;
    private UndoView tooltip;

    private int settingsHeaderRow;
    private int navigationDrawerRow;
    private int settingsShadowRow;

    private int visibleHeaderRow;
    private int visibleRowStart;
    private int visibleRowEnd;
    private int addDividerRow;
    private int infoRow;

    private int hiddenHeaderRow;
    private int hiddenRowStart;
    private int hiddenRowEnd;

    // Working copies; mutated by toggle/reorder and persisted via DrawerMenuHelper.save().
    private final List<Integer> layout = new ArrayList<>();
    private final List<Integer> hidden = new ArrayList<>();

    @Override
    protected String getActionBarTitle() {
        return getString(R.string.DrawerElements);
    }

    @Override
    protected String getKey() {
        return "sidebar";
    }

    @Override
    public View createView(Context context) {
        View view = super.createView(context);

        tooltip = new UndoView(context);
        ((FrameLayout) fragmentView).addView(tooltip, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.LEFT, 8, 0, 8, 8));

        resetItem = addResetMenuItem(MENU_RESET);
        resetItem.setOnClickListener(v -> {
            DrawerMenuHelper.resetToDefault();
            reloadData();
            updateRows();
            updateResetVisibility();
            notifyDrawerChanged();
        });
        updateResetVisibility();

        attachReorder(new ReorderDelegate() {
            @Override
            public boolean isDraggable(int position) {
                return isInVisible(position) || isInHidden(position);
            }

            @Override
            public boolean isSameSection(int from, int to) {
                return (isInVisible(from) && isInVisible(to)) || (isInHidden(from) && isInHidden(to));
            }

            @Override
            public void onMove(int from, int to) {
                if (isInVisible(from) && isInVisible(to)) {
                    moveWithin(layout, from - visibleRowStart, to - visibleRowStart);
                } else if (isInHidden(from) && isInHidden(to)) {
                    moveWithin(hidden, from - hiddenRowStart, to - hiddenRowStart);
                }
                listAdapter.notifyItemMoved(from, to);
            }

            @Override
            public void onReorderFinished() {
                DrawerMenuHelper.save(layout, hidden);
                notifyDrawerChanged();
                updateResetVisibility();
                if (listAdapter != null) {
                    listAdapter.notifyDataSetChanged();
                }
            }
        });

        return view;
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        super.onInsets(left, top, right, bottom);
        if (tooltip != null) {
            FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) tooltip.getLayoutParams();
            layoutParams.setMargins(org.telegram.messenger.AndroidUtilities.dp(8), 0, org.telegram.messenger.AndroidUtilities.dp(8), org.telegram.messenger.AndroidUtilities.dp(8) + bottom);
            tooltip.setLayoutParams(layoutParams);
        }
    }

    @Override
    protected BaseListAdapter createAdapter(Context context) {
        return new ListAdapter(context);
    }

    private void reloadData() {
        layout.clear();
        layout.addAll(DrawerMenuHelper.getLayout());
        hidden.clear();
        hidden.addAll(DrawerMenuHelper.getHidden());
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
        reloadData();

        settingsHeaderRow = addRow();
        navigationDrawerRow = addRow(KEY_NAV_DRAWER);
        settingsShadowRow = addRow();

        visibleHeaderRow = addRow();
        visibleRowStart = rowCount;
        for (int i = 0; i < layout.size(); i++) {
            addRow();
        }
        visibleRowEnd = rowCount - 1;
        addDividerRow = addRow();
        infoRow = addRow();

        hiddenHeaderRow = -1;
        hiddenRowStart = -1;
        hiddenRowEnd = -1;
        if (!hidden.isEmpty()) {
            hiddenHeaderRow = addRow();
            hiddenRowStart = rowCount;
            for (int i = 0; i < hidden.size(); i++) {
                addRow();
            }
            hiddenRowEnd = rowCount - 1;
        }
    }

    private boolean isInVisible(int position) {
        return visibleRowStart != -1 && position >= visibleRowStart && position <= visibleRowEnd;
    }

    private boolean isInHidden(int position) {
        return hiddenRowStart != -1 && position >= hiddenRowStart && position <= hiddenRowEnd;
    }

    private static void moveWithin(List<Integer> list, int from, int to) {
        if (from < 0 || from >= list.size() || to < 0 || to >= list.size()) {
            return;
        }
        list.add(to, list.remove(from));
    }

    @Override
    protected void onItemClick(View view, int position, float x, float y) {
        if (position == navigationDrawerRow) {
            NekoConfig.navigationDrawerEnabled.toggleConfigBool();
            ((TextCheckCell) view).setChecked(NekoConfig.navigationDrawerEnabled.Bool());
            NotificationCenter.getInstance(UserConfig.selectedAccount).postNotificationName(NotificationCenter.mainUserInfoChanged);
            if (tooltip != null) {
                tooltip.showWithAction(0, UndoView.ACTION_NEED_RESTART, null, null);
            }
            return;
        }
        if (position == addDividerRow) {
            List<String> oldItems = buildItemIdentifiers();
            layout.add(DrawerMenuHelper.DIVIDER);
            DrawerMenuHelper.save(layout, hidden);
            animateLayoutChange(oldItems);
            notifyDrawerChanged();
            return;
        }
        if (isInVisible(position)) {
            int index = position - visibleRowStart;
            if (index < 0 || index >= layout.size()) return;
            List<String> oldItems = buildItemIdentifiers();
            if (layout.get(index) == DrawerMenuHelper.DIVIDER) {
                layout.remove(index);
            } else {
                int id = layout.remove(index);
                if (!hidden.contains(id)) {
                    hidden.add(0, id);
                }
            }
            DrawerMenuHelper.save(layout, hidden);
            animateLayoutChange(oldItems);
            notifyDrawerChanged();
        } else if (isInHidden(position)) {
            int index = position - hiddenRowStart;
            if (index < 0 || index >= hidden.size()) return;
            List<String> oldItems = buildItemIdentifiers();
            int id = hidden.remove(index);
            layout.add(id);
            DrawerMenuHelper.save(layout, hidden);
            animateLayoutChange(oldItems);
            notifyDrawerChanged();
        }
    }

    /** Rebuilds rows and dispatches an animated diff, matching PillStack's toggle animation. */
    private void animateLayoutChange(List<String> oldItems) {
        rebuildRows();
        List<String> newItems = buildItemIdentifiers();
        if (listAdapter != null) {
            DiffUtil.DiffResult result = DiffUtil.calculateDiff(new DiffUtil.Callback() {
                @Override
                public int getOldListSize() {
                    return oldItems.size();
                }

                @Override
                public int getNewListSize() {
                    return newItems.size();
                }

                @Override
                public boolean areItemsTheSame(int o, int n) {
                    return oldItems.get(o).equals(newItems.get(n));
                }

                @Override
                public boolean areContentsTheSame(int o, int n) {
                    return false;
                }
            }, true);
            result.dispatchUpdatesTo(listAdapter);
        }
        updateResetVisibility();
    }

    private List<String> buildItemIdentifiers() {
        List<String> items = new ArrayList<>(rowCount);
        for (int i = 0; i < rowCount; i++) {
            if (i == settingsHeaderRow) items.add("h_settings");
            else if (i == navigationDrawerRow) items.add("c_navdrawer");
            else if (i == visibleHeaderRow) items.add("h_visible");
            else if (i == addDividerRow) items.add("b_adddiv");
            else if (i == infoRow) items.add("i_info");
            else if (i == hiddenHeaderRow) items.add("h_hidden");
            else if (isInVisible(i)) {
                int index = i - visibleRowStart;
                int id = layout.get(index);
                items.add(id == DrawerMenuHelper.DIVIDER ? "div_" + index : "item_" + id);
            } else if (isInHidden(i)) {
                items.add("item_" + hidden.get(i - hiddenRowStart));
            } else {
                items.add("x_" + i);
            }
        }
        return items;
    }

    private void updateResetVisibility() {
        boolean isDefault = DrawerMenuHelper.getLayout().equals(DrawerMenuHelper.defaultLayout())
                && DrawerMenuHelper.getHidden().equals(DrawerMenuHelper.defaultHidden());
        updateResetButtonVisibility(resetItem, isDefault);
    }

    private void notifyDrawerChanged() {
        NotificationCenter.getInstance(UserConfig.selectedAccount)
                .postNotificationName(NotificationCenter.mainUserInfoChanged);
    }

    private class ListAdapter extends BaseListAdapter {
        ListAdapter(Context context) {
            super(context);
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
            RecyclerView.ViewHolder holder = super.onCreateViewHolder(parent, viewType);
            clearRowBackgroundForRoundedSection(holder, viewType);
            return holder;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == settingsHeaderRow || position == visibleHeaderRow || position == hiddenHeaderRow) {
                return TYPE_HEADER;
            }
            if (position == navigationDrawerRow) {
                return TYPE_CHECK;
            }
            if (position == settingsShadowRow) {
                return TYPE_SHADOW;
            }
            if (position == infoRow) {
                return TYPE_INFO_PRIVACY;
            }
            return TYPE_TEXT;
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, boolean partial) {
            switch (holder.getItemViewType()) {
                case TYPE_HEADER: {
                    HeaderCell headerCell = (HeaderCell) holder.itemView;
                    headerCell.applySeparatedHeadersStyle();
                    if (position == settingsHeaderRow) {
                        headerCell.setText(getString(R.string.Settings));
                    } else if (position == visibleHeaderRow) {
                        headerCell.setText(getString(R.string.MainMenuItems));
                    } else if (position == hiddenHeaderRow) {
                        headerCell.setText(getString(R.string.MainMenuHiddenItems));
                    }
                    break;
                }
                case TYPE_CHECK: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    cell.setTextAndCheck(getString(R.string.HomeDrawer), NekoConfig.navigationDrawerEnabled.Bool(), true);
                    break;
                }
                case TYPE_INFO_PRIVACY: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    cell.setText(getString(R.string.MainMenuItemsInfo));
                    break;
                }
                case TYPE_TEXT: {
                    TextCell cell = (TextCell) holder.itemView;
                    if (position == addDividerRow) {
                        bindAddDivider(cell);
                    } else if (isInVisible(position)) {
                        int id = layout.get(position - visibleRowStart);
                        bindMenuRow(cell, id, position != visibleRowEnd);
                    } else if (isInHidden(position)) {
                        int id = hidden.get(position - hiddenRowStart);
                        bindMenuRow(cell, id, position != hiddenRowEnd);
                    }
                    break;
                }
            }
        }

        private void bindAddDivider(TextCell cell) {
            cell.setTextAndIcon(getString(R.string.MainMenuAddDivider), R.drawable.msg_add, false);
            cell.setColors(org.telegram.ui.ActionBar.Theme.key_windowBackgroundWhiteBlueText4, org.telegram.ui.ActionBar.Theme.key_windowBackgroundWhiteBlueText4);
        }

        private void bindMenuRow(TextCell cell, int id, boolean divider) {
            if (id == DrawerMenuHelper.DIVIDER) {
                cell.setTextAndIcon(getString(R.string.MainMenuDivider), R.drawable.msg_block, divider);
            } else {
                DrawerMenuHelper.Entry entry = DrawerMenuHelper.entryFor(id);
                if (entry == null) {
                    cell.setText("", divider);
                } else {
                    cell.setTextAndIcon(getString(entry.getLabelRes()), entry.getIconRes(), divider);
                }
            }
            cell.setColors(org.telegram.ui.ActionBar.Theme.key_windowBackgroundWhiteGrayIcon, org.telegram.ui.ActionBar.Theme.key_windowBackgroundWhiteBlackText);
            applyReorderHandle(cell);
        }
    }
}
