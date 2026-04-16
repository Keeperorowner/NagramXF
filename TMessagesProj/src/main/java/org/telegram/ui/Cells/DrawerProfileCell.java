
package org.telegram.ui.Cells;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.telegram.PhoneFormat.PhoneFormat;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.DrawerLayoutContainer;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.FireworksEffect;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.MotionBackgroundDrawable;
import org.telegram.ui.Components.RLottieDrawable;
import org.telegram.ui.Components.RLottieImageView;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Components.SnowflakesEffect;
import org.telegram.ui.Components.Reactions.HwEmojis;
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble;

import tw.nekomimi.nekogram.NekoConfig;

public class DrawerProfileCell extends FrameLayout implements NotificationCenter.NotificationCenterDelegate {

    private final BackupImageView avatarImageView;
    private final SimpleTextView nameTextView;
    private final TextView phoneTextView;
    private final ImageView shadowView;
    protected final ImageView arrowView;
    private final FrameLayout darkThemeBackgroundView;
    private final RLottieImageView darkThemeView;
    private final RLottieDrawable sunDrawable;
    private final DrawerLayoutContainer drawerLayoutContainer;
    private final AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable status;
    private final AnimatedStatusView animatedStatus;
    private final Rect srcRect = new Rect();
    private final Rect destRect = new Rect();
    private final Paint backgroundPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private SnowflakesEffect snowflakesEffect;
    private FireworksEffect fireworksEffect;

    private boolean accountsShown;
    private boolean updateRightDrawable = true;
    private TLRPC.User user;
    private Long statusGiftId;
    private int lastAccount = -1;
    private Drawable premiumStar;
    private int lastStatusColor = Integer.MIN_VALUE;

    public DrawerProfileCell(Context context, DrawerLayoutContainer drawerLayoutContainer) {
        super(context);
        this.drawerLayoutContainer = drawerLayoutContainer;

        applyBackground(true);

        shadowView = new ImageView(context);
        shadowView.setVisibility(INVISIBLE);
        shadowView.setScaleType(ImageView.ScaleType.FIT_XY);
        shadowView.setImageResource(R.drawable.bottom_shadow);
        addView(shadowView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 70, Gravity.LEFT | Gravity.BOTTOM));

        avatarImageView = new BackupImageView(context);
        avatarImageView.setRoundRadius(AndroidUtilities.dp(32));
        addView(avatarImageView, LayoutHelper.createFrame(64, 64, Gravity.LEFT | Gravity.BOTTOM, 16, 0, 0, 67));

        nameTextView = new SimpleTextView(context) {
            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                if (updateRightDrawable) {
                    updateRightDrawable = false;
                    getEmojiStatusLocation(AndroidUtilities.rectTmp2);
                    animatedStatus.translate(AndroidUtilities.rectTmp2.centerX(), AndroidUtilities.rectTmp2.centerY());
                }
            }

            @Override
            public void invalidate() {
                if (HwEmojis.grab(this)) {
                    return;
                }
                super.invalidate();
            }

            @Override
            public void invalidate(int l, int t, int r, int b) {
                if (HwEmojis.grab(this)) {
                    return;
                }
                super.invalidate(l, t, r, b);
            }

            @Override
            public void invalidateDrawable(Drawable who) {
                if (HwEmojis.grab(this)) {
                    return;
                }
                super.invalidateDrawable(who);
            }

