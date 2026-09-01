package com.exteragram.messenger.feed;

import androidx.collection.LongSparseArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * Coordinates the feed UI inside a chat-mode host: unread divider placement,
 * initial scroll restore, pagedown counter, reactions refresh, hidden-channel
 * undo and reconciliation of the visible list with the feed store.
 */
public class FeedChatIntegration {
    private Runnable channelsChangedCallback;
    private final int currentAccount;
    private boolean destroyed;
    private final Host host;
    private boolean initialScrollApplied;
    private boolean pagedownShownByScroll;
    private boolean pendingDividerScroll;
    private long pendingHideDialogId;
    private ScrollAnchor pendingInitialScrollRestore;
    private boolean reactionsRefreshScheduled;
    private boolean readyToMarkAsRead;
    private final boolean restoreDrawerScrollPosition;
    private boolean scrollPreservedNewerToUnread;
    private boolean settleAtNewestScheduled;
    private int totalScrollDy;
    private MessageObject unreadDivider;
    private boolean viewportActive;
    private static final int PAGEDOWN_SCROLL_THRESHOLD = AndroidUtilities.dp(100.0f);
    private static final int NEAR_NEWEST_THRESHOLD = AndroidUtilities.dp(160.0f);
    private int preserveScrollLoadIndex = -1;
    private int lastPagedownCount = -1;
    private final Runnable settleAtNewestRunnable = this::settleAtNewestNow;
    private final int reactionsRequestGuid = ConnectionsManager.generateClassGuid();
    private final LongSparseArray<Long> reactionsLastCheckTimes = new LongSparseArray<>();
    private final LongSparseArray<ArrayList<Integer>> pendingReactionIds = new LongSparseArray<>();
    private final Runnable reactionsRefreshRunnable = this::flushReactionsRefresh;

    public interface Host {
        boolean canScrollToNewer();

        ScrollAnchor captureScrollAnchor();

        void deleteRows(ArrayList<Integer> rowIds);

        int getDistanceToNewerPx();

        BaseFragment getFragment();

        int getLastVisibleMessageIndex();

        ArrayList<MessageObject> getMessages();

        int getNewestVisibleMessageIndex();

        void invalidateVisiblePart();

        boolean isFirstLoadComplete();

        boolean isListReady();

        boolean isListScrollIdle();

        boolean isPagedownButtonVisible();

        boolean isScrollAnimationRunning();

        void materializeRow(MessageObject message);

        int nextStableId();

        void notifyAllMessagesChanged();

        void notifyMessageInserted(int index);

        void notifyMessageRemoved(int index);

        void onFeedListChanged();

        void reloadFeed();

        void requestOlderFeedPage();

        void restoreScrollAnchor(ScrollAnchor anchor);

        void scrollToMessage(int index, int offset);

        void scrollToMessageAnimated(int index, int offset);

        void setPagedownButtonVisible(boolean visible);

        void setPagedownCount(int count);

        void showEmptyFeedProgress();

        void showEmptyFeedState();

        int stableIdForDateHeader(int dateKeyInt);
    }

    public static final class ScrollAnchor {
        public final int offsetTop;
        public final MessageObject row;

        public ScrollAnchor(MessageObject row, int offsetTop) {
            this.row = row;
            this.offsetTop = offsetTop;
        }
    }

    private void settleAtNewestNow() {
        settleAtNewestScheduled = false;
        if (destroyed || !viewportActive || !host.isListReady() || host.isScrollAnimationRunning() || host.canScrollToNewer()) {
            return;
        }
        settleUnreadDivider();
    }

    public FeedChatIntegration(int account, Host host, boolean restoreDrawerScrollPosition) {
        this.currentAccount = account;
        this.host = host;
        this.restoreDrawerScrollPosition = restoreDrawerScrollPosition;
    }

    /**
     * Re-applies pending initial scroll/divider positioning after the visible
     * row set changed.
     */
    public void refreshRows() {
        requestPendingInitialPosition();
    }

    public void setChannelsChangedCallback(Runnable callback) {
        channelsChangedCallback = callback;
    }

