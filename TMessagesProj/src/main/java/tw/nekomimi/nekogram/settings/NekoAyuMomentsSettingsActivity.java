package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.RadioColorCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UndoView;

import java.util.ArrayList;

import tw.nekomimi.nekogram.config.CellGroup;
import tw.nekomimi.nekogram.config.ConfigItem;
import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.config.cell.AbstractConfigCell;
import tw.nekomimi.nekogram.config.cell.ConfigCellCustom;
import tw.nekomimi.nekogram.config.cell.ConfigCellDivider;
import tw.nekomimi.nekogram.config.cell.ConfigCellHeader;
import tw.nekomimi.nekogram.config.cell.ConfigCellTextCheck;
import tw.nekomimi.nekogram.config.cell.ConfigCellTextCheckIcon;
import tw.nekomimi.nekogram.config.cell.ConfigCellTextInput;
import tw.nekomimi.nekogram.filters.RegexFiltersSettingActivity;
import tw.nekomimi.nekogram.helpers.TimeStringHelper;
import tw.nekomimi.nekogram.ui.cells.DeletedMessagesColorPickerCell;
import tw.nekomimi.nekogram.ui.cells.DeletedMessagesPreviewCell;
import xyz.nextalone.nagram.NaConfig;

@SuppressWarnings("unused")
public class NekoAyuMomentsSettingsActivity extends BaseNekoXSettingsActivity {

    private ListAdapter listAdapter;
    private DeletedMessagesPreviewCell deletedMessagesPreviewCell;
    private DeletedMessagesColorPickerCell deletedMessagesColorPickerCell;

    private final CellGroup cellGroup = new CellGroup(this);