            @Override
            public void invalidate(Rect dirty) {
                if (HwEmojis.grab(this)) {
                    return;
                }
                super.invalidate(dirty);
            }
        };
        nameTextView.setPadding(0, AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4));
        nameTextView.setTextSize(15);
        nameTextView.setTypeface(AndroidUtilities.bold());
        nameTextView.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        nameTextView.setEllipsizeByGradient(true);
        nameTextView.setRightDrawableOutside(true);
        addView(nameTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.BOTTOM, 16, 0, 76, 28));

        phoneTextView = new TextView(context);
        phoneTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        phoneTextView.setGravity(Gravity.LEFT);
        phoneTextView.setLines(1);
        phoneTextView.setMaxLines(1);
        phoneTextView.setSingleLine(true);
        addView(phoneTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.BOTTOM, 16, 0, 52, 9));

        arrowView = new ImageView(context);
        arrowView.setScaleType(ImageView.ScaleType.CENTER);
        arrowView.setImageResource(R.drawable.msg_expand);
        addView(arrowView, LayoutHelper.createFrame(59, 59, Gravity.RIGHT | Gravity.BOTTOM));
        setArrowState(false);

        sunDrawable = new RLottieDrawable(R.raw.sun, "" + R.raw.sun, AndroidUtilities.dp(24), AndroidUtilities.dp(24), true, null);
        sunDrawable.setPlayInDirectionOfCustomEndFrame(true);
        darkThemeBackgroundView = new FrameLayout(context);
        ScaleStateListAnimator.apply(darkThemeBackgroundView);
        addView(darkThemeBackgroundView, LayoutHelper.createFrame(36, 36, Gravity.RIGHT | Gravity.BOTTOM, 0, 0, 12, 96));
        darkThemeView = new RLottieImageView(context) {
            @Override
            public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(info);
                if (Theme.isCurrentThemeDark()) {
                    info.setText(LocaleController.getString(R.string.AccDescrSwitchToDayTheme));
                } else {
                    info.setText(LocaleController.getString(R.string.AccDescrSwitchToNightTheme));
                }
            }
        };
        darkThemeView.setScaleType(ImageView.ScaleType.CENTER);
        darkThemeView.setAnimation(sunDrawable);
        darkThemeBackgroundView.addView(darkThemeView, LayoutHelper.createFrame(24, 24, Gravity.CENTER));
        updateThemeToggleColors(Theme.getColor(Theme.key_chats_menuName));
        syncThemeToggle(Theme.isCurrentThemeDark(), false);
        darkThemeBackgroundView.setOnClickListener(v -> {
            if (DialogsActivity.switchingTheme || Theme.isAnimatingColor()) {
                return;
            }
            resetThemeTogglePressAnimation();
            SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("themeconfig", Context.MODE_PRIVATE);
            String dayThemeName = preferences.getString("lastDayTheme", "Blue");
            if (Theme.getTheme(dayThemeName) == null || Theme.getTheme(dayThemeName).isDark()) {
                dayThemeName = "Blue";
            }
            String nightThemeName = preferences.getString("lastDarkTheme", "Dark Blue");
            if (Theme.getTheme(nightThemeName) == null || !Theme.getTheme(nightThemeName).isDark()) {
                nightThemeName = "Dark Blue";
            }
            boolean toDark;
            Theme.ThemeInfo themeInfo;
            if (Theme.isCurrentThemeDark()) {
                toDark = false;
                themeInfo = Theme.getTheme(dayThemeName);
            } else {
                toDark = true;
                themeInfo = Theme.getTheme(nightThemeName);
            }
            if (themeInfo == null) {
                return;
            }
            DialogsActivity.switchingTheme = true;
            syncThemeToggle(toDark, true);
            switchTheme(themeInfo, toDark);
            Theme.turnOffAutoNight((BulletinFactory) null, null);
        });
        darkThemeBackgroundView.setOnLongClickListener(e -> {
            if (drawerLayoutContainer != null) {
                drawerLayoutContainer.presentFragment(new org.telegram.ui.ThemeActivity(org.telegram.ui.ThemeActivity.THEME_TYPE_BASIC));
            }
            return true;
        });

        status = new AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable(this, AndroidUtilities.dp(20));
        nameTextView.setRightDrawable(status);
        nameTextView.setRightDrawableOnClick(v -> {
            if (status.getDrawable() != null) {
                onPremiumClick();
            }
        });

        animatedStatus = new AnimatedStatusView(context, 20, 60);
        animatedStatus.setAlpha(0f);
        addView(animatedStatus, LayoutHelper.createFrame(20, 20, Gravity.LEFT | Gravity.TOP));

        updateHeaderDecoration();
        updateColors();
    }

    protected void onPremiumClick() {
    }

    public boolean isInAvatar(float x, float y) {
        return x >= avatarImageView.getLeft() && x <= avatarImageView.getRight() && y >= avatarImageView.getTop() && y <= avatarImageView.getBottom();
    }

    public boolean hasAvatar() {
        return avatarImageView.getImageReceiver().hasNotThumb();
    }

    public boolean isAccountsShown() {
        return accountsShown;
    }

    public void setAccountsShown(boolean value, boolean animated) {
        if (accountsShown == value) {
            return;
        }
        accountsShown = value;
        setArrowState(animated);
    }

    public void setUser(TLRPC.User user, boolean accountsShown) {
        int account = UserConfig.selectedAccount;
        if (account != lastAccount) {
            if (lastAccount >= 0) {
                NotificationCenter.getInstance(lastAccount).removeObserver(this, NotificationCenter.userEmojiStatusUpdated);
                NotificationCenter.getInstance(lastAccount).removeObserver(this, NotificationCenter.updateInterfaces);
            }
            NotificationCenter.getInstance(lastAccount = account).addObserver(this, NotificationCenter.userEmojiStatusUpdated);
            NotificationCenter.getInstance(lastAccount).addObserver(this, NotificationCenter.updateInterfaces);
        }

        this.user = user;
        this.accountsShown = accountsShown;
        setArrowState(false);
        if (user == null) {
            return;
        }

        CharSequence text = UserObject.getUserName(user);
        try {
            text = Emoji.replaceEmoji(text, nameTextView.getPaint().getFontMetricsInt(), false);
        } catch (Exception ignore) {
        }
        nameTextView.setText(text);

        statusGiftId = null;
        Long emojiStatusId = UserObject.getEmojiStatusDocumentId(user);
        if (emojiStatusId != null) {
            boolean isCollectible = user.emoji_status instanceof TLRPC.TL_emojiStatusCollectible;
            animatedStatus.animate().alpha(1f).setDuration(200).start();
            nameTextView.setDrawablePadding(AndroidUtilities.dp(4));
            status.set(emojiStatusId, true);
            status.setParticles(isCollectible, true);
            if (isCollectible) {
                statusGiftId = ((TLRPC.TL_emojiStatusCollectible) user.emoji_status).collectible_id;
            }
        } else if (MessagesController.getInstance(account).isPremiumUser(user)) {
            animatedStatus.animate().alpha(1f).setDuration(200).start();
            nameTextView.setDrawablePadding(AndroidUtilities.dp(4));
            if (premiumStar == null) {
                premiumStar = getResources().getDrawable(R.drawable.msg_premium_liststar).mutate();
            }
            if (lastStatusColor != Integer.MIN_VALUE) {
                premiumStar.setColorFilter(new PorterDuffColorFilter(lastStatusColor, PorterDuff.Mode.MULTIPLY));
            }
            status.set(premiumStar, true);
            status.setParticles(false, true);
        } else {
            animatedStatus.animateChange(null);
            animatedStatus.animate().alpha(0f).setDuration(200).start();
            status.set((Drawable) null, true);
            status.setParticles(false, true);
        }

        updateStatusColors();

        if (!NekoConfig.hidePhone.Bool() && !TextUtils.isEmpty(user.phone)) {
            phoneTextView.setText(PhoneFormat.getInstance().format("+" + user.phone));
        } else if (!TextUtils.isEmpty(user.username)) {
            phoneTextView.setText("@" + user.username);
        } else {
            phoneTextView.setText(LocaleController.getString("MobileHidden", R.string.MobileHidden));
        }

        AvatarDrawable avatarDrawable = new AvatarDrawable(user);
        avatarDrawable.setColor(Theme.getColor(Theme.key_avatar_backgroundInProfileBlue));
        avatarImageView.setForUserOrChat(user, avatarDrawable);

        applyBackground(true);
        updateRightDrawable = true;
    }

    public Integer applyBackground(boolean force) {
        Integer currentTag = (Integer) getTag();
        int backgroundKey = Theme.hasThemeKey(Theme.key_chats_menuTopBackground) && Theme.getColor(Theme.key_chats_menuTopBackground) != 0
                ? Theme.key_chats_menuTopBackground
                : Theme.key_chats_menuTopBackgroundCats;
        if (force || currentTag == null || backgroundKey != currentTag) {
            setBackgroundColor(Theme.getColor(backgroundKey));
            setTag(backgroundKey);
        }
        return backgroundKey;
    }

    private void updateHeaderDecoration() {
        int decoration = NekoConfig.actionBarDecoration.Int();
        if (decoration == 3) {
            snowflakesEffect = null;
            fireworksEffect = null;
            return;
        }
        if (Theme.getEventType() == 0 || decoration == 1) {
            if (snowflakesEffect == null) {
                snowflakesEffect = new SnowflakesEffect(0);
            }
            snowflakesEffect.setColorKey(Theme.key_chats_menuName);
            fireworksEffect = null;
            return;
        }
        if (decoration == 2) {
            if (fireworksEffect == null) {
                fireworksEffect = new FireworksEffect();
            }
            snowflakesEffect = null;
            return;
        }
        snowflakesEffect = null;
        fireworksEffect = null;
    }

    public void updateColors() {
        applyBackground(false);
        updateHeaderDecoration();
        int nameColor = Theme.getColor(Theme.key_chats_menuName);
        nameTextView.setTextColor(nameColor);
        phoneTextView.setTextColor(nameColor);
        arrowView.setColorFilter(new PorterDuffColorFilter(nameColor, PorterDuff.Mode.MULTIPLY));
        if (snowflakesEffect != null) {
            snowflakesEffect.updateColors();
        }
        updateStatusColors();
        if (!darkThemeView.isPlaying() && !DialogsActivity.switchingTheme) {
            syncThemeToggle(Theme.isCurrentThemeDark(), false);
        }
        if (!DialogsActivity.switchingTheme || Theme.isCurrentThemeDark()) {
            updateThemeToggleColors(nameColor);
        }
        invalidate();
        darkThemeView.invalidate();
    }

    private void updateStatusColors() {
        int statusColor = Theme.getColor(Theme.isCurrentThemeDark() ? Theme.key_chats_verifiedBackground : Theme.key_chats_menuPhoneCats);
        lastStatusColor = statusColor;
        animatedStatus.setColor(statusColor);
        status.setColor(statusColor);
        if (premiumStar != null) {
            premiumStar.setColorFilter(new PorterDuffColorFilter(statusColor, PorterDuff.Mode.MULTIPLY));
        }
    }

    private void applyThemeToggleDrawableColors(RLottieDrawable drawable, int color) {
        if (drawable == null) {
            return;
        }
        drawable.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        drawable.beginApplyLayerColors();
        drawable.setLayerColor("Sunny.**", color);
        drawable.setLayerColor("Path 6.**", color);
        drawable.setLayerColor("Path.**", color);
        drawable.setLayerColor("Path 5.**", color);
        drawable.commitApplyLayerColors();
    }

    private void updateThemeToggleColors(int color) {
        darkThemeBackgroundView.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(18), Theme.multAlpha(color, 0.075f)));
        ScaleStateListAnimator.apply(darkThemeBackgroundView);
        applyThemeToggleDrawableColors(sunDrawable, color);
        darkThemeView.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        darkThemeView.invalidate();
    }

    private int getThemeToggleCurrentFrame(boolean toDark) {
        return toDark ? sunDrawable.getFramesCount() - 1 : 0;
    }

    private int getThemeToggleEndFrame(boolean toDark) {
        return toDark ? sunDrawable.getFramesCount() : 0;
    }

    private void setThemeToggleStaticState(boolean toDark) {
        sunDrawable.stop();
        sunDrawable.setCurrentFrame(getThemeToggleCurrentFrame(toDark));
        sunDrawable.setCustomEndFrame(getThemeToggleEndFrame(toDark));
        darkThemeView.invalidate();
    }

    private void syncThemeToggle(boolean toDark, boolean animated) {
        if (sunDrawable == null || sunDrawable.getFramesCount() <= 0) {
            return;
        }
        int currentFrame = getThemeToggleCurrentFrame(toDark);
        int endFrame = getThemeToggleEndFrame(toDark);
        if (animated) {
            sunDrawable.setCustomEndFrame(endFrame);
            darkThemeView.playAnimation();
        } else {
            if (!isAttachedToWindow()) {
                setThemeToggleStaticState(toDark);
                return;
            }
            sunDrawable.stop();
            sunDrawable.setCurrentFrame(currentFrame, false, true);
            sunDrawable.setCustomEndFrame(currentFrame);
            darkThemeView.invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Drawable wallpaper = Theme.getCachedWallpaper();
        int backgroundKey = applyBackground(false);
        boolean useWallpaper = backgroundKey != Theme.key_chats_menuTopBackground
                && Theme.isCustomTheme()
                && !Theme.isPatternWallpaper()
                && wallpaper != null
                && !(wallpaper instanceof ColorDrawable)
                && !(wallpaper instanceof GradientDrawable);
        if (Theme.getActiveTheme() != null && Theme.getActiveTheme().isMonet() && !Theme.isCurrentThemeDark()) {
            useWallpaper = false;
        }

        int nameColor = Theme.getColor(Theme.key_chats_menuName);
        nameTextView.setTextColor(nameColor);
        phoneTextView.setTextColor(nameColor);

        boolean drawCatsShadow = !useWallpaper && Theme.hasThemeKey(Theme.key_chats_menuTopShadowCats);
        int shadowColor = drawCatsShadow
                ? Theme.getColor(Theme.key_chats_menuTopShadowCats)
                : (Theme.hasThemeKey(Theme.key_chats_menuTopShadow)
                    ? Theme.getColor(Theme.key_chats_menuTopShadow)
                    : (Theme.getServiceMessageColor() | 0xff000000));
        Drawable shadowDrawable = shadowView.getDrawable();
        if (shadowDrawable != null) {
            shadowDrawable.setColorFilter(new PorterDuffColorFilter(shadowColor, PorterDuff.Mode.MULTIPLY));
        }

        if (useWallpaper) {
            phoneTextView.setTextColor(Theme.getColor(Theme.key_chats_menuPhone));
            if (shadowView.getVisibility() != VISIBLE) {
                shadowView.setVisibility(VISIBLE);
            }
            if (wallpaper instanceof MotionBackgroundDrawable || wallpaper instanceof ColorDrawable || wallpaper instanceof GradientDrawable) {
                wallpaper.setBounds(0, 0, getMeasuredWidth(), getMeasuredHeight());
                wallpaper.draw(canvas);
            } else if (wallpaper instanceof BitmapDrawable bitmapDrawable) {
                if (bitmapDrawable.getBitmap() != null) {
                    float scaleX = (float) getMeasuredWidth() / bitmapDrawable.getBitmap().getWidth();
                    float scaleY = (float) getMeasuredHeight() / bitmapDrawable.getBitmap().getHeight();
                    float scale = Math.max(scaleX, scaleY);
                    int width = (int) (getMeasuredWidth() / scale);
                    int height = (int) (getMeasuredHeight() / scale);
                    int x = (bitmapDrawable.getBitmap().getWidth() - width) / 2;
                    int y = (bitmapDrawable.getBitmap().getHeight() - height) / 2;
                    srcRect.set(x, y, x + width, y + height);
                    destRect.set(0, 0, getMeasuredWidth(), getMeasuredHeight());
                    try {
                        canvas.drawBitmap(bitmapDrawable.getBitmap(), srcRect, destRect, backgroundPaint);
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                } else {
                    super.onDraw(canvas);
                }
            } else {
                wallpaper.setBounds(0, 0, getMeasuredWidth(), getMeasuredHeight());
                wallpaper.draw(canvas);
            }
        } else {
            int visibility = drawCatsShadow ? VISIBLE : INVISIBLE;
            if (shadowView.getVisibility() != visibility) {
                shadowView.setVisibility(visibility);
            }
            super.onDraw(canvas);
        }

        if (snowflakesEffect != null) {
            snowflakesEffect.onDraw(this, canvas);
        } else if (fireworksEffect != null) {
            fireworksEffect.onDraw(this, canvas);
        }
    }

    public void animateStateChange(long documentId) {
        animatedStatus.animateChange(ReactionsLayoutInBubble.VisibleReaction.fromCustomEmoji(documentId));
        updateRightDrawable = true;
    }

    public void getEmojiStatusLocation(Rect rect) {
        if (nameTextView.getRightDrawable() == null) {
            rect.set(nameTextView.getWidth() - 1, nameTextView.getHeight() / 2 - 1, nameTextView.getWidth() + 1, nameTextView.getHeight() / 2 + 1);
            return;
        }
        rect.set(nameTextView.getRightDrawable().getBounds());
        rect.offset((int) nameTextView.getX(), (int) nameTextView.getY());
        animatedStatus.translate(rect.centerX(), rect.centerY());
    }

    public AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable getEmojiStatusDrawable() {
        return status;
    }

    public Long getEmojiStatusGiftId() {
        return statusGiftId;
    }

    public View getEmojiStatusDrawableParent() {
        return nameTextView;
    }

    private void setArrowState(boolean animated) {
        float rotation = accountsShown ? 180.0f : 0.0f;
        if (animated) {
            arrowView.animate().rotation(rotation).setDuration(220).setInterpolator(CubicBezierInterpolator.EASE_OUT).start();
        } else {
            arrowView.animate().cancel();
            arrowView.setRotation(rotation);
        }
        arrowView.setContentDescription(accountsShown ? LocaleController.getString(R.string.AccDescrHideAccounts) : LocaleController.getString(R.string.AccDescrShowAccounts));
    }

    private void resetThemeTogglePressAnimation() {
        darkThemeBackgroundView.setPressed(false);
        darkThemeBackgroundView.setScaleX(1f);
        darkThemeBackgroundView.setScaleY(1f);
    }

    private void switchTheme(Theme.ThemeInfo themeInfo, boolean toDark) {
        int[] pos = new int[2];
        darkThemeBackgroundView.getLocationInWindow(pos);
        pos[0] += darkThemeBackgroundView.getMeasuredWidth() / 2;
        pos[1] += darkThemeBackgroundView.getMeasuredHeight() / 2;
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.needSetDayNightTheme, themeInfo, false, pos, -1, toDark, darkThemeView, null, null, false, null);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        status.attach();
        updateColors();
        if (!darkThemeView.isPlaying() && !DialogsActivity.switchingTheme) {
            syncThemeToggle(Theme.isCurrentThemeDark(), false);
        }
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.emojiLoaded);
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            NotificationCenter.getInstance(i).addObserver(this, NotificationCenter.currentUserPremiumStatusChanged);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        status.detach();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.emojiLoaded);
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            NotificationCenter.getInstance(i).removeObserver(this, NotificationCenter.currentUserPremiumStatusChanged);
        }
        if (lastAccount >= 0) {
            NotificationCenter.getInstance(lastAccount).removeObserver(this, NotificationCenter.userEmojiStatusUpdated);
            NotificationCenter.getInstance(lastAccount).removeObserver(this, NotificationCenter.updateInterfaces);
            lastAccount = -1;
        }
        if (nameTextView.getRightDrawable() instanceof AnimatedEmojiDrawable.WrapSizeDrawable) {
            Drawable drawable = ((AnimatedEmojiDrawable.WrapSizeDrawable) nameTextView.getRightDrawable()).getDrawable();
            if (drawable instanceof AnimatedEmojiDrawable) {
                ((AnimatedEmojiDrawable) drawable).removeView(nameTextView);
            }
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.emojiLoaded) {
            nameTextView.invalidate();
        } else if (id == NotificationCenter.userEmojiStatusUpdated) {
            setUser((TLRPC.User) args[0], accountsShown);
        } else if (id == NotificationCenter.currentUserPremiumStatusChanged) {
            setUser(UserConfig.getInstance(UserConfig.selectedAccount).getCurrentUser(), accountsShown);
        } else if (id == NotificationCenter.updateInterfaces) {
            int flags = (int) args[0];
            if ((flags & MessagesController.UPDATE_MASK_NAME) != 0
                    || (flags & MessagesController.UPDATE_MASK_AVATAR) != 0
                    || (flags & MessagesController.UPDATE_MASK_STATUS) != 0
                    || (flags & MessagesController.UPDATE_MASK_PHONE) != 0
                    || (flags & MessagesController.UPDATE_MASK_EMOJI_STATUS) != 0) {
                setUser(UserConfig.getInstance(UserConfig.selectedAccount).getCurrentUser(), accountsShown);
            }
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(148) + AndroidUtilities.statusBarHeight, MeasureSpec.EXACTLY)
        );
    }
}
