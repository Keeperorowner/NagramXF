package com.exteragram.messenger.ai.ui.activities;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

import com.exteragram.messenger.ai.AiConfig;
import com.exteragram.messenger.ai.AiController;
import com.exteragram.messenger.preferences.BasePreferencesActivity;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Locale;

import tw.nekomimi.nekogram.config.ConfigItem;
import tw.nekomimi.nekogram.settings.NekoTranslatorSettingsActivity;
import xyz.nextalone.nagram.NaConfig;

/** AI chat settings: services, roles, history and the generation options. */
public class AiPreferencesActivity extends BasePreferencesActivity {

    private static final int ENDPOINT = 1;
    private static final int ROLE = 2;
    private static final int HISTORY = 3;
    private static final int SHARED_PROVIDER = 4;
    private static final int SAVE_HISTORY = 5;
    private static final int REPLACE_TELEGRAM_EDITOR = 6;
    private static final int REPLACE_TELEGRAM_SUMMARIES = 7;
    private static final int RESPONSE_STREAMING = 8;
    private static final int SHOW_RESPONSE_ONLY = 9;
    private static final int INSERT_AS_QUOTE = 10;
    private static final int TEMPERATURE = 11;

    @Override
    protected boolean useSimpleActionBar() {
        return true;
    }

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.AIChat);
    }

    @Override
    public View createView(Context context) {
        View view = super.createView(context);
        // the large title scrolls away: the header shows it only once the top view is out of sight
        setHeaderTitle(null);
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                updateHeaderTitle();
            }
        });
        updateHeaderTitle();
        return view;
    }

    private void setHeaderTitle(CharSequence title) {
        if (simpleActionBar != null) {
            simpleActionBar.setTitle(title);
        } else if (actionBar != null) {
            actionBar.setTitle(title);
        }
    }

    private void updateHeaderTitle() {
        if (listView == null) {
            return;
        }
        boolean topVisible = listView.layoutManager.findFirstVisibleItemPosition() <= 0;
        setHeaderTitle(topVisible ? null : getTitle());
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asTopView(getTitle(), LocaleController.getString(R.string.AIChatInfo2), "exteraGramPlaceholders", "🤖"));
        items.add(UItem.asShadow(null));
        items.add(UItem.asHeader(LocaleController.getString(R.string.General)));
        items.add(UItem.asSettingsCell(ENDPOINT, R.drawable.msg_language, LocaleController.getString(R.string.AIChatServices), getEndpointValue()));
        items.add(UItem.asSettingsCell(ROLE, R.drawable.msg_openprofile, LocaleController.getString(R.string.AIChatRoles), AiConfig.getSelectedRole()));
        items.add(UItem.asSettingsCell(HISTORY, R.drawable.msg_discuss, LocaleController.getString(R.string.AIChatMessageHistory), getHistoryValue()));
        items.add(UItem.asCheck(SHARED_PROVIDER, LocaleController.getString(R.string.AIChatUseLlmProvider), null).setChecked(AiConfig.useLlmProviderConfig.Bool()));
        items.add(UItem.asCheck(SAVE_HISTORY, LocaleController.getString(R.string.AIChatSaveHistory), null).setChecked(AiConfig.saveHistoryConfig.Bool()));
        items.add(UItem.asShadow(LocaleController.getString(R.string.AIChatHistoryInfo)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.AIReplaceTelegram)));
        items.add(UItem.asCheck(REPLACE_TELEGRAM_EDITOR, LocaleController.getString(R.string.AIFeaturesEditor), LocaleController.getString(R.string.AIReplaceTelegramEditorInfo)).setChecked(NaConfig.INSTANCE.getReplaceTelegramAiEditor().Bool()));
        UItem summaries = UItem.asCheck(REPLACE_TELEGRAM_SUMMARIES, LocaleController.getString(R.string.AIFeaturesSummaries), LocaleController.getString(R.string.AIReplaceTelegramSummariesInfo)).setChecked(NaConfig.INSTANCE.getReplaceTelegramAiSummaries().Bool());
        summaries.hideDivider = true;
        items.add(summaries);
        items.add(UItem.asShadow(LocaleController.getString(R.string.AIReplaceTelegramInfo)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.AIGeneration)));
        items.add(UItem.asCheck(RESPONSE_STREAMING, LocaleController.getString(R.string.AIChatResponseStreaming), LocaleController.getString(R.string.AIChatResponseStreamingInfo)).setChecked(AiConfig.responseStreamingConfig.Bool()));
        items.add(UItem.asCheck(SHOW_RESPONSE_ONLY, LocaleController.getString(R.string.AIChatShowResponseOnly), null).setChecked(AiConfig.showResponseOnlyConfig.Bool()));
        UItem quote = UItem.asCheck(INSERT_AS_QUOTE, LocaleController.getString(R.string.AIChatInsertAsQuote), null).setChecked(AiConfig.insertAsQuoteConfig.Bool());
        quote.hideDivider = true;
        items.add(quote);
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.AIChatTemperature)));
        UItem temperature = UItem.asIntSlideView(1, 0, AiConfig.temperatureConfig.Int(), 20, this::formatTemperature, value -> {
            AiConfig.temperatureConfig.setConfigInt(value);
            AiConfig.syncFields();
        });
        temperature.id = TEMPERATURE;
        temperature.hideDivider = true;
        items.add(temperature);
        items.add(UItem.asShadow(LocaleController.getString(R.string.AIChatTemperatureInfo)));
    }

    private CharSequence formatTemperature(int value) {
        return String.format(Locale.US, "%.1f", value / 10.0f);
    }

    private CharSequence getHistoryValue() {
        if (!AiConfig.saveHistoryConfig.Bool()) {
            return LocaleController.getString(R.string.BlurOff);
        }
        int size = AiConfig.getConversationHistory().size();
        return size == 0 ? LocaleController.getString(R.string.BlockedEmpty) : LocaleController.formatPluralString("messages", size);
    }

    private String getEndpointValue() {
        try {
            String host = new URL(AiController.getInstance().getSelected().getUrl()).getHost();
            if (!TextUtils.isEmpty(host) && AiController.canUseAI()) {
                return host.contains("generativelanguage.googleapis") ? "Gemini" : host;
            }
            return LocaleController.getString(R.string.BlockedEmpty);
        } catch (MalformedURLException e) {
            return LocaleController.getString(R.string.BlockedEmpty);
        }
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ENDPOINT:
                presentFragment(AiConfig.useLlmProvider ? new NekoTranslatorSettingsActivity() : new ServicesActivity());
                return;
            case ROLE:
                presentFragment(new RolesActivity());
                return;
            case HISTORY:
                presentFragment(new AiHistoryActivity());
                return;
            case SHARED_PROVIDER: {
                boolean enabled = AiConfig.useLlmProviderConfig.toggleConfigBool();
                AiConfig.syncFields();
                // the two providers keep separate histories
                AiConfig.clearConversationHistory();
                item.setChecked(enabled);
                refreshRows();
                return;
            }
            case SAVE_HISTORY:
                toggle(AiConfig.saveHistoryConfig, item);
                return;
            case REPLACE_TELEGRAM_EDITOR:
                toggle(NaConfig.INSTANCE.getReplaceTelegramAiEditor(), item);
                return;
            case REPLACE_TELEGRAM_SUMMARIES:
                toggle(NaConfig.INSTANCE.getReplaceTelegramAiSummaries(), item);
                return;
            case RESPONSE_STREAMING:
                toggle(AiConfig.responseStreamingConfig, item);
                return;
            case SHOW_RESPONSE_ONLY:
                toggle(AiConfig.showResponseOnlyConfig, item);
                return;
            case INSERT_AS_QUOTE:
                toggle(AiConfig.insertAsQuoteConfig, item);
                return;
            default:
                break;
        }
    }

    private void toggle(ConfigItem config, UItem item) {
        boolean enabled = config.toggleConfigBool();
        AiConfig.syncFields();
        item.setChecked(enabled);
        refreshRows();
    }

    private void refreshRows() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        AiConfig.syncFields();
        refreshRows();
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        if (listView != null) {
            listView.setClipToPadding(false);
            if (!useSimpleActionBar()) {
                listView.setPadding(left, 0, right, bottom);
            }
        }
    }
}
