package com.exteragram.messenger.plugins.hooks;

import java.util.Map;

/**
 * No-op stand-in for the plugin runtime in the normal flavor.
 */
public class MenuItemRecord {

    public interface OnClickCallback {
        void call(Map<String, Object> context);
    }

    public String text;
    public int iconResId;
    public String itemId;
    public String pluginId;
    public final OnClickCallback onClickCallback = context -> {
    };

    public void executeClick(Object contextData) {
    }
}
