package com.exteragram.messenger.plugins.ui;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.plugins.Plugin;
import com.exteragram.messenger.plugins.PluginsConstants;
import com.exteragram.messenger.plugins.PluginsController;
import com.exteragram.messenger.preferences.BasePreferencesActivity;
import com.exteragram.messenger.plugins.ui.components.EmptyPluginsView;
import com.exteragram.messenger.plugins.ui.components.PluginCell;
import com.exteragram.messenger.plugins.ui.components.PluginCellDelegate;
import com.exteragram.messenger.plugins.utils.PluginsWatchdog;
import com.exteragram.messenger.utils.text.LocaleUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PluginsActivity extends BasePreferencesActivity implements NotificationCenter.NotificationCenterDelegate {
    private static final int REQUEST_PICK_PLUGIN = 9001;
    private static final int BUTTON_TOGGLE_ENGINE = 1;

    private ActionBarMenuItem searchItem;
    private ActionBarMenuItem installItem;
    private ActionBarMenuItem infoItem;
    private boolean searching;
    private String query;
    private boolean isSwitchingEngineState;

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.Plugins);
    }

    @Override
    public View createView(android.content.Context context) {
        View view = super.createView(context);
        listView.setPadding(0, 0, 0, AndroidUtilities.navigationBarHeight + AndroidUtilities.dp(12));
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING && getParentActivity() != null) {
                    AndroidUtilities.hideKeyboard(getParentActivity().getCurrentFocus());
                }
            }
        });

        ActionBarMenu menu = actionBar.createMenu();
        searchItem = menu.addItem(0, R.drawable.ic_ab_search)
                .setIsSearchField(true)
                .setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                    @Override
                    public void onSearchExpand() {
                        searching = true;
                        listView.adapter.update(true);
                        listView.scrollToPosition(0);
                        if (infoItem != null) {
                            infoItem.setVisibility(View.GONE);
                        }
                    }

                    @Override
                    public void onSearchCollapse() {
                        searching = false;
                        query = null;
                        listView.adapter.update(true);
                        listView.scrollToPosition(0);
                        if (infoItem != null) {
                            infoItem.setVisibility(View.VISIBLE);
                        }
                    }

                    @Override
                    public void onTextChanged(EditText editText) {
                        query = editText.getText().toString();
                        listView.adapter.update(true);
                        listView.scrollToPosition(0);
                    }
                });
        searchItem.setSearchFieldHint(LocaleController.getString(R.string.Search));
        updateSearchVisibility(false);

        installItem = menu.addItem(2, R.drawable.msg_add);
        installItem.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openPluginPicker();
            }
        });

        infoItem = menu.addItem(1, R.drawable.msg_info);
        infoItem.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                presentFragment(new PluginsInfoActivity());
            }
        });
        return view;
    }

    private void openPluginPicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(intent, REQUEST_PICK_PLUGIN);
        } catch (Exception e) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.ErrorOccurred)).show();
        }
    }

    private String resolveDisplayName(Uri uri) {
        if (uri == null || getParentActivity() == null) {
            return null;
        }
        Cursor cursor = null;
        try {
            cursor = getParentActivity().getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        } catch (Exception ignore) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_PICK_PLUGIN && resultCode == Activity.RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                try {
                    if (getParentActivity() == null) {
                        throw new IllegalStateException("parentActivity == null");
                    }
                    File cacheDir = new File(getParentActivity().getCacheDir(), "plugin_imports");
                    if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                        throw new IllegalStateException("Failed to create plugin import cache dir");
                    }
                    File file = File.createTempFile("plugin_import_", PluginsConstants.PLUGINS_EXT, cacheDir);
                    try (InputStream inputStream = getParentActivity().getContentResolver().openInputStream(uri);
                         OutputStream outputStream = new FileOutputStream(file)) {
                        if (inputStream == null) {
                            throw new IllegalStateException("inputStream == null");
                        }
                        byte[] buffer = new byte[4 * 1024];
                        int read;
                        while ((read = inputStream.read(buffer)) != -1) {
                            outputStream.write(buffer, 0, read);
                        }
                        outputStream.flush();
                    }
                    PluginsController.getInstance().showInstallDialog(this, file.getAbsolutePath(), false);
                } catch (Throwable e) {
                    FileLog.e("Failed to import plugin from uri " + uri, e);
                    BulletinFactory.of(this)
                            .createSimpleBulletin(
                                    R.raw.error,
                                    LocaleController.getString(R.string.ErrorOccurred),
                                    LocaleController.getString(R.string.Copy),
                                    () -> {
                                        if (AndroidUtilities.addToClipboard(Log.getStackTraceString(e))) {
                                            BulletinFactory.of(this).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
                                        }
                                    })
                            .show();
                }
            }
        }
        super.onActivityResultFragment(requestCode, resultCode, data);
    }

    private void updateSearchVisibility(boolean animated) {
        AndroidUtilities.updateViewVisibilityAnimated(searchItem, ExteraConfig.pluginsEngine && !PluginsController.getInstance().plugins.isEmpty(), 0.5f, false, animated);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (!searching) {
            items.add(createPluginsEngineItem());
            UItem runtimeIssueItem = createRuntimeIssueItem();
            if (runtimeIssueItem != null) {
                items.add(runtimeIssueItem);
            }
        }
        if (!ExteraConfig.pluginsEngine) {
            return;
        }

        Map<String, Plugin> plugins = new HashMap<>(PluginsController.getInstance().plugins);
        UItem topSpacer = UItem.asSpace(AndroidUtilities.dp(8));
        topSpacer.transparent = true;
        items.add(topSpacer);

        if (searching && !TextUtils.isEmpty(query)) {
            String lowered = query.toLowerCase();
            ArrayList<Plugin> filtered = new ArrayList<>();
            for (Plugin plugin : plugins.values()) {
                if (plugin != null && plugin.getName() != null && plugin.getName().toLowerCase().contains(lowered)) {
                    filtered.add(plugin);
                }
            }
            if (!filtered.isEmpty()) {
                for (Plugin plugin : filtered) {
                    addPluginItem(items, plugin);
                }
                return;
            }
            plugins.clear();
        }

        if (plugins.isEmpty()) {
            EmptyPluginsView emptyView = new EmptyPluginsView(getContext() != null ? getContext() : fragmentView.getContext(), getResourceProvider());
            if (searching) {
                MediaDataController.getInstance(UserConfig.selectedAccount).setPlaceholderImage(emptyView.getBackupImageView(), "AnimatedEmojies", "🔎", "100_100");
                emptyView.setText(LocaleController.getString(R.string.PluginsNotFound));
            } else {
                MediaDataController.getInstance(UserConfig.selectedAccount).setPlaceholderImage(emptyView.getBackupImageView(), "AnimatedEmojies", "📂", "100_100");
                emptyView.setText(LocaleUtils.formatWithUsernames(LocaleController.getString(R.string.PluginsInfo)));
            }
            items.add(UItem.asFullscreenCustom(emptyView, AndroidUtilities.dp(72) + org.telegram.ui.ActionBar.ActionBar.getCurrentActionBarHeight() + AndroidUtilities.statusBarHeight, true).setTransparent(true));
            return;
        }

        if (!ExteraConfig.pinnedPlugins.isEmpty()) {
            for (String pluginId : ExteraConfig.pinnedPlugins) {
                Plugin plugin = plugins.get(pluginId);
                if (plugin != null) {
                    addPluginItem(items, plugin);
                }
            }
        }

        ArrayList<Plugin> sorted = new ArrayList<>(plugins.values());
        Collections.sort(sorted, new Comparator<Plugin>() {
            @Override
            public int compare(Plugin o1, Plugin o2) {
                return String.CASE_INSENSITIVE_ORDER.compare(o1.getName(), o2.getName());
            }
        });
        for (Plugin plugin : sorted) {
            if (!PluginsController.isPluginPinned(plugin.getId())) {
                addPluginItem(items, plugin);
            }
        }

        UItem bottomSpacer = UItem.asSpace(AndroidUtilities.dp(4));
        bottomSpacer.transparent = true;
        items.add(bottomSpacer);
    }

    private void addPluginItem(ArrayList<UItem> items, Plugin plugin) {
        items.add(createPluginItem(plugin));
        UItem spacer = UItem.asSpace(AndroidUtilities.dp(8));
        spacer.transparent = true;
        items.add(spacer);
    }

    private UItem createPluginsEngineItem() {
        return UItem.asRippleCheck(BUTTON_TOGGLE_ENGINE, LocaleController.getString(R.string.EnablePluginsEngine))
                .setChecked(ExteraConfig.pluginsEngine);
    }

    private UItem createRuntimeIssueItem() {
        String issue = PluginsController.getPluginRuntimeIssue();
        if (TextUtils.isEmpty(issue) || "disabled".equals(issue) || "unsupported".equals(issue)) {
            return null;
        }
        CharSequence text;
        if ("safe_mode".equals(issue)) {
            text = PluginsController.getSafeModeStatusText();
        } else if ("native_runtime_unavailable".equals(issue)) {
            text = LocaleController.getString(R.string.PluginsNativeRuntimeUnavailable);
        } else if ("python_runtime_unavailable".equals(issue)) {
            text = LocaleController.getString(R.string.PluginsPythonRuntimeUnavailable);
        } else {
            return null;
        }
        return UItem.asShadow(text);
    }

    private UItem createPluginItem(final Plugin plugin) {
        return PluginCell.Factory.of(plugin, new PluginCellDelegate() {
            @Override
            public void sharePlugin() {
                PluginsController.PluginsEngine engine = PluginsController.getInstance().getPluginEngine(plugin.getId());
                if (engine != null) {
                    engine.sharePlugin(plugin.getId());
                }
            }

            @Override
            public void openInExternalApp() {
                PluginsController.PluginsEngine engine = PluginsController.getInstance().getPluginEngine(plugin.getId());
                if (engine != null) {
                    engine.openInExternalApp(plugin.getId());
                }
            }

            @Override
            public void deletePlugin() {
                if (plugin.isNotResponding()) {
                    PluginsWatchdog.showNotRespondingAlert(plugin);
                    return;
                }
                AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                        .setTitle(LocaleController.getString(R.string.PluginDelete))
                        .setMessage(AndroidUtilities.replaceTags(LocaleController.formatString(R.string.PluginDeleteInfo, plugin.getName())))
                        .setPositiveButton(LocaleController.getString(R.string.Delete), new AlertDialog.OnButtonClickListener() {
                            @Override
                            public void onClick(AlertDialog alertDialog, int which) {
                                PluginsController.getInstance().deletePlugin(plugin.getId(), new Utilities.Callback<String>() {
                                    @Override
                                    public void run(final String error) {
                                        AndroidUtilities.runOnUIThread(new Runnable() {
                                            @Override
                                            public void run() {
                                                if (fragmentView == null) {
                                                    return;
                                                }
                                                updateSearchVisibility(true);
                                                listView.adapter.update(true);
                                                if (error != null) {
                                                    BulletinFactory.of(PluginsActivity.this).createSimpleBulletin(R.raw.error, error).show();
                                                }
                                            }
                                        });
                                    }
                                });
                            }
                        })
                        .setNegativeButton(LocaleController.getString(R.string.Cancel), null);
                AlertDialog dialog = builder.create();
                showDialog(dialog);
                TextView textView = (TextView) dialog.getButton(-1);
                if (textView != null) {
                    textView.setTextColor(Theme.getColor(Theme.key_text_RedBold, getResourceProvider()));
                }
            }

            @Override
            public void togglePlugin(View view) {
                if (plugin.isNotResponding()) {
                    PluginsWatchdog.showNotRespondingAlert(plugin);
                    return;
                }
                final PluginCell pluginCell = (PluginCell) view;
                final boolean enabled = !plugin.isEnabled();
                PluginsController.getInstance().setPluginEnabled(plugin.getId(), enabled, new Utilities.Callback<String>() {
                    @Override
                    public void run(final String error) {
                        AndroidUtilities.runOnUIThread(new Runnable() {
                            @Override
                            public void run() {
                                if (error != null) {
                                    BulletinFactory.of(PluginsActivity.this)
                                            .createSimpleBulletin(
                                                    R.raw.error,
                                                    LocaleController.formatString(enabled ? R.string.PluginEnableError : R.string.PluginDisableError, plugin.getName()),
                                                    LocaleController.getString(R.string.Copy),
                                                    new Runnable() {
                                                        @Override
                                                        public void run() {
                                                            if (AndroidUtilities.addToClipboard(error)) {
                                                                BulletinFactory.of(PluginsActivity.this).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
                                                            }
                                                        }
                                                    })
                                            .show();
                                } else {
                                    pluginCell.setChecked(enabled, true);
                                    listView.adapter.update(false);
                                    if (enabled && ExteraConfig.pluginsSafeMode) {
                                        BulletinFactory.of(PluginsActivity.this)
                                                .createSimpleBulletin(R.raw.error, PluginsController.getSafeModeStatusText())
                                                .show();
                                    }
                                }
                            }
                        });
                    }
                });
            }

            @Override
            public void openPluginSettings() {
                if (!PluginsController.getInstance().hasPluginSettings(plugin.getId())) {
                    return;
                }
                PluginsController.PluginsEngine engine = PluginsController.getInstance().getPluginEngine(plugin.getId());
                if (engine != null) {
                    engine.openPluginSettings(plugin, PluginsActivity.this);
                }
            }

            @Override
            public void pinPlugin(View view) {
                boolean pinned = PluginsController.isPluginPinned(plugin.getId());
                PluginsController.setPluginPinned(plugin.getId(), !pinned);
                if (view instanceof PluginCell) {
                    ((PluginCell) view).setPinned(!pinned);
                }
                listView.adapter.update(true);
                listView.smoothScrollToPosition(0);
            }

            @Override
            public boolean canOpenInExternalApp() {
                PluginsController.PluginsEngine engine = PluginsController.getInstance().getPluginEngine(plugin.getId());
                return engine != null && engine.canOpenInExternalApp();
            }
        });
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item != null && item.id == BUTTON_TOGGLE_ENGINE) {
            togglePluginsEngine(view, item);
        }
    }

    private void togglePluginsEngine(View view, UItem item) {
        if (isSwitchingEngineState) {
            return;
        }
        isSwitchingEngineState = true;
        ExteraConfig.pluginsEngine = !ExteraConfig.pluginsEngine;
        SharedPreferences.Editor editor = ExteraConfig.editor;
        editor.putBoolean("pluginsEngine", ExteraConfig.pluginsEngine).apply();
        if (item != null) {
            item.checked = ExteraConfig.pluginsEngine;
        }
        if (view instanceof TextCheckCell) {
            TextCheckCell textCheckCell = (TextCheckCell) view;
            textCheckCell.setChecked(ExteraConfig.pluginsEngine);
            textCheckCell.setBackgroundColorAnimated(
                    ExteraConfig.pluginsEngine,
                    Theme.getColor(
                            ExteraConfig.pluginsEngine ? Theme.key_windowBackgroundChecked : Theme.key_windowBackgroundUnchecked,
                            getResourceProvider()));
        }

        Runnable onComplete = new Runnable() {
            @Override
            public void run() {
                AndroidUtilities.runOnUIThread(new Runnable() {
                    @Override
                    public void run() {
                        if (fragmentView == null) {
                            return;
                        }
                        if (searching) {
                            actionBar.closeSearchField();
                        }
                        updateSearchVisibility(true);
                        listView.adapter.update(true);
                        isSwitchingEngineState = false;
                    }
                });
            }
        };
        if (ExteraConfig.pluginsEngine) {
            PluginsController.getInstance().init(onComplete);
        } else {
            PluginsController.getInstance().shutdown(onComplete);
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.pluginsUpdated);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.reloadInterface);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.pluginIsNotResponding);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.pluginsUpdated);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.reloadInterface);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.pluginIsNotResponding);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.pluginsUpdated) {
            updateSearchVisibility(true);
            listView.adapter.update(true);
        } else if (id == NotificationCenter.reloadInterface) {
            listView.invalidateViews();
        } else if (id == NotificationCenter.pluginIsNotResponding) {
            listView.adapter.update(true);
        }
    }

}
