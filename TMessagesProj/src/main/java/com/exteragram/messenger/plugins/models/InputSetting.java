package com.exteragram.messenger.plugins.models;

import com.chaquo.python.PyObject;
import com.exteragram.messenger.plugins.PluginsConstants;

public class InputSetting extends SettingItem {
    public String key;
    public String text;
    public String defaultValue;
    public String subtext;
    public PyObject onChangeCallback;

    public InputSetting(String key, String text, String defaultValue, String subtext, String icon, PyObject onChangeCallback, PyObject onLongClickCallback, String linkAlias) {
        super(PluginsConstants.Settings.TYPE_INPUT, icon, onLongClickCallback, linkAlias);
        this.key = key;
        this.text = text;
        this.defaultValue = defaultValue;
        this.subtext = subtext;
        this.onChangeCallback = onChangeCallback;
    }

    @Override
    public void cleanup() {
        super.cleanup();
        closeCallback(onChangeCallback);
        onChangeCallback = null;
    }
}
