package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.lerp;
import static org.telegram.messenger.LocaleController.getString;
import static org.telegram.ui.Components.Premium.LimitReachedBottomSheet.TYPE_ACCOUNTS;

import android.animation.Animator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.ShapeDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.math.MathUtils;
import androidx.core.view.WindowInsetsCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.ContactsController;
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
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CubicBezierInterpolator;
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
import org.telegram.ui.web.WebBrowserSettings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import me.vkryl.android.animator.BoolAnimator;
import me.vkryl.android.animator.FactorAnimator;
import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.helpers.AppRestartHelper;
import tw.nekomimi.nekogram.helpers.MainTabsHelper;
import tw.nekomimi.nekogram.helpers.PasscodeHelper;
import tw.nekomimi.nekogram.settings.GhostModeActivity;
import tw.nekomimi.nekogram.settings.MainTabsCustomizeActivity;
import tw.nekomimi.nekogram.settings.NekoSettingsActivity;
import tw.nekomimi.nekogram.ui.BookmarkManagerActivity;
import tw.nekomimi.nekogram.utils.BrowserUtils;
import xyz.nextalone.nagram.NaConfig;

public class MainTabsActivity extends ViewPagerActivity implements NotificationCenter.NotificationCenterDelegate, FactorAnimator.Target {
    private static final int DEFAULT_PAGER_POSITION = 0;

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
                super.dispatchDraw(canvas);
                blur3_invalidateBlur();
            }
        };
    }

    @Override
    public void onResume() {
        super.onResume();
        blur3_updateColors();
        checkContactsTabBadge();
        checkUnreadCount(true);

        Bulletin.Delegate delegate = new Bulletin.Delegate() {
            @Override
            public int getBottomOffset(int tag) {
                return navigationBarHeight + getVisibleBottomBarOffset();
            }
        };

        Bulletin.addDelegate(this, delegate);
        Bulletin.addDelegate(contentView, delegate);

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
        Bulletin.removeDelegate(this);
        Bulletin.removeDelegate(contentView);
        if (accountSwitchHint != null) {
            accountSwitchHint.hide();
        }
    }

    @Override
    public View createView(Context context) {
        super.createView(context);

        final int mainTabsMargin = MainTabsHelper.getMainTabsMargin();

        tabsView = new MainTabsLayout(context);
        tabsView.setEqualWidthWhenTitlesVisible(true);
        tabsView.setClipChildren(false);
        final int paddingH = dp(mainTabsMargin + 4);
        final int paddingV = dp(mainTabsMargin + 4);
        tabsView.setPadding(paddingH, paddingV, paddingH, paddingV);

        rebuildTabs();

        selectTab(viewPager.getCurrentPosition(), false);

        iBlur3SourceColor.setColor(getThemedColor(Theme.key_windowBackgroundWhite));


        ViewPositionWatcher viewPositionWatcher = new ViewPositionWatcher(contentView);


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

    public void openAccountSelector(View button) {
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

        MessagesController.getGlobalMainSettings().edit()
            .putInt("accountswitchhint", 3)
            .apply();
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
        avatarView.setRoundRadius(org.telegram.messenger.AvatarCornerHelper.getAvatarRoundRadius(32.0f));
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
            case CONTACTS -> {
                Bundle args = new Bundle();
                args.putBoolean("needPhonebook", true);
                args.putBoolean("needFinishFragment", false);
                args.putBoolean("hasMainTabs", shouldUseMainTabsPadding());
                return new ContactsActivity(args);
            }
            case SETTINGS -> {
                Bundle args = new Bundle();
                args.putBoolean("hasMainTabs", shouldUseMainTabsPadding());
                return new SettingsActivity(args);
            }
            case CALLS -> {
                Bundle args = new Bundle();
                args.putBoolean("needFinishFragment", false);
                args.putBoolean("hasMainTabs", shouldUseMainTabsPadding());
                return new CallLogActivity(args);
            }
            case PROFILE -> {
                Bundle args = new Bundle();
                args.putLong("user_id", UserConfig.getInstance(currentAccount).getClientUserId());
                args.putBoolean("my_profile", true);
                args.putBoolean("hasMainTabs", shouldUseMainTabsPadding());
                return new ProfileActivity(args);
            }
            case CHATS -> {
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

    /* */

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
        checkContactsTabBadge();
        updateSearchTabButtonVisibility();
    }


    /* * */

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


    /* * */

    private int navigationBarHeight;

    @NonNull
    @Override
    protected WindowInsetsCompat onApplyWindowInsets(@NonNull View v, @NonNull WindowInsetsCompat insets) {
        navigationBarHeight = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
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
            final int bottomMargin = isUpdateLayoutVisible ? (navigationBarHeight + updateLayoutHeight) : 0;
            lp = (ViewGroup.MarginLayoutParams) viewPager.getLayoutParams();
            if (lp.bottomMargin != bottomMargin) {
                lp.bottomMargin = bottomMargin;
                viewPager.setLayoutParams(lp);
            }
        }

        tabsViewWrapper.setPadding(0, 0, 0, navigationBarHeight);

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
            if (updateLayout != null) {
                updateLayout.updateAppUpdateViews(currentAccount, LaunchActivity.getMainFragmentsStackSize() == 1);
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

    @Override
    public boolean onFragmentCreate() {
        configuredTabs = MainTabsConfigManager.getEnabledTabs();

        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.fileLoaded);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.fileLoadProgressChanged);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.fileLoadFailed);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.notificationsCountUpdated);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.updateInterfaces);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.callTabsVisibleToggled);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.mainUserInfoChanged);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.contactsPermissionBadgeCheck);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.appUpdateAvailable);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.appUpdateLoading);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.needSetDayNightTheme);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.mainTabsLayoutChanged);

        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.fileLoaded);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.fileLoadProgressChanged);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.fileLoadFailed);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.notificationsCountUpdated);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.updateInterfaces);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.callTabsVisibleToggled);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.mainUserInfoChanged);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.contactsPermissionBadgeCheck);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.appUpdateAvailable);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.appUpdateLoading);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.needSetDayNightTheme);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.mainTabsLayoutChanged);

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
        final float alpha = (1f - isProfile * hide) * animatorTabsVisible.getFloatValue() * (1f - scrollHideFactor);

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

    /* * */

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

        if (accountSwitchHint == null && MessagesController.getGlobalMainSettings().getInt("accountswitchhint", 0) < 2) {
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
            }, 1500);

            MessagesController.getGlobalMainSettings().edit()
                .putInt("accountswitchhint", MessagesController.getGlobalMainSettings()
                .getInt("channelgifthint", 0) + 1)
                .apply();
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

    private void blur3_updateColors() {
        iBlur3SourceColor.setColor(getThemedColor(Theme.key_windowBackgroundWhite));
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

    private boolean processLongClick(View button, MainTabsConfigManager.TabType tabType) {
        if (tabType == MainTabsConfigManager.TabType.PROFILE) {
            openAccountSelector(button);
            return true;
        }
        if (tabType == MainTabsConfigManager.TabType.SETTINGS) {
            ItemOptions o = ItemOptions.makeOptions(this, button);
            if (NekoConfig.showGhostInDrawer.Bool()) {
                final String msg = NekoConfig.isGhostModeActive()
                    ? getString(R.string.DisableGhostMode)
                    : getString(R.string.EnableGhostMode);
                o.add(R.drawable.ayu_ghost, msg, () -> presentFragment(new GhostModeActivity()), () -> {
                    final String toggleMsg = NekoConfig.isGhostModeActive()
                        ? getString(R.string.GhostModeDisabled)
                        : getString(R.string.GhostModeEnabled);
                    NekoConfig.toggleGhostMode();
                    BulletinFactory.of(contentView, resourceProvider).createSuccessBulletin(toggleMsg).show();
                    NotificationCenter.getInstance(UserConfig.selectedAccount).postNotificationName(NotificationCenter.mainUserInfoChanged);
                });
                o.addGap();
            }
            o.add(R.drawable.msg_settings, getString(R.string.NekoSettings), () -> presentFragment(new NekoSettingsActivity()));
            o.add(R.drawable.web_browser, getString(R.string.InappBrowser), () -> presentFragment(new WebBrowserSettings(null)), () -> BrowserUtils.openBrowserHome(null, true));
            o.addGap();
            o.add(R.drawable.msg_retry_solar, getString(R.string.RestartApp), () ->
                AppRestartHelper.triggerRebirth(
                    ApplicationLoader.applicationContext,
                    new Intent(ApplicationLoader.applicationContext, LaunchActivity.class)
                )
            );
            setupPopupMenuStyle(o);
            o.show();
            return true;
        }
        if (tabType != MainTabsConfigManager.TabType.CHATS) {
            return false;
        }

        ItemOptions o = ItemOptions.makeOptions(this, button);
        o.add(R.drawable.tabs_reorder, getString(R.string.MainTabsCustomize), () ->
            presentFragment(new MainTabsCustomizeActivity())
        );
        o.addGap();
        o.add(R.drawable.msg_archive, getString(R.string.ArchivedChats), () -> {
            Bundle args = new Bundle();
            args.putInt("folderId", 1);
            presentFragment(new DialogsActivity(args));
        });
        o.add(R.drawable.msg_saved, getString(R.string.SavedMessages), () -> {
            Bundle args = new Bundle();
            args.putLong("user_id", UserConfig.getInstance(currentAccount).getClientUserId());
            presentFragment(new ChatActivity(args));
        });
        if (NaConfig.INSTANCE.getShowAddToBookmark().Bool()) {
            o.add(R.drawable.msg_fave, getString(R.string.BookmarksManager), () -> presentFragment(new BookmarkManagerActivity()));
        }
        setupPopupMenuStyle(o);
        o.show();
        return true;
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

        // Fix visual centering: the pill background is inset from tabsView bounds
        // by bgPadding on the left, while the search button only has marginEnd on
        // the right. Shift the container so both visual edges are equidistant from
        // the screen edges.
        int bgPadding = dp(MainTabsHelper.getMainTabsMargin() - 0.334f);
        int leftVisualPad = tabsBarContainer.getPaddingLeft() + bgPadding;
        int rightVisualPad = tabsBarContainer.getPaddingRight() + endInset;
        float correction = (rightVisualPad - leftVisualPad) / 2f;
        tabsBarContainer.setTranslationX(correction);
    }
    private void onSearchTabButtonClicked() {
        final BaseFragment fragment = getCurrentVisibleFragment();
        if (fragment instanceof TabFragmentDelegate) {
            TabFragmentDelegate delegate = (TabFragmentDelegate) fragment;
            if (delegate.hasSearch()) {
                delegate.onSearchButtonClicked();
                return;
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

}
