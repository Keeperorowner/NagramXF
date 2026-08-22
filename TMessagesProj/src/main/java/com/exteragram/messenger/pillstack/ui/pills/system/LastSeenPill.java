package com.exteragram.messenger.pillstack.ui.pills.system;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.exteragram.messenger.pillstack.core.PillStackConfig;
import com.exteragram.messenger.pillstack.ui.PillStackPreferencesActivity;
import com.exteragram.messenger.pillstack.ui.pills.BasePill;
import com.radolyn.ayugram.AyuConstants;
import com.radolyn.ayugram.AyuWorker;
import com.radolyn.ayugram.controllers.AyuGhostController;
import com.radolyn.ayugram.utils.AyuGhostUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.LaunchActivity;

import java.util.Calendar;
import java.util.Date;

import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.settings.GhostModeActivity;

public class LastSeenPill extends BasePill implements NotificationCenter.NotificationCenterDelegate {

    private static final String[] cachedStatusText = new String[16];
    private static final long[] cachedStatusUpdatedAt = new long[16];

    private final ImageView iconView;
    private final LinearLayout layout;
    private final View textSpacer;
    private final AnimatedTextView textView;

    private boolean requestInFlight;
    private boolean waitingForWorkerFetch;
    private boolean statusExpanded;

    private final Runnable hideStatusRunnable = () -> hideStatusText(true);
    private final Runnable finishHideRunnable = new Runnable() {
        @Override
        public void run() {
            if (textView.isAnimating()) {
                postDelayed(this, 16L);
                return;
            }
            if (!statusExpanded && TextUtils.isEmpty(textView.getText())) {
                textView.setVisibility(GONE);
            }
        }
    };

    public LastSeenPill(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context, resourcesProvider);

        layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER);
        layout.setMinimumWidth(AndroidUtilities.dp(48f));
        layout.setPadding(AndroidUtilities.dp(8f), 0, AndroidUtilities.dp(8f), 0);
        addView(layout, LayoutHelper.createFrame(-2, 28,
                (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL));

        iconView = new ImageView(context);
        iconView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        iconView.setImageResource(R.drawable.menu_premium_seen);
        layout.addView(iconView, LayoutHelper.createLinear(20, 20, Gravity.CENTER_VERTICAL, 0, 0, 0, 0));

        textSpacer = new View(context);
        layout.addView(textSpacer, LayoutHelper.createLinear(0, 0, Gravity.CENTER_VERTICAL));

        textView = new AnimatedTextView(context, true, true, true);
        textView.setTextSize(AndroidUtilities.dp(13f));
        textView.setTypeface(AndroidUtilities.bold());
        textView.setIncludeFontPadding(false);
        textView.adaptWidth = true;
        textView.setText("", false);
        textView.setVisibility(GONE);
        layout.addView(textView, LayoutHelper.createLinear(-2, -2, Gravity.CENTER_VERTICAL));

        setLoadingTargetView(layout);
        updateColors();
        ScaleStateListAnimator.apply(layout);
    }

    @Override
    public int getPillId() {
        return PillStackConfig.PillType.LAST_SEEN.id;
    }

    @Override
    public long getRefreshInterval() {
        return isPeriodicOnlineEnabled() ? 60_000L : 0L;
    }

    @Override
    public void onPillClicked() {
        if (loading || requestInFlight) {
            return;
        }
        if (isPeriodicOnlineEnabled()) {
            requestStatus(true);
        } else if (!statusExpanded && !textView.isAnimating()) {
            requestStatus(false);
        }
    }

    @Override
    public void onUpdateData(boolean force) {
        if (!isPeriodicOnlineEnabled()) {
            hideStatusText(force);
            markDataUpdated();
            return;
        }
        if (TextUtils.isEmpty(textView.getText())) {
            showCachedStatus();
        }
        requestStatus(true);
    }

    private int getAccount() {
        return UserConfig.selectedAccount;
    }

    private boolean isPeriodicOnlineEnabled() {
        return PillStackConfig.isLastSeenPeriodicOnlineEnabled(getAccount());
    }

    private void requestStatus(boolean persistent) {
        if (requestInFlight || waitingForWorkerFetch) {
            return;
        }
        int account = getAccount();
        long selfId = UserConfig.getInstance(account).getClientUserId();
        if (selfId == 0) {
            return;
        }
        requestInFlight = false;
        waitingForWorkerFetch = true;
        if (!loading) {
            startLoading();
        }
        AyuWorker.requestLastSeenUpdate(account);
    }

    private void onWorkerFetchReady() {
        waitingForWorkerFetch = false;
        int account = getAccount();
        long selfId = UserConfig.getInstance(account).getClientUserId();
        if (selfId == 0) {
            finishRequest(null, false);
            return;
        }
        requestInFlight = true;
        fetchSelfUser(account, selfId, true);
    }

    private void fetchSelfUser(int account, long selfId, boolean persistent) {
        MessagesController messagesController = MessagesController.getInstance(account);
        ConnectionsManager connectionsManager = ConnectionsManager.getInstance(account);

        TLRPC.TL_users_getUsers req = new TLRPC.TL_users_getUsers();
        TLRPC.InputUser inputUser = messagesController.getInputUser(selfId);
        if (inputUser == null) {
            finishRequest(null, persistent);
            return;
        }
        req.id.add(inputUser);
        connectionsManager.sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            TLRPC.User resolved = null;
            if (error == null && response instanceof Vector) {
                for (Object o : ((Vector) response).objects) {
                    if (o instanceof TLRPC.User) {
                        resolved = (TLRPC.User) o;
                        break;
                    }
                }
            }
            finishRequest(resolved, persistent);
        }));
    }

    private void finishRequest(TLRPC.User user, boolean persistent) {
        requestInFlight = false;
        waitingForWorkerFetch = false;
        stopLoading();
        if (!isAttachedToWindow()) {
            return;
        }
        boolean keepVisible = persistent || isPeriodicOnlineEnabled();
        String label = user != null ? formatStatus(user) : getCachedStatusText(getAccount());
        if (user != null && label != null) {
            cacheStatusText(getAccount(), label);
            correctResidualOnline(getAccount(), user);
        }
        if (TextUtils.isEmpty(label)) {
            return;
        }
        showStatusText(label, keepVisible, true);
        if (keepVisible) {
            markDataUpdated();
        }
    }

    // 服务器仍把本账号标为在线时立即补发 offline 包，避免 ghost 模式残留在线状态
    private void correctResidualOnline(int account, TLRPC.User user) {
        int currentTime = ConnectionsManager.getInstance(account).getCurrentTime();
        if (!(user.status instanceof TLRPC.TL_userStatusOnline) || user.status.expires <= currentTime) {
            return;
        }
        AyuGhostController ghost = AyuGhostController.getInstance(account);
        if (ghost.isGhostModeActive() || !ghost.isSendOnlinePackets() || ghost.isSendOfflinePacketAfterOnline()) {
            AyuGhostUtils.performStatusRequest(account, true);
        }
    }

    private String formatStatus(TLRPC.User user) {
        int currentTime = ConnectionsManager.getInstance(getAccount()).getCurrentTime();
        TLRPC.UserStatus status = user.status;
        if (status instanceof TLRPC.TL_userStatusOnline && status.expires > currentTime) {
            return LocaleController.getString(R.string.Online);
        }
        int expires = (status instanceof TLRPC.TL_userStatusOffline || status instanceof TLRPC.TL_userStatusOnline)
                ? status.expires : 0;
        if (expires <= 0) {
            return LocaleController.getString(R.string.ALongTimeAgo);
        }
        long seconds = expires;
        long millis = seconds * 1000L;
        Calendar now = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(millis);
        if (now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
                && now.get(Calendar.YEAR) == then.get(Calendar.YEAR)) {
            return LocaleController.getInstance().getFormatterDay().format(new Date(millis));
        }
        return LocaleController.formatDateTime(seconds, true);
    }

    private void showStatusText(CharSequence text, boolean keepVisible, boolean animate) {
        removeCallbacks(hideStatusRunnable);
        removeCallbacks(finishHideRunnable);
        boolean shouldAnimateSize = animate && !(statusExpanded
                && textView.getVisibility() == VISIBLE
                && TextUtils.equals(textView.getText(), text));
        statusExpanded = true;
        if (shouldAnimateSize) {
            animateSizeChange();
        }
        setTextSpacing(AndroidUtilities.dp(4f));
        textView.setVisibility(VISIBLE);
        textView.setText(text, animate);
        if (!keepVisible) {
            postDelayed(hideStatusRunnable, 3000L);
        }
    }

    private void hideStatusText(boolean animate) {
        removeCallbacks(hideStatusRunnable);
        removeCallbacks(finishHideRunnable);
        if (!statusExpanded && textView.getVisibility() == GONE && TextUtils.isEmpty(textView.getText())) {
            return;
        }
        statusExpanded = false;
        if (animate) {
            animateSizeChange();
        }
        setTextSpacing(0);
        textView.setVisibility(VISIBLE);
        textView.setText("", animate);
        if (animate) {
            post(finishHideRunnable);
        } else {
            textView.setVisibility(GONE);
        }
    }

    private void setTextSpacing(int width) {
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) textSpacer.getLayoutParams();
        if (params.width == width) return;
        params.width = width;
        textSpacer.setLayoutParams(params);
    }

    private boolean showCachedStatus() {
        String cached = getCachedStatusText(getAccount());
        if (TextUtils.isEmpty(cached)) return false;
        showStatusText(cached, true, false);
        return true;
    }

    private static void cacheStatusText(int account, String status) {
        if (account < 0 || account >= cachedStatusText.length || TextUtils.isEmpty(status)) {
            return;
        }
        cachedStatusText[account] = status;
        cachedStatusUpdatedAt[account] = SystemClock.elapsedRealtime();
    }

    private static String getCachedStatusText(int account) {
        if (account < 0 || account >= cachedStatusText.length) return null;
        return cachedStatusText[account];
    }

    @Override
    public boolean onPillLongClicked() {
        final BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (fragment == null) return false;
        final int account = getAccount();
        final boolean periodicEnabled = PillStackConfig.isLastSeenPeriodicOnlineEnabled(account);
        final boolean ghostActive = NekoConfig.isGhostModeActive();

        ActionBarMenuSubItem ghostToggle = new ActionBarMenuSubItem(getContext(), false, false, resourcesProvider);
        ghostToggle.setTextAndIcon(LocaleController.getString(R.string.GhostMode), R.drawable.ayu_ghost);
        ghostToggle.setSubtext(LocaleController.getString(ghostActive ? R.string.PasswordOn : R.string.PasswordOff));

        ActionBarMenuSubItem autoRefreshToggle = new ActionBarMenuSubItem(getContext(), false, false, resourcesProvider);
        autoRefreshToggle.setTextAndIcon(LocaleController.getString(R.string.LastSeenPillAutoRefresh), R.drawable.menu_clear_recent);
        autoRefreshToggle.setSubtext(LocaleController.getString(periodicEnabled ? R.string.PasswordOn : R.string.PasswordOff));

        final ItemOptions options = ItemOptions.makeOptions(fragment, this);
        options.add(ghostToggle);
        options.add(autoRefreshToggle);
        options.addGap()
                .add(R.drawable.msg_retry, LocaleController.getString(R.string.Refresh), this::onPillClicked)
                .add(R.drawable.ayu_ghost, LocaleController.getString(R.string.GhostMode),
                        () -> fragment.presentFragment(new GhostModeActivity()))
                .add(R.drawable.msg_settings, LocaleController.getString(R.string.Settings),
                        () -> fragment.presentFragment(new PillStackPreferencesActivity()))
                .setDrawScrim(false)
                .setDimAlpha(0)
                .show();

        ghostToggle.setOnClickListener(v -> {
            NekoConfig.toggleGhostMode();
            options.dismiss();
        });
        autoRefreshToggle.setOnClickListener(v -> {
            PillStackConfig.setLastSeenPeriodicOnlineEnabled(account, !periodicEnabled);
            options.dismiss();
        });
        return true;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.pillStackSettingsChanged);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.reloadInterface);
        NotificationCenter.getGlobalInstance().addObserver(this, AyuConstants.LAST_SEEN_PILL_FETCH);
        boolean pending = PillStackConfig.checkAndClearPendingUpdate(getPillId());
        onUpdateData(pending);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.pillStackSettingsChanged);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.reloadInterface);
        NotificationCenter.getGlobalInstance().removeObserver(this, AyuConstants.LAST_SEEN_PILL_FETCH);
        removeCallbacks(hideStatusRunnable);
        removeCallbacks(finishHideRunnable);
        statusExpanded = false;
        requestInFlight = false;
        waitingForWorkerFetch = false;
        setTextSpacing(0);
        stopLoading();
        textView.cancelAnimation();
        textView.setText("", false);
        textView.setVisibility(GONE);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.pillStackSettingsChanged
                && PillStackConfig.shouldUpdatePill(args, getPillId())) {
            PillStackConfig.checkAndClearPendingUpdate(getPillId());
            onUpdateData(true);
        } else if (id == AyuConstants.LAST_SEEN_PILL_FETCH) {
            if (waitingForWorkerFetch) {
                onWorkerFetchReady();
            }
        } else if (id == NotificationCenter.reloadInterface) {
            updateColors();
            if (isPeriodicOnlineEnabled() || statusExpanded) {
                onUpdateData(true);
            }
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
        int color;
        if (NekoConfig.isGhostModeActive()) {
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
