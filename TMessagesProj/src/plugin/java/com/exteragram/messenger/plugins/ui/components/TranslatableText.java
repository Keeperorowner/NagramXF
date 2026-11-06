package com.exteragram.messenger.plugins.ui.components;

import androidx.annotation.NonNull;

import com.exteragram.messenger.plugins.ui.PluginSettingsTranslation;

/**
 * Settings text that resolves its translation once and keeps that snapshot for the lifetime of the
 * object, so a length read and the following character reads always see the same string. The page
 * rebuilds its rows when new translations arrive, which creates fresh instances.
 */
public final class TranslatableText implements CharSequence {

    private final String pluginId;
    private final String original;
    private String value;

    public TranslatableText(String pluginId, CharSequence original) {
        this.pluginId = pluginId;
        this.original = original == null ? "" : original.toString();
    }

    public String getOriginal() {
        return original;
    }

    private String resolve() {
        if (value == null) {
            String resolved = PluginSettingsTranslation.of(pluginId).text(original);
            value = resolved == null ? original : resolved;
        }
        return value;
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
