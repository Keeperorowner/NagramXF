package com.exteragram.messenger.plugins.models;

import com.exteragram.messenger.plugins.PluginsConstants;

public class HeaderSetting extends SettingItem {
    public String text;

    public HeaderSetting(String text) {
        super(PluginsConstants.Settings.TYPE_HEADER);
        this.text = text;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }
}
