package com.radolyn.ayugram.utils.seq;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.HashSet;

public class DummyFileUploadWaiter extends SyncWaiter {
    private final HashSet<String> trackedUploadPaths = new HashSet<>();

    public DummyFileUploadWaiter(int currentAccount) {
        super(currentAccount);
    }

    public void clear() {
        trackedUploadPaths.clear();
    }

    public void addUploadPaths(ArrayList<String> uploadPaths) {
        if (uploadPaths == null) {
            return;
        }
        for (int i = 0; i < uploadPaths.size(); i++) {
            String path = uploadPaths.get(i);
            if (!TextUtils.isEmpty(path)) {
                trackedUploadPaths.add(path);
            }
        }
    }

    public boolean matchesTrackedUploadPath(String path) {
        if (TextUtils.isEmpty(path) || trackedUploadPaths.isEmpty()) {
            return false;
        }
        if (trackedUploadPaths.contains(path)) {
            return true;
        }
        for (String trackedPath : trackedUploadPaths) {
            if (TextUtils.isEmpty(trackedPath)) {
                continue;
            }
            if (trackedPath.contains(path) || path.contains(trackedPath) || trackedPath.endsWith(path) || path.endsWith(trackedPath)) {
                return true;
            }
        }
        return false;
    }
}
