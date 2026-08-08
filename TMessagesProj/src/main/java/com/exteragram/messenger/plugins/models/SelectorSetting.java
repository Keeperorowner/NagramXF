package com.exteragram.messenger.plugins.models;

import com.chaquo.python.PyObject;
import com.exteragram.messenger.plugins.PluginsConstants;

public class SelectorSetting extends SettingItem {
    public String key;
    public String text;
    public int defaultValue;
    public String[] items;
    public PyObject onChangeCallback;

    public SelectorSetting(String key, String text, int defaultValue, String[] items, String icon, PyObject onChangeCallback, PyObject onLongClickCallback, String linkAlias) {
        super(PluginsConstants.Settings.TYPE_SELECTOR, icon, onLongClickCallback, linkAlias);
        this.key = key;
        this.text = text;
        this.defaultValue = defaultValue;
        this.items = items;
        this.onChangeCallback = onChangeCallback;
    }

    @Override
    public void cleanup() {
        super.cleanup();
        closeCallback(onChangeCallback);
        onChangeCallback = null;
    }
}
