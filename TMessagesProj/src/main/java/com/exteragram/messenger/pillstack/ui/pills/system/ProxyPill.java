package com.exteragram.messenger.pillstack.ui.pills.system;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.exteragram.messenger.pillstack.core.PillStackConfig;
import com.exteragram.messenger.pillstack.ui.PillStackPreferencesActivity;
import com.exteragram.messenger.pillstack.ui.pills.BasePill;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.ProxyListActivity;

public class ProxyPill extends BasePill implements NotificationCenter.NotificationCenterDelegate {

    private final ImageView iconView;
    private final LinearLayout layout;
    private final AnimatedTextView textView;

    @Override
    public long getRefreshInterval() {
        return 0L;
    }

    public ProxyPill(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context, resourcesProvider);
        layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER);
        layout.setMinimumWidth(AndroidUtilities.dp(48f));
        layout.setPadding(AndroidUtilities.dp(8f), 0, AndroidUtilities.dp(10f), 0);
        addView(layout, LayoutHelper.createFrame(-2, 28, (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL));

        iconView = new ImageView(context);
        iconView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        layout.addView(iconView, LayoutHelper.createLinear(16, 16, Gravity.CENTER_VERTICAL, 0, 0, 2, 0));

        textView = new AnimatedTextView(context, true, true, true);
        textView.setTextSize(AndroidUtilities.dp(13f));
        textView.setIncludeFontPadding(false);
        textView.setTypeface(AndroidUtilities.bold());
        textView.adaptWidth = true;
        layout.addView(textView, LayoutHelper.createLinear(-2, -2, Gravity.CENTER_VERTICAL));

        setLoadingTargetView(layout);
        updateColors();
        ScaleStateListAnimator.apply(layout);
        onUpdateData(false);
    }

    @Override
    public int getPillId() {
        return PillStackConfig.PillType.PROXY.id;
    }

    @Override
    public void onUpdateData(boolean animate) {
        boolean proxyEnabled = SharedConfig.isProxyEnabled();
        int state = ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState();
        boolean connected = state == ConnectionsManager.ConnectionStateConnected || state == ConnectionsManager.ConnectionStateUpdating;
        String previous = textView.getText() != null ? textView.getText().toString() : "";
        String label;
        SharedConfig.ProxyInfo proxy = SharedConfig.currentProxy;
        if (!proxyEnabled || proxy == null) {
            iconView.setImageResource(R.drawable.msg_proxy_off);
            label = LocaleController.getString(R.string.Proxy);
            stopLoading();
        } else if (connected) {
            long ping = Utilities.clamp(proxy.ping, 9999L, 0L);
            iconView.setImageResource(R.drawable.msg_proxy);
            if (ping > 0) {
                label = LocaleController.formatString(R.string.NavigationDrawerProxyPingShort, ping);
            } else {
                label = LocaleController.getString(R.string.MenuProxyConnected);
            }
            stopLoading();
        } else {
            iconView.setImageResource(R.drawable.msg_proxy_off);
            label = LocaleController.getString(R.string.MenuProxyConnecting);
            startLoading();
        }
        if (animate || !TextUtils.equals(previous, label)) {
            if (animate) {
                animateSizeChange();
            }
            textView.setText(label, animate);
        }
        updateColors();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        onUpdateData(true);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxySettingsChanged);
        NotificationCenter.getInstance(UserConfig.selectedAccount).addObserver(this, NotificationCenter.didUpdateConnectionState);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.proxySettingsChanged);
        NotificationCenter.getInstance(UserConfig.selectedAccount).removeObserver(this, NotificationCenter.didUpdateConnectionState);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.proxySettingsChanged || id == NotificationCenter.didUpdateConnectionState) {
            onUpdateData(true);
        }
    }

    @Override
    public void onPillClicked() {
        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (fragment != null) {
            fragment.presentFragment(new ProxyListActivity());
        }
    }

    @Override
    public boolean onPillLongClicked() {
        final BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (fragment == null) return false;
        ItemOptions.makeOptions(fragment, this)
                .add(R.drawable.msg_settings, LocaleController.getString(R.string.Settings),
                        () -> fragment.presentFragment(new PillStackPreferencesActivity()))
                .setDrawScrim(false)
                .setDimAlpha(0)
                .show();
        return true;
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
        boolean proxyEnabled = SharedConfig.isProxyEnabled();
        int state = ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState();
        boolean connected = state == ConnectionsManager.ConnectionStateConnected || state == ConnectionsManager.ConnectionStateUpdating;
        int color;
        if (proxyEnabled && SharedConfig.currentProxy != null && connected) {
            color = getThemedColor(Theme.key_windowBackgroundWhiteGreenText);
        } else {
            color = getThemedColor(Theme.key_windowBackgroundWhiteBlackText, 0.75f);
        }
        layout.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(14f),
                Theme.isCurrentThemeDark() ? getThemedColor(Theme.key_windowBackgroundWhite) : Theme.multAlpha(color, 0.09f),
                Theme.multAlpha(color, 0.1f)));
        textView.setTextColor(color);
        iconView.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.MULTIPLY));
        updateLoadingColors();
    }
}
