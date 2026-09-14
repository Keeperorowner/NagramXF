package com.radolyn.ayugram.utils;

/** Identifies a message by dialog id and message id. */
public final class AyuMessageKey {
    public final long dialogId;
    public final int messageId;

    public AyuMessageKey(long dialogId, int messageId) {
        this.dialogId = dialogId;
        this.messageId = messageId;
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof AyuMessageKey key && dialogId == key.dialogId && messageId == key.messageId;
    }

    @Override
    public int hashCode() {
        return 31 * Long.hashCode(dialogId) + messageId;
    }
}
