package com.exteragram.messenger.feed;

import android.util.SparseArray;
import android.util.SparseIntArray;

import androidx.collection.LongSparseArray;

import com.exteragram.messenger.ExteraConfig;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;

/**
 * Per-account controller that assembles and maintains the merged channel feed:
 * loads pages across all included channels (via FeedTimelineLoader +
 * FeedBackfillCoordinator), keeps the in-memory store, tracks unread state
 * and posts results through NotificationCenter.messagesDidLoad.
 */
public class FeedController implements NotificationCenter.NotificationCenterDelegate {
    private static final FeedController[] Instance = new FeedController[16];
    private static final Object[] lockObjects = new Object[16];
    private int attemptRounds;
    private final FeedBackfillCoordinator backfill;
    private int cachedIncludedChannelCount;
    private final int closedRefreshGuid;
    private final Runnable closedRefreshRunnable;
    private boolean closedRefreshScheduled;
    private int configGeneration;
    public final int currentAccount;
    private SavedScrollPosition drawerScrollPosition;
    private boolean hasChannels;
    private boolean hasIncludedChannels;
    private int heldGuid;
    private int heldLoadIndex;
    private final ArrayList<int[]> initialLoadWaiters;
    private boolean initialUnreadScrollPending;
    private final FeedTimelineLoader loader;
    private boolean loading;
    private boolean loadingNewer;
    private boolean newerPagingBoundsDirty;
    private boolean olderPagingBoundsDirty;
    private int resumedUiClients;
    private int sessionGeneration;
    private int staleEnumerationRetries;
    private final FeedStore store;
    private int uiActiveClients;
    private final FeedUnreadTracker unreadTracker;

    public interface ChannelsCallback {
        void onChannels(ArrayList<TLRPC.Chat> channels, int includedCount, boolean failed, int configGeneration);
    }

    static {
        for (int i = 0; i < 16; i++) {
            lockObjects[i] = new Object();
        }
    }

    public static FeedController peekInstance(int account) {
        return Instance[account];
    }

    public static FeedController getInstance(int account) {
        FeedController controller = Instance[account];
        if (controller != null) {
            return controller;
        }
        synchronized (lockObjects[account]) {
            controller = Instance[account];
            if (controller == null) {
                controller = new FeedController(account);
                Instance[account] = controller;
            }
        }
        return controller;
    }

    public static final class SavedScrollPosition {
        public final long dialogId;
        public final int messageId;
        public final int offsetTop;

        private SavedScrollPosition(long dialogId, int messageId, int offsetTop) {
            this.dialogId = dialogId;
            this.messageId = messageId;
            this.offsetTop = offsetTop;
        }
    }

