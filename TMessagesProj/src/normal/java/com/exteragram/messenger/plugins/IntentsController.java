package com.exteragram.messenger.plugins;

import android.app.Activity;
import android.content.Intent;

import org.telegram.messenger.MessageObject;
import org.telegram.ui.ActionBar.BaseFragment;

import java.io.File;

/**
 * No-op stand-in for the plugin runtime in the normal flavor.
 */
public final class IntentsController {

    public static final int PLACE_UNKNOWN = 0;
    public static final int PLACE_CHAT_ACTIVITY = 1;
    public static final int PLACE_FILTERED_SEARCH_VIEW = 2;
    public static final int PLACE_SHARED_MEDIA_LAYOUT = 3;
    public static final int PLACE_SEARCH_DOWNLOADS_CONTAINER = 4;
    public static final int PLACE_CHANNEL_ADMIN_LOG_ACTIVITY = 5;

    public static int resolvePlace(BaseFragment parentFragment) {
        return PLACE_UNKNOWN;
    }

    public static boolean dispatchFileOpen(int place, File file, String fileName, MessageObject message,
                                           Activity activity, BaseFragment parentFragment) {
        return false;
    }

    public static boolean dispatchBeforeIntent(Intent intent) {
        return false;
    }

    public static void dispatchAfterIntent(Intent intent) {
    }

    private IntentsController() {
    }
}
