package com.exteragram.messenger.plugins;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.system.Os;
import android.text.TextUtils;
import android.view.View;

import androidx.core.content.FileProvider;

import com.chaquo.python.PyException;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.updater.PythonSdkUpdateDialog;
import com.exteragram.messenger.utils.AppUtils;
import com.exteragram.messenger.utils.network.RemoteUtils;
import com.exteragram.messenger.plugins.hooks.PluginsHooks;
import com.exteragram.messenger.plugins.models.CustomSetting;
import com.exteragram.messenger.plugins.models.DividerSetting;
import com.exteragram.messenger.plugins.models.EditTextSetting;
import com.exteragram.messenger.plugins.models.HeaderSetting;
import com.exteragram.messenger.plugins.models.InputSetting;
import com.exteragram.messenger.plugins.models.SelectorSetting;
import com.exteragram.messenger.plugins.models.SettingItem;
import com.exteragram.messenger.plugins.models.SwitchSetting;
import com.exteragram.messenger.plugins.models.TextSetting;
import com.exteragram.messenger.plugins.pip.PipController;
import com.exteragram.messenger.plugins.ui.PluginSettingsActivity;
import com.exteragram.messenger.plugins.ui.components.InstallPluginBottomSheet;
import com.exteragram.messenger.plugins.ui.components.PluginFileViewer;
import com.exteragram.messenger.plugins.utils.PyObjectUtils;
import com.exteragram.messenger.plugins.utils.SdkSignature;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.DownloadController;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.LaunchActivity;

