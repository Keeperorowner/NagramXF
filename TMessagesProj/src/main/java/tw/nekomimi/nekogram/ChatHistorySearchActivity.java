/*
 * This is the source code of Nagram XF for Android.
 * It is licensed under GNU GPL v. 3 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 * 
 * https://github.com/Keeperorowner/NagramXF
 * 
 * Please, be respectful and credit the original author if you use this code.
 *
 * Copyright @Keeperorowner, 2025-2030
 */

package tw.nekomimi.nekogram;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.os.Bundle;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.ChatObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Components.FilterTabsView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ViewPagerFixed;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.UserCell;
import org.telegram.ui.SearchTabsAndFiltersLayout;

import java.util.ArrayList;
import java.util.LinkedList;

public class ChatHistorySearchActivity extends BaseFragment {

    // SharedPreferences constants
    private static final String PREF_RECENT_SEARCH = "chat_recent_search";
    private static final String KEY_COUNT = "count";
    private static final String KEY_RECENT_PREFIX = "recent";
    private static final int MAX_RECENT_SEARCHES = 20;
    private static final long SEARCH_DEBOUNCE_MS = 250L;
    private static final int TABS_CONTAINER_HEIGHT_DP = 50;

    private RecyclerListView listView;
    private ListAdapter adapter;
    private final ArrayList<ChatHistoryActivity.HistoryItem> historyItems = new ArrayList<>();
    private ArrayList<ChatHistoryActivity.HistoryItem> results = new ArrayList<>();
    private ArrayList<String> recentSearches = new ArrayList<>();
    private ActionBarMenuItem searchItem;
    private ActionBarMenuItem cancelItem;
    private android.widget.TextView resultCountView;
    private ViewPagerFixed viewPager;
    private ViewPagerFixed.TabsView tabsView;
    private SearchTabsAndFiltersLayout tabsContainer;
    private BlurredBackgroundDrawable tabsContainerBackground;
    private final BlurredBackgroundSourceColor tabsBackgroundSourceColor = new BlurredBackgroundSourceColor();
    private final BlurredBackgroundDrawableViewFactory tabsBackgroundDrawableFactory = new BlurredBackgroundDrawableViewFactory(tabsBackgroundSourceColor);
    private int savedCurrentTab = 0;
    private boolean isOpeningChat = false;
    private String savedSearchQuery = "";
    private String searchQuery = "";
    private Runnable searchRunnable;
    private int searchRequestId;
    private boolean searchInProgress;
    