    public void notifyChannelsChanged() {
        if (channelsChangedCallback != null) {
            channelsChangedCallback.run();
        }
    }

    public void resetUiState() {
        resetMetadataRefresh();
        initialScrollApplied = false;
        readyToMarkAsRead = false;
        pendingDividerScroll = false;
        pendingInitialScrollRestore = null;
        scrollPreservedNewerToUnread = false;
        preserveScrollLoadIndex = -1;
        unreadDivider = null;
        lastPagedownCount = -1;
        pagedownShownByScroll = false;
        totalScrollDy = 0;
        pendingHideDialogId = 0L;
        if (settleAtNewestScheduled) {
            AndroidUtilities.cancelRunOnUIThread(settleAtNewestRunnable);
            settleAtNewestScheduled = false;
        }
    }

    private boolean hasMaterializedPostRows() {
        ArrayList<MessageObject> messages = host.getMessages();
        for (int i = 0; i < messages.size(); i++) {
            if (FeedMessageUtils.isPostRow(messages.get(i))) {
                return true;
            }
        }
        return false;
    }

    public void onMessagesLoaded() {
        if (!host.getFragment().isPaused() && host.isListReady() && hasMaterializedPostRows()) {
            if (!initialScrollApplied) {
                initialScrollApplied = true;
                FeedController feedController = FeedController.getInstance(currentAccount);
                boolean scrollInitialUnread = feedController.consumeInitialUnreadScroll();
                FeedController.SavedScrollPosition drawerPosition = restoreDrawerScrollPosition ? feedController.getDrawerScrollPosition() : null;
                MessageObject anchorRow = drawerPosition != null ? feedController.getMessage(drawerPosition.dialogId, drawerPosition.messageId) : null;
                if (anchorRow != null && host.getMessages().contains(anchorRow)) {
                    applyUnreadDivider(false);
                    pendingInitialScrollRestore = new ScrollAnchor(anchorRow, drawerPosition.offsetTop);
                    requestPendingInitialPosition();
                } else {
                    applyUnreadDivider(scrollInitialUnread || host.getDistanceToNewerPx() > NEAR_NEWEST_THRESHOLD ? scrollInitialUnread : true);
                }
            }
        }
    }

    public void onHostResumed() {
        if (!host.getMessages().isEmpty()) {
            onMessagesLoaded();
        }
        requestPendingInitialPosition();
    }

    private boolean hasPendingInitialPosition() {
        return pendingInitialScrollRestore != null || pendingDividerScroll;
    }

    private void requestPendingInitialPosition() {
        if (host.getFragment().isPaused() || !host.isListReady()) {
            return;
        }
        ScrollAnchor anchor = pendingInitialScrollRestore;
        if (anchor != null) {
            host.restoreScrollAnchor(anchor);
            return;
        }
        if (pendingDividerScroll) {
            int index = unreadDivider == null ? -1 : host.getMessages().indexOf(unreadDivider);
            if (index < 0) {
                pendingDividerScroll = false;
                readyToMarkAsRead = true;
            } else {
                host.scrollToMessage(index, AndroidUtilities.dp(48.0f));
            }
        }
    }

    public void setViewportActive(boolean active) {
        if (viewportActive == active) {
            return;
        }
        viewportActive = active;
        if (!active) {
            if (settleAtNewestScheduled) {
                AndroidUtilities.cancelRunOnUIThread(settleAtNewestRunnable);
                settleAtNewestScheduled = false;
            }
            cancelPendingReactionsRefresh();
            return;
        }
        onHostResumed();
        if (host.getMessages().isEmpty()) {
            return;
        }
        onVisiblePartInvalidated();
    }

    public boolean canMarkVisibleAsRead() {
        return viewportActive && !host.getFragment().isPaused() && initialScrollApplied && readyToMarkAsRead && !pendingDividerScroll && pendingInitialScrollRestore == null && !BaseFragment.hasSheets(host.getFragment());
    }

