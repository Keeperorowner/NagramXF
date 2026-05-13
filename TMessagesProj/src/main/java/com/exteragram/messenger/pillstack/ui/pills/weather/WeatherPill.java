package com.exteragram.messenger.pillstack.ui.pills.weather;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.location.Location;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.exteragram.messenger.pillstack.core.PillStackConfig;
import com.exteragram.messenger.pillstack.ui.pills.BasePill;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.Stories.recorder.Weather;

public class WeatherPill extends BasePill implements NotificationCenter.NotificationCenterDelegate {

    private final ImageView iconView;
    private final LinearLayout layout;
    private final AnimatedTextView textView;
    private boolean showingWeather;

    @Override
    public long getRefreshInterval() {
        return 1_200_000L;
    }

    public WeatherPill(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context, resourcesProvider);
        layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER);
        layout.setMinimumWidth(AndroidUtilities.dp(48f));
        layout.setPadding(AndroidUtilities.dp(8f), 0, AndroidUtilities.dp(8f), 0);
        addView(layout, LayoutHelper.createFrame(-2, 28, (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL));

        iconView = new ImageView(context);
        iconView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        layout.addView(iconView, LayoutHelper.createLinear(16, 16, Gravity.CENTER_VERTICAL, 0, 0, 4, 0));

        textView = new AnimatedTextView(context, true, true, true);
        textView.setTextSize(AndroidUtilities.dp(13f));
        textView.setTypeface(AndroidUtilities.bold());
        textView.setIncludeFontPadding(false);
        textView.adaptWidth = true;
        NotificationCenter.listenEmojiLoading(textView);
        layout.addView(textView, LayoutHelper.createLinear(-2, -2, Gravity.CENTER_VERTICAL));

        setLoadingTargetView(layout);
        updateColors();
        ScaleStateListAnimator.apply(layout);

        Weather.State cached = Weather.getCached();
        if (cached != null) {
            setData(cached, false);
        }
    }

    @Override
    public int getPillId() {
        return PillStackConfig.PillType.WEATHER.id;
    }

    @Override
    public void onPillClicked() {
        if (PillStackConfig.useCurrentLocation
                && !showingWeather
                && (!Weather.isLocationPermissionGranted() || !Weather.isLocationEnabled())) {
            requestLocationAndUpdate();
        } else {
            onPillLongClicked();
        }
    }

    @Override
    public boolean onPillLongClicked() {
        final BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (fragment == null) return false;
        ItemOptions.makeOptions(fragment, this)
                .add(R.drawable.msg_retry, LocaleController.getString(R.string.Refresh), () -> onUpdateData(true))
                .add(R.drawable.msg_settings, LocaleController.getString(R.string.Settings),
                        () -> fragment.presentFragment(new WeatherSettingsActivity()))
                .setDrawScrim(false)
                .setDimAlpha(0)
                .show();
        return true;
    }

    @Override
    public void onUpdateData(boolean force) {
        if (PillStackConfig.useCurrentLocation) {
            if (!Weather.isLocationPermissionGranted()) {
                setLocationState(R.string.WeatherLocationPermissionGrant, showingWeather);
                return;
            }
            if (!Weather.isLocationEnabled()) {
                setLocationState(R.string.WeatherLocationServicesEnable, showingWeather);
                return;
            }
        }
        if (force) {
            Weather.clearCache();
        }
        startLoading();
        Weather.fetchExtera(state -> {
            if (state != null) {
                markDataUpdated();
                postDelayed(() -> setData(state, true), 300L);
            } else {
                postDelayed(() -> setErrorState(true), 300L);
            }
        });
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (PillStackConfig.checkAndClearPendingUpdate(getPillId())
                || Weather.getCached() == null
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
                && PillStackConfig.shouldUpdatePill(args, getPillId())) {
            PillStackConfig.checkAndClearPendingUpdate(getPillId());
            onUpdateData(true);
        }
    }

    private void setLocationState(int stringRes, boolean animate) {
        stopLoading();
        if (animate) animateSizeChange();
        iconView.setImageResource(R.drawable.filled_location);
        iconView.setVisibility(VISIBLE);
        textView.setText(LocaleController.getString(stringRes), animate);
        showingWeather = false;
    }

    private void requestLocationAndUpdate() {
        Weather.getUserLocation(true, (Location loc) -> {
            if (loc != null) {
                onUpdateData(true);
            }
        });
    }

    private void setErrorState(boolean animate) {
        stopLoading();
        if (animate) animateSizeChange();
        iconView.setImageResource(R.drawable.msg_retry);
        iconView.setVisibility(VISIBLE);
        textView.setText(LocaleController.getString(R.string.Retry), animate);
        showingWeather = false;
    }

    public void setData(Weather.State state, boolean animate) {
        stopLoading();
        if (state == null) return;
        if (animate) animateSizeChange();
        int weatherIconRes = getWeatherIconRes(state.getEmoji());
        if (weatherIconRes != 0) {
            iconView.setImageResource(weatherIconRes);
            iconView.setVisibility(VISIBLE);
            textView.setText(state.getTemperature(), animate);
        } else {
            iconView.setVisibility(GONE);
            textView.setText(Emoji.replaceEmoji(String.format("%s %s", state.getEmoji(), state.getTemperature()),
                    textView.getPaint().getFontMetricsInt(), true), animate);
        }
        showingWeather = true;
    }

    private int getWeatherIconRes(String emoji) {
        if (emoji == null || emoji.isEmpty()) return 0;
        switch (emoji) {
            case "\u2600":
                return R.drawable.weather_sunny;
            case "\u2601":
                return R.drawable.weather_cloudy;
            case "\u26c5":
            case "\ud83c\udf24":
                return R.drawable.weather_partly_cloudy;
            case "\ud83c\udf26":
            case "\ud83c\udf27":
                return R.drawable.weather_rainy;
            case "\u26a1":
            case "\u26c8":
                return R.drawable.weather_thunderstorm;
            case "\u2744":
            case "\ud83c\udf28":
                return R.drawable.weather_snowy;
            case "\ud83d\ude36\u200d\ud83c\udf2b":
                return R.drawable.weather_foggy;
            case "\ud83c\udf13":
            case "\ud83c\udf14":
            case "\ud83c\udf16":
            case "\ud83c\udf17":
            case "\ud83c\udf1a":
            case "\ud83c\udf1b":
            case "\ud83c\udf1c":
            case "\ud83c\udf1d":
                return R.drawable.weather_night;
            default:
                return 0;
        }
    }

    @Override
    public void setPressed(boolean pressed) {
        if (loading) pressed = false;
        super.setPressed(pressed);
        layout.setPressed(pressed);
    }

    @Override
    public void updateColors() {
        int color = getThemedColor(Theme.key_windowBackgroundWhiteBlackText, 0.75f);
        layout.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(14f),
                Theme.isCurrentThemeDark() ? getThemedColor(Theme.key_windowBackgroundWhite) : Theme.multAlpha(color, 0.09f),
                Theme.multAlpha(color, 0.1f)));
        textView.setTextColor(color);
            iconView.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.MULTIPLY));
        updateLoadingColors();
    }
}
