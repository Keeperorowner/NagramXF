package com.radolyn.ayugram.utils.seq;

import org.telegram.messenger.SendMessagesHelper;

import java.util.ArrayList;
import java.util.HashSet;

public class DummyMessageWaiter extends SyncWaiter {

    public enum Result {
        NONE,
        COMPLETE,
        FAIL
    }

    private long taskId;
    private long dialogId;
    private int expectedCount;
    private int completedCount;
    private long lastActivityTime;
    private final HashSet<Integer> baselineIds = new HashSet<>();
    private final HashSet<Integer> trackedIds = new HashSet<>();
    private final HashSet<Integer> handledIds = new HashSet<>();
    private final HashSet<Integer> ackedIds = new HashSet<>();
    private final HashSet<Integer> pendingErrorIds = new HashSet<>();

    public DummyMessageWaiter(int currentAccount) {
        super(currentAccount);
    }

    public void prepare(long taskId, long dialogId, SendMessagesHelper sendMessagesHelper) {
        clear();
        this.taskId = taskId;
        this.dialogId = dialogId;
        this.lastActivityTime = android.os.SystemClock.elapsedRealtime();
        baselineIds.addAll(sendMessagesHelper.getSendingMessageIds(dialogId));
    }

    public void clear() {
        taskId = 0L;
        dialogId = 0L;
        expectedCount = 0;
        completedCount = 0;
        lastActivityTime = 0L;
        baselineIds.clear();
        trackedIds.clear();
        handledIds.clear();
        ackedIds.clear();
        pendingErrorIds.clear();
    }

    public boolean isActive() {
        return taskId != 0L;
    }

    public long getTaskId() {
        return taskId;
    }

    public long getDialogId() {
        return dialogId;
    }

    public void setExpectedCount(int expectedCount) {
        this.expectedCount = expectedCount;
    }

    public int getExpectedCount() {
        return expectedCount;
    }

    public int getCompletedCount() {
        return completedCount;
    }

    public long getLastActivityTime() {
        return lastActivityTime;
    }

    public void bumpActivity() {
        markActivity();
    }

    public Result refreshTrackedSendIds(SendMessagesHelper sendMessagesHelper, Runnable onMessageSent) {
        if (!isActive()) {
            return Result.NONE;
        }
        ArrayList<Integer> currentIds = sendMessagesHelper.getSendingMessageIds(dialogId);
        HashSet<Integer> currentIdSet = new HashSet<>(currentIds.size());
        for (int i = 0; i < currentIds.size(); i++) {
            int localId = currentIds.get(i);
            currentIdSet.add(localId);
            if (baselineIds.contains(localId)) {
                continue;
            }
            if (trackedIds.add(localId)) {
                markActivity();
                if (pendingErrorIds.remove(localId)) {
                    return Result.FAIL;
                }
            }
        }
        return completeAckedMessagesMissingFromQueue(currentIdSet, onMessageSent);
    }

    public Result handleAcknowledged(Object... args) {
        if (!isActive() || args == null || args.length == 0 || !(args[0] instanceof Integer)) {
            return Result.NONE;
        }
        int localId = (Integer) args[0];
        if (handledIds.contains(localId) || baselineIds.contains(localId)) {
            return Result.NONE;
        }
        if (pendingErrorIds.remove(localId)) {
            return Result.FAIL;
        }
        trackedIds.add(localId);
        ackedIds.add(localId);
        markActivity();
        return Result.NONE;
    }

    public Result handleConfirmedByServer(Object[] args, Runnable onMessageSent) {
        if (!isActive() || args == null || args.length < 4 || !(args[0] instanceof Integer) || !(args[3] instanceof Long)) {
            return Result.NONE;
        }
        int localId = (Integer) args[0];
        long dialogId = (Long) args[3];
        if (dialogId != this.dialogId) {
            return Result.NONE;
        }
        return finalizeCompletedSend(localId, onMessageSent) ? Result.COMPLETE : Result.NONE;
    }

    public Result handleError(Object... args) {
        if (!isActive() || args == null || args.length == 0 || !(args[0] instanceof Integer)) {
            return Result.NONE;
        }
        int localId = (Integer) args[0];
        if (handledIds.contains(localId) || baselineIds.contains(localId)) {
            return Result.NONE;
        }
        if (trackedIds.contains(localId)) {
            return Result.FAIL;
        }
        pendingErrorIds.add(localId);
        return Result.NONE;
    }

    private Result completeAckedMessagesMissingFromQueue(HashSet<Integer> currentIds, Runnable onMessageSent) {
        if (ackedIds.isEmpty()) {
            return Result.NONE;
        }
        ArrayList<Integer> completedIds = null;
        for (Integer ackedId : ackedIds) {
            if (ackedId == null || handledIds.contains(ackedId) || baselineIds.contains(ackedId) || currentIds.contains(ackedId)) {
                continue;
            }
            if (completedIds == null) {
                completedIds = new ArrayList<>();
            }
            completedIds.add(ackedId);
        }
        if (completedIds == null) {
            return Result.NONE;
        }
        for (int i = 0; i < completedIds.size(); i++) {
            if (finalizeCompletedSend(completedIds.get(i), onMessageSent)) {
                return Result.COMPLETE;
            }
        }
        return Result.NONE;
    }

    private boolean finalizeCompletedSend(int localId, Runnable onMessageSent) {
        if (baselineIds.contains(localId) || !handledIds.add(localId)) {
            return false;
        }
        trackedIds.add(localId);
        ackedIds.remove(localId);
        pendingErrorIds.remove(localId);
        markActivity();
        completedCount++;
        if (onMessageSent != null) {
            onMessageSent.run();
        }
        return expectedCount > 0 && completedCount >= expectedCount;
    }

    private void markActivity() {
        lastActivityTime = android.os.SystemClock.elapsedRealtime();
    }
}
