package com.radolyn.ayugram;

import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;

import com.radolyn.ayugram.controllers.AyuAttachments;
import com.radolyn.ayugram.controllers.AyuMapper;
import com.radolyn.ayugram.utils.AyuMessageUtils;
import com.radolyn.ayugram.utils.seq.AyuSequentialUtils;
import com.radolyn.ayugram.utils.seq.DummyFileDownloadWaiter;
import com.radolyn.ayugram.utils.seq.DummyFileUploadWaiter;
import com.radolyn.ayugram.utils.seq.DummyMessageWaiter;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessageSuggestionParams;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

public class AyuForward implements NotificationCenter.NotificationCenterDelegate {

    private static final ConcurrentHashMap<Long, AyuForward> activeForwards = new ConcurrentHashMap<>();

    private static class GroupedMediaItem {
        final MessageObject messageObject;
        final String caption;

        GroupedMediaItem(MessageObject messageObject, String caption) {
            this.messageObject = messageObject;
            this.caption = caption;
        }
    }

    public interface CompletionCallback {
        void onComplete(boolean shouldContinue);
    }

    private static final long MAX_MEDIA_WAIT_MS = 300_000L;
    private static final long MAX_MEDIA_STALL_TIMEOUT_MS = 30_000L;
    private static final long STALL_CHECK_INTERVAL_MS = 5_000L;
    private static final long SEND_STEP_DELAY_MS = 16L;
    private static final long SEND_PHASE_FINISH_DELAY_MS = 180L;
    private static final long SEND_WAIT_POLL_INTERVAL_MS = 25L;
    private static final long MAX_SEND_WAIT_MS = 600_000L;
    private static final int STATUS_REFRESH_MASK_BASE = 1 << 30;
    private static final int MAX_GROUP_BATCH_SIZE = 10;
    private static final int STATUS_IDLE = 0;
    private static final int STATUS_LOADING = 1;
    private static final int STATUS_FORWARDING = 2;
    private static final int STATUS_STOPPING = 3;

    private final ChatActivity parentFragment;
    private final int currentAccount;
    private final MessageObject replyToTopMessage;
    private final int chatMode;
    private final String quickReplyShortcut;
    private final int quickReplyShortcutId;
    private final long monoForumPeerId;
    private final MessageSuggestionParams suggestionParams;
    private final DummyFileDownloadWaiter fileDownloadWaiter;
    private final DummyFileUploadWaiter fileUploadWaiter;
    private final DummyMessageWaiter messageWaiter;
    private final AyuAttachments attachments;
    private final AyuMapper mapper;
    private long targetDialogId;
    private long activeTaskId;
    private int currentStatus = STATUS_IDLE;
    private int totalMessages;
    private int sentMessages;
    private int skippedMessages;
    private int totalMediaToPrepare;
    private int pendingMedia;
    private String currentStatusDetail;
    private String lastFailureReason;
    private boolean stopRequested;
    private long mediaWaitStartTime;
    private long mediaLastProgressTime;
    private long mediaLastObservedBytes;
    private int mediaLastMissingCount;
    private boolean disposed;
    private boolean detached;
    private boolean notificationSubscribed;
    private ArrayList<MessageObject> pendingMessages;
    private long pendingTargetDialogId;
    private boolean pendingShowUndo;
    private boolean pendingHideCaption;
    private boolean pendingNotify;
    private int pendingScheduleDate;
    private long pendingPayStars;
    private long pendingTaskId;
    private CompletionCallback pendingOnComplete;
    private Runnable stallCheckRunnable;
    private int currentChunkIndex;
    private int totalChunks = 1;
    private int statusUpdateVersion;
    private Runnable sendWaitRunnable;
    private ArrayList<AyuSequentialUtils.SendStep> activeSendSteps;
    private int activeSendStepIndex;
    private CompletionCallback activeSendOnComplete;
    
    public AyuForward(ChatActivity fragment, int account) {
        this(
                fragment,
                account,
                fragment != null ? fragment.getThreadMessage() : null,
                fragment != null ? fragment.getChatMode() : 0,
                fragment != null ? fragment.quickReplyShortcut : null,
                fragment != null ? fragment.getQuickReplyId() : 0,
                fragment != null ? fragment.getSendMonoForumPeerId() : 0,
                fragment != null ? fragment.getSendMessageSuggestionParams() : null
        );
    }

    public AyuForward(int account, MessageObject replyToTopMessage, int chatMode, String quickReplyShortcut, int quickReplyShortcutId, long monoForumPeerId, MessageSuggestionParams suggestionParams) {
        this(null, account, replyToTopMessage, chatMode, quickReplyShortcut, quickReplyShortcutId, monoForumPeerId, suggestionParams);
    }

    protected AyuForward(ChatActivity fragment, int account, MessageObject replyToTopMessage, int chatMode, String quickReplyShortcut, int quickReplyShortcutId, long monoForumPeerId, MessageSuggestionParams suggestionParams) {
        this.parentFragment = fragment;
        this.currentAccount = account;
        this.replyToTopMessage = replyToTopMessage;
        this.chatMode = chatMode;
        this.quickReplyShortcut = quickReplyShortcut;
        this.quickReplyShortcutId = quickReplyShortcutId;
        this.monoForumPeerId = monoForumPeerId;
        this.suggestionParams = suggestionParams;
        this.fileDownloadWaiter = new DummyFileDownloadWaiter(account);
        this.fileUploadWaiter = new DummyFileUploadWaiter(account);
        this.messageWaiter = new DummyMessageWaiter(account);
        this.attachments = new AyuAttachments(account, fileDownloadWaiter);
        this.mapper = new AyuMapper(account);
    }

    public static boolean isChatNoForwards(MessageObject messageObject) {
        return AyuMessageUtils.isChatNoForwards(messageObject);
    }

    public static boolean isPeerNoForwards(MessageObject messageObject) {
        return AyuMessageUtils.isPeerNoForwards(messageObject);
    }

    public static boolean canForwardAyuDeletedMessage(MessageObject messageObject) {
        return AyuMessageUtils.canForwardAyuDeletedMessage(messageObject);
    }

    public static boolean isUnforwardable(MessageObject messageObject) {
        return AyuMessageUtils.isUnforwardable(messageObject);
    }

    public static boolean isAyuForwardNeeded(MessageObject messageObject) {
        return AyuMessageUtils.isAyuForwardNeeded(messageObject);
    }

    public static boolean isForwardingToDialog(long dialogId) {
        AyuForward ff = activeForwards.get(dialogId);
        if (ff == null) return false;
        return ff.isForwarding();
    }

    public static String getStatusForDialog(long dialogId) {
        AyuForward ff = activeForwards.get(dialogId);
        if (ff == null) return null;
        return ff.getForwardingStatus();
    }

    public static boolean stopForDialog(long dialogId) {
        AyuForward ff = activeForwards.get(dialogId);
        if (ff == null) return false;
        return ff.stopCurrentRun();
    }

    public static String consumeFailureReasonForDialog(long dialogId) {
        AyuForward ff = activeForwards.get(dialogId);
        if (ff == null) return null;
        return ff.consumeLastFailureReason();
    }

    private String prependPseudoReply(MessageObject mo, String text, ArrayList<TLRPC.MessageEntity> entities) {
        return AyuMessageUtils.prependPseudoReply(text, null, false, mo, entities, currentAccount, targetDialogId).text;
    }

    private String prependPseudoReplyCaption(MessageObject mo, String caption, ArrayList<TLRPC.MessageEntity> entities) {
        return AyuMessageUtils.prependPseudoReply(null, caption, mo != null && mo.isPhoto(), mo, entities, currentAccount, targetDialogId).caption;
    }

    private void clearRunState() {
        clearActiveSendQueue();
        unsubscribeFromNotifications();
        if (targetDialogId != 0) {
            activeForwards.remove(targetDialogId);
        }
        activeTaskId = 0L;
        currentStatus = STATUS_IDLE;
        totalMessages = 0;
        sentMessages = 0;
        skippedMessages = 0;
        totalMediaToPrepare = 0;
        pendingMedia = 0;
        currentStatusDetail = null;
        stopRequested = false;
        mediaWaitStartTime = 0L;
        mediaLastProgressTime = 0L;
        mediaLastObservedBytes = 0L;
        mediaLastMissingCount = 0;
        fileDownloadWaiter.clear();
        currentChunkIndex = 0;
        totalChunks = 1;
        statusUpdateVersion = 0;
    }

