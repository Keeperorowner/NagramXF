package com.exteragram.messenger.plugins.ui.components;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.exteragram.messenger.plugins.ui.PluginSettingsTranslation;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

/**
 * Translate entry of a plugin settings page: one button of the glass header's right pill that turns
 * the translation on, shows the progress of the batch while it is on its way and turns the page back
 * to the originals once the translations are there.
 *
 * <p>The button keeps no translation state of its own: it reads the shared
 * {@link PluginSettingsTranslation} of the plugin, so the header of the page and the header of its
 * sub pages always show the same state, and a page rebuild never leaves a stale button behind.
 *
 * <p>A view is created per page; the theme listener and the progress animation are bound to the
 * window and released with it.
 */
public class PluginTranslateButton extends View implements NotificationCenter.NotificationCenterDelegate {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ringRect = new RectF();

    private final Theme.ResourcesProvider resourcesProvider;

    private PluginSettingsTranslation translation;

    private Drawable translateDrawable;
    private Drawable resetDrawable;

    private int iconColor;
    private int accentColor;
    private int errorColor;

    private boolean loading;
    private boolean failed;
    private boolean resetState;

    private ValueAnimator loadingAnimator;
    private float loadingProgress;

    public PluginTranslateButton(Context context, @Nullable Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        // the click and the animated shown state belong to the menu item this view sits in
        setClickable(false);
        setFocusable(false);
        resolveColors();
    }

    /** The translation state this button shows, shared by every page of the plugin. */
    public void setTranslation(PluginSettingsTranslation translation) {
        this.translation = translation;
        updateState();
    }

    /**
     * Reads the state of the shared translation: no work in flight, a finished batch or a batch that
     * left texts untranslated. Called by the page whenever its translation listener fires.
     */
    public void updateState() {
        final boolean wasLoading = loading;
        loading = translation != null && translation.getEnabled() && translation.isLoading();
        resetState = translation != null && translation.getEnabled();
        if (loading != wasLoading) {
            if (loading) {
                startLoadingAnimation();
            } else {
                cancelLoadingAnimation();
            }
        }
        invalidate();
    }

    /** Whether the last batch left texts untranslated; the entry then stays for a retry. */
    public void setFailed(boolean failed) {
        if (this.failed != failed) {
            this.failed = failed;
            invalidate();
        }
    }

    /** True while the page shows the translations, i.e. the entry offers the originals again. */
    public boolean isResetState() {
        return resetState;
    }

    /** Whether the entry has anything to do for the current page texts. */
    public boolean isEntryVisible() {
        return translation != null && (translation.getEnabled() || !translation.isKnownSameLanguage());
    }

    /* Colors */

    private void resolveColors() {
        iconColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
        accentColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4, resourcesProvider);
        errorColor = Theme.getColor(Theme.key_text_RedRegular, resourcesProvider);
    }

    /** Re-reads the theme colors, e.g. after the theme of the app changed. */
    public void updateColors() {
        resolveColors();
        invalidate();
    }

    private int resolveCurrentColor() {
        if (failed && !loading) {
            return errorColor;
        }
        return resetState ? accentColor : iconColor;
    }

    /* Drawing */

    @Override
    protected void onDraw(Canvas canvas) {
        final int color = resolveCurrentColor();
        if (loading) {
            drawProgress(canvas, color);
            return;
        }
        final Drawable drawable = resetState ? getResetDrawable() : getTranslateDrawable();
        if (drawable == null) {
            return;
        }
        final int size = AndroidUtilities.dp(24);
        final int left = (getMeasuredWidth() - size) / 2;
        final int top = (getMeasuredHeight() - size) / 2;
        drawable.setBounds(left, top, left + size, top + size);
        drawable.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        drawable.draw(canvas);
    }

    private void drawProgress(Canvas canvas, int color) {
        final int size = AndroidUtilities.dp(20);
        final int stroke = Math.max(1, AndroidUtilities.dp(2));
        final int left = (getMeasuredWidth() - size) / 2;
        final int top = (getMeasuredHeight() - size) / 2;
        ringRect.set(left, top, left + size, top + size);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(Theme.multAlpha(color, .25f));
        canvas.drawArc(ringRect, 0, 360, false, paint);

        paint.setColor(color);
        canvas.drawArc(ringRect, loadingProgress * 360f, 90f, false, paint);
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

    private Drawable getResetDrawable() {
        if (resetDrawable == null) {
            resetDrawable = ContextCompat.getDrawable(getContext(), R.drawable.msg_reset);
            if (resetDrawable != null) {
                resetDrawable = resetDrawable.mutate();
            }
        }
        return resetDrawable;
    }

    /* Progress animation */

    private void startLoadingAnimation() {
        if (loadingAnimator != null) {
            return;
        }
        loadingAnimator = ValueAnimator.ofFloat(0f, 1f);
        loadingAnimator.setDuration(900);
        loadingAnimator.setRepeatCount(ValueAnimator.INFINITE);
        loadingAnimator.setInterpolator(new LinearInterpolator());
        loadingAnimator.addUpdateListener(animation -> {
            loadingProgress = (float) animation.getAnimatedValue();
            invalidate();
        });
        loadingAnimator.start();
    }

    private void cancelLoadingAnimation() {
        if (loadingAnimator != null) {
            loadingAnimator.cancel();
            loadingAnimator = null;
        }
        loadingProgress = 0f;
        invalidate();
    }

    /* Attach / detach: the theme listener and the animation live exactly as long as the view */

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didSetNewTheme);
        if (loading) {
            startLoadingAnimation();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.didSetNewTheme);
        cancelLoadingAnimation();
        super.onDetachedFromWindow();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.didSetNewTheme) {
            updateColors();
        }
    }
}
