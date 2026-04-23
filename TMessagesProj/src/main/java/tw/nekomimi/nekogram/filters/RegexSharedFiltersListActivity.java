package tw.nekomimi.nekogram.filters;

import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;

import java.util.ArrayList;

import tw.nekomimi.nekogram.filters.popup.RegexFilterPopup;
import tw.nekomimi.nekogram.settings.BaseNekoSettingsActivity;
import tw.nekomimi.nekogram.ui.cells.HeaderCell;

public class RegexSharedFiltersListActivity extends BaseNekoSettingsActivity {

    private final long exclusionDialogId;
    private int headerRow;
    private int startRow;
    private int endRow;
    private int addBtnRow;
    private int emptyRow;

    public RegexSharedFiltersListActivity() {
        this(0L);
    }

    public RegexSharedFiltersListActivity(long exclusionDialogId) {
        this.exclusionDialogId = exclusionDialogId;
    }

    private ArrayList<AyuFilter.FilterModel> getDisplayedFilters() {
        ArrayList<AyuFilter.FilterModel> filters = new ArrayList<>(AyuFilter.getRegexFilters());
        if (exclusionDialogId != 0L) {
            filters.removeIf(filter -> filter == null || AyuFilter.isSharedFilterExcluded(exclusionDialogId, filter.id));
        }
        return filters;
    }

    @Override
    protected void updateRows() {
        super.updateRows();

        ArrayList<AyuFilter.FilterModel> filters = getDisplayedFilters();
        boolean hasFilters = !filters.isEmpty();
        headerRow = hasFilters ? rowCount++ : -1;
        addBtnRow = -1;
        startRow = rowCount;
        rowCount += filters.size();
        endRow = rowCount;
        emptyRow = !hasFilters ? rowCount++ : -1;
    }

    @Override
    protected String getActionBarTitle() {
        return exclusionDialogId != 0L ? getString(R.string.RegexFiltersExcluded) : getString(R.string.RegexFiltersSharedHeader);
    }

    @Override
    protected BaseListAdapter createAdapter(Context context) {
        return new ListAdapter(context);
    }

    @Override
    public View createView(Context context) {
        View view = super.createView(context);
        if (exclusionDialogId == 0L) {
            ActionBarMenu menu = actionBar.createMenu();
            var addItem = menu.addItem(0, R.drawable.msg_add);
            addItem.setContentDescription(getString(R.string.Add));
            addItem.setOnClickListener(v -> presentFragment(new RegexFilterEditActivity()));
            actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
                @Override
                public void onItemClick(int id) {
                    if (id == -1) {
                        finishFragment();
                    } else if (id == 0) {
                        presentFragment(new RegexFilterEditActivity());
                    }
                }
            });
        }
        return view;
    }

    @SuppressLint("NotifyDataSetChanged")
    @Override
    public void onResume() {
        super.onResume();
        updateRows();
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    @Override
    protected void onItemClick(View view, int position, float x, float y) {
        if (position >= startRow && position < endRow) {
            int filterIndex = position - startRow;
            ArrayList<AyuFilter.FilterModel> filterModels = getDisplayedFilters();
            if (filterIndex < 0 || filterIndex >= filterModels.size()) {
                return;
            }
            if (exclusionDialogId != 0L) {
                AyuFilter.FilterModel filterModel = filterModels.get(filterIndex);
                if (filterModel != null) {
                    AyuFilter.setSharedFilterExcluded(exclusionDialogId, filterModel.id, true);
                }
                finishFragment();
                return;
            }
            RegexFilterPopup.show(this, view, x, y, filterIndex);
        }
    }

    @Override
    protected boolean onItemLongClick(View view, int position, float x, float y) {
        if (exclusionDialogId != 0L) {
            return super.onItemLongClick(view, position, x, y);
        }
        if (position >= startRow && position < endRow) {
            int filterIndex = position - startRow;
            ArrayList<AyuFilter.FilterModel> filterModels = getDisplayedFilters();
            if (filterIndex >= 0 && filterIndex < filterModels.size()) {
                RegexFilterPopup.show(this, view, x, y, filterIndex);
                return true;
            }
        }
        return super.onItemLongClick(view, position, x, y);
    }

    private class ListAdapter extends BaseListAdapter {
        public ListAdapter(Context context) {
            super(context);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, boolean payload) {
            switch (holder.getItemViewType()) {
                case TYPE_HEADER:
                    if (position == headerRow) {
                        ((HeaderCell) holder.itemView).setText(getString(R.string.RegexFiltersHeader));
                    }
                    break;
                case TYPE_INFO_PRIVACY:
                    TextInfoPrivacyCell infoCell = (TextInfoPrivacyCell) holder.itemView;
                    infoCell.setBackground(Theme.getThemedDrawable(mContext, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
                    if (position == emptyRow) {
                        infoCell.setText(getString(R.string.RegexFiltersListEmpty));
                    }
                    break;
                case TYPE_TEXT:
                    if (position >= startRow && position < endRow) {
                        int idx = position - startRow;
                        ArrayList<AyuFilter.FilterModel> filters = getDisplayedFilters();
                        if (idx >= 0 && idx < filters.size()) {
                            TextCell textCell = (TextCell) holder.itemView;
                            AyuFilter.FilterModel model = filters.get(idx);
                            boolean divider = position + 1 < endRow;
                            if (exclusionDialogId != 0L) {
                                textCell.setColors(-1, Theme.key_windowBackgroundWhiteBlackText);
                                textCell.setText(AyuFilter.getFilterDisplayText(model), divider);
                            } else {
                                int textColorKey = model.enabled ? Theme.key_windowBackgroundWhiteBlackText : Theme.key_windowBackgroundWhiteGrayText;
                                textCell.setColors(-1, textColorKey);
                                textCell.setText(AyuFilter.getFilterDisplayText(model), divider);
                            }
                        }
                    }
                    break;
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == headerRow) {
                return TYPE_HEADER;
            }
            if (position == emptyRow) {
                return TYPE_INFO_PRIVACY;
            }
            if (position >= startRow && position < endRow) {
                return TYPE_TEXT;
            }
            return TYPE_TEXT;
        }
    }
}

