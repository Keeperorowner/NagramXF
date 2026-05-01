package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.os.StatFs;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.radolyn.ayugram.AyuConstants;
import com.radolyn.ayugram.database.AyuData;
import com.radolyn.ayugram.messages.AyuMessagesController;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Cells.MaxFileSizeCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckBoxCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BlurredRecyclerView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SlideChooseView;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

import tw.nekomimi.nekogram.config.CellGroup;
import tw.nekomimi.nekogram.config.cell.AbstractConfigCell;
import tw.nekomimi.nekogram.config.cell.ConfigCellCustom;
import tw.nekomimi.nekogram.config.cell.ConfigCellDivider;
import tw.nekomimi.nekogram.config.cell.ConfigCellHeader;
import tw.nekomimi.nekogram.config.cell.ConfigCellTextCheck;
import tw.nekomimi.nekogram.helpers.AppRestartHelper;
import tw.nekomimi.nekogram.ui.cells.HeaderCell;
import xyz.nextalone.nagram.NaConfig;

@SuppressLint("RtlHardcoded")
@SuppressWarnings("unused")
public class NekoAyuSpySettingsActivity extends BaseNekoXSettingsActivity {

    private static final int REQUEST_CODE_ATTACHMENT_FOLDER = 6969;
    private static final int REQUEST_CODE_IMPORT_DATABASE = 6970;
    private static final int REQUEST_CODE_EXPORT_DATABASE = 6971;
    private static final String CLEAR_TOGGLE_PREFIX = "ayuspy_clear_toggled_";
    private static final int TELEGRAM_DATABASE_RESTART_DELAY_MS = 3000;

    private ListAdapter listAdapter;
    private long totalDeviceSize = -1L;
    private int[] attachmentLimitPresetIndices = new int[0];

    private final CellGroup cellGroup = new CellGroup(this);

