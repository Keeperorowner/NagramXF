package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.lerp;
import static org.telegram.messenger.LocaleController.getString;
import static org.telegram.ui.Components.Premium.LimitReachedBottomSheet.TYPE_ACCOUNTS;

import android.animation.Animator;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.ShapeDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextPaint;
import android.text.style.ReplacementSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.Insets;
import androidx.core.math.MathUtils;
import androidx.core.view.WindowInsetsCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.EdgeToEdgeSupportMode;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.MessageObject;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.messenger.AvatarCornerHelper;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.FolderDrawable;
import org.telegram.ui.Components.HintsController;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Premium.LimitReachedBottomSheet;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.BlurredBackgroundWithFadeDrawable;
import org.telegram.ui.Components.blur3.RenderNodeWithHash;
import org.telegram.ui.Components.blur3.capture.IBlur3Hash;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode;
import org.telegram.ui.Components.chat.ViewPositionWatcher;
import org.telegram.ui.Components.glass.GlassTabView;
import org.telegram.ui.Stories.recorder.HintView2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import me.vkryl.android.animator.BoolAnimator;
import me.vkryl.android.animator.FactorAnimator;
import tw.nekomimi.nekogram.folder.FolderIconHelper;
import tw.nekomimi.nekogram.helpers.AppRestartHelper;
import tw.nekomimi.nekogram.helpers.MainTabsHelper;
import tw.nekomimi.nekogram.helpers.PasscodeHelper;
import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.settings.GhostModeActivity;
import tw.nekomimi.nekogram.settings.MainTabsCustomizeActivity;
import tw.nekomimi.nekogram.settings.NekoSettingsActivity;
import tw.nekomimi.nekogram.utils.BrowserUtils;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.web.WebBrowserSettings;
import xyz.nextalone.nagram.NaConfig;

public class MainTabsActivity extends ViewPagerActivity implements NotificationCenter.NotificationCenterDelegate, FactorAnimator.Target {
    private static final int DEFAULT_PAGER_POSITION = 0;

    public static final int TABS_COUNT = 4;
    private static final int POSITION_CHATS = 0;
    private static final int POSITION_CONTACTS = 1;
    private static final int POSITION_CALLS_OR_SETTINGS = 2;
    private static final int POSITION_PROFILE = 3;

    private static final int INDEX_CHATS = 0;
    private static final int INDEX_CONTACTS = 1;
    private static final int INDEX_SETTINGS = 2;
    private static final int INDEX_CALLS = 3;
    private static final int INDEX_PROFILE = 4;

    private static int indexToPosition(int index) {
        return index > 2 ? index - 1 : index;
    }

    private static final int ANIMATOR_ID_TABS_VISIBLE = 0;
    private static final int ANIMATOR_ID_TABS_SCROLL_HIDE = 1;
    private final BoolAnimator animatorTabsVisible = new BoolAnimator(ANIMATOR_ID_TABS_VISIBLE,
        this, CubicBezierInterpolator.EASE_OUT_QUINT, 380, true);
    private final BoolAnimator animatorTabsScrollHide = new BoolAnimator(ANIMATOR_ID_TABS_SCROLL_HIDE,
        this, CubicBezierInterpolator.EASE_OUT_QUINT, 300, false);

    private IUpdateLayout updateLayout;
    private boolean dropCallsFragmentAfterPageScroll;

    private UpdateLayoutWrapper updateLayoutWrapper;
    private FrameLayout tabsViewWrapper;
    private LinearLayout tabsBarContainer;
    private MainTabsLayout tabsView;
    private BlurredBackgroundDrawable tabsViewBackground;
    private BlurredBackgroundDrawable searchTabButtonBackground;
    private View fadeView;
    private FrameLayout searchTabButton;
    private ArrayList<MainTabsConfigManager.TabState> configuredTabs = new ArrayList<>();
    private boolean lastBottomBarHidden = isBottomBarHidden();

    public MainTabsActivity() {
        super();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            iBlur3SourceTabGlass = new BlurredBackgroundSourceRenderNode(null);
            iBlur3SourceTabGlass.setupRenderer(new RenderNodeWithHash.Renderer() {
                @Override
                public void renderNodeCalculateHash(IBlur3Hash hash) {
                    hash.add(getThemedColor(Theme.key_windowBackgroundWhite));
                    hash.add(SharedConfig.chatBlurEnabled());

                    for (int a = 0, N = fragmentsArr.size(); a < N; a++) {
                        final FragmentState state = fragmentsArr.valueAt(a);
                        final BaseFragment fragment = state.fragment;
                        if (fragment.fragmentView == null) {
                            continue;
                        }
                        if (!ViewPositionWatcher.computeRectInParent(fragment.fragmentView, contentView, fragmentPosition)) {
                            continue;
                        }
                        if (fragmentPosition.right <= 0 || fragmentPosition.left >= fragmentView.getMeasuredWidth()) {
                            continue;
                        }

                        if (fragment instanceof TabFragmentDelegate) {
                            TabFragmentDelegate delegate = (TabFragmentDelegate) fragment;
                            BlurredBackgroundSourceRenderNode source = delegate.getGlassSource();
                            if (source != null) {
                                hash.addF(fragmentPosition.left);
                                hash.addF(fragmentPosition.top);
                                hash.add(fragment.getClassGuid());
                            }
                        }
                    }
                }

                @Override
                public void renderNodeUpdateDisplayList(Canvas canvas) {
                    final int width = fragmentView.getMeasuredWidth();
                    final int height = fragmentView.getMeasuredHeight();

                    canvas.drawColor(getThemedColor(Theme.key_windowBackgroundWhite));

                    for (int a = 0, N = fragmentsArr.size(); a < N; a++) {
                        final FragmentState state = fragmentsArr.valueAt(a);
                        final BaseFragment fragment = state.fragment;
                        if (fragment.fragmentView == null) {
                            continue;
                        }
                        if (!ViewPositionWatcher.computeRectInParent(fragment.fragmentView, contentView, fragmentPosition)) {
                            continue;
                        }
                        if (fragmentPosition.right <= 0 || fragmentPosition.left >= fragmentView.getMeasuredWidth()) {
                            continue;
                        }

                        if (fragment instanceof TabFragmentDelegate) {
                            TabFragmentDelegate delegate = (TabFragmentDelegate) fragment;
                            BlurredBackgroundSourceRenderNode source = delegate.getGlassSource();
                            if (source != null) {
                                canvas.save();
                                canvas.translate(fragmentPosition.left, fragmentPosition.top);
                                source.draw(canvas, 0, 0, width, height);
                                canvas.restore();
                            }
                        }
                    }
                }
            });
        } else {
            iBlur3SourceTabGlass = null;
        }

        iBlur3SourceColor = new BlurredBackgroundSourceColor();

        Bulletin.Delegate delegate = new Bulletin.Delegate() {
            @Override
            public int getBottomOffset(int tag) {
                return navigationBarHeight + dp(DialogsActivity.MAIN_TABS_HEIGHT + DialogsActivity.MAIN_TABS_MARGIN);
            }
        };

