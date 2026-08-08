package com.exteragram.messenger.updater;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.widget.NestedScrollView;

import com.exteragram.messenger.ExteraConfig;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DocumentObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.SvgHelper;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.spoilers.SpoilersTextView;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

public class UpdateAppAlertDialog extends BottomSheet {
    private final int accountNum;
    private final TLRPC.TL_help_appUpdate appUpdate;
    protected final LinearLayout linearLayout;
    private final int[] location;
    private int scrollOffsetY;
    private final NestedScrollView scrollView;
    private final View shadow;
    private AnimatorSet shadowAnimation;
    private final Drawable shadowDrawable;
    private final TextView textView;

    public void addContentBeforeDoneButton(FrameLayout frameLayout) {
    }

    public UpdateAppAlertDialog(Activity activity, TLRPC.TL_help_appUpdate appUpdate, int accountNum) {
        super(activity, false);
        this.location = new int[2];
        this.appUpdate = appUpdate;
        this.accountNum = accountNum;
        fixNavigationBar();
        setApplyTopPadding(false);
        setApplyBottomPadding(false);
        Drawable shadowDrawable = activity.getResources().getDrawable(R.drawable.sheet_shadow_round).mutate();
        this.shadowDrawable = shadowDrawable;
        shadowDrawable.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_dialogBackground), PorterDuff.Mode.MULTIPLY));
        FrameLayout frameLayout = new FrameLayout(activity) {
            @Override
            public void setTranslationY(float translationY) {
                super.setTranslationY(translationY);
                updateLayout();
            }

            @Override
            public boolean onInterceptTouchEvent(MotionEvent ev) {
                if (ev.getAction() == 0 && scrollOffsetY != 0 && ev.getY() < scrollOffsetY) {
                    dismiss();
                    return true;
                }
                return super.onInterceptTouchEvent(ev);
            }

            @Override
            @SuppressLint("ClickableViewAccessibility")
            public boolean onTouchEvent(MotionEvent event) {
                return !isDismissed() && super.onTouchEvent(event);
            }

            @Override
            public void onDraw(android.graphics.Canvas canvas) {
                shadowDrawable.setBounds(0, (int) (scrollOffsetY - backgroundPaddingTop) - (int) getTranslationY(), getMeasuredWidth(), getMeasuredHeight());
                shadowDrawable.draw(canvas);
            }
        };
        frameLayout.setWillNotDraw(false);
        containerView = frameLayout;
        NestedScrollView nestedScrollView = new NestedScrollView(activity) {
            private boolean ignoreLayout;

            @Override
            public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int size = View.MeasureSpec.getSize(heightMeasureSpec);
                measureChildWithMargins(linearLayout, widthMeasureSpec, 0, heightMeasureSpec, 0);
                int measuredHeight = linearLayout.getMeasuredHeight();
                int paddingTop = (size / 5) * 2;
                if (measuredHeight - (size - paddingTop) < AndroidUtilities.dp(90) || measuredHeight < (size / 2) + AndroidUtilities.dp(90)) {
                    paddingTop = size - measuredHeight;
                }
                if (paddingTop < 0) {
                    paddingTop = 0;
                }
                if (getPaddingTop() != paddingTop) {
                    ignoreLayout = true;
                    setPadding(0, paddingTop, 0, 0);
                    ignoreLayout = false;
                }
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.UNSPECIFIED));
            }

            @Override
            public void onLayout(boolean changed, int left, int top, int right, int bottom) {
                super.onLayout(changed, left, top, right, bottom);
                updateLayout();
            }

            @Override
            public void requestLayout() {
                if (ignoreLayout) {
                    return;
                }
                super.requestLayout();
            }

            @Override
            public void onScrollChanged(int l, int t, int oldl, int oldt) {
                super.onScrollChanged(l, t, oldl, oldt);
                updateLayout();
            }
        };
        scrollView = nestedScrollView;
        nestedScrollView.setFillViewport(true);
        nestedScrollView.setWillNotDraw(false);
        nestedScrollView.setClipToPadding(false);
        nestedScrollView.setVerticalScrollBarEnabled(false);
        frameLayout.addView(nestedScrollView, LayoutHelper.createFrame(-1, -1.0f, 51, 0.0f, 0.0f, 0.0f, 130.0f));
        LinearLayout linearLayout = new LinearLayout(activity);
        this.linearLayout = linearLayout;
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        nestedScrollView.addView(linearLayout, LayoutHelper.createScroll(-1, -2, 51));
        if (appUpdate.sticker != null) {
            BackupImageView backupImageView = new BackupImageView(activity);
            SvgHelper.SvgDrawable svgThumb = DocumentObject.getSvgThumb(appUpdate.sticker.thumbs, Theme.key_windowBackgroundGray, 1.0f);
            ImageLocation forDocument = ImageLocation.getForDocument(FileLoader.getClosestPhotoSizeWithSize(appUpdate.sticker.thumbs, 90), appUpdate.sticker);
            if (svgThumb != null) {
                backupImageView.setImage(ImageLocation.getForDocument(appUpdate.sticker), "250_250", svgThumb, 0, "update");
            } else {
                backupImageView.setImage(ImageLocation.getForDocument(appUpdate.sticker), "250_250", forDocument, null, 0, "update");
            }
            linearLayout.addView(backupImageView, LayoutHelper.createLinear(160, 160, 49, 17, 8, 17, 0));
        }
        TextView textView = new TextView(activity);
        this.textView = textView;
        textView.setTypeface(AndroidUtilities.bold());
        textView.setTextSize(20.0f);
        int dialogTextBlack = Theme.key_dialogTextBlack;
        textView.setTextColor(Theme.getColor(dialogTextBlack));
        textView.setSingleLine(true);
        textView.setEllipsize(TextUtils.TruncateAt.END);
        textView.setText(getTitleText());
        linearLayout.addView(textView, LayoutHelper.createLinear(-2, -2, 49, 23, 16, 23, 0));
        TextView versionTextView = new TextView(getContext());
        versionTextView.setTextColor(Theme.getColor(Theme.key_dialogTextGray3));
        versionTextView.setTextSize(14.0f);
        versionTextView.setMovementMethod(new AndroidUtilities.LinkMovementMethodMy());
        int dialogTextLink = Theme.key_dialogTextLink;
        versionTextView.setLinkTextColor(Theme.getColor(dialogTextLink));
        versionTextView.setText(LocaleController.formatString("AppUpdateVersionAndSize", R.string.AppUpdateVersionAndSize, appUpdate.version, AndroidUtilities.formatFileSize(appUpdate.document.size)));
        versionTextView.setGravity(49);
        linearLayout.addView(versionTextView, LayoutHelper.createLinear(-2, -2, 49, 23, 0, 23, 5));
        SpoilersTextView spoilersTextView = new SpoilersTextView(getContext());
        spoilersTextView.setTextColor(Theme.getColor(dialogTextBlack));
        spoilersTextView.setTextSize(14.0f);
        spoilersTextView.setMovementMethod(new AndroidUtilities.LinkMovementMethodMy());
        spoilersTextView.setLinksClickable(false);
        spoilersTextView.setLinkTextColor(Theme.getColor(dialogTextLink));
        if (TextUtils.isEmpty(appUpdate.text)) {
            spoilersTextView.setText(AndroidUtilities.replaceTags(LocaleController.getString(R.string.AppUpdateChangelogEmpty)));
        } else {
            android.text.SpannableStringBuilder stringBuilder = new android.text.SpannableStringBuilder(appUpdate.text);
            MessageObject.addEntitiesToText(stringBuilder, appUpdate.entities, false, false, false, false);
            MessageObject.replaceAnimatedEmoji(stringBuilder, appUpdate.entities, spoilersTextView.getPaint().getFontMetricsInt());
            spoilersTextView.setText(stringBuilder);
        }
        spoilersTextView.setGravity(51);
        linearLayout.addView(spoilersTextView, LayoutHelper.createLinear(-2, -2, 51, 23, 15, 23, 0));
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(-1, AndroidUtilities.getShadowHeight(), 83);
        layoutParams.bottomMargin = AndroidUtilities.dp(127.0f);
        View shadow = new View(activity);
        this.shadow = shadow;
        shadow.setBackgroundColor(Theme.getColor(Theme.key_dialogShadowLine));
        shadow.setAlpha(0.0f);
        shadow.setTag(1);
        frameLayout.addView(shadow, layoutParams);
        addContentBeforeDoneButton(frameLayout);
        ButtonWithCounterView buttonWithCounterView = new ButtonWithCounterView(activity, true, null);
        buttonWithCounterView.setRound();
        buttonWithCounterView.setText(getDoneButtonText(), false);
        buttonWithCounterView.setOnClickListener(view -> onDone());
        frameLayout.addView(buttonWithCounterView, LayoutHelper.createFrame(-1, 48.0f, 83, 22.0f, 14.0f, 22.0f, 64.0f));
        addContentAfterDoneButton(frameLayout);
    }

    public void addContentAfterDoneButton(FrameLayout frameLayout) {
        addRemindLaterButton(frameLayout, () -> {
            ExteraConfig.sdkUpdateScheduleTimestamp = System.currentTimeMillis();
            dismiss();
        });
    }

    public void addRemindLaterButton(FrameLayout frameLayout, Runnable runnable) {
        ButtonWithCounterView buttonWithCounterView = new ButtonWithCounterView(getContext(), false, null);
        buttonWithCounterView.setRound();
        buttonWithCounterView.setText(LocaleController.getString(R.string.AppUpdateRemindMeLater), false);
        buttonWithCounterView.setOnClickListener(view -> runnable.run());
        frameLayout.addView(buttonWithCounterView, LayoutHelper.createFrame(-1, 48.0f, 83, 22.0f, 14.0f, 22.0f, 8.0f));
    }

    public String getDoneButtonText() {
        return LocaleController.getString(FileLoader.getInstance(currentAccount).getPathToAttach(SharedConfig.pendingAppUpdate.document, true).exists() ? R.string.AppUpdateNow : R.string.AppUpdateDownloadNow);
    }

    public String getTitleText() {
        return LocaleController.getString(R.string.UpdateAvailable);
    }

    public void onDone() {
        if (FileLoader.getInstance(currentAccount).getPathToAttach(SharedConfig.pendingAppUpdate.document, true).exists()) {
            ApplicationLoader.applicationLoaderInstance.openApkInstall((Activity) getContext(), SharedConfig.pendingAppUpdate.document);
        } else {
            FileLoader.getInstance(accountNum).loadFile(appUpdate.document, "update", 1, 1);
        }
        dismiss();
    }

    private void runShadowAnimation(final boolean show) {
        if ((!show || shadow.getTag() == null) && (show || shadow.getTag() != null)) {
            return;
        }
        shadow.setTag(show ? null : 1);
        if (show) {
            shadow.setVisibility(View.VISIBLE);
        }
        AnimatorSet currentAnimation = shadowAnimation;
        if (currentAnimation != null) {
            currentAnimation.cancel();
        }
        AnimatorSet animatorSet = new AnimatorSet();
        shadowAnimation = animatorSet;
        animatorSet.playTogether(ObjectAnimator.ofFloat(shadow, View.ALPHA, show ? 1.0f : 0.0f));
        shadowAnimation.setDuration(150L);
        shadowAnimation.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animator) {
                if (shadowAnimation == null || !shadowAnimation.equals(animator)) {
                    return;
                }
                if (!show) {
                    shadow.setVisibility(View.INVISIBLE);
                }
                shadowAnimation = null;
            }

            @Override
            public void onAnimationCancel(Animator animator) {
                if (shadowAnimation == null || !shadowAnimation.equals(animator)) {
                    return;
                }
                shadowAnimation = null;
            }
        });
        shadowAnimation.start();
    }

    private void updateLayout() {
        linearLayout.getChildAt(0).getLocationInWindow(location);
        int max = Math.max(location[1] - AndroidUtilities.dp(24.0f), 0);
        runShadowAnimation((location[1] + linearLayout.getMeasuredHeight()) > (container.getMeasuredHeight() - AndroidUtilities.dp(113.0f)) + containerView.getTranslationY());
        if (scrollOffsetY != max) {
            scrollOffsetY = max;
            scrollView.invalidate();
        }
    }
}
