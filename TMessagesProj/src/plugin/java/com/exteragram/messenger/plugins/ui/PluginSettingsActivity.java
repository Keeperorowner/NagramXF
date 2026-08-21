package com.exteragram.messenger.plugins.ui;

import android.content.DialogInterface;
import android.text.InputType;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;

import com.chaquo.python.PyObject;
import com.exteragram.messenger.plugins.Plugin;
import com.exteragram.messenger.plugins.PluginsConstants;
import com.exteragram.messenger.plugins.PluginsController;
import com.exteragram.messenger.plugins.PythonPluginsEngine;
import com.exteragram.messenger.plugins.models.CustomSetting;
import com.exteragram.messenger.plugins.models.DividerSetting;
import com.exteragram.messenger.plugins.models.EditTextSetting;
import com.exteragram.messenger.plugins.models.HeaderSetting;
import com.exteragram.messenger.plugins.models.InputSetting;
import com.exteragram.messenger.plugins.models.SelectorSetting;
import com.exteragram.messenger.plugins.models.SettingItem;
import com.exteragram.messenger.plugins.models.SwitchSetting;
import com.exteragram.messenger.plugins.models.TextSetting;
import com.exteragram.messenger.plugins.ui.components.PluginEditTextCell;
import com.exteragram.messenger.preferences.utils.SettingsRegistry;
import com.exteragram.messenger.utils.system.VibratorUtils;
import com.exteragram.messenger.utils.text.LocaleUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.NotificationsCheckCell;
import org.telegram.ui.Cells.RadioColorCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public class PluginSettingsActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {
    private final Plugin plugin;
    private final String customTitle;
    private final PyObject createSubFragmentCallback;
    private List<SettingItem> settingItems;
    private String settingsLinkPrefix;
    private String targetSettingName;
    private Integer targetSettingItemId;

    private UniversalRecyclerView listView;
    private LinearLayoutManager layoutManager;
    private ActionBarMenuItem resetItem;

    public PluginSettingsActivity(Plugin plugin) {
        this(plugin, null, null, null, null);
    }

    public PluginSettingsActivity(Plugin plugin, String targetSetting) {
        this(plugin, null, null, null, targetSetting);
    }

    public PluginSettingsActivity(Plugin plugin, String title, List<SettingItem> items, PyObject createSubFragmentCallback) {
        this(plugin, title, items, createSubFragmentCallback, null);
    }

    public PluginSettingsActivity(Plugin plugin, String title, List<SettingItem> items, PyObject createSubFragmentCallback, String targetSetting) {
        this.plugin = plugin;
        this.customTitle = title;
        this.settingItems = items;
        this.createSubFragmentCallback = createSubFragmentCallback;
        this.targetSettingName = targetSetting;
    }

    public PluginSettingsActivity setSettingsLinkPrefix(String settingsLinkPrefix) {
        this.settingsLinkPrefix = settingsLinkPrefix;
        return this;
    }

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.pluginSettingsRegistered);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.pluginSettingsUnregistered);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.pluginSettingsRegistered);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.pluginSettingsUnregistered);
        super.onFragmentDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        AndroidUtilities.runOnUIThread(new Runnable() {
            @Override
            public void run() {
                checkTargetSetting();
            }
        }, 60);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        String pluginId = null;
        if (args != null && args.length > 0 && args[0] instanceof String) {
            pluginId = (String) args[0];
        }
        if (id == NotificationCenter.pluginSettingsRegistered) {
            if (plugin == null || (pluginId != null && !TextUtils.equals(plugin.getId(), pluginId))) {
                return;
            }
            if (createSubFragmentCallback != null) {
                reloadSubFragmentSettings();
                return;
            }
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(true);
                AndroidUtilities.runOnUIThread(new Runnable() {
                    @Override
                    public void run() {
                        checkTargetSetting();
                    }
                }, 60);
            }
            if (resetItem != null) {
                AndroidUtilities.updateViewVisibilityAnimated(resetItem, PluginsController.getInstance().hasPluginSettingsPreferences(plugin.getId()), 0.5f, false);
            }
        } else if (id == NotificationCenter.pluginSettingsUnregistered) {
            if (plugin != null && TextUtils.equals(plugin.getId(), pluginId) && !PluginsController.getInstance().hasPluginSettings(plugin.getId())) {
                finishFragment();
            }
        }
    }

    private void reloadSubFragmentSettings() {
        PluginsController.runOnPluginsQueue(new Runnable() {
            @Override
            public void run() {
                final List<SettingItem> items = new ArrayList<>();
                try {
                    PyObject result = createSubFragmentCallback.call(new Object[0]);
                    if (result != null) {
                        PluginsController.PluginsEngine engine = PluginsController.engines.get(PluginsConstants.PYTHON);
                        if (engine instanceof PythonPluginsEngine) {
                            items.addAll(((PythonPluginsEngine) engine).parsePySettingDefinitions(result.asList()));
                        }
                    }
                } catch (Exception ignored) {
                }
                AndroidUtilities.runOnUIThread(new Runnable() {
                    @Override
                    public void run() {
                        settingItems = items;
                        if (listView != null && listView.adapter != null) {
                            listView.adapter.update(true);
                            AndroidUtilities.runOnUIThread(new Runnable() {
                                @Override
                                public void run() {
                                    checkTargetSetting();
                                }
                            }, 60);
                        }
                    }
                });
            }
        });
    }
    @Override
    public View createView(android.content.Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(customTitle != null ? customTitle : plugin.getName());
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        if (createSubFragmentCallback == null && plugin != null) {
            resetItem = actionBar.createMenu().addItem(0, R.drawable.msg_reset);
            resetItem.setContentDescription(LocaleController.getString(R.string.Reset));
            AndroidUtilities.updateViewVisibilityAnimated(resetItem, PluginsController.getInstance().hasPluginSettingsPreferences(plugin.getId()), 0.5f, false);
            resetItem.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showResetDialog();
                }
            });
        }

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, getResourceProvider()));

        listView = new UniversalRecyclerView(this, new Utilities.Callback2<ArrayList<UItem>, UniversalAdapter>() {
            @Override
            public void run(ArrayList<UItem> items, UniversalAdapter adapter) {
                fillItems(items, adapter);
            }
        }, new Utilities.Callback5<UItem, View, Integer, Float, Float>() {
            @Override
            public void run(UItem item, View view, Integer position, Float x, Float y) {
                onClick(item, view, position.intValue(), x.floatValue(), y.floatValue());
            }
        }, new Utilities.Callback5Return<UItem, View, Integer, Float, Float, Boolean>() {
            @Override
            public Boolean run(UItem item, View view, Integer position, Float x, Float y) {
                return onLongClick(item, view, position.intValue(), x.floatValue(), y.floatValue());
            }
        });
        listView.setSections(true);
        listView.setClipToPadding(false);
        listView.setPadding(0, 0, 0, AndroidUtilities.navigationBarHeight + AndroidUtilities.dp(12));
        actionBar.setAdaptiveBackground(listView);
        layoutManager = listView.layoutManager;
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        fragmentView = frameLayout;
        return frameLayout;
    }

    private void showResetDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.ResetSettings));
        builder.setMessage(AndroidUtilities.replaceTags(LocaleController.formatString(R.string.ResetPluginSettingsInfo, plugin.getName())));
        builder.setPositiveButton(LocaleController.getString(R.string.Reset), new AlertDialog.OnButtonClickListener() {
            @Override
            public void onClick(AlertDialog alertDialog, int which) {
                AndroidUtilities.updateViewVisibilityAnimated(resetItem, false, 0.5f, true);
                PluginsController.getInstance().clearPluginSettingsPreferences(plugin.getId());
                PluginsController.getInstance().loadPluginSettings(plugin.getId());
                AndroidUtilities.runOnUIThread(new Runnable() {
                    @Override
                    public void run() {
                        BulletinFactory.of(PluginSettingsActivity.this).createSimpleBulletin(R.raw.info, LocaleController.formatString(R.string.ResetPluginSettings, plugin.getName())).show();
                    }
                });
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        TextView button = (TextView) dialog.getButton(-1);
        if (button != null) {
            button.setTextColor(Theme.getColor(Theme.key_text_RedBold, getResourceProvider()));
        }
    }

    public void checkTargetSetting() {
        if (listView == null || listView.adapter == null) {
            return;
        }
        if (targetSettingItemId != null) {
            final int position = listView.findPositionByItemId(targetSettingItemId);
            if (position >= 0 && position < listView.adapter.getItemCount()) {
                listView.highlightRow(new RecyclerListView.IntReturnCallback() {
                    @Override
                    public int run() {
                        layoutManager.scrollToPositionWithOffset(position, AndroidUtilities.dp(60));
                        return position;
                    }
                });
                targetSettingItemId = null;
            } else if (listView.adapter.getItemCount() > 0) {
                targetSettingItemId = null;
                SettingsRegistry.getInstance().onSettingNotFound(this);
            }
        } else if (!TextUtils.isEmpty(targetSettingName) && listView.adapter.getItemCount() > 0) {
            SettingsRegistry.getInstance().onSettingNotFound(this);
            targetSettingName = null;
        }
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (plugin == null) {
            return;
        }
        List<SettingItem> definitions = settingItems != null ? settingItems : PluginsController.getInstance().getPluginSettingsList(plugin.getId());
        if (definitions == null || definitions.isEmpty()) {
            return;
        }

        boolean foundTarget = false;
        for (SettingItem item : definitions) {
            if (item == null) {
                continue;
            }
            int iconRes = resolveIcon(item.icon);
            UItem uiItem = createItem(item, iconRes);
            if (uiItem == null) {
                continue;
            }
            uiItem.id = getStableId(item);
            if (!foundTarget && item.linkAlias != null && !TextUtils.isEmpty(item.linkAlias) && !TextUtils.isEmpty(targetSettingName) && TextUtils.equals(item.linkAlias, targetSettingName)) {
                targetSettingItemId = uiItem.id;
                targetSettingName = null;
                foundTarget = true;
            }
            items.add(uiItem);
        }

        if (!foundTarget && !TextUtils.isEmpty(targetSettingName)) {
            SettingsRegistry.getInstance().onSettingNotFound(this);
            targetSettingName = null;
        }
    }

    private UItem createItem(SettingItem item, int iconRes) {
        try {
            switch (item.type) {
                case PluginsConstants.Settings.TYPE_DIVIDER: {
                    DividerSetting setting = (DividerSetting) item;
                    CharSequence text = setting.text != null ? LocaleUtils.fullyFormatText(setting.text, this, null) : "";
                    UItem uiItem = UItem.asShadow(text);
                    uiItem.object2 = item;
                    return uiItem;
                }
                case PluginsConstants.Settings.TYPE_HEADER: {
                    HeaderSetting setting = (HeaderSetting) item;
                    if (setting.text == null) {
                        return null;
                    }
                    UItem uiItem = UItem.asHeader(setting.text);
                    uiItem.object2 = item;
                    return uiItem;
                }
                case PluginsConstants.Settings.TYPE_SWITCH: {
                    SwitchSetting setting = (SwitchSetting) item;
                    if (setting.key == null || setting.text == null) {
                        return null;
                    }
                    boolean checked = PluginsController.getInstance().getPluginSettingBoolean(plugin.getId(), setting.key, setting.defaultValue);
                    UItem uiItem = setting.subtext != null ? UItem.asButtonCheck(0, setting.text, setting.subtext) : UItem.asCheck(0, setting.text);
                    uiItem.setChecked(checked);
                    uiItem.object = setting.key;
                    uiItem.object2 = setting;
                    if (iconRes != 0) {
                        uiItem.iconResId = iconRes;
                    }
                    return uiItem;
                }
                case PluginsConstants.Settings.TYPE_INPUT: {
                    InputSetting setting = (InputSetting) item;
                    if (setting.key == null || setting.text == null) {
                        return null;
                    }
                    String value = PluginsController.getInstance().getPluginSettingString(plugin.getId(), setting.key, setting.defaultValue);
                    UItem uiItem = UItem.asButton(0, setting.text, value);
                    uiItem.object = setting.key;
                    uiItem.object2 = setting;
                    if (iconRes != 0) {
                        uiItem.iconResId = iconRes;
                    }
                    return uiItem;
                }
                case PluginsConstants.Settings.TYPE_SELECTOR: {
                    SelectorSetting setting = (SelectorSetting) item;
                    if (setting.key == null || setting.text == null || setting.items == null || setting.items.length == 0) {
                        return null;
                    }
                    int value = PluginsController.getInstance().getPluginSettingInt(plugin.getId(), setting.key, setting.defaultValue);
                    if (value < 0 || value >= setting.items.length) {
                        value = Math.max(0, Math.min(setting.defaultValue, setting.items.length - 1));
                        PluginsController.getInstance().setPluginSetting(plugin.getId(), setting.key, value);
                    }
                    UItem uiItem = UItem.asButton(0, setting.text, setting.items[value]);
                    uiItem.texts = setting.items;
                    uiItem.intValue = value;
                    uiItem.object = setting.key;
                    uiItem.object2 = setting;
                    if (iconRes != 0) {
                        uiItem.iconResId = iconRes;
                    }
                    return uiItem;
                }
                case PluginsConstants.Settings.TYPE_TEXT: {
                    TextSetting setting = (TextSetting) item;
                    UItem uiItem = UItem.asButton(0, setting.text);
                    uiItem.accent = setting.accent;
                    uiItem.red = setting.red;
                    uiItem.object2 = setting;
                    if (iconRes != 0) {
                        uiItem.iconResId = iconRes;
                    }
                    return uiItem;
                }
                case PluginsConstants.Settings.TYPE_CUSTOM: {
                    CustomSetting setting = (CustomSetting) item;
                    UItem uiItem = null;
                    if (setting.item != null) {
                        uiItem = setting.item;
                    } else if (setting.factory != null) {
                        uiItem = setting.factory.create(plugin, setting, setting.factoryArgs);
                    }
                    if (uiItem != null) {
                        uiItem.object2 = setting;
                    }
                    return uiItem;
                }
                case PluginsConstants.Settings.TYPE_EDIT_TEXT: {
                    EditTextSetting setting = (EditTextSetting) item;
                    if (setting.key == null || setting.hint == null) {
                        return null;
                    }
                    return PluginEditTextCell.Factory.of(plugin, setting);
                }
                default:
                    return null;
            }
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private int resolveIcon(String icon) {
        if (TextUtils.isEmpty(icon)) {
            return 0;
        }
        android.content.Context appContext = org.telegram.messenger.ApplicationLoader.applicationContext;
        if (appContext == null) {
            return 0;
        }
        return appContext.getResources().getIdentifier(icon, "drawable", appContext.getPackageName());
    }

    private SettingItem getSettingItem(UItem item) {
        return item != null && item.object2 instanceof SettingItem ? (SettingItem) item.object2 : null;
    }

    private String getSettingKey(UItem item) {
        return item != null && item.object instanceof String ? (String) item.object : null;
    }

    private void onClick(final UItem item, View view, int position, float x, float y) {
        if (item == null || plugin == null) {
            return;
        }
        final SettingItem settingItem = getSettingItem(item);
        if (settingItem instanceof TextSetting) {
            final TextSetting textSetting = (TextSetting) settingItem;
            if (textSetting.createSubFragmentCallback != null) {
                PluginsController.runOnPluginsQueue(new Runnable() {
                    @Override
                    public void run() {
                        openSubFragment(textSetting, item);
                    }
                });
                return;
            }
            if (textSetting.onClickCallback != null) {
                try {
                    textSetting.onClickCallback.call(view);
                } catch (Exception ignored) {
                }
                return;
            }
        } else if (settingItem instanceof CustomSetting) {
            final CustomSetting customSetting = (CustomSetting) settingItem;
            if (customSetting.createSubFragmentCallback != null) {
                PluginsController.runOnPluginsQueue(new Runnable() {
                    @Override
                    public void run() {
                        openCustomSubFragment(customSetting, item);
                    }
                });
                return;
            }
            if (customSetting.factory != null) {
                customSetting.factory.onClick(plugin, item, view);
                return;
            }
            if (customSetting.onClickCallback != null) {
                try {
                    customSetting.onClickCallback.call(view);
                } catch (Exception ignored) {
                }
                return;
            }
        }

        final String key = getSettingKey(item);
        if (key == null) {
            return;
        }
        if (view instanceof TextCheckCell) {
            final boolean checked = !((TextCheckCell) view).isChecked();
            ((TextCheckCell) view).setChecked(checked);
            item.setChecked(checked);
            PluginsController.runOnPluginsQueue(new Runnable() {
                @Override
                public void run() {
                    PluginsController.getInstance().setPluginSetting(plugin.getId(), key, checked);
                    if (settingItem instanceof SwitchSetting) {
                        triggerOnChange(((SwitchSetting) settingItem).onChangeCallback, key, checked);
                    }
                }
            });
        } else if (view instanceof NotificationsCheckCell) {
            final boolean checked = !((NotificationsCheckCell) view).isChecked();
            ((NotificationsCheckCell) view).setChecked(checked);
            item.setChecked(checked);
            PluginsController.runOnPluginsQueue(new Runnable() {
                @Override
                public void run() {
                    PluginsController.getInstance().setPluginSetting(plugin.getId(), key, checked);
                    if (settingItem instanceof SwitchSetting) {
                        triggerOnChange(((SwitchSetting) settingItem).onChangeCallback, key, checked);
                    }
                }
            });
        } else if (view instanceof TextCell) {
            if (settingItem instanceof SelectorSetting) {
                showSelectorDialog(item, view, key, (SelectorSetting) settingItem);
            } else if (settingItem instanceof InputSetting) {
                showStringInputDialog(item, view, key, (InputSetting) settingItem);
            }
        }
    }

    private void openSubFragment(final TextSetting textSetting, final UItem item) {
        final List<SettingItem> items = new ArrayList<>();
        try {
            PyObject result = textSetting.createSubFragmentCallback.call(new Object[0]);
            if (result != null) {
                PluginsController.PluginsEngine engine = PluginsController.engines.get(PluginsConstants.PYTHON);
                if (engine instanceof PythonPluginsEngine) {
                    items.addAll(((PythonPluginsEngine) engine).parsePySettingDefinitions(result.asList()));
                }
            }
        } catch (Exception ignored) {
        }
        if (items.isEmpty()) {
            return;
        }
        AndroidUtilities.runOnUIThread(new Runnable() {
            @Override
            public void run() {
                SettingItem currentSettingItem = getSettingItem(item);
                String prefix = currentSettingItem != null && !TextUtils.isEmpty(currentSettingItem.linkAlias) ? currentSettingItem.linkAlias : null;
                if (!TextUtils.isEmpty(settingsLinkPrefix) && !TextUtils.isEmpty(prefix)) {
                    prefix = settingsLinkPrefix + ":" + prefix;
                } else if (!TextUtils.isEmpty(settingsLinkPrefix)) {
                    prefix = settingsLinkPrefix;
                }
                presentFragment(new PluginSettingsActivity(plugin, item.text != null ? item.text.toString() : plugin.getName(), items, textSetting.createSubFragmentCallback).setSettingsLinkPrefix(prefix));
            }
        });
    }

    private void openCustomSubFragment(final CustomSetting customSetting, final UItem item) {
        final List<SettingItem> items = new ArrayList<>();
        try {
            PyObject result = customSetting.createSubFragmentCallback.call(new Object[0]);
            if (result != null) {
                PluginsController.PluginsEngine engine = PluginsController.engines.get(PluginsConstants.PYTHON);
                if (engine instanceof PythonPluginsEngine) {
                    items.addAll(((PythonPluginsEngine) engine).parsePySettingDefinitions(result.asList()));
                }
            }
        } catch (Exception ignored) {
        }
        if (items.isEmpty()) {
            return;
        }
        AndroidUtilities.runOnUIThread(new Runnable() {
            @Override
            public void run() {
                SettingItem currentSettingItem = getSettingItem(item);
                String prefix = currentSettingItem != null && !TextUtils.isEmpty(currentSettingItem.linkAlias) ? currentSettingItem.linkAlias : null;
                if (!TextUtils.isEmpty(settingsLinkPrefix) && !TextUtils.isEmpty(prefix)) {
                    prefix = settingsLinkPrefix + ":" + prefix;
                } else if (!TextUtils.isEmpty(settingsLinkPrefix)) {
                    prefix = settingsLinkPrefix;
                }
                presentFragment(new PluginSettingsActivity(plugin, item.text != null ? item.text.toString() : plugin.getName(), items, customSetting.createSubFragmentCallback).setSettingsLinkPrefix(prefix));
            }
        });
    }

    private Boolean onLongClick(final UItem item, View view, int position, float x, float y) {
        if (item == null || plugin == null) {
            return false;
        }
        final SettingItem settingItem = getSettingItem(item);
        if (settingItem == null) {
            return false;
        }
        if (!TextUtils.isEmpty(settingItem.linkAlias)) {
            view.performHapticFeedback(VibratorUtils.getType(HapticFeedbackConstants.LONG_PRESS), 1);
            ItemOptions.makeOptions(this, view)
                    .add(R.drawable.msg_copy, LocaleController.getString(R.string.CopyLink), new Runnable() {
                        @Override
                        public void run() {
                            if (AndroidUtilities.addToClipboard(settingItem.getLink(plugin.getId(), settingsLinkPrefix))) {
                                BulletinFactory.of(PluginSettingsActivity.this).createCopyBulletin(LocaleController.getString(R.string.LinkCopied)).show();
                            }
                        }
                    })
                    .show();
            return true;
        }
        if (settingItem.onLongClickCallback != null) {
            try {
                settingItem.onLongClickCallback.call(view);
            } catch (Exception ignored) {
            }
            return true;
        }
        if (settingItem instanceof CustomSetting) {
            CustomSetting customSetting = (CustomSetting) settingItem;
            if (customSetting.factory != null) {
                customSetting.factory.onLongClick(plugin, item, view);
                return true;
            }
        }
        return false;
    }

    private void showStringInputDialog(UItem item, final View view, final String key, final InputSetting setting) {
        if (getParentActivity() == null) {
            return;
        }
        final AlertDialog[] dialogs = new AlertDialog[1];
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext(), getResourceProvider());
        builder.setTitle(item.text);
        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(LinearLayout.VERTICAL);

        if (setting.subtext != null) {
            TextView textView = new TextView(getContext());
            textView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, getResourceProvider()));
            textView.setTextSize(1, 16);
            textView.setText(setting.subtext);
            layout.addView(textView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 5, 24, 12));
        }

        final EditTextBoldCursor editText = new EditTextBoldCursor(getContext());
        editText.lineYFix = true;
        editText.setTextSize(1, 18);
        editText.setText(PluginsController.getInstance().getPluginSettingString(plugin.getId(), key, setting.defaultValue));
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, getResourceProvider()));
        editText.setHintColor(Theme.getColor(Theme.key_groupcreate_hintText, getResourceProvider()));
        editText.setHintText(LocaleController.getString(R.string.EnterValue));
        editText.setFocusable(true);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        editText.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated, getResourceProvider()));
        editText.setLineColors(
                Theme.getColor(Theme.key_windowBackgroundWhiteInputField, getResourceProvider()),
                Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated, getResourceProvider()),
                Theme.getColor(Theme.key_text_RedRegular, getResourceProvider()));
        editText.setBackgroundDrawable(null);
        editText.setPadding(0, AndroidUtilities.dp(6), 0, AndroidUtilities.dp(6));
        layout.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 10));

        builder.makeCustomMaxHeight();
        builder.setView(layout);
        builder.setWidth(AndroidUtilities.dp(292));
        builder.setPositiveButton(LocaleController.getString(R.string.Done), new AlertDialog.OnButtonClickListener() {
            @Override
            public void onClick(AlertDialog alertDialog, int which) {
                final String value = editText.getText().toString();
                if (dialogs[0] != null) {
                    dialogs[0].dismiss();
                }
                ((TextCell) view).setValue(value, true);
                PluginsController.runOnPluginsQueue(new Runnable() {
                    @Override
                    public void run() {
                        PluginsController.getInstance().setPluginSetting(plugin.getId(), key, value);
                        triggerOnChange(setting.onChangeCallback, key, value);
                    }
                });
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), new AlertDialog.OnButtonClickListener() {
            @Override
            public void onClick(AlertDialog alertDialog, int which) {
                alertDialog.dismiss();
            }
        });
        dialogs[0] = builder.create();
        dialogs[0].setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialogInterface) {
                AndroidUtilities.hideKeyboard(editText);
            }
        });
        dialogs[0].setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialogInterface) {
                editText.requestFocus();
                editText.setSelection(editText.length());
                AndroidUtilities.showKeyboard(editText);
            }
        });
        dialogs[0].setDismissDialogByButtons(false);
        showDialog(dialogs[0]);
    }

    private void showSelectorDialog(UItem item, final View view, final String key, final SelectorSetting setting) {
        if (getParentActivity() == null) {
            return;
        }
        final AtomicReference<AlertDialog> dialogRef = new AtomicReference<>();
        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        final String[] values = setting.items;
        for (int i = 0; i < values.length; i++) {
            final int index = i;
            RadioColorCell cell = new RadioColorCell(getParentActivity());
            cell.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), 0);
            cell.setCheckColor(Theme.getColor(Theme.key_radioBackground), Theme.getColor(Theme.key_dialogRadioBackgroundChecked));
            cell.setTextAndValue(values[i], PluginsController.getInstance().getPluginSettingInt(plugin.getId(), key, setting.defaultValue) == i);
            cell.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
            layout.addView(cell);
            cell.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    AlertDialog dialog = dialogRef.get();
                    if (dialog != null) {
                        dialog.dismiss();
                    }
                    ((TextCell) view).setValue(values[index], true);
                    PluginsController.runOnPluginsQueue(new Runnable() {
                        @Override
                        public void run() {
                            PluginsController.getInstance().setPluginSetting(plugin.getId(), key, index);
                            triggerOnChange(setting.onChangeCallback, key, index);
                        }
                    });
                }
            });
        }
        AlertDialog dialog = new AlertDialog.Builder(getParentActivity()).setTitle(item.text).setView(layout).setNegativeButton(LocaleController.getString(R.string.Cancel), null).create();
        dialogRef.set(dialog);
        showDialog(dialog);
    }

    private int getStableId(SettingItem settingItem) {
        if (settingItem instanceof SwitchSetting) {
            return Objects.hash(PluginsConstants.Settings.TYPE_SWITCH, ((SwitchSetting) settingItem).key);
        } else if (settingItem instanceof InputSetting) {
            return Objects.hash(PluginsConstants.Settings.TYPE_INPUT, ((InputSetting) settingItem).key);
        } else if (settingItem instanceof EditTextSetting) {
            return Objects.hash(PluginsConstants.Settings.TYPE_EDIT_TEXT, ((EditTextSetting) settingItem).key);
        } else if (settingItem instanceof SelectorSetting) {
            return Objects.hash(PluginsConstants.Settings.TYPE_SELECTOR, ((SelectorSetting) settingItem).key);
        } else if (settingItem instanceof HeaderSetting) {
            return Objects.hash(PluginsConstants.Settings.TYPE_HEADER, ((HeaderSetting) settingItem).text);
        } else if (settingItem instanceof DividerSetting) {
            return Objects.hash(PluginsConstants.Settings.TYPE_DIVIDER, ((DividerSetting) settingItem).text);
        } else if (settingItem instanceof TextSetting) {
            return Objects.hash(PluginsConstants.Settings.TYPE_TEXT, ((TextSetting) settingItem).text);
        } else if (settingItem instanceof CustomSetting) {
            CustomSetting setting = (CustomSetting) settingItem;
            Integer factoryHash = setting.factory != null ? setting.factory.hashCode() : null;
            Integer itemId = setting.item != null ? setting.item.id : null;
            Integer factoryArgsHash = setting.factoryArgs != null ? setting.factoryArgs.hashCode() : null;
            return Objects.hash(PluginsConstants.Settings.TYPE_CUSTOM, factoryHash, itemId, factoryArgsHash);
        }
        return settingItem.hashCode();
    }

    private void triggerOnChange(final PyObject callback, final String key, final Object value) {
        PluginsController.runOnPluginsQueue(new Runnable() {
            @Override
            public void run() {
                if (callback != null) {
                    try {
                        callback.call(value);
                    } catch (Exception e) {
                        FileLog.e("Error executing on_change callback for " + plugin.getId() + "/" + key, e);
                    }
                }
            }
        });
    }

    @Override
    public int getNavigationBarColor() {
        return Theme.getColor(Theme.key_windowBackgroundGray, getResourceProvider());
    }
}


