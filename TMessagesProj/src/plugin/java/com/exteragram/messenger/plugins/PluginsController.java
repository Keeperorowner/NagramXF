package com.exteragram.messenger.plugins;

import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import com.chaquo.python.PyObject;
import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.plugins.hooks.EventHookRecord;
import com.exteragram.messenger.plugins.hooks.HookRecord;
import com.exteragram.messenger.plugins.hooks.MenuItemRecord;
import com.exteragram.messenger.plugins.hooks.PluginsHooks;
import com.exteragram.messenger.plugins.hooks.XposedHookRecord;
import com.exteragram.messenger.plugins.models.SettingItem;
import com.exteragram.messenger.plugins.ui.PluginsActivity;
import com.exteragram.messenger.plugins.ui.components.InstallPluginBottomSheet;
import com.exteragram.messenger.plugins.ui.components.SafeModeBottomSheet;
import com.exteragram.messenger.plugins.utils.MenuContextBuilder;
import com.exteragram.messenger.plugins.utils.NativeCrashHandler;
import com.exteragram.messenger.plugins.utils.PluginsWatchdog;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import tw.nekomimi.nekogram.helpers.MessageHelper;

public class PluginsController implements PluginsHooks {
    public static final String PREF_PLUGIN_ENABLED_KEY_PREFIX = "plugin_enabled_";
    public static final String PREF_PENDING_CRASH = "had_crash";
    public static final String PREF_CRASHED_PLUGIN_ID = "crashed_plugin_id";
    public static final String PREF_SAFE_MODE_REASON = "safe_mode_reason";

    public static final class SafeModeReason {
        public static final String MANUAL = "manual";
        public static final String NATIVE_CRASH = "native_crash";
        public static final String PLUGIN_CRASH = "plugin_crash";
        public static final String CRASH = "crash";

        private SafeModeReason() {
        }
    }

    public static final int PLUGIN_FILE_ICON_NONE = -1;
    public static final int PLUGIN_FILE_ICON_START = 101;

    public static final ConcurrentHashMap<String, PluginsEngine> engines = new ConcurrentHashMap<>();

    static {
        engines.put(PluginsConstants.PYTHON, PythonPluginsEngine.INSTANCE);
    }

    private volatile Map<String, List<EventHookRecord>> exactMatchEventHooksCache = Collections.emptyMap();
    private volatile List<EventHookRecord> substringMatchEventHooksCache = Collections.emptyList();

    public File pluginsDir;
    public final ConcurrentHashMap<String, Plugin> plugins = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<String, List<SettingItem>> settings = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MenuItemRecord> menuItemsById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<MenuItemRecord>> menuItemsByMenuType = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<HookRecord>> hooks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<String>> interestedPluginsCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> fileIconIdsByExtension = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Drawable> fileIconDrawablesById = new ConcurrentHashMap<>();
    private final AtomicInteger nextFileIconId = new AtomicInteger(PLUGIN_FILE_ICON_START);
    private final Object hooksCacheLock = new Object();
    private volatile boolean hooksCacheDirty = true;

    public SharedPreferences preferences = ApplicationLoader.applicationContext != null
            ? ApplicationLoader.applicationContext.getSharedPreferences("plugin_settings", 0)
            : null;
    public final PluginsWatchdog watchdog = new PluginsWatchdog(this);

    private final Runnable updateNotificationRunnable = () -> {
        NotificationCenter.getGlobalInstance().postNotificationNameOnUIThread(NotificationCenter.pluginsUpdated);
        NotificationCenter.getGlobalInstance().postNotificationNameOnUIThread(NotificationCenter.pluginMenuItemsUpdated);
    };

    public interface PluginsEngine {
        boolean canOpenInExternalApp();

        void checkDevServer();

        void clearPluginSettings(String pluginId);

        void deletePlugin(String pluginId, Utilities.Callback<String> callback);

        void executeOnAppEvent(String eventName);

        HookResult<PostRequestResult> executePostRequestHook(String hookName, int account, TLObject response, TLRPC.TL_error error, String pluginId);

        HookResult<TLObject> executePreRequestHook(String hookName, int account, TLObject request, String pluginId);

        HookResult<SendMessagesHelper.SendMessageParams> executeSendMessageHook(int account, SendMessagesHelper.SendMessageParams params, String pluginId);

        HookResult<TLRPC.Update> executeUpdateHook(String hookName, int account, TLRPC.Update update, String pluginId);

        HookResult<TLRPC.Updates> executeUpdatesHook(String hookName, int account, TLRPC.Updates updates, String pluginId);

        Map<String, ?> getAllPluginSettings(String pluginId);

        String getPluginPath(String pluginId);

        Object getPluginSetting(String pluginId, String key, Object defaultValue);

        void init(Runnable runnable);

        boolean isEngineAvailable();

        boolean isPlugin(File file);

        List<SettingItem> loadPluginSettings(String pluginId);

        void openInExternalApp(String pluginId);

        void openPluginSetting(Plugin plugin, String settingId, BaseFragment baseFragment);

        void openPluginSetting(String pluginId, String settingId, BaseFragment baseFragment);

        void openPluginSettings(Plugin plugin, BaseFragment baseFragment);

        void openPluginSettings(String pluginId, BaseFragment baseFragment);

        void setPluginEnabled(String pluginId, boolean enabled, Utilities.Callback<String> callback);

        void setPluginSetting(String pluginId, String key, Object value);

        void sharePlugin(String pluginId);

        void showInstallDialog(BaseFragment baseFragment, InstallPluginBottomSheet.PluginInstallParams pluginInstallParams);

        void shutdown(Runnable runnable);
    }

    private static SharedPreferences getPluginSettingsPreferences() {
        if (ApplicationLoader.applicationContext == null) {
            return null;
        }
        return ApplicationLoader.applicationContext.getSharedPreferences("plugin_settings", 0);
    }