    public void onPostCellVisible(MessageObject message, boolean firstVisible, boolean lastVisible) {
        if (message == null || message.isSponsored()) {
            return;
        }
        requestReactionsRefresh(message);
        if (canMarkVisibleAsRead()) {
            if (firstVisible || lastVisible) {
                FeedController.getInstance(currentAccount).onPostSeen(message.getDialogId(), message.getRealId());
            }
        }
    }

    private void requestReactionsRefresh(MessageObject message) {
        if (destroyed || !viewportActive || message.messageOwner == null) {
            return;
        }
        int realId = message.getRealId();
        long dialogId = message.getDialogId();
        if (realId <= 0 || dialogId == 0) {
            return;
        }
        int id = message.getId();
        if (message.messageOwner.action == null || message.canSetReaction()) {
            long now = System.currentTimeMillis();
            if (now - reactionsLastCheckTimes.get(id, 0L) <= 15000) {
                return;
            }
            reactionsLastCheckTimes.put(id, now);
            ArrayList<Integer> ids = pendingReactionIds.get(dialogId);
            if (ids == null) {
                ids = new ArrayList<>();
                pendingReactionIds.put(dialogId, ids);
            }
            ids.add(realId);
            if (!reactionsRefreshScheduled) {
                reactionsRefreshScheduled = true;
                AndroidUtilities.runOnUIThread(reactionsRefreshRunnable);
            }
        }
    }

    private void flushReactionsRefresh() {
        reactionsRefreshScheduled = false;
        if (destroyed || !viewportActive) {
            pendingReactionIds.clear();
            return;
        }
        for (int i = 0; i < pendingReactionIds.size(); i++) {
            TLRPC.TL_messages_getMessagesReactions request = new TLRPC.TL_messages_getMessagesReactions();
            request.peer = MessagesController.getInstance(currentAccount).getInputPeer(pendingReactionIds.keyAt(i));
            request.id.addAll(pendingReactionIds.valueAt(i));
            ConnectionsManager.getInstance(currentAccount).bindRequestToGuid(ConnectionsManager.getInstance(currentAccount).sendRequest(request, (RequestDelegate) (response, error) -> {
                if (response instanceof TLRPC.Updates) {
                    TLRPC.Updates updates = (TLRPC.Updates) response;
                    for (int j = 0; j < updates.updates.size(); j++) {
                        TLRPC.Update update = updates.updates.get(j);
                        if (update instanceof TL_update.TL_updateMessageReactions) {
                            ((TL_update.TL_updateMessageReactions) update).updateUnreadState = false;
                        }
                    }
                    MessagesController.getInstance(currentAccount).processUpdates(updates, false);
                }
            }), reactionsRequestGuid);
        }
        pendingReactionIds.clear();
    }

    private void cancelPendingReactionsRefresh() {
        AndroidUtilities.cancelRunOnUIThread(reactionsRefreshRunnable);
        reactionsRefreshScheduled = false;
        pendingReactionIds.clear();
    }

    private void resetMetadataRefresh() {
        cancelPendingReactionsRefresh();
        reactionsLastCheckTimes.clear();
        ConnectionsManager.getInstance(currentAccount).cancelRequestsForGuid(reactionsRequestGuid);
    }

    public void markAllRead() {
        FeedController.getInstance(currentAccount).markAllRead();
        applyUnreadDivider(false);
        refreshRows();
        host.invalidateVisiblePart();
    }

    public void settleUnreadDivider() {
        int lastVisibleIndex;
        if (canMarkVisibleAsRead() && host.isListReady() && (lastVisibleIndex = host.getLastVisibleMessageIndex()) != Integer.MIN_VALUE) {
            ArrayList<MessageObject> messages = host.getMessages();
            int limit = host.canScrollToNewer() ? Math.min(lastVisibleIndex, messages.size() - 1) : messages.size() - 1;
            if (limit >= 0) {
                FeedController feedController = FeedController.getInstance(currentAccount);
                for (int i = 0; i <= limit; i++) {
                    MessageObject message = messages.get(i);
                    if (message != null && !message.isDateObject && message.type != MessageObject.TYPE_LOADING && !message.isSponsored()) {
                        feedController.onPostSeen(message.getDialogId(), message.getRealId());
                    }
                }
            }
            applyUnreadDivider(false);
            refreshRows();
            updatePagedownCounter();
        }
    }