        Bulletin.addDelegate(this, delegate);
        Bulletin.addDelegate(contentView, delegate);
    }

    @Override
    protected FrameLayout createContentView(Context context) {
        return new FrameLayout(context) {
            @Override
            protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                super.onLayout(changed, left, top, right, bottom);
                checkUi_tabsPosition();
                checkUi_fadeView();
            }

            @Override
            protected void dispatchDraw(@NonNull Canvas canvas) {
                final int color = getEstBackgroundColor();
                if (insetLeft != 0) {
                    canvas.drawRect(0, 0, insetLeft, getHeight(), Theme.fillingPaint(color));
                }
                if (insetRight != 0) {
                    canvas.drawRect(getWidth() - insetRight, 0, getWidth(), getHeight(), Theme.fillingPaint(color));
                }

                super.dispatchDraw(canvas);
                blur3_invalidateBlur();
                blur3_updateFadeColors();
            }
        };
    }

    private int getEstBackgroundColor() {
        return ColorUtils.blendARGB(
                getThemedColor(Theme.key_windowBackgroundGray),
                getThemedColor(Theme.key_windowBackgroundWhite),
                viewPager != null ? viewPager.getPositionVisibility(0) : 1);
    }

    private boolean tabletLayout;
    public void updateLayout() {
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateLayout();
    }

    @Override
    public void onResume() {
        super.onResume();
        blur3_updateColors();
        checkContactsTabBadge();
        checkUnreadCount(true);

        showAccountChangeHint();
    }

    private void checkContactsTabBadge() {
        int contactsTabIndex = getTabIndex(MainTabsConfigManager.TabType.CONTACTS);
        if (tabsView != null && tabs != null && contactsTabIndex >= 0 && contactsTabIndex < tabs.length && tabs[contactsTabIndex] != null) {
            final boolean hasPermission = Build.VERSION.SDK_INT >= 23 && ContactsController.hasContactsPermission();
            if (hasPermission) {
                MessagesController.getGlobalNotificationsSettings().edit().putBoolean("askAboutContacts2", true).apply();
            }
            if (Build.VERSION.SDK_INT >= 23 && UserConfig.getInstance(currentAccount).syncContacts && !hasPermission && MessagesController.getGlobalNotificationsSettings().getBoolean("askAboutContacts2", true)) {
                tabs[contactsTabIndex].setCounter("!", true, true);
            } else {
                tabs[contactsTabIndex].setCounter(null, true, true);
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (accountSwitchHint != null) {
            accountSwitchHint.hide();
        }
    }

    @Override
    public View createView(Context context) {
        super.createView(context);
        tabletLayout = false;

        final int mainTabsMargin = MainTabsHelper.getMainTabsMargin();

        tabsView = new MainTabsLayout(context, resourceProvider);
        tabsView.setEqualWidthWhenTitlesVisible(true);
        tabsView.setClipChildren(false);
        final int paddingH = dp(mainTabsMargin + 4);
        final int paddingV = dp(mainTabsMargin + 4);
        tabsView.setPadding(paddingH, paddingV, paddingH, paddingV);
        tabsView.setMaxWidth(dp(328 + mainTabsMargin * 2));

        rebuildTabs();

        selectTab(viewPager.getCurrentPosition(), false);

        iBlur3SourceColor.setColor(getThemedColor(Theme.key_windowBackgroundWhite));

        final ViewPositionWatcher viewPositionWatcher = new ViewPositionWatcher(contentView);

        BlurredBackgroundDrawableViewFactory iBlur3FactoryGlass = new BlurredBackgroundDrawableViewFactory(iBlur3SourceTabGlass != null ? iBlur3SourceTabGlass : iBlur3SourceColor);
        iBlur3FactoryGlass.setSourceRootView(viewPositionWatcher, contentView);
        iBlur3FactoryGlass.setLiquidGlassEffectAllowed(LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS));

        tabsViewBackground = iBlur3FactoryGlass.create(tabsView, BlurredBackgroundProviderImpl.mainTabs(resourceProvider));
        tabsViewBackground.setRadius(dp(MainTabsHelper.getMainTabsHeight() / 2f));
        tabsViewBackground.setPadding(dp(mainTabsMargin - 0.334f));
        tabsView.setBackground(tabsViewBackground);

        BlurredBackgroundDrawableViewFactory iBlur3FactoryFade = new BlurredBackgroundDrawableViewFactory(iBlur3SourceColor);
        iBlur3FactoryFade.setSourceRootView(viewPositionWatcher, contentView);

        fadeView = new View(context);
        BlurredBackgroundWithFadeDrawable fadeDrawable = new BlurredBackgroundWithFadeDrawable(iBlur3FactoryFade.create(fadeView, null));
        fadeDrawable.setFadeHeight(dp(60), true);
        fadeView.setBackground(fadeDrawable);

        contentView.addView(fadeView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 0, Gravity.BOTTOM));

        tabsViewWrapper = new FrameLayout(context);
        tabsViewWrapper.setOnClickListener(v -> {});
        tabsViewWrapper.setClipChildren(false);
        tabsViewWrapper.setClipToPadding(false);

        tabsBarContainer = new LinearLayout(context);
        tabsBarContainer.setOrientation(LinearLayout.HORIZONTAL);
        tabsBarContainer.setGravity(Gravity.CENTER_VERTICAL | Gravity.CENTER_HORIZONTAL);
        tabsBarContainer.setClipChildren(false);
        tabsBarContainer.setClipToPadding(false);
        tabsBarContainer.setPadding(dp(2), 0, dp(2), 0);
        tabsView.setTranslationX(0f);
        tabsBarContainer.addView(tabsView, LayoutHelper.createLinear(dp(MainTabsHelper.getTabsViewWidth()), DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS));

        searchTabButton = new FrameLayout(context);
        searchTabButton.setClipChildren(false);
        searchTabButton.setClipToPadding(false);
        searchTabButtonBackground = iBlur3FactoryGlass.create(searchTabButton, BlurredBackgroundProviderImpl.mainTabs(resourceProvider));
        searchTabButtonBackground.setRadius(dp(28));
        searchTabButtonBackground.setPadding(dp(0.334f));
        searchTabButton.setBackground(searchTabButtonBackground);

        ImageView searchIcon = new ImageView(context);
        searchIcon.setImageResource(R.drawable.outline_header_search);
        searchIcon.setPadding(dp(14), dp(14), dp(14), dp(14));
        searchIcon.setColorFilter(new PorterDuffColorFilter(
            Theme.getColor(Theme.key_glass_tabUnselected, resourceProvider), PorterDuff.Mode.SRC_IN));
        searchTabButton.addView(searchIcon, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        searchTabButton.setClickable(true);
        searchTabButton.setContentDescription(getString(R.string.Search));
        searchTabButton.setOnClickListener(v -> onSearchTabButtonClicked());
        searchTabButton.setOnLongClickListener(v -> {
            onSearchTabButtonLongClicked();
            return true;
        });
        int searchBtnSize = dp(56);
        LinearLayout.LayoutParams searchBtnLp = new LinearLayout.LayoutParams(searchBtnSize, searchBtnSize);
        searchBtnLp.setMarginStart(-dp(10));
        searchBtnLp.setMarginEnd(dp(4));
        tabsBarContainer.addView(searchTabButton, searchBtnLp);
        tabsViewWrapper.addView(tabsBarContainer, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));

        tabsViewWrapper.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
                repositionSearchButton();
            }
        });

        contentView.addView(tabsViewWrapper, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));

        updateLayoutWrapper = new UpdateLayoutWrapper(context);
        contentView.addView(updateLayoutWrapper, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));

        updateLayout = ApplicationLoader.applicationLoaderInstance.takeUpdateLayout(getParentActivity(), updateLayoutWrapper);
        if (updateLayout != null) {
            updateLayout.updateAppUpdateViews(currentAccount, false);
        }

        updateLayout();
        checkUnreadCount(false);
        updateSearchTabButtonVisibility();
        return contentView;
    }

    private void checkUnreadCount(boolean animated) {
        if (tabsView == null || tabs == null) {
            return;
        }

        int chatsTabIndex = getTabIndex(MainTabsConfigManager.TabType.CHATS);
        if (chatsTabIndex < 0 || chatsTabIndex >= tabs.length || tabs[chatsTabIndex] == null) {
            return;
        }

        final int unreadCount = MessagesStorage.getInstance(currentAccount).getMainUnreadCount();
        if (unreadCount > 0) {
            final String unreadCountFmt = LocaleController.formatNumber(unreadCount, ',');
            tabs[chatsTabIndex].setCounter(unreadCountFmt, false, animated);
        } else {
            tabs[chatsTabIndex].setCounter(null, false, animated);
        }
    }

    private boolean isBottomBarHidden() {
        return NaConfig.INSTANCE.getMainTabsDisplayMode().Int() == MainTabsHelper.BOTTOM_BAR_MODE_HIDE;
    }

    private boolean shouldUseMainTabsPadding() {
        return !isBottomBarHidden();
    }

    private int getVisibleBottomBarOffset() {
        return shouldUseMainTabsPadding()
            ? dp(MainTabsHelper.getMainTabsHeight() + MainTabsHelper.getMainTabsMargin())
            : 0;
    }

    private boolean hasUnmutedUnreadDialogs(MessagesController.DialogFilter folder) {
        final MessagesController messagesController = getMessagesController();
        final ArrayList<TLRPC.Dialog> dialogs = folder.isDefault()
                ? messagesController.getDialogs(0)
                : messagesController.getAllDialogs();
        for (int i = 0; i < dialogs.size(); i++) {
            final TLRPC.Dialog dialog = dialogs.get(i);
            if (!folder.isDefault()) {
                long dialogId = dialog.id;
                if (DialogObject.isEncryptedDialog(dialogId)) {
                    final TLRPC.EncryptedChat encryptedChat = messagesController.getEncryptedChat(DialogObject.getEncryptedChatId(dialogId));
                    if (encryptedChat != null) {
                        dialogId = encryptedChat.user_id;
                    }
                }
                if (!folder.includesDialog(getAccountInstance(), dialogId, dialog)) {
                    continue;
                }
            }
            if ((messagesController.getDialogUnreadCount(dialog) > 0 || dialog.unread_mark)
                    && !messagesController.isDialogMuted(dialog.id, 0)) {
                return true;
            }
        }
        return false;
    }

    private class FolderCounterSpan extends ReplacementSpan {

        private static final float HEIGHT_DP = 17.333f;
        private final String count;
        private final boolean hasUnmutedUnreadDialogs;
        private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float counterWidth;

        FolderCounterSpan(int count, boolean hasUnmutedUnreadDialogs) {
            this.count = String.valueOf(count);
            this.hasUnmutedUnreadDialogs = hasUnmutedUnreadDialogs;
            textPaint.setTextSize(AndroidUtilities.dpf2(11));
            textPaint.setTypeface(AndroidUtilities.bold());
            counterWidth = Math.max(dp(HEIGHT_DP - 10), textPaint.measureText(this.count)) + dp(10);
        }

        @Override
        public int getSize(@NonNull Paint paint, CharSequence text, int start, int end, @Nullable Paint.FontMetricsInt fm) {
            return (int) Math.ceil(dp(5) + counterWidth);
        }

        @Override
        public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, @NonNull Paint paint) {
            final float left = x + dp(5);
            final float centerY = (top + bottom) / 2f + dp(1);
            final float halfHeight = dp(HEIGHT_DP) / 2f;
            backgroundPaint.setColor(getThemedColor(
                hasUnmutedUnreadDialogs ?
                    Theme.key_featuredStickers_addButton :
                    Theme.key_chats_tabUnreadUnactiveBackground
            ));
            textPaint.setColor(getThemedColor(Theme.key_actionBarDefault));
            AndroidUtilities.rectTmp.set(left, centerY - halfHeight, left + counterWidth, centerY + halfHeight);
            canvas.drawRoundRect(AndroidUtilities.rectTmp, halfHeight, halfHeight, backgroundPaint);
            final Paint.FontMetrics fontMetrics = textPaint.getFontMetrics();
            final float baseline = centerY - (fontMetrics.ascent + fontMetrics.descent) / 2f;
            canvas.drawText(count, left + (counterWidth - textPaint.measureText(count)) / 2f, baseline, textPaint);
        }
    }

    public boolean openAccountSelector(View button) {
        final ArrayList<Integer> accountNumbers = new ArrayList<>();

        accountNumbers.clear();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (PasscodeHelper.isAccountHidden(a)) continue;
            if (UserConfig.getInstance(a).isClientActivated()) {
                accountNumbers.add(a);
            }
        }
        Collections.sort(accountNumbers, (o1, o2) -> {
            long l1 = UserConfig.getInstance(o1).loginTime;
            long l2 = UserConfig.getInstance(o2).loginTime;
            if (l1 > l2) {
                return 1;
            } else if (l1 < l2) {
                return -1;
            }
            return 0;
        });

        ItemOptions o = ItemOptions.makeOptions(this, button);
        if (UserConfig.getActivatedAccountsCount() < UserConfig.MAX_ACCOUNT_COUNT) {
            o.add(R.drawable.msg_addbot, getString(R.string.AddAccount), () -> {
                int freeAccounts = 0;
                Integer availableAccount = null;
                for (int a = UserConfig.MAX_ACCOUNT_COUNT - 1; a >= 0; a--) {
                    if (!UserConfig.getInstance(a).isClientActivated()) {
                        freeAccounts++;
                        if (availableAccount == null) {
                            availableAccount = a;
                        }
                    }
                }
                if (!UserConfig.hasPremiumOnAccounts()) {
                    freeAccounts -= (UserConfig.MAX_ACCOUNT_COUNT - UserConfig.MAX_ACCOUNT_DEFAULT_COUNT);
                }
                if (freeAccounts > 0 && availableAccount != null) {
                    presentFragment(new LoginActivity(availableAccount));
                } else if (!UserConfig.hasPremiumOnAccounts()) {
                    showDialog(new LimitReachedBottomSheet(this, getContext(), TYPE_ACCOUNTS, currentAccount, null));
                }
            });
        }

        if (BuildVars.DEBUG_PRIVATE_VERSION) {
            o.add(R.drawable.menu_download_round, "Dump Canvas", () -> AndroidUtilities.runOnUIThread(this::dumpCanvas, 1000));
        }

        if (accountNumbers.size() > 0) {
            if (o.getItemsCount() > 0) o.addGap();
            for (int acc : accountNumbers) {
                final int account = acc;
                final View btn = accountView(acc, currentAccount == acc);
                btn.setOnClickListener(v -> {
                    if (currentAccount == account) return;
                    o.dismiss();
                    if (LaunchActivity.instance != null) {
                        LaunchActivity.instance.switchToAccount(account, true);
                    }
                });
                o.addView(btn, LayoutHelper.createLinear(230, 48));
            }
        }

        setupPopupMenuStyle(o);
        o.show();

        HintsController.Hint.AccountSwitchHint.doNotShowAgain();

        return true;
    }

    public LinearLayout accountView(int account, boolean selected) {
        final LinearLayout btn = new LinearLayout(getContext());
        btn.setOrientation(LinearLayout.HORIZONTAL);
        btn.setBackground(Theme.createRadSelectorDrawable(getThemedColor(Theme.key_listSelector), 0, 0));

        final TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();

        final AvatarDrawable avatarDrawable = new AvatarDrawable();
        avatarDrawable.setInfo(user);

        final FrameLayout avatarContainer = new FrameLayout(getContext()) {
            private final Paint selectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override
            protected void dispatchDraw(@NonNull Canvas canvas) {
                if (selected) {
                    selectedPaint.setStyle(Paint.Style.STROKE);
                    selectedPaint.setStrokeWidth(dp(1.33f));
                    selectedPaint.setColor(getThemedColor(Theme.key_featuredStickers_addButton));
                    canvas.drawCircle(getWidth() / 2.0f, getHeight() / 2.0f, dp(16), selectedPaint);
                }
                super.dispatchDraw(canvas);
            }
        };
        btn.addView(avatarContainer, LayoutHelper.createLinear(34, 34, Gravity.CENTER_VERTICAL, 12, 0, 0, 0));

        final BackupImageView avatarView = new BackupImageView(getContext());
        if (selected) {
            avatarView.setScaleX(0.833f);
            avatarView.setScaleY(0.833f);
        }
        avatarView.setRoundRadius(AvatarCornerHelper.getAvatarRoundRadius(32.0f));
        avatarView.getImageReceiver().setCurrentAccount(account);
        avatarView.setForUserOrChat(user, avatarDrawable);
        avatarContainer.addView(avatarView, LayoutHelper.createLinear(32, 32, Gravity.CENTER, 1, 1, 1, 1));

        final TextView textView = new TextView(getContext());
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        textView.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        textView.setText(UserObject.getUserName(user));
        textView.setMaxLines(2);
        textView.setEllipsize(TextUtils.TruncateAt.END);
        btn.addView(textView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 13, 0, 14, 0));

        return btn;
    }

    @Override
    protected void onViewPagerScrollEnd() {
        if (tabsView != null) {
            selectTab(viewPager.getCurrentPosition(), true);
            setGestureSelectedOverride(0, false);
        }
        blur3_invalidateBlur();

        if (viewPager != null) {
            final int currentPosition = viewPager.getCurrentPosition();
            if (dropCallsFragmentAfterPageScroll) {
                int callsTabPosition = getTabIndex(MainTabsConfigManager.TabType.CALLS);
                if (callsTabPosition >= 0 && currentPosition != callsTabPosition) {
                    dropFragmentAtPosition(callsTabPosition);
                    dropCallsFragmentAfterPageScroll = false;
                }
            }
            int profileTabPosition = getTabIndex(MainTabsConfigManager.TabType.PROFILE);
            if (profileTabPosition >= 0 && currentPosition != profileTabPosition) {
                dropFragmentAtPosition(profileTabPosition);
            }
        }
    }

    @Override
    protected void onViewPagerTabAnimationUpdate(boolean manual) {
        final boolean isDragByGesture = !manual;

        if (tabsView != null) {
            final float position = viewPager.getPositionAnimated();
            setGestureSelectedOverride(position, isDragByGesture);
            if (isDragByGesture) {
                selectTab(Math.round(position), true);
            }
        }

        checkUi_fadeView();
        blur3_invalidateBlur();
        contentView.invalidate();
    }

    @Override
    protected int getFragmentsCount() {
        ensureConfiguredTabsLoaded();
        return Math.max(1, configuredTabs.size());
    }

    @Override
    protected int getStartPosition() {
        ensureConfiguredTabsLoaded();
        return getPreferredStartPosition();
    }

    private DialogsActivity dialogsActivity;

    @Override
    public boolean onBackPressed(boolean invoked) {
        final boolean result = super.onBackPressed(invoked);
        if (result) {
            final int startPosition = getStartPosition();
            if (viewPager.getCurrentPosition() != startPosition) {
                if (invoked) {
                    viewPager.scrollToPosition(startPosition);
                }
                return false;
            }
        }
        return result;
    }

    public DialogsActivity prepareDialogsActivity(Bundle bundle) {
        if (bundle == null) {
            bundle = new Bundle();
        }

        ensureConfiguredTabsLoaded();
        bundle.putBoolean("hasMainTabs", shouldUseMainTabsPadding());
        dialogsActivity = new DialogsActivity(bundle);
        dialogsActivity.setMainTabsActivityController(new MainTabsActivityControllerImpl());
        dialogsActivity.setMainTabsScrollHideProgress(animatorTabsScrollHide.getFloatValue());
        putFragmentAtPosition(getPreferredStartPositionFor(MainTabsConfigManager.TabType.CHATS), dialogsActivity);
        return dialogsActivity;
    }

    @Override
    protected BaseFragment createBaseFragmentAt(int position) {
        ensureConfiguredTabsLoaded();
        position = getSafePagerPosition(position);
        return createFragmentForTab(getTabTypeByPosition(position));
    }

    private BaseFragment createFragmentForTab(MainTabsConfigManager.TabType tabType) {
        switch (tabType) {
            case CONTACTS: {
                Bundle args = new Bundle();
                args.putBoolean("needPhonebook", true);
                args.putBoolean("needFinishFragment", false);
                args.putBoolean("hasMainTabs", shouldUseMainTabsPadding());
                return new ContactsActivity(args);
            }
            case SETTINGS: {
                Bundle args = new Bundle();
                args.putBoolean("hasMainTabs", shouldUseMainTabsPadding());
                return new SettingsActivity(args);
            }
            case CALLS: {
                Bundle args = new Bundle();
                args.putBoolean("needFinishFragment", false);
                args.putBoolean("hasMainTabs", shouldUseMainTabsPadding());
                return new CallLogActivity(args);
            }
            case PROFILE: {
                Bundle args = new Bundle();
                args.putLong("user_id", UserConfig.getInstance(currentAccount).getClientUserId());
                args.putBoolean("my_profile", true);
                args.putBoolean("hasMainTabs", shouldUseMainTabsPadding());
                return new ProfileActivity(args);
            }
            case CHATS: {
                Bundle args = new Bundle();
                args.putBoolean("hasMainTabs", shouldUseMainTabsPadding());
                dialogsActivity = new DialogsActivity(args);
                dialogsActivity.setMainTabsActivityController(new MainTabsActivityControllerImpl());
                dialogsActivity.setMainTabsScrollHideProgress(animatorTabsScrollHide.getFloatValue());
                return dialogsActivity;
            }
        }
        return createFragmentForTab(MainTabsConfigManager.TabType.CHATS);
    }

    public DialogsActivity getDialogsActivity() {
        return dialogsActivity;
    }

    @Override
    public void clearViews() {
        configuredTabs = MainTabsConfigManager.getEnabledTabs();
        dropCallsFragmentAfterPageScroll = false;
        super.clearViews();
    }

    public GlassTabView[] tabs;

    public void selectTab(int position, boolean animated) {
        if (tabs == null || configuredTabs == null) {
            return;
        }
        for (int a = 0; a < tabs.length; a++) {
            GlassTabView tab = tabs[a];
            tab.setSelected(a == position, animated);
        }
    }

    public void setGestureSelectedOverride(float animatedPosition, boolean allow) {
        if (tabs == null || configuredTabs == null) {
            return;
        }
        for (int index = 0; index < tabs.length; index++) {
            final float visibility = Math.max(0, 1f - Math.abs(index - animatedPosition));
            tabs[index].setGestureSelectedOverride(visibility, allow);
        }
        tabsView.invalidate();
    }

    private void ensureConfiguredTabsLoaded() {
        if (configuredTabs == null || configuredTabs.isEmpty()) {
            configuredTabs = MainTabsConfigManager.getEnabledTabs();
        }
    }

    private MainTabsConfigManager.TabType getTabTypeByPosition(int position) {
        if (position >= 0 && position < configuredTabs.size()) {
            return configuredTabs.get(position).type;
        }
        return MainTabsConfigManager.TabType.CHATS;
    }

    private int getTabIndex(MainTabsConfigManager.TabType type) {
        for (int i = 0; i < configuredTabs.size(); i++) {
            if (configuredTabs.get(i).type == type) {
                return i;
            }
        }
        return -1;
    }

    private int getSafePagerPosition(int position) {
        if (configuredTabs == null || configuredTabs.isEmpty()) {
            return DEFAULT_PAGER_POSITION;
        }
        return MathUtils.clamp(position, 0, configuredTabs.size() - 1);
    }

    private int getPreferredStartPosition() {
        int chatsPosition = getTabIndex(MainTabsConfigManager.TabType.CHATS);
        if (chatsPosition >= 0) {
            return chatsPosition;
        }
        return configuredTabs.isEmpty() ? DEFAULT_PAGER_POSITION : 0;
    }

    private int getPreferredStartPositionFor(MainTabsConfigManager.TabType preferredType) {
        int preferredPosition = getTabIndex(preferredType);
        if (preferredPosition >= 0) {
            return preferredPosition;
        }
        return getPreferredStartPosition();
    }

    private static boolean isSameTabsLayout(List<MainTabsConfigManager.TabState> first, List<MainTabsConfigManager.TabState> second) {
        if (first == second) {
            return true;
        }
        if (first == null || second == null || first.size() != second.size()) {
            return false;
        }
        for (int i = 0; i < first.size(); i++) {
            if (first.get(i).type != second.get(i).type) {
                return false;
            }
        }
        return true;
    }

    private void dropAllTabFragments() {
        for (int i = fragmentsArr.size() - 1; i >= 0; i--) {
            dropFragmentAtPosition(fragmentsArr.keyAt(i));
        }
        dialogsActivity = null;
    }

    private void rebuildTabs() {
        if (tabsView == null) {
            return;
        }

        ensureConfiguredTabsLoaded();

        MainTabsConfigManager.TabType selectedType = MainTabsConfigManager.TabType.CHATS;
        if (viewPager != null && !configuredTabs.isEmpty()) {
            selectedType = getTabTypeByPosition(getSafePagerPosition(viewPager.getCurrentPosition()));
        }

        ArrayList<MainTabsConfigManager.TabState> newTabs = MainTabsConfigManager.getEnabledTabs();
        boolean layoutChanged = !isSameTabsLayout(configuredTabs, newTabs);
        boolean bottomBarHidden = isBottomBarHidden();
        boolean hiddenStateChanged = lastBottomBarHidden != bottomBarHidden;
        lastBottomBarHidden = bottomBarHidden;
        configuredTabs = newTabs;

        if (NaConfig.INSTANCE.getMainTabsDisplayMode().Int() != MainTabsHelper.BOTTOM_BAR_MODE_FLOATING) {
            animatorTabsScrollHide.setValue(false, false);
        }

        int targetPosition = getSafePagerPosition(getPreferredStartPositionFor(selectedType));

        if (viewPager != null) {
            if (layoutChanged || hiddenStateChanged) {
                dropAllTabFragments();
                viewPager.rebuild(false);
            }
            if (viewPager.getCurrentPosition() != targetPosition) {
                viewPager.setPosition(targetPosition);
            }
        }

        tabsView.removeAllViews();
        tabs = new GlassTabView[configuredTabs.size()];

        for (int index = 0; index < configuredTabs.size(); index++) {
            final int tabIndex = index;
            final MainTabsConfigManager.TabType type = configuredTabs.get(index).type;
            final int position = index;

            GlassTabView tabView = MainTabsConfigManager.createTabView(getContext(), resourceProvider, currentAccount, type, false);
            tabsView.addTabToIgnoreClick(tabView);
            tabView.setOnClickListener(v -> {
                if (viewPager.isManualScrolling() || viewPager.isTouch()) {
                    return;
                }

                if (viewPager.getCurrentPosition() == position) {
                    final BaseFragment fragment = getCurrentVisibleFragment();
                    if (fragment instanceof MainTabsActivity.TabFragmentDelegate) {
                        ((MainTabsActivity.TabFragmentDelegate) fragment).onParentScrollToTop();
                    }
                    return;
                }

                selectTab(position, true);
                viewPager.scrollToPosition(position);
            });
            tabView.setOnLongClickListener(v -> processLongClick(v, type));

            tabs[tabIndex] = tabView;
            tabsView.addView(tabView);
            tabsView.setViewVisible(tabView, true, false);
        }

        int selectedPosition = viewPager != null ? getSafePagerPosition(viewPager.getCurrentPosition()) : targetPosition;
        selectTab(selectedPosition, false);
        tabsView.requestLayout();
        checkUnreadCount(false);
        checkContactsTabBadge();
        if (hiddenStateChanged && fragmentView != null) {
            fragmentView.requestApplyInsets();
        }
        if (updateLayoutWrapper != null) {
            checkUi_tabsPosition();
        }
        if (fadeView != null) {
            checkUi_fadeView();
        }
        updateSearchTabButtonVisibility();
    }

    public interface TabFragmentDelegate {
        default boolean canParentTabsSlide(MotionEvent ev, boolean forward) {
            return false;
        }

        default void onParentScrollToTop() {

        }

        default BlurredBackgroundSourceRenderNode getGlassSource() {
            return null;
        }

        default void onSearchButtonClicked() {

        }

        default boolean hasSearch() {
            return false;
        }
    }

    @Override
    protected boolean canScrollForward(MotionEvent ev) {
        return canScrollInternal(ev, true);
    }

    @Override
    protected boolean canScrollBackward(MotionEvent ev) {
        return canScrollInternal(ev, false);
    }

    private boolean canScrollInternal(MotionEvent ev, boolean forward) {
        if (isBottomBarHidden()) {
            return false;
        }

        final BaseFragment fragment = getCurrentVisibleFragment();
        if (fragment instanceof TabFragmentDelegate) {
            final TabFragmentDelegate delegate = (TabFragmentDelegate) fragment;
            return delegate.canParentTabsSlide(ev, forward);
        }

        return false;
    }

    private int navigationBarHeight;
    private int insetLeft;
    private int insetRight;

    @NonNull
    @Override
    protected WindowInsetsCompat onApplyWindowInsets(@NonNull View v, @NonNull WindowInsetsCompat insets) {
        final Insets systemInsets = AndroidUtilities.getDefaultWindowInsets(insets, false);

        insetLeft = systemInsets.left;
        insetRight = systemInsets.right;

        navigationBarHeight = systemInsets.bottom;
        final boolean isUpdateLayoutVisible = updateLayoutWrapper.isUpdateLayoutVisible();
        final int updateLayoutHeight = isUpdateLayoutVisible ? dp(UpdateLayoutWrapper.HEIGHT) : 0;
        updateLayoutWrapper.setPadding(0, 0, 0, navigationBarHeight);

        ViewGroup.MarginLayoutParams lp;
        {
            final int height = shouldUseMainTabsPadding()
                ? navigationBarHeight + updateLayoutHeight + dp(MainTabsHelper.getMainTabsHeightWithMargins())
                : 0;
            lp = (ViewGroup.MarginLayoutParams) fadeView.getLayoutParams();
            if (lp.height != height) {
                lp.height = height;
                fadeView.setLayoutParams(lp);
            }
        }
        {
            int bottomMargin = isUpdateLayoutVisible ? (navigationBarHeight + updateLayoutHeight) : 0;
            if (tabletLayout) {
                bottomMargin = Math.max(bottomMargin, navigationBarHeight + dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS));
            }
            lp = (ViewGroup.MarginLayoutParams) viewPager.getLayoutParams();
            if (lp.bottomMargin != bottomMargin || lp.leftMargin != systemInsets.left || lp.rightMargin != systemInsets.right) {
                lp.leftMargin = systemInsets.left;
                lp.rightMargin = systemInsets.right;
                lp.bottomMargin = bottomMargin;
                viewPager.setLayoutParams(lp);
            }
        }

        tabsViewWrapper.setPadding(systemInsets.left, 0, systemInsets.right, navigationBarHeight);

        final WindowInsetsCompat consumed = isUpdateLayoutVisible ?
            insets.inset(0, 0, 0, navigationBarHeight) : insets;

        checkUi_tabsPosition();
        checkUi_fadeView();

        return super.onApplyWindowInsets(v, consumed);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.notificationsCountUpdated || id == NotificationCenter.updateInterfaces) {
            checkUnreadCount(fragmentView != null && fragmentView.isAttachedToWindow());
        } else if (id == NotificationCenter.appUpdateLoading) {
            if (updateLayout != null) {
                updateLayout.updateFileProgress(null);
                updateLayout.updateAppUpdateViews(currentAccount, true);
            }
        } else if (id == NotificationCenter.fileLoaded) {
            String path = (String) args[0];
            if (SharedConfig.isAppUpdateAvailable()) {
                String name = FileLoader.getAttachFileName(SharedConfig.pendingAppUpdate.document);
                if (name.equals(path) && updateLayout != null) {
                    updateLayout.updateAppUpdateViews(currentAccount, true);
                }
            }
        } else if (id == NotificationCenter.fileLoadFailed) {
            String path = (String) args[0];
            if (SharedConfig.isAppUpdateAvailable()) {
                String name = FileLoader.getAttachFileName(SharedConfig.pendingAppUpdate.document);
                if (name.equals(path) && updateLayout != null) {
                    updateLayout.updateAppUpdateViews(currentAccount, true);
                }
            }
        } else if (id == NotificationCenter.fileLoadProgressChanged) {
            if (updateLayout != null) {
                updateLayout.updateFileProgress(args);
            }
        } else if (id == NotificationCenter.appUpdateAvailable) {
            if (updateLayout != null && LaunchActivity.instance != null) {
                updateLayout.updateAppUpdateViews(currentAccount, LaunchActivity.instance.getMainFragmentsStackSize() == 1);
            }
        } else if (id == NotificationCenter.needSetDayNightTheme) {
            clearAllHiddenFragments();
        } else if (id == NotificationCenter.callTabsVisibleToggled) {
            checkUi_callTabVisible(getUserConfig().showCallsTab, true);
        } else if (id == NotificationCenter.mainUserInfoChanged) {
            int profileTabIndex = getTabIndex(MainTabsConfigManager.TabType.PROFILE);
            if (tabs != null && profileTabIndex >= 0 && profileTabIndex < tabs.length && tabs[profileTabIndex] != null) {
                tabs[profileTabIndex].updateUserAvatar(currentAccount);
            }
        } else if (id == NotificationCenter.mainTabsLayoutChanged) {
            rebuildTabs();
        } else if (id == NotificationCenter.contactsPermissionBadgeCheck) {
            checkContactsTabBadge();
        }
    }

    private NotificationCenter.ObserversGroup observersGroup;
    private NotificationCenter.ObserversGroup globalObserversGroup;

    @Override
    public boolean onFragmentCreate() {
        configuredTabs = MainTabsConfigManager.getEnabledTabs();

        observersGroup = NotificationCenter.getInstance(currentAccount).createObserversGroup(this)
            .add(NotificationCenter.fileLoaded)
            .add(NotificationCenter.fileLoadProgressChanged)
            .add(NotificationCenter.fileLoadFailed)
            .add(NotificationCenter.notificationsCountUpdated)
            .add(NotificationCenter.updateInterfaces)
            .add(NotificationCenter.callTabsVisibleToggled)
            .add(NotificationCenter.mainUserInfoChanged)
            .add(NotificationCenter.contactsPermissionBadgeCheck);

        globalObserversGroup = NotificationCenter.getGlobalInstance().createObserversGroup(this)
            .add(NotificationCenter.appUpdateAvailable)
            .add(NotificationCenter.appUpdateLoading)
            .add(NotificationCenter.needSetDayNightTheme)
            .add(NotificationCenter.mainTabsLayoutChanged);

        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        Bulletin.removeDelegate(this);
        Bulletin.removeDelegate(contentView);

        if (observersGroup != null) {
            observersGroup.removeAllObservers();
            observersGroup = null;
        }
        if (globalObserversGroup != null) {
            globalObserversGroup.removeAllObservers();
            globalObserversGroup = null;
        }
        super.onFragmentDestroy();
    }

    @Override
    public void onFactorChanged(int id, float factor, float fraction, FactorAnimator callee) {
        if (id == ANIMATOR_ID_TABS_VISIBLE || id == ANIMATOR_ID_TABS_SCROLL_HIDE) {
            checkUi_tabsPosition();
            checkUi_fadeView();
            if (dialogsActivity != null) {
                dialogsActivity.setMainTabsScrollHideProgress(animatorTabsScrollHide.getFloatValue());
            }
        }
    }

    private void checkUi_fadeView() {
        if (viewPager == null || fadeView == null) {
            return;
        }
        if (isBottomBarHidden()) {
            fadeView.setAlpha(0f);
            fadeView.setVisibility(View.GONE);
            return;
        }

        final float animatedPosition = viewPager.getPositionAnimated();
        final int profilePosition = getTabIndex(MainTabsConfigManager.TabType.PROFILE);
        final float isProfile = profilePosition >= 0
            ? 1f - MathUtils.clamp(Math.abs(profilePosition - animatedPosition), 0, 1)
            : 0f;
        final float hide = 1f - AndroidUtilities.getNavigationBarThirdButtonsFactor(0, 1f, navigationBarHeight);
        final float scrollHideFactor = animatorTabsScrollHide.getFloatValue();
        float alpha = (1f - isProfile * hide) * animatorTabsVisible.getFloatValue() * (1f - scrollHideFactor);
        if (tabletLayout) {
            alpha = 0.0f;
        }

        fadeView.setAlpha(alpha);
        fadeView.setTranslationY(isProfile * dp(48));
        fadeView.setVisibility(alpha > 0 ? View.VISIBLE : View.GONE);
    }

    private void checkUi_tabsPosition() {
        if (tabsView == null) return;
        if (isBottomBarHidden()) {
            if (tabsViewWrapper != null) {
                tabsViewWrapper.setVisibility(View.GONE);
            }
            tabsView.setClickable(false);
            tabsView.setEnabled(false);
            tabsView.setAlpha(0f);
            tabsView.setVisibility(View.GONE);
            if (searchTabButton != null) {
                searchTabButton.setVisibility(View.GONE);
            }
            return;
        }
        final boolean isUpdateLayoutVisible = updateLayoutWrapper.isUpdateLayoutVisible();
        final int updateLayoutHeight = isUpdateLayoutVisible ? dp(UpdateLayoutWrapper.HEIGHT) : 0;
        final int normalY = -(updateLayoutHeight);
        final int hiddenY = normalY + dp(40);

        final float visibleFactor = animatorTabsVisible.getFloatValue();
        final float scrollHideFactor = animatorTabsScrollHide.getFloatValue();
        final float combinedFactor = visibleFactor * (1f - scrollHideFactor);
        final float scale = lerp(0.85f, 1f, combinedFactor);
        final int scrollHideOffset = dp(MainTabsHelper.getMainTabsHeight() + MainTabsHelper.getMainTabsMargin() * 2);

        tabsViewWrapper.setTranslationY(lerp(hiddenY, normalY, visibleFactor) + scrollHideOffset * scrollHideFactor);
        tabsViewWrapper.setVisibility(combinedFactor > 0 ? View.VISIBLE : View.GONE);
        tabsView.setScaleX(scale);
        tabsView.setScaleY(scale);
        tabsView.setClickable(combinedFactor > 0.5f);
        tabsView.setEnabled(combinedFactor > 0.5f);
        tabsView.setAlpha(combinedFactor);
        tabsView.setVisibility(combinedFactor > 0 ? View.VISIBLE : View.GONE);
    }

    private void checkUi_callTabVisible(boolean callTabsVisible, boolean animated) {
        rebuildTabs();
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> themeDescriptions = super.getThemeDescriptions();

        ThemeDescription.ThemeDescriptionDelegate cellDelegate = this::blur3_updateColors;
        themeDescriptions.add(new ThemeDescription(null, 0, null, null, null, cellDelegate, Theme.key_windowBackgroundWhite));
        themeDescriptions.add(new ThemeDescription(null, 0, null, null, null, cellDelegate, Theme.key_dialogBackground));

        return themeDescriptions;
    }

    private class MainTabsActivityControllerImpl implements MainTabsActivityController {
        @Override
        public void setTabsVisible(boolean visible) {
            animatorTabsVisible.setValue(visible, true);
        }

        @Override
        public void setTabsScrollHide(boolean hide) {
            if (NaConfig.INSTANCE.getMainTabsDisplayMode().Int() != MainTabsHelper.BOTTOM_BAR_MODE_FLOATING) return;
            animatorTabsScrollHide.setValue(hide, true);
        }
    }

    /* Slide */

    @Override
    public boolean canBeginSlide() {
        final BaseFragment fragment = getCurrentVisibleFragment();
        return fragment != null && fragment.canBeginSlide();
    }

    @Override
    public void onBeginSlide() {
        super.onBeginSlide();
        final BaseFragment fragment = getCurrentVisibleFragment();
        if (fragment != null) {
            fragment.onBeginSlide();
        }
    }

    @Override
    public void onSlideProgress(boolean isOpen, float progress) {
        final BaseFragment fragment = getCurrentVisibleFragment();
        if (fragment != null) {
            fragment.onSlideProgress(isOpen, progress);
        }
    }

    @Override
    public Animator getCustomSlideTransition(boolean topFragment, boolean backAnimation, float distanceToMove) {
        final BaseFragment fragment = getCurrentVisibleFragment();
        return fragment != null ? fragment.getCustomSlideTransition(topFragment, backAnimation, distanceToMove) : null;
    }

    @Override
    public void prepareFragmentToSlide(boolean topFragment, boolean beginSlide) {
        final BaseFragment fragment = getCurrentVisibleFragment();
        if (fragment != null) {
            fragment.prepareFragmentToSlide(topFragment, beginSlide);
        }
    }

    private HintView2 accountSwitchHint;
    private boolean accountSwitchHintShown;

    private void showAccountChangeHint() {
        if (accountSwitchHintShown) return;

        if (accountSwitchHint == null && HintsController.Hint.AccountSwitchHint.show()) {
            AndroidUtilities.runOnUIThread(() -> {
                if (getContext() == null || tabs == null) return;

                int profileTabIndex = getTabIndex(MainTabsConfigManager.TabType.PROFILE);
                if (profileTabIndex < 0 || profileTabIndex >= tabs.length) return;
                final View v = tabs[profileTabIndex];
                final float translate = (contentView.getWidth() - ((tabsView.getX() + v.getX()) + v.getWidth()) + v.getWidth() / 2f) / AndroidUtilities.density;

                accountSwitchHint = new HintView2(getContext(), HintView2.DIRECTION_BOTTOM);
                accountSwitchHint.setTranslationY(-navigationBarHeight + dp(4));
                accountSwitchHint.setPadding(dp(7.33f), 0, dp(7.33f), 0);
                accountSwitchHint.setMultilineText(false);
                accountSwitchHint.setCloseButton(true);
                accountSwitchHint.setText(getString(R.string.SwitchAccountHint));
                accountSwitchHint.setJoint(1, -translate + 7.33f);
                contentView.addView(accountSwitchHint, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 100, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL, 0, 0, 0, MainTabsHelper.getMainTabsHeightWithMargins()));
                accountSwitchHint.setOnHiddenListener(() -> AndroidUtilities.removeFromParent(accountSwitchHint));
                accountSwitchHint.setDuration(8000);
                accountSwitchHint.show();

                HintsController.Hint.AccountSwitchHint.increment();
            }, 1500);
        }

        accountSwitchHintShown = true;
    }

    /* * */

    private final @NonNull BlurredBackgroundSourceColor iBlur3SourceColor;
    private final @Nullable BlurredBackgroundSourceRenderNode iBlur3SourceTabGlass;

    private final RectF fragmentPosition = new RectF();
    private void blur3_invalidateBlur() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || iBlur3SourceTabGlass == null || fragmentView == null) {
            return;
        }

        final int width = fragmentView.getMeasuredWidth();
        final int height = fragmentView.getMeasuredHeight();

        iBlur3SourceTabGlass.setSize(width, height);
        iBlur3SourceTabGlass.updateDisplayListIfNeeded();
    }

    private void blur3_updateFadeColors() {
        iBlur3SourceColor.setColor(getEstBackgroundColor());
        if (fadeView != null) {
            fadeView.invalidate();
        }
    }

    private void blur3_updateColors() {
        blur3_updateFadeColors();
        if (tabsViewBackground != null) {
            tabsViewBackground.updateColors();
        }
        if (searchTabButtonBackground != null) {
            searchTabButtonBackground.updateColors();
        }
        blur3_invalidateBlur();
        if (fadeView != null) {
            fadeView.invalidate();
        }
        if (tabsView != null) {
            tabsView.invalidate();
        }
        if (searchTabButton != null) {
            searchTabButton.invalidate();
        }
        updateSearchTabButtonVisibility();
        if (tabs != null) {
            for (GlassTabView tabView : tabs) {
                tabView.updateColorsLottie();
            }
        }
    }

    public boolean openContactsSelector(View anchor) {
        if (getContext() == null || getParentActivity() == null) return false;
        final ItemOptions o = ItemOptions.makeOptions(this, anchor);
        o.add(R.drawable.msg_contact_add, getString(R.string.NewContact), () -> {
            new NewContactBottomSheet(this, getContext()).show();
        });
        o.add(R.drawable.msg_calls, getString(R.string.VoipChatRecentCalls), () -> {
            Bundle args = new Bundle();
            args.putBoolean("needFinishFragment", false);
            presentFragment(new CallLogActivity(args));
        });
        setupPopupMenuStyle(o);
        o.setGravity(Gravity.LEFT);
        o.show();
        return true;
    }

    public boolean openCallsSelector(View anchor) {
        if (getContext() == null || getParentActivity() == null) return false;
        final ItemOptions o = ItemOptions.makeOptions(this, anchor);
        o.add(R.drawable.menu_call_create, getString(R.string.GroupCallCreate2), () -> CallLogActivity.openCreateCall(this));
        if (getUserConfig().showCallsTab) {
            o.add(R.drawable.msg_archive_hide, getString(R.string.HideCallTab), () -> {
                getUserConfig().setShowCallsTab(false);
                rebuildTabs();
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.callTabsVisibleToggled);
            });
        } else {
            o.add(R.drawable.menu_add_tab_24, getString(R.string.GroupCallShowInMainTabs), () -> {
                getUserConfig().setShowCallsTab(true);
                rebuildTabs();
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.callTabsVisibleToggled);
            });
        }
        setupPopupMenuStyle(o);
        o.show();
        return true;
    }

    private boolean processLongClick(View button, MainTabsConfigManager.TabType tabType) {
        if (tabType == MainTabsConfigManager.TabType.PROFILE) {
            openAccountSelector(button);
            return true;
        }
        if (tabType == MainTabsConfigManager.TabType.CONTACTS) {
            return openContactsSelector(button);
        }
        if (tabType == MainTabsConfigManager.TabType.CALLS) {
            return openCallsSelector(button);
        }
        if (tabType == MainTabsConfigManager.TabType.SETTINGS) {
            final boolean drawerOn = NekoConfig.navigationDrawerEnabled.Bool();
            final boolean showGhost = NekoConfig.showGhostInDrawer.Bool();
            final boolean ghostInDrawer = drawerOn && showGhost;
            final boolean nSettingsInDrawer = drawerOn && NaConfig.INSTANCE.getDrawerItemNSettings().Bool();
            final boolean browserInDrawer = drawerOn && NaConfig.INSTANCE.getDrawerItemBrowser().Bool();
            final boolean restartInDrawer = drawerOn && NaConfig.INSTANCE.getDrawerItemRestartApp().Bool();

            ItemOptions o = ItemOptions.makeOptions(this, button);
            boolean isDark = resourceProvider != null ? resourceProvider.isDark() : Theme.isCurrentThemeDark();
            o.add(isDark ? R.drawable.menu_day_mode_24 : R.drawable.menu_night_mode_24, getString(isDark ? R.string.SwitchThemeToDay : R.string.SwitchThemeToNight), () -> {
                if (DialogsActivity.switchingTheme) return;
                DialogsActivity.switchingTheme = true;
                android.content.SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences("themeconfig", 0);
                String dayTheme = prefs.getString("lastDayTheme", "Blue");
                Theme.ThemeInfo dayInfo = Theme.getTheme(dayTheme);
                if (dayInfo == null || dayInfo.isDark()) dayTheme = "Blue";
                String darkTheme = prefs.getString("lastDarkTheme", "Dark Blue");
                Theme.ThemeInfo darkInfo = Theme.getTheme(darkTheme);
                if (darkInfo == null || !darkInfo.isDark()) darkTheme = "Dark Blue";
                Theme.ThemeInfo active = Theme.getActiveTheme();
                String targetKey;
                if (dayTheme.equals(darkTheme)) {
                    boolean isActiveDark = active.isDark();
                    if (isActiveDark && dayTheme.equals("Dark Blue")) {
                        targetKey = "Blue";
                    } else if (!isActiveDark && dayTheme.equals("Blue")) {
                        targetKey = "Dark Blue";
                    } else {
                        targetKey = isActiveDark ? dayTheme : darkTheme;
                    }
                } else {
                    targetKey = active.getKey().equals(dayTheme) ? darkTheme : dayTheme;
                }
                Theme.ThemeInfo target = Theme.getTheme(targetKey);
                switchTheme(button, target, active.getKey().equals(dayTheme));
                BulletinFactory bulletinFactory = BulletinFactory.of(MainTabsActivity.this);
                Theme.turnOffAutoNight(bulletinFactory, () -> presentFragment(new ThemeActivity(1)));
            });
            o.addGap();
            boolean addedAny = false;
            if (showGhost && !ghostInDrawer) {
                final String msg = NekoConfig.isGhostModeActive() ? getString(R.string.DisableGhostMode) : getString(R.string.EnableGhostMode);
                o.add(R.drawable.ayu_ghost, msg, () -> presentFragment(new GhostModeActivity()), () -> {
                    final String toggleMsg = NekoConfig.isGhostModeActive() ? getString(R.string.GhostModeDisabled) : getString(R.string.GhostModeEnabled);
                    NekoConfig.toggleGhostMode();
                    BulletinFactory.of(contentView, resourceProvider).createSuccessBulletin(toggleMsg).show();
                    NotificationCenter.getInstance(UserConfig.selectedAccount).postNotificationName(NotificationCenter.mainUserInfoChanged);
                });
                o.addGap();
                addedAny = true;
            }
            if (!nSettingsInDrawer) {
                o.add(R.drawable.msg_settings, getString(R.string.NekoSettings), () -> presentFragment(new NekoSettingsActivity()));
                addedAny = true;
            }
            if (!browserInDrawer) {
                o.add(R.drawable.web_browser, getString(R.string.InappBrowser), () -> presentFragment(new WebBrowserSettings(null)), () -> BrowserUtils.openBrowserHome(null, true));
                addedAny = true;
            }
            if (!restartInDrawer) {
                if (addedAny) {
                    o.addGap();
                }
                o.add(R.drawable.msg_retry_solar, getString(R.string.RestartApp), () ->
                    AppRestartHelper.triggerRebirth(
                        ApplicationLoader.applicationContext,
                        new Intent(ApplicationLoader.applicationContext, LaunchActivity.class)
                    )
                );
                addedAny = true;
            }
            if (!addedAny) {
                return false;
            }
            setupPopupMenuStyle(o);
            o.show();
            return true;
        }
        if (tabType != MainTabsConfigManager.TabType.CHATS) {
            return false;
        }

        final ArrayList<MessagesController.DialogFilter> filters = getMessagesController().getDialogFilters();
        final boolean hasFolders = filters != null && filters.size() > 1;

        ItemOptions o = ItemOptions.makeOptions(this, button);

        ActionBarMenuSubItem checkedItem = null;
        if (hasFolders) {
            final int selectedFolderIndex = dialogsActivity != null ? dialogsActivity.getSelectedFilterIndex() : -1;
            for (int i = 0; i < filters.size(); i++) {
                final MessagesController.DialogFilter folder = filters.get(i);
                final boolean checked = i == selectedFolderIndex;
                final ActionBarMenuSubItem folderItem = new ActionBarMenuSubItem(getParentActivity(), 2, i == 0, i == filters.size() - 1, getResourceProvider());
                folderItem.setPadding(dp(18), 0, dp(18), 0);
                folderItem.setChecked(checked);
                if (checked) {
                    checkedItem = folderItem;
                }
                CharSequence title = folder.isDefault() ? getString(R.string.FilterAllChats) : folder.name;
                title = Emoji.replaceEmoji(title, folderItem.getTextView().getPaint().getFontMetricsInt(), false);
                if (!folder.isDefault()) {
                    title = MessageObject.replaceAnimatedEmoji(title, folder.entities, folderItem.getTextView().getPaint().getFontMetricsInt());
                }
                final int unreadCount = folder.isDefault()
                        ? MessagesStorage.getInstance(currentAccount).getMainUnreadCount()
                        : folder.unreadCount;
                if (unreadCount > 0) {
                    final SpannableStringBuilder titleWithCounter = new SpannableStringBuilder(title);
                    final int counterStart = titleWithCounter.length();
                    titleWithCounter.append(String.valueOf(unreadCount));
                    titleWithCounter.setSpan(
                            new FolderCounterSpan(unreadCount, hasUnmutedUnreadDialogs(folder)),
                            counterStart,
                            titleWithCounter.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    );
                    title = titleWithCounter;
                    folderItem.setContentDescription(
                            TextUtils.concat(
                                    folder.isDefault() ? getString(R.string.FilterAllChats) : folder.name,
                                    "\n",
                                    LocaleController.formatPluralString("AccDescrUnreadCount", unreadCount)
                            )
                    );
                }
                folderItem.setEmojiCacheType(folder.title_noanimate ? AnimatedEmojiDrawable.CACHE_TYPE_NOANIMATE_FOLDER : AnimatedEmojiDrawable.CACHE_TYPE_MESSAGES);
                final int color = getMessagesController().folderTags ? folder.color : -1;
                folderItem.setTextAndIcon(title, 0, new FolderDrawable(getContext(), FolderIconHelper.getTabIcon(folder.isDefault() ? "\uD83D\uDCAC" : folder.emoticon), color));
                folderItem.getTextView().setEmojiColor(getThemedColor(Theme.key_featuredStickers_addButton));
                folderItem.setMinimumWidth(160);
                int folderId = folder.id;
                folderItem.setOnClickListener(e -> {
                    o.dismiss();
                    if (checked) {
                        return;
                    }
                    if (dialogsActivity != null && viewPager != null) {
                        int chatsPosition = getTabIndex(MainTabsConfigManager.TabType.CHATS);
                        if (viewPager.getCurrentPosition() == chatsPosition) {
                            dialogsActivity.scrollToFolder(folderId);
                        } else {
                            viewPager.scrollToPosition(chatsPosition);
                            AndroidUtilities.runOnUIThread(() -> {
                                if (dialogsActivity != null) dialogsActivity.scrollToFolder(folderId);
                            }, 300);
                        }
                    }
                });
                o.addView(folderItem, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            }
            o.addGap();
        }

        o.add(R.drawable.tabs_reorder, getString(R.string.MainTabsCustomize), () ->
            presentFragment(new MainTabsCustomizeActivity())
        );
        setupPopupMenuStyle(o);
        o.translate(-dp(8), 0);
        o.setMaxHeight(dp(400));
        o.setGravity(Gravity.LEFT);
        o.show();

        if (checkedItem != null) {
            final ActionBarMenuSubItem item = checkedItem;
            item.post(() -> scrollItemIntoView(item));
        }
        return true;
    }

    private static void scrollItemIntoView(View item) {
        ViewParent parent = item.getParent();
        while (parent != null && !(parent instanceof ScrollView)) {
            parent = parent.getParent();
        }
        if (!(parent instanceof ScrollView)) {
            return;
        }
        final ScrollView scrollView = (ScrollView) parent;
        final int top = item.getTop();
        final int bottom = item.getBottom();
        final int scrollY = scrollView.getScrollY();
        final int height = scrollView.getHeight();
        if (top < scrollY) {
            scrollView.smoothScrollTo(0, top);
        } else if (bottom > scrollY + height) {
            scrollView.smoothScrollTo(0, bottom - height);
        }
    }

    private void switchTheme(View view, Theme.ThemeInfo themeInfo, boolean z) {
        if (view == null) return;
        int[] loc = new int[2];
        view.getLocationInWindow(loc);
        loc[0] += view.getMeasuredWidth() / 2;
        loc[1] += view.getMeasuredHeight() / 2;
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.needSetDayNightTheme, themeInfo, Boolean.FALSE, loc, -1, Boolean.valueOf(z), null, null, null, Boolean.TRUE);
    }

    private void setupPopupMenuStyle(ItemOptions options) {
        options.setBlur(true);
        options.translate(0, -dp(4));
        final ShapeDrawable bg = Theme.createRoundRectDrawable(dp(28), getThemedColor(Theme.key_windowBackgroundWhite));
        bg.getPaint().setShadowLayer(dp(6), 0, dp(1), Theme.multAlpha(0xFF000000, 0.15f));
        options.setScrimViewBackground(bg);
    }

    private void repositionSearchButton() {
        if (searchTabButton == null || tabsView == null || tabsViewWrapper == null || tabsBarContainer == null) return;
        boolean show = NaConfig.INSTANCE.getMainTabsShowSearchButton().Bool() && !isBottomBarHidden();

        ViewGroup.LayoutParams tabsBaseLp = tabsView.getLayoutParams();
        LinearLayout.LayoutParams tabsLp;
        if (tabsBaseLp instanceof LinearLayout.LayoutParams) {
            tabsLp = (LinearLayout.LayoutParams) tabsBaseLp;
        } else {
            tabsLp = new LinearLayout.LayoutParams(dp(MainTabsHelper.getTabsViewWidth()), dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS));
            tabsView.setLayoutParams(tabsLp);
        }

        ViewGroup.LayoutParams searchBaseLp = searchTabButton.getLayoutParams();
        LinearLayout.LayoutParams searchLp;
        if (searchBaseLp instanceof LinearLayout.LayoutParams) {
            searchLp = (LinearLayout.LayoutParams) searchBaseLp;
        } else {
            int searchBtnSize = dp(56);
            searchLp = new LinearLayout.LayoutParams(searchBtnSize, searchBtnSize);
            searchLp.setMarginStart(-dp(10));
            searchTabButton.setLayoutParams(searchLp);
        }

        tabsView.setTranslationX(0f);
        searchTabButton.setTranslationX(0f);
        tabsBarContainer.setTranslationX(0f);

        if (!show) {
            int baseTabsWidth = dp(MainTabsHelper.getTabsViewWidth());
            int wrapperWidth = tabsViewWrapper.getWidth();
            if (wrapperWidth > 0) {
                int containerPadding = tabsBarContainer.getPaddingLeft() + tabsBarContainer.getPaddingRight();
                int outerInset = dp(Math.min(DialogsActivity.MAIN_TABS_MARGIN, 6));
                int maxTabsWidth = Math.max(0, wrapperWidth - containerPadding - outerInset * 2);
                baseTabsWidth = Math.min(baseTabsWidth, maxTabsWidth);
            }
            if (tabsLp.width != baseTabsWidth) {
                tabsLp.width = baseTabsWidth;
                tabsView.setLayoutParams(tabsLp);
            }
            searchTabButton.setVisibility(View.GONE);
            return;
        }

        int searchBtnWidth = searchTabButton.getWidth();
        if (searchBtnWidth <= 0) {
            searchBtnWidth = searchLp.width > 0 ? searchLp.width : dp(56);
        }
        int gap = searchLp.getMarginStart();
        int endInset = searchLp.getMarginEnd();
        int wrapperWidth = tabsViewWrapper.getWidth();
        if (wrapperWidth <= 0) return;

        int containerPadding = tabsBarContainer.getPaddingLeft() + tabsBarContainer.getPaddingRight();
        int outerInset = dp(Math.min(DialogsActivity.MAIN_TABS_MARGIN, 6));
        int maxTabsWidth = Math.max(0, wrapperWidth - containerPadding - searchBtnWidth - gap - endInset - outerInset * 2);
        if (tabsLp.width != maxTabsWidth) {
            tabsLp.width = maxTabsWidth;
            tabsView.setLayoutParams(tabsLp);
        }
        searchTabButton.setVisibility(View.VISIBLE);

        int bgPadding = dp(MainTabsHelper.getMainTabsMargin() - 0.334f);
        int leftVisualPad = tabsBarContainer.getPaddingLeft() + bgPadding;
        int rightVisualPad = tabsBarContainer.getPaddingRight() + endInset;
        float correction = (rightVisualPad - leftVisualPad) / 2f;
        tabsBarContainer.setTranslationX(correction);
    }

    private void onSearchTabButtonClicked() {
        if (!NaConfig.INSTANCE.getMainTabsForceOpenChats().Bool()) {
            final BaseFragment fragment = getCurrentVisibleFragment();
            if (fragment instanceof TabFragmentDelegate) {
                TabFragmentDelegate delegate = (TabFragmentDelegate) fragment;
                if (delegate.hasSearch()) {
                    delegate.onSearchButtonClicked();
                    return;
                }
            }
        }
        int chatsPosition = getTabIndex(MainTabsConfigManager.TabType.CHATS);
        if (chatsPosition >= 0 && viewPager != null) {
            viewPager.scrollToPosition(chatsPosition);
            if (dialogsActivity != null) {
                dialogsActivity.onSearchButtonClicked();
            }
        }
    }

    private void onSearchTabButtonLongClicked() {
        Bundle args = new Bundle();
        args.putLong("user_id", UserConfig.getInstance(currentAccount).getClientUserId());
        presentFragment(new ChatActivity(args));
    }

    private void updateSearchTabButtonVisibility() {
        if (searchTabButton == null) return;
        boolean show = NaConfig.INSTANCE.getMainTabsShowSearchButton().Bool() && !isBottomBarHidden();
        searchTabButton.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            View child = searchTabButton.getChildAt(0);
            if (child instanceof ImageView) {
                ((ImageView) child).setColorFilter(new PorterDuffColorFilter(
                    Theme.getColor(Theme.key_glass_tabUnselected, resourceProvider), PorterDuff.Mode.SRC_IN));
            }
        }
        repositionSearchButton();
    }

    @Override
    public EdgeToEdgeSupportMode getEdgeToEdgeSupportMode() {
        return EdgeToEdgeSupportMode.FULL;
    }
}