    private final AbstractConfigCell headerSpySettings = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.AyuSpySettings)));
    private final AbstractConfigCell saveLastSeenRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getSaveLocalLastSeen()));
    private final AbstractConfigCell enableSaveDeletedMessagesRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getEnableSaveDeletedMessages()));
    private final AbstractConfigCell enableSaveEditsHistoryRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getEnableSaveEditsHistory()));
    private final AbstractConfigCell saveDeletedMessageForBotsUserRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getSaveDeletedMessageForBotUser()));
    private final AbstractConfigCell saveDeletedMessageInBotChatRow = cellGroup.appendCell(new ConfigCellTextCheck(NaConfig.INSTANCE.getSaveDeletedMessageForBot()));
    private final AbstractConfigCell dividerAttachmentsSection = cellGroup.appendCell(new ConfigCellDivider());
    private final AbstractConfigCell saveAttachmentsRow = cellGroup.appendCell(new ConfigCellCustom("SaveAttachmentsRow", CellGroup.ITEM_TYPE_TEXT_CHECK, true));
    private final AbstractConfigCell attachmentFolderRow = cellGroup.appendCell(new ConfigCellCustom("AttachmentFolderRow", CellGroup.ITEM_TYPE_TEXT_SETTINGS_CELL, true));
    private final AbstractConfigCell dividerAttachmentLimit = cellGroup.appendCell(new ConfigCellDivider());
    private final AbstractConfigCell attachmentLimitHeaderRow = cellGroup.appendCell(new ConfigCellCustom("AttachmentFolderSizeLimitHeader", CellGroup.ITEM_TYPE_HEADER, false));
    private final AbstractConfigCell attachmentLimitSliderRow = cellGroup.appendCell(new ConfigCellCustom("AttachmentFolderSizeLimitSlider", ConfigCellCustom.CUSTOM_ITEM_AttachmentSizeLimit, false));
    private final AbstractConfigCell attachmentLimitInfoRow = cellGroup.appendCell(new ConfigCellCustom("AttachmentFolderSizeLimitInfo", CellGroup.ITEM_TYPE_TEXT, false));
    private final AbstractConfigCell exportDatabaseRow = cellGroup.appendCell(new ConfigCellCustom("ExportDatabaseRow", CellGroup.ITEM_TYPE_TEXT_CHECK_ICON, true));
    private final AbstractConfigCell importDatabaseRow = cellGroup.appendCell(new ConfigCellCustom("ImportDatabaseRow", CellGroup.ITEM_TYPE_TEXT_CHECK_ICON, true));
    private final AbstractConfigCell dividerClearData = cellGroup.appendCell(new ConfigCellDivider());
    private final AbstractConfigCell clearDataRow = cellGroup.appendCell(new ConfigCellCustom("ClearSavedDataRow", CellGroup.ITEM_TYPE_TEXT_CHECK_ICON, true));

    public NekoAyuSpySettingsActivity() {
        checkSaveBotMsgRows();
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
        return "ayuspy";
    }

    @Override
    protected void styleTextInfoPrivacyCell(TextInfoPrivacyCell cell) {
        cell.setBackground(Theme.getThemedDrawable(cell.getContext(), R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
    }

    @Override
    public boolean onFragmentCreate() {
        calculateTotalDeviceSize();
        super.onFragmentCreate();
        AyuData.loadSizes(this::refreshAyuDataSize);
        return true;
    }

    @Override
    protected BlurredRecyclerView createListView(Context context) {
        return new BlurredRecyclerView(context) {
            @Override
            public Integer getSelectorColor(int position) {
                if (position == cellGroup.rows.indexOf(clearDataRow)) {
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
            if (key.equals(NaConfig.INSTANCE.getSaveDeletedMessageForBotUser().getKey())) {
                checkSaveBotMsgRows();
            }
        };

        return superView;
    }

    @Override
    protected void onCustomCellClick(View view, int position, float x, float y) {
        if (position == cellGroup.rows.indexOf(saveAttachmentsRow)) {
            if (isSwitchTap(view, x)) {
                NaConfig.INSTANCE.getMessageSavingSaveMedia().toggleConfigBool();
                ((TextCheckCell) view).setChecked(NaConfig.INSTANCE.getMessageSavingSaveMedia().Bool());
            } else if (NaConfig.INSTANCE.getMessageSavingSaveMedia().Bool()) {
                showBottomSheet();
            }
            return;
        }
        if (position == cellGroup.rows.indexOf(attachmentFolderRow)) {
            openAttachmentFolderPicker();
            return;
        }
        if (position == cellGroup.rows.indexOf(exportDatabaseRow)) {
            openExportDatabasePicker();
            return;
        }
        if (position == cellGroup.rows.indexOf(importDatabaseRow)) {
            openImportDatabasePicker();
            return;
        }
        if (position == cellGroup.rows.indexOf(clearDataRow)) {
            showClearSavedDataDialog();
            return;
        }
        super.onCustomCellClick(view, position, x, y);
    }

    @Override
    public int getBaseGuid() {
        return 15100;
    }

    @Override
    public int getDrawable() {
        return R.drawable.msg_secret_solar;
    }

    @Override
    public String getTitle() {
        return getString(R.string.AyuSpySettings);
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }

        Uri uri = data.getData();
        if (requestCode == REQUEST_CODE_ATTACHMENT_FOLDER) {
            applyAttachmentFolderSelection(uri);
        } else if (requestCode == REQUEST_CODE_EXPORT_DATABASE) {
            runDatabaseExport(uri);
        } else if (requestCode == REQUEST_CODE_IMPORT_DATABASE) {
            confirmImportDatabase(uri);
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> themeDescriptions = super.getThemeDescriptions();
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR, new Class[]{TextCell.class, SlideChooseView.class}, null, null, null, Theme.key_windowBackgroundWhite));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteValueText));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCell.class}, new String[]{"imageView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayIcon));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextInfoPrivacyCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText4));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{SlideChooseView.class}, null, null, null, Theme.key_switchTrack));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{SlideChooseView.class}, null, null, null, Theme.key_switchTrackChecked));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{SlideChooseView.class}, null, null, null, Theme.key_windowBackgroundWhiteGrayText));
        return themeDescriptions;
    }

    public void refreshAyuDataSize() {
        notifyRowChanged(clearDataRow);
    }

    private boolean isSwitchTap(View view, float x) {
        return LocaleController.isRTL ? x <= AndroidUtilities.dp(76) : x >= view.getMeasuredWidth() - AndroidUtilities.dp(76);
    }

    private void calculateTotalDeviceSize() {
        try {
            ArrayList<File> rootDirs = AndroidUtilities.getRootDirs();
            if (rootDirs == null || rootDirs.isEmpty()) {
                return;
            }
            File selectedRoot = rootDirs.get(0);
            if (!TextUtils.isEmpty(SharedConfig.storageCacheDir)) {
                for (File rootDir : rootDirs) {
                    if (rootDir != null && SharedConfig.storageCacheDir.startsWith(rootDir.getAbsolutePath())) {
                        selectedRoot = rootDir;
                        break;
                    }
                }
            }
            StatFs statFs = new StatFs(selectedRoot.getPath());
            totalDeviceSize = statFs.getBlockCountLong() * statFs.getBlockSizeLong();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void applyAttachmentFolderSelection(Uri uri) {
        try {
            File folder = resolveTreeUriToFile(uri);
            if (folder == null) {
                throw new IOException("Unable to resolve selected tree uri");
            }
            AyuMessagesController.setAttachmentFolderPath(folder);
            AyuData.loadSizes(this::refreshAyuDataSize);
            notifyRowChanged(attachmentFolderRow);
            BulletinFactory.of(this).createSimpleBulletin(R.raw.done, getString(R.string.AttachmentFolderUpdated)).show();
        } catch (Exception e) {
            FileLog.e(e);
            BulletinFactory.of(this).createErrorBulletin(getString(R.string.AttachmentFolderSelectionFailed)).show();
        }
    }

    private void openAttachmentFolderPicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addCategory(Intent.CATEGORY_DEFAULT);
            startActivityForResult(Intent.createChooser(intent, getString(R.string.AttachmentFolder)), REQUEST_CODE_ATTACHMENT_FOLDER);
        } catch (Exception e) {
            FileLog.e(e);
            BulletinFactory.of(this).createErrorBulletin(getString(R.string.AttachmentFolderSelectionFailed)).show();
        }
    }

    private void openExportDatabasePicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/octet-stream");
            intent.putExtra(Intent.EXTRA_TITLE, AyuConstants.AYU_DATABASE);
            startActivityForResult(Intent.createChooser(intent, getString(R.string.ExportMessageDatabase)), REQUEST_CODE_EXPORT_DATABASE);
        } catch (Exception e) {
            FileLog.e(e);
            BulletinFactory.of(this).createErrorBulletin(getString(R.string.ExportMessageDatabaseFailed)).show();
        }
    }

    private void openImportDatabasePicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(Intent.createChooser(intent, getString(R.string.ImportMessageDatabase)), REQUEST_CODE_IMPORT_DATABASE);
        } catch (Exception e) {
            FileLog.e(e);
            BulletinFactory.of(this).createErrorBulletin(getString(R.string.ImportMessageDatabaseFailed)).show();
        }
    }

    private void confirmImportDatabase(Uri uri) {
        Context context = getParentActivity();
        if (context == null || uri == null) {
            return;
        }
        new AlertDialog.Builder(context, getResourceProvider())
                .setTitle(getString(R.string.ImportMessageDatabase))
                .setMessage(getString(R.string.ImportMessageDatabaseConfirm))
                .setPositiveButton(getString(R.string.Import), (dialog, which) -> runDatabaseImport(uri))
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
    }

    private void runDatabaseExport(Uri uri) {
        Context context = getParentActivity();
        if (context == null || uri == null) {
            return;
        }
        AlertDialog progressDialog = new AlertDialog(context, AlertDialog.ALERT_TYPE_SPINNER);
        progressDialog.setCanCancel(false);
        progressDialog.show();
        Utilities.globalQueue.postRunnable(() -> {
            boolean success = false;
            try (OutputStream outputStream = context.getContentResolver().openOutputStream(uri)) {
                if (outputStream != null) {
                    AyuData.exportDatabase(outputStream);
                    success = true;
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            boolean finalSuccess = success;
            AndroidUtilities.runOnUIThread(() -> {
                progressDialog.dismiss();
                if (finalSuccess) {
                    AyuData.loadSizes(this::refreshAyuDataSize);
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.done, getString(R.string.ExportMessageDatabaseNotification)).show();
                } else {
                    BulletinFactory.of(this).createErrorBulletin(getString(R.string.ExportMessageDatabaseFailed)).show();
                }
            });
        });
    }

    private void runDatabaseImport(Uri uri) {
        Context context = getParentActivity();
        if (context == null || uri == null) {
            return;
        }
        AlertDialog progressDialog = new AlertDialog(context, AlertDialog.ALERT_TYPE_SPINNER);
        progressDialog.setCanCancel(false);
        progressDialog.show();
        Utilities.globalQueue.postRunnable(() -> {
            boolean success = false;
            try (InputStream inputStream = context.getContentResolver().openInputStream(uri)) {
                if (inputStream != null) {
                    AyuData.importDatabase(inputStream);
                    success = true;
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            boolean finalSuccess = success;
            AndroidUtilities.runOnUIThread(() -> {
                progressDialog.dismiss();
                if (finalSuccess) {
                    AyuData.loadSizes(this::refreshAyuDataSize);
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.done, getString(R.string.ImportMessageDatabaseNotification)).show();
                } else {
                    BulletinFactory.of(this).createErrorBulletin(getString(R.string.ImportMessageDatabaseFailed)).show();
                }
            });
        });
    }

    private void showClearSavedDataDialog() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        BottomSheet.Builder builder = new BottomSheet.Builder(context);
        builder.setApplyTopPadding(false);
        builder.setApplyBottomPadding(false);

        SharedPreferences preferences = getClearPreferences();
        long attachmentsSize = AyuData.getAttachmentsDirSize();
        long ayuDatabaseSize = AyuData.getAyuDatabaseSize();
        long telegramDatabaseSize = getMessagesStorage().getDatabaseSize();

        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        builder.setCustomView(linearLayout);

        HeaderCell headerCell = new HeaderCell(context, Theme.key_dialogTextBlue2, 21, 15, false);
        headerCell.setText(getString(R.string.ClearSavedMessageData).toUpperCase());
        linearLayout.addView(headerCell, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView infoView = new TextView(context);
        infoView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        infoView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        infoView.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(6), AndroidUtilities.dp(21), AndroidUtilities.dp(6));
        infoView.setText(getString(R.string.ClearSavedMessageDataDescription));
        linearLayout.addView(infoView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        CheckBoxCell[] cells = new CheckBoxCell[3];
        String[] titles = new String[]{
                getString(R.string.AyuAttachments),
                getString(R.string.AyuDatabase),
                getString(R.string.TelegramLocalDatabase)
        };
        String[] values = new String[]{
                AndroidUtilities.formatFileSize(Math.max(attachmentsSize, 0L)),
                AndroidUtilities.formatFileSize(Math.max(ayuDatabaseSize, 0L)),
                AndroidUtilities.formatFileSize(Math.max(telegramDatabaseSize, 0L))
        };

        TextView[] clearActionHolder = new TextView[1];
        for (int i = 0; i < cells.length; i++) {
            CheckBoxCell checkBoxCell = new CheckBoxCell(context, CheckBoxCell.TYPE_CHECK_BOX_ROUND, 21, getResourceProvider());
            checkBoxCell.setBackground(Theme.getSelectorDrawable(false));
            checkBoxCell.setText(titles[i], values[i], true, i != cells.length - 1);
            checkBoxCell.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
            checkBoxCell.setCheckBoxColor(Theme.key_radioBackgroundChecked, Theme.key_windowBackgroundWhiteGrayIcon, Theme.key_checkboxCheck);
            checkBoxCell.setChecked(preferences.getBoolean(CLEAR_TOGGLE_PREFIX + i, false), false);
            final int index = i;
            checkBoxCell.setOnClickListener(v -> {
                CheckBoxCell cell = (CheckBoxCell) v;
                boolean checked = !cell.isChecked();
                cell.setChecked(checked, true);
                preferences.edit().putBoolean(CLEAR_TOGGLE_PREFIX + index, checked).apply();
                updateClearActionButton(clearActionHolder[0], cells);
            });
            cells[i] = checkBoxCell;
            linearLayout.addView(checkBoxCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));
        }

        FrameLayout buttonsLayout = new FrameLayout(context);
        buttonsLayout.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8));
        linearLayout.addView(buttonsLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52));

        TextView cancelView = createBottomSheetAction(context, getString(R.string.Cancel), Theme.key_dialogTextBlue2);
        buttonsLayout.addView(cancelView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 36, Gravity.TOP | Gravity.LEFT));
        cancelView.setOnClickListener(v -> builder.getDismissRunnable().run());

        TextView clearView = createBottomSheetAction(context, getString(R.string.Clear), Theme.key_text_RedRegular);
        clearActionHolder[0] = clearView;
        buttonsLayout.addView(clearView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 36, Gravity.TOP | Gravity.RIGHT));
        clearView.setOnClickListener(v -> {
            if (!hasAnyClearOptionSelected(cells)) {
                return;
            }
            builder.getDismissRunnable().run();
            clearSavedData(cells[0].isChecked(), cells[1].isChecked(), cells[2].isChecked());
        });
        updateClearActionButton(clearView, cells);

        showDialog(builder.create());
    }

    private void clearSavedData(boolean clearAttachments, boolean clearAyuDatabase, boolean clearTelegramDatabase) {
        if (!clearAttachments && !clearAyuDatabase && !clearTelegramDatabase) {
            return;
        }

        Context context = getParentActivity();
        if (context == null) {
            return;
        }

        AlertDialog progressDialog = new AlertDialog(context, AlertDialog.ALERT_TYPE_SPINNER);
        progressDialog.setCanCancel(false);
        progressDialog.show();
        Utilities.globalQueue.postRunnable(() -> {
            if (clearAttachments) {
                AyuMessagesController.clearAttachments();
            }
            if (clearAyuDatabase) {
                AyuMessagesController.clearDatabase();
            }
            AndroidUtilities.runOnUIThread(() -> {
                progressDialog.dismiss();
                if (clearTelegramDatabase) {
                    getMessagesStorage().clearLocalDatabase(false, this::showTelegramDatabaseRestartCountdown);
                    return;
                }
                AyuData.loadSizes(this::refreshAyuDataSize);
                BulletinFactory.of(this).createSimpleBulletin(R.raw.done, getString(R.string.Done)).show();
            });
        });
    }

    private void showTelegramDatabaseRestartCountdown() {
        int restartDelaySeconds = TELEGRAM_DATABASE_RESTART_DELAY_MS / 1000;
        BulletinFactory bulletinFactory = BulletinFactory.canShowBulletin(this) ? BulletinFactory.of(this) : BulletinFactory.global();
        bulletinFactory.createSimpleBulletin(
                R.raw.info,
                LocaleController.formatString(R.string.AppWillRestartInSeconds, restartDelaySeconds),
                4,
                TELEGRAM_DATABASE_RESTART_DELAY_MS
        ).show();
        AndroidUtilities.runOnUIThread(
                () -> AppRestartHelper.triggerRebirth(
                        ApplicationLoader.applicationContext,
                        new Intent(ApplicationLoader.applicationContext, LaunchActivity.class)
                ),
                TELEGRAM_DATABASE_RESTART_DELAY_MS
        );
    }

    private TextView createBottomSheetAction(Context context, String text, int colorKey) {
        TextView actionView = new TextView(context);
        actionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        actionView.setTextColor(Theme.getColor(colorKey));
        actionView.setGravity(Gravity.CENTER);
        actionView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        actionView.setText(text.toUpperCase());
        actionView.setPadding(AndroidUtilities.dp(10), 0, AndroidUtilities.dp(10), 0);
        return actionView;
    }

    private SharedPreferences getClearPreferences() {
        return MessagesController.getGlobalMainSettings();
    }

    private boolean hasAnyClearOptionSelected(CheckBoxCell[] cells) {
        for (CheckBoxCell cell : cells) {
            if (cell != null && cell.isChecked()) {
                return true;
            }
        }
        return false;
    }

    private void updateClearActionButton(TextView clearView, CheckBoxCell[] cells) {
        if (clearView == null) {
            return;
        }
        boolean enabled = hasAnyClearOptionSelected(cells);
        clearView.setEnabled(enabled);
        clearView.setAlpha(enabled ? 1.0f : 0.5f);
    }

    private File resolveTreeUriToFile(Uri treeUri) {
        if (treeUri == null) {
            return null;
        }
        String treeDocumentId;
        try {
            treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
        if (TextUtils.isEmpty(treeDocumentId)) {
            return null;
        }
        String[] split = treeDocumentId.split(":", 2);
        String volume = split[0];
        String relativePath = split.length > 1 ? split[1] : "";
        if ("primary".equalsIgnoreCase(volume)) {
            File baseDir = Environment.getExternalStorageDirectory();
            return TextUtils.isEmpty(relativePath) ? baseDir : new File(baseDir, relativePath);
        }
        if ("home".equalsIgnoreCase(volume)) {
            File baseDir = new File(Environment.getExternalStorageDirectory(), "Documents");
            return TextUtils.isEmpty(relativePath) ? baseDir : new File(baseDir, relativePath);
        }
        File directPath = new File("/storage/" + treeDocumentId.replace(':', '/'));
        if (directPath.exists()) {
            return directPath;
        }
        File baseDir = resolveVolumeBaseDir(volume);
        if (baseDir == null) {
            return null;
        }
        return TextUtils.isEmpty(relativePath) ? baseDir : new File(baseDir, relativePath);
    }

    private File resolveVolumeBaseDir(String volume) {
        if (TextUtils.isEmpty(volume)) {
            return null;
        }
        if ("primary".equalsIgnoreCase(volume)) {
            return Environment.getExternalStorageDirectory();
        }
        ArrayList<File> rootDirs = AndroidUtilities.getRootDirs();
        if (rootDirs != null) {
            for (File rootDir : rootDirs) {
                if (rootDir != null && volume.equalsIgnoreCase(rootDir.getName())) {
                    return rootDir;
                }
            }
        }
        File fallback = new File("/storage/" + volume);
        return fallback.exists() && fallback.isDirectory() ? fallback : null;
    }

    private int[] getVisibleAttachmentLimitPresets() {
        ArrayList<Integer> options = new ArrayList<>();
        options.add(0);
        options.add(1);
        options.add(2);
        if (totalDeviceSize > 0) {
            float totalSizeInGb = (int) (totalDeviceSize / 1024L / 1024L) / 1000.0f;
            if (totalSizeInGb > 5.0f) {
                options.add(3);
            }
            if (totalSizeInGb > 16.0f) {
                options.add(4);
            }
        }
        options.add(5);
        int[] presetIndices = new int[options.size()];
        for (int i = 0; i < options.size(); i++) {
            presetIndices[i] = options.get(i);
        }
        return presetIndices;
    }

    private String[] getVisibleAttachmentLimitLabels(int[] presetIndices) {
        String[] labels = new String[presetIndices.length];
        for (int i = 0; i < presetIndices.length; i++) {
            labels[i] = getAttachmentLimitLabel(presetIndices[i]);
        }
        return labels;
    }

    private String getAttachmentLimitLabel(int presetIndex) {
        if (presetIndex == 0) {
            return "300 MB";
        } else if (presetIndex == 1) {
            return "1 GB";
        } else if (presetIndex == 2) {
            return "2 GB";
        } else if (presetIndex == 3) {
            return "5 GB";
        } else if (presetIndex == 4) {
            return "16 GB";
        } else {
            return getString(R.string.AttachmentFolderSizeLimitUnlimited);
        }
    }

    private int getSelectedAttachmentLimitOption(int[] presetIndices) {
        int currentPreset = AyuMessagesController.clampAttachmentSizeLimitPreset(NaConfig.INSTANCE.getAttachmentFolderSizeLimitPreset().Int());
        for (int i = 0; i < presetIndices.length; i++) {
            if (presetIndices[i] == currentPreset) {
                return i;
            }
        }
        return presetIndices.length - 1;
    }

    private String getAttachmentFolderDisplayName() {
        AyuMessagesController.syncAttachmentsPathWithConfig();
        File path = AyuMessagesController.attachmentsPath;
        if (path == null) {
            return AyuMessagesController.attachmentsSubfolder;
        }
        String name = path.getName();
        return TextUtils.isEmpty(name) ? path.getAbsolutePath() : name;
    }

    private String getClearValueText() {
        return AyuData.totalSize > 0 ? AndroidUtilities.formatFileSize(AyuData.totalSize) : "...";
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

        MaxFileSizeCell[] sizeCells = new MaxFileSizeCell[2];
        for (int a = 0; a < sizeCells.length; a++) {
            MaxFileSizeCell sizeCell = sizeCells[a] = new MaxFileSizeCell(getParentActivity());
            sizeCell.setSliderStyleOverride(org.telegram.ui.Components.SeekBarView.SLIDER_STYLE_DEFAULT);
            if (a == 0) {
                sizeCell.setText(getString(R.string.MaximumMediaSizeCellular));
                sizeCell.setSize(NaConfig.INSTANCE.getSaveMediaOnCellularDataLimit().Long());
            } else {
                sizeCell.setText(getString(R.string.MaximumMediaSizeWiFi));
                sizeCell.setSize(NaConfig.INSTANCE.getSaveMediaOnWiFiLimit().Long());
            }
            linearLayout.addView(sizeCell, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 80));
        }

        FrameLayout buttonsLayout = new FrameLayout(getParentActivity());
        buttonsLayout.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8));
        linearLayout.addView(buttonsLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52));

        TextView cancelView = new TextView(getParentActivity());
        cancelView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        cancelView.setTextColor(Theme.getColor(Theme.key_dialogTextBlue2));
        cancelView.setGravity(Gravity.CENTER);
        cancelView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        cancelView.setText(getString(R.string.Cancel).toUpperCase());
        cancelView.setPadding(AndroidUtilities.dp(10), 0, AndroidUtilities.dp(10), 0);
        buttonsLayout.addView(cancelView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 36, Gravity.TOP | Gravity.LEFT));
        cancelView.setOnClickListener(v -> builder.getDismissRunnable().run());

        TextView saveView = new TextView(getParentActivity());
        saveView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        saveView.setTextColor(Theme.getColor(Theme.key_dialogTextBlue2));
        saveView.setGravity(Gravity.CENTER);
        saveView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        saveView.setText(getString(R.string.Save).toUpperCase());
        saveView.setPadding(AndroidUtilities.dp(10), 0, AndroidUtilities.dp(10), 0);
        buttonsLayout.addView(saveView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 36, Gravity.TOP | Gravity.RIGHT));
        saveView.setOnClickListener(v -> {
            NaConfig.INSTANCE.getSaveMediaInPrivateChats().setConfigBool(cells[0].isChecked());
            NaConfig.INSTANCE.getSaveMediaInPublicChannels().setConfigBool(cells[1].isChecked());
            NaConfig.INSTANCE.getSaveMediaInPrivateChannels().setConfigBool(cells[2].isChecked());
            NaConfig.INSTANCE.getSaveMediaInPublicGroups().setConfigBool(cells[3].isChecked());
            NaConfig.INSTANCE.getSaveMediaInPrivateGroups().setConfigBool(cells[4].isChecked());
            NaConfig.INSTANCE.getSaveMediaOnCellularDataLimit().setConfigLong(sizeCells[0].getSize());
            NaConfig.INSTANCE.getSaveMediaOnWiFiLimit().setConfigLong(sizeCells[1].getSize());
            builder.getDismissRunnable().run();
        });
        showDialog(builder.create());
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
            int index = cellGroup.rows.indexOf(saveDeletedMessageForBotsUserRow);
            if (index >= 0 && !cellGroup.rows.contains(saveDeletedMessageInBotChatRow)) {
                cellGroup.rows.add(index + 1, saveDeletedMessageInBotChatRow);
                listAdapter.notifyItemInserted(index + 1);
            }
        } else {
            int index = cellGroup.rows.indexOf(saveDeletedMessageInBotChatRow);
            if (index != -1) {
                cellGroup.rows.remove(saveDeletedMessageInBotChatRow);
                listAdapter.notifyItemRemoved(index);
            }
        }
        addRowsToMap(cellGroup);
    }

    private class ListAdapter extends BaseListAdapter {

        public ListAdapter(Context context) {
            super(context);
        }

        @Override
        protected View onCreateCustomViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == ConfigCellCustom.CUSTOM_ITEM_AttachmentSizeLimit) {
                SlideChooseView slideChooseView = new SlideChooseView(mContext);
                slideChooseView.setCallback(index -> {
                    if (index < 0 || index >= attachmentLimitPresetIndices.length) {
                        return;
                    }
                    NaConfig.INSTANCE.getAttachmentFolderSizeLimitPreset().setConfigInt(attachmentLimitPresetIndices[index]);
                    AyuMessagesController.trimAttachmentsFolderToLimit();
                    AyuData.loadSizes(NekoAyuSpySettingsActivity.this::refreshAyuDataSize);
                });
                return slideChooseView;
            }
            return null;
        }

        @Override
        protected void onBindCustomViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            AbstractConfigCell row = cellGroup.rows.get(position);

            if (row == saveAttachmentsRow) {
                TextCheckCell textCheckCell = (TextCheckCell) holder.itemView;
                textCheckCell.setTextAndValueAndCheck(
                        getString(R.string.MessageSavingSaveMedia),
                        getString(R.string.MessageSavingSaveMediaHint),
                        NaConfig.INSTANCE.getMessageSavingSaveMedia().Bool(),
                        true,
                        true,
                        true
                );
            } else if (row == attachmentFolderRow) {
                TextSettingsCell textSettingsCell = (TextSettingsCell) holder.itemView;
                textSettingsCell.setCanDisable(true);
                textSettingsCell.setTextAndValue(getString(R.string.AttachmentFolder), getAttachmentFolderDisplayName(), false);
            } else if (row == attachmentLimitHeaderRow) {
                HeaderCell headerCell = (HeaderCell) holder.itemView;
                headerCell.setText(getString(R.string.AttachmentFolderSizeLimit));
            } else if (row == attachmentLimitSliderRow) {
                SlideChooseView slideChooseView = (SlideChooseView) holder.itemView;
                attachmentLimitPresetIndices = getVisibleAttachmentLimitPresets();
                slideChooseView.setOptions(
                        getSelectedAttachmentLimitOption(attachmentLimitPresetIndices),
                        getVisibleAttachmentLimitLabels(attachmentLimitPresetIndices)
                );
            } else if (row == attachmentLimitInfoRow) {
                TextInfoPrivacyCell textInfoPrivacyCell = (TextInfoPrivacyCell) holder.itemView;
                textInfoPrivacyCell.setText(getString(R.string.AttachmentFolderSizeLimitInfo));
            } else if (row == exportDatabaseRow) {
                TextCell textCell = (TextCell) holder.itemView;
                textCell.setTextAndIcon(getString(R.string.ExportMessageDatabase), R.drawable.msg_unarchive_solar, true);
                textCell.setColors(Theme.key_windowBackgroundWhiteGrayIcon, Theme.key_windowBackgroundWhiteBlackText);
            } else if (row == importDatabaseRow) {
                TextCell textCell = (TextCell) holder.itemView;
                textCell.setTextAndIcon(getString(R.string.ImportMessageDatabase), R.drawable.msg_archive_solar, false);
                textCell.setColors(Theme.key_windowBackgroundWhiteGrayIcon, Theme.key_windowBackgroundWhiteBlackText);
            } else if (row == clearDataRow) {
                TextCell textCell = (TextCell) holder.itemView;
                textCell.setTextAndValueAndIcon(getString(R.string.ClearSavedMessageData), getClearValueText(), R.drawable.msg_clear_solar, false);
                textCell.setColors(Theme.key_text_RedRegular, Theme.key_text_RedRegular);
            }
        }
    }
}