    private void clearPendingRunState() {
        pendingMessages = null;
        pendingTargetDialogId = 0;
        pendingShowUndo = false;
        pendingHideCaption = false;
        pendingNotify = false;
        pendingScheduleDate = 0;
        pendingPayStars = 0;
        pendingTaskId = 0;
        pendingOnComplete = null;
    }

    private void subscribeToNotifications() {
        if (notificationSubscribed) return;
        notificationSubscribed = true;
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.fileLoaded);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.fileLoadFailed);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.httpFileDidLoad);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.httpFileDidFailedLoad);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messageReceivedByAck);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messageReceivedByServer);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messageSendError);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.fileUploaded);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.fileUploadFailed);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.filePreparingFailed);
    }

    private void unsubscribeFromNotifications() {
        if (!notificationSubscribed) return;
        notificationSubscribed = false;
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.fileLoaded);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.fileLoadFailed);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.httpFileDidLoad);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.httpFileDidFailedLoad);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messageReceivedByAck);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messageReceivedByServer);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messageSendError);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.fileUploaded);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.fileUploadFailed);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.filePreparingFailed);
    }

    private void scheduleStallCheck() {
        cancelStallCheck();
        stallCheckRunnable = () -> {
            if (disposed || !isTaskActive(pendingTaskId)) return;
            AyuAttachments.MediaPreparationResult mediaState = attachments.prepareMedia(pendingMessages);
            if (shouldAbortMediaWait(mediaState, pendingTaskId, pendingOnComplete)) {
                unsubscribeFromNotifications();
                clearPendingRunState();
                return;
            }
            scheduleStallCheck();
        };
        AndroidUtilities.runOnUIThread(stallCheckRunnable, STALL_CHECK_INTERVAL_MS);
    }

    private void cancelStallCheck() {
        if (stallCheckRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(stallCheckRunnable);
            stallCheckRunnable = null;
        }
    }

    private void didReceivedNotificationLegacy(int id, int account, Object... args) {
        if (disposed) {
            return;
        }
        if (id == NotificationCenter.fileLoaded || id == NotificationCenter.httpFileDidLoad) {
            if (isTaskActive(pendingTaskId) && pendingMessages != null) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (disposed || !isTaskActive(pendingTaskId) || pendingMessages == null) return;
                    resumeMediaWait();
                });
            }
        } else if (id == NotificationCenter.fileLoadFailed || id == NotificationCenter.httpFileDidFailedLoad) {
            if (isTaskActive(pendingTaskId) && pendingMessages != null) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (disposed || !isTaskActive(pendingTaskId) || pendingMessages == null) return;
                    resumeMediaWait();
                });
            }
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (disposed) {
            return;
        }
        if (id == NotificationCenter.fileLoaded || id == NotificationCenter.httpFileDidLoad) {
            if (isTaskActive(pendingTaskId) && pendingMessages != null) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (disposed || !isTaskActive(pendingTaskId) || pendingMessages == null) return;
                    resumeMediaWait();
                });
            }
        } else if (id == NotificationCenter.fileLoadFailed || id == NotificationCenter.httpFileDidFailedLoad) {
            if (isTaskActive(pendingTaskId) && pendingMessages != null) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (disposed || !isTaskActive(pendingTaskId) || pendingMessages == null) return;
                    resumeMediaWait();
                });
            }
        } else if (id == NotificationCenter.messageReceivedByAck) {
            handleSendAcknowledged(args);
        } else if (id == NotificationCenter.messageReceivedByServer) {
            handleSendConfirmedByServer(args);
        } else if (id == NotificationCenter.messageSendError) {
            handleSendError(args);
        } else if (id == NotificationCenter.fileUploaded) {
            handleUploadStateChanged(args, false);
        } else if (id == NotificationCenter.fileUploadFailed || id == NotificationCenter.filePreparingFailed) {
            handleUploadStateChanged(args, true);
        }
    }

    private void resumeMediaWait() {
        if (disposed || pendingMessages == null || !isTaskActive(pendingTaskId)) return;

        AyuAttachments.MediaPreparationResult mediaState = attachments.prepareMedia(pendingMessages);
        if (mediaState.hasUndownloadedAyuDeletedMedia) {
            unsubscribeFromNotifications();
            cancelStallCheck();
            failRun(pendingTaskId, LocaleController.getString(R.string.PleaseDownload), pendingOnComplete);
            clearPendingRunState();
            return;
        }

        int missingMediaCount = mediaState.missingMediaCount;
        updateLoadingState(missingMediaCount);

        if (missingMediaCount > 0) {
            if (shouldAbortMediaWait(mediaState, pendingTaskId, pendingOnComplete)) {
                unsubscribeFromNotifications();
                cancelStallCheck();
                clearPendingRunState();
                return;
            }
            return;
        }

        unsubscribeFromNotifications();
        cancelStallCheck();
        ArrayList<MessageObject> messages = pendingMessages;
        long targetDialogId = pendingTargetDialogId;
        boolean hideCaption = pendingHideCaption;
        boolean notify = pendingNotify;
        int scheduleDate = pendingScheduleDate;
        long payStars = pendingPayStars;
        long taskId = pendingTaskId;
        CompletionCallback onComplete = pendingOnComplete;
        clearPendingRunState();
        executeSendPhase(messages, targetDialogId, hideCaption, notify, scheduleDate, payStars, taskId, onComplete);
    }

    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        detached = true;
        unsubscribeFromNotifications();
        cancelStallCheck();
        cancelPendingDownloads();
        clearRunState();
        clearPendingRunState();
        lastFailureReason = null;
    }

    public void detachFromFragment() {
        if (detached) return;
        detached = true;
    }

    public boolean isForwarding() {
        return activeTaskId != 0L;
    }

    public String consumeLastFailureReason() {
        String reason = lastFailureReason;
        lastFailureReason = null;
        return reason;
    }

    public boolean stopCurrentRun() {
        if (!isForwarding()) {
            return false;
        }
        stopRequested = true;
        currentStatus = STATUS_STOPPING;
        currentStatusDetail = LocaleController.getString(R.string.ForceForwardStatusStoppingCurrentBatch);
        lastFailureReason = null;
        unsubscribeFromNotifications();
        cancelStallCheck();
        cancelPendingDownloads();
        if (pendingMessages != null) {
            long taskId = pendingTaskId != 0 ? pendingTaskId : activeTaskId;
            CompletionCallback onComplete = pendingOnComplete;
            clearPendingRunState();
            finishRun(taskId, false, onComplete);
            return true;
        }
        if (activeSendSteps != null || isSendWaitActive()) {
            long taskId = messageWaiter.isActive() ? messageWaiter.getTaskId() : activeTaskId;
            CompletionCallback onComplete = activeSendOnComplete;
            clearActiveSendQueue();
            finishRun(taskId, false, onComplete);
            return true;
        }
        notifyStatusChanged();
        return true;
    }

    public String getForwardingStatus() {
        if (!isForwarding()) {
            return null;
        }
        if (currentStatus == STATUS_LOADING) {
            String label = TextUtils.isEmpty(currentStatusDetail) ? LocaleController.getString(R.string.ForceForwardStatusPreparingMedia) : currentStatusDetail;
            String progress;
            if (totalMediaToPrepare > 0) {
                progress = LocaleController.formatString(R.string.ForceForwardStatusSentCount, Math.max(totalMediaToPrepare - pendingMedia, 0), totalMediaToPrepare);
            } else {
                progress = LocaleController.formatString(R.string.ForceForwardStatusSentCount, sentMessages, totalMessages);
            }
            if (totalChunks > 1) {
                progress = progress + " | " + LocaleController.formatString(R.string.ForceForwardStatusChunkCount, currentChunkIndex + 1, totalChunks);
            }
            return label + " " + progress;
        }
        String progress = LocaleController.formatString(R.string.ForceForwardStatusSentCount, sentMessages, totalMessages);
        if (totalChunks > 1) {
            progress = progress + " | " + LocaleController.formatString(R.string.ForceForwardStatusChunkCount, currentChunkIndex + 1, totalChunks);
        }
        if (currentStatus == STATUS_FORWARDING) {
            String label = TextUtils.isEmpty(currentStatusDetail) ? LocaleController.getString(R.string.ForceForwardStatusForwarding) : currentStatusDetail;
            return label + " " + progress;
        }
        if (currentStatus == STATUS_STOPPING) {
            String label = TextUtils.isEmpty(currentStatusDetail) ? LocaleController.getString(R.string.ForceForwardStatusStopping) : currentStatusDetail;
            return totalChunks > 1 ? label + " | " + LocaleController.formatString(R.string.ForceForwardStatusChunkCount, currentChunkIndex + 1, totalChunks) : label;
        }
        return null;
    }

    private void notifyStatusChanged() {
        if (disposed) {
            return;
        }
        int updateMask = STATUS_REFRESH_MASK_BASE | ((statusUpdateVersion++ & 0x1FF) << 21);
        Runnable action = () -> NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.updateInterfaces, updateMask);
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            AndroidUtilities.runOnUIThread(action);
        }
    }

    private long startRun(int messageCount, int mediaCount, long targetDid, int chunkIndex, int chunkCount) {
        activeTaskId++;
        this.targetDialogId = targetDid;
        this.currentChunkIndex = Math.max(chunkIndex, 0);
        this.totalChunks = Math.max(chunkCount, 1);
        currentStatus = STATUS_LOADING;
        totalMessages = Math.max(messageCount, 0);
        sentMessages = 0;
        skippedMessages = 0;
        totalMediaToPrepare = Math.max(mediaCount, 0);
        pendingMedia = totalMediaToPrepare;
        currentStatusDetail = LocaleController.getString(R.string.ForceForwardStatusPreparingMedia);
        lastFailureReason = null;
        stopRequested = false;
        mediaWaitStartTime = 0L;
        mediaLastProgressTime = 0L;
        mediaLastObservedBytes = 0L;
        mediaLastMissingCount = 0;
        statusUpdateVersion = 0;
        activeForwards.put(targetDid, this);
        notifyStatusChanged();
        return activeTaskId;
    }

    private boolean isTaskActive(long taskId) {
        return activeTaskId == taskId;
    }

    private void updateLoadingState(int missingMedia) {
        currentStatus = STATUS_LOADING;
        pendingMedia = Math.min(Math.max(missingMedia, 0), totalMediaToPrepare);
        currentStatusDetail = missingMedia > 0 ? LocaleController.getString(R.string.ForceForwardStatusWaitingDownloads) : LocaleController.getString(R.string.ForceForwardStatusMediaReady);
        notifyStatusChanged();
    }

    private void updateForwardingState() {
        updateForwardingState(null);
    }

    private void updateForwardingState(String detail) {
        currentStatus = STATUS_FORWARDING;
        pendingMedia = 0;
        currentStatusDetail = detail;
        notifyStatusChanged();
    }

    private void setFailureReason(String failureReason) {
        lastFailureReason = failureReason;
    }

    private void failRun(long taskId, String failureReason, CompletionCallback onComplete) {
        setFailureReason(failureReason);
        finishRun(taskId, false, onComplete);
    }

    private void onMessageSent() {
        sentMessages++;
        notifyStatusChanged();
    }

    private void onMessageSkipped() {
        if (totalMessages > 0) {
            totalMessages--;
        }
        skippedMessages++;
        pendingMedia = Math.min(pendingMedia, totalMediaToPrepare);
        sentMessages = Math.min(sentMessages, totalMessages);
        notifyStatusChanged();
    }

    private void finishRun(long taskId, boolean shouldContinue, CompletionCallback onComplete) {
        if (disposed) {
            return;
        }
        boolean isCurrentTask = activeTaskId == taskId;
        if (isCurrentTask) {
            if (skippedMessages > 0 && shouldContinue && lastFailureReason == null) {
                lastFailureReason = LocaleController.formatString(R.string.ForceForwardSomeSkipped, skippedMessages);
            }
            clearRunState();
            notifyStatusChanged();
        }
        if (shouldContinue) {
        }
        if (detached) {
            if (isCurrentTask) {
                disposed = true;
            }
            return;
        }
        if (onComplete != null) {
            onComplete.onComplete(isCurrentTask && shouldContinue);
        }
    }

    private void finishRunAfterUiRefresh(long taskId, CompletionCallback onComplete) {
        AndroidUtilities.runOnUIThread(() -> {
            if (disposed) {
                return;
            }
            finishRun(taskId, !stopRequested, onComplete);
        }, SEND_PHASE_FINISH_DELAY_MS);
    }

    private void cancelSendWaitPoll() {
        if (sendWaitRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(sendWaitRunnable);
            sendWaitRunnable = null;
        }
    }

    private void clearSendWaitState() {
        cancelSendWaitPoll();
        messageWaiter.clear();
        fileUploadWaiter.clear();
    }

    private void clearActiveSendQueue() {
        clearSendWaitState();
        activeSendSteps = null;
        activeSendStepIndex = 0;
        activeSendOnComplete = null;
    }

    private boolean isSendWaitActive() {
        return messageWaiter.isActive();
    }

    private void prepareSendWait(ArrayList<AyuSequentialUtils.SendStep> sendSteps, int index, long taskId, CompletionCallback onComplete) {
        clearSendWaitState();
        activeSendSteps = sendSteps;
        activeSendStepIndex = index;
        activeSendOnComplete = onComplete;
        AyuSequentialUtils.prepareWait(messageWaiter, fileUploadWaiter, SendMessagesHelper.getInstance(currentAccount), taskId, targetDialogId);
    }

    private boolean refreshTrackedSendIds() {
        if (!isSendWaitActive()) {
            return true;
        }
        DummyMessageWaiter.Result result = messageWaiter.refreshTrackedSendIds(SendMessagesHelper.getInstance(currentAccount), this::onMessageSent);
        if (result == DummyMessageWaiter.Result.FAIL) {
            failCurrentSendStep(LocaleController.getString(R.string.ForceForwardFailed));
            return false;
        }
        if (result == DummyMessageWaiter.Result.COMPLETE) {
            completeCurrentSendStep();
            return false;
        }
        return true;
    }

    private void scheduleSendWaitPoll() {
        cancelSendWaitPoll();
        sendWaitRunnable = () -> {
            sendWaitRunnable = null;
            if (disposed || !isSendWaitActive()) {
                return;
            }
            if (!isTaskActive(messageWaiter.getTaskId())) {
                clearSendWaitState();
                return;
            }
            if (!refreshTrackedSendIds()) {
                return;
            }
            if (messageWaiter.getExpectedCount() > 0 && messageWaiter.getCompletedCount() >= messageWaiter.getExpectedCount()) {
                completeCurrentSendStep();
                return;
            }
            if (SystemClock.elapsedRealtime() - messageWaiter.getLastActivityTime() >= MAX_SEND_WAIT_MS) {
                failCurrentSendStep(LocaleController.getString(R.string.ForceForwardFailed));
                return;
            }
            scheduleSendWaitPoll();
        };
        AndroidUtilities.runOnUIThread(sendWaitRunnable, SEND_WAIT_POLL_INTERVAL_MS);
    }

    private void completeCurrentSendStep() {
        ArrayList<AyuSequentialUtils.SendStep> sendSteps = activeSendSteps;
        int nextIndex = activeSendStepIndex + 1;
        long taskId = messageWaiter.getTaskId();
        CompletionCallback onComplete = activeSendOnComplete;
        clearSendWaitState();
        if (disposed || sendSteps == null) {
            return;
        }
        runSendQueue(sendSteps, nextIndex, taskId, onComplete);
    }

    private void failCurrentSendStep(String failureReason) {
        long taskId = messageWaiter.getTaskId();
        CompletionCallback onComplete = activeSendOnComplete;
        clearActiveSendQueue();
        if (taskId == 0L) {
            return;
        }
        failRun(taskId, failureReason, onComplete);
    }

    private void handleSendAcknowledged(Object... args) {
        if (!isSendWaitActive() || !isTaskActive(messageWaiter.getTaskId())) {
            return;
        }
        if (messageWaiter.handleAcknowledged(args) == DummyMessageWaiter.Result.FAIL) {
            failCurrentSendStep(LocaleController.getString(R.string.ForceForwardFailed));
        }
    }

    private void handleSendConfirmedByServer(Object... args) {
        if (!isSendWaitActive() || !isTaskActive(messageWaiter.getTaskId())) {
            return;
        }
        if (messageWaiter.handleConfirmedByServer(args, this::onMessageSent) == DummyMessageWaiter.Result.COMPLETE) {
            completeCurrentSendStep();
        }
    }

    private void handleSendError(Object... args) {
        if (!isSendWaitActive() || !isTaskActive(messageWaiter.getTaskId())) {
            return;
        }
        if (messageWaiter.handleError(args) == DummyMessageWaiter.Result.FAIL) {
            failCurrentSendStep(LocaleController.getString(R.string.ForceForwardFailed));
        }
    }

    private boolean matchesTrackedUploadPath(String path) {
        return fileUploadWaiter.matchesTrackedUploadPath(path);
    }

    private void handleUploadStateChanged(Object[] args, boolean failed) {
        if (!isSendWaitActive() || !isTaskActive(messageWaiter.getTaskId()) || args == null || args.length == 0) {
            return;
        }
        Object value = args[0];
        if (failed && args.length > 1 && args[1] instanceof String) {
            value = args[1];
        }
        if (!(value instanceof String)) {
            return;
        }
        String path = (String) value;
        if (!matchesTrackedUploadPath(path)) {
            return;
        }
        messageWaiter.bumpActivity();
        if (failed) {
            failCurrentSendStep(LocaleController.getString(R.string.ForceForwardFailed));
        }
    }

    private void runSendQueue(ArrayList<AyuSequentialUtils.SendStep> sendSteps, int index, long taskId, CompletionCallback onComplete) {
        if (disposed) {
            return;
        }
        if (!isTaskActive(taskId) || stopRequested) {
            finishRun(taskId, false, onComplete);
            return;
        }
        if (index >= sendSteps.size()) {
            finishRunAfterUiRefresh(taskId, onComplete);
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (disposed) {
                return;
            }
            if (!isTaskActive(taskId) || stopRequested) {
                finishRun(taskId, false, onComplete);
                return;
            }
            prepareSendWait(sendSteps, index, taskId, onComplete);
            AyuSequentialUtils.DispatchResult dispatchResult;
            try {
                dispatchResult = sendSteps.get(index).dispatch();
            } catch (Exception e) {
                FileLog.e(e);
                clearActiveSendQueue();
                failRun(taskId, LocaleController.getString(R.string.ForceForwardFailed), onComplete);
                return;
            }
            if (dispatchResult == null || dispatchResult.expectedCompletions <= 0) {
                clearSendWaitState();
                runSendQueue(sendSteps, index + 1, taskId, onComplete);
                return;
            }
            AyuSequentialUtils.applyDispatchResult(messageWaiter, fileUploadWaiter, dispatchResult);
            if (!refreshTrackedSendIds()) {
                return;
            }
            if (messageWaiter.getCompletedCount() >= messageWaiter.getExpectedCount()) {
                completeCurrentSendStep();
                return;
            }
            scheduleSendWaitPoll();
        }, index == 0 ? 0 : SEND_STEP_DELAY_MS);
    }
    
    private CharSequence getMessageCaption(MessageObject mo) {
        return ChatActivity.getMessageCaption(mo, null, null);
    }

    private CharSequence getForwardCaption(MessageObject mo) {
        if (mo == null) {
            return null;
        }
        if (mo.getGroupId() != 0) {
            return ChatActivity.getMessageCaption(mo, null, null);
        }
        return getMessageCaption(mo);
    }

    private String resolvePath(MessageObject mo) {
        return attachments.resolvePath(mo);
    }

    private void trackPendingDownload(String fileName) {
        fileDownloadWaiter.trackPendingDownload(fileName);
    }

    private void clearPendingDownload(String fileName) {
        fileDownloadWaiter.clearPendingDownload(fileName);
    }

    private void cancelPendingDownloads() {
        attachments.cancelPendingDownloads();
    }

    private void resetDownloadCancellationFlags(ArrayList<MessageObject> messagesToSend) {
        attachments.resetDownloadCancellationFlags(messagesToSend);
    }

    private boolean ensureDownloaded(MessageObject mo) {
        if (mo == null || mo.messageOwner == null) return false;
        if (mo.loadingCancelled) return false;
        
        String path = resolvePath(mo);
        if (!TextUtils.isEmpty(path) && new File(path).exists()) return true;
        if (mo.isAyuDeleted()) return false;

        if (mo.getDocument() != null) {
            String fileName = FileLoader.getAttachFileName(mo.getDocument());
            trackPendingDownload(fileName);
            if (!FileLoader.getInstance(currentAccount).isLoadingFile(fileName)) {
                FileLoader.getInstance(currentAccount).loadFile(mo.getDocument(), mo, FileLoader.PRIORITY_NORMAL, 0);
            }
            return false;
        }
        
        if (mo.isPhoto()) {
            TLRPC.Photo photo = MessageObject.getPhoto(mo.messageOwner);
            if (photo != null) {
                TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, 1280);
                if (size != null) {
                    String fileName = FileLoader.getAttachFileName(size);
                    trackPendingDownload(fileName);
                    if (!FileLoader.getInstance(currentAccount).isLoadingFile(fileName)) {
                        ImageLocation imageLocation = ImageLocation.getForObject(size, mo.messageOwner);
                        if (imageLocation != null) {
                            FileLoader.getInstance(currentAccount).loadFile(imageLocation, mo.messageOwner, "jpg", FileLoader.PRIORITY_NORMAL, 0);
                        }
                    }
                }
            }
            return false;
        }
        
        if (mo.isVideo()) {
            if (mo.getDocument() == null && mo.messageOwner.media instanceof TLRPC.TL_messageMediaVideo_old) {
                TLRPC.TL_messageMediaVideo_old videoMedia = (TLRPC.TL_messageMediaVideo_old) mo.messageOwner.media;
                 if (videoMedia.video_unused != null && videoMedia.video_unused.thumb != null) {
                     String fileName = FileLoader.getAttachFileName(videoMedia.video_unused.thumb);
                     trackPendingDownload(fileName);
                     if (!FileLoader.getInstance(currentAccount).isLoadingFile(fileName)) {
                         ImageLocation imageLocation = ImageLocation.getForObject(videoMedia.video_unused.thumb, mo.messageOwner);
                         if (imageLocation != null) {
                             FileLoader.getInstance(currentAccount).loadFile(imageLocation, mo.messageOwner, "jpg", FileLoader.PRIORITY_NORMAL, 0);
                         }
                     }
                 }
            }
            return false;
        }
        
        return false;
    }

    private String getDownloadTrackingKey(MessageObject mo) {
        if (mo == null || mo.messageOwner == null) {
            return null;
        }
        if (mo.getDocument() != null) {
            return FileLoader.getAttachFileName(mo.getDocument());
        }
        if (mo.isPhoto()) {
            TLRPC.Photo photo = MessageObject.getPhoto(mo.messageOwner);
            if (photo == null) {
                return null;
            }
            TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, 1280);
            return size != null ? FileLoader.getAttachFileName(size) : null;
        }
        if (mo.isVideo() && mo.messageOwner.media instanceof TLRPC.TL_messageMediaVideo_old) {
            TLRPC.TL_messageMediaVideo_old videoMedia = (TLRPC.TL_messageMediaVideo_old) mo.messageOwner.media;
            if (videoMedia.video_unused != null && videoMedia.video_unused.thumb != null) {
                return FileLoader.getAttachFileName(videoMedia.video_unused.thumb);
            }
        }
        return null;
    }

    private boolean needsLocalCopy(MessageObject mo) {
        if (mo == null || mo.messageOwner == null) {
            return false;
        }
        if (mo.type == MessageObject.TYPE_TEXT || mo.isAnimatedEmoji()) {
            return false;
        }
        if (mo.isPhoto() || mo.isVideo() || mo.isGif()) {
            return true;
        }
        if (mo.isAyuDeleted()) {
            return mo.getDocument() != null;
        }
        return mo.getDocument() != null && !mo.isSticker() && !mo.isAnimatedSticker();
    }

    private int countMediaToPrepare(ArrayList<MessageObject> messagesToSend) {
        if (messagesToSend == null || messagesToSend.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < messagesToSend.size(); i++) {
            if (needsLocalCopy(messagesToSend.get(i))) {
                count++;
            }
        }
        return count;
    }

    private AyuAttachments.MediaPreparationResult prepareMedia(ArrayList<MessageObject> messagesToSend) {
        AyuAttachments.MediaPreparationResult result = new AyuAttachments.MediaPreparationResult();
        for (int i = 0; i < messagesToSend.size(); i++) {
            MessageObject messageObject = messagesToSend.get(i);
            if (!needsLocalCopy(messageObject)) {
                continue;
            }
            if (messageObject.loadingCancelled) {
                result.cancelledByUser = true;
                return result;
            }
            if (messageObject.isAyuDeleted() && !AyuMessageUtils.hasLocalForwardCopy(messageObject)) {
                result.hasUndownloadedAyuDeletedMedia = true;
                return result;
            }
            if (!ensureDownloaded(messageObject)) {
                result.missingMediaCount++;
                String trackingKey = getDownloadTrackingKey(messageObject);
                if (!TextUtils.isEmpty(trackingKey)) {
                    trackPendingDownload(trackingKey);
                    if (FileLoader.getInstance(currentAccount).isLoadingFile(trackingKey)) {
                        result.activeDownloadCount++;
                    }
                    long[] progress = ImageLoader.getInstance().getFileProgressSizes(trackingKey);
                    if (progress != null) {
                        result.downloadedBytes += progress[0];
                    }
                }
            }
        }
        return result;
    }

    private void sendMediaBatch(ArrayList<SendMessagesHelper.SendingMediaInfo> list, long targetDialogId, boolean isDocument, boolean isGrouping, boolean notify, int scheduleDate, long payStars) {
        MessageObject replyToMsg = replyToTopMessage;
        MessageObject replyToTopMsg = replyToTopMessage;
        SendMessagesHelper.prepareSendingMedia(
                AccountInstance.getInstance(currentAccount),
                list,
                targetDialogId,
                replyToMsg,
                replyToTopMsg,
                null,
                null,
                isDocument,
                isGrouping,
                null,
                notify,
                scheduleDate,
                0,
                chatMode,
                false,
                null,
                quickReplyShortcut,
                quickReplyShortcutId,
                0,
                false,
                payStars,
                monoForumPeerId,
                suggestionParams
        );
    }

    private void applySendContext(SendMessagesHelper.SendMessageParams params, long payStars) {
        if (params == null) {
            return;
        }
        MessageObject replyToMsg = replyToTopMessage;
        if (params.replyToMsg == null) {
            params.replyToMsg = replyToMsg;
        }
        if (params.replyToTopMsg == null) {
            params.replyToTopMsg = replyToMsg;
        }
        params.quick_reply_shortcut = quickReplyShortcut;
        params.quick_reply_shortcut_id = quickReplyShortcutId;
        params.payStars = payStars;
        params.monoForumPeer = monoForumPeerId;
        params.suggestionParams = suggestionParams;
    }

    private void addUploadPath(ArrayList<String> uploadPaths, String path) {
        if (uploadPaths == null || TextUtils.isEmpty(path) || uploadPaths.contains(path)) {
            return;
        }
        uploadPaths.add(path);
    }

    private ArrayList<String> createUploadPaths(SendMessagesHelper.SendMessageParams params) {
        if (params == null) {
            return null;
        }
        ArrayList<String> uploadPaths = new ArrayList<>(1);
        addUploadPath(uploadPaths, params.path);
        return uploadPaths.isEmpty() ? null : uploadPaths;
    }

    private ArrayList<String> createUploadPaths(ArrayList<SendMessagesHelper.SendingMediaInfo> mediaInfos) {
        if (mediaInfos == null || mediaInfos.isEmpty()) {
            return null;
        }
        ArrayList<String> uploadPaths = new ArrayList<>(mediaInfos.size());
        for (int i = 0; i < mediaInfos.size(); i++) {
            SendMessagesHelper.SendingMediaInfo info = mediaInfos.get(i);
            if (info != null) {
                addUploadPath(uploadPaths, info.path);
            }
        }
        return uploadPaths.isEmpty() ? null : uploadPaths;
    }

    private int getMessageTtl(MessageObject mo) {
        if (mo == null || mo.messageOwner == null || mo.messageOwner.media == null) {
            return 0;
        }
        return mo.messageOwner.media.ttl_seconds;
    }

    private TLRPC.TL_photo mapPhoto(MessageObject mo, String filePath) {
        if (mo == null || mo.messageOwner == null || TextUtils.isEmpty(filePath)) {
            return null;
        }
        TLRPC.Photo source = MessageObject.getPhoto(mo.messageOwner);
        if (source == null) {
            return null;
        }
        TLRPC.TL_photo mapped = new TLRPC.TL_photo();
        mapped.flags = source.flags;
        mapped.has_stickers = source.has_stickers;
        mapped.date = (int) (System.currentTimeMillis() / 1000);
        mapped.dc_id = Integer.MIN_VALUE;
        mapped.user_id = source.user_id;
        mapped.geo = source.geo;
        mapped.caption = source.caption;
        mapped.video_sizes = new ArrayList<>(source.video_sizes);
        mapped = SendMessagesHelper.getInstance(currentAccount).generatePhotoSizes(mapped, filePath, null, false);
        if (mapped == null || mapped.sizes == null || mapped.sizes.isEmpty()) {
            return null;
        }
        mapped.id = 0;
        mapped.access_hash = 0;
        mapped.file_reference = new byte[0];
        return mapped;
    }

    private TLRPC.TL_document mapDocument(MessageObject mo) {
        TLRPC.Document source = mo != null ? mo.getDocument() : null;
        if (source == null) {
            return null;
        }
        String localPath = resolvePath(mo);
        boolean hasLocalFile = !TextUtils.isEmpty(localPath) && new File(localPath).exists();

        TLRPC.TL_document mapped = new TLRPC.TL_document();
        mapped.flags = source.flags;
        mapped.id = 0;
        mapped.access_hash = 0;
        mapped.file_reference = new byte[0];
        mapped.user_id = source.user_id;
        mapped.date = (int) (System.currentTimeMillis() / 1000);
        mapped.file_name = source.file_name;
        mapped.mime_type = source.mime_type;
        mapped.size = hasLocalFile ? (int) new File(localPath).length() : source.size;
        mapped.thumbs = new ArrayList<>(source.thumbs);
        mapped.video_thumbs = new ArrayList<>(source.video_thumbs);
        mapped.version = source.version;
        mapped.dc_id = hasLocalFile ? source.dc_id : Integer.MIN_VALUE;
        mapped.key = null;
        mapped.iv = null;
        mapped.attributes = new ArrayList<>(source.attributes);
        mapped.file_name_fixed = source.file_name_fixed;
        mapped.localPath = hasLocalFile ? localPath : source.localPath;
        mapped.localThumbPath = source.localThumbPath;
        if (mo != null && mo.isGif()) {
            mapped.mime_type = "video/mp4";
        }
        return mapped;
    }

    private HashMap<String, String> createGroupedParams(long groupId, boolean isFinal) {
        HashMap<String, String> params = new HashMap<>();
        if (groupId != 0) {
            params.put("groupId", String.valueOf(groupId));
        }
        if (isFinal) {
            params.put("final", "1");
        }
        return params.isEmpty() ? null : params;
    }

    private SendMessagesHelper.SendMessageParams buildMappedPhotoParams(MessageObject mo, String caption, long targetDialogId, boolean notify, int scheduleDate, HashMap<String, String> extraParams) {
        String filePath = resolvePath(mo);
        TLRPC.TL_photo photo = mapPhoto(mo, filePath);
        if (photo == null) {
            return null;
        }
        ArrayList<TLRPC.MessageEntity> entities = mo == null || mo.messageOwner == null || mo.messageOwner.entities == null ? new ArrayList<>() : new ArrayList<>(mo.messageOwner.entities);
        String effectiveCaption = prependPseudoReplyCaption(mo, caption, entities);
        if (TextUtils.isEmpty(effectiveCaption)) {
            effectiveCaption = null;
        }
        HashMap<String, String> params = extraParams != null ? new HashMap<>(extraParams) : null;
        return SendMessagesHelper.SendMessageParams.of(photo, filePath, targetDialogId, null, null, effectiveCaption, entities.isEmpty() ? null : entities, null, params, notify, scheduleDate, 0, getMessageTtl(mo), mo, false, mo != null && mo.hasMediaSpoilers());
    }

    private SendMessagesHelper.SendMessageParams buildMappedDocumentParams(MessageObject mo, String caption, long targetDialogId, boolean notify, int scheduleDate, HashMap<String, String> extraParams) {
        String filePath = resolvePath(mo);
        if (TextUtils.isEmpty(filePath)) {
            return null;
        }
        TLRPC.TL_document document = mapDocument(mo);
        if (document == null) {
            return null;
        }
        ArrayList<TLRPC.MessageEntity> entities = mo == null || mo.messageOwner == null || mo.messageOwner.entities == null ? new ArrayList<>() : new ArrayList<>(mo.messageOwner.entities);
        String effectiveCaption = prependPseudoReplyCaption(mo, caption, entities);
        if (TextUtils.isEmpty(effectiveCaption)) {
            effectiveCaption = null;
        }
        HashMap<String, String> params = extraParams != null ? new HashMap<>(extraParams) : null;
        return SendMessagesHelper.SendMessageParams.of(document, null, filePath, targetDialogId, null, null, effectiveCaption, entities.isEmpty() ? null : entities, null, params, notify, scheduleDate, 0, getMessageTtl(mo), mo, null, false, mo != null && mo.hasMediaSpoilers());
    }

    private void sendMappedParams(SendMessagesHelper.SendMessageParams params, long payStars) {
        if (params == null) {
            return;
        }
        applySendContext(params, payStars);
        SendMessagesHelper.getInstance(currentAccount).sendMessage(params);
    }

    private SendMessagesHelper.SendMessageParams buildDocumentFallbackParams(MessageObject mo, String caption, long targetDialogId, boolean notify, int scheduleDate) {
        String filePath = resolvePath(mo);
        if (TextUtils.isEmpty(filePath)) {
            return null;
        }
        File file = new File(filePath);
        if (!file.exists()) {
            return null;
        }
        TLRPC.TL_document document = mapDocument(mo);
        if (document == null) {
            return null;
        }
        document.localPath = filePath;
        document.size = (int) file.length();
        document.date = (int) (System.currentTimeMillis() / 1000);
        ArrayList<TLRPC.MessageEntity> entities = mo == null || mo.messageOwner == null || mo.messageOwner.entities == null ? new ArrayList<>() : new ArrayList<>(mo.messageOwner.entities);
        String effectiveCaption = prependPseudoReplyCaption(mo, caption, entities);
        if (TextUtils.isEmpty(effectiveCaption)) {
            effectiveCaption = null;
        }
        return SendMessagesHelper.SendMessageParams.of(
                document, null, filePath, targetDialogId, null, null, effectiveCaption, entities.isEmpty() ? null : entities,
                null, null, notify, scheduleDate, 0, 0, mo, null, false, mo != null && mo.hasMediaSpoilers());
    }

    private ArrayList<SendMessagesHelper.SendingMediaInfo> createMediaInfoList(ArrayList<GroupedMediaItem> items) {
        ArrayList<SendMessagesHelper.SendingMediaInfo> list = new ArrayList<>();
        if (items == null) {
            return list;
        }
        for (int i = 0; i < items.size(); i++) {
            GroupedMediaItem item = items.get(i);
            SendMessagesHelper.SendingMediaInfo info = createMediaInfo(item.messageObject, item.caption);
            if (item.messageObject != null) {
                info.isVideo = item.messageObject.isVideo() || item.messageObject.isGif();
            }
            list.add(info);
        }
        return list;
    }

    private void enqueueMappedGroup(ArrayList<AyuSequentialUtils.SendStep> sendSteps, ArrayList<GroupedMediaItem> group, long targetDialogId, boolean notify, int scheduleDate, long payStars, boolean documentFallback) {
        if (group == null || group.isEmpty()) {
            return;
        }

        if (group.size() > MAX_GROUP_BATCH_SIZE) {
            for (int start = 0; start < group.size(); start += MAX_GROUP_BATCH_SIZE) {
                int end = Math.min(start + MAX_GROUP_BATCH_SIZE, group.size());
                enqueueMappedGroup(sendSteps, new ArrayList<>(group.subList(start, end)), targetDialogId, notify, scheduleDate, payStars, documentFallback);
            }
            return;
        }

        final String groupLabel = documentFallback ? LocaleController.getString(R.string.ForceForwardStatusDocumentGroup) : LocaleController.getString(R.string.ForceForwardStatusMediaGroup);
        if (group.size() == 1) {
            GroupedMediaItem item = group.get(0);
            MessageObject messageObject = item.messageObject;
            sendSteps.add(() -> {
                updateForwardingState(groupLabel + " 1/1");
                SendMessagesHelper.SendMessageParams params = messageObject != null && messageObject.isPhoto()
                        ? buildMappedPhotoParams(messageObject, item.caption, targetDialogId, notify, scheduleDate, null)
                        : buildMappedDocumentParams(messageObject, item.caption, targetDialogId, notify, scheduleDate, null);
                if (params == null) {
                    updateForwardingState(documentFallback ? LocaleController.getString(R.string.ForceForwardStatusDocumentFallback) : LocaleController.getString(R.string.ForceForwardStatusMediaFallback));
                    ArrayList<SendMessagesHelper.SendingMediaInfo> fallbackInfo = createMediaInfoList(group);
                    sendMediaBatch(fallbackInfo, targetDialogId, documentFallback, false, notify, scheduleDate, payStars);
                    return AyuSequentialUtils.DispatchResult.dispatched(1, createUploadPaths(fallbackInfo));
                }
                sendMappedParams(params, payStars);
                return AyuSequentialUtils.DispatchResult.dispatched(1, createUploadPaths(params));
            });
            return;
        }

        long generatedGroupId;
        do {
            generatedGroupId = Utilities.random.nextLong();
        } while (generatedGroupId == 0);

        ArrayList<SendMessagesHelper.SendMessageParams> paramsList = new ArrayList<>(group.size());
        for (int i = 0; i < group.size(); i++) {
            GroupedMediaItem item = group.get(i);
            MessageObject messageObject = item.messageObject;
            HashMap<String, String> groupParams = createGroupedParams(generatedGroupId, i == group.size() - 1);
            SendMessagesHelper.SendMessageParams params = messageObject != null && messageObject.isPhoto()
                    ? buildMappedPhotoParams(messageObject, item.caption, targetDialogId, notify, scheduleDate, groupParams)
                    : buildMappedDocumentParams(messageObject, item.caption, targetDialogId, notify, scheduleDate, groupParams);
            if (params == null) {
                ArrayList<GroupedMediaItem> fallbackGroup = new ArrayList<>(group);
                sendSteps.add(() -> {
                    updateForwardingState(documentFallback ? LocaleController.getString(R.string.ForceForwardStatusDocumentGroupFallback) : LocaleController.getString(R.string.ForceForwardStatusMediaGroupFallback));
                    ArrayList<SendMessagesHelper.SendingMediaInfo> fallbackInfo = createMediaInfoList(fallbackGroup);
                    sendMediaBatch(fallbackInfo, targetDialogId, documentFallback, !documentFallback, notify, scheduleDate, payStars);
                    return AyuSequentialUtils.DispatchResult.dispatched(fallbackGroup.size(), createUploadPaths(fallbackInfo));
                });
                return;
            }
            paramsList.add(params);
        }

        for (int i = 0; i < paramsList.size(); i++) {
            final int stepIndex = i;
            final int stepCount = paramsList.size();
            final SendMessagesHelper.SendMessageParams params = paramsList.get(i);
            sendSteps.add(() -> {
                updateForwardingState(groupLabel + " " + (stepIndex + 1) + "/" + stepCount);
                sendMappedParams(params, payStars);
                return AyuSequentialUtils.DispatchResult.dispatched(1, createUploadPaths(params));
            });
        }
    }

    private void enqueueLeftoverGroupAsSingles(ArrayList<AyuSequentialUtils.SendStep> sendSteps, ArrayList<GroupedMediaItem> group, long targetDialogId, boolean notify, int scheduleDate, long payStars, boolean documentFallback) {
        if (group == null || group.isEmpty()) {
            return;
        }
        FileLog.w("AyuForward: degraded leftover " + (documentFallback ? "document" : "media") + " group to singles");
        for (int i = 0; i < group.size(); i++) {
            GroupedMediaItem item = group.get(i);
            MessageObject messageObject = item.messageObject;
            if (messageObject == null) {
                sendSteps.add(() -> {
                    onMessageSkipped();
                    return AyuSequentialUtils.DispatchResult.none();
                });
                continue;
            }
            final String caption = item.caption;
            sendSteps.add(() -> {
                if (documentFallback) {
                    updateForwardingState(LocaleController.getString(R.string.ForceForwardStatusDocumentCopy));
                    SendMessagesHelper.SendMessageParams params = buildMappedDocumentParams(messageObject, caption, targetDialogId, notify, scheduleDate, null);
                    if (params == null) {
                        updateForwardingState(LocaleController.getString(R.string.ForceForwardStatusDocumentFallback));
                        params = buildDocumentFallbackParams(messageObject, caption, targetDialogId, notify, scheduleDate);
                        if (params == null) {
                            onMessageSkipped();
                            return AyuSequentialUtils.DispatchResult.none();
                        }
                    }
                    sendMappedParams(params, payStars);
                    return AyuSequentialUtils.DispatchResult.dispatched(1, createUploadPaths(params));
                } else {
                    updateForwardingState(messageObject.isPhoto()
                            ? LocaleController.getString(R.string.ForceForwardStatusPhotoCopy)
                            : LocaleController.getString(R.string.ForceForwardStatusMediaCopy));
                    SendMessagesHelper.SendMessageParams params = messageObject.isPhoto()
                            ? buildMappedPhotoParams(messageObject, caption, targetDialogId, notify, scheduleDate, null)
                            : buildMappedDocumentParams(messageObject, caption, targetDialogId, notify, scheduleDate, null);
                    if (params == null) {
                        updateForwardingState(LocaleController.getString(R.string.ForceForwardStatusMediaFallback));
                        ArrayList<SendMessagesHelper.SendingMediaInfo> one = new ArrayList<>(1);
                        SendMessagesHelper.SendingMediaInfo info = createMediaInfo(messageObject, caption);
                        info.isVideo = messageObject.isVideo() || messageObject.isGif();
                        one.add(info);
                        sendMediaBatch(one, targetDialogId, false, false, notify, scheduleDate, payStars);
                        return AyuSequentialUtils.DispatchResult.dispatched(1, createUploadPaths(one));
                    }
                    sendMappedParams(params, payStars);
                    return AyuSequentialUtils.DispatchResult.dispatched(1, createUploadPaths(params));
                }
            });
        }
    }

    private void addToGroup(long gid,
                            GroupedMediaItem item,
                            HashMap<Long, ArrayList<GroupedMediaItem>> map,
                            HashMap<Long, Integer> remain,
                            ArrayList<AyuSequentialUtils.SendStep> sendSteps,
                            boolean document,
                            long targetDialogId,
                            boolean notify,
                            int scheduleDate,
                            long payStars) {
        ArrayList<GroupedMediaItem> list = map.computeIfAbsent(gid, k -> new ArrayList<>());
        list.add(item);
        Integer r = remain.get(gid);
        if (r != null) {
            r = r - 1;
            if (r <= 0) {
                map.remove(gid);
                remain.remove(gid);
                enqueueMappedGroup(sendSteps, new ArrayList<>(list), targetDialogId, notify, scheduleDate, payStars, document);
            } else {
                remain.put(gid, r);
            }
        }
    }
    
    public void forwardMessages(ArrayList<MessageObject> messagesToSend, long targetDialogId, boolean showUndo, boolean hideCaption, boolean notify, int scheduleDate, long payStars, CompletionCallback onComplete) {
        forwardMessages(messagesToSend, targetDialogId, showUndo, hideCaption, notify, scheduleDate, payStars, 0, 1, onComplete);
    }

    public void forwardMessages(ArrayList<MessageObject> messagesToSend, long targetDialogId, boolean showUndo, boolean hideCaption, boolean notify, int scheduleDate, long payStars, int chunkIndex, int chunkCount, CompletionCallback onComplete) {
        if (disposed || messagesToSend == null || messagesToSend.isEmpty() || (!detached && parentFragment != null && parentFragment.getParentActivity() == null)) {
            setFailureReason(null);
            if (onComplete != null) {
                onComplete.onComplete(false);
            }
            return;
        }
        resetDownloadCancellationFlags(messagesToSend);
        long taskId = startRun(messagesToSend.size(), countMediaToPrepare(messagesToSend), targetDialogId, chunkIndex, chunkCount);
        forwardMessages(messagesToSend, targetDialogId, showUndo, hideCaption, notify, scheduleDate, payStars, taskId, 0, onComplete);
    }

    private void forwardMessages(ArrayList<MessageObject> messagesToSend, long targetDialogId, boolean showUndo, boolean hideCaption, boolean notify, int scheduleDate, long payStars, long taskId, int retryCount, CompletionCallback onComplete) {
        if (disposed) {
            return;
        }
        if (!isTaskActive(taskId)) {
            finishRun(taskId, false, onComplete);
            return;
        }
        if (stopRequested) {
            finishRun(taskId, false, onComplete);
            return;
        }

        AyuAttachments.MediaPreparationResult mediaState = prepareMedia(messagesToSend);
        if (mediaState.hasUndownloadedAyuDeletedMedia) {
            failRun(taskId, LocaleController.getString(R.string.PleaseDownload), onComplete);
            return;
        }
        int missingMediaCount = mediaState.missingMediaCount;
        updateLoadingState(missingMediaCount);
        if (!isTaskActive(taskId)) {
            finishRun(taskId, false, onComplete);
            return;
        }

        if (missingMediaCount > 0) {
            if (shouldAbortMediaWait(mediaState, taskId, onComplete)) {
                return;
            }
            pendingMessages = messagesToSend;
            pendingTargetDialogId = targetDialogId;
            pendingShowUndo = showUndo;
            pendingHideCaption = hideCaption;
            pendingNotify = notify;
            pendingScheduleDate = scheduleDate;
            pendingPayStars = payStars;
            pendingTaskId = taskId;
            pendingOnComplete = onComplete;
            subscribeToNotifications();
            scheduleStallCheck();
            return;
        }

        executeSendPhase(messagesToSend, targetDialogId, hideCaption, notify, scheduleDate, payStars, taskId, onComplete);
    }

    private void executeSendPhase(ArrayList<MessageObject> messagesToSend, long targetDialogId, boolean hideCaption, boolean notify, int scheduleDate, long payStars, long taskId, CompletionCallback onComplete) {
        subscribeToNotifications();
        updateForwardingState(LocaleController.getString(R.string.ForceForwardStatusStarting));
        try {
            HashMap<Long, ArrayList<GroupedMediaItem>> albumMap = new HashMap<>();
            HashMap<Long, ArrayList<GroupedMediaItem>> docAlbumMap = new HashMap<>();
            HashMap<Long, Integer> albumRemain = new HashMap<>();
            ArrayList<AyuSequentialUtils.SendStep> sendSteps = new ArrayList<>();

            for (MessageObject mo : messagesToSend) {
                long gid = mo.getGroupId();
                if (gid == 0) continue;
                
                boolean groupedMedia = mo.isPhoto() || mo.isVideo() || mo.isGif();
                boolean groupedDoc = mo.getDocument() != null && !mo.isVideo() && !MessageObject.isGifMessage(mo.messageOwner) && !mo.isSticker() && !mo.isAnimatedSticker();
                
                if (groupedMedia || groupedDoc) {
                    albumRemain.put(gid, albumRemain.getOrDefault(gid, 0) + 1);
                }
            }

            for (MessageObject mo : messagesToSend) {
                if (!isTaskActive(taskId) || stopRequested) {
                    finishRun(taskId, false, onComplete);
                    return;
                }
                CharSequence captionCs = hideCaption ? null : getForwardCaption(mo);
                String caption = captionCs != null ? captionCs.toString() : null;
                boolean hasMessage = mo.messageOwner != null && !TextUtils.isEmpty(mo.messageOwner.message);

                if (mo.type == MessageObject.TYPE_TEXT || mo.isAnimatedEmoji()) {
                    MessageObject messageObject = mo;
                    String messageCaption = caption;
                    sendSteps.add(() -> {
                        updateForwardingState(LocaleController.getString(R.string.ForceForwardStatusTextCopy));
                        sendTextMessage(messageObject, messageCaption, targetDialogId, notify, scheduleDate, payStars);
                        return AyuSequentialUtils.DispatchResult.dispatched(1, null);
                    });
                    continue;
                }
                
                long gid = mo.getGroupId();

                if (mo.isPhoto() || mo.isVideo() || mo.isGif()) {
                    if (!ensureDownloaded(mo)) {
                        onMessageSkipped();
                        continue;
                    }

                    if (gid != 0) {
                        addToGroup(gid, new GroupedMediaItem(mo, caption), albumMap, albumRemain, sendSteps, false, targetDialogId, notify, scheduleDate, payStars);
                    } else {
                        MessageObject messageObject = mo;
                        String messageCaption = caption;
                        sendSteps.add(() -> {
                            updateForwardingState(messageObject.isPhoto() ? LocaleController.getString(R.string.ForceForwardStatusPhotoCopy) : LocaleController.getString(R.string.ForceForwardStatusMediaCopy));
                            SendMessagesHelper.SendMessageParams params = messageObject.isPhoto()
                                    ? buildMappedPhotoParams(messageObject, messageCaption, targetDialogId, notify, scheduleDate, null)
                                    : buildMappedDocumentParams(messageObject, messageCaption, targetDialogId, notify, scheduleDate, null);
                            if (params == null) {
                                updateForwardingState(LocaleController.getString(R.string.ForceForwardStatusMediaFallback));
                                ArrayList<SendMessagesHelper.SendingMediaInfo> one = new ArrayList<>(1);
                                SendMessagesHelper.SendingMediaInfo info = createMediaInfo(messageObject, messageCaption);
                                info.isVideo = messageObject.isVideo() || messageObject.isGif();
                                one.add(info);
                                sendMediaBatch(one, targetDialogId, false, false, notify, scheduleDate, payStars);
                                return AyuSequentialUtils.DispatchResult.dispatched(1, createUploadPaths(one));
                            }
                            sendMappedParams(params, payStars);
                            return AyuSequentialUtils.DispatchResult.dispatched(1, createUploadPaths(params));
                        });
                    }
                    continue;
                }

                if (mo.getDocument() != null && !mo.isSticker() && !mo.isAnimatedSticker()) {
                    if (!ensureDownloaded(mo)) {
                        onMessageSkipped();
                        continue;
                    }
                    
                    if (gid != 0) {
                        addToGroup(gid, new GroupedMediaItem(mo, caption), docAlbumMap, albumRemain, sendSteps, true, targetDialogId, notify, scheduleDate, payStars);
                    } else {
                        MessageObject messageObject = mo;
                        String messageCaption = caption;
                        sendSteps.add(() -> {
                            updateForwardingState(LocaleController.getString(R.string.ForceForwardStatusDocumentCopy));
                            SendMessagesHelper.SendMessageParams params = buildMappedDocumentParams(messageObject, messageCaption, targetDialogId, notify, scheduleDate, null);
                            if (params == null) {
                                updateForwardingState(LocaleController.getString(R.string.ForceForwardStatusDocumentFallback));
                                params = buildDocumentFallbackParams(messageObject, messageCaption, targetDialogId, notify, scheduleDate);
                                if (params == null) {
                                    onMessageSkipped();
                                    return AyuSequentialUtils.DispatchResult.none();
                                }
                            }
                            sendMappedParams(params, payStars);
                            return AyuSequentialUtils.DispatchResult.dispatched(1, createUploadPaths(params));
                        });
                    }
                    continue;
                }

                if (mo.isSticker() || mo.isAnimatedSticker()) {
                    if (mo.getDocument() != null) {
                        MessageObject messageObject = mo;
                        sendSteps.add(() -> {
                            updateForwardingState(LocaleController.getString(R.string.ForceForwardStatusStickerCopy));
                            MessageObject replyToMsg = replyToTopMessage;
                            SendMessagesHelper.getInstance(currentAccount).sendSticker(messageObject.getDocument(), null, targetDialogId, replyToMsg, replyToMsg, null, null, null, notify, scheduleDate, 0, false, null, quickReplyShortcut, quickReplyShortcutId, payStars, monoForumPeerId, suggestionParams);
                            return AyuSequentialUtils.DispatchResult.dispatched(1, null);
                        });
                    } else {
                        onMessageSkipped();
                    }
                    continue;
                }
                
                if (hasMessage) {
                    MessageObject messageObject = mo;
                    sendSteps.add(() -> {
                        updateForwardingState(LocaleController.getString(R.string.ForceForwardStatusTextFallback));
                        sendTextMessage(messageObject, null, targetDialogId, notify, scheduleDate, payStars);
                        return AyuSequentialUtils.DispatchResult.dispatched(1, null);
                    });
                } else {
                    onMessageSkipped();
                }
            }

            if (!isTaskActive(taskId) || stopRequested) {
                finishRun(taskId, false, onComplete);
                return;
            }

            for (ArrayList<GroupedMediaItem> group : albumMap.values()) {
                enqueueLeftoverGroupAsSingles(sendSteps, new ArrayList<>(group), targetDialogId, notify, scheduleDate, payStars, false);
            }
            for (ArrayList<GroupedMediaItem> group : docAlbumMap.values()) {
                enqueueLeftoverGroupAsSingles(sendSteps, new ArrayList<>(group), targetDialogId, notify, scheduleDate, payStars, true);
            }

            if (!isTaskActive(taskId) || stopRequested) {
                finishRun(taskId, false, onComplete);
                return;
            }

            if (sendSteps.isEmpty()) {
                finishRunAfterUiRefresh(taskId, onComplete);
                return;
            }
            runSendQueue(sendSteps, 0, taskId, onComplete);
        } catch (Exception e) {
            FileLog.e(e);
            failRun(taskId, LocaleController.getString(R.string.ForceForwardFailed), onComplete);
        }
    }
    
    private SendMessagesHelper.SendingMediaInfo createMediaInfo(MessageObject mo, String caption) {
        SendMessagesHelper.SendingMediaInfo info = new SendMessagesHelper.SendingMediaInfo();
        info.path = resolvePath(mo);
        ArrayList<TLRPC.MessageEntity> entities = mo == null || mo.messageOwner == null || mo.messageOwner.entities == null ? new ArrayList<>() : new ArrayList<>(mo.messageOwner.entities);
        info.caption = prependPseudoReplyCaption(mo, caption, entities);
        info.entities = entities.isEmpty() ? null : entities;
        if (mo.messageOwner != null && mo.messageOwner.media != null) {
            info.ttl = mo.messageOwner.media.ttl_seconds;
        }
        info.hasMediaSpoilers = mo.hasMediaSpoilers();
        return info;
    }

    private void sendTextMessage(MessageObject mo, String captionOverride, long targetDialogId, boolean notify, int scheduleDate, long payStars) {
        if (mo == null) return;

        String text = mo.messageOwner != null && !TextUtils.isEmpty(mo.messageOwner.message)
                ? mo.messageOwner.message
                : captionOverride;

        if (TextUtils.isEmpty(text)) return;

        ArrayList<TLRPC.MessageEntity> entities = mo.messageOwner != null && mo.messageOwner.entities != null && !mo.messageOwner.entities.isEmpty()
                ? new ArrayList<>(mo.messageOwner.entities)
                : MediaDataController.getInstance(currentAccount).getEntities(new CharSequence[]{text}, true);

        text = prependPseudoReply(mo, text, entities);

        SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(text, targetDialogId, null, null, null, false, entities, null, null, notify, scheduleDate, 0, null, false);
        applySendContext(params, payStars);
        SendMessagesHelper.getInstance(currentAccount).sendMessage(params);
    }

    private boolean shouldAbortMediaWait(AyuAttachments.MediaPreparationResult mediaState, long taskId, CompletionCallback onComplete) {
        if (mediaState.cancelledByUser) {
            setFailureReason(null);
            finishRun(taskId, false, onComplete);
            return true;
        }

        long now = SystemClock.elapsedRealtime();
        if (mediaWaitStartTime == 0L) {
            mediaWaitStartTime = now;
            mediaLastProgressTime = now;
            mediaLastObservedBytes = mediaState.downloadedBytes;
            mediaLastMissingCount = mediaState.missingMediaCount;
            return false;
        }

        boolean progressAdvanced = mediaState.missingMediaCount < mediaLastMissingCount || mediaState.downloadedBytes > mediaLastObservedBytes;
        if (progressAdvanced) {
            mediaLastProgressTime = now;
            mediaLastObservedBytes = mediaState.downloadedBytes;
            mediaLastMissingCount = mediaState.missingMediaCount;
            return false;
        }

        if (now - mediaWaitStartTime >= MAX_MEDIA_WAIT_MS) {
            FileLog.w("AyuForward: media wait exceeded hard timeout");
            failRun(taskId, LocaleController.getString(R.string.ForceForwardMediaTimedOut), onComplete);
            return true;
        }

        if (now - mediaLastProgressTime >= MAX_MEDIA_STALL_TIMEOUT_MS) {
            String failureReason = mediaState.activeDownloadCount > 0
                    ? LocaleController.getString(R.string.ForceForwardMediaStalled)
                    : LocaleController.getString(R.string.ForceForwardMediaDidNotStart);
            FileLog.w("AyuForward: " + failureReason);
            failRun(taskId, failureReason, onComplete);
            return true;
        }

        return false;
    }

}