    public void onScrollAnimationFinished() {
        if (destroyed || !viewportActive || settleAtNewestScheduled) {
            return;
        }
        settleAtNewestScheduled = true;
        AndroidUtilities.runOnUIThread(settleAtNewestRunnable);
    }

    public void destroy() {
        destroyed = true;
        resetMetadataRefresh();
        if (settleAtNewestScheduled) {
            AndroidUtilities.cancelRunOnUIThread(settleAtNewestRunnable);
            settleAtNewestScheduled = false;
        }
        pendingInitialScrollRestore = null;
    }

    public void saveDrawerScrollPosition() {
        ScrollAnchor anchor = host.captureScrollAnchor();
        if (anchor == null || anchor.row == null) {
            return;
        }
        FeedController.getInstance(currentAccount).saveDrawerScrollPosition(anchor.row.getDialogId(), anchor.row.getRealId(), anchor.offsetTop);
    }

    public void onReadStateRefreshed() {
        ScrollAnchor anchor = host.captureScrollAnchor();
        boolean hadPendingPosition = hasPendingInitialPosition();
        applyUnreadDivider(false);
        refreshRows();
        if (!hadPendingPosition || !hasPendingInitialPosition()) {
            host.restoreScrollAnchor(anchor);
        }
        lastPagedownCount = -1;
        updatePagedownCounter();
        host.invalidateVisiblePart();
    }

    public void onPreserveScrollLoadStarted(int loadIndex) {
        preserveScrollLoadIndex = loadIndex;
    }

    public boolean consumePreserveScrollLoad(int loadIndex) {
        if (preserveScrollLoadIndex != loadIndex) {
            return false;
        }
        preserveScrollLoadIndex = -1;
        return true;
    }

    public void beforePreservedNewerMessagesInserted() {
        scrollPreservedNewerToUnread = host.isListScrollIdle() && !host.isScrollAnimationRunning() && host.getDistanceToNewerPx() <= NEAR_NEWEST_THRESHOLD;
    }

    public boolean afterPreservedNewerMessagesInserted() {
        boolean wasNearNewest = scrollPreservedNewerToUnread;
        applyUnreadDivider(wasNearNewest, true);
        scrollPreservedNewerToUnread = false;
        return wasNearNewest;
    }

    public void applyUnreadDivider(boolean scrollToDivider) {
        applyUnreadDivider(scrollToDivider, false);
    }

    private void applyUnreadDivider(boolean scrollToDivider, boolean animate) {
        if (!host.isListReady()) {
            return;
        }
        ArrayList<MessageObject> messages = host.getMessages();
        if (messages.isEmpty()) {
            unreadDivider = null;
            readyToMarkAsRead = false;
            return;
        }
        MessageObject previousDivider = unreadDivider;
        int previousIndex = previousDivider == null ? -1 : messages.indexOf(previousDivider);
        MessageObject reuseDivider = previousIndex >= 0 ? unreadDivider : null;
        if (previousIndex >= 0) {
            messages.remove(previousIndex);
        }
        unreadDivider = null;
        int firstUnreadIndex = FeedController.getInstance(currentAccount).findFirstUnreadIndex(messages);
        if (firstUnreadIndex < 0) {
            pendingDividerScroll = false;
            if (previousIndex >= 0) {
                if (scrollToDivider && !animate) {
                    host.notifyAllMessagesChanged();
                } else {
                    host.notifyMessageRemoved(previousIndex);
                    host.invalidateVisiblePart();
                }
            }
            readyToMarkAsRead = true;
            return;
        }
        int insertIndex = findDividerInsertIndex(messages, firstUnreadIndex);
        if (reuseDivider == null) {
            reuseDivider = FeedMessageUtils.createUnreadDivider(currentAccount, host.nextStableId());
        }
        messages.add(insertIndex, reuseDivider);
        unreadDivider = reuseDivider;
        if (scrollToDivider) {
            readyToMarkAsRead = false;
            if (!animate) {
                host.notifyAllMessagesChanged();
            } else if (previousIndex < 0) {
                host.notifyMessageInserted(insertIndex);
            } else if (previousIndex != insertIndex) {
                host.notifyMessageRemoved(previousIndex);
                host.notifyMessageInserted(insertIndex);
            }
            pendingDividerScroll = true;
            requestPendingInitialPosition();
            host.invalidateVisiblePart();
            return;
        }
        readyToMarkAsRead = true;
        if (previousIndex < 0) {
            host.notifyMessageInserted(insertIndex);
            host.invalidateVisiblePart();
        } else if (previousIndex != insertIndex) {
            host.notifyMessageRemoved(previousIndex);
            host.notifyMessageInserted(insertIndex);
            host.invalidateVisiblePart();
        }
    }

