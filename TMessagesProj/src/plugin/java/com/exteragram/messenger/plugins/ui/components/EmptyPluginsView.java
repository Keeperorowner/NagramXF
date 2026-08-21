package com.exteragram.messenger.plugins.ui.components;

import android.content.Context;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.EffectsTextView;
import org.telegram.ui.Components.LayoutHelper;

public class EmptyPluginsView extends FrameLayout {
    private final BackupImageView backupImageView;
    private final EffectsTextView textView;

    public EmptyPluginsView(Context context) {
        this(context, null);
    }

    public EmptyPluginsView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);

        LinearLayout container = new LinearLayout(context);
        container.setPadding(AndroidUtilities.dp(20), 0, AndroidUtilities.dp(20), 0);
        container.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setClipChildren(false);
        container.setClipToPadding(false);

        backupImageView = new BackupImageView(context);
        container.addView(backupImageView, LayoutHelper.createLinear(100, 100, android.view.Gravity.CENTER_HORIZONTAL, 0, 0, 0, 20));

        textView = new EffectsTextView(context, resourcesProvider);
        textView.setTextSize(1, 14);
        textView.setTextColor(Theme.getColor(Theme.key_emptyListPlaceholder, resourcesProvider));
        textView.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        textView.setTypeface(Typeface.DEFAULT);
        textView.setMovementMethod(new AndroidUtilities.LinkMovementMethodMy());
        textView.setLinkTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteLinkText, resourcesProvider));
        textView.setText(LocaleController.getString(R.string.NoResult));
        container.addView(textView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, android.view.Gravity.CENTER_HORIZONTAL));

        addView(container, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, android.view.Gravity.CENTER));
        setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return true;
            }
        });
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    public void setText(CharSequence text) {
        textView.setText(text);
    }

    public BackupImageView getBackupImageView() {
        return backupImageView;
    }
}
