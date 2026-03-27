package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BotWebViewVibrationEffect;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
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

    private ActionBarMenuItem doneButton;
    private UniversalRecyclerView listView;
    private EditTextCell usernameEdit;
    private EditTextCell apiKeyEdit;

    private int initialServiceType;
    private String initialUsername;
    private String initialApiKey;

    private int serviceType;
    private String username;
    private String apiKey;

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
        initialUsername = username = LocalNowPlayingController.getLastFmUsername();
        initialApiKey = apiKey = LocalNowPlayingController.getLastFmApiKey();

        usernameEdit = new EditTextCell(context, getString(R.string.NowPlayingLastFmUsername), false, false, -1, resourceProvider) {
            @Override
            protected void onTextChanged(CharSequence newText) {
                super.onTextChanged(newText);
                username = newText == null ? "" : newText.toString().trim();
                checkDone(true);
            }
        };
        usernameEdit.hideKeyboardOnEnter();
        usernameEdit.setText(username);

        apiKeyEdit = new EditTextCell(context, getString(R.string.NowPlayingLastFmApiKey), false, false, -1, resourceProvider) {
            @Override
            protected void onTextChanged(CharSequence newText) {
                super.onTextChanged(newText);
                apiKey = newText == null ? "" : newText.toString().trim();
                checkDone(true);
            }
        };
        apiKeyEdit.hideKeyboardOnEnter();
        apiKeyEdit.editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiKeyEdit.editText.setRawInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiKeyEdit.editText.setTransformationMethod(PasswordTransformationMethod.getInstance());
        apiKeyEdit.setText(apiKey);

        FrameLayout contentView = new FrameLayout(context);
        contentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, null);
        listView.setSections();
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        actionBar.setAdaptiveBackground(listView);

        checkDone(false);

        return fragmentView = contentView;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(getString(R.string.NowPlayingService)));
        items.add(UItem.asRadio(RADIO_NONE, getString(R.string.None)).setChecked(serviceType == LocalNowPlayingController.SERVICE_NONE));
        items.add(UItem.asRadio(RADIO_LAST_FM, "Last.fm").setChecked(serviceType == LocalNowPlayingController.SERVICE_LAST_FM));
        items.add(UItem.asShadow(getString(R.string.NowPlayingLocalOnlyInfo)));

        if (serviceType == LocalNowPlayingController.SERVICE_LAST_FM) {
            items.add(UItem.asHeader(getString(R.string.Username)));
            items.add(UItem.asCustom(usernameEdit));
            items.add(UItem.asHeader(getString(R.string.NowPlayingLastFmApiKey)));
            items.add(UItem.asCustom(apiKeyEdit));
            items.add(UItem.asShadow(AndroidUtilities.replaceSingleTag(getString(R.string.NowPlayingLastFmApiKeyInfo), () ->
                Browser.openUrl(getParentActivity(), "https://www.last.fm/api/account/create")
            )));
        }
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == RADIO_NONE) {
            serviceType = LocalNowPlayingController.SERVICE_NONE;
            listView.adapter.update(true);
            checkDone(true);
        } else if (item.id == RADIO_LAST_FM) {
            serviceType = LocalNowPlayingController.SERVICE_LAST_FM;
            listView.adapter.update(true);
            checkDone(true);
        }
    }

    private boolean hasChanges() {
        return serviceType != initialServiceType
            || !TextUtils.equals(username, initialUsername)
            || !TextUtils.equals(apiKey, initialApiKey);
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
        if (serviceType == LocalNowPlayingController.SERVICE_LAST_FM) {
            if (TextUtils.isEmpty(username)) {
                BotWebViewVibrationEffect.APP_ERROR.vibrate();
                AndroidUtilities.shakeViewSpring(usernameEdit, shiftDp = -shiftDp);
                return;
            }
            if (TextUtils.isEmpty(apiKey)) {
                BotWebViewVibrationEffect.APP_ERROR.vibrate();
                AndroidUtilities.shakeViewSpring(apiKeyEdit, shiftDp = -shiftDp);
                return;
            }
        }

        NaConfig.INSTANCE.getNowPlayingServiceType().setConfigInt(serviceType);
        NaConfig.INSTANCE.getNowPlayingLastFmUsername().setConfigString(username == null ? "" : username);
        NaConfig.INSTANCE.getNowPlayingLastFmApiKey().setConfigString(apiKey == null ? "" : apiKey);
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