    private final AbstractConfigCell headerAyuMoments = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.AyuMoments)));
    private final AbstractConfigCell ghostModeRow = cellGroup.appendCell(new ConfigCellTextCheckIcon(null, "GhostMode", null, R.drawable.ayu_ghost, true, () -> presentFragment(new GhostModeActivity())));
    private final AbstractConfigCell regexFiltersEnabledRow = cellGroup.appendCell(new ConfigCellTextCheckIcon(null, "RegexFilters", null, R.drawable.menu_tag_filter_solar, true, () -> presentFragment(new RegexFiltersSettingActivity())));
    private final AbstractConfigCell spySettingsRow = cellGroup.appendCell(new ConfigCellTextCheckIcon(null, "AyuSpySettings", null, R.drawable.msg_bots_solar, true, () -> presentFragment(new NekoAyuSpySettingsActivity())));
    private final AbstractConfigCell customHeaderRow = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.AyuMomentsCustomHeader)));
    private final AbstractConfigCell deletedMessagesPreviewRow = cellGroup.appendCell(new ConfigCellCustom("DeletedMessagesAppearancePreviewRow", ConfigCellCustom.CUSTOM_ITEM_DeletedMessagesAppearanceCard, false));
    private final AbstractConfigCell translucentDeletedMessagesRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getTranslucentDeletedMessages()));
    private final AbstractConfigCell deletedMarkRow = cellGroup.appendCell(new ConfigCellCustom(NaConfig.INSTANCE.getDeletedIconStyle().getKey(), CellGroup.ITEM_TYPE_TEXT_SETTINGS_CELL, true));
    private final AbstractConfigCell deletedMarkColorRow = cellGroup.appendCell(new ConfigCellCustom(NaConfig.INSTANCE.getDeletedIconColor().getKey(), ConfigCellCustom.CUSTOM_ITEM_DeletedMessagesColorPicker, false));
    private final AbstractConfigCell customDeletedMarkRow = cellGroup.appendCell(new ConfigCellTextInput(null, NaConfig.INSTANCE.getCustomDeletedMark(), "", null));
    private final AbstractConfigCell dividerCustomExperimentalRow = cellGroup.appendCell(new ConfigCellDivider());
    private final AbstractConfigCell localPremiumRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.localPremium));
    private final AbstractConfigCell hideProxySponsorChannelRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.hideProxySponsorChannel));
    private final AbstractConfigCell hideSponsoredMessageRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.hideSponsoredMessage));
    private final AbstractConfigCell dividerCustomExperimentalBottomRow = cellGroup.appendCell(new ConfigCellDivider());

    public NekoAyuMomentsSettingsActivity() {
        checkDeletedMarkDetailRows();
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
        return "ayumoments";
    }

    @Override
    public View createView(Context context) {
        View superView = super.createView(context);

        listAdapter = new ListAdapter(context);
        listView.setAdapter(listAdapter);
        setupDefaultListeners();

        cellGroup.callBackSettingsChanged = (key, newValue) -> {
            if (key.equals(NaConfig.INSTANCE.getTranslucentDeletedMessages().getKey())) {
                notifyRowChanged(deletedMessagesPreviewRow);
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface);
            } else if (key.equals(NaConfig.INSTANCE.getDeletedIconStyle().getKey())) {
                checkDeletedMarkDetailRows();
                notifyRowChanged(deletedMessagesPreviewRow);
                notifyRowChanged(deletedMarkRow);
                notifyRowChanged(deletedMarkColorRow);
                notifyRowChanged(customDeletedMarkRow);
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface);
            } else if (key.equals(NaConfig.INSTANCE.getDeletedIconColor().getKey())) {
                notifyRowChanged(deletedMessagesPreviewRow);
                notifyRowChanged(deletedMarkRow);
                notifyRowChanged(deletedMarkColorRow);
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface);
            } else if (key.equals(NaConfig.INSTANCE.getCustomDeletedMark().getKey())) {
                notifyRowChanged(deletedMessagesPreviewRow);
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface);
            } else if (key.equals(NekoConfig.localPremium.getKey())) {
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mainUserInfoChanged);
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface);
            } else if (key.equals(NekoConfig.hideSponsoredMessage.getKey())) {
                tooltip.showWithAction(0, UndoView.ACTION_NEED_RESTART, null, null);
            } else if (key.equals(NekoConfig.hideProxySponsorChannel.getKey())) {
                for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                    if (UserConfig.getInstance(account).isClientActivated()) {
                        org.telegram.messenger.MessagesController.getInstance(account).checkPromoInfo(true);
                    }
                }
            }
        };

        return superView;
    }

    @Override
    protected void onCustomCellClick(View view, int position, float x, float y) {
        if (position == cellGroup.rows.indexOf(deletedMarkRow)) {
            showDeletedMarkDialog();
            return;
        }
        super.onCustomCellClick(view, position, x, y);
    }

    @Override
    protected boolean onItemLongClick(View view, int position, float x, float y) {
        if (position == cellGroup.rows.indexOf(deletedMarkRow)) {
            ItemOptions options = makeLongClickOptions(view);
            addDefaultLongClickOptions(
                    options,
                    getSettingsPrefix(),
                    NaConfig.INSTANCE.getDeletedIconStyle().getKey(),
                    NaConfig.INSTANCE.getDeletedIconStyle().String()
            );
            showLongClickOptions(view, options);
            return true;
        }
        if (position == cellGroup.rows.indexOf(deletedMarkColorRow)) {
            ItemOptions options = makeLongClickOptions(view);
            addDefaultLongClickOptions(
                    options,
                    getSettingsPrefix(),
                    NaConfig.INSTANCE.getDeletedIconColor().getKey(),
                    NaConfig.INSTANCE.getDeletedIconColor().String()
            );
            showLongClickOptions(view, options);
            return true;
        }
        return super.onItemLongClick(view, position, x, y);
    }

    @Override
    public int getBaseGuid() {
        return 15000;
    }

    @Override
    public int getDrawable() {
        return R.drawable.heart_angle_solar;
    }

    @Override
    public String getTitle() {
        return getString(R.string.AyuMoments);
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> themeDescriptions = super.getThemeDescriptions();
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR, new Class[]{TextCell.class}, null, null, null, Theme.key_windowBackgroundWhite));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteValueText));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCell.class}, new String[]{"imageView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayIcon));
        return themeDescriptions;
    }

    @Override
    public void importToRow(String key, String value, Runnable unknown) {
        ConfigItem config = getDeletedMessagesAppearanceConfigItem(key);
        if (config == null) {
            super.importToRow(key, value, unknown);
            return;
        }

        Context context = getParentActivity();
        if (context != null && value != null) {
            Object newValue = config.checkConfigFromString(value);
            if (newValue == null) {
                scrollToRow(key, unknown);
                return;
            }
            new AlertDialog.Builder(context)
                    .setTitle(getString(R.string.ImportSettings))
                    .setMessage(getString(R.string.ImportSettingsAlert))
                    .setNegativeButton(getString(R.string.Cancel), (dialogInter, i) -> scrollToRow(key, unknown))
                    .setPositiveButton(getString(R.string.Import), (dialogInter, i) -> {
                        config.changed(newValue);
                        config.saveConfig();
                        TimeStringHelper.invalidateDeletedStyle();
                        cellGroup.runCallback(key, newValue);
                        updateRows();
                        scrollToRow(key, unknown);
                    })
                    .show();
            return;
        }

        scrollToRow(key, unknown);
    }

    @Override
    public void scrollToRow(String key, Runnable unknown) {
        if (NaConfig.INSTANCE.getDeletedIconColor().getKey().equals(key) && !cellGroup.rows.contains(deletedMarkColorRow)) {
            String rowKey = getRowKey(deletedMarkRow);
            super.scrollToRow(rowKey != null ? rowKey : key, unknown);
            return;
        }
        if (NaConfig.INSTANCE.getCustomDeletedMark().getKey().equals(key) && !cellGroup.rows.contains(customDeletedMarkRow)) {
            String rowKey = getRowKey(deletedMarkRow);
            super.scrollToRow(rowKey != null ? rowKey : key, unknown);
            return;
        }
        super.scrollToRow(key, unknown);
    }

    private ConfigItem getDeletedMessagesAppearanceConfigItem(String key) {
        if (NaConfig.INSTANCE.getDeletedIconStyle().getKey().equals(key)) {
            return NaConfig.INSTANCE.getDeletedIconStyle();
        }
        if (NaConfig.INSTANCE.getDeletedIconColor().getKey().equals(key)) {
            return NaConfig.INSTANCE.getDeletedIconColor();
        }
        if (NaConfig.INSTANCE.getCustomDeletedMark().getKey().equals(key)) {
            return NaConfig.INSTANCE.getCustomDeletedMark();
        }
        return null;
    }

    private void checkDeletedMarkDetailRows() {
        boolean showColorRow = TimeStringHelper.getDeletedIconStyle() != 0;
        AbstractConfigCell rowToShow = showColorRow ? deletedMarkColorRow : customDeletedMarkRow;
        AbstractConfigCell rowToHide = showColorRow ? customDeletedMarkRow : deletedMarkColorRow;
        if (listAdapter == null) {
            cellGroup.rows.remove(rowToHide);
            if (!cellGroup.rows.contains(rowToShow)) {
                int index = cellGroup.rows.indexOf(deletedMarkRow);
                if (index >= 0) {
                    cellGroup.rows.add(index + 1, rowToShow);
                }
            }
            return;
        }
        int hiddenIndex = cellGroup.rows.indexOf(rowToHide);
        if (hiddenIndex != -1) {
            cellGroup.rows.remove(hiddenIndex);
            listAdapter.notifyItemRemoved(hiddenIndex);
        }
        int index = cellGroup.rows.indexOf(deletedMarkRow);
        if (index >= 0 && !cellGroup.rows.contains(rowToShow)) {
            cellGroup.rows.add(index + 1, rowToShow);
            listAdapter.notifyItemInserted(index + 1);
        }
        addRowsToMap(cellGroup);
    }

    private void bindDeletedMarkRow(TextSettingsCell cell) {
        cell.setTextAndValue(getString(R.string.DeletedMarkText), null, cellGroup.needSetDivider(deletedMarkRow));

        ImageView valueImageView = cell.getValueImageView();
        Drawable drawable = TimeStringHelper.getDeletedPreviewDrawable();
        int style = TimeStringHelper.getDeletedIconStyle();

        if (style == 0 || drawable == null) {
            valueImageView.setImageDrawable(null);
            valueImageView.setVisibility(View.INVISIBLE);
            valueImageView.setTranslationX(0f);
            return;
        }

        valueImageView.setImageDrawable(drawable);
        valueImageView.setVisibility(View.VISIBLE);
        valueImageView.setTranslationX(style == 3 ? -AndroidUtilities.dp(1) : 0f);
        valueImageView.setColorFilter(new PorterDuffColorFilter(
                Theme.getColor(Theme.key_chat_inTimeText, getResourceProvider()),
                PorterDuff.Mode.SRC_IN
        ));
    }

    private void showDeletedMarkDialog() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }

        CharSequence[] entries = new CharSequence[]{
                getString(R.string.DeletedMarkNothing),
                getString(R.string.DeletedMarkTrashBin),
                getString(R.string.DeletedMarkCross),
                getString(R.string.DeletedMarkEyeCrossed)
        };
        int checkedIndex = TimeStringHelper.getDeletedIconStyle();

        AlertDialog.Builder builder = new AlertDialog.Builder(context, getResourceProvider());
        builder.setTitle(getString(R.string.DeletedMarkText));

        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        builder.setView(linearLayout);

        for (int i = 0; i < entries.length; i++) {
            RadioColorCell cell = new RadioColorCell(context, getResourceProvider());
            cell.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), 0);
            cell.setTag(i);
            cell.setCheckColor(
                    Theme.getColor(Theme.key_radioBackground, getResourceProvider()),
                    Theme.getColor(Theme.key_dialogRadioBackgroundChecked, getResourceProvider())
            );
            cell.setTextAndValue(entries[i], checkedIndex == i);
            cell.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, getResourceProvider()), 2));
            linearLayout.addView(cell);
            cell.setOnClickListener(v -> {
                Integer index = (Integer) v.getTag();
                NaConfig.INSTANCE.getDeletedIconStyle().setConfigInt(index);
                TimeStringHelper.invalidateDeletedStyle();
                cellGroup.runCallback(NaConfig.INSTANCE.getDeletedIconStyle().getKey(), index);
                builder.getDismissRunnable().run();
            });
        }

        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void notifyRowChanged(AbstractConfigCell row) {
        if (listAdapter == null) {
            return;
        }
        int index = cellGroup.rows.indexOf(row);
        if (index >= 0) {
            listAdapter.notifyItemChanged(index);
        }
    }

    private class ListAdapter extends BaseListAdapter {

        public ListAdapter(Context context) {
            super(context);
        }

        @Override
        protected View onCreateCustomViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == ConfigCellCustom.CUSTOM_ITEM_DeletedMessagesAppearanceCard) {
                return deletedMessagesPreviewCell = new DeletedMessagesPreviewCell(mContext, getParentLayout());
            } else if (viewType == ConfigCellCustom.CUSTOM_ITEM_DeletedMessagesColorPicker) {
                deletedMessagesColorPickerCell = new DeletedMessagesColorPickerCell(mContext, getResourceProvider());
                deletedMessagesColorPickerCell.setOnColorSelected(colorId -> {
                    NaConfig.INSTANCE.getDeletedIconColor().setConfigInt(colorId);
                    TimeStringHelper.invalidateDeletedStyle();
                    cellGroup.runCallback(NaConfig.INSTANCE.getDeletedIconColor().getKey(), colorId);
                });
                return deletedMessagesColorPickerCell;
            }
            return null;
        }

        @Override
        protected void onBindCustomViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            AbstractConfigCell row = cellGroup.rows.get(position);

            if (row == deletedMessagesPreviewRow) {
                DeletedMessagesPreviewCell previewCell = (DeletedMessagesPreviewCell) holder.itemView;
                previewCell.refresh();
            } else if (row == deletedMarkRow) {
                bindDeletedMarkRow((TextSettingsCell) holder.itemView);
            } else if (row == deletedMarkColorRow) {
                DeletedMessagesColorPickerCell colorPickerCell = (DeletedMessagesColorPickerCell) holder.itemView;
                colorPickerCell.setSelectedColorId(NaConfig.INSTANCE.getDeletedIconColor().Int(), false);
            }
        }
    }
}
