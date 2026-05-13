package com.exteragram.messenger.pillstack.ui.pills.crypto;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.exteragram.messenger.pillstack.core.PillStackConfig;
import com.exteragram.messenger.pillstack.ui.PillStackPreferencesActivity;
import com.exteragram.messenger.pillstack.ui.pills.BasePill;
import com.exteragram.messenger.pillstack.ui.pills.crypto.utils.ColoredBackground;
import com.exteragram.messenger.pillstack.ui.pills.crypto.utils.ExchangeRates;
import com.exteragram.messenger.pillstack.ui.pills.crypto.utils.PillStackCurrencies;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LoadingDrawable;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.LaunchActivity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.atomic.AtomicReference;

public abstract class RatePill extends BasePill implements NotificationCenter.NotificationCenterDelegate {

    public static final class RateCache {
        public final AtomicReference<String> cachedPrice = new AtomicReference<>();
        public final AtomicReference<String> cachedCurrency = new AtomicReference<>();
    }

    private final ColoredBackground background;
    private final String baseCurrency;
    private final RateCache cache;
    private final int iconResId;
    private final int scale;

    private final ImageView iconView;
    private final LinearLayout layout;
    private final AnimatedTextView textView;
    private boolean requestInFlight;

    protected abstract String getTargetSelection();

    protected abstract void setTargetSelection(String value);

    protected String[] getTargetCurrencies() {
        return PillStackCurrencies.TARGET_CURRENCIES;
    }

    @Override
    public long getRefreshInterval() {
        return 300_000L;
    }

    protected RatePill(Context context, Theme.ResourcesProvider resourcesProvider,
                       RateCache cache, String baseCurrency, int scale, int iconResId,
                       ColoredBackground background) {
        super(context, resourcesProvider);
        this.cache = cache;
        this.baseCurrency = baseCurrency;
        this.scale = scale;
        this.iconResId = iconResId;
        this.background = background;

        layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(android.view.Gravity.CENTER);
        layout.setMinimumWidth(AndroidUtilities.dp(48f));
        layout.setPadding(AndroidUtilities.dp(8f), 0, AndroidUtilities.dp(8f), 0);
        addView(layout, LayoutHelper.createFrame(-2, 28,
                (LocaleController.isRTL ? android.view.Gravity.LEFT : android.view.Gravity.RIGHT) | android.view.Gravity.CENTER_VERTICAL));

        iconView = new ImageView(context);
        iconView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        layout.addView(iconView, LayoutHelper.createLinear(16, 16, android.view.Gravity.CENTER_VERTICAL, 0, 0, 4, 0));

        textView = new AnimatedTextView(context, true, true, true);
        textView.setTextSize(AndroidUtilities.dp(13f));
        textView.setIncludeFontPadding(false);
        textView.setTypeface(AndroidUtilities.bold());
        textView.adaptWidth = true;
        layout.addView(textView, LayoutHelper.createLinear(-2, -2, android.view.Gravity.CENTER_VERTICAL));

        setLoadingTargetView(layout);
        updateColors();
        ScaleStateListAnimator.apply(layout);

        String cachedPrice = cache.cachedPrice.get();
        if (cachedPrice != null) {
            setData(cachedPrice, false);
        }
    }

