package com.radolyn.ayugram;

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

/**
 * Periodically sends offline status packets for accounts with {@code sendOfflinePacketAfterOnline}
 * enabled, and coordinates LastSeenPill fetches by sending an offline packet before posting
 * LAST_SEEN_PILL_FETCH.
 */
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

    /** (Re)schedules the periodic offline-packet task, cancelling any running one first. */
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

    /** One iteration: send an offline packet for each activated account with {@code needOffline} set and the setting enabled. */
    private static void runOnce() {
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            if (UserConfig.getInstance(account).isClientActivated()
                    && shouldSendOffline(account)) {
                sendOffline(account);
            }
        }
    }

    private static boolean shouldSendOffline(int account) {
        if (!AyuGhostConfig.isSendOfflinePacketAfterOnline(account)) {
            return false;
        }
        AtomicBoolean flag = needOffline.get(account);
        return flag != null && flag.getAndSet(false);
    }

    private static void sendOffline(int account) {
        AyuGhostUtils.performStatusRequest(account, true);
        notifyLastSeenPillFetch(account);
    }

    /** Posts LAST_SEEN_PILL_FETCH on the UI thread after a short delay so the pill fetches after the offline packet lands. */
    private static void notifyLastSeenPillFetch(int account) {
        AndroidUtilities.runOnUIThread(() ->
                NotificationCenter.getGlobalInstance().postNotificationName(
                        AyuConstants.LAST_SEEN_PILL_FETCH, account),
                LAST_SEEN_FETCH_DELAY_MS);
    }

    /**
     * Sends an offline packet for the account and notifies LastSeenPill it may fetch.
     * If the setting is disabled, only the fetch notification is posted.
     */
    public static void requestLastSeenUpdate(int account) {
        if (AyuGhostConfig.isSendOfflinePacketAfterOnline(account)) {
            AtomicBoolean flag = needOffline.get(account);
            if (flag != null) {
                flag.set(true);
            }
            // Send offline now, then arm the periodic scheduler.
            sendOffline(account);
            run();
        } else {
            // No ghost mode — let the pill fetch directly.
            notifyLastSeenPillFetch(account);
        }
    }

    /**
     * Sets the {@code needOffline} flag and starts the scheduler if the setting is enabled.
     */
    public static synchronized void setOnline(int account, boolean needOffline) {
        AtomicBoolean flag = AyuWorker.needOffline.get(account);
        if (flag != null) {
            flag.set(needOffline);
        }
        if (AyuGhostConfig.isSendOfflinePacketAfterOnline(account)) {
            run();
        }
    }

    /** Clears the {@code needOffline} flag; call when ghost mode is turned off. */
    public static void clearOnline(int account) {
        AtomicBoolean flag = needOffline.get(account);
        if (flag != null) {
            flag.set(false);
        }
    }

    /**
     * Shuts down the scheduler. Called on app termination.
     */
    public static void shutdown() {
        scheduler.shutdown();
    }
}