    private static void setSafeModeFlag(boolean enabled) {
        ExteraConfig.pluginsSafeMode = enabled;
        if (ExteraConfig.editor != null) {
            ExteraConfig.editor.putBoolean("pluginsSafeMode", enabled).apply();
        } else if (ApplicationLoader.applicationContext != null) {
            ApplicationLoader.applicationContext.getSharedPreferences("exteraconfig", 0)
                    .edit()
                    .putBoolean("pluginsSafeMode", enabled)
                    .apply();
        }
    }

    private static void persistSafeModeState(boolean enabled, String reason, String crashedPluginId, boolean hadCrash) {
        SharedPreferences preferences = getPluginSettingsPreferences();
        if (preferences != null) {
            SharedPreferences.Editor editor = preferences.edit();
            if (hadCrash) {
                editor.putBoolean(PREF_PENDING_CRASH, true);
            } else {
                editor.remove(PREF_PENDING_CRASH);
            }
            if (TextUtils.isEmpty(reason)) {
                editor.remove(PREF_SAFE_MODE_REASON);
            } else {
                editor.putString(PREF_SAFE_MODE_REASON, reason);
            }
            if (TextUtils.isEmpty(crashedPluginId)) {
                editor.remove(PREF_CRASHED_PLUGIN_ID);
            } else {
                editor.putString(PREF_CRASHED_PLUGIN_ID, crashedPluginId);
            }
            editor.apply();
        }
        setSafeModeFlag(enabled);
    }

    public static void enableManualSafeMode() {
        persistSafeModeState(true, SafeModeReason.MANUAL, null, false);
    }

    public static void disableSafeMode() {
        persistSafeModeState(false, null, null, false);
    }

    public static void markPendingSafeModeCrash(String reason, String crashedPluginId) {
        persistSafeModeState(true, reason, crashedPluginId, true);
    }

    public static String getSafeModeReason() {
        SharedPreferences preferences = getPluginSettingsPreferences();
        String reason = preferences != null ? preferences.getString(PREF_SAFE_MODE_REASON, null) : null;
        if (TextUtils.isEmpty(reason) && ExteraConfig.pluginsSafeMode) {
            return SafeModeReason.MANUAL;
        }
        return reason;
    }

    public static String getSafeModeCrashedPluginId() {
        SharedPreferences preferences = getPluginSettingsPreferences();
        return preferences != null ? preferences.getString(PREF_CRASHED_PLUGIN_ID, null) : null;
    }

    public static CharSequence getSafeModeStatusText() {
        String reason = getSafeModeReason();
        if (SafeModeReason.MANUAL.equals(reason)) {
            return LocaleController.getString(R.string.PluginsSafeModeInfoManual);
        }
        if (SafeModeReason.NATIVE_CRASH.equals(reason)) {
            return LocaleController.getString(R.string.PluginsSafeModeInfoNativeCrash);
        }
        if (SafeModeReason.PLUGIN_CRASH.equals(reason)) {
            String pluginId = getSafeModeCrashedPluginId();
            if (!TextUtils.isEmpty(pluginId)) {
                Plugin plugin = getInstance().plugins.get(pluginId);
                String pluginName = plugin != null ? plugin.getName() : pluginId;
                return LocaleController.formatString(R.string.PluginsSafeModeInfoPluginCrash, pluginName);
            }
        }
        return LocaleController.getString(R.string.PluginsSafeModeInfoCrashGeneric);
    }

    public static PluginsController getInstance() {
        return SingletonHolder.INSTANCE;
    }

    public static boolean isPluginEngineSupported() {
        return Build.VERSION.SDK_INT >= 24;
    }

    private static boolean hasAvailablePluginsEngine() {
        for (PluginsEngine engine : engines.values()) {
            if (engine == null) {
                continue;
            }
            try {
                if (engine.isEngineAvailable()) {
                    return true;
                }
            } catch (Throwable t) {
                FileLog.e("Error checking engine availability.", t);
            }
        }
        return false;
    }

    public static boolean isPluginEngineAvailable() {
        if (!isPluginEngineSupported() || !ExteraConfig.pluginsEngine || ExteraConfig.pluginsSafeMode) {
            return false;
        }
        return hasAvailablePluginsEngine();
    }

