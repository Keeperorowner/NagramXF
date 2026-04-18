package com.radolyn.ayugram.utils.seq;

import android.text.TextUtils;

import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.Utilities;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;

public class DummyFileDownloadWaiter extends SyncWaiter {

    private final HashSet<String> pendingDownloadKeys = new HashSet<>();
    private volatile boolean failed;

    public DummyFileDownloadWaiter(int currentAccount) {
        super(currentAccount);
        notifications.add(NotificationCenter.fileLoaded);
        notifications.add(NotificationCenter.fileLoadFailed);
        notifications.add(NotificationCenter.httpFileDidLoad);
        notifications.add(NotificationCenter.httpFileDidFailedLoad);
    }

    public void trackPendingDownload(String fileName) {
        if (!TextUtils.isEmpty(fileName)) {
            pendingDownloadKeys.add(fileName);
        }
    }

    public void clearPendingDownload(String fileName) {
        if (!TextUtils.isEmpty(fileName)) {
            pendingDownloadKeys.remove(fileName);
        }
    }

    public boolean hasPendingDownloads() {
        return !pendingDownloadKeys.isEmpty();
    }

    public boolean hasFailed() {
        return failed || isTimedOut();
    }

    public void clear() {
        pendingDownloadKeys.clear();
        failed = false;
    }

    public void cancelPendingDownloads() {
        if (pendingDownloadKeys.isEmpty()) {
            return;
        }
        FileLoader.getInstance(currentAccount).cancelLoadFiles(new ArrayList<>(pendingDownloadKeys));
        pendingDownloadKeys.clear();
        unsubscribe();
    }

    public void resetDownloadCancellationFlags(ArrayList<MessageObject> messagesToSend) {
        if (messagesToSend == null) {
            return;
        }
        for (int i = 0; i < messagesToSend.size(); i++) {
            MessageObject messageObject = messagesToSend.get(i);
            if (messageObject != null) {
                messageObject.loadingCancelled = false;
            }
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (args == null || args.length == 0 || !(args[0] instanceof String)) {
            return;
        }
        final String loadedKey = (String) args[0];
        final boolean failure = id == NotificationCenter.fileLoadFailed || id == NotificationCenter.httpFileDidFailedLoad;
        Utilities.globalQueue.postRunnable(() -> process(loadedKey, failure));
    }

    private void process(String loadedKey, boolean failure) {
        if (TextUtils.isEmpty(loadedKey) || pendingDownloadKeys.isEmpty()) {
            return;
        }
        Iterator<String> iterator = pendingDownloadKeys.iterator();
        while (iterator.hasNext()) {
            String pendingKey = iterator.next();
            if (matches(pendingKey, loadedKey)) {
                if (failure) {
                    failed = true;
                    unsubscribe();
                    return;
                }
                iterator.remove();
            }
        }
        if (pendingDownloadKeys.isEmpty()) {
            unsubscribe();
        }
    }

    private boolean matches(String pendingKey, String loadedKey) {
        if (TextUtils.isEmpty(pendingKey) || TextUtils.isEmpty(loadedKey)) {
            return false;
        }
        return pendingKey.equals(loadedKey)
                || loadedKey.endsWith("/" + pendingKey)
                || loadedKey.endsWith("\\" + pendingKey);
    }
}
