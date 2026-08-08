package com.exteragram.messenger.plugins;

import android.app.Activity;
import android.content.Intent;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ChannelAdminLogActivity;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.ProfileActivity;

import java.io.File;

/**
 * Dispatches intent/file-open events to the Python plugin SDK.
 */
public final class IntentsController {
    public static final int PLACE_UNKNOWN = 0;
    public static final int PLACE_CHAT_ACTIVITY = 1;
    public static final int PLACE_FILTERED_SEARCH_VIEW = 2;
    public static final int PLACE_SHARED_MEDIA_LAYOUT = 3;
    public static final int PLACE_SEARCH_DOWNLOADS_CONTAINER = 4;
    public static final int PLACE_CHANNEL_ADMIN_LOG_ACTIVITY = 5;

    private IntentsController() {
    }

    public static int resolvePlace(BaseFragment parentFragment) {
        if (parentFragment == null) {
            return PLACE_UNKNOWN;
        }
        if (parentFragment instanceof ChatActivity) {
            return PLACE_CHAT_ACTIVITY;
        }
        if (parentFragment instanceof ProfileActivity) {
            return PLACE_SHARED_MEDIA_LAYOUT;
        }
        if (parentFragment instanceof ChannelAdminLogActivity) {
            return PLACE_CHANNEL_ADMIN_LOG_ACTIVITY;
        }
        return PLACE_UNKNOWN;
    }

    public static boolean dispatchBeforeIntent(Intent intent) {
        if (!PluginsController.isPluginEngineAvailable()) {
            return false;
        }
        try {
            return PythonPluginsEngine.INSTANCE.dispatchIntentHook(intent, true);
        } catch (Throwable t) {
            FileLog.e(t);
            return false;
        }
    }

    public static void dispatchAfterIntent(Intent intent) {
        if (!PluginsController.isPluginEngineAvailable()) {
            return;
        }
        try {
            PythonPluginsEngine.INSTANCE.dispatchIntentHook(intent, false);
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    public static boolean dispatchFileOpen(int place, File file, String fileName, MessageObject message, Activity activity, BaseFragment parentFragment) {
        if (!PluginsController.isPluginEngineAvailable()) {
            return false;
        }
        try {
            return PythonPluginsEngine.INSTANCE.onFileOpen(place, file, fileName, message, activity, parentFragment);
        } catch (Throwable t) {
            FileLog.e(t);
            return false;
        }
    }
}
