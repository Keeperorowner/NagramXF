package com.exteragram.messenger.plugins.ui;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;

import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.translate.Translator;
import tw.nekomimi.nekogram.translate.TranslatorKt;

/**
 * Machine translation of plugin settings texts, cached per plugin and per target language.
 *
 * <p>A page collects the texts it shows and hands them to {@link #check(Collection)}; texts that are
 * not cached yet are translated in the background and every registered {@link Listener} is notified
 * once the batch finishes, so the page can re-render with the translated strings. When the target
 * language changes the cache is dropped and rebuilt on the next {@code check}.
 */
public final class PluginSettingsTranslation {

    private static final HashMap<String, PluginSettingsTranslation> instances = new HashMap<>();

    private final HashMap<String, String> translations = new HashMap<>();
    private final HashSet<String> pending = new HashSet<>();
    private final HashSet<String> failed = new HashSet<>();
    private final ArrayList<Listener> listeners = new ArrayList<>();
    private String targetLanguage;
    private boolean enabled;

    /** Notified on the UI thread whenever translations arrive or the mode is toggled. */
    public interface Listener {
        void onTranslationChanged(boolean failed);
    }

    private PluginSettingsTranslation() {
    }

    /** Shared instance for a plugin, so the cache survives page re-creation. */
    public static PluginSettingsTranslation of(String pluginId) {
        synchronized (instances) {
            PluginSettingsTranslation instance = instances.get(pluginId);
            if (instance == null) {
                instance = new PluginSettingsTranslation();
                instances.put(pluginId, instance);
            }
            return instance;
        }
    }

    public boolean getEnabled() {
        return enabled;
    }

    public boolean isLoading() {
        return !pending.isEmpty();
    }

    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) {
            return;
        }
        this.enabled = enabled;
        if (enabled) {
            failed.clear();
        }
        notifyListeners(false);
    }

    /** Translated text for {@code original}, or the original when no translation is available. */
    public String text(String original) {
        if (!enabled || original == null) {
            return original;
        }
        String translated = translations.get(original);
        return translated != null ? translated : original;
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void resetFailed() {
        failed.clear();
    }

    /**
     * Requests translations for every text that is not cached, pending or known to fail. The target
     * language is resolved here so a language switch invalidates the cache automatically.
     */
    public void check(Collection<String> texts) {
        final String language = resolveTargetLanguage();
        if (!TextUtils.equals(language, targetLanguage)) {
            targetLanguage = language;
            translations.clear();
            failed.clear();
        }
        if (!enabled || texts == null || texts.isEmpty()) {
            return;
        }
        final ArrayList<String> request = new ArrayList<>();
        for (String text : texts) {
            if (TextUtils.isEmpty(text) || translations.containsKey(text)
                    || pending.contains(text) || failed.contains(text)) {
                continue;
            }
            request.add(text);
        }
        if (!request.isEmpty()) {
            request(request, language);
        }
    }

    private void request(ArrayList<String> texts, String language) {
        pending.addAll(texts);
        final Locale locale = TranslatorKt.getCode2Locale(language);
        // Settings labels are short and numerous: never spend LLM tokens on them, use Telegram.
        final int provider = NekoConfig.translationProvider.Int() == Translator.providerLLMTranslator
                ? Translator.providerTelegram : 0;
        final int[] remaining = {texts.size()};
        final boolean[] anyFailed = {false};
        for (int i = 0; i < texts.size(); i++) {
            final String original = texts.get(i);
            Translator.translate(locale, original, provider, new Translator.Companion.TranslateCallBack() {
                @Override
                public void onSuccess(String translation) {
                    if (TextUtils.isEmpty(translation)) {
                        failed.add(original);
                        anyFailed[0] = true;
                    } else {
                        translations.put(original, translation);
                    }
                    finishOne();
                }

                @Override
                public void onFailed(boolean unsupported, String message) {
                    failed.add(original);
                    anyFailed[0] = true;
                    finishOne();
                }

                private void finishOne() {
                    pending.remove(original);
                    if (--remaining[0] <= 0) {
                        // Stop offering the mode once nothing can be translated at all.
                        if (anyFailed[0] && translations.isEmpty()) {
                            enabled = false;
                        }
                        notifyListeners(anyFailed[0]);
                    }
                }
            });
        }
    }

    private void notifyListeners(boolean failed) {
        for (Listener listener : new ArrayList<>(listeners)) {
            try {
                listener.onTranslationChanged(failed);
            } catch (Exception ignored) {
            }
        }
    }

    private static String resolveTargetLanguage() {
        String code = NekoConfig.translateToLang.String();
        return code == null ? "" : code;
    }
}
