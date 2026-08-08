package com.exteragram.messenger.plugins.models;

import com.exteragram.messenger.plugins.PluginsConstants;

public class HeaderSetting extends SettingItem {
    public String text;

    public HeaderSetting(String text) {
        super(PluginsConstants.Settings.TYPE_HEADER);
        this.text = text;
    }
}
