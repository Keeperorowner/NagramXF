package com.exteragram.messenger.updater;

import android.app.Activity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.plugins.PythonPluginsEngine;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CheckBox2;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;

public class PythonSdkUpdateDialog extends UpdateAppAlertDialog {
    private final PythonPluginsEngine.Updater.PythonSdkUpdateInfo update;
    private boolean enableAutoUpdate;

    public PythonSdkUpdateDialog(Activity activity, PythonPluginsEngine.Updater.PythonSdkUpdateInfo update, int accountNum) {
        super(activity, update, accountNum);
        this.update = update;
    }

    @Override
    public String getDoneButtonText() {
        return LocaleController.getString(R.string.AppUpdateNow);
    }

    @Override
    public String getTitleText() {
        return super.getTitleText() + " (Plugins PySDK)";
    }

    @Override
    public void addContentBeforeDoneButton(FrameLayout container) {
        final CheckBox2 checkBox2 = new CheckBox2(getContext(), 21, resourcesProvider);
        checkBox2.setColor(Theme.key_radioBackgroundChecked, Theme.key_checkboxDisabled, Theme.key_checkboxCheck);
        checkBox2.setDrawUnchecked(true);
        checkBox2.setChecked(false, false);
        checkBox2.setDrawBackgroundAsArc(10);
        TextView textView = new TextView(getContext());
        textView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        textView.setTextSize(1, 14.0f);
        textView.setText(LocaleController.getString(R.string.EnableAutoUpdate));
        FrameLayout checkboxContainer = new FrameLayout(getContext());
        checkboxContainer.addView(checkBox2, LayoutHelper.createFrame(21, 21.0f, 17, 0.0f, 0.0f, 0.0f, 0.0f));
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(AndroidUtilities.dp(8.0f), AndroidUtilities.dp(6.0f), AndroidUtilities.dp(10.0f), AndroidUtilities.dp(6.0f));
        row.addView(checkboxContainer, LayoutHelper.createLinear(24, 24, 16, 0, 0, 6, 0));
        row.addView(textView, LayoutHelper.createLinear(-2, -2, 16));
        row.setOnClickListener(view -> {
            checkBox2.setChecked(!checkBox2.isChecked(), true);
            enableAutoUpdate = checkBox2.isChecked();
        });
        ScaleStateListAnimator.apply(row, 0.05f, 1.2f);
        row.setBackground(Theme.createRadSelectorDrawable(getThemedColor(Theme.key_listSelector), 8, 8));
        linearLayout.addView(row, LayoutHelper.createLinear(-2, -2, 1, 0, 0, 0, 8));
    }

    @Override
    public void addContentAfterDoneButton(FrameLayout container) {
        addRemindLaterButton(container, () -> {
            long now = System.currentTimeMillis();
            ExteraConfig.sdkUpdateScheduleTimestamp = now;
            ExteraConfig.editor.putLong("sdkUpdateScheduleTimestamp", now).apply();
            dismiss();
        });
    }

    @Override
    public void onDone() {
        if (enableAutoUpdate) {
            ExteraConfig.pluginsPySdkAutoUpdate = true;
            ExteraConfig.editor.putBoolean("pluginsPySdkAutoUpdate", true).apply();
            if (PythonPluginsEngine.Updater.getNotifyWhenChangeStatus()) {
                PythonPluginsEngine.Updater.notifyRunnable.run();
            }
        }
        PythonPluginsEngine.Updater.savePythonSdkArchive(update.message, update.document, true);
        dismiss();
    }
}