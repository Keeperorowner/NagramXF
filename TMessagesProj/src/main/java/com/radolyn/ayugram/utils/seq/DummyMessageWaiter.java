package com.radolyn.ayugram.utils.seq;

import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class DummyMessageWaiter extends SyncWaiter {

    private static final long LOOKUP_TIMEOUT_MS = 3500L;
    private static final long WATCHER_TIMEOUT_MS = 300000L;
    private static final long POLL_INTERVAL_MS = 25L;

    private final Set<Integer> alreadySent = Collections.synchronizedSet(new HashSet<>());
    private final Set<Integer> baselineIds = new HashSet<>();

    private long dialogId;
    private int baselinePendingCount;
    private volatile boolean failed;
    private volatile boolean queueWatcherStarted;

    public int sendingId;

    public DummyMessageWaiter(int currentAccount) {
        super(currentAccount);
        notifications.add(NotificationCenter.messageReceivedByServer);
        notifications.add(NotificationCenter.messageSendError);
        notifications.add(NotificationCenter.messageReceivedByAck);
        notifications.add(NotificationCenter.messagesDeleted);
    }

    public void trySetSendingId(long dialogId, ArrayList<Integer> existingIds) {
        if (dialogId == 0) {
            dialogId = UserConfig.getInstance(currentAccount).getClientUserId();
        }
        this.dialogId = dialogId;
        SendMessagesHelper sendMessagesHelper = SendMessagesHelper.getInstance(currentAccount);
        baselineIds.clear();
        if (existingIds != null) {
            baselineIds.addAll(existingIds);
        }
        baselinePendingCount = baselineIds.size();
        long start = System.currentTimeMillis();
        int currentSendingId = 0;
        while (currentSendingId == 0) {
            currentSendingId = resolveNewSendingId(sendMessagesHelper);
            if (currentSendingId != 0) {
                break;
            }
            if (System.currentTimeMillis() - start > LOOKUP_TIMEOUT_MS) {
                startQueueWatcher(sendMessagesHelper);
                break;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                unsubscribe();
                break;
            }
        }
        if (currentSendingId != 0) {
            setSendingId(currentSendingId);
            startQueueWatcher(sendMessagesHelper);
        }
    }

    public boolean hasFailed() {
        return failed || isTimedOut();
    }

    private void setSendingId(int sendingId) {
        this.sendingId = sendingId;
        if (alreadySent.contains(sendingId)) {
            unsubscribe();
        }
    }

    private int resolveNewSendingId(SendMessagesHelper sendMessagesHelper) {
        try {
            ArrayList<Integer> currentIds = sendMessagesHelper.getSendingMessageIds(dialogId);
            for (int i = 0; i < currentIds.size(); i++) {
                Integer id = currentIds.get(i);
                if (id != null && !baselineIds.contains(id)) {
                    return id;
                }
            }
        } catch (Exception ignore) {
        }
        return 0;
    }

    private void startQueueWatcher(SendMessagesHelper sendMessagesHelper) {
        if (queueWatcherStarted || isReleased()) {
            return;
        }
        queueWatcherStarted = true;
        Thread watcher = new Thread(() -> {
            boolean observedNewPending = sendingId != 0;
            long start = System.currentTimeMillis();
            while (!isReleased() && System.currentTimeMillis() - start < WATCHER_TIMEOUT_MS) {
                try {
                    ArrayList<Integer> currentIds = sendMessagesHelper.getSendingMessageIds(dialogId);
                    for (int i = 0; i < currentIds.size(); i++) {
                        Integer id = currentIds.get(i);
                        if (id != null && !baselineIds.contains(id)) {
                            observedNewPending = true;
                            if (sendingId == 0) {
                                setSendingId(id);
                            }
                            break;
                        }
                    }

                    if (!observedNewPending && !alreadySent.isEmpty()) {
                        observedNewPending = true;
                    }

                    if (observedNewPending && currentIds.size() <= baselinePendingCount) {
                        unsubscribe();
                        return;
                    }

                    Thread.sleep(POLL_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception ignore) {
                }
            }
        }, "AyuMessageWaiter-" + currentAccount);
        watcher.setDaemon(true);
        watcher.start();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.messageReceivedByAck
                || id == NotificationCenter.messageReceivedByServer
                || id == NotificationCenter.messageSendError) {
            if (args == null || args.length == 0 || !(args[0] instanceof Integer)) {
                return;
            }
            Integer messageId = (Integer) args[0];
            if (id == NotificationCenter.messageSendError) {
                failed = true;
            }
            if (sendingId == 0) {
                alreadySent.add(messageId);
                return;
            }
            if (messageId == sendingId) {
                unsubscribe();
            }
            return;
        }

        if (id != NotificationCenter.messagesDeleted || args == null || args.length < 2 || !(args[0] instanceof ArrayList) || !(args[1] instanceof Long)) {
            return;
        }

        ArrayList<?> deletedIds = (ArrayList<?>) args[0];
        Long dialogId = (Long) args[1];
        if (Math.abs(dialogId) != Math.abs(this.dialogId) && dialogId != 0L && this.dialogId != 0L) {
            return;
        }

        if (sendingId == 0) {
            for (int i = 0; i < deletedIds.size(); i++) {
                Object value = deletedIds.get(i);
                if (value instanceof Integer) {
                    alreadySent.add((Integer) value);
                }
            }
            return;
        }

        for (int i = 0; i < deletedIds.size(); i++) {
            Object value = deletedIds.get(i);
            if (value instanceof Integer && (Integer) value == sendingId) {
                unsubscribe();
                return;
            }
        }
    }
}
