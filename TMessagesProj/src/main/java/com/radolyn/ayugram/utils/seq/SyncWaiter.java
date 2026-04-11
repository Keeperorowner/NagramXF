package com.radolyn.ayugram.utils.seq;

import org.telegram.messenger.NotificationCenter;

public abstract class SyncWaiter implements NotificationCenter.NotificationCenterDelegate {
    protected final int currentAccount;

    protected SyncWaiter(int currentAccount) {
        this.currentAccount = currentAccount;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
    }
}