    private FeedController(final int account) {
        FeedStore feedStore = new FeedStore();
        this.store = feedStore;
        this.initialUnreadScrollPending = true;
        this.initialLoadWaiters = new ArrayList<>();
        this.closedRefreshGuid = ConnectionsManager.generateClassGuid();
        this.closedRefreshRunnable = this::runClosedRefresh;
        this.currentAccount = account;
        this.unreadTracker = new FeedUnreadTracker(account, feedStore.getMessages());
        this.loader = new FeedTimelineLoader(account);
        this.backfill = new FeedBackfillCoordinator(account, this::onBackfillRoundFinished);
        AndroidUtilities.runOnUIThread(() -> {
            NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.messagesDidLoad);
            NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.loadingMessagesFailed);
            NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.messagesDeleted);
            NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.historyCleared);
            NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.didReceiveNewMessages);
            FeedChannelRegistry.getInstance(account).addListener(FeedController.this::onFeedChannelsChanged);
        });
    }

    private void onFeedChannelsChanged(HashSet<Long> added, HashSet<Long> removed) {
        loader.invalidateChannelCache();
        for (Long dialogId : removed) {
            deleteHistory(dialogId, Integer.MAX_VALUE);
        }
        if (added.isEmpty()) {
            NotificationCenter.getInstance(currentAccount).postNotificationNameOnUIThread(NotificationCenter.feedNeedReload, Boolean.FALSE);
        } else {
            reconcileChannelSet(aBoolean -> NotificationCenter.getInstance(currentAccount).postNotificationNameOnUIThread(NotificationCenter.feedNeedReload, aBoolean));
        }
    }

    public FeedStore getStore() {
        return store;
    }

    public ArrayList<MessageObject> getMessages() {
        return store.getMessages();
    }

    public boolean isLoading() {
        return loading || loadingNewer;
    }

    public boolean hasMessagesForDialog(long dialogId) {
        return store.hasMessagesForDialog(dialogId);
    }

    public boolean hasChannels() {
        return hasChannels;
    }

    public boolean hasIncludedChannels() {
        return hasIncludedChannels;
    }

    public int getIncludedChannelCount() {
        return cachedIncludedChannelCount;
    }

    public void setUiActive(boolean active) {
        int clients = uiActiveClients;
        if (!active) {
            if (clients == 0) {
                return;
            }
            int remaining = clients - 1;
            uiActiveClients = remaining;
            if (remaining == 0) {
                cancelLoads();
                trimForInactiveCache();
            }
            return;
        }
        int updated = clients + 1;
        uiActiveClients = updated;
        if (updated > 1) {
            return;
        }
        if (closedRefreshScheduled) {
            AndroidUtilities.cancelRunOnUIThread(closedRefreshRunnable);
            closedRefreshScheduled = false;
        }
        if (loadingNewer) {
            cancelLoads();
        }
    }

    private boolean isUiActive() {
        return uiActiveClients > 0;
    }

    public void setUiResumed(boolean resumed) {
        int clients = resumedUiClients;
        if (resumed) {
            resumedUiClients = clients + 1;
        } else if (clients > 0) {
            resumedUiClients = clients - 1;
        }
    }

    public void clear() {
        sessionGeneration++;
        configGeneration = FeedConfig.getInstance(currentAccount).getGeneration();
        unreadTracker.clear();
        drawerScrollPosition = null;
        store.clear();
        loading = false;
        loadingNewer = false;
        olderPagingBoundsDirty = false;
        newerPagingBoundsDirty = false;
        attemptRounds = 0;
        staleEnumerationRetries = 0;
        initialLoadWaiters.clear();
        backfill.cancel();
        backfill.clearExhausted();
        if (closedRefreshScheduled) {
            AndroidUtilities.cancelRunOnUIThread(closedRefreshRunnable);
            closedRefreshScheduled = false;
        }
    }

    public void cancelLoads() {
        sessionGeneration++;
        loading = false;
        loadingNewer = false;
        olderPagingBoundsDirty = false;
        newerPagingBoundsDirty = false;
        attemptRounds = 0;
        staleEnumerationRetries = 0;
        initialLoadWaiters.clear();
        backfill.cancel();
    }

    private static int getInactiveCacheCap() {
        int performanceClass = SharedConfig.getDevicePerformanceClass();
        if (performanceClass == SharedConfig.PERFORMANCE_CLASS_LOW) {
            return 300;
        }
        if (performanceClass != SharedConfig.PERFORMANCE_CLASS_HIGH) {
            return 600;
        }
        return 1000;
    }

    public void trimForInactiveCache() {
        if (isUiActive() || store.isEmpty()) {
            return;
        }
        store.trim(getInactiveCacheCap());
    }

    public boolean isIncludedChannelPost(long dialogId) {
        if (!DialogObject.isChatDialog(dialogId) || FeedConfig.getInstance(currentAccount).isExcluded(dialogId)) {
            return false;
        }
        return isEligibleChannel(MessagesController.getInstance(currentAccount).getChat(Long.valueOf(-dialogId)));
    }

    public static boolean isEligibleChannel(TLRPC.Chat chat) {
        return chat != null && ChatObject.isChannelAndNotMegaGroup(chat) && !ChatObject.isCommunity(chat) && !ChatObject.isNotInChat(chat);
    }

    public boolean consumeInitialUnreadScroll() {
        boolean pending = initialUnreadScrollPending;
        initialUnreadScrollPending = false;
        return pending;
    }

    public int getUnreadCount() {
        if (ExteraConfig.showFeedUnreadCounter) {
            return unreadTracker.getUnreadCount();
        }
        return 0;
    }

    public void onPostSeen(long dialogId, int messageId) {
        unreadTracker.onPostSeen(dialogId, messageId);
    }

    public void markAllRead() {
        unreadTracker.markAllRead();
    }

    public int findFirstUnreadIndex(ArrayList<MessageObject> messages) {
        return unreadTracker.findFirstUnreadIndex(messages);
    }

    public int countUnreadBelow(ArrayList<MessageObject> messages, int index) {
        return unreadTracker.countUnreadBelow(messages, index);
    }

    public void saveDrawerScrollPosition(long dialogId, int messageId, int offsetTop) {
        if (dialogId == 0 || messageId <= 0) {
            return;
        }
        drawerScrollPosition = new SavedScrollPosition(dialogId, messageId, offsetTop);
    }

    public SavedScrollPosition getDrawerScrollPosition() {
        return drawerScrollPosition;
    }

    public boolean hasNoSyntheticIds() {
        return store.hasNoSyntheticIds();
    }

    public MessageObject getMessage(long dialogId, int messageId) {
        return store.getMessage(dialogId, messageId);
    }

    public int resolveRealMessageId(long dialogId, int messageId) {
        return store.resolveRealMessageId(dialogId, messageId);
    }

    public long resolveRealDialogId(int messageId) {
        return store.resolveRealDialogId(messageId);
    }

    public boolean loadInitial(final int guid, final int loadIndex) {
        ensureCurrentConfig();
        final FeedConfig feedConfig = FeedConfig.getInstance(currentAccount);
        final int configGen = feedConfig.getGeneration();
        final int cacheEpoch = loader.getChannelCacheEpoch();
        if (store.isEmpty()) {
            if (!loadMore(guid, loadIndex)) {
                initialLoadWaiters.add(new int[]{guid, loadIndex});
            }
            return false;
        }
        final ArrayList<MessageObject> visibleMessages = store.getVisibleMessages();
        for (int i = 0; i < visibleMessages.size(); i++) {
            visibleMessages.get(i).viewsReloaded = false;
        }
        if (visibleMessages.isEmpty() && !store.isEndReached()) {
            if (!loadMore(guid, loadIndex)) {
                initialLoadWaiters.add(new int[]{guid, loadIndex});
            }
            return false;
        }
        final int sessionGen = this.sessionGeneration;
        MessagesStorage.getInstance(currentAccount).getStorageQueue().postRunnable(() -> {
            final FeedTimelineLoader.ChannelEnumeration enumeration = loader.enumerateChannels(feedConfig, sessionGen, true);
            AndroidUtilities.runOnUIThread(() -> {
                if (sessionGen != FeedController.this.sessionGeneration) {
                    return;
                }
                if (!isEnumerationCurrent(enumeration, feedConfig, configGen, cacheEpoch)) {
                    postFeedResults(guid, loadIndex, new ArrayList<>(), 0, false, true);
                    postFeedCount(guid);
                } else {
                    applyEnumeration(enumeration);
                    postFeedResults(guid, loadIndex, visibleMessages, 0, false, enumeration.failed);
                    postFeedCount(guid);
                }
            });
        });
        return true;
    }

    private void ensureCurrentConfig() {
        if (configGeneration != FeedConfig.getInstance(currentAccount).getGeneration()) {
            applyConfigChange(aBoolean -> NotificationCenter.getInstance(currentAccount).postNotificationNameOnUIThread(NotificationCenter.feedNeedReload, aBoolean));
        }
    }

    public void markConfigApplied() {
        configGeneration = FeedConfig.getInstance(currentAccount).getGeneration();
    }

    public void applyConfigChange(Utilities.Callback<Boolean> callback) {
        reconcileChannelSet(callback);
    }

    private void reconcileChannelSet(final Utilities.Callback<Boolean> callback) {
        final int sessionGen = sessionGeneration;
        final FeedConfig feedConfig = FeedConfig.getInstance(currentAccount);
        final int configGen = feedConfig.getGeneration();
        final int cacheEpoch = loader.getChannelCacheEpoch();
        if (store.isEmpty()) {
            loadChannels(new ChannelsCallback() {
                @Override
                public void onChannels(ArrayList<TLRPC.Chat> channels, int includedCount, boolean failed, int configGeneration) {
                    if (!failed) {
                        FeedController.this.configGeneration = configGeneration;
                    }
                    if (callback != null) {
                        callback.run(Boolean.FALSE);
                    }
                }
            });
            return;
        }
        final HashSet<Long> loadedDialogIds = store.getLoadedDialogIds();
        final HashSet<Long> hiddenSnapshot = store.getHiddenSnapshot();
        final FeedTimelineLoader.Cursor newestCursor = new FeedTimelineLoader.Cursor();
        final FeedTimelineLoader.Cursor oldestCursor = new FeedTimelineLoader.Cursor();
        newestCursor.set(store.getNewestCursor().date, store.getNewestCursor().uid, store.getNewestCursor().mid);
        oldestCursor.set(store.getOldestCursor().date, store.getOldestCursor().uid, store.getOldestCursor().mid);
        MessagesStorage.getInstance(currentAccount).getStorageQueue().postRunnable(() -> {
            final FeedTimelineLoader.ChannelEnumeration enumeration = loader.enumerateChannels(feedConfig, sessionGen, true);
            if (enumeration.failed) {
                AndroidUtilities.runOnUIThread(() -> finishStaleReconcile(sessionGen, enumeration, feedConfig, configGen, cacheEpoch, callback));
                return;
            }
            ArrayList<Long> missingDialogs = new ArrayList<>();
            ArrayList<FeedTimelineLoader.ChannelSnapshot> included = enumeration.included;
            for (int i = 0; i < included.size(); i++) {
                FeedTimelineLoader.ChannelSnapshot snapshot = included.get(i);
                if (!loadedDialogIds.contains(snapshot.dialogId) || hiddenSnapshot.contains(snapshot.dialogId)) {
                    missingDialogs.add(snapshot.dialogId);
                }
            }
            final FeedTimelineLoader.WindowPage window = missingDialogs.isEmpty() ? null : loader.loadChannelWindow(missingDialogs, newestCursor, oldestCursor);
            if (window != null && window.failed) {
                AndroidUtilities.runOnUIThread(() -> finishStaleReconcile(sessionGen, enumeration, feedConfig, configGen, cacheEpoch, callback));
                return;
            }
            final ArrayList<MessageObject> windowMessages = window != null ? createMessageObjects(window.messages, window.users, window.chats) : null;
            final boolean anyMissing = !missingDialogs.isEmpty();
            AndroidUtilities.runOnUIThread(() -> {
                if (sessionGen != FeedController.this.sessionGeneration) {
                    return;
                }
                if (!isEnumerationCurrent(enumeration, feedConfig, configGen, cacheEpoch) && canRetryStaleEnumeration()) {
                    reconcileChannelSet(callback);
                    return;
                }
                applyEnumeration(enumeration);
                FeedController.this.configGeneration = enumeration.configGeneration;
                HashSet<Long> includedDialogs = new HashSet<>();
                for (int i = 0; i < enumeration.included.size(); i++) {
                    includedDialogs.add(enumeration.included.get(i).dialogId);
                }
                store.applyIncludedDialogs(includedDialogs);
                boolean truncated = window != null && window.truncated;
                if (window != null && !truncated && !missingDialogs.isEmpty()) {
                    MessagesController messagesController = MessagesController.getInstance(currentAccount);
                    messagesController.putUsers(window.users, true);
                    messagesController.putChats(window.chats, true);
                    store.mergeRows(windowMessages);
                }
                if (anyMissing) {
                    store.setEndReached(false);
                    if (loading) {
                        olderPagingBoundsDirty = true;
                    }
                    if (loadingNewer) {
                        newerPagingBoundsDirty = true;
                    }
                }
                if (callback != null) {
                    callback.run(truncated);
                }
            });
        });
    }

    private void finishStaleReconcile(int sessionGen, FeedTimelineLoader.ChannelEnumeration enumeration, FeedConfig feedConfig, int configGen, int cacheEpoch, Utilities.Callback<Boolean> callback) {
        if (sessionGen != sessionGeneration) {
            return;
        }
        if (!isEnumerationCurrent(enumeration, feedConfig, configGen, cacheEpoch) && canRetryStaleEnumeration()) {
            reconcileChannelSet(callback);
        } else if (callback != null) {
            callback.run(Boolean.FALSE);
        }
    }

    public void refreshReadState(final Runnable callback) {
        final int sessionGen = sessionGeneration;
        final FeedConfig feedConfig = FeedConfig.getInstance(currentAccount);
        final int configGen = feedConfig.getGeneration();
        final int cacheEpoch = loader.getChannelCacheEpoch();
        MessagesStorage.getInstance(currentAccount).getStorageQueue().postRunnable(() -> {
            final FeedTimelineLoader.ChannelEnumeration enumeration = loader.enumerateChannels(feedConfig, sessionGen, true);
            AndroidUtilities.runOnUIThread(() -> {
                if (sessionGen != FeedController.this.sessionGeneration) {
                    return;
                }
                if (isEnumerationCurrent(enumeration, feedConfig, configGen, cacheEpoch)) {
                    applyEnumeration(enumeration);
                }
                if (callback != null) {
                    callback.run();
                }
            });
        });
    }

    public boolean loadMore(int guid, int loadIndex) {
        ensureCurrentConfig();
        if (loading || (store.isEndReached() && !store.getOldestCursor().isEmpty())) {
            return false;
        }
        loading = true;
        heldGuid = guid;
        heldLoadIndex = loadIndex;
        attemptRounds = 0;
        runAttempt();
        return true;
    }

    private void runAttempt() {
        final int guid = heldGuid;
        final int loadIndex = heldLoadIndex;
        final int sessionGen = sessionGeneration;
        final FeedConfig feedConfig = FeedConfig.getInstance(currentAccount);
        final int configGen = feedConfig.getGeneration();
        final int cacheEpoch = loader.getChannelCacheEpoch();
        final boolean initialLoad = store.getOldestCursor().isEmpty();
        final FeedTimelineLoader.Cursor cursor = new FeedTimelineLoader.Cursor();
        cursor.set(store.getOldestCursor().date, store.getOldestCursor().uid, store.getOldestCursor().mid);
        final HashSet<Long> exhaustedSnapshot = backfill.getExhaustedSnapshot();
        MessagesStorage.getInstance(currentAccount).getStorageQueue().postRunnable(() -> {
            final FeedTimelineLoader.ChannelEnumeration enumeration = loader.enumerateChannels(feedConfig, sessionGen, false);
            if (enumeration.failed) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (sessionGen != FeedController.this.sessionGeneration) {
                        return;
                    }
                    if (!isEnumerationCurrent(enumeration, feedConfig, configGen, cacheEpoch) && canRetryStaleEnumeration()) {
                        attemptRounds = 0;
                        runAttempt();
                    } else {
                        loading = false;
                        postFeedResults(guid, loadIndex, new ArrayList<>(), 2, false, true);
                        postFeedCount(guid);
                        flushInitialLoadWaiters(true);
                    }
                });
                return;
            }
            if (enumeration.included.isEmpty()) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (sessionGen != FeedController.this.sessionGeneration) {
                        return;
                    }
                    if (!isEnumerationCurrent(enumeration, feedConfig, configGen, cacheEpoch)) {
                        if (canRetryStaleEnumeration()) {
                            olderPagingBoundsDirty = false;
                            attemptRounds = 0;
                            runAttempt();
                            return;
                        }
                        loading = false;
                        postFeedResults(guid, loadIndex, new ArrayList<>(), 2, false, true);
                        postFeedCount(guid);
                        flushInitialLoadWaiters(true);
                        return;
                    }
                    applyEnumeration(enumeration);
                    olderPagingBoundsDirty = false;
                    unreadTracker.clear();
                    loading = false;
                    store.setEndReached(true);
                    postFeedResults(guid, loadIndex, new ArrayList<>(), 2);
                    postFeedCount(guid);
                    flushInitialLoadWaiters();
                });
                return;
            }
            final FeedTimelineLoader.OlderPage olderPage = loader.loadOlderPage(enumeration.included, cursor, exhaustedSnapshot);
            final ArrayList<MessageObject> olderMessages = createMessageObjects(olderPage.messages, olderPage.users, olderPage.chats);
            AndroidUtilities.runOnUIThread(() -> {
                if (sessionGen != FeedController.this.sessionGeneration) {
                    return;
                }
                if (!isEnumerationCurrent(enumeration, feedConfig, configGen, cacheEpoch) && canRetryStaleEnumeration()) {
                    olderPagingBoundsDirty = false;
                    attemptRounds = 0;
                    runAttempt();
                    return;
                }
                if (olderPagingBoundsDirty) {
                    olderPagingBoundsDirty = false;
                    attemptRounds = 0;
                    runAttempt();
                    return;
                }
                applyEnumeration(enumeration);
                MessagesController messagesController = MessagesController.getInstance(currentAccount);
                pruneStaleExclusions(FeedConfig.getInstance(currentAccount), messagesController);
                if (olderPage.failed) {
                    loading = false;
                    postFeedResults(guid, loadIndex, new ArrayList<>(), 2, false, true);
                    postFeedCount(guid);
                    flushInitialLoadWaiters(true);
                    return;
                }
                FeedTimelineLoader.Cursor storeOldestCursor = store.getOldestCursor();
                storeOldestCursor.set(olderPage.last.date, olderPage.last.uid, olderPage.last.mid);
                if (initialLoad && !olderPage.first.isEmpty()) {
                    FeedTimelineLoader.Cursor storeNewestCursor = store.getNewestCursor();
                    storeNewestCursor.set(olderPage.first.date, olderPage.first.uid, olderPage.first.mid);
                }
                messagesController.putUsers(olderPage.users, true);
                messagesController.putChats(olderPage.chats, true);
                ArrayList<MessageObject> appended = store.appendMessages(olderMessages, false);
                if (appended.isEmpty() && olderPage.lastChunkRowCount == 30) {
                    runAttempt();
                    return;
                }
                boolean endReached = !olderPage.hasIncomplete && olderPage.lastChunkRowCount < 30;
                if (!appended.isEmpty() || endReached || olderPage.backfillCandidates.isEmpty() || attemptRounds >= 3) {
                    loading = false;
                    store.setEndReached(endReached);
                    postFeedResults(guid, loadIndex, appended, 2);
                    postFeedCount(guid);
                    flushInitialLoadWaiters();
                    return;
                }
                attemptRounds++;
                backfill.startRound(olderPage.backfillCandidates);
            });
        });
    }

    private void postFeedResults(int guid, int loadIndex, ArrayList<MessageObject> messages, int loadType) {
        postFeedResults(guid, loadIndex, messages, loadType, false, false);
    }

    private void postFeedResults(int guid, int loadIndex, ArrayList<MessageObject> messages, int loadType, boolean hasMore, boolean error) {
        NotificationCenter.getInstance(currentAccount).postNotificationNameOnUIThread(NotificationCenter.messagesDidLoad, 0L, Integer.valueOf(messages.size()), messages, Boolean.FALSE, 0, 0, 0, 0, Integer.valueOf(loadType), Boolean.TRUE, Integer.valueOf(guid), Integer.valueOf(loadIndex), 0, 0, 7, Boolean.valueOf(hasMore), Boolean.valueOf(error));
    }

    private void postFeedCount(int guid) {
        NotificationCenter.getInstance(currentAccount).postNotificationNameOnUIThread(NotificationCenter.hashtagSearchUpdated, Integer.valueOf(guid), Integer.valueOf(store.getCount()), Boolean.valueOf(store.isEndReached()), 0, 0, 0);
    }

    private boolean isEnumerationCurrent(FeedTimelineLoader.ChannelEnumeration enumeration, FeedConfig feedConfig, int configGen, int cacheEpoch) {
        boolean current = loader.isEnumerationCurrent(enumeration) && enumeration.configGeneration == feedConfig.getGeneration() && enumeration.configGeneration == configGen && enumeration.cacheEpoch == cacheEpoch;
        if (current) {
            staleEnumerationRetries = 0;
        }
        return current;
    }

    private boolean canRetryStaleEnumeration() {
        int retries = staleEnumerationRetries;
        if (retries >= 3) {
            staleEnumerationRetries = 0;
            return false;
        }
        staleEnumerationRetries = retries + 1;
        return true;
    }

    private void applyEnumeration(FeedTimelineLoader.ChannelEnumeration enumeration) {
        if (enumeration.failed) {
            return;
        }
        hasChannels = enumeration.hasChannels;
        hasIncludedChannels = !enumeration.included.isEmpty();
        cachedIncludedChannelCount = enumeration.included.size();
        ArrayList<FeedTimelineLoader.ChannelSnapshot> included = enumeration.included;
        for (int i = 0; i < included.size(); i++) {
            FeedTimelineLoader.ChannelSnapshot snapshot = included.get(i);
            int readInboxMax = snapshot.readInboxMax;
            if (readInboxMax <= 0 && snapshot.unreadCount <= 0) {
                readInboxMax = snapshot.topMessage;
            }
            unreadTracker.applyReadInboxMax(snapshot.dialogId, readInboxMax);
        }
    }

    private void flushInitialLoadWaiters() {
        flushInitialLoadWaiters(false);
    }

    private void flushInitialLoadWaiters(boolean error) {
        if (initialLoadWaiters.isEmpty()) {
            return;
        }
        ArrayList<int[]> waiters = new ArrayList<>(initialLoadWaiters);
        initialLoadWaiters.clear();
        ArrayList<MessageObject> visibleMessages = store.getVisibleMessages();
        for (int i = 0; i < waiters.size(); i++) {
            int[] waiter = waiters.get(i);
            postFeedResults(waiter[0], waiter[1], visibleMessages, 0, false, error);
            postFeedCount(waiter[0]);
        }
    }

    private void onBackfillRoundFinished() {
        if (loading) {
            runAttempt();
        }
    }

    public boolean loadNewer(int guid, int loadIndex) {
        ensureCurrentConfig();
        if (loadingNewer || store.getNewestCursor().isEmpty()) {
            return false;
        }
        loadingNewer = true;
        runLoadNewer(guid, loadIndex);
        return true;
    }

    private void runLoadNewer(final int guid, final int loadIndex) {
        final int sessionGen = sessionGeneration;
        final FeedConfig feedConfig = FeedConfig.getInstance(currentAccount);
        final int configGen = feedConfig.getGeneration();
        final int cacheEpoch = loader.getChannelCacheEpoch();
        final FeedTimelineLoader.Cursor cursor = new FeedTimelineLoader.Cursor();
        cursor.set(store.getNewestCursor().date, store.getNewestCursor().uid, store.getNewestCursor().mid);
        MessagesStorage.getInstance(currentAccount).getStorageQueue().postRunnable(() -> {
            final FeedTimelineLoader.ChannelEnumeration enumeration = loader.enumerateChannels(feedConfig, sessionGen, false);
            if (enumeration.failed) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (sessionGen != FeedController.this.sessionGeneration) {
                        return;
                    }
                    if (!isEnumerationCurrent(enumeration, feedConfig, configGen, cacheEpoch) && canRetryStaleEnumeration()) {
                        runLoadNewer(guid, loadIndex);
                    } else {
                        loadingNewer = false;
                        postNewerMessagesLoaded(guid, loadIndex, null, false, true);
                    }
                });
                return;
            }
            if (enumeration.included.isEmpty()) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (sessionGen != FeedController.this.sessionGeneration) {
                        return;
                    }
                    if (!isEnumerationCurrent(enumeration, feedConfig, configGen, cacheEpoch) && canRetryStaleEnumeration()) {
                        newerPagingBoundsDirty = false;
                        runLoadNewer(guid, loadIndex);
                    } else {
                        newerPagingBoundsDirty = false;
                        loadingNewer = false;
                        postNewerMessagesLoaded(guid, loadIndex, null, false);
                        postFeedCount(guid);
                    }
                });
                return;
            }
            final FeedTimelineLoader.NewerPage newerPage = loader.loadNewerPage(enumeration.included, cursor);
            final ArrayList<MessageObject> newerMessages = createMessageObjects(newerPage.messages, newerPage.users, newerPage.chats);
            AndroidUtilities.runOnUIThread(() -> {
                if (sessionGen != FeedController.this.sessionGeneration) {
                    return;
                }
                if (!isEnumerationCurrent(enumeration, feedConfig, configGen, cacheEpoch) && canRetryStaleEnumeration()) {
                    newerPagingBoundsDirty = false;
                    runLoadNewer(guid, loadIndex);
                    return;
                }
                if (newerPagingBoundsDirty) {
                    newerPagingBoundsDirty = false;
                    if (store.getNewestCursor().isEmpty()) {
                        loadingNewer = false;
                        postNewerMessagesLoaded(guid, loadIndex, null, false);
                        postFeedCount(guid);
                        return;
                    }
                    runLoadNewer(guid, loadIndex);
                    return;
                }
                loadingNewer = false;
                applyEnumeration(enumeration);
                if (newerPage.failed) {
                    postNewerMessagesLoaded(guid, loadIndex, null, false, true);
                    return;
                }
                FeedTimelineLoader.Cursor storeNewestCursor = store.getNewestCursor();
                storeNewestCursor.set(newerPage.first.date, newerPage.first.uid, newerPage.first.mid);
                if (newerPage.messages.isEmpty()) {
                    postNewerMessagesLoaded(guid, loadIndex, null, newerPage.hasMore);
                    if (!newerPage.hasMore) {
                        postFeedCount(guid);
                    }
                    return;
                }
                MessagesController messagesController = MessagesController.getInstance(currentAccount);
                messagesController.putUsers(newerPage.users, true);
                messagesController.putChats(newerPage.chats, true);
                postNewerMessagesLoaded(guid, loadIndex, store.appendMessages(newerMessages, true), newerPage.hasMore);
                if (!newerPage.hasMore) {
                    postFeedCount(guid);
                }
                trimForInactiveCache();
            });
        });
    }

    private void postNewerMessagesLoaded(int guid, int loadIndex, ArrayList<MessageObject> messages, boolean hasMore) {
        postNewerMessagesLoaded(guid, loadIndex, messages, hasMore, false);
    }

    private void postNewerMessagesLoaded(int guid, int loadIndex, ArrayList<MessageObject> messages, boolean hasMore, boolean error) {
        int loadType = 0;
        ArrayList<MessageObject> ordered = new ArrayList<>();
        if (messages != null && !messages.isEmpty()) {
            ordered.addAll(messages);
            Collections.reverse(ordered);
            loadType = 1;
        }
        postFeedResults(guid, loadIndex, ordered, loadType, hasMore, error);
    }

    private ArrayList<MessageObject> createMessageObjects(ArrayList<TLRPC.Message> source, ArrayList<TLRPC.User> users, ArrayList<TLRPC.Chat> chats) {
        HashMap<Long, TLRPC.User> usersById = new HashMap<>();
        HashMap<Long, TLRPC.Chat> chatsById = new HashMap<>();
        for (int i = 0; i < users.size(); i++) {
            TLRPC.User user = users.get(i);
            usersById.put(user.id, user);
        }
        for (int i = 0; i < chats.size(); i++) {
            TLRPC.Chat chat = chats.get(i);
            chatsById.put(chat.id, chat);
        }
        ArrayList<MessageObject> result = new ArrayList<>(source.size());
        for (int i = 0; i < source.size(); i++) {
            result.add(new MessageObject(currentAccount, source.get(i), null, usersById, chatsById, null, null, true, true, 0L, false, false, false, 4));
        }
        return result;
    }

    public void loadChannels(ChannelsCallback callback) {
        loadChannels(false, callback);
    }

    public void loadChannels(final boolean forceRebuild, final ChannelsCallback callback) {
        final FeedConfig feedConfig = FeedConfig.getInstance(currentAccount);
        final int sessionGen = sessionGeneration;
        final int configGen = feedConfig.getGeneration();
        final int cacheEpoch = loader.getChannelCacheEpoch();
        MessagesStorage.getInstance(currentAccount).getStorageQueue().postRunnable(() -> {
            final FeedTimelineLoader.ChannelEnumeration enumeration = loader.enumerateChannels(feedConfig, sessionGen, forceRebuild);
            AndroidUtilities.runOnUIThread(() -> {
                if (sessionGen != FeedController.this.sessionGeneration) {
                    return;
                }
                if (!isEnumerationCurrent(enumeration, feedConfig, configGen, cacheEpoch)) {
                    if (callback != null) {
                        callback.onChannels(new ArrayList<>(), 0, true, enumeration.configGeneration);
                    }
                } else {
                    applyEnumeration(enumeration);
                    if (!enumeration.failed) {
                        MessagesController.getInstance(currentAccount).putChats(enumeration.channels, true);
                    }
                    if (callback != null) {
                        callback.onChannels(enumeration.channels, enumeration.included.size(), enumeration.failed, enumeration.configGeneration);
                    }
                }
            });
        });
    }

    private void pruneStaleExclusions(FeedConfig feedConfig, MessagesController messagesController) {
        HashSet<Long> stale = null;
        for (Long dialogId : feedConfig.getExcludedSnapshot()) {
            TLRPC.Chat chat = messagesController.getChat(Long.valueOf(-dialogId));
            if (chat != null && !isEligibleChannel(chat)) {
                if (stale == null) {
                    stale = new HashSet<>();
                }
                stale.add(dialogId);
            }
        }
        if (stale != null) {
            feedConfig.removeExcluded(stale);
            markConfigApplied();
        }
    }

    public void replaceMessage(MessageObject oldMessage, MessageObject newMessage) {
        store.replaceMessage(oldMessage, newMessage);
    }

    public ArrayList<Integer> deleteMessages(long dialogId, ArrayList<Integer> messageIds) {
        boolean[] changed = new boolean[1];
        ArrayList<Integer> removedIds = store.deleteMessages(dialogId, messageIds, changed);
        if (changed[0]) {
            onFeedRowsRemoved();
        }
        return removedIds;
    }

    public ArrayList<Integer> deleteHistory(long dialogId, int maxId) {
        boolean[] changed = new boolean[1];
        ArrayList<Integer> removedIds = store.deleteHistory(dialogId, maxId, changed);
        if (changed[0]) {
            onFeedRowsRemoved();
        }
        return removedIds;
    }

    private void onFeedRowsRemoved() {
        if (loading) {
            olderPagingBoundsDirty = true;
        }
        if (loadingNewer) {
            newerPagingBoundsDirty = true;
        }
    }

    public ArrayList<MessageObject> updateViews(LongSparseArray<SparseIntArray> views, LongSparseArray<SparseIntArray> forwards, LongSparseArray<SparseArray<TLRPC.MessageReplies>> replies, boolean incremental) {
        ArrayList<MessageObject> updated = new ArrayList<>();
        updateCounters(views, true, updated);
        updateCounters(forwards, false, updated);
        updateReplies(replies, incremental, updated);
        return updated;
    }

    private void updateCounters(LongSparseArray<SparseIntArray> counters, boolean views, ArrayList<MessageObject> updated) {
        if (counters == null) {
            return;
        }
        for (int i = 0; i < counters.size(); i++) {
            long dialogId = counters.keyAt(i);
            SparseIntArray values = counters.valueAt(i);
            for (int j = 0; j < values.size(); j++) {
                MessageObject message = getMessage(dialogId, values.keyAt(j));
                if (message != null) {
                    int value = values.valueAt(j);
                    TLRPC.Message owner = message.messageOwner;
                    if (views) {
                        if (value > owner.views) {
                            owner.views = value;
                            addUpdated(updated, message);
                        }
                    } else if (value > owner.forwards) {
                        owner.forwards = value;
                        addUpdated(updated, message);
                    }
                }
            }
        }
    }

    private void updateReplies(LongSparseArray<SparseArray<TLRPC.MessageReplies>> replies, boolean incremental, ArrayList<MessageObject> updated) {
        if (replies == null) {
            return;
        }
        for (int i = 0; i < replies.size(); i++) {
            long dialogId = replies.keyAt(i);
            SparseArray<TLRPC.MessageReplies> values = replies.valueAt(i);
            for (int j = 0; j < values.size(); j++) {
                MessageObject message = getMessage(dialogId, values.keyAt(j));
                TLRPC.MessageReplies newReplies = values.valueAt(j);
                if (message != null && newReplies != null) {
                    TLRPC.Message owner = message.messageOwner;
                    if (incremental) {
                        if (owner.replies == null) {
                            owner.replies = new TLRPC.TL_messageReplies();
                        }
                        owner.replies.replies += newReplies.replies;
                        for (int k = 0; k < newReplies.recent_repliers.size(); k++) {
                            owner.replies.recent_repliers.remove(newReplies.recent_repliers.get(k));
                        }
                        owner.replies.recent_repliers.addAll(0, newReplies.recent_repliers);
                        while (owner.replies.recent_repliers.size() > 3) {
                            owner.replies.recent_repliers.remove(0);
                        }
                    } else {
                        TLRPC.MessageReplies currentReplies = owner.replies;
                        if (currentReplies == null || newReplies.replies_pts > currentReplies.replies_pts || newReplies.read_max_id > currentReplies.read_max_id || newReplies.max_id > currentReplies.max_id) {
                            owner.replies = newReplies;
                        }
                    }
                    message.animateComments = true;
                    addUpdated(updated, message);
                }
            }
        }
    }

    private static void addUpdated(ArrayList<MessageObject> updated, MessageObject message) {
        if (updated.contains(message)) {
            return;
        }
        updated.add(message);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.messagesDidLoad) {
            backfill.onMessagesDidLoad(args);
        } else if (id == NotificationCenter.loadingMessagesFailed) {
            backfill.onLoadingMessagesFailed(args);
        } else if (id == NotificationCenter.messagesDeleted) {
            if (isUiActive() || (Boolean) args[2]) {
                return;
            }
            long dialogId = (Long) args[1];
            if (dialogId == 0) {
                return;
            }
            if (dialogId > 0) {
                dialogId = -dialogId;
            }
            deleteMessages(dialogId, (ArrayList<Integer>) args[0]);
        } else if (id == NotificationCenter.historyCleared) {
            if (isUiActive()) {
                return;
            }
            long dialogId = (Long) args[0];
            if (DialogObject.isChatDialog(dialogId)) {
                deleteHistory(dialogId, (Integer) args[1]);
            }
        } else if (id == NotificationCenter.didReceiveNewMessages) {
            if (isUiActive() || (Boolean) args[2] || store.isEmpty() || store.getNewestCursor().isEmpty() || !isIncludedChannelPost((Long) args[0])) {
                return;
            }
            scheduleClosedRefresh();
        }
    }

    private void scheduleClosedRefresh() {
        if (closedRefreshScheduled) {
            return;
        }
        closedRefreshScheduled = true;
        AndroidUtilities.runOnUIThread(closedRefreshRunnable, 1000L);
    }

    private void runClosedRefresh() {
        closedRefreshScheduled = false;
        if (isUiActive() || loadingNewer || store.isEmpty() || store.getNewestCursor().isEmpty()) {
            return;
        }
        loadNewer(closedRefreshGuid, 0);
    }
}
