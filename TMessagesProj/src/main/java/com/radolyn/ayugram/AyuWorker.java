package com.radolyn.ayugram;

import com.radolyn.ayugram.controllers.AyuGhostController;

import com.radolyn.ayugram.utils.AyuGhostUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class AyuWorker {

    private static final long INITIAL_DELAY_MS = 1500L;
    private static final long PERIOD_MS = 3000L;
    private static final long LAST_SEEN_FETCH_DELAY_MS = 100L;

    private static ScheduledFuture<?> scheduledTask;
    private static final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();
    private static final ConcurrentHashMap<Integer, AtomicBoolean> needOffline =
            new ConcurrentHashMap<>();

    static {
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            needOffline.put(i, new AtomicBoolean(false));
        }
    }

    private AyuWorker() {
    }

    public static synchronized void run() {
        ScheduledFuture<?> existing = scheduledTask;
        if (existing != null && !existing.isDone()) {
            existing.cancel(false);
        }
        scheduledTask = scheduler.scheduleWithFixedDelay(
                AyuWorker::runOnce,
                INITIAL_DELAY_MS,
                PERIOD_MS,
                TimeUnit.MILLISECONDS
        );
    }

    private static void runOnce() {
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            if (UserConfig.getInstance(account).isClientActivated()
                    && shouldSendOffline(account)) {
                sendOffline(account);
            }
        }
    }

    private static boolean shouldSendOffline(int account) {
        if (!AyuGhostController.getInstance(account).isSendOfflinePacketAfterOnline()) {
            return false;
        }
        AtomicBoolean flag = needOffline.get(account);
        return flag != null && flag.getAndSet(false);
    }

    private static void sendOffline(int account) {
        AyuGhostUtils.performStatusRequest(account, true);
        notifyLastSeenPillFetch(account);
    }

    private static void notifyLastSeenPillFetch(int account) {
        AndroidUtilities.runOnUIThread(() ->
                NotificationCenter.getGlobalInstance().postNotificationName(
                        AyuConstants.LAST_SEEN_PILL_FETCH, account),
                LAST_SEEN_FETCH_DELAY_MS);
    }

    public static void requestLastSeenUpdate(int account) {
        if (AyuGhostController.getInstance(account).isSendOfflinePacketAfterOnline()) {
            AtomicBoolean flag = needOffline.get(account);
            if (flag != null) {
                flag.set(true);
            }
            sendOffline(account);
            run();
        } else {
            notifyLastSeenPillFetch(account);
        }
    }

    public static synchronized void setOnline(int account, boolean needOffline) {
        AtomicBoolean flag = AyuWorker.needOffline.get(account);
        if (flag != null) {
            flag.set(needOffline);
        }
        if (AyuGhostController.getInstance(account).isSendOfflinePacketAfterOnline()) {
            run();
        }
    }

    public static void clearOnline(int account) {
        AtomicBoolean flag = needOffline.get(account);
        if (flag != null) {
            flag.set(false);
        }
    }

    public static void shutdown() {
        scheduler.shutdown();
    }
}
