package com.exteragram.messenger.plugins.ui;

import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LanguageDetector;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.TranslateController;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;

import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.translate.Translator;
import tw.nekomimi.nekogram.translate.TranslatorKt;

/**
 * Machine translation of plugin settings texts, cached per plugin and per cache identity.
 *
 * <p>A page collects the texts it shows and hands them to {@link #check(Collection)}; texts that are
 * not cached yet are translated in the background and every registered {@link Listener} is notified
 * once the batch starts, so the page can show a loading state, and once it finishes, so the page can
 * re-render with the translated strings.
 *
 * <p>The cache identity is the full language tag of the effective target locale, script and region
 * included, together with the effective provider. Any change drops the cache and starts a new
 * generation; results of older generations are discarded instead of being written back.
 */
public final class PluginSettingsTranslation {

    /** Upper bound of the sample handed to the language detector. */
    private static final int DETECTION_SAMPLE_LENGTH = 2000;

    private static final HashMap<String, PluginSettingsTranslation> instances = new HashMap<>();

    private final HashMap<String, String> translations = new HashMap<>();
    /** In-flight texts of the current generation, mapped to the request that owns them. */
    private final HashMap<String, Long> pending = new HashMap<>();
    private final HashSet<String> failed = new HashSet<>();
    private final ArrayList<Listener> listeners = new ArrayList<>();
    /** Cache generation; a request only applies its result while its generation is still current. */
    private int generation;
    /** Identifier of the latest issued request; older requests must not apply their results. */
    private long requestToken;
    /** Full language tag of the effective target locale; part of the cache identity. */
    private String targetTag;
    /** Effective translation provider; part of the cache identity. */
    private int targetProvider;
    private String detectedLanguage;
    private boolean detecting;
    private boolean detectionFailed;
    private boolean enabled;

    /**
     * Notified on the UI thread when the translate mode is toggled, when a batch starts or finishes,
     * and when language detection finishes. {@code failed} is true when the finished batch left at
     * least one text untranslated.
     */
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

    /**
     * True only when the language detector positively identified the page texts as the effective
     * target locale, so translating them would not help. Everything else - no detector, a failed or
     * unfinished detection, a different language, or a detection that does not confirm a script or
     * region the target asks for - keeps the translate entry available for manual use.
     */
    public boolean isKnownSameLanguage() {
        final String detected = detectedLanguage;
        if (TextUtils.isEmpty(detected)) {
            return false;
        }
        final String tag = targetTag != null ? targetTag : resolveTargetTag();
        if (TextUtils.isEmpty(tag)) {
            return false;
        }
        final Locale detectedLocale = Locale.forLanguageTag(detected);
        final Locale targetLocale = Locale.forLanguageTag(tag);
        if (TextUtils.isEmpty(detectedLocale.getLanguage()) || TextUtils.isEmpty(targetLocale.getLanguage())
                || !TextUtils.equals(detectedLocale.getLanguage(), targetLocale.getLanguage())) {
            return false;
        }
        // The detector reports a language only, so a requested script or region has to be confirmed
        // by the detection itself; otherwise the entry stays visible.
        final String targetScript = targetLocale.getScript();
        if (!TextUtils.isEmpty(targetScript) && !targetScript.equalsIgnoreCase(detectedLocale.getScript())) {
            return false;
        }
        final String targetRegion = targetLocale.getCountry();
        return TextUtils.isEmpty(targetRegion) || targetRegion.equalsIgnoreCase(detectedLocale.getCountry());
    }

