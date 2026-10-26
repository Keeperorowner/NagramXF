package com.exteragram.messenger.plugins.ui.components;

import androidx.annotation.NonNull;

import com.exteragram.messenger.plugins.ui.PluginSettingsTranslation;

/**
 * Settings text that resolves its translation every time it is rendered, so a page only has to
 * rebind its rows after {@link PluginSettingsTranslation} reports new translations.
 */
public final class TranslatableText implements CharSequence {

    private final String pluginId;
    private final String original;

    public TranslatableText(String pluginId, CharSequence original) {
        this.pluginId = pluginId;
        this.original = original == null ? "" : original.toString();
    }

    public String getOriginal() {
        return original;
    }

    private String resolve() {
        return PluginSettingsTranslation.of(pluginId).text(original);
    }

    @Override
    public int length() {
        return resolve().length();
    }

    @Override
    public char charAt(int index) {
        return resolve().charAt(index);
    }

    @NonNull
    @Override
    public CharSequence subSequence(int start, int end) {
        return resolve().subSequence(start, end);
    }

    @NonNull
    @Override
    public String toString() {
        return resolve();
    }
}
