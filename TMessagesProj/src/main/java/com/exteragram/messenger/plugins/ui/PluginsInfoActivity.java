package com.exteragram.messenger.plugins.ui;

import android.content.Context;
import android.content.res.AssetManager;
import android.text.SpannableString;
import android.view.View;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.plugins.PluginsConstants;
import com.exteragram.messenger.plugins.PluginsController;
import com.exteragram.messenger.plugins.PythonPluginsEngine;
import com.exteragram.messenger.preferences.BasePreferencesActivity;
import com.exteragram.messenger.utils.text.LocaleUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.Cells.NotificationsCheckCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

public class PluginsInfoActivity extends BasePreferencesActivity implements NotificationCenter.NotificationCenterDelegate {
    private static final int ITEM_DEV_MODE = 1;
    private static final int ITEM_COMPACT_VIEW = 2;
    private static final int ITEM_SAFE_MODE = 3;
    private static final int ITEM_DOCUMENTATION = 4;
    private static final int ITEM_TRUSTED = 5;
    private static final int ITEM_INSTALL_SMOKE_TEST = 6;
    private static final int ITEM_DISABLE_ART = 7;
    private static final int ITEM_PYSDK_HEADER = 8;
    private static final int ITEM_PYSDK_AUTO_UPDATE = 9;
    private static final int ITEM_PYSDK_BETA = 10;
    private static final int ITEM_PYSDK_CHECK = 11;
    private static final int ITEM_PYSDK_RESTORE = 12;

