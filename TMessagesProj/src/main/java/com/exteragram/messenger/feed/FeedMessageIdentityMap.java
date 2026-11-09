package com.exteragram.messenger.feed;

import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps real (dialogId, messageId) pairs to synthetic message ids so the feed
 * timeline can expose a flat, collision-free id space to RecyclerView-based
 * chat views. Also tracks the members of each grouped album so the primary
 * message can be re-elected when the previous one is replaced or removed.
 */
final class FeedMessageIdentityMap {
    private final HashMap<MessageCompositeID, Integer> generatedIds = new HashMap<>();
    private final ConcurrentHashMap<Integer, MessageCompositeID> realIdsByGeneratedId = new ConcurrentHashMap<>();
    private final HashMap<MessageCompositeID, MessageObject> messagesByRealId = new HashMap<>();
    private final HashMap<GroupKey, ArrayList<MessageObject>> groupMembers = new HashMap<>();
    private int lastGeneratedId = Integer.MAX_VALUE - 10;

    public boolean register(MessageObject message) {
        message.reactionsLastCheckTime = Long.MAX_VALUE;
        long dialogId = message.getDialogId();
        int realId = message.getRealId();
        MessageCompositeID compositeID = new MessageCompositeID(dialogId, realId);
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
            messagesByRealId.put(compositeID, message);
            addToGroup(message, dialogId);
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
        MessageObject previous = messagesByRealId.get(compositeID);
        if (previous != null && previous != message) {
            removeFromGroup(previous, compositeID.dialog_id);
        }
        generatedIds.put(compositeID, message.getId());
        realIdsByGeneratedId.put(message.getId(), compositeID);
        messagesByRealId.put(compositeID, message);
        addToGroup(message, compositeID.dialog_id);
    }

    public void releaseRow(MessageObject message) {
        MessageCompositeID compositeID = new MessageCompositeID(message.getDialogId(), message.getRealId());
        if (messagesByRealId.get(compositeID) == message) {
            messagesByRealId.remove(compositeID);
        }
        removeFromGroup(message, compositeID.dialog_id);
    }

    public void purge(MessageObject message) {
        MessageCompositeID compositeID = new MessageCompositeID(message.getDialogId(), message.getRealId());
        // Only the stored instance may drop the mappings: a stale object left behind by a
        // replacement must not remove the generated id that the live row still uses.
        if (messagesByRealId.get(compositeID) == message) {
            messagesByRealId.remove(compositeID);
            generatedIds.remove(compositeID);
            realIdsByGeneratedId.remove(message.getId());
        }
        removeFromGroup(message, compositeID.dialog_id);
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
        groupMembers.clear();
        lastGeneratedId = Integer.MAX_VALUE - 10;
    }

    private void addToGroup(MessageObject message, long dialogId) {
        if (!message.hasValidGroupId()) {
            message.isPrimaryGroupMessage = false;
            return;
        }
        GroupKey groupKey = new GroupKey(dialogId, message.messageOwner.grouped_id);
        ArrayList<MessageObject> members = groupMembers.get(groupKey);
        if (members == null) {
            members = new ArrayList<>();
            groupMembers.put(groupKey, members);
        }
        if (indexOfMember(members, message) < 0) {
            members.add(message);
        }
        electPrimary(members);
    }

    private void removeFromGroup(MessageObject message, long dialogId) {
        if (!message.hasValidGroupId()) {
            return;
        }
        GroupKey groupKey = new GroupKey(dialogId, message.messageOwner.grouped_id);
        ArrayList<MessageObject> members = groupMembers.get(groupKey);
        if (members == null) {
            return;
        }
        int index = indexOfMember(members, message);
        if (index < 0) {
            return;
        }
        members.remove(index);
        message.isPrimaryGroupMessage = false;
        if (members.isEmpty()) {
            groupMembers.remove(groupKey);
        } else {
            electPrimary(members);
        }
    }

    /** Group membership is by object identity: ids can be re-registered on new instances. */
    private static int indexOfMember(ArrayList<MessageObject> members, MessageObject message) {
        for (int i = 0; i < members.size(); i++) {
            if (members.get(i) == message) {
                return i;
            }
        }
        return -1;
    }

    /** Elects the highest real message id of the album as its primary message. */
    private static void electPrimary(ArrayList<MessageObject> members) {
        MessageObject primary = null;
        for (int i = 0; i < members.size(); i++) {
            MessageObject member = members.get(i);
            if (primary == null || member.getRealId() > primary.getRealId()) {
                primary = member;
            }
        }
        for (int i = 0; i < members.size(); i++) {
            MessageObject member = members.get(i);
            member.isPrimaryGroupMessage = member == primary;
        }
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
