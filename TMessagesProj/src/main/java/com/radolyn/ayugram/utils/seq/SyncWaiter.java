package com.radolyn.ayugram.utils.seq;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.NotificationCenter;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class SyncWaiter implements NotificationCenter.NotificationCenterDelegate {

    private static final long DEFAULT_TIMEOUT_SECONDS = 300L;

    protected final int currentAccount;
    protected final ArrayList<Integer> notifications = new ArrayList<>();

    private final CountDownLatch latch = new CountDownLatch(1);
    private final AtomicBoolean subscribed = new AtomicBoolean(false);
    private final AtomicBoolean released = new AtomicBoolean(false);

    private volatile boolean timedOut;

    protected SyncWaiter(int currentAccount) {
        this.currentAccount = currentAccount;
    }

    public void subscribe() {
        if (notifications.isEmpty()) {
            throw new IllegalStateException("No notifications configured");
        }
        runOnUiThreadBlocking(() -> {
            if (!subscribed.compareAndSet(false, true)) {
                return;
            }
            for (int i = 0; i < notifications.size(); i++) {
                NotificationCenter.getInstance(currentAccount).addObserver(this, notifications.get(i));
            }
        });
    }

    protected void unsubscribe() {
        release();
    }

    protected final void release() {
        if (!released.compareAndSet(false, true)) {
            return;
        }
        runOnUiThreadBlocking(() -> {
            if (!subscribed.compareAndSet(true, false)) {
                return;
            }
            for (int i = 0; i < notifications.size(); i++) {
                NotificationCenter.getInstance(currentAccount).removeObserver(this, notifications.get(i));
            }
        });
        latch.countDown();
    }

    public boolean await() {
        boolean completed;
        try {
            completed = latch.await(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            completed = false;
        }
        timedOut = !completed;
        if (!completed) {
            release();
        }
        return completed;
    }

    public boolean isTimedOut() {
        return timedOut;
    }

    protected final boolean isReleased() {
        return released.get();
    }

    private void runOnUiThreadBlocking(Runnable action) {
        if (Thread.currentThread() == ApplicationLoader.applicationHandler.getLooper().getThread()) {
            action.run();
            return;
        }
        CountDownLatch done = new CountDownLatch(1);
        AndroidUtilities.runOnUIThread(() -> {
            try {
                action.run();
            } finally {
                done.countDown();
            }
        });
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
