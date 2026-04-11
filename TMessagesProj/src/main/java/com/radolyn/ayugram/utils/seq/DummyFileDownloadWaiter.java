package com.radolyn.ayugram.utils.seq;

import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MessageObject;

import java.util.ArrayList;
import java.util.HashSet;

public class DummyFileDownloadWaiter extends SyncWaiter {
    private final HashSet<String> pendingDownloadKeys = new HashSet<>();

    public DummyFileDownloadWaiter(int currentAccount) {
        super(currentAccount);
    }

    public void trackPendingDownload(String fileName) {
        if (fileName != null && !fileName.isEmpty()) {
            pendingDownloadKeys.add(fileName);
        }
    }

    public void clearPendingDownload(String fileName) {
        if (fileName != null && !fileName.isEmpty()) {
            pendingDownloadKeys.remove(fileName);
        }
    }

    public void clear() {
        pendingDownloadKeys.clear();
    }

    public void cancelPendingDownloads() {
        if (pendingDownloadKeys.isEmpty()) {
            return;
        }
        FileLoader.getInstance(currentAccount).cancelLoadFiles(new ArrayList<>(pendingDownloadKeys));
        pendingDownloadKeys.clear();
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
}
