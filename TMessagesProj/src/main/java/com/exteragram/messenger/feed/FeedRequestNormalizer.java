package com.exteragram.messenger.feed;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rewrites synthetic feed message ids back to real channel message ids inside
 * outgoing TL requests (view counts, reactions, payments, etc.) so requests
 * built from feed rows target the actual channel posts. Also fixes up
 * peer/channel fields when the resolved dialog differs from the request's.
 */
public abstract class FeedRequestNormalizer {
    private static final Field[] EMPTY_FIELDS = new Field[0];
    private static final ClassMetadata EMPTY_METADATA = new ClassMetadata(null, null, null, null, EMPTY_FIELDS);
    private static final ConcurrentHashMap<Class<?>, ClassMetadata> metadataCache = new ConcurrentHashMap<>();

    private static long mergeResolvedDialogIds(long a, long b) {
        if (a == 0) {
            return b;
        }
        if (b == 0 || a == b) {
            return a;
        }
        return 0;
    }

    public static TLObject normalize(int account, TLObject request) {
        FeedController feedController;
        if (request != null && (feedController = FeedController.peekInstance(account)) != null && !feedController.hasNoSyntheticIds() && request.getClass().getName().startsWith("org.telegram.tgnet.")) {
            ClassMetadata metadata = getMetadata(request);
            if (metadata.messageIdFields.length != 0 || metadata.invoiceField != null) {
                normalizeMessageIds(account, feedController, request, metadata);
                normalizeInvoice(account, feedController, getFieldValue(metadata.invoiceField, request));
            }
        }
        return request;
    }

    private static ClassMetadata getMetadata(Object object) {
        if (object == null) {
            return EMPTY_METADATA;
        }
        return metadataCache.computeIfAbsent(object.getClass(), FeedRequestNormalizer::buildMetadata);
    }

    private static ClassMetadata buildMetadata(Class<?> cls) {
        Field[] fields;
        try {
            fields = cls.getFields();
        } catch (Exception unused) {
            fields = EMPTY_FIELDS;
        }
        Field requestPeerField = null;
        ArrayList<Field> messageIdFields = null;
        Field peerField = null;
        Field channelField = null;
        Field invoiceField = null;
        for (Field field : fields) {
            String name = field.getName();
            if ("from_peer".equals(name) && requestPeerField == null) {
                requestPeerField = field;
            } else if ("peer".equals(name) && peerField == null) {
                peerField = field;
            } else if ("channel".equals(name) && channelField == null) {
                channelField = field;
            } else if ("invoice".equals(name) && invoiceField == null) {
                invoiceField = field;
            }
            if (isMessageIdField(field)) {
                if (messageIdFields == null) {
                    messageIdFields = new ArrayList<>();
                }
                messageIdFields.add(field);
            }
        }
        return new ClassMetadata(requestPeerField != null ? requestPeerField : peerField, peerField, channelField, invoiceField, messageIdFields != null ? messageIdFields.toArray(new Field[0]) : EMPTY_FIELDS);
    }

    private static void normalizeMessageIds(int account, FeedController feedController, Object object, ClassMetadata metadata) {
        Field requestPeerField = metadata.requestPeerField;
        long dialogId = getDialogId(requestPeerField, object);
        if (dialogId == 0) {
            dialogId = getDialogId(metadata.peerField, object);
        }
        if (dialogId == 0) {
            dialogId = getChannelDialogId(metadata.channelField, object);
        }
        long resolvedDialogId = normalizeMessageIdFields(feedController, object, metadata);
        if (resolvedDialogId == 0 || resolvedDialogId == dialogId) {
            return;
        }
        if (requestPeerField != null) {
            setInputPeer(account, requestPeerField, object, resolvedDialogId);
        } else if (metadata.channelField != null) {
            setInputChannel(account, metadata.channelField, object, resolvedDialogId);
        }
    }

    private static long normalizeMessageIdFields(FeedController feedController, Object object, ClassMetadata metadata) {
        long merged = 0;
        if (object == null) {
            return 0;
        }
        for (Field field : metadata.messageIdFields) {
            merged = mergeResolvedDialogIds(merged, normalizeMessageIdField(feedController, object, field));
        }
        return merged;
    }

