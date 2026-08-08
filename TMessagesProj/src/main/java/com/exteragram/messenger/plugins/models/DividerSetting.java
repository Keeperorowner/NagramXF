package com.exteragram.messenger.plugins.models;

import com.exteragram.messenger.plugins.PluginsConstants;

public class DividerSetting extends SettingItem {
    public String text;

    public DividerSetting(String text) {
        super(PluginsConstants.Settings.TYPE_DIVIDER);
        this.text = text;
    }
}
