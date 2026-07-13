
package tw.nekomimi.nekogram;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.google.android.exoplayer2.util.Log;
import org.telegram.messenger.BuildVars;

import static android.view.View.MeasureSpec;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BlurredRecyclerView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CheckBox2;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.FilterTabsView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.ShareAlert;
import org.telegram.ui.Components.SizeNotifierFrameLayout;
import org.telegram.ui.Components.ViewPagerFixed;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor;
import org.telegram.ui.SearchTabsAndFiltersLayout;
import org.telegram.ui.ChatActivity;

import tw.nekomimi.nekogram.helpers.PasscodeHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

import tw.nekomimi.nekogram.RecentDialogsStore;

public class ChatHistoryActivity extends BaseFragment {

    private static final String TAG = "ChatHistoryActivity";
    private static final int TABS_CONTAINER_HEIGHT_DP = 50;

    public enum ChatCategory {
        ALL(0),
        CHANNELS(1),
        GROUPS(2),
        USERS(3),
        BOTS(4);

        public final int id;

        ChatCategory(int id) {
            this.id = id;
        }
    }

    private ViewPagerFixed viewPager;
    private ViewPagerFixed.TabsView tabsView;
    private SearchTabsAndFiltersLayout tabsContainer;
    private BlurredBackgroundDrawable tabsContainerBackground;
    private final BlurredBackgroundSourceColor tabsBackgroundSourceColor = new BlurredBackgroundSourceColor();
    private final BlurredBackgroundDrawableViewFactory tabsBackgroundDrawableFactory = new BlurredBackgroundDrawableViewFactory(tabsBackgroundSourceColor);

    private ArrayList<HistoryItem> allHistoryItems = new ArrayList<>();

    private android.os.Parcelable savedScrollState = null;
    private int savedScrollTab = -1;


    private ActionBarMenuItem searchItem;

    // State preservation
    private int savedCurrentTab = 0;
    private boolean isOpeningChat = false;

