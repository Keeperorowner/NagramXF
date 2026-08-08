package com.exteragram.messenger.plugins.models;

import com.chaquo.python.PyObject;

public class TextSetting extends SettingItem {
    public String text;
    public String subtext;
    public boolean accent;
    public boolean red;
    public PyObject onClickCallback;
    public PyObject createSubFragmentCallback;

    public TextSetting(String text, String subtext, String icon, boolean accent, boolean red, PyObject onClickCallback, PyObject createSubFragmentCallback, PyObject onLongClickCallback, String linkAlias) {
        super("text", icon, onLongClickCallback, linkAlias);
        this.text = text;
        this.subtext = subtext;
        this.accent = accent;
        this.red = red;
        this.onClickCallback = onClickCallback;
        this.createSubFragmentCallback = createSubFragmentCallback;
    }

    @Override
    public void cleanup() {
        super.cleanup();
        closeCallback(onClickCallback);
        closeCallback(createSubFragmentCallback);
        onClickCallback = null;
        createSubFragmentCallback = null;
    }
}