    public static void applyArtOpts() {
        if (ExteraConfig.pluginsDisableArtOpts && isPluginEngineSupported()) {
            try {
                XposedBridge.disableProfileSaver();
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
    }

    public static boolean isPluginNativeRuntimeAvailable() {
        return de.robv.android.xposed.XposedBridge.isNativeAvailable();
    }

    public static boolean isPluginRuntimeOperational() {
        return isPluginEngineAvailable() && isPluginNativeRuntimeAvailable();
    }

    public static String getPluginRuntimeIssue() {
        if (!isPluginEngineSupported()) {
            return "unsupported";
        }
        if (!ExteraConfig.pluginsEngine) {
            return "disabled";
        }
        if (ExteraConfig.pluginsSafeMode) {
            return "safe_mode";
        }
        if (!hasAvailablePluginsEngine()) {
            return "python_runtime_unavailable";
        }
        if (!isPluginNativeRuntimeAvailable()) {
            return "native_runtime_unavailable";
        }
        return null;
    }

    public static boolean isPlugin(MessageObject messageObject) {
        if (messageObject == null || messageObject.getDocumentName() == null || !isPluginEngineSupported()) {
            return false;
        }
        String path = MessageHelper.getPathToMessage(messageObject);
        return !TextUtils.isEmpty(path) && isPlugin(new File(path));
    }

    public static boolean isPlugin(File file) {
        if (file == null) {
            return false;
        }
        for (PluginsEngine engine : engines.values()) {
            if (engine != null && engine.isPlugin(file)) {
                return true;
            }
        }
        return false;
    }

    public static PluginsEngine getPluginEngine(File file) {
        if (file == null) {
            return null;
        }
        for (PluginsEngine engine : engines.values()) {
            if (engine != null && engine.isPlugin(file)) {
                return engine;
            }
        }
        return null;
    }

    public static int registerFileIcon(String extension, Drawable drawable) {
        return getInstance().registerFileIconInternal(extension, drawable);
    }

    public static void unregisterFileIcon(String extension) {
        getInstance().unregisterFileIconInternal(extension);
    }

    public static void clearFileIcons() {
        getInstance().clearFileIconsInternal();
    }

    public static int getFileIconId(String fileName) {
        return getInstance().getFileIconIdInternal(fileName);
    }

    public static boolean isPluginFileIcon(int icon) {
        return getInstance().fileIconDrawablesById.containsKey(icon);
    }

    public static Drawable getPluginFileIconDrawable(int icon) {
        return getInstance().fileIconDrawablesById.get(icon);
    }

    private int registerFileIconInternal(String extension, Drawable drawable) {
        String normalized = normalizeFileExtension(extension);
        if (normalized == null || drawable == null) {
            return PLUGIN_FILE_ICON_NONE;
        }
        Integer existing = fileIconIdsByExtension.get(normalized);
        if (existing != null) {
            fileIconDrawablesById.put(existing, drawable);
            return existing;
        }
        int id = nextFileIconId.getAndIncrement();
        Integer raceWinner = fileIconIdsByExtension.putIfAbsent(normalized, id);
        if (raceWinner != null) {
            id = raceWinner;
        }
        fileIconDrawablesById.put(id, drawable);
        return id;
    }

    private void unregisterFileIconInternal(String extension) {
        String normalized = normalizeFileExtension(extension);
        if (normalized == null) {
            return;
        }
        Integer id = fileIconIdsByExtension.remove(normalized);
        if (id != null) {
            fileIconDrawablesById.remove(id);
        }
    }

    private void clearFileIconsInternal() {
        fileIconIdsByExtension.clear();
        fileIconDrawablesById.clear();
    }

    private int getFileIconIdInternal(String fileName) {
        if (TextUtils.isEmpty(fileName) || fileIconIdsByExtension.isEmpty()) {
            return PLUGIN_FILE_ICON_NONE;
        }
        int lastDot = fileName.lastIndexOf('.');
        String extension = lastDot >= 0 ? fileName.substring(lastDot + 1) : fileName;
        String normalized = normalizeFileExtension(extension);
        if (normalized == null) {
            return PLUGIN_FILE_ICON_NONE;
        }
        Integer id = fileIconIdsByExtension.get(normalized);
        return id != null ? id : PLUGIN_FILE_ICON_NONE;
    }

    private static String normalizeFileExtension(String extension) {
        if (TextUtils.isEmpty(extension)) {
            return null;
        }
        return extension.toLowerCase(java.util.Locale.ROOT);
    }

    public static void openPluginSetting(String pluginId, String settingId) {
        BaseFragment lastFragment = LaunchActivity.getLastFragment();
        if (TextUtils.isEmpty(pluginId) || lastFragment == null) {
            return;
        }
        if (!ExteraConfig.pluginsEngine) {
            BulletinFactory.of(lastFragment)
                    .createSimpleBulletin(
                            R.raw.error,
                            LocaleController.formatString(R.string.PluginEngineNotEnabled, pluginId),
                            LocaleController.getString(R.string.Enable),
                            2750,
                            () -> lastFragment.presentFragment(new PluginsActivity()))
                    .show();
            return;
        }
        Plugin plugin = getInstance().plugins.get(pluginId);
        if (plugin == null) {
            BulletinFactory.of(lastFragment)
                    .createSimpleBulletin(R.raw.error, LocaleController.formatString(R.string.PluginNotFound, pluginId))
                    .show();
            return;
        }
        if (!getInstance().hasPluginSettings(pluginId)) {
            BulletinFactory.of(lastFragment)
                    .createSimpleBulletin(R.raw.error, LocaleController.formatString(R.string.PluginHasNoSettings, plugin.getName()))
                    .show();
            return;
        }
        PluginsEngine engine = getInstance().getPluginEngine(pluginId);
        if (engine != null) {
            engine.openPluginSetting(pluginId, settingId, lastFragment);
        }
    }

    public PluginsEngine getPluginEngine(String pluginId) {
        if (TextUtils.isEmpty(pluginId)) {
            return null;
        }
        Plugin plugin = plugins.get(pluginId);
        if (plugin == null) {
            return null;
        }
        if (plugin.cachedEngine != null) {
            return plugin.cachedEngine;
        }
        PluginsEngine engine = engines.get(plugin.getEngine());
        if (engine != null) {
            plugin.cachedEngine = engine;
        }
        return engine;
    }

    public static boolean isPluginPinned(String pluginId) {
        return !TextUtils.isEmpty(pluginId) && ExteraConfig.pinnedPlugins.contains(pluginId);
    }

    public static void setPluginPinned(String pluginId, boolean pinned) {
        if (TextUtils.isEmpty(pluginId)) {
            return;
        }
        java.util.HashSet<String> set = new java.util.HashSet<>(ExteraConfig.pinnedPlugins);
        if (pinned) {
            set.add(pluginId);
        } else {
            set.remove(pluginId);
        }
        ExteraConfig.pinnedPlugins = set;
        ExteraConfig.editor.putStringSet("pinnedPlugins", set).apply();
        getInstance().notifyPluginsChanged();
    }

    public void init() {
        init(null);
    }

    public static void runOnPluginsQueue(Runnable runnable) {
        if (Utilities.pluginsQueue == null || !Utilities.pluginsQueue.isAlive()) {
            synchronized (PluginsController.class) {
                if (Utilities.pluginsQueue == null || !Utilities.pluginsQueue.isAlive()) {
                    Utilities.pluginsQueue = new DispatchQueue("pluginsQueue");
                }
            }
        }
        Utilities.pluginsQueue.postRunnable(runnable);
    }

    public void init(Runnable runnable) {
        if (!isPluginEngineSupported() || !ExteraConfig.pluginsEngine) {
            if (runnable != null) {
                runnable.run();
            }
            return;
        }

        NativeCrashHandler.checkAndHandleNativeCrash();
        applyArtOpts();
        watchdog.start();
        if (Utilities.pluginsQueue == null || !Utilities.pluginsQueue.isAlive()) {
            Utilities.pluginsQueue = new DispatchQueue("pluginsQueue");
        }
        if (preferences == null && ApplicationLoader.applicationContext != null) {
            preferences = ApplicationLoader.applicationContext.getSharedPreferences("plugin_settings", 0);
        }

        try {
            boolean hadCrash = preferences != null && preferences.getBoolean(PREF_PENDING_CRASH, false);
            String crashedPluginId = preferences != null ? preferences.getString(PREF_CRASHED_PLUGIN_ID, null) : null;
            String safeModeReason = preferences != null ? preferences.getString(PREF_SAFE_MODE_REASON, null) : null;
            boolean manualSafeMode = SafeModeReason.MANUAL.equals(safeModeReason);
            if (TextUtils.isEmpty(safeModeReason) && ExteraConfig.pluginsSafeMode) {
                safeModeReason = SafeModeReason.MANUAL;
                manualSafeMode = true;
                if (preferences != null) {
                    preferences.edit().putString(PREF_SAFE_MODE_REASON, safeModeReason).apply();
                }
            }
            if (preferences != null) {
                preferences.edit().remove(PREF_PENDING_CRASH).apply();
            }
            if (hadCrash) {
                if (!TextUtils.isEmpty(crashedPluginId) && SafeModeReason.PLUGIN_CRASH.equals(safeModeReason) && preferences != null) {
                    preferences.edit().putBoolean(PREF_PLUGIN_ENABLED_KEY_PREFIX + crashedPluginId, false).apply();
                }
                setSafeModeFlag(true);
                if (!manualSafeMode) {
                    AndroidUtilities.runOnUIThread(() -> {
                        BaseFragment lastFragment = LaunchActivity.getLastFragment();
                        if (lastFragment != null) {
                            new SafeModeBottomSheet(lastFragment).show();
                        }
                    }, 800);
                }
            }
        } catch (Exception ignored) {
        }

        pluginsDir = new File(ApplicationLoader.getFilesDirFixed(), PluginsConstants.PLUGINS);
        if (!pluginsDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            pluginsDir.mkdirs();
        }

        if (engines.isEmpty()) {
            if (runnable != null) {
                runnable.run();
            }
            return;
        }

        AtomicInteger completed = new AtomicInteger();
        Runnable onEngineReady = () -> {
            if (completed.incrementAndGet() >= engines.size() && runnable != null) {
                runnable.run();
            }
        };
        for (PluginsEngine engine : engines.values()) {
            if (engine != null) {
                try {
                    engine.init(onEngineReady);
                } catch (Throwable t) {
                    FileLog.e("Failed to initialize plugins engine.", t);
                    onEngineReady.run();
                }
            } else {
                onEngineReady.run();
            }
        }
    }

    public void checkDevServers() {
        for (PluginsEngine engine : engines.values()) {
            if (engine != null) {
                engine.checkDevServer();
            }
        }
    }

    public void shutdown(Runnable runnable) {
        runOnPluginsQueue(() -> {
            if (engines.isEmpty()) {
                watchdog.stop();
                plugins.clear();
                settings.clear();
                FileLog.d("Plugin system shut down.");
                if (runnable != null) {
                    runnable.run();
                }
                return;
            }
            AtomicInteger completed = new AtomicInteger();
            Runnable done = () -> {
                if (completed.incrementAndGet() >= engines.size()) {
                    watchdog.stop();
                    plugins.clear();
                    settings.clear();
                    FileLog.d("Plugin system shut down.");
                    if (runnable != null) {
                        runnable.run();
                    }
                }
            };
            for (PluginsEngine engine : engines.values()) {
                if (engine != null) {
                    engine.shutdown(done);
                } else {
                    done.run();
                }
            }
        });
    }

    public void restart() {
        FileLog.d("Restarting plugins engine...");
        shutdown(() -> {
            if (ExteraConfig.pluginsEngine) {
                init(() -> FileLog.d("Plugins engine restarted."));
            }
        });
    }

    public boolean isPluginActive(String pluginId) {
        if (TextUtils.isEmpty(pluginId)) {
            return false;
        }
        Plugin plugin = plugins.get(pluginId);
        return plugin != null && plugin.isEnabled();
    }

    public boolean isPluginActive(Plugin plugin) {
        return plugin != null && plugins.get(plugin.getId()) == plugin && plugin.isEnabled();
    }

    public List<SettingItem> getPluginSettingsList(String pluginId) {
        if (TextUtils.isEmpty(pluginId)) {
            return null;
        }
        return settings.get(pluginId);
    }

    public void setPluginEnabled(String pluginId, boolean enabled, Utilities.Callback<String> callback) {
        runOnPluginsQueue(() -> {
            PluginsEngine engine = getPluginEngine(pluginId);
            if (engine == null) {
                notifyEngineMissing(pluginId, callback);
                return;
            }
            engine.setPluginEnabled(pluginId, enabled, callback);
            interestedPluginsCache.clear();
        });
    }

    public void deletePlugin(String pluginId, Utilities.Callback<String> callback) {
        runOnPluginsQueue(() -> {
            PluginsEngine engine = getPluginEngine(pluginId);
            if (engine == null) {
                notifyEngineMissing(pluginId, callback);
                return;
            }
            engine.deletePlugin(pluginId, callback);
        });
    }

    private void notifyEngineMissing(String pluginId, Utilities.Callback<String> callback) {
        final String error = "No plugins engine found for plugin: " + pluginId;
        FileLog.e(error);
        if (callback != null) {
            AndroidUtilities.runOnUIThread(() -> callback.run(error));
        }
    }

    void cleanupPlugin(String pluginId) {
        removeHooksByPluginId(pluginId);
        invalidatePluginSettings(pluginId);
        removeMenuItemsByPluginId(pluginId);
    }

    public String getPluginPath(String pluginId) {
        PluginsEngine engine = getPluginEngine(pluginId);
        return engine != null ? engine.getPluginPath(pluginId) : null;
    }

    public void showInstallDialog(BaseFragment baseFragment, MessageObject messageObject) {
        try {
            showInstallDialog(baseFragment, InstallPluginBottomSheet.PluginInstallParams.of(messageObject));
        } catch (Throwable t) {
            showInstallDialogError(baseFragment, messageObject != null ? messageObject.getDocumentName() : null, t);
        }
    }

    public void showInstallDialog(BaseFragment baseFragment, String filePath, boolean external) {
        try {
            showInstallDialog(baseFragment, new InstallPluginBottomSheet.PluginInstallParams(filePath, external));
        } catch (Throwable t) {
            showInstallDialogError(baseFragment, filePath != null ? new File(filePath).getName() : null, t);
        }
    }

    private void showInstallDialog(BaseFragment baseFragment, InstallPluginBottomSheet.PluginInstallParams params) {
        if (baseFragment == null || params == null || TextUtils.isEmpty(params.filePath) || !AndroidUtilities.isActivityRunning(baseFragment.getParentActivity())) {
            return;
        }
        File file = new File(params.filePath);
        if (!ExteraConfig.pluginsEngine) {
            BulletinFactory.of(baseFragment)
                    .createSimpleBulletin(
                            R.raw.error,
                            LocaleController.formatString(R.string.PluginNotEnabled, file.getName()),
                            LocaleController.getString(R.string.Enable),
                            2750,
                            () -> baseFragment.presentFragment(new PluginsActivity()))
                    .show();
            return;
        }
        PluginsEngine engine = getPluginEngine(file);
        if (engine != null) {
            try {
                engine.showInstallDialog(baseFragment, params);
            } catch (Throwable t) {
                showInstallDialogError(baseFragment, file.getName(), t);
            }
        }
    }

    private void showInstallDialogError(BaseFragment baseFragment, String pluginName, Throwable t) {
        FileLog.e("Failed to show install dialog for plugin: " + pluginName, t);
        if (baseFragment == null || !AndroidUtilities.isActivityRunning(baseFragment.getParentActivity())) {
            return;
        }
        final String finalPluginName = TextUtils.isEmpty(pluginName) ? "plugin" : pluginName;
        final String stackTrace = Log.getStackTraceString(t);
        AndroidUtilities.runOnUIThread(() ->
                BulletinFactory.of(baseFragment)
                        .createSimpleBulletin(
                                R.raw.error,
                                LocaleController.formatString(R.string.PluginInstallError, finalPluginName),
                                LocaleController.getString(R.string.Copy),
                                () -> {
                                    if (AndroidUtilities.addToClipboard(stackTrace)) {
                                        BulletinFactory.of(baseFragment).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
                                    }
                                })
                        .show());
    }

    public void loadPluginSettings() {
        loadPluginSettings(null);
    }

    public void loadPluginSettings(String pluginId) {
        if (TextUtils.isEmpty(pluginId)) {
            for (Plugin plugin : new ArrayList<>(plugins.values())) {
                if (plugin != null && plugin.isEnabled() && !plugin.hasError()) {
                    loadPluginSettings(plugin.getId());
                }
            }
            return;
        }
        Utilities.pluginsQueue.postRunnable(() -> {
            try {
                PluginsEngine engine = getPluginEngine(pluginId);
                if (engine == null) {
                    return;
                }
                List<SettingItem> pluginSettings = engine.loadPluginSettings(pluginId);
                if (pluginSettings == null) {
                    invalidatePluginSettings(pluginId);
                    return;
                }
                settings.put(pluginId, pluginSettings);
                FileLog.d("Registered settings for plugin " + pluginId);
                NotificationCenter.getGlobalInstance().postNotificationNameOnUIThread(NotificationCenter.pluginSettingsRegistered, pluginId);
            } catch (Throwable t) {
                FileLog.e(t);
                invalidatePluginSettings(pluginId);
            }
        });
    }

    public void invalidatePluginSettings(String pluginId) {
        if (TextUtils.isEmpty(pluginId)) {
            return;
        }
        List<SettingItem> removed = settings.remove(pluginId);
        if (removed == null) {
            return;
        }
        for (SettingItem item : removed) {
            item.cleanup();
        }
        NotificationCenter.getGlobalInstance().postNotificationNameOnUIThread(NotificationCenter.pluginSettingsUnregistered, pluginId);
    }

    public void clearPluginSettingsPreferences(String pluginId) {
        clearPluginSettingsPreferences(pluginId, false);
    }

    public void clearPluginSettingsPreferences(String pluginId, boolean clearEnabledState) {
        if (TextUtils.isEmpty(pluginId)) {
            return;
        }
        PluginsEngine engine = getPluginEngine(pluginId);
        if (engine != null) {
            engine.clearPluginSettings(pluginId);
        } else {
            for (PluginsEngine other : engines.values()) {
                if (other != null) {
                    other.clearPluginSettings(pluginId);
                }
            }
        }
        if (!clearEnabledState || preferences == null) {
            return;
        }
        String enabledKey = PREF_PLUGIN_ENABLED_KEY_PREFIX + pluginId;
        if (preferences.contains(enabledKey)) {
            preferences.edit().remove(enabledKey).apply();
        }
    }

    public Map<String, ?> getPluginSettingsPreferences(String pluginId) {
        PluginsEngine engine = getPluginEngine(pluginId);
        return engine != null ? engine.getAllPluginSettings(pluginId) : null;
    }

    public boolean hasPluginSettings(String pluginId) {
        return !TextUtils.isEmpty(pluginId) && settings.containsKey(pluginId);
    }

    public boolean hasPluginSettingsPreferences(String pluginId) {
        Map<String, ?> values = getPluginSettingsPreferences(pluginId);
        return values != null && !values.isEmpty();
    }

    public boolean getPluginSettingBoolean(String pluginId, String key, boolean defaultValue) {
        PluginsEngine engine = getPluginEngine(pluginId);
        if (engine != null) {
            Object value = engine.getPluginSetting(pluginId, key, defaultValue);
            if (value instanceof Boolean) {
                return (Boolean) value;
            }
        }
        return defaultValue;
    }

    public int getPluginSettingInt(String pluginId, String key, int defaultValue) {
        PluginsEngine engine = getPluginEngine(pluginId);
        if (engine != null) {
            Object value = engine.getPluginSetting(pluginId, key, defaultValue);
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
        }
        return defaultValue;
    }

    public String getPluginSettingString(String pluginId, String key, String defaultValue) {
        PluginsEngine engine = getPluginEngine(pluginId);
        Object value = engine != null ? engine.getPluginSetting(pluginId, key, defaultValue) : null;
        return value != null ? value.toString() : defaultValue;
    }

    public void setPluginSetting(String pluginId, String key, Object value) {
        PluginsEngine engine = getPluginEngine(pluginId);
        if (engine != null) {
            engine.setPluginSetting(pluginId, key, value);
            loadPluginSettings(pluginId);
        }
    }

    private void addHook(String pluginId, HookRecord hookRecord, String logMessage) {
        if (TextUtils.isEmpty(pluginId) || hookRecord == null) {
            return;
        }
        Set<HookRecord> set = hooks.computeIfAbsent(pluginId, ignored -> new CopyOnWriteArraySet<>());
        if (!set.add(hookRecord)) {
            return;
        }
        FileLog.d(logMessage);
        interestedPluginsCache.clear();
        hooksCacheDirty = true;
    }

    private void removeHook(String pluginId, java.util.function.Predicate<HookRecord> predicate, String logMessage) {
        if (TextUtils.isEmpty(pluginId)) {
            return;
        }
        Set<HookRecord> set = hooks.get(pluginId);
        if (set == null || set.isEmpty()) {
            return;
        }

        ArrayList<HookRecord> remaining = new ArrayList<>();
        ArrayList<HookRecord> removed = new ArrayList<>();
        for (HookRecord hookRecord : set) {
            if (predicate.test(hookRecord)) {
                removed.add(hookRecord);
            } else {
                remaining.add(hookRecord);
            }
        }
        if (removed.isEmpty()) {
            return;
        }
        for (HookRecord record : removed) {
            record.cleanup();
        }
        if (remaining.isEmpty()) {
            hooks.remove(pluginId);
        } else {
            hooks.put(pluginId, new CopyOnWriteArraySet<>(remaining));
        }
        FileLog.d(logMessage);
        interestedPluginsCache.clear();
        hooksCacheDirty = true;
    }

    public void addEventHook(String pluginId, String hookName, boolean matchSubstring, int priority) {
        addHook(pluginId, new EventHookRecord(pluginId, hookName, matchSubstring, priority),
                "Added event hook '" + hookName + "' for plugin " + pluginId);
    }

    public void removeEventHook(String pluginId, String hookName) {
        removeHook(pluginId,
                record -> record instanceof EventHookRecord && TextUtils.equals(((EventHookRecord) record).getHookName(), hookName),
                "Removed event hook(s) matching name '" + hookName + "' for plugin " + pluginId);
    }

    public void addXposedHook(String pluginId, XC_MethodHook.Unhook unhook) {
        addHook(pluginId, new XposedHookRecord(unhook), "Added Xposed hook for plugin " + pluginId);
    }

    public void addXposedHooks(String pluginId, ArrayList<XC_MethodHook.Unhook> unhooks) {
        if (unhooks == null) {
            return;
        }
        for (XC_MethodHook.Unhook unhook : unhooks) {
            addXposedHook(pluginId, unhook);
        }
    }

    public void removeXposedHook(String pluginId, XC_MethodHook.Unhook unhook) {
        removeHook(pluginId,
                record -> record instanceof XposedHookRecord && ((XposedHookRecord) record).matches(unhook),
                "Removed Xposed hook for plugin " + pluginId);
    }

    public void removeHooksByPluginId(String pluginId) {
        if (TextUtils.isEmpty(pluginId)) {
            return;
        }
        Set<HookRecord> removed = hooks.remove(pluginId);
        if (removed == null) {
            return;
        }
        for (HookRecord hookRecord : removed) {
            hookRecord.cleanup();
        }
        FileLog.d("Removed all (" + removed.size() + ") hooks for plugin " + pluginId);
        interestedPluginsCache.clear();
        hooksCacheDirty = true;
    }

    public String addMenuItem(String pluginId, PyObject object) {
        if (!isPluginEngineAvailable() || object == null) {
            return null;
        }
        try {
            MenuItemRecord record = new MenuItemRecord(pluginId, object);
            if (record.menuType == null) {
                return null;
            }
            MenuItemRecord existing = menuItemsById.get(record.itemId);
            if (existing != null && !TextUtils.equals(existing.pluginId, pluginId)) {
                FileLog.w(String.format("Plugin %s tried to add a menu item: %s, which is already used by plugin %s", pluginId, record.itemId, existing.pluginId));
                return null;
            }
            if (existing != null) {
                CopyOnWriteArrayList<MenuItemRecord> existingList = menuItemsByMenuType.get(existing.menuType);
                if (existingList != null) {
                    existingList.remove(existing);
                }
                existing.markRemoved();
            }
            menuItemsById.put(record.itemId, record);
            menuItemsByMenuType.compute(record.menuType, (key, oldList) -> {
                ArrayList<MenuItemRecord> newItems = oldList == null ? new ArrayList<>() : new ArrayList<>(oldList);
                newItems.removeIf(item -> TextUtils.equals(item.itemId, record.itemId));
                newItems.add(record);
                newItems.sort((a, b) -> Integer.compare(b.priority, a.priority));
                return new CopyOnWriteArrayList<>(newItems);
            });
            FileLog.d("Added menu item: " + record.itemId + " for plugin " + pluginId + " in type " + record.menuType);
            NotificationCenter.getGlobalInstance().postNotificationNameOnUIThread(NotificationCenter.pluginMenuItemsUpdated);
            return record.itemId;
        } catch (Exception ignored) {
            return null;
        }
    }

    public boolean removeMenuItem(String pluginId, String itemId) {
        if (TextUtils.isEmpty(itemId)) {
            return false;
        }
        MenuItemRecord record = menuItemsById.remove(itemId);
        if (record == null || record.menuType == null) {
            return false;
        }
        if (!TextUtils.equals(record.pluginId, pluginId)) {
            menuItemsById.put(itemId, record);
            return false;
        }
        CopyOnWriteArrayList<MenuItemRecord> list = menuItemsByMenuType.get(record.menuType);
        if (list != null) {
            list.remove(record);
        }
        record.markRemoved();
        FileLog.d("Removed menu item: " + itemId + " for plugin " + pluginId);
        NotificationCenter.getGlobalInstance().postNotificationNameOnUIThread(NotificationCenter.pluginMenuItemsUpdated);
        return true;
    }

    public void removeMenuItemsByPluginId(String pluginId) {
        if (TextUtils.isEmpty(pluginId)) {
            return;
        }
        ArrayList<String> itemIds = new ArrayList<>();
        for (MenuItemRecord item : menuItemsById.values()) {
            if (TextUtils.equals(item.pluginId, pluginId)) {
                itemIds.add(item.itemId);
            }
        }
        for (String itemId : itemIds) {
            removeMenuItem(pluginId, itemId);
        }
        FileLog.d("Removed all menu items for plugin: " + pluginId);
    }

    public List<MenuItemRecord> getMenuItemsForLocation(String menuType, MenuContextBuilder builder) {
        if (builder == null) {
            return getMenuItemsForLocation(menuType, new java.util.HashMap<>());
        }
        return getMenuItemsForLocation(menuType, builder.build());
    }

    public List<MenuItemRecord> getMenuItemsForLocation(String menuType, Map<String, Object> context) {
        if (!isPluginEngineAvailable() || TextUtils.isEmpty(menuType)) {
            return Collections.emptyList();
        }
        CopyOnWriteArrayList<MenuItemRecord> list = menuItemsByMenuType.get(menuType);
        if (PluginsConstants.MenuItemTypes.MAIN_MENU.equals(menuType)) {
            CopyOnWriteArrayList<MenuItemRecord> drawerList = menuItemsByMenuType.get(PluginsConstants.MenuItemTypes.DRAWER_MENU);
            if (drawerList != null && !drawerList.isEmpty()) {
                if (list == null || list.isEmpty()) {
                    list = drawerList;
                } else {
                    CopyOnWriteArrayList<MenuItemRecord> merged = new CopyOnWriteArrayList<>(list);
                    merged.addAllAbsent(drawerList);
                    list = merged;
                }
            }
        }
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        ArrayList<MenuItemRecord> result = new ArrayList<>();
        for (MenuItemRecord item : list) {
            Plugin plugin = plugins.get(item.pluginId);
            if (plugin != null && plugin.isEnabled() && !plugin.hasError()) {
                watchdog.onPluginExecutionStarted(item.pluginId);
                try {
                    if (item.checkCondition(context)) {
                        result.add(item);
                    }
                } finally {
                    watchdog.onPluginExecutionFinished(item.pluginId);
                }
            }
        }
        return result;
    }

    void notifyPluginsChanged() {
        AndroidUtilities.cancelRunOnUIThread(updateNotificationRunnable);
        AndroidUtilities.runOnUIThread(updateNotificationRunnable, 150);
    }

    public void executeOnAppEvent(String eventName) {
        if (!isPluginEngineAvailable()) {
            return;
        }
        FileLog.d("Execute scripts on app event " + eventName);
        for (PluginsEngine engine : engines.values()) {
            if (engine != null) {
                engine.executeOnAppEvent(eventName);
            }
        }
    }

    List<String> getInterestedPluginIds(String hookName) {
        if (TextUtils.isEmpty(hookName)) {
            return Collections.emptyList();
        }
        List<String> cached = interestedPluginsCache.get(hookName);
        if (cached != null) {
            return cached;
        }

        rebuildHooksCacheIfNeeded();
        java.util.HashMap<String, Integer> priorities = new java.util.HashMap<>();

        List<EventHookRecord> exactMatches = exactMatchEventHooksCache.get(hookName);
        if (exactMatches != null) {
            for (EventHookRecord record : exactMatches) {
                priorities.merge(record.getPluginId(), record.getPriority(), Math::max);
            }
        }
        for (EventHookRecord record : substringMatchEventHooksCache) {
            if (record.matches(hookName)) {
                priorities.merge(record.getPluginId(), record.getPriority(), Math::max);
            }
        }

        List<String> result;
        if (priorities.isEmpty()) {
            result = Collections.emptyList();
        } else {
            ArrayList<Map.Entry<String, Integer>> entries = new ArrayList<>(priorities.entrySet());
            entries.sort((a, b) -> {
                int priorityCompare = Integer.compare(b.getValue(), a.getValue());
                return priorityCompare != 0 ? priorityCompare : a.getKey().compareTo(b.getKey());
            });
            ArrayList<String> filtered = new ArrayList<>(entries.size());
            for (Map.Entry<String, Integer> entry : entries) {
                Plugin plugin = plugins.get(entry.getKey());
                if (plugin != null && plugin.isEnabled() && !plugin.hasError()) {
                    filtered.add(entry.getKey());
                }
            }
            result = filtered;
        }
        interestedPluginsCache.put(hookName, result);
        if (!result.isEmpty()) {
            FileLog.d("Calculated and cached potential plugins for '" + hookName + "': " + result);
        }
        return result;
    }

    private void rebuildHooksCacheIfNeeded() {
        if (!hooksCacheDirty) {
            return;
        }
        synchronized (hooksCacheLock) {
            if (!hooksCacheDirty) {
                return;
            }
            java.util.HashMap<String, List<EventHookRecord>> exact = new java.util.HashMap<>();
            ArrayList<EventHookRecord> substring = new ArrayList<>();
            for (Set<HookRecord> records : hooks.values()) {
                for (HookRecord record : records) {
                    if (!(record instanceof EventHookRecord)) {
                        continue;
                    }
                    EventHookRecord eventHookRecord = (EventHookRecord) record;
                    if (eventHookRecord.isMatchSubstring()) {
                        substring.add(eventHookRecord);
                    } else {
                        exact.computeIfAbsent(eventHookRecord.getHookName(), ignored -> new ArrayList<>()).add(eventHookRecord);
                    }
                }
            }
            exactMatchEventHooksCache = exact;
            substringMatchEventHooksCache = substring;
            hooksCacheDirty = false;
        }
    }

    @Override
    public TLObject executePreRequestHook(String hookName, int account, TLObject request) {
        if (!isPluginEngineAvailable()) {
            return request;
        }
        List<String> pluginIds = getInterestedPluginIds(hookName);
        if (pluginIds.isEmpty()) {
            return request;
        }
        TLObject result = request;
        for (String pluginId : pluginIds) {
            PluginsEngine engine = getPluginEngine(pluginId);
            if (engine == null) {
                continue;
            }
            watchdog.onPluginExecutionStarted(pluginId);
            try {
                HookResult<TLObject> hookResult = engine.executePreRequestHook(hookName, account, result, pluginId);
                result = hookResult.result;
                if (hookResult.cancel) {
                    return null;
                }
                if (hookResult.isFinal) {
                    return result;
                }
            } finally {
                watchdog.onPluginExecutionFinished(pluginId);
            }
        }
        return result;
    }

    @Override
    public PostRequestResult executePostRequestHook(String hookName, int account, TLObject response, TLRPC.TL_error error) {
        if (!isPluginEngineAvailable()) {
            return new PostRequestResult(response, error);
        }
        List<String> pluginIds = getInterestedPluginIds(hookName);
        if (pluginIds.isEmpty()) {
            return new PostRequestResult(response, error);
        }
        TLObject currentResponse = response;
        TLRPC.TL_error currentError = error;
        for (String pluginId : pluginIds) {
            PluginsEngine engine = getPluginEngine(pluginId);
            if (engine == null) {
                continue;
            }
            watchdog.onPluginExecutionStarted(pluginId);
            try {
                HookResult<PostRequestResult> hookResult = engine.executePostRequestHook(hookName, account, currentResponse, currentError, pluginId);
                PostRequestResult postRequestResult = hookResult.result;
                currentResponse = postRequestResult.response;
                currentError = postRequestResult.error;
                if (hookResult.cancel) {
                    return null;
                }
                if (hookResult.isFinal) {
                    return new PostRequestResult(currentResponse, currentError);
                }
            } finally {
                watchdog.onPluginExecutionFinished(pluginId);
            }
        }
        return new PostRequestResult(currentResponse, currentError);
    }

    @Override
    public TLRPC.Update executeUpdateHook(String hookName, int account, TLRPC.Update update) {
        if (!isPluginEngineAvailable()) {
            return update;
        }
        List<String> pluginIds = getInterestedPluginIds(hookName);
        if (pluginIds.isEmpty()) {
            return update;
        }
        TLRPC.Update result = update;
        for (String pluginId : pluginIds) {
            PluginsEngine engine = getPluginEngine(pluginId);
            if (engine == null) {
                continue;
            }
            watchdog.onPluginExecutionStarted(pluginId);
            try {
                HookResult<TLRPC.Update> hookResult = engine.executeUpdateHook(hookName, account, result, pluginId);
                result = hookResult.result;
                if (hookResult.cancel) {
                    return null;
                }
                if (hookResult.isFinal) {
                    return result;
                }
            } finally {
                watchdog.onPluginExecutionFinished(pluginId);
            }
        }
        return result;
    }

    @Override
    public TLRPC.Updates executeUpdatesHook(String hookName, int account, TLRPC.Updates updates) {
        if (!isPluginEngineAvailable()) {
            return updates;
        }
        List<String> pluginIds = getInterestedPluginIds(hookName);
        if (pluginIds.isEmpty()) {
            return updates;
        }
        TLRPC.Updates result = updates;
        for (String pluginId : pluginIds) {
            PluginsEngine engine = getPluginEngine(pluginId);
            if (engine == null) {
                continue;
            }
            watchdog.onPluginExecutionStarted(pluginId);
            try {
                HookResult<TLRPC.Updates> hookResult = engine.executeUpdatesHook(hookName, account, result, pluginId);
                result = hookResult.result;
                if (hookResult.cancel) {
                    return null;
                }
                if (hookResult.isFinal) {
                    return result;
                }
            } finally {
                watchdog.onPluginExecutionFinished(pluginId);
            }
        }
        return result;
    }

    @Override
    public SendMessagesHelper.SendMessageParams executeSendMessageHook(int account, SendMessagesHelper.SendMessageParams params) {
        if (!isPluginEngineAvailable()) {
            return params;
        }
        List<String> pluginIds = getInterestedPluginIds(PluginsConstants.SEND_MESSAGE_HOOK);
        if (pluginIds.isEmpty()) {
            return params;
        }
        SendMessagesHelper.SendMessageParams result = params;
        for (String pluginId : pluginIds) {
            PluginsEngine engine = getPluginEngine(pluginId);
            if (engine == null) {
                continue;
            }
            watchdog.onPluginExecutionStarted(pluginId);
            try {
                HookResult<SendMessagesHelper.SendMessageParams> hookResult = engine.executeSendMessageHook(account, result, pluginId);
                result = hookResult.result;
                if (hookResult.cancel) {
                    return null;
                }
                if (hookResult.isFinal) {
                    return result;
                }
            } finally {
                watchdog.onPluginExecutionFinished(pluginId);
            }
        }
        return result;
    }

    private static class SingletonHolder {
        private static final PluginsController INSTANCE = new PluginsController();
    }

    public static class HookResult<T> {
        public T result;
        public boolean cancel;
        public boolean isFinal;

        public HookResult(T result, boolean cancel, boolean isFinal) {
            this.result = result;
            this.cancel = cancel;
            this.isFinal = isFinal;
        }
    }

    public static class PluginValidationResult {
        public Plugin plugin;
        public String error;

        public PluginValidationResult(Plugin plugin, String error) {
            this.plugin = plugin;
            this.error = error;
        }
    }
}
