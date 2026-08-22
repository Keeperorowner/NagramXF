package com.radolyn.ayugram.utils;

import com.radolyn.ayugram.controllers.AyuGhostController;
import com.radolyn.ayugram.AyuWorker;
import com.radolyn.ayugram.utils.network.TLRPCWrappedBypass;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.tgnet.tl.TL_forum;
import org.telegram.tgnet.tl.TL_phone;
import org.telegram.tgnet.tl.TL_stories;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Stories.StoriesController;

public class AyuGhostUtils {

    private static final int OFFLINE_DELAY_MS = 1000;

    public static Long getDialogId(TLRPC.InputPeer peer) {
        long dialogId;
        if (peer.chat_id != 0) {
            dialogId = -peer.chat_id;
        } else if (peer.channel_id != 0) {
            dialogId = -peer.channel_id;
        } else {
            dialogId = peer.user_id;
        }

        return dialogId;
    }

    public static Long getDialogId(TLRPC.InputChannel peer) {
        return -peer.channel_id;
    }

    public static Long getDialogId(TLRPC.TL_inputEncryptedChat peer) {
        if (peer == null) {
            return null;
        }
        return (long) DialogObject.getEncryptedChatId(peer.chat_id);
    }

    public static ConnectionsManager getConnectionsManager() {
        return ConnectionsManager.getInstance(UserConfig.selectedAccount);
    }

    public static MessagesController getMessagesController() {
        return MessagesController.getInstance(UserConfig.selectedAccount);
    }

    public static MessagesStorage getMessagesStorage() {
        return MessagesStorage.getInstance(UserConfig.selectedAccount);
    }

    public static void markReadOnServer(int messageId, TLRPC.InputPeer peer, boolean internal) {
        markReadOnServer(UserConfig.selectedAccount, messageId, peer, internal);
    }

    public static void markReadOnServer(int account, int messageId, TLRPC.InputPeer peer, boolean internal) {
        TLObject req;
        if (peer instanceof TLRPC.TL_inputPeerChannel) {
            TLRPC.TL_channels_readHistory request = new TLRPC.TL_channels_readHistory();
            request.channel = MessagesController.getInputChannel(peer);
            request.max_id = messageId;
            req = request;
        } else {
            TLRPC.TL_messages_readHistory request = new TLRPC.TL_messages_readHistory();
            request.peer = peer;
            request.max_id = messageId;
            req = request;
        }

        AyuState.setAllowReadPacket(true, 1);
        ConnectionsManager.getInstance(account).sendRequest(new TLRPCWrappedBypass(req), (response, error) -> {
            if (error == null) {
                if (response instanceof TLRPC.TL_messages_affectedMessages res) {
                    MessagesController.getInstance(account).processNewDifferenceParams(-1, res.pts, -1, res.pts_count);
                }
                if (internal) FileLog.d("GhostMode: Read-after-send request completed.");
                if (AyuGhostController.getInstance(account).isSendOfflinePacketAfterOnline() && !internal) {
                    AyuWorker.setOnline(account, true);
                }
            }
        });
    }

    public static void markReadOnServer(MessageObject message, boolean internal) {
        markReadOnServer(UserConfig.selectedAccount, message, internal);
    }