    public boolean scrollToUnreadDividerIfAbove() {
        int newestVisibleIndex;
        if (!host.isListReady()) {
            return false;
        }
        ArrayList<MessageObject> messages = host.getMessages();
        MessageObject divider = unreadDivider;
        if (divider == null || !messages.contains(divider)) {
            applyUnreadDivider(false);
            refreshRows();
            messages = host.getMessages();
        }
        int index = messages.indexOf(unreadDivider);
        if (index < 0 || (newestVisibleIndex = host.getNewestVisibleMessageIndex()) == Integer.MIN_VALUE || index >= newestVisibleIndex) {
            return false;
        }
        host.scrollToMessageAnimated(index, AndroidUtilities.dp(48.0f));
        host.invalidateVisiblePart();
        return true;
    }

    private static int findDividerInsertIndex(ArrayList<MessageObject> messages, int firstUnreadIndex) {
        MessageObject message = messages.get(firstUnreadIndex);
        long groupId = message.getGroupId();
        if (groupId == 0) {
            return firstUnreadIndex + 1;
        }
        long dialogId = message.getDialogId();
        int maxIndex = firstUnreadIndex + 1;
        for (int i = 0; i < messages.size(); i++) {
            MessageObject row = messages.get(i);
            if (row != null && row.getGroupId() == groupId && row.getDialogId() == dialogId) {
                maxIndex = Math.max(maxIndex, i + 1);
            }
        }
        return maxIndex;
    }

    public void onVisiblePartInvalidated() {
        if (!viewportActive || host.getFragment().isPaused()) {
            return;
        }
        ScrollAnchor anchor = pendingInitialScrollRestore;
        if (anchor != null) {
            host.restoreScrollAnchor(anchor);
            pendingInitialScrollRestore = null;
        }
        maybeScrollToDivider();
        updatePagedownCounter();
    }

    private void maybeScrollToDivider() {
        if (pendingDividerScroll) {
            if (!host.isListReady() || unreadDivider == null) {
                pendingDividerScroll = false;
                readyToMarkAsRead = true;
                return;
            }
            int lastVisibleIndex = host.getLastVisibleMessageIndex();
            if (lastVisibleIndex == Integer.MIN_VALUE) {
                return;
            }
            int index = host.getMessages().indexOf(unreadDivider);
            if (index >= 0 && index > lastVisibleIndex) {
                host.scrollToMessage(index, AndroidUtilities.dp(48.0f));
            }
            pendingDividerScroll = false;
            readyToMarkAsRead = true;
        }
    }

    private void updatePagedownCounter() {
        if (host.isListReady() && !host.isScrollAnimationRunning()) {
            int newestVisibleIndex = host.getNewestVisibleMessageIndex();
            int unreadCount = newestVisibleIndex == Integer.MIN_VALUE ? 0 : FeedController.getInstance(currentAccount).countUnreadBelow(host.getMessages(), newestVisibleIndex);
            if (unreadCount != lastPagedownCount) {
                lastPagedownCount = unreadCount;
                host.setPagedownCount(unreadCount);
            }
            if (unreadCount > 0) {
                pagedownShownByScroll = false;
                host.setPagedownButtonVisible(true);
            } else {
                if (host.canScrollToNewer()) {
                    return;
                }
                pagedownShownByScroll = false;
                host.setPagedownButtonVisible(false);
            }
        }
    }

