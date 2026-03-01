
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
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ViewPagerFixed;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.UserCell;

import java.util.ArrayList;
import java.util.LinkedList;

public class ChatHistorySearchActivity extends BaseFragment {

    private static final String PREF_RECENT_SEARCH = "chat_recent_search";
    private static final String KEY_COUNT = "count";
    private static final String KEY_RECENT_PREFIX = "recent";
    private static final int MAX_RECENT_SEARCHES = 20;

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
    private int savedCurrentTab = 0;
    private boolean isOpeningChat = false;
    private String savedSearchQuery = "";
    private String searchQuery = "";
    
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

        android.widget.FrameLayout container = new android.widget.FrameLayout(context);

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
                        savedScrollState = null;
                        savedScrollTab = -1;
                    }
                };
        viewPager.setAdapter(new SearchCategoryPagerAdapter());
        tabsView = viewPager.createTabsView(true, ViewPagerFixed.SELECTOR_TYPE_BUBBLE_STYLE);
        tabsView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        container.addView(tabsView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.TOP));
        container.addView(viewPager, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP, 0, 48, 0, 0));

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
        results.clear();
        
        if (adapter != null) {
            adapter.invalidateGroupingCache();
        }

        if (TextUtils.isEmpty(searchQuery)) {
            adapter.notifyDataSetChanged();
            updateResultCounter(0);
            updateSearchModeUI();
            return;
        }
        String lower = searchQuery.toLowerCase();
        for (ChatHistoryActivity.HistoryItem item : historyItems) {
            if (ChatHistoryActivity.matchesSearchQuery(item, lower)) {
                results.add(item);
            }
        }
        refreshAllPages();
        updateResultCounter(results.size());
        updateSearchModeUI();
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
                    cell.avatarImageView.setRoundRadius(AndroidUtilities.dp(24));
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
                if (item.user != null) {
                    if (item.user.bot) bots.add(item); else users.add(item);
                } else if (item.chat != null) {
                    if (item.chat.broadcast) channels.add(item); else groups.add(item);
                }
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
        
        savedScrollState = null;
        savedScrollTab = -1;
    }

    private void updateSearchModeUI() {
        boolean hasQuery = !TextUtils.isEmpty(searchQuery);
        if (listView != null) {
            listView.setVisibility(hasQuery ? View.GONE : View.VISIBLE);
        }
        if (tabsView != null) {
            tabsView.setVisibility(hasQuery ? View.VISIBLE : View.GONE);
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
            tabsView.removeTabs();
            tabsView.addTab(0, LocaleController.getString(R.string.ChatCategoryAll));
            tabsView.addTab(1, LocaleController.getString(R.string.ChatCategoryChannels));
            tabsView.addTab(2, LocaleController.getString(R.string.ChatCategoryGroups));
            tabsView.addTab(3, LocaleController.getString(R.string.ChatCategoryUsers));
            tabsView.addTab(4, LocaleController.getString(R.string.ChatCategoryBots));
            tabsView.finishAddingTabs();
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
            if (TextUtils.isEmpty(searchQuery)) {
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
            switch (position) {
                case 0: return LocaleController.getString(R.string.ChatCategoryAll);
                case 1: return LocaleController.getString(R.string.ChatCategoryChannels);
                case 2: return LocaleController.getString(R.string.ChatCategoryGroups);
                case 3: return LocaleController.getString(R.string.ChatCategoryUsers);
                case 4: return LocaleController.getString(R.string.ChatCategoryBots);
            }
            return LocaleController.getString(R.string.ChatCategoryAll);
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
                if (categoryIndex == 0) {
                    categoryItems.add(item);
                } else if (categoryIndex == 1 && item.chat != null && item.chat.broadcast) {
                    categoryItems.add(item);
                } else if (categoryIndex == 2 && item.chat != null && !item.chat.broadcast) {
                    categoryItems.add(item);
                } else if (categoryIndex == 3 && item.user != null && !item.user.bot) {
                    categoryItems.add(item);
                } else if (categoryIndex == 4 && item.user != null && item.user.bot) {
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
                empty.setText(TextUtils.isEmpty(searchQuery) ? LocaleController.getString(R.string.ChatHistory_EnterSearchQuery) : LocaleController.formatString(R.string.ChatHistory_NoResultsFor, searchQuery));
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
            if (holder.itemView instanceof UserCell && position >= 0 && position < categoryItems.size()) {
                UserCell cell = (UserCell) holder.itemView;
                ChatHistoryActivity.HistoryItem item = categoryItems.get(position);
                ChatHistoryUtils.bindUserCell(cell, item);
                cell.avatarImageView.setRoundRadius(AndroidUtilities.dp(24));
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
            if (tabsView != null) {
                tabsView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            }
            if (resultCountView != null) {
                resultCountView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                resultCountView.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(12), Theme.getColor(Theme.key_actionBarDefaultSubmenuBackground)));
            }
            if (viewPager != null) {
                viewPager.removeAllViews();
                viewPager.setAdapter(new SearchCategoryPagerAdapter());
            }
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        };

        ArrayList<ThemeDescription> themeDescriptions = new ArrayList<>();

        themeDescriptions.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, cellDelegate, Theme.key_windowBackgroundWhite));

        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundWhite));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_SELECTOR, null, null, null, null, Theme.key_listSelector));

        if (tabsView != null) {
            themeDescriptions.add(new ThemeDescription(tabsView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundWhite));
        }

        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{UserCell.class}, new String[]{"nameTextView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{UserCell.class}, new String[]{"statusTextView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR, new Class[]{UserCell.class}, null, null, null, Theme.key_windowBackgroundWhite));

        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{org.telegram.ui.Cells.HeaderCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlueHeader));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR, new Class[]{org.telegram.ui.Cells.HeaderCell.class}, null, null, null, Theme.key_windowBackgroundWhite));

        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector));

        return themeDescriptions;
    }
}
