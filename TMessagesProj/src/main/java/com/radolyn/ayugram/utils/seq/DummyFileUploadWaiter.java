package com.radolyn.ayugram.utils.seq;

import android.text.TextUtils;

import org.telegram.messenger.NotificationCenter;

public class DummyFileUploadWaiter extends SyncWaiter {

    private final String path;
    private volatile boolean failed;
    private int messageId;

    public DummyFileUploadWaiter(int currentAccount, String path) {
        super(currentAccount);
        this.path = path;
        notifications.add(NotificationCenter.fileUploaded);
        notifications.add(NotificationCenter.fileUploadFailed);
        notifications.add(NotificationCenter.filePreparingFailed);
        notifications.add(NotificationCenter.messageReceivedByServer);
    }

    public void setMessageId(int messageId) {
        this.messageId = messageId;
    }

    public boolean hasFailed() {
        return failed || isTimedOut();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.fileUploaded || id == NotificationCenter.fileUploadFailed) {
            if (args == null || args.length == 0 || !(args[0] instanceof String)) {
                return;
            }
            process((String) args[0], id == NotificationCenter.fileUploadFailed);
            return;
        }

        if (id == NotificationCenter.filePreparingFailed) {
            if (args == null || args.length < 2 || !(args[1] instanceof String)) {
                return;
            }
            process((String) args[1], true);
            return;
        }

        if (id == NotificationCenter.messageReceivedByServer && args != null && args.length > 0 && args[0] instanceof Integer) {
            if (messageId != 0 && messageId == (Integer) args[0]) {
                unsubscribe();
            }
        }
    }

    private void process(String value, boolean failure) {
        if (!matches(value)) {
            return;
        }
        failed = failure;
        unsubscribe();
    }

    private boolean matches(String value) {
        if (TextUtils.isEmpty(path) || TextUtils.isEmpty(value)) {
            return false;
        }
        return path.equals(value)
                || path.contains(value)
                || value.endsWith(path);
    }
}