    public void onScrolled(int dy) {
        if (viewportActive && host.isListReady() && !host.isScrollAnimationRunning()) {
            if (!host.canScrollToNewer()) {
                totalScrollDy = 0;
                pagedownShownByScroll = false;
                host.setPagedownButtonVisible(false);
                if (dy <= 0 || settleAtNewestScheduled) {
                    return;
                }
                settleAtNewestScheduled = true;
                AndroidUtilities.runOnUIThread(settleAtNewestRunnable);
                return;
            }
            if (lastPagedownCount > 0) {
                return;
            }
            boolean visible = host.isPagedownButtonVisible();
            if (dy > 0) {
                if (visible) {
                    return;
                }
                int total = totalScrollDy + dy;
                totalScrollDy = total;
                if (total > PAGEDOWN_SCROLL_THRESHOLD) {
                    totalScrollDy = 0;
                    pagedownShownByScroll = true;
                    host.setPagedownButtonVisible(true);
                }
                return;
            }
            if (dy < 0 && pagedownShownByScroll && visible) {
                int total = totalScrollDy + dy;
                totalScrollDy = total;
                if (total < (-PAGEDOWN_SCROLL_THRESHOLD)) {
                    totalScrollDy = 0;
                    host.setPagedownButtonVisible(false);
                }
            }
        }
    }

    public void onMessagesDeleted() {
        if (unreadDivider == null) {
            return;
        }
        ArrayList<MessageObject> messages = host.getMessages();
        int index = messages.indexOf(unreadDivider);
        for (int i = 0; i < index; i++) {
            if (FeedMessageUtils.isPostRow(messages.get(i))) {
                return;
            }
        }
        unreadDivider = null;
        if (index < 0 || !host.isListReady()) {
            return;
        }
        messages.remove(index);
        host.notifyMessageRemoved(index);
    }

