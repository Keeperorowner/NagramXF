package com.radolyn.ayugram.messages;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Snapshot of a cleared history plus the ids that were archived successfully. */
public final class AyuHistoryDeletion {
    public final long dialogId;
    public final int deleteDate;
    public final int minId;
    public final int maxId;
    public final List<Integer> messageIds;
    public final boolean captured;
    private final Set<Integer> savedIds;

    public AyuHistoryDeletion(long dialogId, int deleteDate, int minId, int maxId, List<Integer> messageIds, List<Integer> savedIds, boolean captured) {
        this.dialogId = dialogId;
        this.deleteDate = deleteDate;
        this.minId = minId;
        this.maxId = maxId;
        this.messageIds = Collections.unmodifiableList(new ArrayList<>(messageIds));
        this.savedIds = new HashSet<>(savedIds);
        this.savedIds.retainAll(this.messageIds);
        this.captured = captured;
    }

    public boolean isSaved(int messageId) {
        return affects(messageId) && savedIds.contains(messageId);
    }

    public boolean affects(int messageId) {
        return captured && messageId != 0 && minId <= messageId && messageId <= maxId;
    }
}
