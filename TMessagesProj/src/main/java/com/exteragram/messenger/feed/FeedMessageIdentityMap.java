package com.exteragram.messenger.feed;

import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;

import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps real (dialogId, messageId) pairs to synthetic message ids so the feed
 * timeline can expose a flat, collision-free id space to RecyclerView-based
 * chat views. Also tracks the primary message of each grouped album.
 */
final class FeedMessageIdentityMap {
    private final HashMap<MessageCompositeID, Integer> generatedIds = new HashMap<>();
    private final ConcurrentHashMap<Integer, MessageCompositeID> realIdsByGeneratedId = new ConcurrentHashMap<>();
    private final HashMap<MessageCompositeID, MessageObject> messagesByRealId = new HashMap<>();
    private final HashMap<GroupKey, MessageObject> primaryByGroup = new HashMap<>();
    private int lastGeneratedId = Integer.MAX_VALUE - 10;

    public boolean register(MessageObject message) {
        message.reactionsLastCheckTime = Long.MAX_VALUE;
        MessageCompositeID compositeID = new MessageCompositeID(message.messageOwner);
        int realId = message.messageOwner.id;
        Integer generatedId = generatedIds.get(compositeID);
        if (generatedId == null) {
            generatedId = lastGeneratedId--;
            generatedIds.put(compositeID, generatedId);
        }
        realIdsByGeneratedId.put(generatedId, compositeID);
        boolean isNew;
        if (messagesByRealId.containsKey(compositeID)) {
            isNew = false;
        } else {
            updatePrimaryGroupFlag(message, compositeID.dialog_id, realId);
            messagesByRealId.put(compositeID, message);
            isNew = true;
        }
        TLRPC.Message owner = message.messageOwner;
        owner.realId = realId;
        owner.id = generatedId;
        return isNew;
    }

    public void replace(MessageObject message) {
        message.reactionsLastCheckTime = Long.MAX_VALUE;
        MessageCompositeID compositeID = new MessageCompositeID(message.getDialogId(), message.getRealId());
        generatedIds.put(compositeID, message.getId());
        realIdsByGeneratedId.put(message.getId(), compositeID);
        MessageObject previous = messagesByRealId.put(compositeID, message);
        if (message.hasValidGroupId()) {
            GroupKey groupKey = new GroupKey(compositeID.dialog_id, message.messageOwner.grouped_id);
            if (previous == null || primaryByGroup.get(groupKey) != previous) {
                return;
            }
            primaryByGroup.put(groupKey, message);
        }
    }

    public void releaseRow(MessageObject message) {
        messagesByRealId.remove(new MessageCompositeID(message.getDialogId(), message.getRealId()));
        if (message.hasValidGroupId()) {
            GroupKey groupKey = new GroupKey(message.getDialogId(), message.messageOwner.grouped_id);
            if (primaryByGroup.get(groupKey) == message) {
                primaryByGroup.remove(groupKey);
            }
        }
    }

    public void purge(MessageObject message) {
        MessageCompositeID compositeID = new MessageCompositeID(message.getDialogId(), message.getRealId());
        generatedIds.remove(compositeID);
        messagesByRealId.remove(compositeID);
        realIdsByGeneratedId.remove(message.getId());
        if (message.hasValidGroupId()) {
            GroupKey groupKey = new GroupKey(compositeID.dialog_id, message.messageOwner.grouped_id);
            if (primaryByGroup.get(groupKey) == message) {
                primaryByGroup.remove(groupKey);
            }
        }
    }

    public MessageObject getByRealId(long dialogId, int id) {
        return messagesByRealId.get(new MessageCompositeID(dialogId, id));
    }

    public MessageObject getByAnyId(long dialogId, int id) {
        MessageObject message = messagesByRealId.get(new MessageCompositeID(dialogId, id));
        if (message != null) {
            return message;
        }
        int realId = resolveRealMessageId(dialogId, id);
        if (realId != id) {
            return messagesByRealId.get(new MessageCompositeID(dialogId, realId));
        }
        return null;
    }

    public int resolveRealMessageId(long dialogId, int id) {
        MessageCompositeID compositeID = realIdsByGeneratedId.get(id);
        return compositeID == null || compositeID.dialog_id != dialogId ? id : compositeID.id;
    }

    public long resolveRealDialogId(int id) {
        MessageCompositeID compositeID = realIdsByGeneratedId.get(id);
        return compositeID != null ? compositeID.dialog_id : 0L;
    }

    public boolean isEmpty() {
        return realIdsByGeneratedId.isEmpty();
    }

    public void clear() {
        generatedIds.clear();
        realIdsByGeneratedId.clear();
        messagesByRealId.clear();
        primaryByGroup.clear();
        lastGeneratedId = Integer.MAX_VALUE - 10;
    }

    private void updatePrimaryGroupFlag(MessageObject message, long dialogId, int id) {
        if (!message.hasValidGroupId()) {
            message.isPrimaryGroupMessage = false;
            return;
        }
        GroupKey groupKey = new GroupKey(dialogId, message.messageOwner.grouped_id);
        MessageObject current = primaryByGroup.get(groupKey);
        if (current == null || id > current.getRealId()) {
            message.isPrimaryGroupMessage = true;
            if (current != null) {
                current.isPrimaryGroupMessage = false;
            }
            primaryByGroup.put(groupKey, message);
            return;
        }
        message.isPrimaryGroupMessage = false;
    }

    public static final class GroupKey {
        final long dialog_id;
        final long groupedId;

        public GroupKey(long dialogId, long groupedId) {
            this.dialog_id = dialogId;
            this.groupedId = groupedId;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj != null && GroupKey.class == obj.getClass()) {
                GroupKey other = (GroupKey) obj;
                return dialog_id == other.dialog_id && groupedId == other.groupedId;
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(dialog_id) * 31 + Long.hashCode(groupedId);
        }
    }

    public static final class MessageCompositeID {
        final long dialog_id;
        final int id;

        public MessageCompositeID(TLRPC.Message message) {
            this.dialog_id = MessageObject.getDialogId(message);
            this.id = message.id;
        }

        public MessageCompositeID(long dialogId, int id) {
            this.dialog_id = dialogId;
            this.id = id;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj != null && MessageCompositeID.class == obj.getClass()) {
                MessageCompositeID other = (MessageCompositeID) obj;
                return dialog_id == other.dialog_id && id == other.id;
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(dialog_id) * 31 + id;
        }
    }
}