    private static final String SMOKE_TEST_ASSET_PATH = "plugins/smoke_test.plugin";
    private Boolean smokeTestAssetAvailable;

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.PluginsEngine);
    }

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.pluginsPySdkInfoChanged);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.pluginsPySdkInfoChanged);
        if (PythonPluginsEngine.Updater.status == PythonPluginsEngine.Updater.STATUS_LATEST) {
            PythonPluginsEngine.Updater.status = PythonPluginsEngine.Updater.STATUS_IDLE;
        }
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        View view = super.createView(context);
        listView.setPadding(0, 0, 0, AndroidUtilities.navigationBarHeight + AndroidUtilities.dp(12));
        return view;
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.Settings)));
        items.add(UItem.asCheck(ITEM_DEV_MODE, LocaleController.getString(R.string.PluginsDevMode), R.drawable.msg_settings).setChecked(ExteraConfig.pluginsDevMode).setEnabled(ExteraConfig.pluginsEngine));
        items.add(UItem.asCheck(ITEM_COMPACT_VIEW, LocaleController.getString(R.string.PluginsCompactView), R.drawable.msg_topics).setChecked(ExteraConfig.pluginsCompactView).setEnabled(ExteraConfig.pluginsEngine));
        items.add(UItem.asCheck(ITEM_DISABLE_ART, LocaleController.getString(R.string.PluginsDisableArt), LocaleController.getString(R.string.PluginsDisableArtInfo))
                .setIcon(R.drawable.msg_link2)
                .setChecked(ExteraConfig.pluginsDisableArtOpts)
                .setEnabled(ExteraConfig.pluginsEngine));
        items.add(UItem.asCheck(ITEM_SAFE_MODE, LocaleController.getString(R.string.PluginsSafeMode), R.drawable.msg_secret).setChecked(ExteraConfig.pluginsSafeMode));
        items.add(UItem.asShadow(LocaleController.getString(R.string.PluginsSafeModeInfo2)));

        items.add(UItem.asAnimatedHeader(ITEM_PYSDK_HEADER, "Python SDK"));
        boolean sdkControlsEnabled = PythonPluginsEngine.Updater.status < PythonPluginsEngine.Updater.STATUS_DOWNLOADING;
        UItem sdkAutoUpdateItem = UItem.asCheck(
                        ITEM_PYSDK_AUTO_UPDATE,
                        LocaleController.getString(R.string.PluginsPySdkAutoUpdate),
                        PythonPluginsEngine.Updater.getStateString())
                .setChecked(ExteraConfig.pluginsPySdkAutoUpdate)
                .setEnabled(sdkControlsEnabled);
        items.add(sdkAutoUpdateItem);
        UItem sdkBetaItem = UItem.asCheck(ITEM_PYSDK_BETA, LocaleController.getString(R.string.PluginsPySdkEnableBetaVersion))
                .setChecked(ExteraConfig.pluginsPySdkBetaVersions)
                .setEnabled(sdkControlsEnabled);
        items.add(sdkBetaItem);
        items.add(UItem.asButton(ITEM_PYSDK_CHECK, R.drawable.msg_retry, LocaleController.getString(R.string.PluginsPySdkCheckUpdates))
                .accent()
                .setEnabled(sdkControlsEnabled));
        if (ExteraConfig.pluginsDevMode && !ExteraConfig.pluginsEngine && !PythonPluginsEngine.Updater.isSdkFromApk()) {
            items.add(UItem.asButton(ITEM_PYSDK_RESTORE, R.drawable.msg_reset, LocaleController.getString(R.string.RestoreSdkFromApk)).red());
        }
        items.add(UItem.asShadow(""));

        if (ExteraConfig.pluginsDevMode && hasSmokeTestAsset()) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.PluginsDevTools)));
            items.add(UItem.asButton(ITEM_INSTALL_SMOKE_TEST, R.drawable.msg_addbot, LocaleController.getString(R.string.PluginsInstallSmokeTest)));
            items.add(UItem.asShadow(LocaleController.getString(R.string.PluginsInstallSmokeTestInfo)));
        }
        items.add(UItem.asHeader(LocaleController.getString(R.string.Links)));
        items.add(UItem.asButton(ITEM_DOCUMENTATION, R.drawable.menu_intro, LocaleController.getString(R.string.PluginsDocumentation)));
        items.add(UItem.asButton(ITEM_TRUSTED, R.drawable.msg2_policy, LocaleController.getString(R.string.PluginsTrusted)).accent());
        items.add(UItem.asShadow(LocaleUtils.formatWithHtmlURLs(new SpannableString(LocaleUtils.fromHtml(LocaleController.getString(R.string.PluginsPoweredBy))))));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item == null) {
            return;
        }
        switch (item.id) {
            case ITEM_DEV_MODE:
                if (!ExteraConfig.pluginsEngine) {
                    return;
                }
                ExteraConfig.pluginsDevMode = !ExteraConfig.pluginsDevMode;
                ExteraConfig.editor.putBoolean("pluginsDevMode", ExteraConfig.pluginsDevMode).apply();
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(ExteraConfig.pluginsDevMode);
                }
                item.setChecked(ExteraConfig.pluginsDevMode);
                PluginsController.getInstance().checkDevServers();
                BulletinFactory.of(this)
                        .createSimpleBulletin(
                                ExteraConfig.pluginsDevMode ? R.raw.contact_check : R.raw.error,
                                LocaleController.getString(ExteraConfig.pluginsDevMode ? R.string.PluginsDevServerLaunched : R.string.PluginsDevServerStopped))
                        .show();
                if (listView != null) {
                    listView.adapter.update(true);
                }
                break;
            case ITEM_COMPACT_VIEW:
                if (!ExteraConfig.pluginsEngine) {
                    return;
                }
                ExteraConfig.pluginsCompactView = !ExteraConfig.pluginsCompactView;
                ExteraConfig.editor.putBoolean("pluginsCompactView", ExteraConfig.pluginsCompactView).apply();
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(ExteraConfig.pluginsCompactView);
                }
                item.setChecked(ExteraConfig.pluginsCompactView);
                NotificationCenter.getGlobalInstance().postNotificationNameOnUIThread(NotificationCenter.reloadInterface);
                break;
            case ITEM_SAFE_MODE:
                ExteraConfig.pluginsSafeMode = !ExteraConfig.pluginsSafeMode;
                if (ExteraConfig.pluginsSafeMode) {
                    PluginsController.enableManualSafeMode();
                } else {
                    PluginsController.disableSafeMode();
                }
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(ExteraConfig.pluginsSafeMode);
                }
                item.setChecked(ExteraConfig.pluginsSafeMode);
                PluginsController.getInstance().restart();
                break;
            case ITEM_DISABLE_ART:
                if (!ExteraConfig.pluginsEngine) {
                    return;
                }
                ExteraConfig.pluginsDisableArtOpts = !ExteraConfig.pluginsDisableArtOpts;
                ExteraConfig.editor.putBoolean("pluginsDisableArtOpts", ExteraConfig.pluginsDisableArtOpts).apply();
                updateCheckItem(view, item, ExteraConfig.pluginsDisableArtOpts);
                PluginsController.applyArtOpts();
                BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, LocaleController.getString(R.string.RestartApp)).show();
                break;
            case ITEM_PYSDK_AUTO_UPDATE:
                if (PythonPluginsEngine.Updater.status >= PythonPluginsEngine.Updater.STATUS_DOWNLOADING) {
                    return;
                }
                ExteraConfig.pluginsPySdkAutoUpdate = !ExteraConfig.pluginsPySdkAutoUpdate;
                ExteraConfig.editor.putBoolean("pluginsPySdkAutoUpdate", ExteraConfig.pluginsPySdkAutoUpdate).apply();
                updateCheckItem(view, item, ExteraConfig.pluginsPySdkAutoUpdate);
                if (ExteraConfig.pluginsPySdkAutoUpdate) {
                    PythonPluginsEngine.Updater.checkUpdates(true);
                } else if (listView != null) {
                    listView.adapter.update(true);
                }
                break;
            case ITEM_PYSDK_BETA:
                if (PythonPluginsEngine.Updater.status >= PythonPluginsEngine.Updater.STATUS_DOWNLOADING) {
                    return;
                }
                ExteraConfig.pluginsPySdkBetaVersions = !ExteraConfig.pluginsPySdkBetaVersions;
                ExteraConfig.editor.putBoolean("pluginsPySdkBetaVersions", ExteraConfig.pluginsPySdkBetaVersions).apply();
                ExteraConfig.sdkUpdateScheduleTimestamp = 0L;
                ExteraConfig.editor.putLong("sdkUpdateScheduleTimestamp", 0L).apply();
                updateCheckItem(view, item, ExteraConfig.pluginsPySdkBetaVersions);
                PythonPluginsEngine.Updater.checkUpdates(true);
                break;
            case ITEM_PYSDK_CHECK:
                if (PythonPluginsEngine.Updater.status >= PythonPluginsEngine.Updater.STATUS_DOWNLOADING) {
                    return;
                }
                PythonPluginsEngine.Updater.checkUpdates(true);
                if (listView != null) {
                    listView.adapter.update(true);
                }
                break;
            case ITEM_PYSDK_RESTORE:
                PythonPluginsEngine.Updater.restoreSdkFromApk();
                BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, LocaleController.getString(R.string.RestartPluginSystemToApplyUpdate)).show();
                break;
            case ITEM_DOCUMENTATION:
                Browser.openUrl(getParentActivity(), "https://plugins.exteragram.app/");
                break;
            case ITEM_TRUSTED:
                Browser.openUrl(getParentActivity(), "https://t.me/addlist/pPhOtEq00KhjYTc6");
                break;
            case ITEM_INSTALL_SMOKE_TEST:
                installSmokeTestPlugin();
                break;
        }
    }

    private boolean hasSmokeTestAsset() {
        if (smokeTestAssetAvailable != null) {
            return smokeTestAssetAvailable;
        }
        Context context = getParentActivity();
        if (context == null) {
            smokeTestAssetAvailable = false;
            return false;
        }
        try (InputStream ignored = context.getAssets().open(SMOKE_TEST_ASSET_PATH)) {
            smokeTestAssetAvailable = true;
        } catch (IOException e) {
            smokeTestAssetAvailable = false;
        }
        return smokeTestAssetAvailable;
    }

    private void installSmokeTestPlugin() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        File cacheDir = new File(context.getCacheDir(), "plugins-dev");
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            BulletinFactory.of(this)
                    .createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.PluginsInstallSmokeTestPrepareError))
                    .show();
            return;
        }
        File targetFile = new File(cacheDir, "smoke_test" + PluginsConstants.PLUGINS_EXT);
        try {
            copyAssetToFile(context.getAssets(), SMOKE_TEST_ASSET_PATH, targetFile);
            PluginsController.getInstance().showInstallDialog(this, targetFile.getAbsolutePath(), false);
        } catch (IOException e) {
            BulletinFactory.of(this)
                    .createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.PluginsInstallSmokeTestPrepareError))
                    .show();
        }
    }

    private static void copyAssetToFile(AssetManager assetManager, String assetPath, File targetFile) throws IOException {
        try (InputStream inputStream = assetManager.open(assetPath);
             FileOutputStream outputStream = new FileOutputStream(targetFile, false)) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            outputStream.flush();
        }
    }

    private void updateCheckItem(View view, UItem item, boolean checked) {
        if (view instanceof TextCheckCell) {
            ((TextCheckCell) view).setChecked(checked);
        } else if (view instanceof NotificationsCheckCell) {
            ((NotificationsCheckCell) view).setChecked(checked);
        }
        if (item != null) {
            item.setChecked(checked);
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.pluginsPySdkInfoChanged && listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }
}