    public void loadReplyMessages(ArrayList<MessageObject> messages, int classGuid, int guid) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        LongSparseArray<ArrayList<MessageObject>> byDialog = new LongSparseArray<>();
        for (int i = 0; i < messages.size(); i++) {
            MessageObject message = messages.get(i);
            if (message != null && !message.isDateObject) {
                long dialogId = message.getDialogId();
                if (dialogId != 0) {
                    ArrayList<MessageObject> list = byDialog.get(dialogId);
                    if (list == null) {
                        list = new ArrayList<>();
                        byDialog.put(dialogId, list);
                    }
                    list.add(message);
                }
            }
        }
        for (int i = 0; i < byDialog.size(); i++) {
            MediaDataController.getInstance(currentAccount).loadReplyMessagesForMessages(byDialog.valueAt(i), byDialog.keyAt(i), classGuid, 0L, null, guid, null);
        }
    }

    public void reconcileWithStore() {
        if (!host.isListReady()) {
            return;
        }
        FeedStore store = FeedController.getInstance(currentAccount).getStore();
        ArrayList<MessageObject> messages = host.getMessages();
        int postCount = 0;
        for (int i = 0; i < messages.size(); i++) {
            if (FeedMessageUtils.isPostRow(messages.get(i))) {
                postCount++;
            }
        }
        ArrayList<MessageObject> visibleMessages = store.getVisibleMessages();
        if (postCount == 0) {
            if (!visibleMessages.isEmpty() && host.isFirstLoadComplete()) {
                host.reloadFeed();
            } else {
                if (store.isEmpty() || store.isEndReached()) {
                    return;
                }
                host.requestOlderFeedPage();
            }
            return;
        }
        if (store.isEmpty()) {
            host.reloadFeed();
            return;
        }
        HashSet<MessageObject> visibleSet = new HashSet<>(visibleMessages);
        ArrayList<Integer> invalidRowIds = null;
        ArrayList<MessageObject> outdatedRows = null;
        for (int i = 0; i < messages.size(); i++) {
            MessageObject message = messages.get(i);
            if (FeedMessageUtils.isPostRow(message) && !visibleSet.contains(message)) {
                if (store.getMessage(message.getDialogId(), message.getId()) == message) {
                    if (outdatedRows == null) {
                        outdatedRows = new ArrayList<>();
                    }
                    outdatedRows.add(message);
                } else {
                    if (invalidRowIds == null) {
                        invalidRowIds = new ArrayList<>();
                    }
                    invalidRowIds.add(message.getId());
                }
            }
        }
        int removedCount = (invalidRowIds == null ? 0 : invalidRowIds.size()) + (outdatedRows == null ? 0 : outdatedRows.size());
        if (removedCount == 0 && postCount == visibleMessages.size()) {
            return;
        }
        ScrollAnchor anchor = host.captureScrollAnchor();
        boolean hadPendingPosition = hasPendingInitialPosition();
        if (invalidRowIds != null) {
            host.deleteRows(invalidRowIds);
        }
        if (outdatedRows != null) {
            for (int i = 0; i < outdatedRows.size(); i++) {
                int index = messages.indexOf(outdatedRows.get(i));
                if (index >= 0) {
                    messages.remove(index);
                    host.notifyMessageRemoved(index);
                }
            }
        }
        HashSet<MessageObject> existingPosts = new HashSet<>();
        for (int i = 0; i < messages.size(); i++) {
            if (FeedMessageUtils.isPostRow(messages.get(i))) {
                existingPosts.add(messages.get(i));
            }
        }
        int searchFrom = 0;
        boolean insertedAny = false;
        for (int i = 0; i < visibleMessages.size(); i++) {
            MessageObject visible = visibleMessages.get(i);
            if (existingPosts.contains(visible)) {
                while (searchFrom < messages.size() && messages.get(searchFrom) != visible) {
                    searchFrom++;
                }
                if (searchFrom < messages.size()) {
                    searchFrom++;
                }
            } else {
                host.materializeRow(visible);
                int insertIndex = getInsertIndex(messages, visible, visibleMessages, i, searchFrom);
                messages.add(insertIndex, visible);
                host.notifyMessageInserted(insertIndex);
                searchFrom = insertIndex + 1;
                insertedAny = true;
            }
        }
        boolean changed = removedCount > 0 || insertedAny;
        if (!normalizeDateHeaders(messages) && !changed) {
            return;
        }
        host.onFeedListChanged();
        applyUnreadDivider(false);
        refreshRows();
        onFeedExclusionsChanged();
        if (!hadPendingPosition || !hasPendingInitialPosition()) {
            host.restoreScrollAnchor(anchor);
        }
        host.invalidateVisiblePart();
        if (store.getVisibleCount() == 0) {
            if (store.isEndReached()) {
                host.showEmptyFeedState();
            } else {
                host.showEmptyFeedProgress();
                host.requestOlderFeedPage();
            }
        }
    }

    private static int getInsertIndex(ArrayList<MessageObject> messages, MessageObject message, ArrayList<MessageObject> visibleMessages, int visibleIndex, int startSearchIndex) {
        int insertIndex = Math.min(startSearchIndex, messages.size());
        if (visibleIndex <= 0 || insertIndex >= messages.size()) {
            return insertIndex;
        }
        MessageObject previousVisible = visibleMessages.get(visibleIndex - 1);
        MessageObject rowAtInsert = messages.get(insertIndex);
        if (previousVisible == null || rowAtInsert == null || !rowAtInsert.isDateObject || previousVisible.dateKeyInt == message.dateKeyInt || rowAtInsert.dateKeyInt != previousVisible.dateKeyInt) {
            return insertIndex;
        }
        return insertIndex + 1;
    }

    private boolean normalizeDateHeaders(ArrayList<MessageObject> messages) {
        MessageObject lastPost = null;
        boolean groupOpen = false;
        boolean changed = false;
        int i = 0;
        while (i < messages.size()) {
            MessageObject row = messages.get(i);
            if (row == null || row.type == MessageObject.TYPE_LOADING || row.isSponsored()) {
                i++;
                continue;
            }
            if (row.isDateObject) {
                if (!groupOpen) {
                    messages.remove(i);
                    host.notifyMessageRemoved(i);
                    changed = true;
                } else if (lastPost.dateKeyInt != row.dateKeyInt) {
                    messages.add(i, createDateHeader(lastPost));
                    host.notifyMessageInserted(i);
                    changed = true;
                    i++;
                    groupOpen = false;
                    lastPost = null;
                } else {
                    i++;
                    groupOpen = false;
                    lastPost = null;
                }
            } else {
                if (groupOpen && lastPost.dateKeyInt != row.dateKeyInt) {
                    messages.add(i, createDateHeader(lastPost));
                    host.notifyMessageInserted(i);
                    changed = true;
                    i++;
                    groupOpen = false;
                    lastPost = null;
                } else {
                    i++;
                    lastPost = row;
                    groupOpen = true;
                }
            }
        }
        if (groupOpen && lastPost != null) {
            messages.add(createDateHeader(lastPost));
            host.notifyMessageInserted(messages.size() - 1);
            changed = true;
        }
        return changed;
    }

    private MessageObject createDateHeader(MessageObject message) {
        return FeedMessageUtils.createDateHeader(currentAccount, message, host.stableIdForDateHeader(message.dateKeyInt));
    }

    public ArrayList<Integer> collectLocalRowIds(long dialogId, ArrayList<Integer> messageIds, int maxId) {
        ArrayList<Integer> rowIds = new ArrayList<>();
        HashSet<Integer> requested = messageIds != null ? new HashSet<>(messageIds) : null;
        ArrayList<MessageObject> messages = host.getMessages();
        for (int i = 0; i < messages.size(); i++) {
            MessageObject message = messages.get(i);
            if (FeedMessageUtils.isPostRow(message) && message.getDialogId() == dialogId) {
                int realId = message.getRealId();
                boolean matches;
                if (requested != null) {
                    matches = requested.contains(realId);
                } else {
                    matches = realId > 0 && realId <= maxId;
                }
                if (matches) {
                    rowIds.add(message.getId());
                }
            }
        }
        return rowIds;
    }

    public static void mergeDeletedIds(ArrayList<Integer> target, ArrayList<Integer> source) {
        for (int i = 0; i < source.size(); i++) {
            if (!target.contains(source.get(i))) {
                target.add(source.get(i));
            }
        }
    }

    public void hideChannelWithUndo(final long dialogId, CharSequence title) {
        FeedConfig feedConfig = FeedConfig.getInstance(currentAccount);
        FeedController feedController = FeedController.getInstance(currentAccount);
        feedConfig.setExcluded(dialogId, true);
        feedController.markConfigApplied();
        feedController.getStore().setHidden(dialogId, true);
        pendingHideDialogId = dialogId;
        reconcileWithStore();
        onFeedExclusionsChanged();
        notifyChannelsChanged();
        BulletinFactory.of(host.getFragment()).createUndoBulletin(AndroidUtilities.replaceTags(LocaleController.formatString(R.string.FeedChannelHidden, title)), this::undoHideChannel, () -> {
            if (pendingHideDialogId == dialogId) {
                pendingHideDialogId = 0L;
            }
        }).show();
    }

    private void undoHideChannel() {
        long dialogId = pendingHideDialogId;
        if (dialogId == 0) {
            return;
        }
        pendingHideDialogId = 0;
        FeedController feedController = FeedController.getInstance(currentAccount);
        FeedConfig.getInstance(currentAccount).setExcluded(dialogId, false);
        feedController.markConfigApplied();
        feedController.getStore().setHidden(dialogId, false);
        reconcileWithStore();
        onFeedExclusionsChanged();
        notifyChannelsChanged();
    }

    public void onFeedExclusionsChanged() {
        lastPagedownCount = -1;
        updatePagedownCounter();
        NotificationCenter.getInstance(currentAccount).postNotificationNameOnUIThread(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_READ_DIALOG_MESSAGE);
    }
}