    private boolean isMultiSelectMode = false;
    private ArrayList<HistoryItem> selectedItems = new ArrayList<>();
    private ActionBarMenuItem deleteItem;

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        loadHistoryItems();
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        saveState();
    }

    private void saveState() {
        if (viewPager != null) {
            savedCurrentTab = viewPager.getCurrentPosition();
        }
        if (BuildVars.LOGS_ENABLED) Log.d(TAG, "Save state: currentTab=" + savedCurrentTab);
    }

    private void restoreState() {
        if (BuildVars.LOGS_ENABLED) Log.d(TAG, "Start restoring state: currentTab=" + savedCurrentTab);

        if (viewPager != null && savedCurrentTab >= 0 && savedCurrentTab < ChatCategory.values().length) {
            viewPager.setPosition(savedCurrentTab);
            if (tabsView != null) {
                tabsView.selectTabWithId(savedCurrentTab, 1.0f);
            }
            if (BuildVars.LOGS_ENABLED) Log.d(TAG, "Tab restored to position: " + savedCurrentTab);
        }
    }



    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        updateTitle();

        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == 2) {
                    showOptionsMenu();
                } else if (id == 4) {
                    showDeleteSelectedDialog();
                }
            }
        });

        updateTitle();
        updateActionBarForNormalMode();

        fragmentView = new SizeNotifierFrameLayout(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        createViewPager(context, (SizeNotifierFrameLayout) fragmentView);

        if (isOpeningChat) {
            fragmentView.post(() -> restoreState());
        }

        return fragmentView;
    }

    private void createViewPager(Context context, SizeNotifierFrameLayout fragmentView) {
        viewPager = new ViewPagerFixed(context) {
            @Override
            protected void onTabPageSelected(int position) {
                super.onTabPageSelected(position);
                
                savedScrollState = null;
                savedScrollTab = -1;
                
                if (isMultiSelectMode) {
                    exitMultiSelectMode();
                }
            }
        };
        viewPager.setAdapter(new CategoryPagerAdapter());

        tabsContainer = new SearchTabsAndFiltersLayout(context);
        tabsContainer.setPadding(0, AndroidUtilities.dp(7), 0, AndroidUtilities.dp(7));

        tabsView = viewPager.createTabsView(true, ViewPagerFixed.SELECTOR_TYPE_BUBBLE_STYLE);
        tabsView.setIndicatorAnimation(320, CubicBezierInterpolator.EASE_OUT_QUINT);
        tabsView.tabMarginDp = (int) (FilterTabsView.TAB_PADDING_WIDTH / 2f);
        int tabsListPadding = Math.max(0, AndroidUtilities.dp(23.5f - FilterTabsView.TAB_PADDING_WIDTH / 2f));
        tabsView.listView.setPadding(tabsListPadding, 0, tabsListPadding, 0);
        tabsContainer.addView(tabsView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));

        fragmentView.addView(tabsContainer,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, TABS_CONTAINER_HEIGHT_DP, Gravity.TOP, 4, 0, 4, 0));
        fragmentView.addView(viewPager,
            LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP, 0, TABS_CONTAINER_HEIGHT_DP, 0, 0));

        updateTabs();
        updateTabsStyle();
    }

    private void updateTabs() {
        if (tabsView != null) {
            int currentTab = viewPager != null ? viewPager.getCurrentPosition() : 0;
            tabsView.removeTabs();
            for (int i = 0; i < ChatCategory.values().length; i++) {
                ChatCategory category = ChatCategory.values()[i];
                tabsView.addTab(i, getTabTitle(category));
            }
            tabsView.finishAddingTabs();
            tabsView.selectTabWithId(currentTab, 1.0f);
        }
    }

    private String getTabTitle(ChatCategory category) {
        return ChatHistoryUtils.getCategoryTabTitle(allHistoryItems, category.id);
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


    private void loadHistoryItems() {
        LinkedList<Long> recentDialogIds = RecentDialogsStore.getRecentDialogs(currentAccount);
        allHistoryItems = buildHistoryItems(recentDialogIds, currentAccount);

        updateTabs();
    }

    public static ArrayList<HistoryItem> buildHistoryItems(List<Long> dialogIds, int account) {
        ArrayList<HistoryItem> items = new ArrayList<>(dialogIds.size());
        ArrayList<Long> missingUserIds = new ArrayList<>();
        ArrayList<Long> missingChatIds = new ArrayList<>();
        MessagesController controller = MessagesController.getInstance(account);

        for (Long dialogId : dialogIds) {
            if (dialogId == null || dialogId == 0) {
                continue;
            }
            if (ChatHistoryUtils.isOfficialDialog(dialogId, account)) {
                continue;
            }
            HistoryItem item = new HistoryItem();
            item.dialogId = dialogId;
            if (dialogId > 0) {
                item.user = controller.getUser(dialogId);
                if (item.user == null) {
                    missingUserIds.add(dialogId);
                }
            } else {
                long chatId = -dialogId;
                item.chat = controller.getChat(chatId);
                if (item.chat == null) {
                    missingChatIds.add(chatId);
                }
            }
            items.add(item);
        }

        if (!missingUserIds.isEmpty()) {
            ArrayList<TLRPC.User> users = MessagesStorage.getInstance(account).getUsers(missingUserIds);
            controller.putUsers(users, true);
            HashMap<Long, TLRPC.User> userMap = new HashMap<>(users.size());
            for (TLRPC.User u : users) {
                userMap.put(u.id, u);
            }
            for (HistoryItem it : items) {
                if (it.user == null && it.dialogId > 0) {
                    it.user = userMap.get(it.dialogId);
                }
            }
        }

        if (!missingChatIds.isEmpty()) {
            ArrayList<TLRPC.Chat> chats = MessagesStorage.getInstance(account).getChats(missingChatIds);
            controller.putChats(chats, true);
            HashMap<Long, TLRPC.Chat> chatMap = new HashMap<>(chats.size());
            for (TLRPC.Chat c : chats) {
                chatMap.put(c.id, c);
            }
            for (HistoryItem it : items) {
                if (it.chat == null && it.dialogId < 0) {
                    it.chat = chatMap.get(-it.dialogId);
                }
            }
        }

        ArrayList<HistoryItem> result = new ArrayList<>(items.size());
        for (HistoryItem it : items) {
            if (it.user != null || it.chat != null) {
                result.add(it);
            }
        }
        return result;
    }

    private static TLRPC.User loadUserFromDatabase(long userId, int account) {
        try {
            ArrayList<Long> userIds = new ArrayList<>();
            userIds.add(userId);
            ArrayList<TLRPC.User> users = MessagesStorage.getInstance(account).getUsers(userIds);
            if (!users.isEmpty()) {
                TLRPC.User user = users.get(0);
                MessagesController.getInstance(account).putUser(user, true);
                return user;
            }
        } catch (Exception e) {
            if (BuildVars.LOGS_ENABLED) {
                Log.e(TAG, "Failed to load user from database: " + userId, e);
            }
        }
        return null;
    }

    private static TLRPC.Chat loadChatFromDatabase(long chatId, int account) {
        try {
            ArrayList<Long> chatIds = new ArrayList<>();
            chatIds.add(chatId);
            ArrayList<TLRPC.Chat> chats = MessagesStorage.getInstance(account).getChats(chatIds);
            if (!chats.isEmpty()) {
                TLRPC.Chat chat = chats.get(0);
                MessagesController.getInstance(account).putChat(chat, true);
                return chat;
            }
        } catch (Exception e) {
            if (BuildVars.LOGS_ENABLED) {
                Log.e(TAG, "Failed to load chat from database: " + chatId, e);
            }
        }
        return null;
    }




    private void updateTitle() {
        actionBar.setTitle(getString(R.string.RecentChats));
    }

    public static boolean matchesSearchQuery(HistoryItem item, String query) {
        String name = "";
        if (item.user != null) {
            name = ContactsController.formatName(item.user.first_name, item.user.last_name);
        } else if (item.chat != null) {
            name = item.chat.title;
        }

        if (name.toLowerCase().contains(query)) {
            return true;
        }

        String username = "";
        if (item.user != null) {
            username = UserObject.getPublicUsername(item.user);
            if (TextUtils.isEmpty(username)) {
                username = UserObject.getUserName(item.user);
            }
        } else if (item.chat != null) {
            username = ChatObject.getPublicUsername(item.chat);
            if (TextUtils.isEmpty(username)) {
                username = item.chat.title;
            }
        }

        if (!TextUtils.isEmpty(username) && username.toLowerCase().contains(query)) {
            return true;
        }

        return false;
    }

    private boolean shouldShowAccountSwitch() {
        if (UserConfig.getActivatedAccountsCount() <= 1) {
            return false;
        }

        int visibleAccounts = 0;
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            if (UserConfig.getInstance(i).isClientActivated() && !PasscodeHelper.isAccountHidden(i)) {
                visibleAccounts++;
            }
        }

        return visibleAccounts > 1;
    }

    private void showOptionsMenu() {
        ArrayList<String> items = new ArrayList<>();
        ArrayList<Integer> icons = new ArrayList<>();
        ArrayList<Runnable> actions = new ArrayList<>();

        if (shouldShowAccountSwitch()) {
            items.add(getString(R.string.SwitchAccountNax));
            icons.add(R.drawable.left_status_profile);
            actions.add(() -> showAccountSwitchDialog());
        }

        items.add(getString(R.string.ClearRecentChats));
        icons.add(R.drawable.msg_delete);
        actions.add(() -> showClearHistoryDialog());

        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.Settings));
        
        String[] itemsArray = items.toArray(new String[0]);
        int[] iconsArray = new int[icons.size()];
        for (int i = 0; i < icons.size(); i++) {
            iconsArray[i] = icons.get(i);
        }
        
        builder.setItems(itemsArray, iconsArray, (dialog, which) -> {
            if (which >= 0 && which < actions.size()) {
                actions.get(which).run();
            }
        });
        
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void showAccountSwitchDialog() {
        if (!shouldShowAccountSwitch()) {
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.SwitchAccountNax));

        ArrayList<String> accounts = new ArrayList<>();
        ArrayList<Integer> accountIds = new ArrayList<>();

        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            if (UserConfig.getInstance(i).isClientActivated() && !PasscodeHelper.isAccountHidden(i)) {
                TLRPC.User user = UserConfig.getInstance(i).getCurrentUser();
                if (user != null) {
                    String name = ContactsController.formatName(user.first_name, user.last_name);
                    if (i == currentAccount) {
                        name += " (" + getString(R.string.CurrentNax) + ")";
                    }
                    accounts.add(name);
                    accountIds.add(i);
                }
            }
        }

        builder.setItems(accounts.toArray(new String[0]), (dialog, which) -> {
            int selectedAccount = accountIds.get(which);
            if (selectedAccount != currentAccount) {
                switchToAccount(selectedAccount);
            }
        });

        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void switchToAccount(int accountId) {
        currentAccount = accountId;
        
        clearSavedState();
        
        updateTitle();
        loadHistoryItems();
        refreshAllPages();
    }

    private void showClearHistoryDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.ClearRecentChats));
        builder.setMessage(getString(R.string.ClearRecentChatAlert));

        builder.setPositiveButton(getString(R.string.Clear), (dialog, which) -> {
            clearHistory();
        });

        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void clearHistory() {
        RecentDialogsStore.clearRecentDialogs(currentAccount);

        clearSavedState();

        loadHistoryItems();
        refreshAllPages();
        BulletinFactory.of(this).createSimpleBulletin(R.raw.ic_delete, getString(R.string.ClearRecentChats)).show();
    }

    private void clearSavedState() {
        isOpeningChat = false;
        savedScrollState = null;
        savedScrollTab = -1;
    }

    private void saveScrollPosition() {
        if (viewPager == null) return;
        View v = viewPager.getCurrentView();
        if (v == null) return;

        Object tag = v.getTag();
        if (tag instanceof RecyclerView) {
            RecyclerView.LayoutManager lm = ((RecyclerView) tag).getLayoutManager();
            if (lm != null) {
                savedScrollState = lm.onSaveInstanceState();
                savedScrollTab = viewPager.getCurrentPosition();
            }
        }
    }

    private void restoreScrollPosition() {
        if (viewPager == null || savedScrollState == null) return;
        if (savedScrollTab != viewPager.getCurrentPosition()) {
            savedScrollState = null;
            savedScrollTab = -1;
            return;
        }

        View v = viewPager.getCurrentView();
        if (v == null) return;

        Object tag = v.getTag();
        if (tag instanceof RecyclerView) {
            RecyclerView.LayoutManager lm = ((RecyclerView) tag).getLayoutManager();
            if (lm != null) {
                lm.onRestoreInstanceState(savedScrollState);
            }
        }

        savedScrollState = null;
        savedScrollTab = -1;
    }

    @Override
    public void onResume() {
        super.onResume();

        if (BuildVars.LOGS_ENABLED) Log.d(TAG, "onResume: isOpeningChat=" + isOpeningChat);

        if (isOpeningChat && viewPager != null) {
            if (BuildVars.LOGS_ENABLED) Log.d(TAG, "Returning from chat");
            isOpeningChat = false;

            restoreState();
            restoreScrollPosition();
            return;
        }

        isOpeningChat = false;
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (isMultiSelectMode) {
            if (invoked) {
                exitMultiSelectMode();
            }
            return false;
        }
        return true;
    }

    @Override
    public boolean isSwipeBackEnabled(MotionEvent event) {
        if (isMultiSelectMode) {
            return false;
        }
        return super.isSwipeBackEnabled(event);
    }

    @Override
    public boolean canBeginSlide() {
        if (isMultiSelectMode) {
            return false;
        }
        return super.canBeginSlide();
    }

    private void refreshAllPages() {
        if (viewPager != null) {
            updateTabs();
            rebindCurrentPage();
        }
    }

    private void rebindCurrentPage() {
        if (viewPager == null) return;
        View currentView = viewPager.getCurrentView();
        if (currentView == null) return;

        int backgroundColor = Theme.getColor(Theme.key_windowBackgroundWhite);
        currentView.setBackgroundColor(backgroundColor);
        Object tag = currentView.getTag();
        if (tag instanceof BlurredRecyclerView) {
            BlurredRecyclerView listView = (BlurredRecyclerView) tag;
            listView.setBackgroundColor(backgroundColor);
            RecyclerView.Adapter existing = listView.getAdapter();
            if (existing instanceof CategoryListAdapter) {
                CategoryListAdapter adapter = (CategoryListAdapter) existing;
                adapter.updateCategoryData();
                adapter.notifyDataSetChanged();
                return;
            }
        }
        // Fallback: rebind via adapter
        CategoryPagerAdapter adapter = (CategoryPagerAdapter) viewPager.adapter;
        if (adapter != null) {
            adapter.bindView(currentView, viewPager.getCurrentPosition(), 0);
        }
    }

    private class CategoryPagerAdapter extends ViewPagerFixed.Adapter {
        @Override
        public int getItemCount() {
            return ChatCategory.values().length;
        }

        @Override
        public String getItemTitle(int position) {
            return getTabTitle(ChatCategory.values()[position]);
        }

        @Override
        public View createView(int viewType) {
            Context context = getContext();
            if (context == null) return new View(getParentActivity());

            FrameLayout container = new FrameLayout(context) {
                @Override
                protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                    setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec));
                }
            };
            container.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

            BlurredRecyclerView listView = new BlurredRecyclerView(context);
            listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
            listView.setVerticalScrollBarEnabled(false);

            DefaultItemAnimator itemAnimator = new DefaultItemAnimator();
            itemAnimator.setChangeDuration(350);
            itemAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            itemAnimator.setDelayAnimations(false);
            itemAnimator.setSupportsChangeAnimations(false);
            listView.setItemAnimator(itemAnimator);

            container.addView(listView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ));
            container.setTag(listView);

            return container;
        }

        @Override
        public void bindView(View view, int position, int viewType) {
            Object tag = view.getTag();
            if (!(tag instanceof BlurredRecyclerView)) return;

            int backgroundColor = Theme.getColor(Theme.key_windowBackgroundWhite);
            view.setBackgroundColor(backgroundColor);
            BlurredRecyclerView listView = (BlurredRecyclerView) tag;
            listView.setBackgroundColor(backgroundColor);
            CategoryListAdapter adapter = new CategoryListAdapter(getContext(), position);
            listView.setAdapter(adapter);

            listView.setOnItemClickListener((itemView, itemPosition) -> {
                adapter.onItemClick(itemView, itemPosition);
            });

            listView.setOnItemLongClickListener((itemView, itemPosition) -> {
                if (itemPosition >= 0 && itemPosition < adapter.categoryItems.size()) {
                    if (!isMultiSelectMode) {
                        enterMultiSelectMode();
                    }
                    HistoryItem item = adapter.categoryItems.get(itemPosition);
                    HistoryCell cell = (HistoryCell) itemView;
                    toggleItemSelection(item, cell);
                    return true;
                }
                return false;
            });
        }
    }

    private class CategoryListAdapter extends RecyclerListView.SelectionAdapter {
        private Context mContext;
        private ChatCategory category;
        private ArrayList<HistoryItem> categoryItems = new ArrayList<>();

        public CategoryListAdapter(Context context, int categoryIndex) {
            mContext = context;
            category = ChatCategory.values()[categoryIndex];
            updateCategoryData();
        }

        private void updateCategoryData() {
            categoryItems.clear();

            ArrayList<HistoryItem> sourceItems = allHistoryItems;

            if (sourceItems == null || sourceItems.isEmpty()) {
                if (BuildVars.LOGS_ENABLED) Log.d(TAG, "No data available for " + category.name() + " category");
                return;
            }

            for (HistoryItem item : sourceItems) {
                if (ChatHistoryUtils.shouldIncludeInCategory(item, category.id)) {
                    categoryItems.add(item);
                }
            }

            if (BuildVars.LOGS_ENABLED) Log.d(TAG, "Updated " + category.name() + " category: " + categoryItems.size() + " items from " + sourceItems.size() + " total");
        }

        public void onItemClick(View view, int position) {
            if (position >= 0 && position < categoryItems.size()) {
                HistoryItem item = categoryItems.get(position);
                if (isMultiSelectMode) {
                    HistoryCell cell = (HistoryCell) view;
                    toggleItemSelection(item, cell);
                } else {
                    openChat(item);
                }
            }
        }

        @Override
        public int getItemCount() {
            return categoryItems.isEmpty() ? 1 : categoryItems.size();
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            int viewType = getItemViewType(position);

            if (viewType == 1) {
                if (holder.itemView instanceof EmptyStateCell) {
                    EmptyStateCell emptyStateCell = (EmptyStateCell) holder.itemView;
                    emptyStateCell.applyThemeColors();

                    if (category == ChatCategory.ALL) {
                        emptyStateCell.setText("", getString(R.string.ChatHistory_NoRecentChats));
                    } else {
                        String categoryDisplayName = getCategoryDisplayName(category);
                        emptyStateCell.setText("", LocaleController.formatString(R.string.ChatHistory_NoCategoryFound, categoryDisplayName));
                    }
                }
            } else {
                if (holder.itemView instanceof HistoryCell && position >= 0 && position < categoryItems.size()) {
                    HistoryCell historyCell = (HistoryCell) holder.itemView;
                    HistoryItem item = categoryItems.get(position);
                    historyCell.setDialog(item);
                    
                    historyCell.setMultiSelectMode(isMultiSelectMode);
                    historyCell.setSelected(selectedItems.contains(item));
                }
            }
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return !categoryItems.isEmpty();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == 1) {
                view = new EmptyStateCell(mContext);
            } else {
                view = new HistoryCell(mContext);
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public int getItemViewType(int position) {
            return categoryItems.isEmpty() ? 1 : 0;
        }
    }

    private String getCategoryDisplayName(ChatCategory category) {
        return ChatHistoryUtils.getCategoryDisplayName(category.id);
    }
    
    public void openChat(HistoryItem item) {
        if (item == null || (item.user == null && item.chat == null)) {
            return;
        }

        isOpeningChat = true;
        saveScrollPosition();
        saveState();

        boolean isViewingOwnAccount = (currentAccount == UserConfig.selectedAccount);

        if (isViewingOwnAccount) {
            Bundle args = new Bundle();
            if (item.dialogId < 0) {
                args.putLong("chat_id", -item.dialogId);
                presentFragment(new ChatActivity(args), false, false);
            } else {
                args.putLong("user_id", item.dialogId);
                presentFragment(new ChatActivity(args), false, false);
            }
            return;
        }

        String publicUsername = null;
        if (item.user != null) {
            publicUsername = UserObject.getPublicUsername(item.user);
        } else if (item.chat != null) {
            publicUsername = ChatObject.getPublicUsername(item.chat);
        }

        if (!TextUtils.isEmpty(publicUsername)) {
            MessagesController.getInstance(UserConfig.selectedAccount).openByUserName(publicUsername, this, 1);
        } else {
            if (chatExistsInCurrentAccount(item)) {
                Bundle args = new Bundle();
                if (item.dialogId < 0) {
                    args.putLong("chat_id", -item.dialogId);
                    presentFragment(new ChatActivity(args), false, false);
                } else {
                    args.putLong("user_id", item.dialogId);
                    presentFragment(new ChatActivity(args), false, false);
                }
            } else {
                showPrivateChatDialog(item);
            }
        }
    }

    public static ArrayList<HistoryItem> loadRecentHistoryItems(int account) {
        LinkedList<Long> recentDialogIds = RecentDialogsStore.getRecentDialogs(account);
        return buildHistoryItems(recentDialogIds, account);
    }

    private boolean chatExistsInCurrentAccount(HistoryItem item) {
        int selectedAccount = UserConfig.selectedAccount;

        if (item.dialogId > 0) {
            TLRPC.User user = MessagesController.getInstance(selectedAccount).getUser(item.dialogId);
            if (user == null) {
                user = loadUserFromDatabase(item.dialogId, selectedAccount);
            }
            return user != null;
        } else {
            long chatId = -item.dialogId;
            TLRPC.Chat chat = MessagesController.getInstance(selectedAccount).getChat(chatId);
            if (chat == null) {
                chat = loadChatFromDatabase(chatId, selectedAccount);
            }
            return chat != null;
        }
    }

    private void showPrivateChatDialog(HistoryItem item) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.AppName));

        String chatName = "";
        if (item.user != null) {
            chatName = ContactsController.formatName(item.user.first_name, item.user.last_name);
        } else if (item.chat != null) {
            chatName = item.chat.title;
        }

        builder.setMessage(LocaleController.formatString("PrivateChatMessage", R.string.PrivateChatMessage, chatName));

        builder.setPositiveButton(getString(R.string.OK), null);
        showDialog(builder.create());
    }

    private void showChatOptionsMenu(HistoryItem item, View anchorView) {
        boolean hasPublicUsername = false;
        String username = null;
        String displayName = null;
        
        if (item.user != null) {
            username = UserObject.getPublicUsername(item.user);
            displayName = UserObject.getUserName(item.user);
            hasPublicUsername = !TextUtils.isEmpty(username);
        } else if (item.chat != null) {
            username = ChatObject.getPublicUsername(item.chat);
            displayName = item.chat.title;
            hasPublicUsername = !TextUtils.isEmpty(username);
        }

        boolean isViewingOwnAccount = (currentAccount == UserConfig.selectedAccount);
        boolean canOpen = isViewingOwnAccount || hasPublicUsername || (!isViewingOwnAccount && chatExistsInCurrentAccount(item));

        final String finalUsername = username;
        final String finalDisplayName = displayName;
        final boolean finalHasPublicUsername = hasPublicUsername;
        final boolean finalCanOpen = canOpen;

        ActionBarPopupWindow.ActionBarPopupWindowLayout popupLayout = new ActionBarPopupWindow.ActionBarPopupWindowLayout(getContext(), R.drawable.popup_fixed_alert4, getResourceProvider(), 0);
        popupLayout.setFitItems(true);
        popupLayout.setBackgroundColor(Theme.getColor(Theme.key_actionBarDefaultSubmenuBackground, getResourceProvider()));

        ActionBarPopupWindow popupWindow = new ActionBarPopupWindow(popupLayout, LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT);

        ActionBarMenuSubItem openItem = ActionBarMenuItem.addItem(popupLayout, R.drawable.msg_openin, getString(R.string.Open), false, getResourceProvider());
        openItem.setVisibility(finalCanOpen ? View.VISIBLE : View.GONE);
        if (!finalCanOpen) {
            openItem.setColors(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3), Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3));
        }
        openItem.setOnClickListener(v -> {
            popupWindow.dismiss();
            if (finalCanOpen) {
                openChat(item);
            }
        });

        ActionBarMenuSubItem shareItem = ActionBarMenuItem.addItem(popupLayout, R.drawable.msg_share, getString(R.string.ShareFile), false, getResourceProvider());
        shareItem.setVisibility(finalHasPublicUsername ? View.VISIBLE : View.GONE);
        if (!finalHasPublicUsername) {
            shareItem.setColors(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3), Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3));
        }
        shareItem.setOnClickListener(v -> {
            popupWindow.dismiss();
            if (finalHasPublicUsername) {
                shareChat(finalUsername, item);
            }
        });

        ActionBarMenuSubItem copyItem = ActionBarMenuItem.addItem(popupLayout, R.drawable.msg_copy, getString(R.string.Copy), false, getResourceProvider());
        copyItem.setVisibility(finalHasPublicUsername ? View.VISIBLE : View.GONE);
        if (!finalHasPublicUsername) {
            copyItem.setColors(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3), Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3));
        }
        copyItem.setOnClickListener(v -> {
            popupWindow.dismiss();
            if (finalHasPublicUsername) {
                copyUsername(finalUsername);
            }
        });

        ActionBarMenuSubItem deleteItem = ActionBarMenuItem.addItem(popupLayout, R.drawable.msg_delete, getString(R.string.Delete), false, getResourceProvider());
        deleteItem.setOnClickListener(v -> {
            popupWindow.dismiss();
            showDeleteChatDialog(item);
        });
        popupWindow.setPauseNotifications(true);
        popupWindow.setDismissAnimationDuration(220);
        popupWindow.setOutsideTouchable(true);
        popupWindow.setClippingEnabled(true);
        popupWindow.setAnimationStyle(R.style.PopupContextAnimation);
        popupWindow.setFocusable(true);
        popupLayout.measure(View.MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(1000), View.MeasureSpec.AT_MOST),
                           View.MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(1000), View.MeasureSpec.AT_MOST));
        popupWindow.setInputMethodMode(ActionBarPopupWindow.INPUT_METHOD_NOT_NEEDED);
        popupWindow.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED);
        popupWindow.getContentView().setFocusableInTouchMode(true);

        int[] location = new int[2];
        anchorView.getLocationInWindow(location);
        int popupX = location[0] + anchorView.getWidth() - popupLayout.getMeasuredWidth();
        int popupY = location[1];

        popupWindow.showAtLocation(anchorView, android.view.Gravity.LEFT | android.view.Gravity.TOP, popupX, popupY);
        popupWindow.dimBehind();
    }

    private void shareChat(String username, HistoryItem item) {
        try {
            String shareText = "@" + username;
            ShareAlert shareAlert = ShareAlert.createShareAlert(getContext(), null, shareText, false, shareText, false);
            shareAlert.setDelegate(new ShareAlert.ShareAlertDelegate() {
                @Override
                public void didShare() {
                    int shareCount = shareAlert.getSelectedDialogsCount();
                    
                    if (shareCount > 0) {
                        CharSequence bulletinText = AndroidUtilities.replaceTags(LocaleController.formatPluralString("ChatHistory_LinkSharedToChat", shareCount, shareCount));
                        int duration = shareCount > 1 ? org.telegram.ui.Components.Bulletin.DURATION_PROLONG : org.telegram.ui.Components.Bulletin.DURATION_SHORT;
                        shareAlert.setOnDismissListener(() -> AndroidUtilities.runOnUIThread(() ->
                                BulletinFactory.of(ChatHistoryActivity.this).createSimpleBulletin(
                                        R.raw.forward,
                                        bulletinText
                                ).hideAfterBottomSheet(false).ignoreDetach().setDuration(duration).show()
                        ));
                    }
                }
            });
            showDialog(shareAlert);
        } catch (Exception e) {
            if (BuildVars.LOGS_ENABLED) Log.e(TAG, "Failed to share chat", e);
        }
    }

    private void copyUsername(String username) {
        try {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                getParentActivity().getSystemService(Context.CLIPBOARD_SERVICE);
            android.content.ClipData clip = android.content.ClipData.newPlainText("username", "@" + username);
            clipboard.setPrimaryClip(clip);
            BulletinFactory.of(this).createSimpleBulletin(R.raw.copy,
                getString(R.string.TextCopied)).show();
        } catch (Exception e) {
            if (BuildVars.LOGS_ENABLED) Log.e(TAG, "Failed to copy username", e);
        }
    }

    private void showDeleteChatDialog(HistoryItem item) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.DeleteChatTitle));

        String chatName = "";
        if (item.user != null) {
            chatName = ContactsController.formatName(item.user.first_name, item.user.last_name);
        } else if (item.chat != null) {
            chatName = item.chat.title;
        }

        builder.setMessage(LocaleController.formatString("DeleteChatMessage", R.string.DeleteChatMessage, chatName));

        builder.setPositiveButton(getString(R.string.Delete), (dialog, which) -> {
            deleteChatFromHistory(item);
        });

        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void deleteChatFromHistory(HistoryItem item) {
        deleteChatFromHistory(item, true);
    }

    private void deleteChatFromHistory(HistoryItem item, boolean refreshUI) {
        LinkedList<Long> recentDialogIds = RecentDialogsStore.getRecentDialogs(currentAccount);

        recentDialogIds.remove(item.dialogId);

        RecentDialogsStore.saveRecentDialogs(currentAccount, recentDialogIds);

        if (refreshUI) {
            loadHistoryItems();
            refreshAllPages();

            BulletinFactory.of(this).createSimpleBulletin(R.raw.ic_delete,
                getString(R.string.ChatRemovedFromRecent)).show();
        }
    }


    public static class HistoryItem {
        long dialogId;
        TLRPC.Chat chat;
        TLRPC.User user;
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            HistoryItem that = (HistoryItem) obj;
            return dialogId == that.dialogId;
        }
        
        @Override
        public int hashCode() {
            return Long.hashCode(dialogId);
        }
    }

    private class HistoryCell extends FrameLayout {
        private BackupImageView avatarImageView;
        private TextView nameTextView;
        private TextView usernameTextView;
        private AvatarDrawable avatarDrawable;
        private ActionBarMenuItem optionsButton;
        private CheckBox2 checkBox2;
        private HistoryItem currentItem;
        private boolean isSelected = false;

        public HistoryCell(Context context) {
            super(context);

            avatarDrawable = new AvatarDrawable();
            avatarImageView = new BackupImageView(context);
            avatarImageView.setRoundRadius(org.telegram.messenger.AvatarCornerHelper.getAvatarRoundRadius(50.0f));
            addView(avatarImageView, LayoutHelper.createFrame(50, 50, Gravity.LEFT | Gravity.CENTER_VERTICAL, 16, 0, 0, 0));

            // CheckBox2 for multi-select (shown on avatar corner)
            checkBox2 = new CheckBox2(context, 21, null) {
                @Override
                public void invalidate() {
                    super.invalidate();
                    HistoryCell.this.invalidate();
                }
                @Override
                protected void onDraw(Canvas canvas) {
                    super.onDraw(canvas);
                }
            };
            checkBox2.setVisibility(GONE);
            checkBox2.setColor(-1, Theme.key_windowBackgroundWhite, Theme.key_checkboxCheck);
            checkBox2.setDrawUnchecked(false);
            checkBox2.setDrawBackgroundAsArc(3);
            addView(checkBox2, LayoutHelper.createFrame(24, 24, Gravity.LEFT | Gravity.TOP, 0, 0, 0, 0));

            nameTextView = new TextView(context);
            nameTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            nameTextView.setTextSize(16);
            nameTextView.setLines(1);
            nameTextView.setMaxLines(1);
            nameTextView.setSingleLine(true);
            nameTextView.setEllipsize(TextUtils.TruncateAt.END);
            nameTextView.setGravity(Gravity.LEFT);
            addView(nameTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 82, 16, 64, 0));

            usernameTextView = new TextView(context);
            usernameTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3));
            usernameTextView.setTextSize(14);
            usernameTextView.setLines(1);
            usernameTextView.setMaxLines(1);
            usernameTextView.setSingleLine(true);
            usernameTextView.setEllipsize(TextUtils.TruncateAt.END);
            usernameTextView.setGravity(Gravity.LEFT);
            addView(usernameTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 82, 38, 64, 0));

            optionsButton = new ActionBarMenuItem(context, null, 0, Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3));
            optionsButton.setIcon(R.drawable.ic_ab_other);
            optionsButton.setBackgroundDrawable(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 1));
            optionsButton.setOnClickListener(v -> {
                if (currentItem != null) {
                    showChatOptionsMenu(currentItem, v);
                }
            });
            addView(optionsButton, LayoutHelper.createFrame(48, 48, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 8, 0));

            applyThemeColors();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(72), MeasureSpec.EXACTLY));
        }
        
        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);
            if (checkBox2 != null) {
                int avatarLeft = AndroidUtilities.dp(16);
                int avatarTop = (getMeasuredHeight() - AndroidUtilities.dp(50)) / 2;
                int avatarSize = AndroidUtilities.dp(50);
                
                int checkBoxSize = AndroidUtilities.dp(24);
                int x = avatarLeft + avatarSize - checkBoxSize + AndroidUtilities.dp(8);
                int y = avatarTop + avatarSize - checkBoxSize + AndroidUtilities.dp(8);
                
                checkBox2.layout(x, y, x + checkBox2.getMeasuredWidth(), y + checkBox2.getMeasuredHeight());
            }
        }
        
        public void setMultiSelectMode(boolean multiSelectMode) {
            if (multiSelectMode) {
                optionsButton.setVisibility(VISIBLE);
                checkBox2.setProgressDelegate(null);
                checkBox2.getCheckBoxBase().cancelCheckAnimator();
                checkBox2.setChecked(false, false);
                if (checkBox2.getCheckBoxBase().getProgress() != 0) {
                    checkBox2.getCheckBoxBase().setProgress(0);
                }
                checkBox2.setVisibility(VISIBLE);
            } else {
                optionsButton.setVisibility(VISIBLE);
                setSelected(false, true);
            }
        }

        public void setSelected(boolean selected) {
            setSelected(selected, false);
        }

        public void setSelected(boolean selected, boolean hideAfterAnimation) {
            boolean wasSelected = isSelected;
            isSelected = selected;
            
            boolean shouldAnimate = checkBox2.getVisibility() == VISIBLE && wasSelected != selected;
            
            if (hideAfterAnimation && !selected) {
                if (checkBox2.getVisibility() == VISIBLE) {
                    checkBox2.setProgressDelegate(progress -> {
                        if (progress == 0) {
                            checkBox2.setVisibility(GONE);
                            checkBox2.setProgressDelegate(null);
                        }
                    });
                    checkBox2.setChecked(false, true);
                } else {
                    checkBox2.setVisibility(GONE);
                }
                return;
            }
            
            if (checkBox2.getVisibility() == VISIBLE) {
                checkBox2.setChecked(selected, shouldAnimate);
                float expectedProgress = selected ? 1.0f : 0.0f;
                if (!shouldAnimate && checkBox2.getCheckBoxBase().getProgress() != expectedProgress) {
                    checkBox2.getCheckBoxBase().setProgress(expectedProgress);
                }
            }
        }

        public boolean isSelected() {
            return isSelected;
        }

        public void applyThemeColors() {
            int backgroundColor = Theme.getColor(Theme.key_windowBackgroundWhite);
            int titleColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
            int secondaryColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3);
            setBackgroundColor(backgroundColor);
            nameTextView.setTextColor(titleColor);
            usernameTextView.setTextColor(secondaryColor);
            optionsButton.setIconColor(secondaryColor);
            optionsButton.setBackgroundDrawable(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 1));
            checkBox2.setColor(-1, Theme.key_windowBackgroundWhite, Theme.key_checkboxCheck);
        }

        public void setDialog(HistoryItem item) {
            this.currentItem = item;
            applyThemeColors();
            
            isSelected = false;

            if (item.user != null) {
                avatarDrawable.setInfo(item.user);
                avatarImageView.setForUserOrChat(item.user, avatarDrawable);
                nameTextView.setText(Emoji.replaceEmoji(UserObject.getUserName(item.user), nameTextView.getPaint().getFontMetricsInt(), false));

                String username = UserObject.getPublicUsername(item.user);
                if (!TextUtils.isEmpty(username)) {
                    usernameTextView.setText("@" + username);
                    usernameTextView.setVisibility(VISIBLE);
                } else {
                    usernameTextView.setText("ID: " + item.user.id);
                    usernameTextView.setVisibility(VISIBLE);
                }
            } else if (item.chat != null) {
                avatarDrawable.setInfo(item.chat);
                avatarImageView.setForUserOrChat(item.chat, avatarDrawable);
                nameTextView.setText(Emoji.replaceEmoji(item.chat.title, nameTextView.getPaint().getFontMetricsInt(), false));

                String username = ChatObject.getPublicUsername(item.chat);
                if (!TextUtils.isEmpty(username)) {
                    usernameTextView.setText("@" + username);
                    usernameTextView.setVisibility(VISIBLE);
                } else {
                    if (item.chat.broadcast) {
                        usernameTextView.setText(LocaleController.getString("ChannelPrivate", R.string.ChannelPrivate));
                    } else {
                        usernameTextView.setText(LocaleController.getString("MegaPrivate", R.string.MegaPrivate));
                    }
                    usernameTextView.setVisibility(VISIBLE);
                }
            }
        }
        

    }

    private class EmptyStateCell extends FrameLayout {
        private TextView titleTextView;
        private TextView descriptionTextView;

        public EmptyStateCell(Context context) {
            super(context);

            titleTextView = new TextView(context);
            titleTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3));
            titleTextView.setTextSize(17);
            titleTextView.setGravity(Gravity.CENTER);
            addView(titleTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 32, 48, 32, 0));

            descriptionTextView = new TextView(context);
            descriptionTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3));
            descriptionTextView.setTextSize(15);
            descriptionTextView.setGravity(Gravity.CENTER);
            addView(descriptionTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 32, 80, 32, 48));

            applyThemeColors();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(200), MeasureSpec.EXACTLY)
            );
        }

        public void setText(String title, String description) {
            applyThemeColors();
            if (TextUtils.isEmpty(title)) {
                titleTextView.setVisibility(GONE);
            } else {
                titleTextView.setText(title);
                titleTextView.setVisibility(VISIBLE);
            }

            if (TextUtils.isEmpty(description)) {
                descriptionTextView.setVisibility(GONE);
            } else {
                descriptionTextView.setText(description);
                descriptionTextView.setVisibility(VISIBLE);
            }
        }

        public void applyThemeColors() {
            int backgroundColor = Theme.getColor(Theme.key_windowBackgroundWhite);
            int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3);
            setBackgroundColor(backgroundColor);
            titleTextView.setTextColor(textColor);
            descriptionTextView.setTextColor(textColor);
        }
    }

    private void enterMultiSelectMode() {
        isMultiSelectMode = true;
        selectedItems.clear();
        updateActionBarForMultiSelect();
        updateAllCellsMultiSelectMode();
    }

    private void exitMultiSelectMode() {
        isMultiSelectMode = false;
        selectedItems.clear();

        updateActionBarForNormalMode();
        updateAllCellsMultiSelectMode();
    }

    private void toggleItemSelection(HistoryItem item, HistoryCell cell) {
        if (selectedItems.contains(item)) {
            selectedItems.remove(item);
            cell.setSelected(false);
        } else {
            selectedItems.add(item);
            cell.setSelected(true);
        }
        updateActionBarTitle();
        
        if (selectedItems.isEmpty()) {
            exitMultiSelectMode();
        }
    }

    private void updateActionBarForMultiSelect() {
        if (actionBar != null) {
            actionBar.setBackButtonImage(R.drawable.ic_ab_back);
            actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
                @Override
                public void onItemClick(int id) {
                    if (id == -1) {
                        exitMultiSelectMode();
                    } else if (id == 1) {
                        showDeleteSelectedDialog();
                    }
                }
            });
            
            ActionBarMenu menu = actionBar.createMenu();
            menu.clearItems();
            deleteItem = menu.addItem(1, R.drawable.msg_delete);
            updateActionBarTitle();
        }
    }

    private void updateActionBarForNormalMode() {
        if (actionBar != null) {
            actionBar.setBackButtonImage(R.drawable.ic_ab_back);
            actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
                @Override
                public void onItemClick(int id) {
                    if (id == -1) {
                        finishFragment();
                    } else if (id == 1) {
                        presentFragment(new ChatHistorySearchActivity());
                    } else if (id == 2) {
                        showOptionsMenu();
                    }
                }
            });
            
            ActionBarMenu menu = actionBar.createMenu();
            menu.clearItems();
            searchItem = menu.addItem(1, R.drawable.ic_ab_search);
            searchItem.setOnClickListener(v -> presentFragment(new ChatHistorySearchActivity()));
            ActionBarMenuItem settingsItem = menu.addItem(2, R.drawable.msg_settings);
            settingsItem.setLongClickEnabled(false);
            updateTitle();
        }
    }

    private void updateActionBarTitle() {
        if (actionBar != null) {
            if (isMultiSelectMode) {
                actionBar.setTitle(selectedItems.size() + " " + getString(R.string.ChatHistorySelected));
            } else {
                updateTitle();
            }
        }
    }

    private void updateAllCellsMultiSelectMode() {
        if (viewPager == null) return;
        for (int i = 0; i < viewPager.getChildCount(); i++) {
            View child = viewPager.getChildAt(i);
            Object tag = child != null ? child.getTag() : null;
            if (tag instanceof RecyclerListView) {
                RecyclerListView recyclerView = (RecyclerListView) tag;
                for (int k = 0; k < recyclerView.getChildCount(); k++) {
                    View itemView = recyclerView.getChildAt(k);
                    if (itemView instanceof HistoryCell) {
                        HistoryCell cell = (HistoryCell) itemView;
                        cell.setMultiSelectMode(isMultiSelectMode);
                        if (!isMultiSelectMode) {
                            cell.setSelected(false);
                        }
                    }
                }
            }
        }
    }

    private void showDeleteSelectedDialog() {
        if (selectedItems.isEmpty()) {
            return;
        }
        
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.ChatHistoryDeleteChats));
        builder.setMessage(LocaleController.formatString(R.string.ChatHistoryDeleteConfirmation) + " " + selectedItems.size() + " " + getString(R.string.ChatHistorySelected) + "?");
        builder.setPositiveButton(getString(R.string.ChatHistoryDeleteChats), (dialog, which) -> {
            deleteSelectedChats();
        });
        builder.setNegativeButton(LocaleController.getString("Cancel", R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void deleteSelectedChats() {
        int count = selectedItems.size();
        for (HistoryItem item : selectedItems) {
            deleteChatFromHistory(item, false);
        }
        loadHistoryItems();
        exitMultiSelectMode();
        refreshAllPages();
        
        BulletinFactory.of(this).createSimpleBulletin(R.raw.ic_delete,
            LocaleController.formatPluralString("ChatHistory_ChatsRemoved", count)).show();
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ThemeDescription.ThemeDescriptionDelegate cellDelegate = () -> {
            if (fragmentView != null) {
                fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            }
            updateTabsStyle();
            refreshAllPages();
        };

        ArrayList<ThemeDescription> themeDescriptions = new ArrayList<>();

        themeDescriptions.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, cellDelegate, Theme.key_windowBackgroundWhite));

        if (tabsContainer != null) {
            themeDescriptions.add(new ThemeDescription(tabsContainer, ThemeDescription.FLAG_BACKGROUND, null, null, null, cellDelegate, Theme.key_windowBackgroundWhite));
        }

        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector));

        return themeDescriptions;
    }
}
