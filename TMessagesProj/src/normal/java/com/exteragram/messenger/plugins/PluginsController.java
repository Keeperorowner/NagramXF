package com.exteragram.messenger.plugins;

import android.graphics.drawable.Drawable;

import com.exteragram.messenger.plugins.hooks.MenuItemRecord;
import com.exteragram.messenger.plugins.hooks.PluginsHooks;

import org.telegram.messenger.MessageObject;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * No-op stand-in for the plugin runtime in the normal flavor.
 */
public class PluginsController {

    public static final class SafeModeReason {
        public static final String PLUGIN_CRASH = "plugin_crash";

        private SafeModeReason() {
        }
    }

    private static final PluginsController INSTANCE = new PluginsController();

    public final Map<String, ?> plugins = Collections.emptyMap();

    public Map<String, ?> getPlugins() {
        return plugins;
    }

    public static boolean isPluginEngineSupported() {
        return false;
    }

    public static boolean isPluginEngineAvailable() {
        return false;
    }

    public static boolean isPlugin(MessageObject messageObject) {
        return false;
    }

    public static boolean isPluginFileIcon(int icon) {
        return false;
    }

    public static Drawable getPluginFileIconDrawable(int icon) {
        return null;
    }

    public static int getFileIconId(String fileName) {
        return -1;
    }

    public static void markPendingSafeModeCrash(String reason, String crashedPluginId) {
    }

    public static PluginsController getInstance() {
        return INSTANCE;
    }

    public void init(Runnable runnable) {
        if (runnable != null) {
            runnable.run();
        }
    }

    public void executeOnAppEvent(String eventName) {
    }

    public TLObject executePreRequestHook(String hookName, int account, TLObject request) {
        return request;
    }

    public PluginsHooks.PostRequestResult executePostRequestHook(String hookName, int account,
                                                                  TLObject response, TLRPC.TL_error error) {
        return new PluginsHooks.PostRequestResult(response, error);
    }

    public TLRPC.Update executeUpdateHook(String hookName, int account, TLRPC.Update update) {
        return update;
    }

    public TLRPC.Updates executeUpdatesHook(String hookName, int account, TLRPC.Updates updates) {
        return updates;
    }

    public SendMessagesHelper.SendMessageParams executeSendMessageHook(int account,
                                                                       SendMessagesHelper.SendMessageParams params) {
        return params;
    }

    public List<MenuItemRecord> getMenuItemsForLocation(String menuType, Map<String, Object> context) {
        return Collections.emptyList();
    }

    public void loadPluginSettings() {
    }

    public void showInstallDialog(BaseFragment baseFragment, MessageObject messageObject) {
    }
}