    public static void markReadOnServer(int account, MessageObject message, boolean internal) {
        int messageId = message.getId();
        long dialogId = message.getDialogId();
        MessagesController messagesController = MessagesController.getInstance(account);
        TLRPC.EncryptedChat encryptedChat = messagesController.getEncryptedChat(DialogObject.getEncryptedChatId(dialogId));
        TLRPC.InputPeer inputPeer = messagesController.getInputPeer(message.messageOwner.peer_id);
        boolean readMessageContents = message.isVoice() || message.isRoundVideo();
        TLObject req;
        if (inputPeer instanceof TLRPC.TL_inputPeerChannel) {
            if (readMessageContents) {
                TLRPC.TL_channels_readMessageContents request = new TLRPC.TL_channels_readMessageContents();
                request.channel = MessagesController.getInputChannel(inputPeer);
                request.id.add(messageId);
                req = request;
            } else {
                TLRPC.TL_channels_readHistory request = new TLRPC.TL_channels_readHistory();
                request.channel = MessagesController.getInputChannel(inputPeer);
                request.max_id = messageId;
                req = request;
            }
        } else if (encryptedChat != null) {
            TLRPC.TL_messages_readEncryptedHistory request = new TLRPC.TL_messages_readEncryptedHistory();
            request.peer = new TLRPC.TL_inputEncryptedChat();
            request.peer.chat_id = encryptedChat.id;
            request.peer.access_hash = encryptedChat.access_hash;
            request.max_date = message.messageOwner.date != 0 ? message.messageOwner.date : ConnectionsManager.getInstance(account).getCurrentTime();
            req = request;
        } else if (readMessageContents) {
            TLRPC.TL_messages_readMessageContents request = new TLRPC.TL_messages_readMessageContents();
            request.id.add(messageId);
            req = request;
        } else {
            TLRPC.TL_messages_readHistory request = new TLRPC.TL_messages_readHistory();
            request.peer = inputPeer;
            request.max_id = messageId;
            req = request;
        }

        AyuState.setAllowReadPacket(true, 1);
        ConnectionsManager.getInstance(account).sendRequest(new TLRPCWrappedBypass(req), (response, error) -> {
            if (error == null) {
                if (response instanceof TLRPC.TL_messages_affectedMessages res) {
                    messagesController.processNewDifferenceParams(-1, res.pts, -1, res.pts_count);
                }
                if (internal) FileLog.d("GhostMode: Read-after-send request completed.");
                if (AyuGhostController.getInstance(account).isSendOfflinePacketAfterOnline() && !internal) {
                    AyuWorker.setOnline(account, true);
                }
            }
        });
    }

    public static void performStatusRequest(Boolean offline) {
        performStatusRequest(UserConfig.selectedAccount, offline);
    }

    public static void performStatusRequest(int account, Boolean offline) {
        TL_account.updateStatus offlineRequest = new TL_account.updateStatus();
        offlineRequest.offline = offline;

        ConnectionsManager.getInstance(account).sendRequest(new TLRPCWrappedBypass(offlineRequest), (response, error) -> FileLog.d("GhostMode: Status request completed."));
    }


    public static InterceptResult interceptRequest(TLObject object, RequestDelegate onCompleteOrig) {
        return interceptRequest(object, onCompleteOrig, UserConfig.selectedAccount);
    }


    public static InterceptResult interceptRequest(TLObject object, RequestDelegate onCompleteOrig, int account) {
        if (object instanceof TLRPCWrappedBypass) {
            return InterceptResult.Proceed(onCompleteOrig);
        }

        Long dialogId = extractDialogId(object);
        int readType = dialogId != null ? AyuGhostPreferences.getReadException(dialogId) : AyuGhostPreferences.TYPE_DEFAULT;
        int typingType = dialogId != null ? AyuGhostPreferences.getTypingException(dialogId) : AyuGhostPreferences.TYPE_DEFAULT;

        if (object instanceof TLRPC.TL_messages_setTyping || object instanceof TLRPC.TL_messages_setEncryptedTyping) {
            boolean block;
            if (!AyuGhostController.getInstance(account).isSendUploadProgress()) {
                block = AyuGhostPreferences.shouldBlockWhenGlobalDisabled(typingType);
            } else {
                block = AyuGhostPreferences.shouldBlockWhenGlobalEnabled(typingType);
            }
            if (block) {
                FileLog.d("GhostMode: Blocking typing status request.");
                return InterceptResult.Blocked(onCompleteOrig);
            }
        }

        if (isReadMessageRequest(object)) {
            boolean block;
            if (!AyuGhostController.getInstance(account).isSendReadMessagePackets()) {
                block = !AyuState.getAllowReadPacket() && AyuGhostPreferences.shouldBlockWhenGlobalDisabled(readType);
            } else {
                block = AyuGhostPreferences.shouldBlockWhenGlobalEnabled(readType);
            }
            if (block) {
                FileLog.d("GhostMode: Blocking read status request and sending fake response.");
                sendFakeReadResponse(onCompleteOrig);
                return InterceptResult.Blocked(onCompleteOrig);
            }
        }
        if (isReadStoriesRequest(object)) {
            boolean block;
            if (!AyuGhostController.getInstance(account).isSendReadStoriesPackets()) {
                block = AyuGhostPreferences.shouldBlockWhenGlobalDisabled(readType);
            } else {
                block = AyuGhostPreferences.shouldBlockWhenGlobalEnabled(readType);
            }
            if (block) {
                FileLog.d("GhostMode: Blocking story read request.");
                return InterceptResult.Blocked(onCompleteOrig);
            }
        }

        if (!AyuGhostController.getInstance(account).isSendOnlinePackets() && object instanceof TL_account.updateStatus updateStatus) {
            FileLog.d("GhostMode: Forcing offline status in updateStatus request.");
            updateStatus.offline = true;
        }

        RequestDelegate effectiveOnComplete = wrapOnComplete(object, onCompleteOrig, account);

        return InterceptResult.Proceed(effectiveOnComplete);
    }

