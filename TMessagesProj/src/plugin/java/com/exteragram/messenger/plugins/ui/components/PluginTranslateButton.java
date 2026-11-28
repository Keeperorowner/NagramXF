package com.exteragram.messenger.plugins.ui.components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.exteragram.messenger.plugins.ui.PluginSettingsTranslation;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

/** Displays the shared translation state in the settings header. */
public class PluginTranslateButton extends View implements NotificationCenter.NotificationCenterDelegate {

    private final Theme.ResourcesProvider resourcesProvider;
    private PluginSettingsTranslation translation;

    private Drawable translateDrawable;
    private Drawable originalDrawable;

    private int iconColor;
    private int accentColor;
    private int errorColor;

    private boolean loading;
    private boolean failed;
    private boolean resetState;

    public PluginTranslateButton(Context context, @Nullable Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setClickable(false);
        setFocusable(false);
        resolveColors();
    }

    public void setTranslation(PluginSettingsTranslation translation) {
        this.translation = translation;
        updateState();
    }

    public void updateState() {
        final boolean enabled = translation != null && translation.getEnabled();
        loading = enabled && translation.isLoading();
        resetState = enabled && !loading;
        invalidate();
    }

    public boolean isLoading() {
        return loading;
    }

    public void setFailed(boolean failed) {
        if (this.failed != failed) {
            this.failed = failed;
            invalidate();
        }
    }

    /** True when the entry offers to show the original text. */
    public boolean isResetState() {
        return resetState;
    }

    public boolean isEntryVisible() {
        return translation != null && (translation.getEnabled() || !translation.isKnownSameLanguage());
    }

    private void resolveColors() {
        iconColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
        accentColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4, resourcesProvider);
        errorColor = Theme.getColor(Theme.key_text_RedRegular, resourcesProvider);
    }

    public void updateColors() {
        resolveColors();
        invalidate();
    }

    private int resolveCurrentColor() {
        if (failed && !loading) {
            return errorColor;
        }
        return loading || resetState ? accentColor : iconColor;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final Drawable drawable = resetState ? getOriginalDrawable() : getTranslateDrawable();
        if (drawable == null) {
            return;
        }
        final int size = AndroidUtilities.dp(24);
        final int left = (getMeasuredWidth() - size) / 2;
        final int top = (getMeasuredHeight() - size) / 2;
        drawable.setBounds(left, top, left + size, top + size);
        drawable.setColorFilter(new PorterDuffColorFilter(resolveCurrentColor(), PorterDuff.Mode.SRC_IN));
        drawable.draw(canvas);
    }

    private Drawable getTranslateDrawable() {
        if (translateDrawable == null) {
            translateDrawable = ContextCompat.getDrawable(getContext(), R.drawable.msg_translate);
            if (translateDrawable != null) {
                translateDrawable = translateDrawable.mutate();
            }
        }
        return translateDrawable;
    }

    private Drawable getOriginalDrawable() {
        if (originalDrawable == null) {
            originalDrawable = ContextCompat.getDrawable(getContext(), R.drawable.photo_undo2);
            if (originalDrawable != null) {
                originalDrawable = originalDrawable.mutate();
            }
        }
        return originalDrawable;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didSetNewTheme);
    }

    @Override
    protected void onDetachedFromWindow() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.didSetNewTheme);
        super.onDetachedFromWindow();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.didSetNewTheme) {
            updateColors();
        }
    }
}
