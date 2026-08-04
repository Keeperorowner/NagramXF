package com.exteragram.messenger.ai.ui.activities;

import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.exteragram.messenger.ai.AiConfig;
import com.exteragram.messenger.ai.AiController;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SeekBarView;

import java.net.MalformedURLException;
import java.net.URL;

import tw.nekomimi.nekogram.config.CellGroup;
import tw.nekomimi.nekogram.config.cell.AbstractConfigCell;
import tw.nekomimi.nekogram.config.cell.ConfigCellCustom;
import tw.nekomimi.nekogram.config.cell.ConfigCellHeader;
import tw.nekomimi.nekogram.config.cell.ConfigCellTextCheck;
import tw.nekomimi.nekogram.config.cell.ConfigCellTextCheckIcon;
import tw.nekomimi.nekogram.settings.BaseNekoXSettingsActivity;
import tw.nekomimi.nekogram.settings.NekoTranslatorSettingsActivity;

@SuppressWarnings("FieldCanBeLocal")
public class AiPreferencesActivity extends BaseNekoXSettingsActivity {

    private ListAdapter listAdapter;

    private final CellGroup cellGroup = new CellGroup(this);

    private final AbstractConfigCell headerGeneral = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.General)));
    private final ConfigCellTextCheckIcon servicesRow = (ConfigCellTextCheckIcon) cellGroup.appendCell(new ConfigCellTextCheckIcon(null, "AIChatServices", getString(R.string.AIChatServices), R.drawable.msg_language, true, () -> presentFragment(new ServicesActivity())));
    private final AbstractConfigCell useLlmProviderRow = cellGroup.appendCell(new ConfigCellTextCheckIcon(AiConfig.useLlmProviderConfig, null, getString(R.string.AIChatUseLlmProvider), R.drawable.magic_stick, true, null));
    private final ConfigCellTextCheckIcon rolesRow = (ConfigCellTextCheckIcon) cellGroup.appendCell(new ConfigCellTextCheckIcon(null, "AIChatRoles", getString(R.string.AIChatRoles), R.drawable.msg_openprofile, true, () -> presentFragment(new RolesActivity())));
    private final AbstractConfigCell saveHistoryRow = cellGroup.appendCell(new ConfigCellTextCheckIcon(AiConfig.saveHistoryConfig, getString(R.string.AIChatMessageHistory), R.drawable.msg_discuss, false));

    private final AbstractConfigCell headerTemperature = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.AIChatTemperature)));
    private final AbstractConfigCell temperatureRow = cellGroup.appendCell(new ConfigCellCustom("AiChatTemperature", ConfigCellCustom.CUSTOM_ITEM_AiChatTemperature, false));

    private final AbstractConfigCell headerOther = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.AIChatOther)));
    private final AbstractConfigCell responseStreamingRow = cellGroup.appendCell(new ConfigCellTextCheck(AiConfig.responseStreamingConfig, null, getString(R.string.AIChatResponseStreaming)));
    private final AbstractConfigCell showResponseOnlyRow = cellGroup.appendCell(new ConfigCellTextCheck(AiConfig.showResponseOnlyConfig, null, getString(R.string.AIChatShowResponseOnly)));
    private final AbstractConfigCell insertAsQuoteRow = cellGroup.appendCell(new ConfigCellTextCheck(AiConfig.insertAsQuoteConfig, null, getString(R.string.AIChatInsertAsQuote)));

    public AiPreferencesActivity() {
        addRowsToMap(cellGroup);
    }

    @Override
    protected RecyclerListView.SelectionAdapter getListAdapter() {
        return listAdapter;
    }

    @Override
    protected CellGroup getCellGroup() {
        return cellGroup;
    }

    @Override
    protected String getSettingsPrefix() {
        return "aiChat";
    }

    @Override
    public String getTitle() {
        return getString(R.string.AIChat);
    }

    @SuppressLint("NotifyDataSetChanged")
    @Override
    public View createView(Context context) {
        View superView = super.createView(context);

        listAdapter = new ListAdapter(context);
        listView.setAdapter(listAdapter);
        setupDefaultListeners();

        cellGroup.callBackSettingsChanged = (key, newValue) -> AiConfig.syncFields();

        return superView;
    }

    private static class TemperatureSeekBar extends FrameLayout {

        private final SeekBarView sizeBar;
        private final TextPaint textPaint;

        public TemperatureSeekBar(Context context) {
            super(context);

            setWillNotDraw(false);

            textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            textPaint.setTextSize(AndroidUtilities.dp(16));

            sizeBar = new SeekBarView(context);
            sizeBar.setReportChanges(true);
            sizeBar.setSeparatorsCount(21);
            sizeBar.setDelegate((stop, progress) -> {
                AiConfig.temperatureConfig.setConfigInt(Math.round(progress * 20));
                AiConfig.syncFields();
                invalidate();
            });
            sizeBar.setProgress(AiConfig.temperatureConfig.Int() / 20f);
            addView(sizeBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 38, Gravity.LEFT | Gravity.TOP, 9, 5, 43, 11));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            textPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteValueText));
            @SuppressLint("DefaultLocale") String text = String.format("%.1f", AiConfig.temperatureConfig.Int() / 10f);
            canvas.drawText(text, getMeasuredWidth() - AndroidUtilities.dp(39), AndroidUtilities.dp(28), textPaint);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            sizeBar.setProgress(AiConfig.temperatureConfig.Int() / 20f);
        }

        @Override
        public void invalidate() {
            super.invalidate();
            sizeBar.invalidate();
        }
    }

    @Override
    protected void handleCellClick(View view, int position, float x, float y) {
        if (position >= 0 && position < cellGroup.rows.size() && cellGroup.rows.get(position) == servicesRow && AiConfig.useLlmProvider) {
            presentFragment(new NekoTranslatorSettingsActivity());
            return;
        }
        if (position >= 0 && position < cellGroup.rows.size() && cellGroup.rows.get(position) == useLlmProviderRow) {
            AiConfig.useLlmProviderConfig.toggleConfigBool();
            AiConfig.syncFields();
            AiConfig.clearConversationHistory();
            servicesRow.setValue(getEndpointValue());
            if (view instanceof TextCell textCell) {
                textCell.setChecked(AiConfig.useLlmProviderConfig.Bool());
            }
            return;
        }
        if (position >= 0 && position < cellGroup.rows.size() && cellGroup.rows.get(position) == saveHistoryRow) {
            AiConfig.saveHistoryConfig.toggleConfigBool();
            AiConfig.syncFields();
            if (view instanceof TextCell textCell) {
                textCell.setChecked(AiConfig.saveHistoryConfig.Bool());
            }
            return;
        }
        super.handleCellClick(view, position, x, y);
    }

    @Override
    public void onResume() {
        super.onResume();
        servicesRow.setValue(getEndpointValue());
        rolesRow.setValue(AiConfig.getSelectedRole());
    }

    private String getEndpointValue() {
        try {
            String host = new URL(AiController.getInstance().getSelected().getUrl()).getHost();
            if (!TextUtils.isEmpty(host) && AiController.canUseAI()) {
                return host.contains("generativelanguage.googleapis") ? "Gemini" : host;
            }
            return getString(R.string.AIChatNotConfigured);
        } catch (MalformedURLException e) {
            return getString(R.string.AIChatNotConfigured);
        }
    }

    private class ListAdapter extends BaseListAdapter {

        public ListAdapter(Context context) {
            super(context);
        }

        @Override
        protected View onCreateCustomViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
            if (viewType == CellGroup.ITEM_TYPE_TEXT_CHECK_ICON) {
                return new TextCell(mContext, 23, false, true, getResourceProvider());
            }
            if (viewType == ConfigCellCustom.CUSTOM_ITEM_AiChatTemperature) {
                return new TemperatureSeekBar(mContext);
            }
            return null;
        }
    }
}