    private static RequestDelegate wrapOnComplete(TLObject object, RequestDelegate onCompleteOrig, int account) {
        boolean markReadAfter = shouldHandleReadAfterSend(object, account);
        boolean goOfflineAfter = shouldHandleOfflineAfterSend(object, account);
        if (!markReadAfter && !goOfflineAfter) {
            return onCompleteOrig;
        }

        TLRPC.InputPeer peer = extractPeerFromSendObject(object);
        return (response, error) -> {
            if (onCompleteOrig != null) {
                Utilities.stageQueue.postRunnable(() -> onCompleteOrig.run(response, error));
            }
            if (markReadAfter && peer != null) {
                var dialogId = AyuGhostUtils.getDialogId(peer);
                MessagesStorage.getInstance(account).getStorageQueue().postRunnable(() ->
                        MessagesStorage.getInstance(account).getDialogMaxMessageId(dialogId, maxId ->
                                markReadOnServer(account, maxId, peer, true)
                        )
                );
            }
            if (goOfflineAfter) {
                FileLog.d("GhostMode: Triggering AyuWorker periodic offline schedule.");
                AyuWorker.setOnline(account, true);
            }
        };
    }

    private static boolean shouldHandleReadAfterSend(TLObject object, int account) {
        return AyuGhostController.getInstance(account).isMarkReadAfterSend()
                && !AyuGhostController.getInstance(account).isSendReadMessagePackets()
                && isAfterActionRequest(object);
    }

    private static boolean shouldHandleOfflineAfterSend(TLObject object, int account) {
        if (!AyuGhostController.getInstance(account).isSendOfflinePacketAfterOnline() || !isOnlineActivityRequest(object, account)) {
            return false;
        }
        TLRPC.InputPeer peer = extractPeerFromSendObject(object);
        if (peer != null && AyuGhostPreferences.getTypingException(getDialogId(peer)) == AyuGhostPreferences.TYPE_FORCE_ALLOW) {
            return false;
        }
        FileLog.d("GhostMode: Wrapping callback for offline-after-send via AyuWorker.");
        return true;
    }

    /**
     * 判定请求是否会在服务端留下活动痕迹（可能把账号标为在线）。
     * 命中的请求完成后由 AyuWorker 在数秒内补发 offline 包，避免 ghost 模式下残留在线状态。
     */
    private static boolean isOnlineActivityRequest(TLObject object, int account) {
        if (isMessageSendRequest(object) || isReadMessageRequest(object)) {
            return true;
        }
        if (AyuGhostController.getInstance(account).isMarkReadAfterSend()
                && (object instanceof TLRPC.TL_messages_sendReaction
                || object instanceof TLRPC.TL_messages_sendPaidReaction
                || object instanceof TLRPC.TL_messages_sendVote)) {
            return true;
        }
        return object instanceof TLRPC.TL_messages_createChat
                || object instanceof TLRPC.TL_channels_createChannel
                || object instanceof TL_forum.TL_messages_createForumTopic
                || object instanceof TL_forum.TL_messages_deleteTopicHistory
                || object instanceof TL_forum.TL_messages_editForumTopic
                || object instanceof TLRPC.TL_channels_leaveChannel
                || object instanceof TLRPC.TL_messages_updatePinnedMessage
                || object instanceof TL_phone.requestCall
                || object instanceof TL_phone.acceptCall
                || object instanceof TL_phone.confirmCall
                || object instanceof TL_stories.TL_stories_sendStory
                || object instanceof TL_stories.TL_stories_sendReaction
                || object instanceof TL_stories.TL_stories_readStories;
    }