import tw.nekomimi.nekogram.helpers.AppRestartHelper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class PythonPluginsEngine implements PluginsController.PluginsEngine {
    private static final java.util.regex.Pattern VERSION_REQUIREMENT_PATTERN =
            java.util.regex.Pattern.compile("^\\s*(>=|<=|==|!=|>|<)?\\s*v?(\\d+(?:\\.\\d+)*)\\s*$");

    private static final String[] SDK_REQUIRED_MODULES = {"_sdk_version", "base_plugin", "plugin_settings"};
    private static final long MAX_SDK_VERSION_BYTES = 65536;
    private static final String SDK_SHIMS_DIR = "python_shims";
    public static final String INSTALL_CANCELLED = "__plugin_install_cancelled__";

    public static final PythonPluginsEngine INSTANCE = new PythonPluginsEngine();
    private static File SDK_DIR;
    private static File SDK_COMPAT_SHIMS_DIR;
    public static String SDK_VERSION;
    public static boolean SDK_BETA = false;

    public volatile PyObject basePluginClass;
    public PyObject debuggerListener;
    private PyObject devServerClass;
    public static volatile boolean sdkInitialized;
    private volatile Python python;
    // SDK modules remain imported when only the plugin engine shuts down.
    private boolean sdkImportedInProcess;

    public final ConcurrentHashMap<String, PyObject> pluginInstances = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Object>> settingsCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<String>> pluginDependencyPaths = new ConcurrentHashMap<>();
    // Paths attached to the interpreter, including paths whose last owner was released.
    private final Set<String> insertedDependencyPaths = new java.util.LinkedHashSet<>();

    @FunctionalInterface
    public interface PyMethodCaller<T> {
        PyObject call(PyObject plugin, T value);
    }

    public ConcurrentHashMap<String, PyObject> getPluginInstances() {
        return pluginInstances;
    }

    public PyObject getBasePluginClass() {
        return basePluginClass;
    }

    public void setBasePluginClass(PyObject basePluginClass) {
        this.basePluginClass = basePluginClass;
    }

    public PyObject getDebuggerListener() {
        return debuggerListener;
    }

    public static String getSDK_VERSION() {
        return SDK_VERSION;
    }

    public static void setSDK_VERSION(String version) {
        SDK_VERSION = version;
    }

    public static boolean getSDK_BETA() {
        return SDK_BETA;
    }

    public static void setSDK_BETA(boolean beta) {
        SDK_BETA = beta;
    }

    @Override
    public boolean canOpenInExternalApp() {
        return true;
    }

    private PluginsController getPluginsController() {
        return PluginsController.getInstance();
    }

    private synchronized Python getPython() {
        if (python == null) {
            initPython();
            if (python == null) {
                FileLog.e("Python initialization failed, unable to proceed.");
                return null;
            }
        }
        return initSdk() ? python : null;
    }

    private void initPython() {
        try {
            if (!Python.isStarted()) {
                Python.start(new AndroidPlatform(ApplicationLoader.applicationContext));
            }
            python = Python.getInstance();
            lastPythonError = null;
        } catch (Throwable e) {
            if (lastPythonError == null) {
                lastPythonError = describeThrowable(e);
            }
            FileLog.e("Failed to initialize Python", e);
        }
    }

    /** Message of the most recent Python.start() failure, or null if it succeeded. */
    static volatile String lastPythonError;

    private static String describeThrowable(Throwable e) {
        StringBuilder sb = new StringBuilder(e.getClass().getName());
        int depth = 0;
        for (Throwable c = e; c != null && depth < 8; c = c.getCause(), depth++) {
            if (c.getMessage() != null) {
                sb.append(depth == 0 ? ": " : " <- ").append(c.getMessage());
            }
        }
        return sb.toString();
    }

    @Override
    public boolean isPlugin(File file) {
        return file != null && file.getName().toLowerCase().endsWith(PluginsConstants.PLUGINS_EXT);
    }

    @Override
    public boolean isEngineAvailable() {
        return python != null && Python.isStarted() && sdkInitialized && basePluginClass != null;
    }

    @Override
    public void init(Runnable runnable) {
        PluginsController.runOnPluginsQueue(() -> initOnPluginsQueue(runnable));
    }

    private void initOnPluginsQueue(Runnable runnable) {
        long initStart = System.currentTimeMillis();
        try {
            if (!ExteraConfig.pluginsEngine || getPython() == null) {
                if (runnable != null) {
                    AndroidUtilities.runOnUIThread(runnable);
                }
                return;
            }
            long pythonMs = System.currentTimeMillis();
            if (!initSdk()) {
                if (runnable != null) {
                    AndroidUtilities.runOnUIThread(runnable);
                }
                return;
            }
            FileLog.d("init: getPython+initSdk took " + (System.currentTimeMillis() - pythonMs) + "ms");
            if (!ExteraConfig.pluginsSafeMode) {
                AndroidUtilities.runOnUIThread(Updater::checkUpdates, 5000L);
            }
            try {
                String[] migratedKeys = (String[]) getPython()
                        .getModule("plugin_settings")
                        .callAttr("init", getPluginsController().pluginsDir.getAbsolutePath(), getPluginsController().preferences.getAll())
                        .toJava(String[].class);
                if (migratedKeys != null && migratedKeys.length > 0) {
                    SharedPreferences.Editor editor = getPluginsController().preferences.edit();
                    for (String key : migratedKeys) {
                        editor.remove(key);
                    }
                    editor.apply();
                    FileLog.d("Migrated " + migratedKeys.length + " plugin settings from SharedPreferences to JSON.");
                }
            } catch (PyException e) {
                FileLog.e("Failed to initialize plugin_settings module", e);
            }
            checkDevServer();
            FileLog.d("init: total took " + (System.currentTimeMillis() - initStart) + "ms");
            loadPlugins(runnable);
        } catch (Throwable t) {
            FileLog.e("Failed to initialize Python plugin engine", t);
            if (runnable != null) {
                AndroidUtilities.runOnUIThread(runnable);
            }
        }
    }

    private synchronized boolean initSdk() {
        if (python == null || sdkInitialized) {
            return sdkInitialized;
        }
        if (SDK_DIR == null) {
            SDK_DIR = new File(new File(ApplicationLoader.getFilesDirFixed(), "chaquopy"), "plugins-sdk");
            if (!SDK_DIR.exists() && !SDK_DIR.mkdirs()) {
                FileLog.e("Failed to create plugin SDK dir: " + SDK_DIR);
                return false;
            }
        }
        if (SDK_COMPAT_SHIMS_DIR == null) {
            SDK_COMPAT_SHIMS_DIR = new File(ApplicationLoader.getFilesDirFixed(), SDK_SHIMS_DIR);
        }
        File requestSdkFromApkFile = Updater.requestSdkFromApkFile();
        File sdkUpdateFile = Updater.getPythonSdkUpdateFile();
        File currentSdkFile = Updater.getPythonCurrentSdkFile();
        boolean fromApk = requestSdkFromApkFile.exists();
        if (sdkImportedInProcess && (fromApk || sdkUpdateFile.exists())) {
            FileLog.w("SDK replacement requires an application restart; keeping pending SDK files intact.");
            Updater.restartApp();
            return false;
        }
        if (!fromApk && sdkUpdateFile.exists()) {
            try {
                copyFile(sdkUpdateFile, currentSdkFile);
                installSdkArchive(currentSdkFile, false);
            } catch (IOException e) {
                FileLog.e("Failed to install updated Python SDK archive", e);
                fromApk = true;
            }
        }
        File vFile = new File(SDK_DIR, "v.txt");
        if (vFile.exists()) {
            try (InputStream inputStream = new FileInputStream(vFile)) {
                String content = readStreamFully(inputStream, MAX_SDK_VERSION_BYTES).trim();
                boolean installedBeta = content.endsWith("|1");
                int separator = content.indexOf('|');
                String installedVersion = separator >= 0 ? content.substring(0, separator) : content;
                try (InputStream assetStream = ApplicationLoader.applicationContext.getAssets().open("plugins_pysdk/v.txt");
                     BufferedReader reader = new BufferedReader(new InputStreamReader(assetStream, StandardCharsets.UTF_8))) {
                    StringBuilder builder = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        builder.append(line).append('\n');
                    }
                    String apkVersion = builder.toString().trim();
                    if (AppUtils.compareVersions(installedBeta ? ">=" : ">", apkVersion, installedVersion)) {
                        fromApk = true;
                    }
                }
            } catch (IOException e) {
                FileLog.e("Failed to read Python SDK version (v.txt) from APK assets", e);
                return false;
            }
        }
        if (fromApk || !isSdkDirValid(SDK_DIR)) {
            if (sdkImportedInProcess) {
                FileLog.w("SDK restoration requires an application restart.");
                Updater.restartApp();
                return false;
            }
            if (!fromApk) {
                FileLog.w("Python SDK directory is missing required files. Restoring SDK from APK.");
            }
            deleteRecursive(SDK_DIR);
            try {
                InputStream sdkStream = Updater.sdkFromApk();
                try {
                    copyStream(sdkStream, currentSdkFile);
                } finally {
                    sdkStream.close();
                }
                installSdkArchive(currentSdkFile, true);
                deleteFileIfExists(requestSdkFromApkFile);
            } catch (IOException e) {
                FileLog.e("Failed to install Python SDK from APK", e);
                return false;
            }
        }
        Updater.deleteSdkUpdateFile();
        if (!preparePythonCompatShims()) {
            FileLog.e("Failed to prepare plugin SDK Python shims");
        }
        try {
            PyObject sys = python.getModule("sys");
            PyObject sysPath = sys.get("path");
            if (sysPath != null) {
                sysPath.callAttr("append", SDK_DIR.getAbsolutePath());
                if (SDK_COMPAT_SHIMS_DIR != null && SDK_COMPAT_SHIMS_DIR.exists()) {
                    sysPath.callAttr("append", SDK_COMPAT_SHIMS_DIR.getAbsolutePath());
                }
            }
            sdkImportedInProcess = true;
            PyObject sdkVersionModule = python.getModule("_sdk_version");
            PyObject startResult = sdkVersionModule.callAttr("__start__");
            sdkInitialized = startResult != null && startResult.toBoolean();
            // The SDK is now pure Python (bundled in assets/plugins_pysdk), so the
            // Cython .so version / safe-mode checks that used to need patching are gone.
            if (sdkInitialized) {
                // "elyx" is the public alias of the "elyxcore" package (the zip only ships
                // elyxcore/); force the import first so the alias registers.
                try {
                    PyObject elyxCoreModule = python.getModule("elyxcore");
                    if (elyxCoreModule != null) {
                        PyObject sysAlias = python.getModule("sys");
                        PyObject modulesDict = sysAlias.get("modules");
                        if (modulesDict != null) {
                            modulesDict.callAttr("setdefault", "elyx", elyxCoreModule);
                        }
                    }
                } catch (Throwable t) {
                    FileLog.e("Failed to register elyx alias", t);
                }
            }
            PyObject versionAttr = sdkVersionModule.get("__version__");
            if (versionAttr != null) {
                String version = versionAttr.toJava(String.class);
                if (version != null) {
                    SDK_VERSION = version;
                }
            }
            PyObject betaAttr = sdkVersionModule.get("__beta__");
            if (betaAttr != null) {
                Boolean beta = betaAttr.toJava(Boolean.TYPE);
                if (beta != null) {
                    SDK_BETA = beta;
                }
            }
            if (basePluginClass == null && sdkInitialized && !ExteraConfig.pluginsSafeMode) {
                try {
                    requireBasePluginClass();
                } catch (Exception e) {
                    FileLog.e("Failed to load BasePlugin class", e);
                }
            }
            return sdkInitialized;
        } catch (Throwable t) {
            FileLog.e("Failed to initialize Python SDK bootstrap", t);
            try {
                Updater.restoreSdkFromApk();
            } catch (Throwable t2) {
                FileLog.e("Failed to schedule Python SDK restore from APK", t2);
            }
            return false;
        }
    }

    private synchronized PyObject requireBasePluginClass() throws Exception {
        if (ExteraConfig.pluginsSafeMode) {
            throw new Exception("Plugins cannot be enabled while safe mode is active");
        }
        if (!sdkInitialized) {
            throw new Exception("Python plugin SDK is not initialized");
        }
        if (basePluginClass != null) {
            return basePluginClass;
        }
        if (python == null) {
            throw new Exception("Python interpreter is not initialized");
        }
        try {
            PyObject klass = python.getModule("base_plugin").get("BasePlugin");
            if (klass == null) {
                throw new Exception("BasePlugin class is missing from the Python SDK");
            }
            basePluginClass = klass;
            return klass;
        } catch (Throwable t) {
            throw new Exception("Failed to initialize Python plugin runtime", t);
        }
    }

    private static boolean sdkModuleExists(File sdkDir, String moduleName) {
        if (new File(sdkDir, moduleName + ".so").exists()) {
            return true;
        }
        return new File(sdkDir, moduleName + ".pyc").exists();
    }

    private static void deleteFileIfExists(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        try {
            if (!file.delete()) {
                FileLog.e("Failed to delete file: " + file);
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static boolean isSdkDirValid(File sdkDir) {
        if (sdkDir == null || !sdkDir.isDirectory()) {
            return false;
        }
        for (String module : SDK_REQUIRED_MODULES) {
            if (!sdkModuleExists(sdkDir, module)) {
                return false;
            }
        }
        return true;
    }

    private boolean preparePythonCompatShims() {
        if (SDK_COMPAT_SHIMS_DIR == null) {
            return false;
        }
        File marker = new File(SDK_COMPAT_SHIMS_DIR, "com/exteragram/messenger/plugins/utils/__init__.py");
        if (marker.exists()) {
            return true;
        }
        deleteRecursive(SDK_COMPAT_SHIMS_DIR);
        if (!SDK_COMPAT_SHIMS_DIR.exists() && !SDK_COMPAT_SHIMS_DIR.mkdirs()) {
            return false;
        }
        Map<String, String> shims = new LinkedHashMap<>();
        shims.put("com/__init__.py", "");
        shims.put("com/android/__init__.py", "");
        shims.put("com/android/dx/__init__.py",
                "from java import jclass\n" +
                "Code = jclass('com.android.dx.Code')\n" +
                "Comparison = jclass('com.android.dx.Comparison')\n" +
                "DexMaker = jclass('com.android.dx.DexMaker')\n" +
                "FieldId = jclass('com.android.dx.FieldId')\n" +
                "Label = jclass('com.android.dx.Label')\n" +
                "Local = jclass('com.android.dx.Local')\n" +
                "MethodId = jclass('com.android.dx.MethodId')\n" +
                "TypeId = jclass('com.android.dx.TypeId')\n");
        shims.put("com/exteragram/__init__.py", "");
        shims.put("com/exteragram/messenger/__init__.py",
                "from java import jclass\n" +
                "ExteraConfig = jclass('com.exteragram.messenger.ExteraConfig')\n");
        shims.put("com/exteragram/messenger/utils/__init__.py",
                "from java import jclass\n" +
                "AppUtils = jclass('com.exteragram.messenger.utils.AppUtils')\n");
        shims.put("com/exteragram/messenger/plugins/__init__.py",
                "from java import jclass\n" +
                "Plugin = jclass('com.exteragram.messenger.plugins.Plugin')\n" +
                "PluginsController = jclass('com.exteragram.messenger.plugins.PluginsController')\n" +
                "PluginsConstants = jclass('com.exteragram.messenger.plugins.PluginsConstants')\n");
        shims.put("com/exteragram/messenger/plugins/hooks/__init__.py",
                "from java import jclass\n" +
                "HookFilter = jclass('com.exteragram.messenger.plugins.hooks.HookFilter')\n");
        shims.put("com/exteragram/messenger/plugins/xposed/__init__.py",
                "from java import jclass\n" +
                "PyMethodHook = jclass('com.exteragram.messenger.plugins.xposed.PyMethodHook')\n" +
                "PyMethodReplacement = jclass('com.exteragram.messenger.plugins.xposed.PyMethodReplacement')\n");
        shims.put("com/exteragram/messenger/plugins/models/__init__.py",
                "from java import jclass\n" +
                "CustomSetting = jclass('com.exteragram.messenger.plugins.models.CustomSetting')\n" +
                "SettingItem = jclass('com.exteragram.messenger.plugins.models.SettingItem')\n" +
                "SwitchSetting = jclass('com.exteragram.messenger.plugins.models.SwitchSetting')\n" +
                "SelectorSetting = jclass('com.exteragram.messenger.plugins.models.SelectorSetting')\n" +
                "InputSetting = jclass('com.exteragram.messenger.plugins.models.InputSetting')\n" +
                "TextSetting = jclass('com.exteragram.messenger.plugins.models.TextSetting')\n" +
                "HeaderSetting = jclass('com.exteragram.messenger.plugins.models.HeaderSetting')\n" +
                "DividerSetting = jclass('com.exteragram.messenger.plugins.models.DividerSetting')\n" +
                "EditTextSetting = jclass('com.exteragram.messenger.plugins.models.EditTextSetting')\n");
        shims.put("com/exteragram/messenger/plugins/ui/__init__.py",
                "from java import jclass\n" +
                "PluginSettingsActivity = jclass('com.exteragram.messenger.plugins.ui.PluginSettingsActivity')\n");
        shims.put("com/exteragram/messenger/plugins/ui/components/__init__.py",
                "from java import jclass\n" +
                "PluginCell = jclass('com.exteragram.messenger.plugins.ui.components.PluginCell')\n" +
                "PluginCellDelegate = jclass('com.exteragram.messenger.plugins.ui.components.PluginCellDelegate')\n" +
                "InstallPluginBottomSheet = jclass('com.exteragram.messenger.plugins.ui.components.InstallPluginBottomSheet')\n");
        shims.put("com/exteragram/messenger/plugins/ui/components/templates/__init__.py",
                "from java import jclass\n" +
                "UniversalFragment = jclass('com.exteragram.messenger.plugins.ui.components.templates.UniversalFragment')\n" +
                "UniversalFrameLayout = jclass('com.exteragram.messenger.plugins.ui.components.templates.UniversalFrameLayout')\n" +
                "UniversalView = jclass('com.exteragram.messenger.plugins.ui.components.templates.UniversalView')\n");
        shims.put("com/exteragram/messenger/plugins/utils/__init__.py",
                "from java import jclass\n" +
                "ClassProxy = jclass('com.exteragram.messenger.plugins.utils.ClassProxy')\n");

        try {
            for (Map.Entry<String, String> entry : shims.entrySet()) {
                writeCompatShim(entry.getKey(), entry.getValue());
            }
            return true;
        } catch (Throwable t) {
            FileLog.e("Failed to write plugin SDK Python shims", t);
            return false;
        }
    }

    private void writeCompatShim(String relativePath, String content) throws IOException {
        File file = new File(SDK_COMPAT_SHIMS_DIR, relativePath);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create shim parent dir: " + parent);
        }
        try (FileOutputStream outputStream = new FileOutputStream(file, false)) {
            outputStream.write(content.getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
        }
    }

    private List<String> installPluginDependencies(String pluginId, Plugin pluginMetadata, PipController.InstallerDelegate delegate) throws Exception {
        List<String> paths = resolveDependencyPaths(pluginId, pluginMetadata, delegate);
        activatePluginDependencies(pluginId, paths);
        return paths;
    }

    private void preparePluginDependencies(String pluginId, Plugin pluginMetadata, PipController.InstallerDelegate delegate) throws Exception {
        checkDependencyPaths(pluginId, resolveDependencyPaths(pluginId, pluginMetadata, delegate));
    }

    private List<String> resolveDependencyPaths(String pluginId, Plugin pluginMetadata, PipController.InstallerDelegate delegate) throws Exception {
        List<String> requirements = pluginMetadata != null ? pluginMetadata.getRequirements() : null;
        return PipController.INSTANCE.installDependencies(requirements, pluginId, delegate);
    }

    private Map<String, Set<String>> checkDependencyPaths(String pluginId, List<String> paths) throws Exception {
        Map<String, Set<String>> pathModules = new LinkedHashMap<>();
        for (String path : paths) {
            File directory = new File(path).getCanonicalFile();
            if (!directory.isDirectory()) {
                throw new IOException("Dependency directory is missing: " + path);
            }
            pathModules.put(directory.getAbsolutePath(), topLevelModuleNames(directory));
        }
        Set<String> sdkModules = topLevelModuleNames(SDK_DIR);
        for (Map.Entry<String, Set<String>> entry : pathModules.entrySet()) {
            if (entry.getValue().contains(pluginId)) {
                throw new Exception("Dependency " + new File(entry.getKey()).getName()
                        + " provides a module with the same name as plugin '" + pluginId + "'.");
            }
            Set<String> intersect = new java.util.HashSet<>(entry.getValue());
            intersect.retainAll(sdkModules);
            if (!intersect.isEmpty()) {
                throw new Exception("Dependency " + new File(entry.getKey()).getName() + " of " + pluginId
                        + " provides " + intersect + ", which would replace the plugins SDK module of the same name.");
            }
        }
        return pathModules;
    }

    private void activatePluginDependencies(String pluginId, List<String> paths) throws Exception {
        Map<String, Set<String>> pathModules = checkDependencyPaths(pluginId, paths);
        boolean inUpdate = PipController.INSTANCE.isPluginUpdateInProgress(pluginId);
        if (inUpdate) {
            for (Map.Entry<String, Set<String>> dependency : pathModules.entrySet()) {
                for (String module : dependency.getValue()) {
                    if (pluginInstances.containsKey(module) || getPluginsController().isPluginActive(module)) {
                        throw new Exception("Dependency of '" + pluginId + "' would shadow enabled plugin '" + module + "'.");
                    }
                }
                File packageDir = new File(dependency.getKey()).getParentFile();
                for (Map.Entry<String, List<String>> owner : pluginDependencyPaths.entrySet()) {
                    if (pluginId.equals(owner.getKey())) {
                        continue;
                    }
                    for (String activePath : owner.getValue()) {
                        if (!dependency.getKey().equals(activePath)
                                && packageDir.equals(new File(activePath).getParentFile())) {
                            throw new Exception("Dependency " + packageDir.getName() + " of '" + pluginId
                                    + "' conflicts with the version in use by '" + owner.getKey() + "'.");
                        }
                    }
                }
            }
        }
        List<String> canonicalPaths = new ArrayList<>(pathModules.keySet());
        pluginDependencyPaths.put(pluginId, canonicalPaths);
        boolean attached = false;
        try {
            if (!inUpdate) {
                for (Map.Entry<String, Set<String>> entry : pathModules.entrySet()) {
                    disableShadowedPlugins(pluginId, new File(entry.getKey()).getName(), entry.getValue());
                }
            }
            if (!canonicalPaths.isEmpty()) {
                Python python = getPython();
                PyObject sysPath = python == null ? null : python.getModule("sys").get("path");
                if (sysPath == null) {
                    throw new IllegalStateException("Python import path is unavailable");
                }
                for (int i = canonicalPaths.size() - 1; i >= 0; i--) {
                    String path = canonicalPaths.get(i);
                    // Existing owners keep their relative search precedence.
                    if (insertedDependencyPaths.contains(path) && sysPath.callAttr("__contains__", path).toBoolean()) {
                        continue;
                    }
                    insertedDependencyPaths.add(path);
                    removeFromSysPath(sysPath, path);
                    sysPath.callAttr("insert", 0, path);
                }
            }
            attached = true;
        } finally {
            if (!attached) {
                pluginDependencyPaths.remove(pluginId);
            }
            pruneDependencyPaths();
        }
    }

    private void disableShadowedPlugins(String pluginId, String dependencyName, Set<String> providedModules) {
        for (String moduleName : providedModules) {
            if (TextUtils.equals(moduleName, pluginId)) {
                continue;
            }
            if (new File(getPluginsController().pluginsDir, moduleName + ".py").exists()) {
                FileLog.w("Disabling plugin '" + moduleName + "' because " + dependencyName
                        + " of " + pluginId + " provides a module with the same name.");
                unloadPlugin(moduleName);
                getPluginsController().preferences.edit().putBoolean(PluginsController.PREF_PLUGIN_ENABLED_KEY_PREFIX + moduleName, false).apply();
                Plugin plugin = getPluginsController().plugins.get(moduleName);
                if (plugin != null) {
                    plugin.setError(new Exception("Plugin id '" + moduleName + "' is disabled because "
                            + dependencyName + " of " + pluginId + " provides a module with the same name."));
                }
            }
        }
    }

    private static final String PRUNE_DEPENDENCY_IMPORTS = """
            import importlib, os, sys

            def prune_dependency_imports(roots):
                roots = tuple(roots)
                prefixes = tuple(root + os.sep for root in roots)

                def under_stale_root(path):
                    if not isinstance(path, str) or not os.path.isabs(path):
                        return False
                    path = os.path.realpath(path)
                    return path in roots or path.startswith(prefixes)

                # Inspect namespace package portions before changing their search path.
                stale_modules = []
                for name, module in list(sys.modules.items()):
                    if not isinstance(name, str) or not isinstance(module, type(sys)):
                        continue
                    attrs = vars(module)
                    try:
                        origin = attrs.get('__file__')
                        if origin is None:
                            locations = tuple(attrs.get('__path__', ()))
                            stale = bool(locations) and all(map(under_stale_root, locations))
                        else:
                            stale = under_stale_root(origin)
                    except (OSError, TypeError, ValueError):
                        continue
                    if stale:
                        stale_modules.append((name, module))

                sys.path[:] = [path for path in sys.path if not under_stale_root(path)]
                for path in list(sys.path_importer_cache):
                    if under_stale_root(path):
                        sys.path_importer_cache.pop(path, None)
                for name, module in stale_modules:
                    if sys.modules.get(name) is module:
                        sys.modules.pop(name, None)
                        parent_name, _, child = name.rpartition('.')
                        parent = sys.modules.get(parent_name)
                        if isinstance(parent, type(sys)) and vars(parent).get(child) is module:
                            vars(parent).pop(child, None)
                importlib.invalidate_caches()
            """;

    private void pruneDependencyPaths() {
        if (insertedDependencyPaths.isEmpty() || python == null) {
            return;
        }
        Set<String> stale = new java.util.LinkedHashSet<>(insertedDependencyPaths);
        for (List<String> paths : pluginDependencyPaths.values()) {
            stale.removeAll(paths);
        }
        if (stale.isEmpty()) {
            return;
        }
        try {
            PyObject builtins = python.getBuiltins();
            PyObject namespace = builtins.callAttr("dict");
            builtins.callAttr("exec", PRUNE_DEPENDENCY_IMPORTS, namespace);
            PyObject roots = builtins.callAttr("list");
            for (String path : stale) {
                roots.callAttr("append", path);
            }
            namespace.callAttr("__getitem__", "prune_dependency_imports").call(roots);
            insertedDependencyPaths.removeAll(stale);
        } catch (PyException e) {
            FileLog.e("Failed to prune plugin dependency paths", e);
        }
    }

    private void removePluginPathsFromSysPath() {
        pluginDependencyPaths.clear();
        pruneDependencyPaths();
        Python python = this.python;
        if (python == null) {
            return;
        }
        try {
            PyObject sysPath = python.getModule("sys").get("path");
            if (sysPath != null) {
                removeFromSysPath(sysPath, getPluginsController().pluginsDir.getAbsolutePath());
            }
        } catch (PyException e) {
            FileLog.e("Failed to remove plugin paths from sys.path", e);
        }
    }

    private void removeFromSysPath(PyObject sysPath, String path) {
        while (sysPath.callAttr("__contains__", path).toBoolean()) {
            sysPath.callAttr("remove", path);
        }
    }

    private void refreshImportCaches(String pluginId, File moduleDir) {
        Python python = this.python;
        if (python == null) {
            return;
        }
        try {
            PyObject sys = python.getModule("sys");
            PyObject sysModules = sys.get("modules");
            if (sysModules != null) {
                evictPluginModule(sysModules, pluginId, moduleDir);
            }
            PyObject pathImporterCache = sys.get("path_importer_cache");
            if (moduleDir != null && pathImporterCache != null) {
                pathImporterCache.callAttr("pop", moduleDir.getAbsolutePath(), null);
            }
            python.getModule("importlib").callAttr("invalidate_caches");
        } catch (PyException e) {
            FileLog.e("Failed to refresh import caches for plugin " + pluginId, e);
        }
    }

    private void evictPluginModule(PyObject sysModules, String pluginId, File moduleDir) {
        if (sysModules == null) {
            return;
        }
        PyObject module = sysModules.callAttr("get", pluginId);
        if (module == null) {
            return;
        }
        PyObject fileObj = module.get("__file__");
        String file = fileObj != null ? fileObj.toString() : null;
        String expected = null;
        if (moduleDir != null) {
            expected = canonicalPathOrNull(new File(moduleDir, pluginId + ".py").getAbsolutePath());
        }
        if (expected == null || !TextUtils.equals(canonicalPathOrNull(file), expected)) {
            FileLog.w("Skipping eviction of plugin module " + pluginId + " loaded from "
                    + file + " (expected " + expected + ")");
            return;
        }
        sysModules.callAttr("pop", pluginId, null);
    }

    private static Set<String> topLevelModuleNames(File dir) {
        Set<String> names = new java.util.HashSet<>();
        if (dir == null || !dir.isDirectory()) {
            return names;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return names;
        }
        for (File file : files) {
            String name = file.getName();
            if (name.endsWith(".py")) {
                names.add(name.substring(0, name.length() - 3));
            } else if (name.endsWith(".so")) {
                String base = name.substring(0, name.length() - 3);
                int dot = base.indexOf('.');
                names.add(dot > 0 ? base.substring(0, dot) : base);
            } else if (file.isDirectory() && new File(file, "__init__.py").exists()) {
                names.add(name);
            }
        }
        return names;
    }

    private static String canonicalPathOrNull(String path) {
        if (TextUtils.isEmpty(path)) {
            return null;
        }
        try {
            return new File(path).getCanonicalPath();
        } catch (IOException e) {
            return null;
        }
    }

    private void installSdkArchive(File archiveFile, boolean fromApk) throws IOException {
        File sdkDir = SDK_DIR;
        if (sdkDir == null) {
            throw new IOException("Python SDK directory is not initialized");
        }
        File tempDir = new File(sdkDir.getParentFile(), "plugins-sdk.tmp");
        try {
            try (InputStream inputStream = new FileInputStream(archiveFile)) {
                extractSdkStream(inputStream, tempDir);
            }
            if (!isSdkDirValid(tempDir)) {
                throw new IOException("Python SDK archive unpacked with missing required files");
            }
            deleteRecursive(sdkDir);
            if (!tempDir.renameTo(sdkDir) && !sdkDir.exists()) {
                throw new IOException("Failed to move unpacked Python SDK into place");
            }
            Updater.setBuildFromApk(fromApk);
        } finally {
            deleteRecursive(tempDir);
            if (!sdkDir.exists() && !sdkDir.mkdirs()) {
                FileLog.e("Failed to create plugin SDK dir: " + sdkDir);
            }
        }
    }

    private void extractSdkStream(InputStream inputStream, File targetDir) throws IOException {
        String targetCanonicalPath = targetDir.getCanonicalPath();
        long maxTotalUncompressedSize = 2147483648L;
        int maxEntries = 100000;
        long maxSingleFileSize = 536870912L;
        long maxCompressionRatio = 500L;
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw new IOException("Failed to create extraction dir: " + targetDir);
        }
        try (java.util.zip.ZipInputStream zipInputStream = new java.util.zip.ZipInputStream(inputStream)) {
            java.util.zip.ZipEntry entry;
            byte[] buffer = new byte[8192];
            long totalUncompressedSize = 0L;
            int entries = 0;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                entries++;
                if (entries > maxEntries) {
                    throw new IOException("Python SDK archive has too many entries");
                }
                long size = entry.getSize();
                long compressedSize = entry.getCompressedSize();
                if (size > maxSingleFileSize || (compressedSize > 0 && size / compressedSize > maxCompressionRatio)) {
                    throw new IOException("Unsafe Python SDK archive entry: " + entry.getName());
                }
                totalUncompressedSize += size;
                if (totalUncompressedSize > maxTotalUncompressedSize) {
                    throw new IOException("Python SDK archive is too large");
                }
                File outFile = new File(targetDir, entry.getName());
                if (!outFile.getCanonicalPath().startsWith(targetCanonicalPath)) {
                    throw new IOException("Unsafe Python SDK archive entry path: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    outFile.mkdirs();
                } else {
                    File parent = outFile.getParentFile();
                    if (parent != null && !parent.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        parent.mkdirs();
                    }
                    try (FileOutputStream outputStream = new FileOutputStream(outFile, false)) {
                        int read;
                        while ((read = zipInputStream.read(buffer)) != -1) {
                            outputStream.write(buffer, 0, read);
                        }
                        outputStream.flush();
                    }
                }
                zipInputStream.closeEntry();
            }
        }
    }

    private static String readStreamFully(InputStream inputStream, long maxBytes) throws IOException {
        StringBuilder builder = new StringBuilder();
        byte[] buffer = new byte[8192];
        long total = 0L;
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IOException("Stream exceeds maximum allowed size");
            }
            builder.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
        }
        return builder.toString();
    }

    private void deleteRecursive(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] files = file.listFiles();
        if (files != null) {
            for (File child : files) {
                deleteRecursive(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    @Override
    public void checkDevServer() {
        if (ExteraConfig.pluginsSafeMode) {
            return;
        }
        if (ExteraConfig.pluginsDevMode) {
            runDevServer();
        } else {
            stopDevServer();
        }
    }

    private void runDevServer() {
        if (getPython() == null) {
            return;
        }
        if (devServerClass != null) {
            stopDevServer();
        }
        try {
            PyObject klass = getPython().getModule(PluginsConstants.DevServer.MODULE).get(PluginsConstants.DevServer.CLASS);
            devServerClass = klass;
            if (klass != null) {
                klass.callAttr(PluginsConstants.DevServer.START_SERVER);
                FileLog.d("Dev server started successfully.");
            }
        } catch (Throwable t) {
            FileLog.e("Failed to initialize dev server", t);
            devServerClass = null;
        }
    }

    private void stopDevServer() {
        if (devServerClass == null) {
            return;
        }
        try {
            devServerClass.callAttr(PluginsConstants.DevServer.STOP_SERVER);
            FileLog.d("Dev server stopped successfully.");
        } catch (Throwable t) {
            FileLog.e("Failed to stop dev server", t);
        } finally {
            devServerClass = null;
        }
    }

    @Override
    public void shutdown(Runnable runnable) {
        if (python == null) {
            if (runnable != null) {
                runnable.run();
            }
            return;
        }
        try {
            stopDevServer();
            for (String pluginId : new ArrayList<>(pluginInstances.keySet())) {
                unloadPlugin(pluginId);
            }
            if (debuggerListener != null) {
                debuggerListener.close();
                debuggerListener = null;
            }
            pluginInstances.clear();
            settingsCache.clear();
            synchronized (this) {
                removePluginPathsFromSysPath();
                python = null;
                basePluginClass = null;
                sdkInitialized = false;
            }
            FileLog.d("Python plugin engine shut down.");
        } catch (Exception e) {
            FileLog.e(e);
        }
        if (runnable != null) {
            runnable.run();
        }
    }

    public void loadPlugins(Runnable runnable) {
        PluginsController.runOnPluginsQueue(() -> {
            boolean completionPosted = false;
            try {
                if (!ExteraConfig.pluginsEngine) {
                    return;
                }
                Python python = getPython();
                if (python == null || !sdkInitialized) {
                    return;
                }
                long loadStart = System.currentTimeMillis();
                PyObject sys = python.getModule("sys");
                PyObject path = sys.get("path");
                if (path != null) {
                    path.callAttr("append", getPluginsController().pluginsDir.getAbsolutePath());
                }

                File[] files = getPluginsController().pluginsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".py"));
                if (files == null) {
                    return;
                }

                // Phase 1: validate + register every plugin (fast, no import) so the
                // list appears immediately; the slow import/on_plugin_load happens in
                // phase 2 on this same background queue.
                java.util.ArrayList<File> validFiles = new java.util.ArrayList<>();
                for (File file : files) {
                    String pluginId = file.getName().substring(0, file.getName().length() - 3);
                    PluginsController.PluginValidationResult validationResult = null;
                    try {
                        validationResult = validatePluginFromFile(file.getAbsolutePath());
                        if (validationResult.error != null) {
                            throw new Exception(validationResult.error);
                        }
                        Plugin plugin = validationResult.plugin;
                        plugin.setEnabled(false);
                        getPluginsController().plugins.put(pluginId, plugin);
                        validFiles.add(file);
                    } catch (Throwable t) {
                        FileLog.e("Failed to validate plugin " + file.getName() + ". Reason: " + t.getMessage(), t);
                        Plugin plugin = validationResult != null ? validationResult.plugin : null;
                        if (plugin == null) {
                            plugin = new Plugin(pluginId, pluginId);
                            plugin.setAuthor(LocaleController.getString(R.string.PluginNoAuthor));
                            plugin.setVersion("1.0");
                            plugin.setEngine(PluginsConstants.PYTHON);
                        }
                        plugin.setError(t);
                        plugin.setEnabled(false);
                        getPluginsController().plugins.put(pluginId, plugin);
                    }
                }
                getPluginsController().notifyPluginsChanged();
                FileLog.d("loadPlugins: registered " + files.length + " plugins in " + (System.currentTimeMillis() - loadStart) + "ms (loading in background)");

                // Release the toggle immediately: the list is already populated
                // with every plugin (enabled=false). Import + on_plugin_load
                // continue below on this same queue, notifying per-plugin.
                if (runnable != null) {
                    completionPosted = true;
                    AndroidUtilities.runOnUIThread(runnable);
                }

                // Phase 2: import + on_plugin_load (slow) for each plugin, notifying
                // per-plugin so the list state updates as each one finishes.
                for (File file : validFiles) {
                    String pluginId = file.getName().substring(0, file.getName().length() - 3);
                    long pluginStart = System.currentTimeMillis();
                    try {
                        loadPlugin(pluginId, file.getAbsolutePath(), getPluginsController().plugins.get(pluginId), null, true);
                        FileLog.d("loadPlugins: loaded '" + pluginId + "' in " + (System.currentTimeMillis() - pluginStart) + "ms");
                    } catch (Throwable t) {
                        FileLog.e("Failed to load plugin " + file.getName() + ". Reason: " + t.getMessage(), t);
                        Plugin plugin = getPluginsController().plugins.get(pluginId);
                        if (plugin != null) {
                            plugin.setError(t);
                            plugin.setEnabled(false);
                        }
                    }
                }
                getPluginsController().notifyPluginsChanged();
                FileLog.d("loadPlugins: loaded " + files.length + " plugins in " + (System.currentTimeMillis() - loadStart) + "ms");

                int enabledCount = 0;
                for (Plugin plugin : getPluginsController().plugins.values()) {
                    if (plugin.isEnabled() && !plugin.hasError()) {
                        enabledCount++;
                    }
                }
                FileLog.d("Python plugin system initialized. Total: " + getPluginsController().plugins.size() + ", Enabled: " + enabledCount);
            } catch (Throwable e) {
                FileLog.e("Failed to setup Python environment for plugins", e);
            } finally {
                getPluginsController().notifyPluginsChanged();
                if (!completionPosted && runnable != null) {
                    AndroidUtilities.runOnUIThread(runnable);
                }
            }
        });
    }

    public void loadPlugin(String pluginId, String filePath) throws Exception {
        loadPlugin(pluginId, filePath, null);
    }

    public void loadPlugin(String pluginId, String filePath, Plugin plugin) throws Exception {
        loadPlugin(pluginId, filePath, plugin, null);
    }

    public void loadPlugin(String pluginId, String filePath, Plugin plugin, PipController.InstallerDelegate dependencyDelegate) throws Exception {
        loadPlugin(pluginId, filePath, plugin, dependencyDelegate, true);
    }

    public void loadPlugin(String pluginId, String filePath, Plugin plugin, PipController.InstallerDelegate dependencyDelegate, boolean notifyPlugins) throws Exception {
        boolean shouldEnable = getPluginsController().preferences.getBoolean(PluginsController.PREF_PLUGIN_ENABLED_KEY_PREFIX + pluginId, false);
        try {
            loadPlugin(pluginId, filePath, plugin, dependencyDelegate, shouldEnable, dependencyDelegate != null, null);
        } catch (Throwable t) {
            cleanUpFailedLoad(pluginId, t);
            throw t;
        }
        if (notifyPlugins && shouldEnable && !ExteraConfig.pluginsSafeMode) {
            getPluginsController().notifyPluginsChanged();
        }
    }

    private void loadPlugin(String pluginId, String filePath, Plugin plugin, PipController.InstallerDelegate dependencyDelegate,
                            boolean shouldEnable, boolean prepareDependencies, List<String> dependencyPaths) throws Exception {
        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) {
            throw new Exception("Plugin file not found: " + filePath);
        }
        if (plugin == null) {
            PluginsController.PluginValidationResult validationResult = validatePluginFromFile(filePath);
            if (validationResult.error != null) {
                throw new Exception(validationResult.error);
            }
            plugin = validationResult.plugin;
        }
        if (!pluginId.equals(plugin.getId())) {
            throw new Exception(String.format("Plugin ID mismatch. Expected: %s, but found: %s in metadata.", pluginId, plugin.getId()));
        }
        if (pluginInstances.containsKey(pluginId)) {
            unloadPlugin(pluginId);
        }

        plugin.setEnabled(false);
        plugin.setError(null);
        getPluginsController().plugins.put(pluginId, plugin);
        if (ExteraConfig.pluginsSafeMode) {
            return;
        }
        if (shouldEnable) {
            enablePlugin(pluginId, plugin, dependencyDelegate, dependencyPaths);
        } else if (prepareDependencies) {
            preparePluginDependencies(pluginId, plugin, dependencyDelegate);
        }
    }

    private void createPluginInstance(String pluginId, Plugin plugin, PipController.InstallerDelegate dependencyDelegate,
                                      List<String> dependencyPaths) throws Exception {
        try {
            if (dependencyPaths != null) {
                activatePluginDependencies(pluginId, dependencyPaths);
            } else {
                installPluginDependencies(pluginId, plugin, dependencyDelegate);
            }
            refreshImportCaches(pluginId, new File(getPluginsController().pluginsDir, pluginId + ".py").getParentFile());
            Python python = getPython();
            if (python == null) {
                throw new Exception("Failed to import plugin module: " + pluginId);
            }
            PyObject module = python.getModule(pluginId);
            if (module == null) {
                throw new Exception("Failed to import plugin module: " + pluginId);
            }
            PyObject moduleFile = module.get("__file__");
            String file = moduleFile != null ? moduleFile.toString() : null;
            String expectedFile = canonicalPathOrNull(new File(getPluginsController().pluginsDir, pluginId + ".py").getAbsolutePath());
            if (expectedFile == null || !expectedFile.equals(canonicalPathOrNull(file))) {
                throw new Exception("Plugin module '" + pluginId + "' was loaded from a different location ("
                        + file + "). Make sure your plugin id matches the plugin file name.");
            }
            PyObject pluginClass = findPluginClass(module);
            if (pluginClass == null) {
                throw new Exception("Could not find a class inheriting from BasePlugin in " + pluginId + ".py. Make sure your main plugin class extends BasePlugin.");
            }
            PyObject instance = pluginClass.call();
            instance.put("id", plugin.getId());
            instance.put("name", plugin.getName());
            instance.put("description", plugin.getDescription());
            instance.put("author", plugin.getAuthor());
            instance.put("version", plugin.getVersion());
            instance.put("icon", plugin.getIcon());
            instance.put("min_version", plugin.getMinVersion());
            instance.put("app_version", plugin.getAppVersion());
            instance.put("sdk_version", plugin.getSdkVersion());
            instance.put("requirements", plugin.getRequirements());
            instance.put("enabled", false);
            instance.put("initialized", false);
            instance.put("error_message", (Object) null);

            pluginInstances.put(pluginId, instance);
        } catch (PyException e) {
            throw new Exception("Failed to import plugin module: " + e.getMessage(), e);
        }
    }

    private PyObject findPluginClass(PyObject module) {
        if (basePluginClass == null) {
            FileLog.e("BasePlugin class is not loaded, cannot find plugin class in " + module.get("__name__"));
            return null;
        }
        // The selection rule belongs to the SDK (BasePlugin._findPluginClass): it only considers
        // classes declared in the plugin module itself and prefers the most derived one. The local
        // scan below stays as a fallback for SDK builds that predate that helper.
        try {
            if (basePluginClass.containsKey("_findPluginClass")) {
                return basePluginClass.callAttr("_findPluginClass", module);
            }
        } catch (PyException e) {
            FileLog.e("BasePlugin._findPluginClass failed for module " + module.get("__name__"), e);
        }
        try {
            PyObject builtins = getPython().getBuiltins();
            PyObject dict = module.get("__dict__");
            if (dict == null) {
                return null;
            }
            for (PyObject value : dict.asMap().values()) {
                if (builtins.callAttr("isinstance", value, builtins.get(PluginsConstants.Settings.TYPE)).toBoolean()
                        && !value.equals(basePluginClass)
                        && builtins.callAttr("issubclass", value, basePluginClass).toBoolean()) {
                    return value;
                }
            }
        } catch (PyException e) {
            FileLog.e("Error while searching for a BasePlugin subclass in module " + module.get("__name__"), e);
        }
        return null;
    }

    /** Full teardown: drops the instance and the imported module, so the next enable is a fresh load. */
    public void unloadPlugin(String pluginId) {
        settingsCache.remove(pluginId);
        PyObject instance = pluginInstances.remove(pluginId);
        Plugin plugin = getPluginsController().plugins.get(pluginId);
        if (plugin != null) {
            plugin.setEnabled(false);
        }
        try {
            if (instance != null && PyObjectUtils.getBoolean(instance, "initialized", false)) {
                getPluginsController().watchdog.onPluginExecutionStarted(pluginId);
                try {
                    instance.callAttr(PluginsConstants.ON_PLUGIN_UNLOAD);
                } catch (Throwable t) {
                    FileLog.e("Error during on_plugin_unload for " + pluginId, t);
                } finally {
                    getPluginsController().watchdog.onPluginExecutionFinished(pluginId);
                }
            }
            if (instance != null) {
                instance.put("initialized", false);
                instance.put("enabled", false);
                try {
                    instance.callAttr("_cleanup");
                } catch (Throwable t) {
                    FileLog.e("Error during SDK cleanup for " + pluginId, t);
                }
            }
            deleteStaleBytecode(pluginId);
            refreshImportCaches(pluginId, getPluginsController().pluginsDir);
        } catch (PyException e) {
            FileLog.e("Failed to unload plugin " + pluginId, e);
        } finally {
            try {
                getPluginsController().cleanupPlugin(pluginId);
            } finally {
                if (instance != null) {
                    try {
                        instance.close();
                    } catch (Throwable t) {
                        FileLog.e("Failed to close plugin instance " + pluginId, t);
                    }
                }
                pluginDependencyPaths.remove(pluginId);
                pruneDependencyPaths();
            }
        }
    }

    /**
     * Drops the compiled bytecode of a plugin module (pluginsDir/__pycache__/&lt;id&gt;.cpython-*.pyc)
     * so the next import reads the source file again.
     */
    private void deleteStaleBytecode(String pluginId) {
        File pluginsDir = getPluginsController().pluginsDir;
        if (pluginsDir == null) {
            return;
        }
        File pycache = new File(pluginsDir, "__pycache__");
        if (!pycache.exists() || !pycache.isDirectory()) {
            return;
        }
        File[] files = pycache.listFiles();
        if (files == null) {
            return;
        }
        String prefix = pluginId + ".cpython-";
        for (File file : files) {
            if (file.getName().startsWith(prefix)) {
                deleteFileIfExists(file);
                return;
            }
        }
    }

    @Override
    public void setPluginEnabled(String pluginId, boolean enabled, Utilities.Callback<String> callback) {
        setPluginEnabledInternal(pluginId, enabled, callback, true);
    }

    private void setPluginEnabledInternal(String pluginId, boolean enabled, Utilities.Callback<String> callback, boolean notify) {
        String error = null;
        try {
            Plugin plugin = getPluginsController().plugins.get(pluginId);
            if (plugin == null) {
                throw new Exception("Plugin not found: " + pluginId);
            }
            if (enabled && ExteraConfig.pluginsSafeMode) {
                plugin.setError(null);
                plugin.setEnabled(true);
                PyObject instance = pluginInstances.get(pluginId);
                if (instance != null) {
                    instance.put("enabled", true);
                    instance.put("initialized", false);
                    instance.put("error_message", (Object) null);
                }
            } else if (enabled) {
                enablePlugin(pluginId, plugin, null, null);
            } else {
                unloadPlugin(pluginId);
            }
            getPluginsController().preferences.edit().putBoolean(PluginsController.PREF_PLUGIN_ENABLED_KEY_PREFIX + pluginId, enabled).apply();
        } catch (Throwable t) {
            FileLog.e("Unexpected error setting enabled state for " + pluginId, t);
            if (enabled) {
                cleanUpFailedLoad(pluginId, t);
                getPluginsController().preferences.edit().putBoolean(PluginsController.PREF_PLUGIN_ENABLED_KEY_PREFIX + pluginId, false).apply();
            }
            error = stackTraceToString(t);
        }
        if (notify) {
            getPluginsController().notifyPluginsChanged();
        }
        if (callback != null) {
            String result = error;
            AndroidUtilities.runOnUIThread(() -> callback.run(result));
        }
    }

    private void enablePlugin(String pluginId, Plugin plugin, PipController.InstallerDelegate dependencyDelegate,
                              List<String> dependencyPaths) throws Exception {
        PyObject instance = pluginInstances.get(pluginId);
        if (instance == null) {
            createPluginInstance(pluginId, plugin, dependencyDelegate, dependencyPaths);
            instance = pluginInstances.get(pluginId);
            if (instance == null) {
                throw new Exception("Failed to create plugin instance: " + pluginId);
            }
        }
        if (PyObjectUtils.getBoolean(instance, "initialized", false) && !plugin.hasError()) {
            return;
        }
        getPluginsController().cleanupPlugin(pluginId);
        getPluginsController().watchdog.onPluginExecutionStarted(pluginId);
        try {
            instance.callAttr(PluginsConstants.ON_PLUGIN_LOAD);
        } finally {
            getPluginsController().watchdog.onPluginExecutionFinished(pluginId);
        }
        instance.put("initialized", true);
        instance.put("error_message", (Object) null);
        instance.put("enabled", true);
        plugin.setError(null);
        plugin.setEnabled(true);
        getPluginsController().loadPluginSettings(pluginId);
    }

    private void cleanUpFailedLoad(String pluginId, Throwable failure) {
        try {
            unloadPlugin(pluginId);
        } catch (Throwable cleanupError) {
            failure.addSuppressed(cleanupError);
        }
        Plugin plugin = getPluginsController().plugins.get(pluginId);
        if (plugin != null) {
            plugin.setError(failure);
            plugin.setEnabled(false);
        }
    }

    public void loadPluginFromFile(String sourceFilePath, Plugin plugin, Utilities.Callback<String> callback) {
        loadPluginFromFile(sourceFilePath, plugin, null, callback);
    }

    public void loadPluginFromFile(String sourceFilePath, Plugin plugin, PipController.InstallerDelegate dependencyDelegate, Utilities.Callback<String> callback) {
        loadPluginFromFile(sourceFilePath, plugin, dependencyDelegate, false, callback);
    }

    public void loadPluginFromFile(String sourceFilePath, Plugin plugin, PipController.InstallerDelegate dependencyDelegate,
                                   boolean enableAfterUpdate, Utilities.Callback<String> callback) {
        PluginsController.runOnPluginsQueue(() -> {
            PluginsController controller = getPluginsController();
            String pluginId = null;
            String enabledKey = null;
            File targetFile = null;
            File stagedFile = null;
            File backupFile = null;
            Plugin previousPlugin = null;
            Plugin resolvedPlugin = null;
            Throwable previousError = null;
            List<String> previousPaths = null;
            PyObject previousSettings = null;
            boolean hadEnabledPreference = false;
            boolean wasEnabled = false;
            boolean wasActive = false;
            boolean dependencyUpdateStarted = false;
            boolean previousUnloaded = false;
            boolean targetReplaced = false;
            String error = null;
            try {
                File directory = controller.pluginsDir;
                stagedFile = File.createTempFile("plugin-", ".tmp", directory);
                copyPluginFile(new File(sourceFilePath), stagedFile);
                PluginsController.PluginValidationResult validation = validatePluginFromFile(stagedFile.getAbsolutePath());
                if (validation.error != null || validation.plugin == null) {
                    throw new IOException(validation.error != null ? validation.error : "Cannot validate plugin metadata");
                }
                resolvedPlugin = validation.plugin;
                pluginId = resolvedPlugin.getId();
                if (plugin != null && !pluginId.equals(plugin.getId())) {
                    throw new IOException("Plugin ID changed before installation: " + plugin.getId());
                }
                enabledKey = PluginsController.PREF_PLUGIN_ENABLED_KEY_PREFIX + pluginId;
                hadEnabledPreference = controller.preferences.contains(enabledKey);
                wasEnabled = controller.preferences.getBoolean(enabledKey, false);
                targetFile = new File(directory, pluginId + ".py");
                if (dependencyDelegate != null && dependencyDelegate.isCancelled()) {
                    throw new PipController.InstallationCancelledException();
                }

                if (targetFile.exists()) {
                    previousPlugin = controller.plugins.get(pluginId);
                    wasActive = previousPlugin != null && previousPlugin.isEnabled();
                    previousError = previousPlugin != null ? previousPlugin.getError() : null;
                    previousPaths = pluginDependencyPaths.get(pluginId);
                    if (previousPaths != null) {
                        previousPaths = new ArrayList<>(previousPaths);
                    }
                    boolean willEnable = wasEnabled || enableAfterUpdate;
                    if (willEnable && !ExteraConfig.pluginsSafeMode) {
                        PyObject settings = getPython().getModule("plugin_settings").callAttr("get_all_settings", pluginId);
                        previousSettings = getPython().getModule("copy").callAttr("deepcopy", settings);
                    }
                    File backup = File.createTempFile(pluginId + "-", ".py.bak", directory);
                    backupFile = backup;
                    copyPluginFile(targetFile, backup);
                }

                PipController.INSTANCE.beginPluginUpdate(pluginId);
                dependencyUpdateStarted = true;
                if (backupFile != null) {
                    previousUnloaded = true;
                    unloadPlugin(pluginId);
                }
                Os.rename(stagedFile.getAbsolutePath(), targetFile.getAbsolutePath());
                targetReplaced = true;
                boolean shouldEnable = wasEnabled || (backupFile != null && enableAfterUpdate && !ExteraConfig.pluginsSafeMode);
                loadPlugin(pluginId, targetFile.getAbsolutePath(), resolvedPlugin, dependencyDelegate, shouldEnable, true, null);
                if (dependencyDelegate != null && dependencyDelegate.isCancelled()) {
                    throw new PipController.InstallationCancelledException();
                }
                if (shouldEnable && !wasEnabled) {
                    controller.preferences.edit().putBoolean(enabledKey, true).apply();
                }
                PipController.INSTANCE.finishPluginUpdate(pluginId, true);
                dependencyUpdateStarted = false;
            } catch (Throwable t) {
                boolean cancelled = t instanceof PipController.InstallationCancelledException;
                if (targetReplaced) {
                    cleanUpFailedLoad(pluginId, t);
                }
                if (dependencyUpdateStarted) {
                    try {
                        PipController.INSTANCE.finishPluginUpdate(pluginId, false);
                    } catch (Throwable rollbackError) {
                        t.addSuppressed(rollbackError);
                    }
                }
                if (targetReplaced || previousUnloaded) {
                    restoreEnabledPreference(controller.preferences, enabledKey, hadEnabledPreference, wasEnabled);
                    if (backupFile != null) {
                        if (targetReplaced) {
                            try {
                                Os.rename(backupFile.getAbsolutePath(), targetFile.getAbsolutePath());
                            } catch (Throwable restoreError) {
                                t.addSuppressed(restoreError);
                                FileLog.e("Plugin backup retained at " + backupFile, restoreError);
                                backupFile = null;
                            }
                        }
                        if (previousSettings != null) {
                            try {
                                getPython().getModule("plugin_settings").callAttr("set_all_settings", pluginId, previousSettings);
                            } catch (Throwable restoreError) {
                                t.addSuppressed(restoreError);
                            } finally {
                                settingsCache.remove(pluginId);
                            }
                        }
                        if (backupFile != null) {
                            try {
                                boolean restoreActive = previousPlugin == null ? wasEnabled : wasActive;
                                if (previousPaths == null && restoreActive && !ExteraConfig.pluginsSafeMode) {
                                    previousPaths = PipController.INSTANCE.getRegisteredDependencyPaths(pluginId);
                                }
                                loadPlugin(pluginId, targetFile.getAbsolutePath(), previousPlugin, null, restoreActive, false, previousPaths);
                                if (!restoreActive && previousPlugin != null) {
                                    previousPlugin.setError(previousError);
                                }
                            } catch (Throwable reloadError) {
                                cleanUpFailedLoad(pluginId, reloadError);
                                if (previousPlugin != null) {
                                    previousPlugin.setEnabled(false);
                                    previousPlugin.setError(reloadError);
                                    controller.plugins.put(pluginId, previousPlugin);
                                }
                                t.addSuppressed(reloadError);
                            }
                        } else {
                            // Keep the unverified candidate disabled if the previous source could not be put back.
                            controller.preferences.edit().putBoolean(enabledKey, false).apply();
                        }
                    } else if (cancelled) {
                        controller.plugins.remove(pluginId);
                        deleteFileIfExists(targetFile);
                        controller.clearPluginSettingsPreferences(pluginId);
                    } else {
                        resolvedPlugin.setEnabled(false);
                        resolvedPlugin.setError(t);
                        controller.plugins.put(pluginId, resolvedPlugin);
                    }
                }
                FileLog.e("Failed to install plugin from " + sourceFilePath, t);
                if (cancelled && t.getSuppressed().length == 0) {
                    error = INSTALL_CANCELLED;
                } else if (t instanceof PipController.UnsupportedDependencyException && t.getSuppressed().length == 0) {
                    error = t.getMessage();
                } else {
                    error = stackTraceToString(t);
                }
            } finally {
                if (stagedFile != null) {
                    deleteFileIfExists(stagedFile);
                }
                if (backupFile != null) {
                    deleteFileIfExists(backupFile);
                }
                if (previousSettings != null) {
                    previousSettings.close();
                }
            }
            controller.notifyPluginsChanged();
            if (callback != null) {
                String result = error;
                AndroidUtilities.runOnUIThread(() -> callback.run(result));
            }
        });
    }

    private static void copyPluginFile(File source, File target) throws IOException {
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.getFD().sync();
        }
    }

    private static void restoreEnabledPreference(SharedPreferences preferences, String key, boolean present, boolean enabled) {
        SharedPreferences.Editor edit = preferences.edit();
        if (present) {
            edit.putBoolean(key, enabled);
        } else {
            edit.remove(key);
        }
        edit.apply();
    }

    public PluginsController.PluginValidationResult validatePluginFromFile(String filePath) {
        if (!new File(filePath).exists()) {
            return new PluginsController.PluginValidationResult(null, "Plugin file not found.");
        }
        if (getPython() == null) {
            String err = lastPythonError;
            return new PluginsController.PluginValidationResult(null, err == null
                    ? "Python engine is initializing or was not started; try again in a moment."
                    : "Python engine failed to start: " + err);
        }
        try {
            Map<String, String> pluginMetadata = parsePluginMetadata(filePath);
            String pluginId = pluginMetadata.get("id");
            String pluginName = pluginMetadata.get("name");
            if (TextUtils.isEmpty(pluginId) || TextUtils.isEmpty(pluginName)) {
                return new PluginsController.PluginValidationResult(null, "Plugin metadata must contain non-empty '__id__' and '__name__'.");
            }
            if (!pluginId.matches("^[a-zA-Z][a-zA-Z0-9_-]{1,31}$")) {
                return new PluginsController.PluginValidationResult(null, "Plugin '__id__' must be 2-32 characters long, start with a letter, and contain only latin letters, numbers, dashes and underscores.");
            }
            String appVersion = pluginMetadata.get("app_version");
            String minVersion = pluginMetadata.get("min_version");
            if (!TextUtils.isEmpty(appVersion) && !matchesVersionRequirement(BuildVars.BUILD_VERSION_STRING, appVersion)) {
                return new PluginsController.PluginValidationResult(null, "Plugin requires app version " + appVersion + ". Current is " + BuildVars.BUILD_VERSION_STRING);
            }
            if (minVersion != null) {
                String minimum = parseMinimumVersion(minVersion);
                if (minimum == null) {
                    return new PluginsController.PluginValidationResult(null, LocaleController.getString(R.string.InvalidFormatError) + ": __min_version__");
                }
                if (!SharedConfig.versionBiggerOrEqual(BuildConfig.OFFICIAL_VERSION, minimum)) {
                    return new PluginsController.PluginValidationResult(null, "Plugin requires app version " + minimum + " or higher. Current is " + BuildVars.BUILD_VERSION_STRING);
                }
            }
            String sdkVersion = pluginMetadata.get("sdk_version");
            if (!TextUtils.isEmpty(sdkVersion) && !matchesVersionRequirement(SDK_VERSION, sdkVersion)) {
                return new PluginsController.PluginValidationResult(null, "Plugin requires SDK version " + sdkVersion + ". Current is " + SDK_VERSION);
            }
            Plugin plugin = new Plugin(pluginId, pluginName);
            plugin.setEngine(PluginsConstants.PYTHON);
            plugin.setAuthor(pluginMetadata.getOrDefault("author", LocaleController.getString(R.string.PluginNoAuthor)));
            plugin.setDescription(pluginMetadata.getOrDefault("description", LocaleController.getString(R.string.PluginNoDescription)));
            plugin.setIcon(pluginMetadata.get("icon"));
            plugin.setVersion(pluginMetadata.getOrDefault("version", "1.0"));
            plugin.setAppVersion(appVersion);
            plugin.setSdkVersion(!TextUtils.isEmpty(sdkVersion) ? sdkVersion : "v" + SDK_VERSION);
            plugin.setRequirements(splitRequirements(pluginMetadata.get("requirements")));
            plugin.setMinVersion(minVersion);
            plugin.setEnabled(getPluginsController().preferences.getBoolean(PluginsController.PREF_PLUGIN_ENABLED_KEY_PREFIX + pluginId, false));
            return new PluginsController.PluginValidationResult(plugin, null);
        } catch (PyException e) {
            FileLog.e("Failed to parse metadata from " + filePath + ". Error: " + e.getMessage(), e);
            return new PluginsController.PluginValidationResult(null, e.getMessage());
        } catch (Throwable t) {
            FileLog.e("Unexpected error validating plugin " + filePath, t);
            return new PluginsController.PluginValidationResult(null, t.getMessage());
        }
    }

    @Override
    public List<SettingItem> loadPluginSettings(String pluginId) {
        try {
            Plugin plugin = getPluginsController().plugins.get(pluginId);
            PyObject instance = pluginInstances.get(pluginId);
            if (plugin != null && plugin.isEnabled() && !plugin.hasError() && instance != null) {
                PyObject items = instance.callAttr(PluginsConstants.CREATE_SETTINGS);
                if (items == null) {
                    return null;
                }
                List<PyObject> definitions = items.asList();
                if (definitions.isEmpty()) {
                    return null;
                }
                return parsePySettingDefinitions(definitions);
            }
            getPluginsController().invalidatePluginSettings(pluginId);
            return null;
        } catch (Exception e) {
            FileLog.e("Failed to load plugin settings", e);
            return null;
        }
    }

    public List<SettingItem> parsePySettingDefinitions(List<PyObject> definitions) {
        ArrayList<SettingItem> items = new ArrayList<>(definitions.size());
        for (PyObject item : definitions) {
            if (item == null) {
                continue;
            }
            SettingItem parsed = null;
            String type = PyObjectUtils.getString(item, PluginsConstants.Settings.TYPE, null);
            if (type == null) {
                FileLog.w("A setting item in a plugin is missing its 'type'. Skipping.");
                continue;
            }

            String key = PyObjectUtils.getString(item, PluginsConstants.Settings.KEY, null);
            String text = PyObjectUtils.getString(item, PluginsConstants.Settings.TEXT, null);
            String subtext = PyObjectUtils.getString(item, PluginsConstants.Settings.SUBTEXT, null);
            String icon = PyObjectUtils.getString(item, PluginsConstants.Settings.ICON, null);
            PyObject onChange = item.get(PluginsConstants.Settings.ON_CHANGE);
            PyObject onLongClick = item.get(PluginsConstants.Settings.ON_LONG_CLICK);
            String linkAlias = PyObjectUtils.getString(item, PluginsConstants.Settings.LINK_ALIAS, null);
            PyObject defaultValue = item.get(PluginsConstants.Settings.DEFAULT);
            PyObject onClick = item.get(PluginsConstants.Settings.ON_CLICK);
            PyObject createSubFragment = item.get(PluginsConstants.Settings.CREATE_SUB_FRAGMENT);

            switch (type) {
                case PluginsConstants.Settings.TYPE_EDIT_TEXT -> {
                    String hint = PyObjectUtils.getString(item, PluginsConstants.Settings.HINT, null);
                    boolean multiline = PyObjectUtils.getBoolean(item, PluginsConstants.Settings.MULTILINE, false);
                    int maxLength = PyObjectUtils.getInt(item, PluginsConstants.Settings.MAX_LENGTH, 256);
                    String mask = PyObjectUtils.getString(item, PluginsConstants.Settings.MASK, null);
                    if (key != null && hint != null) {
                        parsed = new EditTextSetting(key, hint, defaultValue != null ? defaultValue.toString() : "", multiline, maxLength, mask, onChange);
                    }
                }
                case PluginsConstants.Settings.TYPE_HEADER -> {
                    if (text != null) {
                        parsed = new HeaderSetting(text);
                    }
                }
                case PluginsConstants.Settings.TYPE_SWITCH -> {
                    if (key != null && text != null && defaultValue != null) {
                        parsed = new SwitchSetting(key, text, defaultValue.toBoolean(), subtext, icon, onChange, onLongClick, linkAlias);
                    }
                }
                case PluginsConstants.Settings.TYPE_TEXT -> {
                    boolean accent = PyObjectUtils.getBoolean(item, PluginsConstants.Settings.ACCENT, false);
                    boolean red = PyObjectUtils.getBoolean(item, PluginsConstants.Settings.RED, false);
                    if (text != null) {
                        parsed = new TextSetting(text, subtext, icon, accent, red, onClick, createSubFragment, onLongClick, linkAlias);
                    }
                }
                case PluginsConstants.Settings.TYPE_CUSTOM -> {
                    PyObject factory = item.get(PluginsConstants.Settings.FACTORY);
                    PyObject factoryArgs = item.get(PluginsConstants.Settings.FACTORY_ARGS);
                    PyObject uItem = item.get(PluginsConstants.Settings.ITEM);
                    PyObject view = item.get(PluginsConstants.Settings.VIEW);
                    if (factory != null) {
                        CustomSetting.Factory<?> nativeFactory = PyObjectUtils.toJavaCompat(factory, CustomSetting.Factory.class);
                        if (nativeFactory != null) {
                            parsed = factoryArgs != null
                                    ? new CustomSetting(nativeFactory, factoryArgs, onClick, createSubFragment, onLongClick, linkAlias)
                                    : new CustomSetting(nativeFactory, onClick, createSubFragment, onLongClick, linkAlias);
                        }
                    } else if (uItem != null) {
                        UItem nativeItem = PyObjectUtils.toJavaCompat(uItem, UItem.class);
                        if (nativeItem != null) {
                            parsed = new CustomSetting(nativeItem, onClick, createSubFragment, onLongClick, linkAlias);
                        }
                    } else if (view != null) {
                        View nativeView = PyObjectUtils.toJavaCompat(view, View.class);
                        if (nativeView != null) {
                            parsed = new CustomSetting(nativeView, onClick, createSubFragment, onLongClick, linkAlias);
                        }
                    }
                }
                case PluginsConstants.Settings.TYPE_INPUT -> {
                    if (key != null && text != null) {
                        parsed = new InputSetting(key, text, defaultValue != null ? defaultValue.toString() : "", subtext, icon, onChange, onLongClick, linkAlias);
                    }
                }
                case PluginsConstants.Settings.TYPE_SELECTOR -> {
                    String[] selectorItems = PyObjectUtils.getStringArray(item, PluginsConstants.Settings.ITEMS, null);
                    if (key != null && text != null && selectorItems != null && selectorItems.length != 0 && defaultValue != null) {
                        parsed = new SelectorSetting(key, text, defaultValue.toInt(), selectorItems, icon, onChange, onLongClick, linkAlias);
                    }
                }
                case PluginsConstants.Settings.TYPE_DIVIDER -> parsed = new DividerSetting(text);
            }

            if (parsed != null) {
                items.add(parsed);
            }
        }
        return items;
    }

    private static void closeQuietly(PyObject pyObject) {
        if (pyObject != null) {
            try {
                pyObject.close();
            } catch (PyException ignored) {
            }
        }
    }

    private <T> PluginsController.HookResult<T> executeHook(String pluginId, T value, Class<T> cls, String payloadKey, PyMethodCaller<T> caller, Utilities.Callback<PyException> callback) {
        return executeHook(pluginInstances.get(pluginId), value, cls, payloadKey, caller, callback);
    }

    public <T> PluginsController.HookResult<T> executeHook(PyObject plugin, T value, Class<T> cls, String payloadKey, PyMethodCaller<T> caller, Utilities.Callback<PyException> callback) {
        if (plugin == null || !PyObjectUtils.getBoolean(plugin, "enabled", false) || PyObjectUtils.getString(plugin, "error_message", null) != null) {
            return new PluginsController.HookResult<>(value, false, false);
        }
        try {
            PyObject result = caller.call(plugin, value);
            if (result != null) {
                try {
                    String strategy = PyObjectUtils.getString(result, PluginsConstants.STRATEGY, PluginsConstants.Strategy.DEFAULT, true);
                    if (PluginsConstants.Strategy.CANCEL.equals(strategy)) {
                        return new PluginsController.HookResult<>(null, true, false);
                    }
                    if (strategy.endsWith(PluginsConstants.Strategy.MODIFY) || strategy.endsWith(PluginsConstants.Strategy.MODIFY_FINAL)) {
                        T current = value;
                        PyObject payload = result.callAttr("get", payloadKey);
                        if (payload != null) {
                            try {
                                current = payload.toJava(cls);
                            } finally {
                                closeQuietly(payload);
                            }
                        }
                        return new PluginsController.HookResult<>(current, false, strategy.endsWith(PluginsConstants.Strategy.MODIFY_FINAL));
                    }
                } finally {
                    closeQuietly(result);
                }
            }
        } catch (PyException e) {
            if (callback != null) {
                callback.run(e);
            }
        }
        return new PluginsController.HookResult<>(value, false, false);
    }

    @Override
    public void executeOnAppEvent(String eventName) {
        if (!sdkInitialized || ExteraConfig.pluginsSafeMode) {
            return;
        }
        Python python = getPython();
        if (python == null) {
            return;
        }
        PyObject module = python.getModule("base_plugin");
        if (module == null) {
            return;
        }
        PyObject appEventClass = module.get("AppEvent");
        if (appEventClass == null) {
            return;
        }
        PyObject appEvent = appEventClass.call(eventName);
        try {
            if (debuggerListener != null) {
                try {
                    debuggerListener.callAttr(PluginsConstants.ON_APP_EVENT, appEvent);
                } catch (PyException e) {
                    FileLog.e("Failed to execute app event for debugger listener", e);
                }
            }
            for (PyObject plugin : pluginInstances.values()) {
                if (PyObjectUtils.getBoolean(plugin, "enabled", false) && PyObjectUtils.getString(plugin, "error_message", null) == null) {
                    try {
                        plugin.callAttr(PluginsConstants.ON_APP_EVENT, appEvent);
                    } catch (PyException e) {
                        FileLog.e("Failed to execute app " + eventName + " for " + PyObjectUtils.getString(plugin, "id", null), e);
                    }
                }
            }
        } finally {
            if (appEvent != null) {
                appEvent.close();
            }
        }
    }

    public boolean dispatchIntentHook(Intent intent, boolean before) {
        if (!sdkInitialized || ExteraConfig.pluginsSafeMode || intent == null) {
            return false;
        }
        Python py = getPython();
        if (py == null) {
            return false;
        }
        try {
            PyObject module = py.getModule("intents");
            if (module == null) {
                return false;
            }
            PyObject manager = module.callAttr("get_intents_manager");
            if (manager == null) {
                return false;
            }
            PyObject result = manager.callAttr("dispatch_intent", intent, before ? "before" : "after");
            return result != null && result.toBoolean();
        } catch (Throwable t) {
            FileLog.e("Failed to dispatch intent hook", t);
            return false;
        }
    }

    /** Returns true when a plugin consumed the file-open event. */
    public boolean onFileOpen(int place, File file, String fileName, MessageObject message, Activity activity, BaseFragment parentFragment) {
        if (!sdkInitialized || ExteraConfig.pluginsSafeMode) {
            return false;
        }
        Python py = getPython();
        if (py == null) {
            return false;
        }
        try {
            PyObject module = py.getModule("file_utils");
            if (module == null) {
                return false;
            }
            PyObject filesController = module.get("FilesController");
            if (filesController == null) {
                return false;
            }
            PyObject result = filesController.callAttr("dispatch", place, file, fileName, message, activity, parentFragment);
            return result != null && result.toBoolean();
        } catch (Throwable t) {
            FileLog.e("Failed to dispatch file open", t);
            return false;
        }
    }

    @Override
    public PluginsController.HookResult<TLObject> executePreRequestHook(String hookName, int account, TLObject request, String pluginId) {
        setHookAccountScope(account);
        try {
            return executeHook(pluginId, request, TLObject.class, PluginsConstants.REQUEST,
                    (plugin, value) -> plugin.callAttr("pre_request_hook", hookName, account, value),
                    error -> FileLog.e("Failed to execute pre_request_hook in " + pluginId + " for " + hookName, error));
        } finally {
            setHookAccountScope(-1);
        }
    }

    private void setHookAccountScope(int account) {
        if (!sdkInitialized || getPython() == null) {
            return;
        }
        try {
            PyObject modules = getPython().getModule("sys").get("modules");
            if (modules == null || modules.callAttr("get", "client_utils") == null) {
                return;
            }
            if (account >= 0) {
                getPython().getModule("client_utils").callAttr("_set_hook_account", account);
            } else {
                getPython().getModule("client_utils").callAttr("_set_hook_account", (Object) null);
            }
        } catch (Throwable t) {
            FileLog.e("Failed to set hook account scope", t);
        }
    }

    public PluginsController.HookResult<PluginsHooks.PostRequestResult> executePostRequestHook(String hookName, int account, TLObject response, TLRPC.TL_error error, PyObject plugin) {
        if (plugin != null) {
            try {
                PyObject result = plugin.callAttr("post_request_hook", hookName, account, response, error);
                if (result != null) {
                    String strategy = PyObjectUtils.getString(result, PluginsConstants.STRATEGY, "", true);
                    if (strategy.endsWith(PluginsConstants.Strategy.MODIFY) || strategy.endsWith(PluginsConstants.Strategy.MODIFY_FINAL)) {
                        PyObject responseObject = result.callAttr("get", PluginsConstants.RESPONSE);
                        if (responseObject != null) {
                            response = responseObject.toJava(TLObject.class);
                        }
                        PyObject errorObject = result.callAttr("get", PluginsConstants.ERROR);
                        if (errorObject != null) {
                            error = errorObject.toJava(TLRPC.TL_error.class);
                        }
                        if (strategy.endsWith(PluginsConstants.Strategy.MODIFY_FINAL)) {
                            return new PluginsController.HookResult<>(new PluginsHooks.PostRequestResult(response, error), false, true);
                        }
                    } else if (PluginsConstants.Strategy.CANCEL.equals(strategy)) {
                        return new PluginsController.HookResult<>(new PluginsHooks.PostRequestResult(response, error), true, false);
                    }
                }
            } catch (PyException e) {
                FileLog.e("Failed to execute post_request_hook for " + hookName, e);
            }
        }
        return new PluginsController.HookResult<>(new PluginsHooks.PostRequestResult(response, error), false, false);
    }

    @Override
    public PluginsController.HookResult<PluginsHooks.PostRequestResult> executePostRequestHook(String hookName, int account, TLObject response, TLRPC.TL_error error, String pluginId) {
        setHookAccountScope(account);
        try {
            return executePostRequestHook(hookName, account, response, error, pluginInstances.get(pluginId));
        } finally {
            setHookAccountScope(-1);
        }
    }

    @Override
    public PluginsController.HookResult<TLRPC.Update> executeUpdateHook(String hookName, int account, TLRPC.Update update, String pluginId) {
        setHookAccountScope(account);
        try {
            return executeHook(pluginId, update, TLRPC.Update.class, PluginsConstants.UPDATE,
                    (plugin, value) -> plugin.callAttr("on_update_hook", hookName, account, value),
                    error -> FileLog.e("Failed to execute on_update_hook for " + hookName, error));
        } finally {
            setHookAccountScope(-1);
        }
    }

    @Override
    public PluginsController.HookResult<TLRPC.Updates> executeUpdatesHook(String hookName, int account, TLRPC.Updates updates, String pluginId) {
        setHookAccountScope(account);
        try {
            return executeHook(pluginId, updates, TLRPC.Updates.class, PluginsConstants.UPDATES,
                    (plugin, value) -> plugin.callAttr("on_updates_hook", hookName, account, value),
                    error -> FileLog.e("Failed to execute on_updates_hook for " + hookName, error));
        } finally {
            setHookAccountScope(-1);
        }
    }

    @Override
    public PluginsController.HookResult<SendMessagesHelper.SendMessageParams> executeSendMessageHook(int account, SendMessagesHelper.SendMessageParams params, String pluginId) {
        setHookAccountScope(account);
        try {
            return executeHook(pluginId, params, SendMessagesHelper.SendMessageParams.class, PluginsConstants.PARAMS,
                    (plugin, value) -> plugin.callAttr("on_send_message_hook", account, value),
                    error -> FileLog.e("Failed to execute on_send_message_hook for " + pluginId, error));
        } finally {
            setHookAccountScope(-1);
        }
    }

    public String fetchParameterValue(String filePath, String key) {
        if (filePath == null) {
            return null;
        }
        try {
            File file = new File(filePath);
            if (file.exists() && file.isFile()) {
                return parsePluginMetadata(filePath).get(key);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public Map<String, String> parsePluginMetadata(String filePath) {
        HashMap<String, String> result = new HashMap<>();
        if (filePath == null) {
            return result;
        }
        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) {
            return result;
        }
        if (getPython() == null) {
            FileLog.e("Python engine not initialized, cannot parse metadata for " + filePath);
            return result;
        }
        try {
            PyObject metadata = getPython().getModule("extera_utils.metadata_parser").callAttr("get_metadata", filePath);
            if (metadata != null) {
                for (Map.Entry<PyObject, PyObject> entry : metadata.asMap().entrySet()) {
                    result.put(entry.getKey().toString(), entry.getValue().toString());
                }
            }
        } catch (PyException e) {
            FileLog.e("Failed to parse metadata from " + filePath + ". Error: " + e.getMessage(), e);
            throw e;
        }
        return result;
    }

    private List<String> splitRequirements(String requirementsValue) {
        ArrayList<String> requirements = new ArrayList<>();
        if (TextUtils.isEmpty(requirementsValue)) {
            return requirements;
        }
        for (String part : requirementsValue.split(",")) {
            String value = part != null ? part.trim() : null;
            if (!TextUtils.isEmpty(value)) {
                requirements.add(value);
            }
        }
        return requirements;
    }

    private static String parseMinimumVersion(String requirement) {
        java.util.regex.Matcher matcher = VERSION_REQUIREMENT_PATTERN.matcher(requirement);
        if (!matcher.matches() || (matcher.group(1) != null && !">=".equals(matcher.group(1)))) {
            return null;
        }
        String version = matcher.group(2);
        try {
            for (String part : version.split("\\.")) {
                Integer.parseInt(part);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return version;
    }

    private static boolean matchesVersionRequirement(String currentVersion, String requirement) {
        if (TextUtils.isEmpty(currentVersion) || TextUtils.isEmpty(requirement)) {
            return true;
        }
        for (String part : requirement.split(",")) {
            String piece = part != null ? part.trim() : null;
            if (TextUtils.isEmpty(piece)) {
                continue;
            }
            java.util.regex.Matcher matcher = VERSION_REQUIREMENT_PATTERN.matcher(piece);
            if (!matcher.matches()) {
                continue;
            }
            String operator = matcher.group(1);
            String requiredVersion = matcher.group(2);
            int compare = compareVersions(currentVersion, requiredVersion);
            if (TextUtils.isEmpty(operator) || "==".equals(operator)) {
                if (compare != 0) {
                    return false;
                }
            } else if (">".equals(operator)) {
                if (compare <= 0) {
                    return false;
                }
            } else if (">=".equals(operator)) {
                if (compare < 0) {
                    return false;
                }
            } else if ("<".equals(operator)) {
                if (compare >= 0) {
                    return false;
                }
            } else if ("<=".equals(operator)) {
                if (compare > 0) {
                    return false;
                }
            } else if ("!=".equals(operator) && compare == 0) {
                return false;
            }
        }
        return true;
    }

    private static int compareVersions(String left, String right) {
        String[] leftParts = left.replaceFirst("^v", "").split("\\.");
        String[] rightParts = right.replaceFirst("^v", "").split("\\.");
        int length = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < length; i++) {
            String leftPart = i < leftParts.length ? leftParts[i] : "";
            String rightPart = i < rightParts.length ? rightParts[i] : "";
            int comparison = compareVersionPart(leftPart, rightPart);
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    private static int compareVersionPart(String left, String right) {
        String leftDigits = leadingDigits(left);
        String rightDigits = leadingDigits(right);
        if (!leftDigits.equals(rightDigits)) {
            if (leftDigits.isEmpty()) {
                return rightDigits.isEmpty() ? 0 : -1;
            }
            if (rightDigits.isEmpty()) {
                return 1;
            }
            try {
                return Integer.compare(Integer.parseInt(leftDigits), Integer.parseInt(rightDigits));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        if (!left.equals(right)) {
            return left.endsWith("beta") ? -1 : 1;
        }
        return 0;
    }

    private static String leadingDigits(String part) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < part.length(); i++) {
            char ch = part.charAt(i);
            if (Character.isDigit(ch)) {
                digits.append(ch);
            } else {
                break;
            }
        }
        return digits.toString();
    }

    private void cachePluginSetting(String pluginId, String key, Object value) {
        if (value == null) {
            ConcurrentHashMap<String, Object> cached = settingsCache.get(pluginId);
            if (cached != null) {
                cached.remove(key);
            }
        } else {
            settingsCache.computeIfAbsent(pluginId, ignored -> new ConcurrentHashMap<>()).put(key, value);
        }
    }

    @Override
    public Object getPluginSetting(String pluginId, String key, Object defaultValue) {
        ConcurrentHashMap<String, Object> cached = settingsCache.get(pluginId);
        if (cached != null && cached.containsKey(key)) {
            return cached.get(key);
        }
        if (getPython() == null) {
            return defaultValue;
        }
        try {
            PyObject value = getPython().getModule("plugin_settings").callAttr("get_setting", pluginId, key, defaultValue);
            if (value != null) {
                Object javaValue;
                if (defaultValue instanceof Boolean) {
                    javaValue = value.toBoolean();
                } else if (defaultValue instanceof Integer) {
                    javaValue = value.toInt();
                } else if (defaultValue instanceof String) {
                    javaValue = value.toString();
                } else if (defaultValue instanceof Float) {
                    javaValue = value.toFloat();
                } else if (defaultValue instanceof Long) {
                    javaValue = value.toLong();
                } else if (defaultValue == null) {
                    javaValue = value.toJava(Object.class);
                } else {
                    javaValue = value.toJava(defaultValue.getClass());
                }
                cachePluginSetting(pluginId, key, javaValue);
                return javaValue;
            }
        } catch (PyException e) {
            FileLog.e("Failed to get plugin setting " + pluginId + "/" + key, e);
        }
        return defaultValue;
    }

    @Override
    public void setPluginSetting(String pluginId, String key, Object value) {
        cachePluginSetting(pluginId, key, value);
        if (getPython() == null) {
            return;
        }
        try {
            getPython().getModule("plugin_settings").callAttr("set_setting", pluginId, key, value);
        } catch (PyException e) {
            FileLog.e("Failed to set plugin setting " + pluginId + "/" + key, e);
        }
    }

    @Override
    public void clearPluginSettings(String pluginId) {
        settingsCache.remove(pluginId);
        if (getPython() == null) {
            return;
        }
        try {
            getPython().getModule("plugin_settings").callAttr("clear_settings", pluginId);
        } catch (PyException e) {
            FileLog.e("Failed to clear plugin settings for " + pluginId, e);
        }
    }

    @Override
    public Map<String, ?> getAllPluginSettings(String pluginId) {
        if (getPython() == null) {
            return null;
        }
        try {
            PyObject value = getPython().getModule("plugin_settings").callAttr("get_all_settings", pluginId);
            if (value != null) {
                HashMap<String, Object> map = new HashMap<>();
                for (Map.Entry<PyObject, PyObject> entry : value.asMap().entrySet()) {
                    if (entry.getKey() != null) {
                        map.put(entry.getKey().toString(), entry.getValue() != null ? entry.getValue().toJava(Object.class) : null);
                    }
                }
                ConcurrentHashMap<String, Object> cached = new ConcurrentHashMap<>();
                for (Map.Entry<String, Object> entry : map.entrySet()) {
                    if (entry.getValue() != null) {
                        cached.put(entry.getKey(), entry.getValue());
                    }
                }
                settingsCache.put(pluginId, cached);
                return map;
            }
        } catch (PyException e) {
            FileLog.e("Failed to get all plugin settings for " + pluginId, e);
        }
        return null;
    }

    @Override
    public String getPluginPath(String pluginId) {
        return getPluginsController().pluginsDir.getAbsolutePath() + File.separator + pluginId + ".py";
    }

    @Override
    public void deletePlugin(String pluginId, Utilities.Callback<String> callback) {
        unloadPlugin(pluginId);
        PipController.INSTANCE.uninstallDependencies(pluginId);
        pruneDependencyPaths();
        getPluginsController().plugins.remove(pluginId);
        File file = new File(getPluginsController().pluginsDir, pluginId + ".py");
        if (file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
        if (PluginsController.isPluginPinned(pluginId)) {
            PluginsController.setPluginPinned(pluginId, false);
        }
        getPluginsController().clearPluginSettingsPreferences(pluginId);
        getPluginsController().notifyPluginsChanged();
        if (callback != null) {
            AndroidUtilities.runOnUIThread(() -> callback.run(null));
        }
    }

    @Override
    public void showInstallDialog(BaseFragment baseFragment, InstallPluginBottomSheet.PluginInstallParams pluginInstallParams) {
        PluginsController.runOnPluginsQueue(() -> {
            File file = new File(pluginInstallParams.filePath);
            String pluginName = fetchParameterValue(pluginInstallParams.filePath, "name");
            if (TextUtils.isEmpty(pluginName) && file.exists()) {
                pluginName = file.getName();
            }
            PluginsController.PluginValidationResult validationResult = validatePluginFromFile(pluginInstallParams.filePath);
            String finalPluginName = pluginName;
            AndroidUtilities.runOnUIThread(() -> {
                if (baseFragment == null || !AndroidUtilities.isActivityRunning(baseFragment.getParentActivity())) {
                    return;
                }
                if (validationResult.plugin != null) {
                    new InstallPluginBottomSheet(baseFragment, validationResult, pluginInstallParams).show();
                } else {
                    BulletinFactory.of(baseFragment)
                            .createSimpleBulletin(
                                    R.raw.error,
                                    LocaleController.formatString(R.string.PluginInstallError, finalPluginName),
                                    LocaleController.getString(R.string.Copy),
                                    () -> {
                                        if (AndroidUtilities.addToClipboard(validationResult.error)) {
                                            BulletinFactory.of(baseFragment).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
                                        }
                                    })
                            .show();
                }
            });
        });
    }

    @Override
    public void openPluginSettings(String pluginId, BaseFragment baseFragment) {
        Plugin plugin = getPluginsController().plugins.get(pluginId);
        if (plugin != null) {
            openPluginSettings(plugin, baseFragment);
        }
    }

    @Override
    public void openPluginSettings(Plugin plugin, BaseFragment baseFragment) {
        if (plugin == null) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> baseFragment.presentFragment(new PluginSettingsActivity(plugin)));
    }

    @Override
    public void openPluginSetting(Plugin plugin, String settingId, BaseFragment baseFragment) {
        if (plugin == null) {
            return;
        }
        PluginsController.runOnPluginsQueue(() -> {
            FileLog.d("Opening plugin setting: " + plugin.getId() + "/" + settingId);
            PluginSettingsActivity activity;
            if (settingId == null || !settingId.contains(":")) {
                activity = new PluginSettingsActivity(plugin, settingId);
            } else {
                List<SettingItem> definitions = getPluginsController().settings.get(plugin.getId());
                if (definitions == null) {
                    return;
                }
                String[] path = settingId.split(":");
                TextSetting matchedTextSetting = null;
                List<SettingItem> currentDefinitions = definitions;
                for (int i = 0; i < path.length - 1; i++) {
                    String link = path[i];
                    for (SettingItem item : currentDefinitions) {
                        if (item instanceof TextSetting textSetting && TextUtils.equals(link, textSetting.linkAlias)) {
                            matchedTextSetting = textSetting;
                            try {
                                PyObject subFragment = textSetting.createSubFragmentCallback != null ? textSetting.createSubFragmentCallback.call() : null;
                                if (subFragment != null) {
                                    currentDefinitions = parsePySettingDefinitions(subFragment.asList());
                                }
                            } catch (Exception ignored) {
                            }
                            break;
                        }
                    }
                    if (matchedTextSetting == null && currentDefinitions.isEmpty()) {
                        AndroidUtilities.runOnUIThread(() -> BulletinFactory.of(baseFragment).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.PluginSettingNotFound)).show());
                        return;
                    }
                }
                if (matchedTextSetting == null) {
                    return;
                }
                activity = new PluginSettingsActivity(plugin, matchedTextSetting.text, currentDefinitions, matchedTextSetting.createSubFragmentCallback, path[path.length - 1])
                        .setSettingsLinkPrefix(String.join(":", Arrays.copyOf(path, path.length - 1)));
            }
            PluginSettingsActivity finalActivity = activity;
            AndroidUtilities.runOnUIThread(() -> baseFragment.presentFragment(finalActivity));
            AndroidUtilities.runOnUIThread(finalActivity::checkTargetSetting);
        });
    }

    @Override
    public void openPluginSetting(String pluginId, String settingId, BaseFragment baseFragment) {
        Plugin plugin = getPluginsController().plugins.get(pluginId);
        if (plugin != null) {
            openPluginSetting(plugin, settingId, baseFragment);
        }
    }

    @Override
    public void sharePlugin(String pluginId) {
        BaseFragment lastFragment = LaunchActivity.getSafeLastFragment();
        if (lastFragment == null) {
            return;
        }
        String pluginPath = getPluginPath(pluginId);
        File tempDir = new File(ApplicationLoader.getFilesDirFixed(), "temp");
        if (!tempDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            tempDir.mkdirs();
        }
        File outputFile = new File(tempDir, pluginId + PluginsConstants.PLUGINS_EXT);
        try {
            copyFile(pluginPath, outputFile);
            Uri uri = FileProvider.getUriForFile(lastFragment.getContext(), ApplicationLoader.getApplicationId() + ".provider", outputFile);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.setType("application/x-plugin");
            lastFragment.startActivityForResult(Intent.createChooser(intent, LocaleController.getString(R.string.ShareFile)), 500);
            outputFile.deleteOnExit();
        } catch (IOException | IllegalArgumentException e) {
            FileLog.e(e);
            sharePluginSafe(pluginId);
        }
    }

    private void sharePluginSafe(String pluginId) {
        BaseFragment lastFragment = LaunchActivity.getSafeLastFragment();
        if (lastFragment == null) {
            return;
        }
        File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "plugins-share");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        File outputFile = new File(dir, pluginId + PluginsConstants.PLUGINS_EXT);
        try {
            copyFile(getPluginPath(pluginId), outputFile);
            Uri uri = FileProvider.getUriForFile(lastFragment.getContext(), ApplicationLoader.getApplicationId() + ".provider", outputFile);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.setType("application/x-plugin");
            lastFragment.startActivityForResult(Intent.createChooser(intent, LocaleController.getString(R.string.ShareFile)), 500);
            outputFile.deleteOnExit();
        } catch (IOException | IllegalArgumentException e) {
            FileLog.e(e);
        }
    }

    @Override
    public void openInExternalApp(String pluginId) {
        BaseFragment lastFragment = LaunchActivity.getSafeLastFragment();
        if (lastFragment == null) {
            return;
        }
        File file = new File(getPluginPath(pluginId));
        if (file.exists()) {
            PluginFileViewer.getInstance().open(lastFragment, file, pluginId + ".plugin");
        }
    }

    public void setDebuggerListener(PyObject debuggerListener) {
        this.debuggerListener = debuggerListener;
    }

    private static void copyFile(String sourcePath, File targetFile) throws IOException {
        try (FileInputStream input = new FileInputStream(sourcePath);
             FileOutputStream output = new FileOutputStream(targetFile)) {
            output.getChannel().transferFrom(input.getChannel(), 0L, input.getChannel().size());
        }
    }

    private static void copyFile(File sourceFile, File targetFile) throws IOException {
        try (FileInputStream input = new FileInputStream(sourceFile);
             FileOutputStream output = new FileOutputStream(targetFile, false)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.flush();
        }
    }

    private static void copyStream(InputStream input, File targetFile) throws IOException {
        File parent = targetFile.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (FileOutputStream output = new FileOutputStream(targetFile, false)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.flush();
        }
    }

    private static boolean moveFile(File sourceFile, File targetFile) {
        if (sourceFile == null || targetFile == null || !sourceFile.exists()) {
            return false;
        }
        File parent = targetFile.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        if (targetFile.exists() && !targetFile.delete()) {
            return false;
        }
        if (sourceFile.renameTo(targetFile)) {
            return true;
        }
        try {
            copyFile(sourceFile, targetFile);
            //noinspection ResultOfMethodCallIgnored
            sourceFile.delete();
            return true;
        } catch (Throwable t) {
            FileLog.e("Failed to move file " + sourceFile + " to " + targetFile, t);
            return false;
        }
    }

    public static class Updater {
        public static final int STATUS_IDLE = 0;
        public static final int STATUS_CHECKING = 1;
        public static final int STATUS_LATEST = 2;
        public static final int STATUS_DOWNLOADING = 3;
        public static final int STATUS_READY = 4;

        private static final java.util.regex.Pattern PYTHON_SDK_APP_VERSION_PATTERN =
                java.util.regex.Pattern.compile("^app_version(>=|<=|==)(.+)$");
        private static final java.util.regex.Pattern PYTHON_SDK_APP_VERSION_CODE_PATTERN =
                java.util.regex.Pattern.compile("^app_version_code(>=|<=|==)(.+)$");

        private static PythonSdkUpdateInfo lastUpdateInfo;
        private static long lastCheckUpdateTime = 0L;
        private static int downloadObserverTag;
        private static boolean isLoading;
        private static boolean appRestartScheduled;
        private static boolean notifyWhenChangeStatus = true;
        public static int status = STATUS_IDLE;
        public static final Runnable notifyRunnable = () ->
                NotificationCenter.getGlobalInstance().postNotificationNameOnUIThread(NotificationCenter.pluginsPySdkInfoChanged);

        public static int getStatus() {
            return status;
        }

        public static void setStatus(int newStatus) {
            status = newStatus;
        }

        public static boolean getNotifyWhenChangeStatus() {
            return notifyWhenChangeStatus;
        }

        public static void setNotifyWhenChangeStatus(boolean value) {
            notifyWhenChangeStatus = value;
        }

        public static class PythonSdkUpdateInfo extends TLRPC.TL_help_appUpdate {
            public TLRPC.Message message;
            public boolean available;
            public String channel;
            public String appVersion;
            public String appVersionOperator;
            public String appVersionCode;
            public String appVersionCodeOperator;
            public String abi;
            public String signature;

            public PythonSdkUpdateInfo() {
                clear();
            }

            public void clear() {
                message = null;
                available = false;
                can_not_skip = false;
                channel = null;
                version = null;
                appVersion = null;
                appVersionOperator = null;
                appVersionCode = null;
                appVersionCodeOperator = null;
                document = null;
                abi = null;
                signature = null;
            }

            public boolean canInstall() {
                boolean appVersionCompatible = appVersion == null || appVersionOperator == null
                        || isAppVersionCompatible(appVersionOperator, appVersion);
                boolean appVersionCodeCompatible = appVersionCode == null || appVersionCodeOperator == null
                        || isAppVersionCodeCompatible(appVersionCodeOperator, appVersionCode);
                boolean sdkVersionNewer = version == null || isSdkVersionNewer(version, "beta".equals(channel));
                return appVersionCompatible && appVersionCodeCompatible && sdkVersionNewer
                        && document != null
                        && abi != null && abi.equals(Build.SUPPORTED_ABIS[0]);
            }

            public TLRPC.Message getMessage() {
                return message;
            }

            public void setMessage(TLRPC.Message message) {
                this.message = message;
            }

            public String getChannel() {
                return channel;
            }

            public void setChannel(String channel) {
                this.channel = channel;
            }

            public void setAppVersion(String appVersion) {
                this.appVersion = appVersion;
            }

            public void setAppVersionOperator(String appVersionOperator) {
                this.appVersionOperator = appVersionOperator;
            }

            public void setAppVersionCode(String appVersionCode) {
                this.appVersionCode = appVersionCode;
            }

            public void setAppVersionCodeOperator(String appVersionCodeOperator) {
                this.appVersionCodeOperator = appVersionCodeOperator;
            }

            public void setAbi(String abi) {
                this.abi = abi;
            }

            public String getSignature() {
                return signature;
            }

            public void setSignature(String signature) {
                this.signature = signature;
            }
        }

        public static CharSequence getVersion() {
            String sdkVersion = (ExteraConfig.pluginsEngine && sdkInitialized) ? SDK_VERSION : null;
            boolean sdkBeta = SDK_BETA;
            if (sdkVersion == null) {
                File sdkDir = SDK_DIR;
                if (sdkDir == null) {
                    sdkDir = new File(new File(ApplicationLoader.getFilesDirFixed(), "chaquopy"), "plugins-sdk");
                }
                File vFile = new File(sdkDir, "v.txt");
                if (vFile.exists()) {
                    try (InputStream inputStream = new FileInputStream(vFile)) {
                        String content = readStreamFully(inputStream, MAX_SDK_VERSION_BYTES).trim();
                        sdkBeta = content.endsWith("|1");
                        int separator = content.indexOf('|');
                        sdkVersion = separator >= 0 ? content.substring(0, separator) : content;
                    } catch (IOException e) {
                        FileLog.e("Failed to read plugin SDK version file", e);
                    }
                }
            }
            if (sdkVersion == null) {
                return "SDK not unpacked";
            }
            return "v" + sdkVersion + (sdkBeta ? "-beta" : "");
        }

        public static CharSequence getStateString() {
            switch (getStatus()) {
                case STATUS_CHECKING:
                    return LocaleController.getString(R.string.CheckingForUpdates);
                case STATUS_LATEST:
                    return LocaleController.getString(R.string.LatestVersionInstalled) + " (" + getVersion() + ')';
                case STATUS_DOWNLOADING:
                    return LocaleController.getString(R.string.LoadingUpdate);
                case STATUS_READY:
                    return LocaleController.getString(R.string.RestartAppToTakeEffect);
                default:
                    return getVersion();
            }
        }

        public static void restartApp() {
            AndroidUtilities.runOnUIThread(() -> {
                if (appRestartScheduled || ApplicationLoader.applicationContext == null) {
                    return;
                }
                appRestartScheduled = true;
                try {
                    AppRestartHelper.triggerRebirth(ApplicationLoader.applicationContext,
                            new Intent(ApplicationLoader.applicationContext, LaunchActivity.class));
                } catch (Throwable t) {
                    appRestartScheduled = false;
                    FileLog.e("Failed to restart app for plugin SDK replacement", t);
                }
            });
        }

        public static boolean isRestartRequired() {
            return getPythonSdkUpdateFile().exists();
        }

        public static File getPythonSdkUpdateFile() {
            return new File(new File(ApplicationLoader.getFilesDirFixed(), "chaquopy"), "newSdk");
        }

        public static File getPythonCurrentSdkFile() {
            return new File(new File(ApplicationLoader.getFilesDirFixed(), "chaquopy"), "currentSdk");
        }

        public static File requestSdkFromApkFile() {
            return new File(new File(ApplicationLoader.getFilesDirFixed(), "chaquopy"), ".restoreSdk");
        }

        public static InputStream sdkFromApk() throws IOException {
            IOException lastError = null;
            for (String abi : Build.SUPPORTED_ABIS) {
                try {
                    return ApplicationLoader.applicationContext.getAssets()
                            .open("plugins_pysdk/sdk-" + abi + ".zip");
                } catch (IOException e) {
                    lastError = e;
                }
            }
            throw new IOException("No bundled plugin SDK for ABIs " + Arrays.toString(Build.SUPPORTED_ABIS), lastError);
        }

        public static boolean isSdkFromApk() {
            return new File(new File(ApplicationLoader.getFilesDirFixed(), "chaquopy"), ".currentSdkFromApk").exists()
                    || requestSdkFromApkFile().exists();
        }

        public static void setBuildFromApk(boolean fromApk) {
            File marker = new File(new File(ApplicationLoader.getFilesDirFixed(), "chaquopy"), ".currentSdkFromApk");
            if (marker.exists() && !fromApk) {
                deleteFileIfExists(marker);
            }
            if (!marker.exists() && fromApk) {
                touchFile(marker);
            }
        }

        public static void deleteSdkUpdateFile() {
            File updateFile = getPythonSdkUpdateFile();
            if (updateFile.exists()) {
                deleteFileIfExists(updateFile);
                updateStatus(STATUS_IDLE);
            }
        }

        public static void checkUpdates() {
            checkUpdates(false);
        }

        public static void checkUpdates(boolean force) {
            long now = System.currentTimeMillis();
            boolean busyOrBusyRecently = getStatus() == STATUS_CHECKING
                    && Math.abs(now - lastCheckUpdateTime) < TimeUnit.SECONDS.toMillis(6L);
            if (busyOrBusyRecently || getStatus() > STATUS_LATEST) {
                return;
            }
            if (!force) {
                boolean recentlyChecked = Math.abs(now - ExteraConfig.sdkUpdateScheduleTimestamp) < TimeUnit.HOURS.toMillis(1L);
                if (!ExteraConfig.pluginsEngine || ExteraConfig.pluginsSafeMode || recentlyChecked) {
                    return;
                }
            }
            ExteraConfig.sdkUpdateScheduleTimestamp = now;
            ExteraConfig.editor.putLong("sdkUpdateScheduleTimestamp", now).apply();
            updateStatus(STATUS_CHECKING);
            lastCheckUpdateTime = now;
            RemoteUtils.searchMessages("python_sdk", new TLRPC.TL_inputMessagesFilterDocument(), (messages, error) -> {
                PythonSdkUpdateInfo updateInfo = null;
                boolean handled = false;
                if (error != null) {
                    FileLog.e("Failed to search messages with sdk updates: " + error.text);
                } else if (messages != null && (updateInfo = parsePythonSdkUpdateResponse(messages)) != null) {
                    if (!ExteraConfig.pluginsPySdkAutoUpdate && !updateInfo.can_not_skip) {
                        BaseFragment safeLastFragment = LaunchActivity.getSafeLastFragment();
                        if (safeLastFragment != null) {
                            PythonSdkUpdateInfo finalUpdateInfo = updateInfo;
                            AndroidUtilities.runOnUIThread(() -> safeLastFragment.showDialog(
                                    new PythonSdkUpdateDialog(safeLastFragment.getParentActivity(), finalUpdateInfo, safeLastFragment.getCurrentAccount())));
                        }
                        handled = true;
                    }
                    if (!handled) {
                        savePythonSdkArchive(updateInfo.getMessage(), updateInfo.document);
                        handled = true;
                    }
                }
                if (!handled) {
                    updateStatus(STATUS_LATEST);
                }
            }, 3000);
        }

        public static void restoreSdkFromApk() {
            touchFile(requestSdkFromApkFile());
        }

        private static void touchFile(File file) {
            try {
                File parent = file.getParentFile();
                if (parent != null && !parent.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                }
                if (!file.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    file.createNewFile();
                }
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }

        private static void updateStatus(int newStatus) {
            setStatus(newStatus);
            if (getNotifyWhenChangeStatus()) {
                AndroidUtilities.cancelRunOnUIThread(notifyRunnable);
                AndroidUtilities.runOnUIThread(notifyRunnable, newStatus == STATUS_CHECKING ? 0L : 600L);
            }
        }

        public static PythonSdkUpdateInfo parsePythonSdkUpdateResponse(TLRPC.messages_Messages res) {
            PythonSdkUpdateInfo updateInfo = new PythonSdkUpdateInfo();
            for (TLRPC.Message message : res.messages) {
                if (!(message instanceof TLRPC.TL_message)) {
                    continue;
                }
                TLRPC.TL_message tlMessage = (TLRPC.TL_message) message;
                if (TextUtils.isEmpty(tlMessage.message) || !(tlMessage.media instanceof TLRPC.TL_messageMediaDocument)) {
                    continue;
                }
                String text = tlMessage.message;
                boolean containsStable = text.contains("python_sdk_stable");
                boolean containsBeta = text.contains("python_sdk_beta");
                if (!containsStable && !containsBeta) {
                    continue;
                }
                if (containsBeta && !ExteraConfig.pluginsPySdkBetaVersions) {
                    continue;
                }
                StringBuilder textBuilder = new StringBuilder();
                boolean parsing = false;
                for (String rawLine : text.split("\n")) {
                    String line = rawLine.trim();
                    if (TextUtils.isEmpty(line) && !parsing) {
                        continue;
                    }
                    if (line.startsWith("python_sdk_")) {
                        updateInfo.setChannel(containsBeta ? "beta" : "stable");
                        parsing = true;
                    } else if (parsing) {
                        java.util.regex.Matcher versionMatcher = PYTHON_SDK_APP_VERSION_PATTERN.matcher(line);
                        if (versionMatcher.matches()) {
                            updateInfo.setAppVersionOperator(versionMatcher.group(1));
                            String group = versionMatcher.group(2);
                            updateInfo.setAppVersion(group != null ? group.trim() : null);
                            continue;
                        }
                        java.util.regex.Matcher versionCodeMatcher = PYTHON_SDK_APP_VERSION_CODE_PATTERN.matcher(line);
                        if (versionCodeMatcher.matches()) {
                            updateInfo.setAppVersionCodeOperator(versionCodeMatcher.group(1));
                            String group = versionCodeMatcher.group(2);
                            updateInfo.setAppVersionCode(group != null ? group.trim() : null);
                            continue;
                        }
                        String[] parts = line.split("=", 2);
                        if (parts.length == 2) {
                            String key = parts[0].trim();
                            String value = parts[1].trim();
                            if ("version".equals(key)) {
                                updateInfo.version = value;
                            } else if ("abi".equals(key)) {
                                updateInfo.setAbi(value);
                            } else if ("signature".equals(key)) {
                                updateInfo.setSignature(value);
                            } else if ("can_not_skip".equals(key)) {
                                updateInfo.can_not_skip = Boolean.parseBoolean(value);
                            }
                        }
                    } else {
                        textBuilder.append(line).append("\n");
                    }
                }
                updateInfo.document = tlMessage.media.document;
                if (!updateInfo.canInstall()) {
                    updateInfo.clear();
                } else {
                    updateInfo.text = textBuilder.toString();
                    ArrayList<TLRPC.MessageEntity> entities = new ArrayList<>();
                    for (TLRPC.MessageEntity entity : tlMessage.entities) {
                        if (!(entity instanceof TLRPC.TL_messageEntityPre)) {
                            entities.add(entity);
                        }
                    }
                    updateInfo.entities = entities;
                    updateInfo.setMessage(message);
                }
            }
            if (updateInfo.getMessage() == null) {
                return null;
            }
            updateInfo.available = updateInfo.document != null && !TextUtils.isEmpty(updateInfo.version);
            lastUpdateInfo = updateInfo;
            return updateInfo;
        }

        public static boolean isSdkVersionNewer(String remoteVersion, boolean isBeta) {
            if (!ExteraConfig.pluginsPySdkBetaVersions && SDK_BETA) {
                return !isBeta;
            }
            return SDK_VERSION != null && AppUtils.compareVersions(">", remoteVersion, SDK_VERSION);
        }

        public static boolean isAppVersionCompatible(String operator, String targetVersion) {
            return AppUtils.compareVersions(operator, BuildVars.BUILD_VERSION_STRING, targetVersion);
        }

        public static boolean isAppVersionCodeCompatible(String operator, String targetVersion) {
            return AppUtils.compareVersions(operator, BuildConfig.VERSION_CODE, Integer.parseInt(targetVersion));
        }

        public static String hashBytes(InputStream inputStream) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-1");
                byte[] buffer = new byte[1048576];
                int read;
                while ((read = inputStream.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
                StringBuilder result = new StringBuilder(digest.getDigestLength() * 2);
                for (byte value : digest.digest()) {
                    result.append(String.format("%02x", value & 0xff));
                }
                inputStream.close();
                return result.toString();
            } catch (IOException | NoSuchAlgorithmException e) {
                FileLog.e(e);
                return null;
            }
        }

        public static void zipFolder(File sourceDir, File zipFile) {
            try (java.util.zip.ZipOutputStream outputStream =
                         new java.util.zip.ZipOutputStream(new FileOutputStream(zipFile))) {
                zipFolderRecursive(sourceDir, sourceDir, outputStream);
            } catch (IOException e) {
                FileLog.e("Failed to zip folder " + sourceDir + " to " + zipFile, e);
            }
        }

        private static void zipFolderRecursive(File baseDir, File dir, java.util.zip.ZipOutputStream outputStream) throws IOException {
            File[] files = dir.listFiles();
            if (files == null) {
                return;
            }
            String basePath = baseDir.getCanonicalPath();
            for (File file : files) {
                String relative = file.getCanonicalPath().substring(basePath.length());
                while (relative.startsWith("/") || relative.startsWith("\\")) {
                    relative = relative.substring(1);
                }
                if (file.isDirectory()) {
                    zipFolderRecursive(baseDir, file, outputStream);
                } else {
                    outputStream.putNextEntry(new java.util.zip.ZipEntry(relative));
                    try (FileInputStream inputStream = new FileInputStream(file)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = inputStream.read(buffer)) != -1) {
                            outputStream.write(buffer, 0, read);
                        }
                    }
                    outputStream.closeEntry();
                }
            }
        }

        private static void copyArchiveToPluginsDirectory(TLRPC.Document document, boolean autoRestartEngine) {
            File updateFile = getPythonSdkUpdateFile();
            try {
                File pathToAttach = FileLoader.getInstance(UserConfig.selectedAccount).getPathToAttach(document);
                if (pathToAttach == null || !pathToAttach.exists()) {
                    throw new IOException("Downloaded Python SDK archive is missing");
                }
                PythonSdkUpdateInfo updateInfo = lastUpdateInfo;
                if (!SdkSignature.verify(updateInfo != null ? updateInfo.version : null, updateInfo != null ? updateInfo.abi : null, pathToAttach, updateInfo != null ? updateInfo.signature : null)) {
                    throw new IOException("Python SDK archive failed signature verification");
                }
                copyFile(pathToAttach, updateFile);
                if (autoRestartEngine) {
                    restartApp();
                } else {
                    updateStatus(STATUS_READY);
                }
            } catch (IOException e) {
                FileLog.e("Failed to copy plugins-sdk file", e);
                updateStatus(STATUS_LATEST);
            } catch (Exception e2) {
                FileLog.e("Failed to copy plugins-sdk file", e2);
                updateStatus(STATUS_LATEST);
            } finally {
                isLoading = false;
            }
        }

        public static void savePythonSdkArchive(TLRPC.Message msg, TLRPC.Document document) {
            savePythonSdkArchive(msg, document, false);
        }

        @SuppressWarnings("unused")
        public static void savePythonSdkArchive(TLRPC.Message msg, TLRPC.Document document, boolean autoRestartEngine) {
            if (isLoading || msg == null || document == null) {
                return;
            }
            MessageObject messageObject = new MessageObject(UserConfig.selectedAccount, msg, false, true);
            isLoading = true;
            updateStatus(STATUS_DOWNLOADING);
            if (!messageObject.mediaExists) {
                downloadObserverTag = DownloadController.getInstance(UserConfig.selectedAccount).generateObserverTag();
                FileLoader.getInstance(UserConfig.selectedAccount).loadFile(document, messageObject, FileLoader.PRIORITY_NORMAL, 0);
                DownloadController.getInstance(UserConfig.selectedAccount).addLoadingFileObserver(
                        FileLoader.getAttachFileName(document), messageObject, new DownloadController.FileDownloadProgressListener() {
                            @Override
                            public void onFailedDownload(String fileName, boolean canceled) {
                                FileLog.e("Failed to load plugins-sdk file");
                                isLoading = false;
                                updateStatus(STATUS_LATEST);
                            }

                            @Override
                            public void onSuccessDownload(String fileName) {
                                copyArchiveToPluginsDirectory(document, autoRestartEngine);
                            }

                            @Override
                            public void onProgressDownload(String fileName, long downloadSize, long totalSize) {
                            }

                            @Override
                            public void onProgressUpload(String fileName, long downloadSize, long totalSize, boolean isEncrypted) {
                            }

                            @Override
                            public int getObserverTag() {
                                return downloadObserverTag;
                            }
                        });
                return;
            }
            copyArchiveToPluginsDirectory(document, autoRestartEngine);
        }
    }

    private static String stackTraceToString(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
