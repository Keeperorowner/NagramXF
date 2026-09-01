package com.exteragram.messenger.feed.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.exteragram.messenger.ExteraConfig;
import com.exteragram.messenger.feed.FeedConfig;
import com.exteragram.messenger.feed.FeedController;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.ChatActivityContainer;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ChatAvatarContainer;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.MainTabsActivity;

import java.util.ArrayList;

/**
 * Hosts the feed as a chat-mode screen: embeds a ChatActivity in feed search
 * mode and wires its action bar (mark-all-read, feed settings), unread
 * subtitle and lifecycle to the FeedController.
 */
public class FeedActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate, MainTabsActivity.TabFragmentDelegate {
    private ChatActivityContainer chatContainer;
    private boolean embeddedChatCreated;
    private boolean hasMainTabs;
    private int lastConfigGeneration;
    private WindowInsetsCompat lastWindowInsets;
    private final Runnable loadNewPosts;
    private Runnable parentTabsGlassInvalidationCallback;
    private boolean resumedOnce;
    private boolean uiActiveHeld;
    private boolean uiResumedHeld;
    private boolean viewportFullyVisible;

    @Override
    public boolean drawEdgeNavigationBar() {
        return false;
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    public FeedActivity() {
        this(null);
    }

    public FeedActivity(Bundle bundle) {
        super(bundle);
        this.loadNewPosts = this::lambda$new$0;
    }

    public static void presentFeed(BaseFragment fragment) {
        LaunchActivity launchActivity;
        if (!AndroidUtilities.isTablet() || (launchActivity = LaunchActivity.instance) == null || launchActivity.getRightActionBarLayout() == null) {
            if (fragment != null) {
                fragment.presentFragment(new FeedActivity());
            }
            return;
        }
        INavigationLayout rightActionBarLayout = LaunchActivity.instance.getRightActionBarLayout();
        if (rightActionBarLayout.getLastFragment() instanceof FeedActivity) {
            return;
        }
        if (!rightActionBarLayout.getFragmentStack().isEmpty()) {
            while (rightActionBarLayout.getFragmentStack().size() - 1 > 0) {
                rightActionBarLayout.removeFragmentFromStack(rightActionBarLayout.getFragmentStack().get(0));
            }
            rightActionBarLayout.closeLastFragment(false);
        }
        rightActionBarLayout.presentFragment(new INavigationLayout.NavigationParams(new FeedActivity()).setNoAnimation(true).forceRightLayout());
    }

    private void lambda$new$0() {
        ChatActivity chatActivity;
        ChatActivityContainer container = this.chatContainer;
        if (container == null || (chatActivity = container.chatActivity) == null || !this.uiResumedHeld) {
            return;
        }
        chatActivity.loadNewerFeed(true);
    }

    @Override
    public boolean onFragmentCreate() {
        Bundle bundle = this.arguments;
        boolean hasTabs = false;
        if (bundle != null && bundle.getBoolean("hasMainTabs", false)) {
            hasTabs = true;
        }
        this.hasMainTabs = hasTabs;
        this.viewportFullyVisible = !hasTabs;
        NotificationCenter.getInstance(this.currentAccount).addObserver(this, NotificationCenter.didReceiveNewMessages);
        NotificationCenter.getInstance(this.currentAccount).addObserver(this, NotificationCenter.feedNeedReload);
        this.lastConfigGeneration = FeedConfig.getInstance(this.currentAccount).getGeneration();
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        AndroidUtilities.cancelRunOnUIThread(this.loadNewPosts);
        destroyEmbeddedChat();
        if (this.uiResumedHeld) {
            this.uiResumedHeld = false;
            FeedController.getInstance(this.currentAccount).setUiResumed(false);
        }
        if (this.uiActiveHeld) {
            this.uiActiveHeld = false;
            FeedController.getInstance(this.currentAccount).setUiActive(false);
        }
        Bulletin.removeDelegate(this);
        NotificationCenter.getInstance(this.currentAccount).removeObserver(this, NotificationCenter.didReceiveNewMessages);
        NotificationCenter.getInstance(this.currentAccount).removeObserver(this, NotificationCenter.feedNeedReload);
        super.onFragmentDestroy();
    }

    private void destroyEmbeddedChat() {
        ChatActivity chatActivity;
        ChatActivityContainer container = this.chatContainer;
        if (container != null && (chatActivity = container.chatActivity) != null) {
            if (!this.hasMainTabs && this.embeddedChatCreated) {
                chatActivity.saveFeedScrollPosition();
            }
            this.chatContainer.chatActivity.setFeedChannelsChangedCallback(null);
            this.chatContainer.chatActivity.setGlassSourceInvalidationCallback(null);
            if (this.embeddedChatCreated) {
                this.chatContainer.chatActivity.onFragmentDestroy();
            }
        }
        this.embeddedChatCreated = false;
        this.chatContainer = null;
    }

    @Override
    public boolean onBackPressed(boolean last) {
        ChatActivity chatActivity;
        ChatActivityContainer container = this.chatContainer;
        if (container == null || (chatActivity = container.chatActivity) == null || chatActivity.getActionBar() == null || !this.chatContainer.chatActivity.getActionBar().isActionModeShowed()) {
            return super.onBackPressed(last);
        }
        if (!last) {
            return false;
        }
        this.chatContainer.chatActivity.clearSelectionMode();
        return false;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.didReceiveNewMessages) {
            if ((Boolean) args[2] || this.chatContainer == null || !FeedController.getInstance(this.currentAccount).isIncludedChannelPost((Long) args[0])) {
                return;
            }
            AndroidUtilities.cancelRunOnUIThread(this.loadNewPosts);
            AndroidUtilities.runOnUIThread(this.loadNewPosts, 1000L);
        } else if (id == NotificationCenter.feedNeedReload) {
            boolean truncated = false;
            ChatActivityContainer container = this.chatContainer;
            if (container != null && container.chatActivity != null) {
                if (args.length > 0 && Boolean.TRUE.equals(args[0])) {
                    truncated = true;
                }
                this.chatContainer.chatActivity.onFeedChannelsChanged(truncated);
            }
            updateFeedSubtitle();
        }
    }