    /**
     * True when detection finished and the texts still need translating. Pages must not use this as
     * their only visibility condition: without a detector it stays false while translation still
     * works, so the entry has to remain visible for manual use.
     */
    public boolean isTranslatable() {
        return detectedLanguage != null && !isKnownSameLanguage();
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

    /** Forgets the failed texts so the next {@link #check(Collection)} retries them. */
    public void resetFailed() {
        failed.clear();
    }

    /**
     * Requests translations for every text that is not cached, pending or known to fail, with
     * duplicated text requested once. The cache identity (target language tag and effective
     * provider) is resolved here, so changing either of them - the application language included
     * when the target follows it - invalidates the cache and abandons the results that are still in
     * flight.
     */
    public void check(Collection<String> texts) {
        final String tag = resolveTargetTag();
        final int provider = resolveTargetProvider();
        if (!TextUtils.equals(tag, targetTag) || provider != targetProvider) {
            targetTag = tag;
            targetProvider = provider;
            generation++;
            translations.clear();
            failed.clear();
            pending.clear();
        }
        if (texts == null || texts.isEmpty()) {
            return;
        }
        if (detectedLanguage == null && !detecting && !detectionFailed && LanguageDetector.hasSupport()) {
            detect(texts);
        }
        if (!enabled) {
            return;
        }
        final LinkedHashSet<String> request = new LinkedHashSet<>();
        for (String text : texts) {
            if (TextUtils.isEmpty(text) || translations.containsKey(text)
                    || pending.containsKey(text) || failed.contains(text)) {
                continue;
            }
            request.add(text);
        }
        if (!request.isEmpty()) {
            request(request, resolveRequestLocale(), tag, provider);
        }
    }

    private void request(Collection<String> texts, final Locale locale, final String tag, final int provider) {
        // One request per unique text, in page order, so pending and the batch counter share a set.
        final LinkedHashSet<String> unique = new LinkedHashSet<>(texts);
        final long token = ++requestToken;
        final int requestGeneration = generation;
        for (String text : unique) {
            pending.put(text, token);
        }
        final int[] remaining = {unique.size()};
        final boolean[] anyFailed = {false};
        notifyListenersLater(false);
        for (String text : unique) {
            final String original = text;
            Translator.translate(locale, original, provider, new Translator.Companion.TranslateCallBack() {
                @Override
                public void onSuccess(String translation) {
                    finish(translation);
                }

                @Override
                public void onFailed(boolean unsupported, String message) {
                    finish(null);
                }

                /** Applies one result when it still belongs to this request of the current identity. */
                private void finish(String translation) {
                    if (requestGeneration != generation || provider != targetProvider
                            || !TextUtils.equals(tag, targetTag)) {
                        return;
                    }
                    final Long owner = pending.get(original);
                    if (owner == null || owner.longValue() != token) {
                        // Duplicate callback, or the text was already taken over by a newer request.
                        return;
                    }
                    pending.remove(original);
                    if (TextUtils.isEmpty(translation)) {
                        failed.add(original);
                        anyFailed[0] = true;
                    } else {
                        translations.put(original, translation);
                        failed.remove(original);
                    }
                    if (--remaining[0] > 0) {
                        return;
                    }
                    // Stop offering the mode once nothing can be translated at all.
                    if (anyFailed[0] && translations.isEmpty()) {
                        enabled = false;
                    }
                    notifyListeners(anyFailed[0]);
                }
            });
        }
    }

    /** Samples the page texts once, to know whether translating them would be pointless. */
    private void detect(Collection<String> texts) {
        final StringBuilder sample = new StringBuilder();
        for (String text : texts) {
            if (TextUtils.isEmpty(text)) {
                continue;
            }
            if (sample.length() > 0) {
                sample.append('\n');
            }
            sample.append(text);
            if (sample.length() >= DETECTION_SAMPLE_LENGTH) {
                break;
            }
        }
        if (sample.length() == 0) {
            return;
        }
        final String query = sample.length() > DETECTION_SAMPLE_LENGTH
                ? sample.substring(0, DETECTION_SAMPLE_LENGTH) : sample.toString();
        detecting = true;
        LanguageDetector.detectLanguage(query, new LanguageDetector.StringCallback() {
            @Override
            public void run(final String language) {
                AndroidUtilities.runOnUIThread(new Runnable() {
                    @Override
                    public void run() {
                        detecting = false;
                        if (TextUtils.isEmpty(language)
                                || TextUtils.equals(language, TranslateController.UNKNOWN_LANGUAGE)) {
                            detectionFailed = true;
                            return;
                        }
                        detectedLanguage = language;
                        notifyListeners(false);
                    }
                });
            }
        }, new LanguageDetector.ExceptionCallback() {
            @Override
            public void run(Exception e) {
                AndroidUtilities.runOnUIThread(new Runnable() {
                    @Override
                    public void run() {
                        detecting = false;
                        detectionFailed = true;
                    }
                });
            }
        });
    }

    private void notifyListeners(boolean failed) {
        for (Listener listener : new ArrayList<>(listeners)) {
            try {
                listener.onTranslationChanged(failed);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Posts the notification to the main thread queue, so a request started while laying out cannot
     * re-enter layout from inside {@link #check(Collection)}.
     */
    private void notifyListenersLater(final boolean failed) {
        AndroidUtilities.runOnUIThread(new Runnable() {
            @Override
            public void run() {
                notifyListeners(failed);
            }
        });
    }

    /**
     * Cache identity of the effective target locale: the full language tag, so a script or region
     * switch never reuses the cache. An empty setting follows the current application locale; a
     * configured value is parsed as a language tag, with {@code _} accepted in place of {@code -}.
     */
    private static String resolveTargetTag() {
        final String configured = NekoConfig.translateToLang.String();
        if (!TextUtils.isEmpty(configured)) {
            final String tag = canonicalTag(Locale.forLanguageTag(configured.replace('_', '-')));
            return tag != null ? tag : configured;
        }
        final Locale current = LocaleController.getInstance().getCurrentLocale();
        final String tag = canonicalTag(current != null ? current : Locale.getDefault());
        return tag != null ? tag : "en";
    }

    /** Canonical form of a locale tag, so locales that describe the same locale compare equal. */
    private static String canonicalTag(Locale locale) {
        if (locale == null) {
            return null;
        }
        final String raw = locale.toLanguageTag();
        if (TextUtils.isEmpty(raw) || "und".equals(raw)) {
            return null;
        }
        final String tag = Locale.forLanguageTag(raw).toLanguageTag();
        return TextUtils.isEmpty(tag) || "und".equals(tag) ? raw : tag;
    }

    /**
     * Locale handed to {@link Translator#translate}. An empty setting follows the current
     * application locale, so the region the provider understands is kept; a configured value goes
     * through the existing code-to-locale path with the {@code nb -> no} normalization the
     * translation stack uses.
     */
    private static Locale resolveRequestLocale() {
        String configured = NekoConfig.translateToLang.String();
        if (TextUtils.isEmpty(configured)) {
            final Locale current = LocaleController.getInstance().getCurrentLocale();
            return current != null ? current : Locale.getDefault();
        }
        if ("nb".equals(configured)) {
            configured = "no";
        }
        return TranslatorKt.getCode2Locale(configured);
    }

    /**
     * Provider the requests are sent to. Settings labels are short and numerous, so the LLM
     * translator is never used for them: the configured LLM provider is replaced by Telegram, and
     * every other configured provider is used as is.
     */
    private static int resolveTargetProvider() {
        final int configured = NekoConfig.translationProvider.Int();
        return configured == Translator.providerLLMTranslator ? Translator.providerTelegram : configured;
    }
}