    private static boolean isAfterActionRequest(TLObject object) {
        return isMessageSendRequest(object)
                || object instanceof TLRPC.TL_messages_sendReaction
                || object instanceof TLRPC.TL_messages_sendVote;
    }

    private static Long extractDialogId(TLObject object) {
        if (object instanceof TLRPC.TL_messages_setTyping obj) {
            return getDialogId(obj.peer);
        } else if (object instanceof TLRPC.TL_messages_setEncryptedTyping obj) {
            return getDialogId(obj.peer);
        } else if (object instanceof TLRPC.TL_messages_readHistory obj) {
            return getDialogId(obj.peer);
        } else if (object instanceof TLRPC.TL_messages_readEncryptedHistory obj) {
            return getDialogId(obj.peer);
        } else if (object instanceof TLRPC.TL_messages_readDiscussion obj) {
            return getDialogId(obj.peer);
        } else if (object instanceof TLRPC.TL_messages_sendMessage obj) {
            return getDialogId(obj.peer);
        } else if (object instanceof TLRPC.TL_messages_sendMedia obj) {
            return getDialogId(obj.peer);
        } else if (object instanceof TLRPC.TL_messages_sendMultiMedia obj) {
            return getDialogId(obj.peer);
        } else if (object instanceof TLRPC.TL_messages_forwardMessages obj) {
            return getDialogId(obj.to_peer);
        } else if (object instanceof TLRPC.TL_messages_sendInlineBotResult obj) {
            return getDialogId(obj.peer);
        } else if (object instanceof TLRPC.TL_messages_sendReaction obj) {
            return getDialogId(obj.peer);
        } else if (object instanceof TLRPC.TL_messages_sendVote obj) {
            return getDialogId(obj.peer);
        } else if (object instanceof TLRPC.TL_messages_editMessage obj) {
            return getDialogId(obj.peer);
        } else if (object instanceof TLRPC.TL_messages_readSavedHistory obj) {
            return getDialogId(obj.peer);
        } else if (object instanceof TLRPC.TL_messages_markDialogUnread obj) {
            if (obj.peer instanceof TLRPC.TL_inputDialogPeer dialogPeer) {
                return getDialogId(dialogPeer.peer);
            }
            return null;
        } else if (object instanceof TL_stories.TL_stories_readStories obj) {
            return getDialogId(obj.peer);
        } else if (object instanceof TL_stories.TL_stories_incrementStoryViews obj) {
            return getDialogId(obj.peer);
        } else if (object instanceof TLRPC.TL_channels_readHistory obj) {
            return getDialogId(obj.channel);
        } else if (object instanceof TLRPC.TL_channels_readMessageContents obj) {
            return getDialogId(obj.channel);
        } else if (object instanceof TLRPC.TL_messages_getMessagesViews obj) {
            return getDialogId(obj.peer);
        }
        return null;
    }

