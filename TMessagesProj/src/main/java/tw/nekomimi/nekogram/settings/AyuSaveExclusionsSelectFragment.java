package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import org.telegram.ui.Components.ForegroundColorSpanThemable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.radolyn.ayugram.AyuSaveExclusions;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Adapters.SearchAdapterHelper;
import org.telegram.ui.Cells.GraySectionCell;
import org.telegram.ui.Components.ColoredImageSpan;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.FlickerLoadingView;
import org.telegram.ui.Components.FragmentFloatingButton;
import org.telegram.ui.Components.FragmentSearchField;
import org.telegram.ui.Components.FragmentSpansContainer;
import org.telegram.ui.Components.GroupCreateSpan;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.StickerEmptyView;
import org.telegram.ui.Components.inset.WindowAnimatedInsetsProvider;
import org.telegram.ui.HeaderShadowView;
import org.telegram.ui.LaunchActivity;
import org.telegram.utils.glass.BaseFragmentSimpleGlass;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;

import me.vkryl.android.animator.FactorAnimator;

/** Edits one save-exclusion list; selections are persisted only on confirmation. */
public class AyuSaveExclusionsSelectFragment extends BaseFragment implements View.OnClickListener,
        NotificationCenter.NotificationCenterDelegate, FactorAnimator.Target, WindowAnimatedInsetsProvider.Listener {
    private static final int TYPE_HEADER = 0;
    private static final int TYPE_CATEGORY = 1;
    private static final int TYPE_PEER = 2;

    private final boolean always;
    private final Runnable onChanged;
    private final HashSet<String> ids = new HashSet<>();
    private final HashSet<String> initialIds = new HashSet<>();
    private final int initialFlags;
    private int flags;
    private final HashMap<Integer, GroupCreateSpan> categorySpans = new HashMap<>();
    private final HashMap<String, GroupCreateSpan> peerSpans = new HashMap<>();
    private final HashMap<String, TLObject> spanPeers = new HashMap<>();
    private final HashMap<String, String> spanNames = new HashMap<>();
    private final HashSet<String> requestedPeers = new HashSet<>();
    private final ArrayList<Item> items = new ArrayList<>();
    private final ArrayList<Item> localResults = new ArrayList<>();
    private final FactorAnimator selectorHeight = new FactorAnimator(0, this, CubicBezierInterpolator.EASE_OUT_QUINT, 350);
    private RecyclerListView listView;
    private LinearLayoutManager layoutManager;
    private ListAdapter adapter;
    private FragmentSearchField searchField;
    private FragmentSpansContainer spansContainer;
    private FragmentFloatingButton floatingButton;
    private StickerEmptyView emptyView;
    private HeaderShadowView headerShadowView;
    private View actionBarBackgroundView;
    private WindowAnimatedInsetsProvider animatedInsetsProvider;
    private SearchAdapterHelper searchHelper;
    private GroupCreateSpan currentDeletingSpan;
    private Context viewContext;
    private int maxSize = dp(144);
    private int insetLeft;
    private int insetRight;
    private int navigationBarHeight;
    private int imeInsetHeight;
    private int searchGeneration;
    private String query = "";
    private Runnable searchRunnable;
    private boolean localSearchPending;
    private boolean syncingSpans;
    private boolean destroyed;

    private static final class PeerEntry {
        final String key;
        final TLObject peer;
        final boolean secret;
        final boolean loaded;
        final String name;
        final String username;

        PeerEntry(String key, TLObject peer, boolean secret, boolean loaded, String name, String username) {
            this.key = key;
            this.peer = peer;
            this.secret = secret;
            this.loaded = loaded;
            this.name = name;
            this.username = username;
        }
    }

    private static final class Item {
        final int type;
        final AyuSaveExclusionCategory category;
        final PeerEntry entry;
        final CharSequence title;
        final CharSequence status;

        Item(CharSequence title) {
            type = TYPE_HEADER;
            category = null;
            entry = null;
            this.title = title;
            status = null;
        }

        Item(AyuSaveExclusionCategory category) {
            type = TYPE_CATEGORY;
            this.category = category;
            entry = null;
            title = category.getTitle();
            status = null;
        }

        Item(PeerEntry entry, CharSequence title, CharSequence status) {
            type = TYPE_PEER;
            category = null;
            this.entry = entry;
            this.title = title;
            this.status = status;
        }
    }

    private class PickerContentView extends FrameLayout implements Theme.Colorable {
        PickerContentView(Context context) {
            super(context);
            updateColors();
        }

        @Override
        public void updateColors() {
            setBackgroundColor(AyuSaveExclusionsSelectFragment.this.getThemedColor(Theme.key_windowBackgroundWhite));
            invalidate();
        }
    }

    public AyuSaveExclusionsSelectFragment(boolean always, Runnable onChanged) {
        this.always = always;
        this.onChanged = onChanged;
        ids.addAll(AyuSaveExclusions.getIds(always));
        initialIds.addAll(ids);
        flags = initialFlags = AyuSaveExclusions.getFlags(always);
    }

    @Override
    public boolean onFragmentCreate() {
        destroyed = false;
        getNotificationCenter().addObserver(this, NotificationCenter.updateInterfaces);
        getNotificationCenter().addObserver(this, NotificationCenter.dialogsNeedReload);
        loadMissingPeers(new ArrayList<>(ids));
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        cancelSearch();
        getNotificationCenter().removeObserver(this, NotificationCenter.updateInterfaces);
        getNotificationCenter().removeObserver(this, NotificationCenter.dialogsNeedReload);
        if (animatedInsetsProvider != null) {
            animatedInsetsProvider.unsubscribeFromWindowInsetsAnimation(this);
            animatedInsetsProvider = null;
        }
        AndroidUtilities.removeAdjustResize(getParentActivity(), classGuid);
        super.onFragmentDestroy();
    }

    @Override
    protected boolean useSimpleActionBar() {
        return true;
    }

    /** The list the glass header samples. */
    @Override
    protected RecyclerListView getListViewForSimpleGlass() {
        return listView;
    }

    @Override
    public View createView(Context context) {
        cancelSearch();
        query = "";
        viewContext = context;
        categorySpans.clear();
        peerSpans.clear();
        spanPeers.clear();
        spanNames.clear();
        currentDeletingSpan = null;
        setHasOwnBackground(true);
        simpleActionBar.setBackButtonImage(R.drawable.ic_ab_back);
        simpleActionBar.setTitle(getString(always ? R.string.AyuSaveExclusionsAlwaysList : R.string.AyuSaveExclusionsNeverList));
        simpleActionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1 && onBackPressed(true)) {
                    finishFragment();
                }
            }
        });
        FrameLayout root = new PickerContentView(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                maxSize = dp(AndroidUtilities.isTablet() || MeasureSpec.getSize(heightMeasureSpec) > MeasureSpec.getSize(widthMeasureSpec) ? 144 : 56);
                measureChildWithMargins(simpleActionBar, widthMeasureSpec, 0, heightMeasureSpec, 0);
                int headerHeight = simpleActionBar.getMeasuredHeight();
                ((MarginLayoutParams) emptyView.getLayoutParams()).topMargin = headerHeight + dp(48);
                setHeaderMargins(searchField, headerHeight, dp(11));
                setHeaderMargins(spansContainer, headerHeight, 0);
                setHeaderMargins(headerShadowView, headerHeight, 0);
                spansContainer.getLayoutParams().height = maxSize;
                updateHeaderBackground(headerHeight);
                updateListPadding();
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }

            @Override
            protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                super.onLayout(changed, left, top, right, bottom);
                updateFloatingButton();
                updateHeaderY();
                updateHeaderShadow();
            }

            @Override
            protected void dispatchDraw(@NonNull Canvas canvas) {
                super.dispatchDraw(canvas);
                AndroidUtilities.drawNavigationBarProtection(canvas, this, getThemedColor(Theme.key_windowBackgroundWhite), navigationBarHeight);
            }
        };
        fragmentView = root;
        root.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        root.setFocusableInTouchMode(true);
        root.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        searchField = new FragmentSearchField(context, resourceProvider);
        searchField.setHintText(getString(R.string.SearchForChats), false);
        searchField.editText.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        searchField.editText.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                onDonePressed();
                return true;
            }
            return false;
        });
        searchField.editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString();
                cancelDeletingSpan();
                rebuild();
            }
        });
        searchField.editText.setOnKeyListener(new View.OnKeyListener() {
            private boolean wasEmpty;
            @Override
            public boolean onKey(View view, int keyCode, KeyEvent event) {
                if (keyCode != KeyEvent.KEYCODE_DEL) {
                    return false;
                }
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    wasEmpty = searchField.editText.length() == 0;
                } else if (event.getAction() == KeyEvent.ACTION_UP && wasEmpty && !spansContainer.allSpans.isEmpty()) {
                    spansContainer.removeSpan(spansContainer.allSpans.get(spansContainer.allSpans.size() - 1));
                    return true;
                }
                return false;
            }
        });
        spansContainer = new FragmentSpansContainer(context, currentAccount) {
            @Override
            public void removeSpan(GroupCreateSpan span) {
                if (!syncingSpans) {
                    Object key = span.getTag();
                    if (key instanceof String) {
                        ids.remove(key);
                        peerSpans.remove(key);
                        spanPeers.remove(key);
                        spanNames.remove(key);
                    } else if (key instanceof AyuSaveExclusionCategory) {
                        int flag = ((AyuSaveExclusionCategory) key).flag;
                        flags &= ~flag;
                        categorySpans.remove(flag);
                    }
                }
                if (span == currentDeletingSpan) {
                    currentDeletingSpan = null;
                }
                super.removeSpan(span);
                if (!syncingSpans) {
                    checkVisibleRows();
                }
            }
        };
        spansContainer.setDelegate(height -> selectorHeight.animateTo(Math.min(height, maxSize)));
        spansContainer.getSpansContainer().setOnClickListener(view -> {
            cancelDeletingSpan();
            searchField.editText.requestFocus();
            AndroidUtilities.showKeyboard(searchField.editText);
        });
        FlickerLoadingView loading = new FlickerLoadingView(context);
        loading.setViewType(FlickerLoadingView.USERS_TYPE);
        loading.showDate(false);
        emptyView = new StickerEmptyView(context, loading, StickerEmptyView.STICKER_TYPE_SEARCH, resourceProvider);
        emptyView.title.setText(getString(R.string.NoResult));
        emptyView.showProgress(false, false);
        root.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        layoutManager = new LinearLayoutManager(context);
        listView = new RecyclerListView(context, resourceProvider);
        listView.setLayoutManager(layoutManager);
        listView.setAdapter(adapter = new ListAdapter());
        listView.setEmptyView(emptyView);
        listView.setAnimateEmptyView(true, RecyclerListView.EMPTY_VIEW_ANIMATION_TYPE_ALPHA);
        listView.setVerticalScrollBarEnabled(false);
        listView.setClipToPadding(false);
        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= items.size()) {
                return;
            }
            Item item = items.get(position);
            if (item.type == TYPE_CATEGORY) {
                flags ^= item.category.flag;
                syncSpans(false);
            } else if (item.type == TYPE_PEER) {
                if (!ids.remove(item.entry.key)) {
                    ids.add(item.entry.key);
                    if (item.entry.loaded) {
                        if (item.entry.peer instanceof TLRPC.User) {
                            getMessagesController().putUser((TLRPC.User) item.entry.peer, false);
                        } else if (item.entry.peer instanceof TLRPC.Chat) {
                            getMessagesController().putChat((TLRPC.Chat) item.entry.peer, false);
                        }
                    }
                }
                syncSpans(false);
                if (!TextUtils.isEmpty(query)) {
                    searchField.editText.setText("");
                    searchField.editText.requestFocus();
                    AndroidUtilities.showKeyboard(searchField.editText);
                }
            }
            checkVisibleRows();
        });
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                updateHeaderShadow();
            }
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    searchField.editText.hideActionMode();
                    AndroidUtilities.hideKeyboard(searchField.editText);
                }
            }
        });
        root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        floatingButton = new FragmentFloatingButton(context, resourceProvider);
        floatingButton.imageView.setImageResource(R.drawable.floating_check);
        floatingButton.setContentDescription(getString(R.string.Done));
        floatingButton.setOnClickListener(view -> onDonePressed());
        root.addView(floatingButton, FragmentFloatingButton.createDefaultLayoutParams());
        actionBarBackgroundView = new View(context);
        root.addView(actionBarBackgroundView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 0, Gravity.TOP));
        // the glass of the header extends under the search field and the chips
        actionBarBackgroundView.setBackground(getBaseSimpleGlass().createActionBarFadeDrawable(actionBarBackgroundView));
        AndroidUtilities.removeFromParent(simpleActionBar);
        root.addView(simpleActionBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        root.addView(searchField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 40, Gravity.TOP, 11, 0, 11, 0));
        root.addView(spansContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, maxSize / AndroidUtilities.density, Gravity.TOP));
        headerShadowView = new HeaderShadowView(context, parentLayout);
        headerShadowView.setShadowVisible(false, false);
        root.addView(headerShadowView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 5, Gravity.TOP));
        searchHelper = new SearchAdapterHelper(false, currentAccount);
        searchHelper.setAllowGlobalResults(false);
        searchHelper.setDelegate(new SearchAdapterHelper.SearchAdapterHelperDelegate() {
            @Override
            public void onDataSetChanged(int searchId) {
                if (canApplySearchResults(searchId)) {
                    rebuildSearchItems();
                }
            }
            @Override
            public boolean canApplySearchResults(int searchId) {
                return !destroyed && searchId == searchGeneration && !TextUtils.isEmpty(query.trim());
            }
        });
        syncSpans(false);
        rebuild();
        if (animatedInsetsProvider != null) {
            animatedInsetsProvider.unsubscribeFromWindowInsetsAnimation(this);
        }
        if (LaunchActivity.instance != null) {
            animatedInsetsProvider = LaunchActivity.instance.getRootAnimatedInsetsListener();
            if (animatedInsetsProvider != null) {
                animatedInsetsProvider.subscribeToWindowInsetsAnimation(this);
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            onInsetsInternal(view, insets);
            imeInsetHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            updateFloatingButton();
            return WindowInsetsCompat.CONSUMED;
        });
        return root;
    }

    private void setHeaderMargins(View view, int top, int side) {
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        params.topMargin = top;
        params.leftMargin = insetLeft + side;
        params.rightMargin = insetRight + side;
    }

    private int visibleSelectorHeight() {
        return Math.min(maxSize, Math.round(selectorHeight.getFactor()));
    }

    /**
     * The glass band behind the header follows the search row and the chips, so it ends exactly where
     * the list starts and never covers the first row when the chips are collapsed.
     */
    private void updateHeaderBackground(int headerHeight) {
        if (actionBarBackgroundView == null) {
            return;
        }
        final ViewGroup.LayoutParams params = actionBarBackgroundView.getLayoutParams();
        final int height = headerHeight + dp(48) + visibleSelectorHeight();
        if (params.height != height) {
            params.height = height;
            actionBarBackgroundView.setLayoutParams(params);
        }
    }

    private void updateListPadding() {
        if (listView != null) {
            // BaseFragmentSimpleGlass pulls the list past both edges with negative margins so content
            // scrolls under the glass; the page owns the paddings, so it adds that overscan back.
            final int overscanTop = BaseFragmentSimpleGlass.getOverscan(mSystemInsets.top);
            final int overscanBottom = BaseFragmentSimpleGlass.getOverscan(mSystemInsets.bottom);
            listView.setPadding(insetLeft,
                    simpleActionBar.getMeasuredHeight() + dp(48) + visibleSelectorHeight() + overscanTop,
                    insetRight, navigationBarHeight + overscanBottom);
        }
        if (emptyView != null) {
            emptyView.setPadding(insetLeft, 0, insetRight, navigationBarHeight);
        }
    }

    private void updateHeaderY() {
        if (searchField != null) {
            searchField.setTranslationY(visibleSelectorHeight());
        }
        if (headerShadowView != null) {
            headerShadowView.setTranslationY(dp(48) + visibleSelectorHeight());
        }
    }

    private void updateFloatingButton() {
        if (floatingButton != null) {
            floatingButton.setTranslationY(-Math.max(navigationBarHeight, imeInsetHeight));
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) floatingButton.getLayoutParams();
            if (LocaleController.isRTL) {
                params.leftMargin = insetLeft + dp(14);
            } else {
                params.rightMargin = insetRight + dp(14);
            }
        }
    }

    private void updateHeaderShadow() {
        if (headerShadowView != null && layoutManager != null) {
            View first = layoutManager.findViewByPosition(0);
            headerShadowView.setShadowVisible(layoutManager.findFirstVisibleItemPosition() > 0
                    || first != null && first.getTop() < listView.getPaddingTop(), true);
        }
    }

    @Override
    public void onFactorChanged(int id, float factor, float fraction, FactorAnimator animator) {
        if (listView == null) {
            return;
        }
        int oldTop = listView.getPaddingTop();
        updateListPadding();
        updateHeaderY();
        // the chips are animating: keep the glass band on the same edge as the list padding
        updateHeaderBackground(simpleActionBar != null ? simpleActionBar.getMeasuredHeight() : 0);
        actionBarBackgroundView.invalidate();
        if (oldTop != listView.getPaddingTop() && !headerShadowView.isShadowVisible()) {
            listView.scrollBy(0, oldTop - listView.getPaddingTop());
        }
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public boolean drawEdgeNavigationBar() {
        return false;
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        insetLeft = left;
        insetRight = right;
        navigationBarHeight = bottom;
        updateListPadding();
        updateFloatingButton();
        if (fragmentView != null) {
            fragmentView.requestLayout();
        }
    }

    @Override
    public View getAnimatedInsetsTargetView() {
        return fragmentView;
    }

    @Override
    public void onAnimatedInsetsChanged(View view, WindowInsetsCompat insets) {
        imeInsetHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
        updateFloatingButton();
    }

    @Override
    public void onResume() {
        super.onResume();
        AndroidUtilities.requestAdjustResize(getParentActivity(), classGuid);
        if (adapter != null) {
            syncSpans(false);
            rebuild();
        }
    }

    private boolean hasChanges() {
        return flags != initialFlags || !ids.equals(initialIds);
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (!TextUtils.isEmpty(query)) {
            if (invoked && searchField != null) {
                searchField.editText.setText("");
                searchField.editText.clearFocus();
                AndroidUtilities.hideKeyboard(searchField.editText);
            }
            return false;
        }
        if (hasChanges()) {
            if (invoked && (getVisibleDialog() == null || !getVisibleDialog().isShowing()) && getParentActivity() != null) {
                AlertDialog dialog = new AlertDialog.Builder(getParentActivity(), resourceProvider)
                        .setTitle(getString(R.string.FilterDiscardTitle))
                        .setMessage(getString(R.string.AyuSaveExclusionsDiscardAlert))
                        .setPositiveButton(getString(R.string.ApplyTheme), (alert, which) -> onDonePressed())
                        .setNegativeButton(getString(R.string.PassportDiscard), (alert, which) -> finishFragment())
                        .setNeutralButton(getString(R.string.Cancel), null)
                        .create();
                showDialog(dialog);
            }
            return false;
        }
        return super.onBackPressed(invoked);
    }

    @Override
    public boolean canBeginSlide() {
        return TextUtils.isEmpty(query) && !hasChanges() && super.canBeginSlide();
    }

    @Override
    public boolean isSwipeBackEnabled(MotionEvent event) {
        return TextUtils.isEmpty(query) && !hasChanges() && super.isSwipeBackEnabled(event);
    }

    private void onDonePressed() {
        AyuSaveExclusions.setIds(always, new HashSet<>(ids));
        AyuSaveExclusions.setFlags(always, flags);
        if (onChanged != null) {
            onChanged.run();
        }
        finishFragment();
    }

    private void cancelDeletingSpan() {
        if (currentDeletingSpan != null) {
            currentDeletingSpan.cancelDeleteAnimation();
            currentDeletingSpan = null;
        }
    }

    @Override
    public void onClick(View view) {
        GroupCreateSpan span = (GroupCreateSpan) view;
        if (span.isDeleting()) {
            spansContainer.removeSpan(span);
        } else {
            cancelDeletingSpan();
            currentDeletingSpan = span;
            span.startDeleteAnimation();
        }
    }

    private void syncSpans(boolean refreshAvatars) {
        if (spansContainer == null || viewContext == null) {
            return;
        }
        syncingSpans = true;
        try {
            for (AyuSaveExclusionCategory category : AyuSaveExclusionCategory.ALL) {
                GroupCreateSpan span = categorySpans.get(category.flag);
                if ((flags & category.flag) != 0 && span == null) {
                    span = new AyuSaveExclusionSpan(viewContext, category, resourceProvider);
                    span.setTag(category);
                    span.setOnClickListener(this);
                    categorySpans.put(category.flag, span);
                    spansContainer.addSpan(span);
                } else if ((flags & category.flag) == 0 && span != null) {
                    categorySpans.remove(category.flag);
                    spansContainer.removeSpan(span);
                }
            }
            for (String key : new ArrayList<>(peerSpans.keySet())) {
                if (!ids.contains(key)) {
                    spansContainer.removeSpan(peerSpans.remove(key));
                    spanPeers.remove(key);
                    spanNames.remove(key);
                }
            }
            ArrayList<String> ordered = new ArrayList<>(ids);
            Collections.sort(ordered);
            for (String key : ordered) {
                PeerEntry entry = peerEntry(key);
                GroupCreateSpan previous = peerSpans.get(key);
                TLObject avatarPeer = entry.loaded ? entry.peer : null;
                if (previous != null && !refreshAvatars && spanPeers.get(key) == avatarPeer && entry.name.equals(spanNames.get(key))) {
                    continue;
                }
                if (previous != null) {
                    spansContainer.removeSpan(previous);
                }
                GroupCreateSpan span = new AyuSaveExclusionSpan(viewContext, avatarPeer, entry.name, entry.secret, resourceProvider);
                span.setTag(key);
                span.setOnClickListener(this);
                peerSpans.put(key, span);
                spanPeers.put(key, avatarPeer);
                spanNames.put(key, entry.name);
                spansContainer.addSpan(span);
            }
        } finally {
            syncingSpans = false;
        }
    }

    private void checkVisibleRows() {
        if (listView == null) {
            return;
        }
        for (int i = 0; i < listView.getChildCount(); i++) {
            View view = listView.getChildAt(i);
            if (view instanceof AyuSaveExclusionCell && view.getTag() instanceof Item) {
                Item item = (Item) view.getTag();
                ((AyuSaveExclusionCell) view).setChecked(item.type == TYPE_CATEGORY
                        ? (flags & item.category.flag) != 0 : ids.contains(item.entry.key), true);
            }
        }
    }

    private ArrayList<PeerEntry> allEntries() {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        TLRPC.User me = getUserConfig().getCurrentUser();
        if (me != null) {
            getMessagesController().putUser(me, true);
            keys.add(String.valueOf(me.id));
        }
        for (TLRPC.Dialog dialog : getMessagesController().getAllDialogs()) {
            if (!(dialog instanceof TLRPC.TL_dialogFolder) && dialog.id != 0) {
                keys.add(String.valueOf(dialog.id));
            }
        }
        ArrayList<String> selected = new ArrayList<>(ids);
        Collections.sort(selected);
        keys.addAll(selected);
        ArrayList<PeerEntry> result = new ArrayList<>();
        for (String key : keys) {
            result.add(peerEntry(key));
        }
        return result;
    }

    private static Long parseId(String key) {
        try {
            return Long.parseLong(key);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private long encryptedUserId(TLRPC.EncryptedChat chat) {
        long selfId = getUserConfig().getClientUserId();
        long userId = chat.admin_id == selfId ? chat.participant_id : chat.admin_id;
        if (userId <= 0 || userId == selfId) {
            userId = chat.user_id;
        }
        return userId;
    }

    private PeerEntry peerEntry(String key) {
        Long id = parseId(key);
        TLObject peer = null;
        boolean secret = id != null && DialogObject.isEncryptedDialog(id);
        if (id != null) {
            if (secret) {
                TLRPC.EncryptedChat chat = getMessagesController().getEncryptedChat(DialogObject.getEncryptedChatId(id));
                if (chat != null) {
                    peer = getMessagesController().getUser(encryptedUserId(chat));
                }
            } else if (DialogObject.isUserDialog(id)) {
                peer = getMessagesController().getUser(id);
            } else if (DialogObject.isChatDialog(id) && id != Long.MIN_VALUE) {
                peer = getMessagesController().getChat(-id);
            }
        }
        return entryForPeer(key, peer, secret);
    }

    private PeerEntry entryForPeer(String key, TLObject peer, boolean secret) {
        boolean loaded = peer instanceof TLRPC.User || peer instanceof TLRPC.Chat;
        String name;
        String username = null;
        if (peer instanceof TLRPC.User) {
            TLRPC.User user = (TLRPC.User) peer;
            name = UserObject.isUserSelf(user) && !secret ? getString(R.string.SavedMessages) : UserObject.getUserName(user);
            username = UserObject.getPublicUsername(user);
        } else if (peer instanceof TLRPC.Chat) {
            name = ((TLRPC.Chat) peer).title;
            username = ChatObject.getPublicUsername((TLRPC.Chat) peer);
        } else {
            name = secret ? getString(R.string.AyuSaveExclusionsSecret) + " · " + key : key;
            TLRPC.TL_user placeholder = new TLRPC.TL_user();
            placeholder.first_name = name;
            peer = placeholder;
        }
        return new PeerEntry(key, peer, secret, loaded, name == null ? key : name, username);
    }

    private CharSequence withSecretLock(CharSequence name) {
        Drawable drawable = viewContext.getResources().getDrawable(R.drawable.list_secret).mutate();
        SpannableStringBuilder text = new SpannableStringBuilder("l ").append(name);
        text.setSpan(new ColoredImageSpan(drawable), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    private Item normalItem(PeerEntry entry) {
        return new Item(entry, entry.secret ? withSecretLock(entry.name) : entry.name,
                entry.secret ? getString(R.string.AccDescrSecretChat) : entry.loaded ? null : "");
    }

    private void rebuild() {
        if (adapter == null || searchHelper == null) {
            return;
        }
        ArrayList<PeerEntry> entries = allEntries();
        ArrayList<String> missing = new ArrayList<>();
        for (PeerEntry entry : entries) {
            if (!entry.loaded) {
                missing.add(entry.key);
            }
        }
        loadMissingPeers(missing);
        cancelSearch();
        if (TextUtils.isEmpty(query.trim())) {
            items.clear();
            items.add(new Item(getString(R.string.AyuSaveExclusionsCategories)));
            for (AyuSaveExclusionCategory category : AyuSaveExclusionCategory.ALL) {
                items.add(new Item(category));
            }
            items.add(new Item(getString(R.string.Chats)));
            for (PeerEntry entry : entries) {
                items.add(normalItem(entry));
            }
            listView.setVerticalScrollBarEnabled(false);
            emptyView.showProgress(false, false);
            adapter.notifyDataSetChanged();
            return;
        }
        final int generation = searchGeneration;
        final String text = query.trim();
        localSearchPending = true;
        items.clear();
        listView.setVerticalScrollBarEnabled(true);
        emptyView.showProgress(true, false);
        adapter.notifyDataSetChanged();
        searchRunnable = () -> {
            searchRunnable = null;
            if (destroyed || generation != searchGeneration) {
                return;
            }
            searchHelper.queryServerSearch(text, true, true, true, true, false, 0, false, 0, generation);
            Utilities.searchQueue.postRunnable(() -> {
                ArrayList<Item> matches = searchLocal(entries, text);
                AndroidUtilities.runOnUIThread(() -> {
                    if (destroyed || generation != searchGeneration) {
                        return;
                    }
                    localSearchPending = false;
                    localResults.clear();
                    localResults.addAll(matches);
                    ArrayList<Object> peers = new ArrayList<>();
                    for (Item match : matches) {
                        if (match.entry.loaded && !match.entry.secret) {
                            peers.add(match.entry.peer);
                        }
                    }
                    searchHelper.mergeResults(peers);
                    rebuildSearchItems();
                });
            });
        };
        AndroidUtilities.runOnUIThread(searchRunnable, 300);
    }

    private ArrayList<Item> searchLocal(ArrayList<PeerEntry> entries, String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        String usernameQuery = normalized.startsWith("@") ? normalized.substring(1) : normalized;
        String translitQuery = LocaleController.getInstance().getTranslitString(normalized);
        ArrayList<Item> results = new ArrayList<>();
        for (PeerEntry entry : entries) {
            String name = entry.name.toLowerCase(Locale.ROOT);
            String translitName = LocaleController.getInstance().getTranslitString(name);
            boolean nameMatches = name.contains(normalized)
                    || !TextUtils.isEmpty(translitName) && translitName.contains(normalized)
                    || !TextUtils.isEmpty(translitQuery) && (name.contains(translitQuery)
                    || !TextUtils.isEmpty(translitName) && translitName.contains(translitQuery));
            boolean usernameMatches = !TextUtils.isEmpty(usernameQuery) && !TextUtils.isEmpty(entry.username)
                    && entry.username.toLowerCase(Locale.ROOT).startsWith(usernameQuery);
            if (!nameMatches && !usernameMatches) {
                continue;
            }
            CharSequence title = highlight(entry.name, text);
            CharSequence status = entry.secret ? getString(R.string.AccDescrSecretChat)
                    : usernameMatches ? highlight("@" + entry.username, "@" + usernameQuery) : entry.loaded ? null : "";
            // Secret locks are added on the UI thread with the result binding.
            results.add(new Item(entry, title, status));
        }
        return results;
    }

    private CharSequence highlight(String text, String part) {
        SpannableStringBuilder result = new SpannableStringBuilder(text);
        int index = AndroidUtilities.indexOfIgnoreCase(text, part);
        if (index >= 0) {
            result.setSpan(new ForegroundColorSpanThemable(Theme.key_windowBackgroundWhiteBlueText4, resourceProvider),
                    index, index + part.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return result;
    }

    private void rebuildSearchItems() {
        if (destroyed || TextUtils.isEmpty(query.trim()) || adapter == null) {
            return;
        }
        items.clear();
        HashSet<String> seen = new HashSet<>();
        for (Item item : localResults) {
            items.add(item);
            seen.add(item.entry.key);
        }
        for (TLObject peer : searchHelper.getLocalServerSearch()) {
            String key = peer instanceof TLRPC.User ? String.valueOf(((TLRPC.User) peer).id)
                    : peer instanceof TLRPC.Chat ? String.valueOf(-((TLRPC.Chat) peer).id) : null;
            if (key == null || !seen.add(key)) {
                continue;
            }
            PeerEntry entry = entryForPeer(key, peer, false);
            items.add(new Item(entry, entry.name, TextUtils.isEmpty(entry.username) ? null
                    : highlight("@" + entry.username, query.trim().startsWith("@") ? query.trim() : "@" + query.trim())));
        }
        emptyView.showProgress(localSearchPending || searchHelper.isSearchInProgress(), true);
        adapter.notifyDataSetChanged();
    }

    private void cancelSearch() {
        searchGeneration++;
        if (searchRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(searchRunnable);
            searchRunnable = null;
        }
        localSearchPending = false;
        localResults.clear();
        if (searchHelper != null) {
            searchHelper.queryServerSearch(null, true, true, true, true, false, 0, false, 0, searchGeneration);
            searchHelper.mergeResults(null);
        }
    }

    /** Loads absent peers on the storage queue without dropping their selected identities. */
    private void loadMissingPeers(ArrayList<String> keys) {
        ArrayList<Long> userIds = new ArrayList<>();
        ArrayList<Long> chatIds = new ArrayList<>();
        ArrayList<Integer> encryptedIds = new ArrayList<>();
        for (String key : keys) {
            Long id = parseId(key);
            if (id == null || !requestedPeers.add(key)) {
                continue;
            }
            if (DialogObject.isEncryptedDialog(id)) {
                TLRPC.EncryptedChat chat = getMessagesController().getEncryptedChat(DialogObject.getEncryptedChatId(id));
                if (chat == null) {
                    encryptedIds.add(DialogObject.getEncryptedChatId(id));
                } else if (getMessagesController().getUser(encryptedUserId(chat)) == null) {
                    userIds.add(encryptedUserId(chat));
                }
            } else if (DialogObject.isUserDialog(id) && getMessagesController().getUser(id) == null) {
                userIds.add(id);
            } else if (DialogObject.isChatDialog(id) && id != Long.MIN_VALUE && getMessagesController().getChat(-id) == null) {
                chatIds.add(-id);
            }
        }
        if (userIds.isEmpty() && chatIds.isEmpty() && encryptedIds.isEmpty()) {
            return;
        }
        MessagesStorage storage = getMessagesStorage();
        long selfId = getUserConfig().getClientUserId();
        storage.getStorageQueue().postRunnable(() -> {
            ArrayList<TLRPC.EncryptedChat> encryptedChats = new ArrayList<>();
            for (int id : encryptedIds) {
                TLRPC.EncryptedChat chat = storage.getEncryptedChat(id);
                if (chat != null) {
                    encryptedChats.add(chat);
                    long userId = chat.admin_id == selfId ? chat.participant_id : chat.admin_id;
                    if (userId <= 0 || userId == selfId) {
                        userId = chat.user_id;
                    }
                    userIds.add(userId);
                }
            }
            ArrayList<TLRPC.User> users = userIds.isEmpty() ? new ArrayList<>() : storage.getUsers(userIds);
            ArrayList<TLRPC.Chat> chats = chatIds.isEmpty() ? new ArrayList<>() : storage.getChats(chatIds);
            AndroidUtilities.runOnUIThread(() -> {
                if (destroyed) {
                    return;
                }
                getMessagesController().putUsers(users, true);
                getMessagesController().putChats(chats, true);
                getMessagesController().putEncryptedChats(encryptedChats, true);
                if (adapter != null) {
                    syncSpans(false);
                    rebuild();
                }
            });
        });
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (adapter == null || destroyed) {
            return;
        }
        if (id == NotificationCenter.updateInterfaces || id == NotificationCenter.dialogsNeedReload) {
            int mask = id == NotificationCenter.updateInterfaces && args.length > 0 && args[0] instanceof Integer ? (Integer) args[0] : 0;
            syncSpans((mask & MessagesController.UPDATE_MASK_AVATAR) != 0);
            rebuild();
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() != TYPE_HEADER;
        }

        @Override
        public int getItemViewType(int position) {
            return items.get(position).type;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new RecyclerListView.Holder(viewType == TYPE_HEADER
                    ? new GraySectionCell(parent.getContext(), resourceProvider)
                    : new AyuSaveExclusionCell(parent.getContext(), resourceProvider));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Item item = items.get(position);
            holder.itemView.setTag(item);
            if (item.type == TYPE_HEADER) {
                ((GraySectionCell) holder.itemView).setText(item.title);
            } else {
                AyuSaveExclusionCell cell = (AyuSaveExclusionCell) holder.itemView;
                if (item.type == TYPE_CATEGORY) {
                    cell.setCategory(item.category);
                    cell.setChecked((flags & item.category.flag) != 0, false);
                } else {
                    CharSequence title = item.title;
                    if (item.entry.secret && !TextUtils.isEmpty(query.trim())) {
                        title = withSecretLock(title);
                    }
                    cell.setPeer(item.entry.peer, title, item.status);
                    cell.setChecked(ids.contains(item.entry.key), false);
                }
                cell.setCheckBoxEnabled(true);
            }
        }

        @Override
        public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
            if (holder.itemView instanceof AyuSaveExclusionCell) {
                ((AyuSaveExclusionCell) holder.itemView).recycle();
            }
            super.onViewRecycled(holder);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }
}
