package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.radolyn.ayugram.database.AyuData;
import com.radolyn.ayugram.messages.AyuMessagesController;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckBoxCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import tw.nekomimi.nekogram.config.CellGroup;
import tw.nekomimi.nekogram.config.cell.AbstractConfigCell;
import tw.nekomimi.nekogram.config.cell.ConfigCellDivider;
import tw.nekomimi.nekogram.config.cell.ConfigCellHeader;
import tw.nekomimi.nekogram.config.cell.ConfigCellText;
import tw.nekomimi.nekogram.config.cell.ConfigCellTextCheck;
import tw.nekomimi.nekogram.config.cell.ConfigCellTextCheckIcon;
import tw.nekomimi.nekogram.config.cell.ConfigCellTextInput;
import tw.nekomimi.nekogram.filters.RegexFiltersSettingActivity;
import tw.nekomimi.nekogram.ui.cells.HeaderCell;
import xyz.nextalone.nagram.NaConfig;

@SuppressLint("RtlHardcoded")
@SuppressWarnings("unused")
public class NekoAyuMomentsSettingsActivity extends BaseNekoXSettingsActivity {

    private ListAdapter listAdapter;

    private final CellGroup cellGroup = new CellGroup(this);

    private final AbstractConfigCell headerAyuMoments = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.AyuMoments)));
    private final AbstractConfigCell ghostModeRow = cellGroup.appendCell(new ConfigCellText("GhostMode", () -> presentFragment(new GhostModeActivity())));
    private final AbstractConfigCell regexFiltersEnabledRow = cellGroup.appendCell(new ConfigCellText("RegexFilters", () -> presentFragment(new RegexFiltersSettingActivity())));
    private final AbstractConfigCell saveLastSeenRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getSaveLocalLastSeen()));
    private final AbstractConfigCell enableSaveDeletedMessagesRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getEnableSaveDeletedMessages()));
    private final AbstractConfigCell enableSaveEditsHistoryRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getEnableSaveEditsHistory()));
    private final AbstractConfigCell messageSavingSaveMediaRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getMessageSavingSaveMedia(), getString(R.string.MessageSavingSaveMediaHint)));
    private final AbstractConfigCell saveDeletedMessageForBotsUserRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getSaveDeletedMessageForBotUser()));
    private final AbstractConfigCell saveDeletedMessageInBotChatRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getSaveDeletedMessageForBot()));
    private final AbstractConfigCell translucentDeletedMessagesRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getTranslucentDeletedMessages()));
    private final AbstractConfigCell useDeletedIconRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getUseDeletedIcon()));
    private final AbstractConfigCell customDeletedMarkRow = cellGroup.appendCell(new ConfigCellTextInput(null, NaConfig.INSTANCE.getCustomDeletedMark(), "", null));
    private final AbstractConfigCell clearMessageDatabaseRow = cellGroup.appendCell(new ConfigCellTextCheckIcon(null, "ClearMessageDatabase", null, AyuData.totalSize > 0 ? AndroidUtilities.formatFileSize(AyuData.totalSize) : "...", R.drawable.msg_clear, false, () -> new AlertDialog.Builder(getContext(), getResourceProvider())
            .setTitle(getString(R.string.ClearMessageDatabase))
            .setMessage(getString(R.string.AreYouSure))
            .setPositiveButton(getString(R.string.Clear), (dialog, which) -> {
                AlertDialog progressDialog = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
                progressDialog.setCanCancel(false);
                progressDialog.show();
                Utilities.globalQueue.postRunnable(() -> {
                    AyuMessagesController.getInstance().clean();
                    AndroidUtilities.runOnUIThread(() -> {
                        progressDialog.dismiss();
                        BulletinFactory.of(this).createSimpleBulletin(R.raw.done, getString(R.string.ClearMessageDatabaseNotification)).show();
                    });
                    AyuData.loadSizes(this::refreshAyuDataSize);
                });
            })
            .setNegativeButton(getString(R.string.Cancel), (d, w) -> d.dismiss())
            .makeRed(AlertDialog.BUTTON_POSITIVE)
            .show()));
    private final AbstractConfigCell dividerAyuMoments = cellGroup.appendCell(new ConfigCellDivider());

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

    public NekoAyuMomentsSettingsActivity() {
        if (NaConfig.INSTANCE.getUseDeletedIcon().Bool()) {
            cellGroup.rows.remove(customDeletedMarkRow);
        }
        if (!NaConfig.INSTANCE.getSaveDeletedMessageForBotUser().Bool()) {
            cellGroup.rows.remove(saveDeletedMessageInBotChatRow);
        }
        checkUseDeletedIconRows();
        checkSaveBotMsgRows();
        checkSaveDeletedRows();
        addRowsToMap(cellGroup);
    }

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        AyuData.loadSizes(this::refreshAyuDataSize);
        return true;
    }

    @Override
    protected org.telegram.ui.Components.BlurredRecyclerView createListView(Context context) {
        return new org.telegram.ui.Components.BlurredRecyclerView(context) {
            @Override
            public Integer getSelectorColor(int position) {
                if (position == cellGroup.rows.indexOf(clearMessageDatabaseRow)) {
                    return Theme.multAlpha(getThemedColor(Theme.key_text_RedRegular), .1f);
                }
                return getThemedColor(Theme.key_listSelector);
            }
        };
    }

    @Override
    public View createView(Context context) {
        View superView = super.createView(context);

        listAdapter = new ListAdapter(context);
        listView.setAdapter(listAdapter);
        setupDefaultListeners();

        cellGroup.callBackSettingsChanged = (key, newValue) -> {
            if (key.equals(NaConfig.INSTANCE.getEnableSaveDeletedMessages().getKey())) {
                checkSaveDeletedRows();
            } else if (key.equals(NaConfig.INSTANCE.getUseDeletedIcon().getKey())) {
                checkUseDeletedIconRows();
            } else if (key.equals(NaConfig.INSTANCE.getSaveDeletedMessageForBotUser().getKey())) {
                checkSaveBotMsgRows();
            }
        };

        return superView;
    }

    @Override
    protected void handleCellClick(View view, int position, float x, float y) {
        if (position < 0 || position >= cellGroup.rows.size()) {
            return;
        }
        AbstractConfigCell a = cellGroup.rows.get(position);
        if (a instanceof ConfigCellTextCheck) {
            if (position == cellGroup.rows.indexOf(messageSavingSaveMediaRow) && (LocaleController.isRTL && x > AndroidUtilities.dp(76) || !LocaleController.isRTL && x < (view.getMeasuredWidth() - AndroidUtilities.dp(76)))) {
                showBottomSheet();
                return;
            }
        }
        super.handleCellClick(view, position, x, y);
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

    private class ListAdapter extends BaseListAdapter {

        public ListAdapter(Context context) {
            super(context);
        }

        @Override
        protected void onBindDefaultViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            CellGroup currentCellGroup = getCellGroup();
            if (currentCellGroup == null) {
                return;
            }
            AbstractConfigCell a = currentCellGroup.rows.get(position);
            if (a instanceof ConfigCellTextCheckIcon && holder.itemView instanceof TextCell textCell) {
                if (position == currentCellGroup.rows.indexOf(clearMessageDatabaseRow)) {
                    textCell.setColors(Theme.key_text_RedRegular, Theme.key_text_RedRegular);
                }
            }
        }
    }

    public void refreshAyuDataSize() {
        if (listAdapter != null) {
            ((ConfigCellTextCheckIcon) clearMessageDatabaseRow).setValue(AyuData.totalSize > 0 ? AndroidUtilities.formatFileSize(AyuData.totalSize) : "...");
            listAdapter.notifyItemChanged(cellGroup.rows.indexOf(clearMessageDatabaseRow));
        }
    }

    private void showBottomSheet() {
        if (getParentActivity() == null) {
            return;
        }
        BottomSheet.Builder builder = new BottomSheet.Builder(getParentActivity());
        builder.setApplyTopPadding(false);
        builder.setApplyBottomPadding(false);
        LinearLayout linearLayout = new LinearLayout(getParentActivity());
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        builder.setCustomView(linearLayout);

        HeaderCell headerCell = new HeaderCell(getParentActivity(), Theme.key_dialogTextBlue2, 21, 15, false);
        headerCell.setText(getString(R.string.MessageSavingSaveMedia).toUpperCase());
        linearLayout.addView(headerCell, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextCheckBoxCell[] cells = new TextCheckBoxCell[5];
        for (int a = 0; a < cells.length; a++) {
            TextCheckBoxCell checkBoxCell = cells[a] = new TextCheckBoxCell(getParentActivity(), true, false);
            if (a == 0) {
                cells[a].setTextAndCheck(getString(R.string.MessageSavingSaveMediaInPrivateChats), NaConfig.INSTANCE.getSaveMediaInPrivateChats().Bool(), true);
            } else if (a == 1) {
                cells[a].setTextAndCheck(getString(R.string.MessageSavingSaveMediaInPublicChannels), NaConfig.INSTANCE.getSaveMediaInPublicChannels().Bool(), true);
            } else if (a == 2) {
                cells[a].setTextAndCheck(getString(R.string.MessageSavingSaveMediaInPrivateChannels), NaConfig.INSTANCE.getSaveMediaInPrivateChannels().Bool(), true);
            } else if (a == 3) {
                cells[a].setTextAndCheck(getString(R.string.MessageSavingSaveMediaInPublicGroups), NaConfig.INSTANCE.getSaveMediaInPublicGroups().Bool(), true);
            } else {
                cells[a].setTextAndCheck(getString(R.string.MessageSavingSaveMediaInPrivateGroups), NaConfig.INSTANCE.getSaveMediaInPrivateGroups().Bool(), true);
            }
            cells[a].setBackground(Theme.getSelectorDrawable(false));
            cells[a].setOnClickListener(v -> {
                if (!v.isEnabled()) {
                    return;
                }
                checkBoxCell.setChecked(!checkBoxCell.isChecked());
            });
            linearLayout.addView(cells[a], LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 50));
        }

        FrameLayout buttonsLayout = new FrameLayout(getParentActivity());
        buttonsLayout.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8));
        linearLayout.addView(buttonsLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52));

        TextView textView = new TextView(getParentActivity());
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        textView.setTextColor(Theme.getColor(Theme.key_dialogTextBlue2));
        textView.setGravity(Gravity.CENTER);
        textView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        textView.setText(getString(R.string.Cancel).toUpperCase());
        textView.setPadding(AndroidUtilities.dp(10), 0, AndroidUtilities.dp(10), 0);
        buttonsLayout.addView(textView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 36, Gravity.TOP | Gravity.LEFT));
        textView.setOnClickListener(v14 -> builder.getDismissRunnable().run());

        textView = new TextView(getParentActivity());
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        textView.setTextColor(Theme.getColor(Theme.key_dialogTextBlue2));
        textView.setGravity(Gravity.CENTER);
        textView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        textView.setText(getString(R.string.Save).toUpperCase());
        textView.setPadding(AndroidUtilities.dp(10), 0, AndroidUtilities.dp(10), 0);
        buttonsLayout.addView(textView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 36, Gravity.TOP | Gravity.RIGHT));
        textView.setOnClickListener(v1 -> {
            NaConfig.INSTANCE.getSaveMediaInPrivateChats().setConfigBool(cells[0].isChecked());
            NaConfig.INSTANCE.getSaveMediaInPublicChannels().setConfigBool(cells[1].isChecked());
            NaConfig.INSTANCE.getSaveMediaInPrivateChannels().setConfigBool(cells[2].isChecked());
            NaConfig.INSTANCE.getSaveMediaInPublicGroups().setConfigBool(cells[3].isChecked());
            NaConfig.INSTANCE.getSaveMediaInPrivateGroups().setConfigBool(cells[4].isChecked());
            builder.getDismissRunnable().run();
        });
        showDialog(builder.create());
    }

    private void checkSaveDeletedRows() {
        final boolean isSaveEnabled = NaConfig.INSTANCE.getEnableSaveDeletedMessages().Bool();
        final List<AbstractConfigCell> allManagedRows = Arrays.asList(
                messageSavingSaveMediaRow,
                saveDeletedMessageForBotsUserRow,
                saveDeletedMessageInBotChatRow,
                translucentDeletedMessagesRow,
                useDeletedIconRow,
                customDeletedMarkRow
        );
        if (listAdapter == null) {
            if (!isSaveEnabled) {
                cellGroup.rows.removeAll(allManagedRows);
            }
            return;
        }
        final int anchorIndex = cellGroup.rows.indexOf(enableSaveEditsHistoryRow);
        int firstManagedRowIndex = -1;
        int lastManagedRowIndex = -1;
        for (int i = anchorIndex + 1; i < cellGroup.rows.size(); i++) {
            if (allManagedRows.contains(cellGroup.rows.get(i))) {
                if (firstManagedRowIndex == -1) {
                    firstManagedRowIndex = i;
                }
                lastManagedRowIndex = i;
            }
        }
        if (firstManagedRowIndex != -1) {
            int count = lastManagedRowIndex - firstManagedRowIndex + 1;
            cellGroup.rows.subList(firstManagedRowIndex, lastManagedRowIndex + 1).clear();
            listAdapter.notifyItemRangeRemoved(firstManagedRowIndex, count);
        }
        if (isSaveEnabled) {
            final List<AbstractConfigCell> rowsToAdd = new ArrayList<>();
            rowsToAdd.add(messageSavingSaveMediaRow);
            rowsToAdd.add(saveDeletedMessageForBotsUserRow);
            if (NaConfig.INSTANCE.getSaveDeletedMessageForBotUser().Bool()) {
                rowsToAdd.add(saveDeletedMessageInBotChatRow);
            }
            rowsToAdd.add(translucentDeletedMessagesRow);
            rowsToAdd.add(useDeletedIconRow);
            if (!NaConfig.INSTANCE.getUseDeletedIcon().Bool()) {
                rowsToAdd.add(customDeletedMarkRow);
            }
            cellGroup.rows.addAll(anchorIndex + 1, rowsToAdd);
            listAdapter.notifyItemRangeInserted(anchorIndex + 1, rowsToAdd.size());
        }
        addRowsToMap(cellGroup);
    }

    private void checkSaveBotMsgRows() {
        boolean enabled = NaConfig.INSTANCE.getSaveDeletedMessageForBotUser().Bool();
        if (listAdapter == null) {
            if (!enabled) {
                cellGroup.rows.remove(saveDeletedMessageInBotChatRow);
            }
            return;
        }
        if (enabled) {
            final int index = cellGroup.rows.indexOf(saveDeletedMessageForBotsUserRow);
            if (!cellGroup.rows.contains(saveDeletedMessageInBotChatRow)) {
                cellGroup.rows.add(index + 1, saveDeletedMessageInBotChatRow);
                listAdapter.notifyItemInserted(index + 1);
            }
        } else {
            final int index = cellGroup.rows.indexOf(saveDeletedMessageInBotChatRow);
            if (index != -1) {
                cellGroup.rows.remove(saveDeletedMessageInBotChatRow);
                listAdapter.notifyItemRemoved(index);
            }
        }
        addRowsToMap(cellGroup);
    }

    private void checkUseDeletedIconRows() {
        boolean enabled = NaConfig.INSTANCE.getUseDeletedIcon().Bool();
        if (listAdapter == null) {
            if (enabled) {
                cellGroup.rows.remove(customDeletedMarkRow);
            }
            return;
        }
        if (!enabled) {
            final int index = cellGroup.rows.indexOf(useDeletedIconRow);
            if (!cellGroup.rows.contains(customDeletedMarkRow)) {
                cellGroup.rows.add(index + 1, customDeletedMarkRow);
                listAdapter.notifyItemInserted(index + 1);
            }
        } else {
            final int index = cellGroup.rows.indexOf(customDeletedMarkRow);
            if (index != -1) {
                cellGroup.rows.remove(customDeletedMarkRow);
                listAdapter.notifyItemRemoved(index);
            }
        }
        addRowsToMap(cellGroup);
    }
}