    @Override
    public View createView(Context context) {
        destroyEmbeddedChat();
        this.lastWindowInsets = null;
        this.actionBar.setAddToContainer(false);
        this.actionBar.setVisibility(View.GONE);
        FrameLayout fragmentView = new FrameLayout(context);
        this.fragmentView = fragmentView;
        fragmentView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        if (this.hasMainTabs) {
            // The bottom tabs height is applied directly in
            // ChatActivity.checkUi_chatListViewPaddings; insets are cached and
            // re-dispatched as-is so the navigation bar inset reaches the
            // embedded chat without double-counting the tabs height.
            ViewCompat.setOnApplyWindowInsetsListener(fragmentView, (view, insets) -> {
                this.lastWindowInsets = insets;
                return insets;
            });
            fragmentView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewDetachedFromWindow(View view) {
                }

                @Override
                public void onViewAttachedToWindow(View view) {
                    if (FeedActivity.this.lastWindowInsets != null) {
                        ViewCompat.dispatchApplyWindowInsets(view, FeedActivity.this.lastWindowInsets);
                    } else {
                        view.requestApplyInsets();
                    }
                }
            });
        }
        FrameLayout containerLayout = new FrameLayout(context);
        fragmentView.addView(containerLayout, LayoutHelper.createFrame(-1, -1, 119));
        Bundle bundle = new Bundle();
        bundle.putInt("chatMode", 7);
        bundle.putInt("searchType", 4);
        bundle.putBoolean("hasMainTabs", this.hasMainTabs);
        ChatActivityContainer container = new ChatActivityContainer(context, getParentLayout(), bundle) {
            boolean activityCreated = false;

            @Override
            public void initChatActivity() {
                if (this.activityCreated) {
                    return;
                }
                this.activityCreated = true;
                FeedActivity.this.embeddedChatCreated = true;
                super.initChatActivity();
                FeedActivity.this.applyFloatingWindowLayout();
                FeedActivity.this.setupChatActionBar();
                FeedActivity.this.setupChatTitle();
                if (FeedActivity.this.lastWindowInsets != null && FeedActivity.this.fragmentView != null) {
                    ViewCompat.dispatchApplyWindowInsets(FeedActivity.this.fragmentView, FeedActivity.this.lastWindowInsets);
                }
            }
        };
        this.chatContainer = container;
        ChatActivity chatActivity = container.chatActivity;
        chatActivity.isInsideContainer = false;
        chatActivity.setFeedChannelsChangedCallback(this::updateFeedSubtitle);
        this.chatContainer.chatActivity.setGlassSourceInvalidationCallback(this::invalidateParentTabsGlass);
        updateFeedViewportActive(this.viewportFullyVisible);
        if (!this.uiResumedHeld) {
            this.chatContainer.onPause();
        }
        containerLayout.addView(this.chatContainer, LayoutHelper.createFrame(-1, -1, 119));
        if (!this.uiActiveHeld) {
            this.uiActiveHeld = true;
            FeedController.getInstance(this.currentAccount).setUiActive(true);
        }
        Bulletin.addDelegate(this, new Bulletin.Delegate() {
            @Override
            public int getTopOffset(int type) {
                if (FeedActivity.this.chatContainer != null && FeedActivity.this.chatContainer.chatActivity != null) {
                    return FeedActivity.this.chatContainer.chatActivity.getBulletinTopOffset();
                }
                return AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight();
            }

            @Override
            public int getBottomOffset(int type) {
                if (FeedActivity.this.chatContainer == null || FeedActivity.this.chatContainer.chatActivity == null) {
                    return 0;
                }
                return FeedActivity.this.chatContainer.chatActivity.getBulletinBottomOffset();
            }
        });
        return this.fragmentView;
    }

    @Override
    public void onResume() {
        ChatActivityContainer containerToResume;
        ChatActivity chatActivity;
        ChatActivity loadNewerChat;
        View view;
        WindowInsetsCompat insets;
        super.onResume();
        ChatActivityContainer container = this.chatContainer;
        if (container != null) {
            container.onResume();
            updateFeedViewportActive(this.viewportFullyVisible);
        }
        if (!this.uiResumedHeld) {
            this.uiResumedHeld = true;
            FeedController.getInstance(this.currentAccount).setUiResumed(true);
        }
        if (this.hasMainTabs && (view = this.fragmentView) != null && (insets = this.lastWindowInsets) != null) {
            ViewCompat.dispatchApplyWindowInsets(view, insets);
        }
        reattachCurrentFeedVideoTexture();
        int generation = FeedConfig.getInstance(this.currentAccount).getGeneration();
        if (generation != this.lastConfigGeneration) {
            this.lastConfigGeneration = generation;
            ChatActivityContainer containerWithChat = this.chatContainer;
            if (containerWithChat != null && (chatActivity = containerWithChat.chatActivity) != null) {
                chatActivity.applyFeedConfigChange();
            }
        } else if (this.resumedOnce && (containerToResume = this.chatContainer) != null && (loadNewerChat = containerToResume.chatActivity) != null) {
            loadNewerChat.reconcileFeedList();
            this.chatContainer.chatActivity.refreshFeedUnreadDivider();
            if (!FeedController.getInstance(this.currentAccount).getMessages().isEmpty()) {
                this.chatContainer.chatActivity.loadNewerFeed(true);
            }
        }
        this.resumedOnce = true;
        updateFeedSubtitle();
    }

    @Override
    public void onBecomeFullyVisible() {
        super.onBecomeFullyVisible();
        this.viewportFullyVisible = true;
        updateFeedViewportActive(true);
        reattachCurrentFeedVideoTexture();
    }

    @Override
    public void onBecomeFullyHidden() {
        this.viewportFullyVisible = false;
        updateFeedViewportActive(false);
        super.onBecomeFullyHidden();
    }

    @Override
    public void onTransitionAnimationStart(boolean open, boolean backward) {
        if (this.hasMainTabs) {
            this.viewportFullyVisible = false;
            updateFeedViewportActive(false);
        }
        super.onTransitionAnimationStart(open, backward);
    }

    @Override
    public void onTransitionAnimationEnd(boolean open, boolean backward) {
        super.onTransitionAnimationEnd(open, backward);
        if (this.hasMainTabs) {
            this.viewportFullyVisible = open;
            updateFeedViewportActive(open);
        }
    }

    @Override
    public void onParentBecomeFullyVisible() {
        reattachCurrentFeedVideoTexture();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (this.chatContainer != null) {
            updateFeedViewportActive(false);
            this.chatContainer.onPause();
        }
        if (this.uiResumedHeld) {
            this.uiResumedHeld = false;
            FeedController.getInstance(this.currentAccount).setUiResumed(false);
        }
    }

    private void updateFeedViewportActive(boolean active) {
        ChatActivity chatActivity;
        ChatActivityContainer container = this.chatContainer;
        if (container == null || (chatActivity = container.chatActivity) == null) {
            return;
        }
        chatActivity.setFeedViewportActive(active);
    }

    @Override
    public boolean canParentTabsSlide(MotionEvent ev, boolean forward) {
        ChatActivity chatActivity;
        ChatActivityContainer container = this.chatContainer;
        return container == null || (chatActivity = container.chatActivity) == null || chatActivity.getActionBar() == null || !this.chatContainer.chatActivity.getActionBar().isActionModeShowed();
    }

    @Override
    public boolean isLightStatusBar() {
        ChatActivity chatActivity;
        ChatActivityContainer container = this.chatContainer;
        if (container != null && (chatActivity = container.chatActivity) != null) {
            return chatActivity.isLightStatusBar();
        }
        return !Theme.isCurrentThemeDark();
    }

    private void reattachCurrentFeedVideoTexture() {
        ChatActivity chatActivity;
        ChatActivityContainer container = this.chatContainer;
        if (container == null || (chatActivity = container.chatActivity) == null) {
            return;
        }
        chatActivity.reattachCurrentFeedVideoTexture();
    }

    private void setupChatActionBar() {
        ChatActivity chatActivity;
        final ActionBar actionBar;
        ChatActivityContainer container = this.chatContainer;
        if (container == null || (chatActivity = container.chatActivity) == null || (actionBar = chatActivity.getActionBar()) == null) {
            return;
        }
        ActionBarMenu menu = actionBar.createMenu();
        if (menu.getItem(76) == null) {
            menu.addItem(76, R.drawable.msg_markread, this.chatContainer.chatActivity.themeDelegate).setContentDescription(LocaleController.getString(R.string.FeedMarkAllRead));
        }
        if (menu.getItem(75) == null) {
            menu.addItem(75, R.drawable.msg_settings, this.chatContainer.chatActivity.themeDelegate).setContentDescription(LocaleController.getString(R.string.FeedSettings));
        }
        if (this.hasMainTabs) {
            applyMainTabsHeaderLayout();
        }
        final ActionBar.ActionBarMenuOnItemClick defaultListener = actionBar.getActionBarMenuOnItemClick();
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1 && FeedActivity.this.hasMainTabs && !actionBar.isActionModeShowed()) {
                    return;
                }
                if (id == 76) {
                    FeedActivity.this.showMarkAllReadDialog();
                    return;
                }
                if (id == 75) {
                    FeedActivity.this.presentFragment(new FeedChannelsActivity());
                    return;
                }
                if (defaultListener != null) {
                    defaultListener.onItemClick(id);
                }
            }

            @Override
            public boolean canOpenMenu() {
                return defaultListener == null || defaultListener.canOpenMenu();
            }
        });
    }

    private void applyFloatingWindowLayout() {
        ChatActivityContainer container;
        ChatActivity chatActivity;
        if (getParentLayout() == null || !getParentLayout().isLayersLayout() || (container = this.chatContainer) == null || (chatActivity = container.chatActivity) == null) {
            return;
        }
        if (chatActivity.getActionBar() != null) {
            chatActivity.getActionBar().setOccupyStatusBar(false);
        }
        ChatAvatarContainer avatarContainer = chatActivity.avatarContainer;
        if (avatarContainer != null) {
            avatarContainer.setOccupyStatusBar(false);
        }
        ChatActivity.ChatActivityFragmentView contentView = chatActivity.contentView;
        if (contentView != null) {
            contentView.setOccupyStatusBar(false);
        }
    }

    /**
     * Shifts the chat header avatar so it lines up with the main-tabs layout
     * when the feed is embedded as a bottom navigation tab: the back button
     * is hidden in this mode, so the avatar drops its left margin (reserved
     * for the back arrow otherwise) and sits flush left.
     */
    private void applyMainTabsHeaderLayout() {
        ChatActivity chatActivity;
        ChatAvatarContainer avatarContainer;
        ChatActivityContainer container = this.chatContainer;
        if (container == null || (chatActivity = container.chatActivity) == null || (avatarContainer = chatActivity.avatarContainer) == null) {
            return;
        }
        ViewGroup.LayoutParams layoutParams = avatarContainer.getLayoutParams();
        if (layoutParams instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams marginLayoutParams = (ViewGroup.MarginLayoutParams) layoutParams;
            int leftMargin = 0;
            if (marginLayoutParams.leftMargin != leftMargin) {
                marginLayoutParams.leftMargin = leftMargin;
                avatarContainer.setLayoutParams(marginLayoutParams);
            }
        }
    }

    private void showMarkAllReadDialog() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.FeedMarkAllRead));
        builder.setMessage(LocaleController.getString(R.string.FeedMarkAllReadConfirm));
        builder.setPositiveButton(LocaleController.getString(R.string.MarkAsRead), (dialog, which) -> {
            markAllRead();
            BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, LocaleController.getString(R.string.FeedMarkAllReadDone)).show();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    public void markAllRead() {
        ChatActivity chatActivity;
        ChatActivityContainer container = this.chatContainer;
        if (container != null && (chatActivity = container.chatActivity) != null) {
            chatActivity.markFeedAsRead();
        } else {
            FeedController.getInstance(this.currentAccount).markAllRead();
        }
    }

    @Override
    public void onParentScrollToTop() {
        ChatActivity chatActivity;
        ChatActivityContainer container = this.chatContainer;
        if (container == null || (chatActivity = container.chatActivity) == null) {
            return;
        }
        chatActivity.onPageDownClicked();
    }

    private void setupChatTitle() {
        ChatActivity chatActivity;
        ChatAvatarContainer avatarContainer;
        ChatActivityContainer container = this.chatContainer;
        if (container == null || (chatActivity = container.chatActivity) == null || (avatarContainer = chatActivity.avatarContainer) == null) {
            return;
        }
        avatarContainer.setTitle(LocaleController.getString(R.string.Feed));
        this.chatContainer.chatActivity.avatarContainer.setFeedAvatar();
        updateFeedSubtitle();
    }

    private void updateFeedSubtitle() {
        FeedController feedController = FeedController.getInstance(this.currentAccount);
        setFeedSubtitle(feedController.getIncludedChannelCount());
        feedController.loadChannels((channels, includedCount, failed, configGeneration) -> {
            if (failed) {
                return;
            }
            setFeedSubtitle(includedCount);
        });
    }

    private void setFeedSubtitle(int count) {
        ChatActivity chatActivity;
        ChatAvatarContainer avatarContainer;
        ChatActivityContainer container = this.chatContainer;
        if (container == null || (chatActivity = container.chatActivity) == null || (avatarContainer = chatActivity.avatarContainer) == null) {
            return;
        }
        avatarContainer.setSubtitle(LocaleController.formatPluralString("Channels", count));
        View subtitleTextView = this.chatContainer.chatActivity.avatarContainer.getSubtitleTextView();
        if (subtitleTextView != null) {
            subtitleTextView.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void setParentTabsGlassInvalidationCallback(Runnable callback) {
        this.parentTabsGlassInvalidationCallback = callback;
    }

    @Override
    public org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode getGlassSource() {
        ChatActivity chatActivity;
        ChatActivityContainer container = this.chatContainer;
        if (container == null || (chatActivity = container.chatActivity) == null) {
            return null;
        }
        return chatActivity.getGlassSource();
    }

    private void invalidateParentTabsGlass() {
        Runnable callback = this.parentTabsGlassInvalidationCallback;
        if (callback != null) {
            callback.run();
        }
    }
}