    private static void sendFakeReadResponse(RequestDelegate onCompleteOrig) {
        var fakeRes = new TLRPC.TL_messages_affectedMessages();
        fakeRes.pts = -1;
        fakeRes.pts_count = 0;
        Utilities.stageQueue.postRunnable(() -> {
            try {
                if (onCompleteOrig != null) {
                    onCompleteOrig.run(fakeRes, null);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
    }

    private static TLRPC.InputPeer extractPeerFromSendObject(TLObject object) {
        if (object instanceof TLRPC.TL_messages_sendMessage) {
            return ((TLRPC.TL_messages_sendMessage) object).peer;
        } else if (object instanceof TLRPC.TL_messages_sendMedia) {
            return ((TLRPC.TL_messages_sendMedia) object).peer;
        } else if (object instanceof TLRPC.TL_messages_sendMultiMedia) {
            return ((TLRPC.TL_messages_sendMultiMedia) object).peer;
        } else if (object instanceof TLRPC.TL_messages_forwardMessages) {
            return ((TLRPC.TL_messages_forwardMessages) object).to_peer;
        } else if (object instanceof TLRPC.TL_messages_sendInlineBotResult) {
            return ((TLRPC.TL_messages_sendInlineBotResult) object).peer;
        } else if (object instanceof TLRPC.TL_messages_sendReaction) {
            return ((TLRPC.TL_messages_sendReaction) object).peer;
        } else if (object instanceof TLRPC.TL_messages_sendVote) {
            return ((TLRPC.TL_messages_sendVote) object).peer;
        } else if (object instanceof TLRPC.TL_messages_editMessage) {
            return ((TLRPC.TL_messages_editMessage) object).peer;
        }
        return null;
    }

    private static boolean isReadMessageRequest(TLObject object) {
        return object instanceof TLRPC.TL_messages_readHistory ||
                object instanceof TLRPC.TL_messages_readEncryptedHistory ||
                object instanceof TLRPC.TL_messages_readDiscussion ||
                object instanceof TLRPC.TL_messages_readMessageContents ||
                object instanceof TLRPC.TL_channels_readMessageContents ||
                object instanceof TLRPC.TL_channels_readHistory ||
                object instanceof TLRPC.TL_messages_readSavedHistory ||
                object instanceof TLRPC.TL_messages_markDialogUnread ||
                object instanceof TLRPC.TL_messages_getMessagesViews obj && obj.increment;
    }

    private static boolean isReadStoriesRequest(TLObject object) {
        return object instanceof TL_stories.TL_stories_readStories ||
                object instanceof TL_stories.TL_stories_incrementStoryViews;
    }

    private static boolean isMessageSendRequest(TLObject object) {
        return object instanceof TLRPC.TL_messages_sendMessage ||
                object instanceof TLRPC.TL_messages_sendMedia ||
                object instanceof TLRPC.TL_messages_sendMultiMedia ||
                object instanceof TLRPC.TL_messages_forwardMessage ||
                object instanceof TLRPC.TL_messages_forwardMessages ||
                object instanceof TLRPC.TL_messages_sendInlineBotResult ||
                object instanceof TLRPC.TL_messages_sendEncrypted ||
                object instanceof TLRPC.TL_messages_sendEncryptedFile ||
                object instanceof TLRPC.TL_messages_sendEncryptedMultiMedia ||
                object instanceof TLRPC.TL_messages_sendEncryptedService ||
                object instanceof TLRPC.TL_messages_editMessage;
    }

    public record InterceptResult(boolean blockRequest, RequestDelegate effectiveOnComplete) {

        public static InterceptResult Blocked(RequestDelegate originalOnComplete) {
                return new InterceptResult(true, originalOnComplete);
            }

            public static InterceptResult Proceed(RequestDelegate effectiveOnComplete) {
                return new InterceptResult(false, effectiveOnComplete);
            }
        }

    public static boolean maybeSuggestGhostBeforeStory(android.content.Context context, int account, long dialogId, Runnable onProceed) {
        if (!AyuGhostController.getInstance(account).isSuggestGhostModeBeforeViewingStory()) {
            return false;
        }
        if (AyuGhostController.getInstance(account).isGhostModeActive()) {
            return false;
        }
        long selfUserId = UserConfig.getInstance(account).getClientUserId();
        if (dialogId == selfUserId || dialogId == 0) {
            return false;
        }
        StoriesController storiesController = MessagesController.getInstance(account).getStoriesController();
        if (storiesController == null || !storiesController.hasUnreadStories(dialogId)) {
            return false;
        }
        if (context == null) {
            return false;
        }

        AlertDialog dlg = new AlertDialog(context, 0);
        dlg.setTitle(LocaleController.getString(R.string.SuggestGhostModeBeforeStoryTitle));
        dlg.setMessage(LocaleController.getString(R.string.SuggestGhostModeBeforeStoryMessage));
        dlg.setPositiveButton(LocaleController.getString(R.string.SuggestGhostModeBeforeStoryEnable), (d, w) -> {
            AyuGhostController.getInstance(account).setGhostMode(true);
            if (onProceed != null) {
                onProceed.run();
            }
        });
        dlg.setNegativeButton(LocaleController.getString(R.string.SuggestGhostModeBeforeStorySkip), (d, w) -> {
            if (onProceed != null) {
                onProceed.run();
            }
        });
        dlg.setOnCancelListener(d -> {
        });
        dlg.show();
        return true;
    }
}