    private static boolean isMessageIdField(Field field) {
        if (field == null || Modifier.isStatic(field.getModifiers())) {
            return false;
        }
        String name = field.getName();
        return "id".equals(name) || "msg_id".equals(name) || name.endsWith("_msg_id");
    }

    private static void normalizeInvoice(int account, FeedController feedController, Object object) {
        if (object instanceof TLRPC.TL_inputInvoiceMessage) {
            normalizeMessageIds(account, feedController, object, getMetadata(object));
        }
    }

    private static long normalizeMessageIdField(FeedController feedController, Object object, Field field) {
        try {
            Object value = field.get(object);
            if (value instanceof Integer) {
                Integer id = (Integer) value;
                long realDialogId = feedController.resolveRealDialogId(id);
                if (realDialogId == 0) {
                    return 0;
                }
                field.setInt(object, feedController.resolveRealMessageId(realDialogId, id));
                return realDialogId;
            }
            if (!(value instanceof ArrayList)) {
                return 0;
            }
            ArrayList list = (ArrayList) value;
            long merged = 0;
            for (int i = 0; i < list.size(); i++) {
                try {
                    Object item = list.get(i);
                    if (item instanceof Integer) {
                        Integer id = (Integer) item;
                        long realDialogId = feedController.resolveRealDialogId(id);
                        if (realDialogId != 0) {
                            list.set(i, feedController.resolveRealMessageId(realDialogId, id));
                            merged = mergeResolvedDialogIds(merged, realDialogId);
                        }
                    }
                } catch (Exception unused) {
                    return merged;
                }
            }
            return merged;
        } catch (Exception unused) {
            return 0;
        }
    }

    private static void setInputPeer(int account, Field field, Object object, long dialogId) {
        if (account < 0) {
            return;
        }
        try {
            TLRPC.InputPeer inputPeer = MessagesController.getInstance(account).getInputPeer(dialogId);
            if (inputPeer != null) {
                field.set(object, inputPeer);
            }
        } catch (Exception unused) {
        }
    }

    private static void setInputChannel(int account, Field field, Object object, long dialogId) {
        if (account < 0 || dialogId >= 0) {
            return;
        }
        try {
            TLRPC.InputChannel inputChannel = MessagesController.getInstance(account).getInputChannel(-dialogId);
            if (inputChannel != null) {
                field.set(object, inputChannel);
            }
        } catch (Exception unused) {
        }
    }

    private static long getDialogId(Field field, Object object) {
        if (field == null) {
            return 0;
        }
        try {
            Object value = field.get(object);
            if (value instanceof TLRPC.InputPeer) {
                return DialogObject.getPeerDialogId((TLRPC.InputPeer) value);
            }
        } catch (Exception unused) {
        }
        return 0;
    }

    private static long getChannelDialogId(Field field, Object object) {
        Object value = getFieldValue(field, object);
        if (value instanceof TLRPC.InputChannel) {
            return getInputChannelDialogId((TLRPC.InputChannel) value);
        }
        return 0;
    }

    private static long getInputChannelDialogId(TLRPC.InputChannel inputChannel) {
        if (inputChannel == null) {
            return 0;
        }
        long channelId = inputChannel.channel_id;
        if (channelId == 0) {
            return 0;
        }
        return -channelId;
    }

    private static Object getFieldValue(Field field, Object object) {
        if (field == null) {
            return null;
        }
        try {
            return field.get(object);
        } catch (Exception unused) {
            return null;
        }
    }

    public static final class ClassMetadata {
        final Field channelField;
        final Field invoiceField;
        final Field[] messageIdFields;
        final Field peerField;
        final Field requestPeerField;

        ClassMetadata(Field requestPeerField, Field peerField, Field channelField, Field invoiceField, Field[] messageIdFields) {
            this.requestPeerField = requestPeerField;
            this.peerField = peerField;
            this.channelField = channelField;
            this.invoiceField = invoiceField;
            this.messageIdFields = messageIdFields;
        }
    }
}