    // Scroll position - only save for current tab
    private android.os.Parcelable savedScrollState = null;
    private int savedScrollTab = -1;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle("");
        ActionBarMenu menu = actionBar.createMenu();
        searchItem = menu.addItem(1, R.drawable.ic_ab_search).setIsSearchField(true, true);
        cancelItem = menu.addItem(2, LocaleController.getString(R.string.Cancel));
        searchItem.searchRightMargin = 0;
        menu.setOnLayoutListener(() -> {
            int right = 0;
            if (cancelItem != null && cancelItem.getVisibility() == View.VISIBLE) {
                right += cancelItem.getMeasuredWidth();
            }
            searchItem.searchRightMargin = right;
        });
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == 2) {
                    finishFragment();
                }
            }
        });
        searchItem.setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
            @Override
            public void onSearchExpand() {
            }

            @Override
            public void onSearchCollapse() {
                finishFragment();
            }

            @Override
            public void onTextChanged(android.widget.EditText editText) {
                searchQuery = editText.getText().toString();
                performSearch(searchQuery);
            }

            @Override
            public void onSearchPressed(android.widget.EditText editText) {
                String q = editText.getText().toString();
                if (!TextUtils.isEmpty(q)) {
                    addToRecentSearches(q);
                }
                performSearch(q);
            }
        });

        // Container for tabs + pages + recent list + result counter
        android.widget.FrameLayout container = new android.widget.FrameLayout(context);

        // Recent searches list (shown when no query)
        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        adapter = new ListAdapter(context);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((view, position) -> {
            if (TextUtils.isEmpty(searchQuery)) {
                if (position == 0) {
                    return;
                }
                int index = position - 1;
                if (index >= 0 && index < recentSearches.size()) {
                    String text = recentSearches.get(index);
                    if (searchItem != null && searchItem.getSearchField() != null) {
                        searchItem.getSearchField().setText(text);
                        searchItem.getSearchField().setSelection(text.length());
                        addToRecentSearches(text);
                        performSearch(text);
                    }
                    return;
                }
                if (index == recentSearches.size()) {
                    clearRecentSearch();
                    return;
                }
            }
        });
        container.addView(listView, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
                viewPager = new ViewPagerFixed(context) {
                    @Override
                    protected void onTabPageSelected(int position) {
                        super.onTabPageSelected(position);
                        // Clear saved scroll position when switching tabs
                        savedScrollState = null;
                        savedScrollTab = -1;
                    }
                };
        viewPager.setAdapter(new SearchCategoryPagerAdapter());
        tabsContainer = new SearchTabsAndFiltersLayout(context);
        tabsContainer.setPadding(0, AndroidUtilities.dp(7), 0, AndroidUtilities.dp(7));
        tabsView = viewPager.createTabsView(true, ViewPagerFixed.SELECTOR_TYPE_BUBBLE_STYLE);
        tabsView.setIndicatorAnimation(320, org.telegram.ui.Components.CubicBezierInterpolator.EASE_OUT_QUINT);
        tabsView.tabMarginDp = (int) (FilterTabsView.TAB_PADDING_WIDTH / 2f);
        int tabsListPadding = Math.max(0, AndroidUtilities.dp(23.5f - FilterTabsView.TAB_PADDING_WIDTH / 2f));
        tabsView.listView.setPadding(tabsListPadding, 0, tabsListPadding, 0);
        tabsContainer.addView(tabsView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));
        container.addView(tabsContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, TABS_CONTAINER_HEIGHT_DP, Gravity.TOP, 4, 0, 4, 0));
        container.addView(viewPager, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP, 0, TABS_CONTAINER_HEIGHT_DP, 0, 0));

        // Add bottom-right result counter
        resultCountView = new android.widget.TextView(context);
        resultCountView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        resultCountView.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(12), Theme.getColor(Theme.key_actionBarDefaultSubmenuBackground)));
        resultCountView.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(6), AndroidUtilities.dp(10), AndroidUtilities.dp(6));
        resultCountView.setTextSize(14);
        resultCountView.setVisibility(View.GONE);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.RIGHT);
        lp.bottomMargin = AndroidUtilities.dp(12);
        lp.rightMargin = AndroidUtilities.dp(12);
        container.addView(resultCountView, lp);

        fragmentView = container;
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        reloadHistoryItems();
        loadRecentSearch();
        fragmentView.post(() -> actionBar.openSearchField("", true));
        updateTabs();
        updateTabsStyle();
        updateSearchModeUI();
        return fragmentView;
    }

    private void openChat(ChatHistoryActivity.HistoryItem item) {
        if (item == null || (item.user == null && item.chat == null)) {
            return;
        }
        if (!openChatThroughHost(item)) {
            isOpeningChat = true;
            savedCurrentTab = viewPager != null ? viewPager.getCurrentPosition() : 0;
            savedSearchQuery = searchQuery;
            saveScrollPosition();
            Bundle args = new Bundle();
            if (item.dialogId < 0) {
                args.putLong("chat_id", -item.dialogId);
                presentFragment(new org.telegram.ui.ChatActivity(args));
            } else {
                args.putLong("user_id", item.dialogId);
                presentFragment(new org.telegram.ui.ChatActivity(args));
            }
        }
    }

    private boolean openChatThroughHost(ChatHistoryActivity.HistoryItem item) {
        try {
            org.telegram.ui.ActionBar.INavigationLayout layout = getParentLayout();
            if (layout == null) return false;
            BaseFragment host = layout.findFragment(tw.nekomimi.nekogram.ChatHistoryActivity.class);
            if (!(host instanceof tw.nekomimi.nekogram.ChatHistoryActivity)) return false;
            ((tw.nekomimi.nekogram.ChatHistoryActivity) host).openChat(item);
            return true;
        } catch (Exception ignore) {
            return false;
        }
    }

    private void performSearch(String query) {
        searchQuery = query == null ? "" : query;
        cancelPendingSearch();
        searchRequestId++;

        if (adapter != null) {
            adapter.invalidateGroupingCache();
        }

        if (TextUtils.isEmpty(searchQuery)) {
            searchInProgress = false;
            results.clear();
            adapter.notifyDataSetChanged();
            updateTabs();
            refreshAllPages();
            updateResultCounter(0);
            updateSearchModeUI();
            return;
        }

        searchInProgress = true;
        results.clear();
        updateTabs();
        refreshAllPages();
        updateResultCounter(0);
        updateSearchModeUI();

        final int requestId = searchRequestId;
        final String queryText = searchQuery;
        final ArrayList<ChatHistoryActivity.HistoryItem> historyItemsSnapshot = new ArrayList<>(historyItems);
        Runnable runnable = () -> {
            String lower = queryText.toLowerCase();
            ArrayList<ChatHistoryActivity.HistoryItem> filtered = new ArrayList<>();
            for (ChatHistoryActivity.HistoryItem item : historyItemsSnapshot) {
                if (ChatHistoryActivity.matchesSearchQuery(item, lower)) {
                    filtered.add(item);
                }
            }
            AndroidUtilities.runOnUIThread(() -> applySearchResults(queryText, requestId, filtered));
        };
        searchRunnable = runnable;
        Utilities.searchQueue.postRunnable(runnable, SEARCH_DEBOUNCE_MS);
    }

    private void applySearchResults(String query, int requestId, ArrayList<ChatHistoryActivity.HistoryItem> filtered) {
        if (requestId != searchRequestId || !TextUtils.equals(query, searchQuery)) {
            return;
        }
        searchRunnable = null;
        searchInProgress = false;
        results.clear();
        results.addAll(filtered);
        if (adapter != null) {
            adapter.invalidateGroupingCache();
        }
        updateTabs();
        refreshAllPages();
        updateResultCounter(results.size());
        updateSearchModeUI();
    }

    private void cancelPendingSearch() {
        if (searchRunnable != null) {
            Utilities.searchQueue.cancelRunnable(searchRunnable);
            searchRunnable = null;
        }
    }

    private CharSequence getSearchEmptyText() {
        if (TextUtils.isEmpty(searchQuery)) {
            return LocaleController.getString(R.string.ChatHistory_EnterSearchQuery);
        }
        if (searchInProgress) {
            return LocaleController.getString(R.string.Loading);
        }
        return LocaleController.formatString(R.string.ChatHistory_NoResultsFor, searchQuery);
    }

    private void updateResultCounter(int count) {
        if (resultCountView == null) return;
        if (count <= 0 || TextUtils.isEmpty(searchQuery)) {
            resultCountView.setVisibility(View.GONE);
        } else {
            resultCountView.setText(LocaleController.formatString(R.string.ChatHistory_ResultCount, count));
            resultCountView.setVisibility(View.VISIBLE);
        }
    }

    private void clearRecentSearch() {
        recentSearches.clear();
        saveRecentSearch();
        adapter.notifyDataSetChanged();
    }

    private void loadRecentSearch() {
        recentSearches.clear();
        android.content.SharedPreferences preferences = org.telegram.messenger.ApplicationLoader
            .applicationContext.getSharedPreferences(PREF_RECENT_SEARCH, android.app.Activity.MODE_PRIVATE);
        int count = preferences.getInt(KEY_COUNT, 0);
        for (int a = 0; a < count; a++) {
            String str = preferences.getString(KEY_RECENT_PREFIX + a, null);
            if (str == null) break;
            recentSearches.add(str);
        }
    }

    private void saveRecentSearch() {
        android.content.SharedPreferences.Editor editor = org.telegram.messenger.ApplicationLoader
            .applicationContext.getSharedPreferences(PREF_RECENT_SEARCH, android.app.Activity.MODE_PRIVATE).edit();
        editor.clear();
        editor.putInt(KEY_COUNT, recentSearches.size());
        for (int a = 0, N = recentSearches.size(); a < N; a++) {
            editor.putString(KEY_RECENT_PREFIX + a, recentSearches.get(a));
        }
        editor.apply();
    }

    private void addToRecentSearches(String query) {
        for (int a = 0, N = recentSearches.size(); a < N; a++) {
            String str = recentSearches.get(a);
            if (str.equalsIgnoreCase(query)) {
                recentSearches.remove(a);
                break;
            }
        }
        recentSearches.add(0, query);
        while (recentSearches.size() > MAX_RECENT_SEARCHES) {
            recentSearches.remove(recentSearches.size() - 1);
        }
        saveRecentSearch();
        adapter.notifyDataSetChanged();
    }

    @Override
    public void onResume() {
        super.onResume();
        reloadHistoryItems();
        if (isOpeningChat) {
            isOpeningChat = false;
            restoreState();
            restoreScrollPosition();
            return;
        }
        if (TextUtils.isEmpty(searchQuery)) {
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        } else {
            performSearch(searchQuery);
        }
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        cancelPendingSearch();
        searchRequestId++;
        searchInProgress = false;
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private Context mContext;

        public ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public int getItemCount() {
            if (TextUtils.isEmpty(searchQuery)) {
                return recentSearches.isEmpty() ? 0 : recentSearches.size() + 2;
            }
            return buildGroupedOrder().size();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public int getItemViewType(int position) {
            if (TextUtils.isEmpty(searchQuery)) {
                if (position == 0) return 0;
                if (position == recentSearches.size() + 1) return 2;
                return 1;
            }
            GroupedItem gi = buildGroupedOrder().get(position);
            return gi.isHeader ? 4 : 3;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == 0) {
                org.telegram.ui.Cells.HeaderCell cell = new org.telegram.ui.Cells.HeaderCell(mContext);
                cell.setText(LocaleController.getString(R.string.Recent));
                return new RecyclerListView.Holder(cell);
            } else if (viewType == 2) {
                TextCell cell = new TextCell(mContext);
                cell.setTextAndIcon(LocaleController.getString(R.string.ClearRecentHistory), R.drawable.msg_clear_recent, false);
                cell.setBackgroundDrawable(Theme.getSelectorDrawable(false));
                return new RecyclerListView.Holder(cell);
            } else if (viewType == 4) {
                org.telegram.ui.Cells.HeaderCell cell = new org.telegram.ui.Cells.HeaderCell(mContext);
                return new RecyclerListView.Holder(cell);
            } else {
                if (TextUtils.isEmpty(searchQuery)) {
                    TextCell cell = new TextCell(mContext);
                    cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    cell.setBackgroundDrawable(Theme.getSelectorDrawable(false));
                    cell.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(12));
                    return new RecyclerListView.Holder(cell);
                } else {
                    UserCell cell = new UserCell(mContext, 0, 0, false);
                    cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    return new RecyclerListView.Holder(cell);
                }
            }
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (TextUtils.isEmpty(searchQuery)) {
                if (getItemViewType(position) == 0) {
                    return;
                } else if (getItemViewType(position) == 2) {
                    return;
                } else if (holder.itemView instanceof TextCell) {
                    int index = position - 1;
                    TextCell cell = (TextCell) holder.itemView;
                    String text = recentSearches.get(index);
                    cell.setTextAndIcon(text, R.drawable.menu_recent, true);
                    android.widget.ImageView iv = cell.getValueImageView();
                    iv.setImageResource(R.drawable.baseline_close_24);
                    iv.setColorFilter(new PorterDuffColorFilter(actionBar.getItemsColor(), PorterDuff.Mode.SRC_IN));
                    iv.setVisibility(View.VISIBLE);
                    iv.setBackgroundDrawable(Theme.getSelectorDrawable(false));
                    iv.setOnClickListener(v -> {
                        if (index >= 0 && index < recentSearches.size()) {
                            recentSearches.remove(index);
                            saveRecentSearch();
                            notifyDataSetChanged();
                        }
                    });
                }
            } else {
                GroupedItem gi = buildGroupedOrder().get(position);
                if (gi.isHeader && holder.itemView instanceof org.telegram.ui.Cells.HeaderCell) {
                    org.telegram.ui.Cells.HeaderCell cell = (org.telegram.ui.Cells.HeaderCell) holder.itemView;
                    cell.setText(gi.headerTitle);
                } else if (holder.itemView instanceof UserCell && gi.item != null) {
                    UserCell cell = (UserCell) holder.itemView;
                    ChatHistoryUtils.bindUserCell(cell, gi.item);
                }
            }
        }

        private ArrayList<GroupedItem> cachedOrder;

        public void invalidateGroupingCache() { cachedOrder = null; }

        private ArrayList<GroupedItem> buildGroupedOrder() {
            if (cachedOrder != null) return cachedOrder;
            ArrayList<GroupedItem> order = new ArrayList<>();
            if (TextUtils.isEmpty(searchQuery)) {
                cachedOrder = order;
                return order;
            }
            ArrayList<ChatHistoryActivity.HistoryItem> channels = new ArrayList<>();
            ArrayList<ChatHistoryActivity.HistoryItem> groups = new ArrayList<>();
            ArrayList<ChatHistoryActivity.HistoryItem> users = new ArrayList<>();
            ArrayList<ChatHistoryActivity.HistoryItem> bots = new ArrayList<>();
            for (ChatHistoryActivity.HistoryItem item : results) {
                if (ChatHistoryUtils.shouldIncludeInCategory(item, 1)) channels.add(item);
                else if (ChatHistoryUtils.shouldIncludeInCategory(item, 2)) groups.add(item);
                else if (ChatHistoryUtils.shouldIncludeInCategory(item, 3)) users.add(item);
                else if (ChatHistoryUtils.shouldIncludeInCategory(item, 4)) bots.add(item);
            }
            if (!channels.isEmpty()) {
                order.add(GroupedItem.header(LocaleController.getString(R.string.ChatCategoryChannels)));
                for (ChatHistoryActivity.HistoryItem hi : channels) order.add(GroupedItem.item(hi));
            }
            if (!groups.isEmpty()) {
                order.add(GroupedItem.header(LocaleController.getString(R.string.ChatCategoryGroups)));
                for (ChatHistoryActivity.HistoryItem hi : groups) order.add(GroupedItem.item(hi));
            }
            if (!users.isEmpty()) {
                order.add(GroupedItem.header(LocaleController.getString(R.string.ChatCategoryUsers)));
                for (ChatHistoryActivity.HistoryItem hi : users) order.add(GroupedItem.item(hi));
            }
            if (!bots.isEmpty()) {
                order.add(GroupedItem.header(LocaleController.getString(R.string.ChatCategoryBots)));
                for (ChatHistoryActivity.HistoryItem hi : bots) order.add(GroupedItem.item(hi));
            }
            cachedOrder = order;
            return order;
        }
    }

    private static class GroupedItem {
        final boolean isHeader;
        final String headerTitle;
        final ChatHistoryActivity.HistoryItem item;

        private GroupedItem(boolean isHeader, String headerTitle, ChatHistoryActivity.HistoryItem item) {
            this.isHeader = isHeader;
            this.headerTitle = headerTitle;
            this.item = item;
        }

        static GroupedItem header(String title) { return new GroupedItem(true, title, null); }
        static GroupedItem item(ChatHistoryActivity.HistoryItem item) { return new GroupedItem(false, null, item); }
    }

    private RecyclerListView getCurrentRecyclerListView() {
        if (viewPager == null) return null;
        View v = viewPager.getCurrentView();
        if (v instanceof android.widget.FrameLayout) {
            android.widget.FrameLayout container = (android.widget.FrameLayout) v;
            for (int i = 0; i < container.getChildCount(); i++) {
                View child = container.getChildAt(i);
                if (child instanceof RecyclerListView) {
                    return (RecyclerListView) child;
                }
            }
        } else if (v instanceof RecyclerListView) {
            return (RecyclerListView) v;
        }
        return null;
    }

    private void restoreState() {
        if (viewPager != null) {
            viewPager.setPosition(savedCurrentTab);
        }
        if (!TextUtils.isEmpty(savedSearchQuery)) {
            searchQuery = savedSearchQuery;
            if (searchItem != null) {
                searchItem.postDelayed(() -> {
                    searchItem.openSearch(false);
                    if (searchItem.getSearchField() != null) {
                        searchItem.getSearchField().setText(savedSearchQuery);
                    }
                    performSearch(savedSearchQuery);
                    updateSearchModeUI();
                }, 50);
            } else {
                performSearch(savedSearchQuery);
                updateSearchModeUI();
            }
        }
    }

    /**
     * Save current scroll position
     */
    private void saveScrollPosition() {
        RecyclerListView lv = getCurrentRecyclerListView();
        if (lv != null) {
            RecyclerView.LayoutManager lm = lv.getLayoutManager();
            if (lm != null) {
                savedScrollState = lm.onSaveInstanceState();
                savedScrollTab = viewPager != null ? viewPager.getCurrentPosition() : 0;
            }
        }
    }

    /**
     * Restore scroll position if still on the same tab
     */
    private void restoreScrollPosition() {
        if (savedScrollState == null) return;
        if (viewPager != null && savedScrollTab != viewPager.getCurrentPosition()) {
            savedScrollState = null;
            savedScrollTab = -1;
            return;
        }
        
        RecyclerListView lv = getCurrentRecyclerListView();
        if (lv != null) {
            RecyclerView.LayoutManager lm = lv.getLayoutManager();
            if (lm != null) {
                lm.onRestoreInstanceState(savedScrollState);
            }
        }
        
        // Clear after restore
        savedScrollState = null;
        savedScrollTab = -1;
    }

    private void updateSearchModeUI() {
        boolean hasQuery = !TextUtils.isEmpty(searchQuery);
        if (listView != null) {
            listView.setVisibility(hasQuery ? View.GONE : View.VISIBLE);
        }
        if (tabsContainer != null) {
            tabsContainer.setVisibility(hasQuery ? View.VISIBLE : View.GONE);
        }
        if (viewPager != null) {
            viewPager.setVisibility(hasQuery ? View.VISIBLE : View.GONE);
        }
        if (resultCountView != null) {
            resultCountView.setVisibility(View.GONE);
        }
    }

    private void updateTabs() {
        if (tabsView != null) {
            int currentTab = viewPager != null ? viewPager.getCurrentPosition() : 0;
            tabsView.removeTabs();
            tabsView.addTab(0, getTabTitle(0));
            tabsView.addTab(1, getTabTitle(1));
            tabsView.addTab(2, getTabTitle(2));
            tabsView.addTab(3, getTabTitle(3));
            tabsView.addTab(4, getTabTitle(4));
            tabsView.finishAddingTabs();
            tabsView.selectTabWithId(currentTab, 1.0f);
        }
    }

    private String getTabTitle(int position) {
        return ChatHistoryUtils.getCategoryTabTitle(results, position);
    }

    private void updateTabsStyle() {
        if (tabsView == null) {
            return;
        }
        tabsBackgroundSourceColor.setColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourceProvider));
        tabsView.setColors(
                Theme.key_profile_tabSelectedLine,
                Theme.key_profile_tabSelectedText,
                Theme.key_profile_tabText,
                Theme.key_profile_tabSelector,
                Theme.key_actionBarDefault
        );
        tabsView.updateColors();
        tabsView.setBackground(null);
        if (tabsContainer != null) {
            if (tabsContainerBackground == null) {
                tabsContainerBackground = tabsBackgroundDrawableFactory.create(tabsContainer, BlurredBackgroundProviderImpl.topPanel(resourceProvider));
                tabsContainerBackground.setRadius(AndroidUtilities.dp(18));
                tabsContainerBackground.setPadding(AndroidUtilities.dp(6.666f));
                tabsContainer.setBlurredBackground(tabsContainerBackground);
            } else {
                tabsContainer.updateColors();
            }
        }
    }

    private void refreshAllPages() {
        if (viewPager != null) {
            bindSearchPage(viewPager.getCurrentView(), viewPager.getCurrentPosition());
        }
    }

    private void reloadHistoryItems() {
        historyItems.clear();
        historyItems.addAll(ChatHistoryActivity.loadRecentHistoryItems(currentAccount));
    }

    private void bindSearchPage(View view, int position) {
        if (!(view instanceof android.widget.FrameLayout)) {
            return;
        }
        android.widget.FrameLayout container = (android.widget.FrameLayout) view;
        RecyclerListView lv = null;
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child instanceof RecyclerListView) {
                lv = (RecyclerListView) child;
                break;
            }
        }
        if (lv == null) {
            return;
        }
        SearchCategoryListAdapter ad;
        RecyclerView.Adapter existingAdapter = lv.getAdapter();
        if (existingAdapter instanceof SearchCategoryListAdapter &&
                ((SearchCategoryListAdapter) existingAdapter).getCategoryIndex() == position) {
            ad = (SearchCategoryListAdapter) existingAdapter;
            ad.refresh();
            ad.notifyDataSetChanged();
        } else {
            Context context = getContext();
            if (context == null) {
                return;
            }
            ad = new SearchCategoryListAdapter(context, position);
            lv.setAdapter(ad);
        }
        lv.setOnItemClickListener((itemView, itemPosition) -> ad.onItemClick(itemView, itemPosition));
        android.widget.TextView counter = container.getTag() instanceof android.widget.TextView
                ? (android.widget.TextView) container.getTag() : null;
        if (counter != null) {
            if (TextUtils.isEmpty(searchQuery) || searchInProgress) {
                counter.setVisibility(View.GONE);
            } else {
                counter.setText(LocaleController.formatString(R.string.ChatHistory_ResultCount, ad.getRealCount()));
                counter.setVisibility(View.VISIBLE);
            }
        }
    }

    private class SearchCategoryPagerAdapter extends ViewPagerFixed.Adapter {
        @Override
        public int getItemCount() { return 5; }

        @Override
        public String getItemTitle(int position) {
            return getTabTitle(position);
        }

        @Override
        public View createView(int viewType) {
            Context context = getContext();
            if (context == null) return new View(getParentActivity());
            android.widget.FrameLayout container = new android.widget.FrameLayout(context);
            container.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            RecyclerListView lv = new RecyclerListView(context);
            lv.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
            lv.setVerticalScrollBarEnabled(false);
            lv.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            container.addView(lv, new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
            android.widget.TextView counter = new android.widget.TextView(context);
            counter.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            counter.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(12), Theme.getColor(Theme.key_actionBarDefaultSubmenuBackground)));
            counter.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(6), AndroidUtilities.dp(10), AndroidUtilities.dp(6));
            counter.setTextSize(14);
            counter.setVisibility(View.GONE);
            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.RIGHT);
            lp.bottomMargin = AndroidUtilities.dp(12);
            lp.rightMargin = AndroidUtilities.dp(12);
            container.addView(counter, lp);
            container.setTag(counter);
            return container;
        }

        @Override
        public void bindView(View view, int position, int viewType) {
            bindSearchPage(view, position);
        }
    }

    private class SearchCategoryListAdapter extends RecyclerListView.SelectionAdapter {
        private Context mContext;
        private int categoryIndex;
        private ArrayList<ChatHistoryActivity.HistoryItem> categoryItems = new ArrayList<>();

        public SearchCategoryListAdapter(Context context, int categoryIndex) {
            mContext = context;
            this.categoryIndex = categoryIndex;
            updateCategoryData();
        }

        public int getCategoryIndex() {
            return categoryIndex;
        }

        public void refresh() {
            updateCategoryData();
        }

        private void updateCategoryData() {
            categoryItems.clear();
            if (TextUtils.isEmpty(searchQuery)) {
                return;
            }
            for (ChatHistoryActivity.HistoryItem item : results) {
                if (ChatHistoryUtils.shouldIncludeInCategory(item, categoryIndex)) {
                    categoryItems.add(item);
                }
            }
        }

        public void onItemClick(View view, int position) {
            if (position >= 0 && position < categoryItems.size()) {
                ChatHistoryActivity.HistoryItem item = categoryItems.get(position);
                openChat(item);
            }
        }

        @Override
        public int getItemCount() { return categoryItems.isEmpty() ? 1 : categoryItems.size(); }

        public int getRealCount() { return categoryItems.size(); }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) { return !categoryItems.isEmpty(); }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == 1) {
                org.telegram.ui.Cells.HeaderCell empty = new org.telegram.ui.Cells.HeaderCell(mContext);
                empty.setText(getSearchEmptyText());
                view = empty;
            } else {
                UserCell cell = new UserCell(mContext, 0, 0, false);
                cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                view = cell;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public int getItemViewType(int position) { return categoryItems.isEmpty() ? 1 : 0; }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (holder.itemView instanceof org.telegram.ui.Cells.HeaderCell && categoryItems.isEmpty()) {
                ((org.telegram.ui.Cells.HeaderCell) holder.itemView).setText(getSearchEmptyText());
            } else if (holder.itemView instanceof UserCell && position >= 0 && position < categoryItems.size()) {
                UserCell cell = (UserCell) holder.itemView;
                ChatHistoryActivity.HistoryItem item = categoryItems.get(position);
                ChatHistoryUtils.bindUserCell(cell, item);
            }
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ThemeDescription.ThemeDescriptionDelegate cellDelegate = () -> {
            if (fragmentView != null) {
                fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            }
            if (listView != null) {
                listView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            }
            updateTabsStyle();
            if (resultCountView != null) {
                resultCountView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                resultCountView.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(12), Theme.getColor(Theme.key_actionBarDefaultSubmenuBackground)));
            }
            // Refresh ViewPager pages
            refreshAllPages();
            // Refresh adapter
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        };

        ArrayList<ThemeDescription> themeDescriptions = new ArrayList<>();

        themeDescriptions.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, cellDelegate, Theme.key_windowBackgroundWhite));

        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundWhite));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_SELECTOR, null, null, null, null, Theme.key_listSelector));

        if (tabsContainer != null) {
            themeDescriptions.add(new ThemeDescription(tabsContainer, ThemeDescription.FLAG_BACKGROUND, null, null, null, cellDelegate, Theme.key_windowBackgroundWhite));
        }

        // UserCell text colors
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{UserCell.class}, new String[]{"nameTextView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{UserCell.class}, new String[]{"statusTextView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR, new Class[]{UserCell.class}, null, null, null, Theme.key_windowBackgroundWhite));

        // HeaderCell for empty state
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{org.telegram.ui.Cells.HeaderCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlueHeader));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR, new Class[]{org.telegram.ui.Cells.HeaderCell.class}, null, null, null, Theme.key_windowBackgroundWhite));

        // ActionBar
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector));

        return themeDescriptions;
    }
}
