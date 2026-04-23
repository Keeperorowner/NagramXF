package tw.nekomimi.nekogram.filters;

import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.ContactsController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;

import java.util.ArrayList;

import tw.nekomimi.nekogram.filters.popup.RegexChatFilterPopup;
import tw.nekomimi.nekogram.filters.popup.RegexExcludedChatFilterPopup;
import tw.nekomimi.nekogram.settings.BaseNekoSettingsActivity;
import tw.nekomimi.nekogram.ui.cells.HeaderCell;

public class RegexChatFiltersListActivity extends BaseNekoSettingsActivity {

    private final long dialogId;
    private int headerRow;
    private int startRow;
    private int endRow;
    private int addBtnRow;
    private int dividerRow;
    private int excludedHeaderRow;
    private int addExcludedBtnRow;
    private int excludedStartRow;
    private int excludedEndRow;
    private int emptyRow;

    public RegexChatFiltersListActivity(long dialogId) {
        this.dialogId = dialogId;
    }

    @Override
    protected void updateRows() {
        super.updateRows();

        ArrayList<AyuFilter.FilterModel> filters = AyuFilter.getChatFiltersForDialog(dialogId);
        ArrayList<AyuFilter.FilterModel> excludedFilters = AyuFilter.getExcludedSharedFiltersForDialog(dialogId);
        boolean hasFilters = !filters.isEmpty();
        boolean hasExcludedFilters = !excludedFilters.isEmpty();

        addBtnRow = -1;
        dividerRow = -1;
        excludedHeaderRow = -1;
        addExcludedBtnRow = -1;
        excludedStartRow = -1;
        excludedEndRow = -1;
        emptyRow = -1;
        headerRow = -1;
        startRow = -1;
        endRow = -1;

        if (hasFilters) {
            headerRow = rowCount++;
            startRow = rowCount;
            rowCount += filters.size();
            endRow = rowCount;
        }

        if (hasExcludedFilters) {
            if (hasFilters) {
                dividerRow = rowCount++;
            }
            excludedHeaderRow = rowCount++;
            excludedStartRow = rowCount;
            rowCount += excludedFilters.size();
            excludedEndRow = rowCount;
        }

        if (!hasFilters && !hasExcludedFilters) {
            emptyRow = rowCount++;
        }
    }

    @Override
    protected String getActionBarTitle() {
        try {
            String title = null;
            if (dialogId > 0) {
                TLRPC.User user = MessagesController.getInstance(UserConfig.selectedAccount).getUser(dialogId);
                if (user != null) {
                    title = ContactsController.formatName(user.first_name, user.last_name);
                }
            } else {
                TLRPC.Chat chat = MessagesController.getInstance(UserConfig.selectedAccount).getChat(-dialogId);
                if (chat != null) {
                    title = chat.title;
                }
            }
            if (!TextUtils.isEmpty(title)) {
                return title;
            }
        } catch (Exception ignored) {
        }
        return getString(R.string.RegexFilters);
    }

    @Override
    protected BaseListAdapter createAdapter(Context context) {
        return new ListAdapter(context);
    }

    @Override
    public View createView(Context context) {
        View view = super.createView(context);
        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(0, R.drawable.msg_blur_radial).setContentDescription(getString(R.string.RegexFiltersExcludeShared));
        menu.addItem(1, R.drawable.msg_add).setContentDescription(getString(R.string.Add));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == 0) {
                    presentFragment(new RegexSharedFiltersListActivity(dialogId));
                } else if (id == 1) {
                    presentFragment(new RegexFilterEditActivity(dialogId));
                }
            }
        });
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
            int idx = position - startRow;
            var filters = AyuFilter.getChatFiltersForDialog(dialogId);
            if (idx >= 0 && idx < filters.size()) {
                RegexChatFilterPopup.show(this, view, x, y, dialogId, idx);
            }
        } else if (position >= excludedStartRow && position < excludedEndRow) {
            int idx = position - excludedStartRow;
            var excludedFilters = AyuFilter.getExcludedSharedFiltersForDialog(dialogId);
            if (idx >= 0 && idx < excludedFilters.size()) {
                RegexExcludedChatFilterPopup.show(this, view, x, y, dialogId, excludedFilters.get(idx));
            }
        }
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
                    } else if (position == excludedHeaderRow) {
                        ((HeaderCell) holder.itemView).setText(getString(R.string.RegexFiltersExcluded));
                    }
                    break;
                case TYPE_TEXT:
                    TextCell textCell = (TextCell) holder.itemView;
                    if (position >= startRow && position < endRow) {
                        int idx = position - startRow;
                        var filters = AyuFilter.getChatFiltersForDialog(dialogId);
                        if (idx >= 0 && idx < filters.size()) {
                            AyuFilter.FilterModel model = filters.get(idx);
                            boolean divider = position + 1 < endRow;
                            int textColorKey = model.enabled ? Theme.key_windowBackgroundWhiteBlackText : Theme.key_windowBackgroundWhiteGrayText;
                            textCell.setColors(-1, textColorKey);
                            textCell.setText(AyuFilter.getFilterDisplayText(model), divider);
                        }
                    } else if (position >= excludedStartRow && position < excludedEndRow) {
                        int idx = position - excludedStartRow;
                        var excludedFilters = AyuFilter.getExcludedSharedFiltersForDialog(dialogId);
                        if (idx >= 0 && idx < excludedFilters.size()) {
                            AyuFilter.FilterModel model = excludedFilters.get(idx);
                            boolean divider = position + 1 < excludedEndRow;
                            textCell.setColors(-1, Theme.key_windowBackgroundWhiteBlackText);
                            textCell.setText(AyuFilter.getFilterDisplayText(model), divider);
                        }
                    }
                    break;
                case TYPE_SHADOW:
                    holder.itemView.setBackground(Theme.getThemedDrawable(mContext, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                    break;
                case TYPE_INFO_PRIVACY:
                    TextInfoPrivacyCell infoCell = (TextInfoPrivacyCell) holder.itemView;
                    infoCell.setBackground(Theme.getThemedDrawable(mContext, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
                    if (position == emptyRow) {
                        infoCell.setText(getString(R.string.RegexFiltersListEmpty));
                    }
                    break;
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == dividerRow) {
                return TYPE_SHADOW;
            }
            if (position == emptyRow) {
                return TYPE_INFO_PRIVACY;
            }
            if (position == headerRow || position == excludedHeaderRow) {
                return TYPE_HEADER;
            }
            return TYPE_TEXT;
        }
    }
}