    @Override
    public void onPillClicked() {
        if (iconView.getVisibility() == VISIBLE && textView.getText() != null
                && TextUtils.equals(textView.getText(), LocaleController.getString(R.string.Retry))) {
            onUpdateData(true);
        } else {
            onPillLongClicked();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (PillStackConfig.checkAndClearPendingUpdate(getPillId())
                || cache.cachedPrice.get() == null
                || isRefreshDue()) {
            onUpdateData(true);
        }
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.pillStackSettingsChanged);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.pillStackSettingsChanged);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.pillStackSettingsChanged
                && PillStackConfig.shouldUpdatePill(args, getPillId())
                && "AUTO".equalsIgnoreCase(getTargetSelection())) {
            PillStackConfig.checkAndClearPendingUpdate(getPillId());
            onUpdateData(true);
        }
    }

    @Override
    public boolean onPillLongClicked() {
        final BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (fragment == null) return false;

        final ItemOptions options = ItemOptions.makeOptions(fragment, this, true);
        final ItemOptions swipeback = options.makeSwipeback(true)
                .add(R.drawable.ic_ab_back, LocaleController.getString(R.string.Back), options::closeSwipeback)
                .addGap();

        final String current = getTargetSelection();
        for (final String code : getTargetCurrencies()) {
            swipeback.addChecked(code.equalsIgnoreCase(current),
                    PillStackCurrencies.getTargetCurrencyLabel(code),
                    () -> {
                        options.dismiss();
                        if (!code.equalsIgnoreCase(current)) {
                            setTargetSelection(code);
                            onUpdateData(false);
                        }
                    });
        }

        ActionBarMenuSubItem header = new ActionBarMenuSubItem(getContext(), false, false, resourcesProvider);
        header.setTextAndIcon(LocaleController.getString(R.string.CryptoPillTargetCurrency), R.drawable.msg_language);
        header.setSubtext(PillStackCurrencies.getTargetCurrencySubtext(getTargetSelection()));
        header.setItemHeight(56);
        header.setOnClickListener(v -> options.openSwipeback(swipeback));
        options.add(header);

        options.addGap()
                .add(R.drawable.msg_retry, LocaleController.getString(R.string.Refresh), () -> onUpdateData(true))
                .add(R.drawable.msg_settings, LocaleController.getString(R.string.Settings),
                        () -> {
                            options.dismiss();
                            fragment.presentFragment(new PillStackPreferencesActivity());
                        })
                .setSwipebackGravity(!LocaleController.isRTL, false)
                .setMaxHeight((int) (AndroidUtilities.displaySize.y * 0.45f))
                .setDrawScrim(false)
                .setGravity(LocaleController.isRTL ? android.view.Gravity.LEFT : android.view.Gravity.RIGHT)
                .setDimAlpha(0)
                .show();
        return true;
    }

    @Override
    public void onUpdateData(boolean force) {
        final String target = ExchangeRates.resolveTargetCurrency(UserConfig.selectedAccount, getTargetSelection());
        String cachedPrice = cache.cachedPrice.get();
        if (!TextUtils.equals(target, cache.cachedCurrency.get())) {
            cachedPrice = null;
        }
        if (!force && cachedPrice != null && !isRefreshDue()) {
            setData(cachedPrice, false);
            return;
        }
        if (requestInFlight) {
            return;
        }
        requestInFlight = true;
        if (force) {
            animateSizeChange();
        }
        startLoading();
        if (cachedPrice == null && cache.cachedPrice.get() == null) {
            iconView.setVisibility(GONE);
            textView.setVisibility(GONE);
        } else {
            iconView.setImageResource(iconResId);
            iconView.setVisibility(VISIBLE);
            textView.setVisibility(VISIBLE);
        }
        if (force) {
            ExchangeRates.clearCache();
        }
        ExchangeRates.fetch(state -> {
            requestInFlight = false;
            if (state == null) {
                String prev = cache.cachedPrice.get();
                if (prev != null) {
                    setData(prev, true);
                } else {
                    setErrorState(true);
                }
                return;
            }
            BigDecimal rate = state.getRate(baseCurrency, target);
            if (rate == null) {
                String prev = cache.cachedPrice.get();
                if (prev != null) {
                    setData(prev, true);
                } else {
                    setErrorState(true);
                }
                return;
            }
            String formatted = formatPrice(rate, target);
            cache.cachedPrice.set(formatted);
            cache.cachedCurrency.set(target);
            setData(formatted, true);
            markDataUpdated();
        });
    }

    protected String formatPrice(BigDecimal amount, String currency) {
        String fiat = PillStackCurrencies.formatFiatPrice(amount, currency);
        if (fiat != null) return fiat;
        return amount.setScale(scale, RoundingMode.HALF_UP).toPlainString() + " " + currency;
    }

    private void setErrorState(boolean animate) {
        stopLoading();
        if (animate) animateSizeChange();
        iconView.setImageResource(R.drawable.msg_retry);
        iconView.setVisibility(VISIBLE);
        textView.setText(LocaleController.getString(R.string.Retry), animate);
        textView.setVisibility(VISIBLE);
        textView.requestLayout();
    }

    private void setData(String text, boolean animate) {
        stopLoading();
        if (animate) animateSizeChange();
        iconView.setImageResource(iconResId);
        iconView.setVisibility(VISIBLE);
        textView.setText(text, animate);
        textView.setVisibility(VISIBLE);
        textView.requestLayout();
    }

    @Override
    public void setPressed(boolean pressed) {
        if (loading) {
            pressed = false;
        }
        super.setPressed(pressed);
        layout.setPressed(pressed);
    }

    @Override
    public void updateColors() {
        layout.setBackground(background);
        textView.setTextColor(-1);
        iconView.setColorFilter(-1);
        updateLoadingColors();
    }

    @Override
    protected void updateLoadingColors() {
        LoadingDrawable drawable = this.loadingDrawable;
        if (drawable != null) {
            drawable.setColors(Theme.multAlpha(-1, 0.1f), Theme.multAlpha(-1, 0.3f));
        }
    }
}
