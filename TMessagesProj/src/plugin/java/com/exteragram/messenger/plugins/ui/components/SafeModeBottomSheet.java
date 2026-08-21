package com.exteragram.messenger.plugins.ui.components;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.exteragram.messenger.plugins.PluginsController;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

public class SafeModeBottomSheet extends BottomSheet {
    public SafeModeBottomSheet(BaseFragment fragment) {
        super(fragment.getParentActivity(), false, fragment.getResourceProvider());
        Activity activity = fragment.getParentActivity();
        fixNavigationBar();

        FrameLayout root = new FrameLayout(activity);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        FrameLayout iconWrap = new FrameLayout(activity);
        iconWrap.setBackground(Theme.createCircleDrawable(AndroidUtilities.dp(96), Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider)));
        ImageView icon = new ImageView(activity);
        icon.setScaleType(ImageView.ScaleType.CENTER);
        icon.setImageResource(R.drawable.msg2_secret);
        icon.setColorFilter(Theme.getColor(Theme.key_featuredStickers_buttonText, resourcesProvider));
        iconWrap.addView(icon, LayoutHelper.createFrame(42, 42, Gravity.CENTER));
        content.addView(iconWrap, LayoutHelper.createLinear(96, 96, Gravity.CENTER_HORIZONTAL, 0, 16, 0, 0));

        TextView titleView = new TextView(activity);
        titleView.setGravity(Gravity.CENTER_HORIZONTAL);
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        titleView.setTextSize(1, 20);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setText(LocaleController.getString(R.string.PluginsSafeMode));
        content.addView(titleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 40, 20, 40, 0));

        TextView subtitleView = new TextView(activity);
        subtitleView.setGravity(Gravity.CENTER_HORIZONTAL);
        subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        subtitleView.setTextSize(1, 14);
        subtitleView.setText(PluginsController.getSafeModeStatusText());
        content.addView(subtitleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 21, 8, 21, 0));

        ButtonWithCounterView disableButton = new ButtonWithCounterView(activity, true, resourcesProvider);
        disableButton.setText(LocaleController.getString(R.string.Disable), false);
        disableButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
                PluginsController.disableSafeMode();
                PluginsController.getInstance().restart();
            }
        });
        content.addView(disableButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.TOP, 16, 28, 16, 16));

        setCustomView(root);
    }
}
