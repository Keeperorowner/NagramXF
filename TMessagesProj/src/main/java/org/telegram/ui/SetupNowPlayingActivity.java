package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BotWebViewVibrationEffect;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.EditTextCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;

import xyz.nextalone.nagram.NaConfig;
import xyz.nextalone.nagram.nowplaying.LocalNowPlayingController;

public class SetupNowPlayingActivity extends BaseFragment {

    private static final int DONE_BUTTON = 1;
    private static final int RADIO_NONE = 1;
    private static final int RADIO_LAST_FM = 2;
    private static final int RADIO_STATS_FM = 3;

    private ActionBarMenuItem doneButton;
    private UniversalRecyclerView listView;
    private EditTextCell usernameEdit;

    private int initialServiceType;
    private String initialLastFmUsername;
    private String initialStatsFmUsername;

    private int serviceType;
    private String lastFmUsername;
    private String statsFmUsername;

    private boolean whitelisted = false;
    private boolean whitelistChecked = false;
    private boolean binding = false;

    private int shiftDp = -4;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.NowPlaying));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    if (onBackPressed(true)) {
                        finishFragment();
                    }
                } else if (id == DONE_BUTTON) {
                    processDone();
                }
            }
        });

        doneButton = actionBar.createMenu().addItemWithWidth(DONE_BUTTON, R.drawable.ic_ab_done, AndroidUtilities.dp(56), getString(R.string.Done));

        initialServiceType = serviceType = LocalNowPlayingController.getServiceType();
        initialLastFmUsername = lastFmUsername = LocalNowPlayingController.getLastFmUsername();
        initialStatsFmUsername = statsFmUsername = LocalNowPlayingController.getStatsFmUsername();

        usernameEdit = new EditTextCell(context, getCurrentUsernameHint(), false, false, -1, resourceProvider) {
            @Override
            protected void onTextChanged(CharSequence newText) {
                super.onTextChanged(newText);
                String text = newText == null ? "" : newText.toString().trim();
                if (serviceType == LocalNowPlayingController.SERVICE_STATS_FM) {
                    statsFmUsername = text;
                } else {
                    lastFmUsername = text;
                }
                checkDone(true);
            }
        };
        usernameEdit.hideKeyboardOnEnter();
        usernameEdit.setText(getCurrentUsername());

        FrameLayout contentView = new FrameLayout(context);
        contentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, null);
        listView.setSections();
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        actionBar.setAdaptiveBackground(listView);

        checkDone(false);
        checkWhitelistAndRefresh();

        return fragmentView = contentView;
    }

    private String getCurrentUsername() {
        return serviceType == LocalNowPlayingController.SERVICE_STATS_FM ? statsFmUsername : lastFmUsername;
    }

    private String getCurrentUsernameHint() {
        if (serviceType == LocalNowPlayingController.SERVICE_STATS_FM) {
            return getString(R.string.NowPlayingStatsFmUsername);
        }
        return getString(R.string.NowPlayingLastFmUsername);
    }

    private void refreshUsernameEdit() {
        usernameEdit.setText(getCurrentUsername());
        usernameEdit.editText.setHint(getCurrentUsernameHint());
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(getString(R.string.NowPlayingService)));
        items.add(UItem.asRadio(RADIO_NONE, getString(R.string.None)).setChecked(serviceType == LocalNowPlayingController.SERVICE_NONE));
        items.add(UItem.asRadio(RADIO_LAST_FM, "Last.fm").setChecked(serviceType == LocalNowPlayingController.SERVICE_LAST_FM));
        items.add(UItem.asRadio(RADIO_STATS_FM, "Stats.fm").setChecked(serviceType == LocalNowPlayingController.SERVICE_STATS_FM));

        if (serviceType == LocalNowPlayingController.SERVICE_LAST_FM || serviceType == LocalNowPlayingController.SERVICE_STATS_FM) {
            items.add(UItem.asHeader(getString(R.string.Username)));
            items.add(UItem.asCustom(usernameEdit));
            if (whitelistChecked && !whitelisted) {
                items.add(UItem.asShadow(getString(R.string.NowPlayingNotWhitelistedInfo)));
            }
        }
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == RADIO_NONE) {
            serviceType = LocalNowPlayingController.SERVICE_NONE;
            listView.adapter.update(true);
            checkDone(true);
        } else if (item.id == RADIO_LAST_FM) {
            serviceType = LocalNowPlayingController.SERVICE_LAST_FM;
            refreshUsernameEdit();
            listView.adapter.update(true);
            checkDone(true);
        } else if (item.id == RADIO_STATS_FM) {
            serviceType = LocalNowPlayingController.SERVICE_STATS_FM;
            refreshUsernameEdit();
            listView.adapter.update(true);
            checkDone(true);
        }
    }

    private void doBind() {
        if (binding) return;
        binding = true;
        long tgUid = UserConfig.getInstance(currentAccount).getClientUserId();
        LocalNowPlayingController.bind(tgUid, (success, message) -> {
            binding = false;
            showBulletin(success ? getString(R.string.NowPlayingBindSuccess) : message);
        });
    }

    private void checkWhitelistAndRefresh() {
        long tgUid = UserConfig.getInstance(currentAccount).getClientUserId();
        LocalNowPlayingController.checkWhitelistStatus(tgUid, status -> {
            whitelisted = status;
            whitelistChecked = true;
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(true);
            }
        });
    }

    private void showBulletin(String message) {
        try {
            if (getParentActivity() != null) {
                org.telegram.ui.Components.BulletinFactory.of(this).createSimpleBulletin(R.drawable.msg_info_remix, message).show();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private boolean hasChanges() {
        return serviceType != initialServiceType
            || !TextUtils.equals(lastFmUsername, initialLastFmUsername)
            || !TextUtils.equals(statsFmUsername, initialStatsFmUsername);
    }

    private void checkDone(boolean animated) {
        if (doneButton == null) {
            return;
        }
        boolean hasChanges = hasChanges();
        doneButton.setEnabled(hasChanges);
        if (animated) {
            doneButton.animate()
                .alpha(hasChanges ? 1.0f : 0.0f)
                .scaleX(hasChanges ? 1.0f : 0.0f)
                .scaleY(hasChanges ? 1.0f : 0.0f)
                .setDuration(180)
                .start();
        } else {
            doneButton.setAlpha(hasChanges ? 1.0f : 0.0f);
            doneButton.setScaleX(hasChanges ? 1.0f : 0.0f);
            doneButton.setScaleY(hasChanges ? 1.0f : 0.0f);
        }
    }

    private void processDone() {
        if (serviceType == LocalNowPlayingController.SERVICE_LAST_FM || serviceType == LocalNowPlayingController.SERVICE_STATS_FM) {
            if (TextUtils.isEmpty(getCurrentUsername())) {
                BotWebViewVibrationEffect.APP_ERROR.vibrate();
                AndroidUtilities.shakeViewSpring(usernameEdit, shiftDp = -shiftDp);
                return;
            }
        }

        NaConfig.INSTANCE.getNowPlayingServiceType().setConfigInt(serviceType);
        NaConfig.INSTANCE.getNowPlayingLastFmUsername().setConfigString(lastFmUsername == null ? "" : lastFmUsername);
        NaConfig.INSTANCE.getNowPlayingStatsFmUsername().setConfigString(statsFmUsername == null ? "" : statsFmUsername);

        // 如果在白名单，自动绑定
        if (whitelisted && serviceType != LocalNowPlayingController.SERVICE_NONE) {
            doBind();
        }

        finishFragment();
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        if (listView != null) {
            listView.setPadding(0, 0, 0, bottom);
            listView.setClipToPadding(false);
        }
    }
}
