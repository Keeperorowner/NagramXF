package com.radolyn.ayugram.utils;

import com.radolyn.ayugram.AyuGhostConfig;
import com.radolyn.ayugram.AyuWorker;

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
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            if (error == null) {
                if (response instanceof TLRPC.TL_messages_affectedMessages res) {
                    MessagesController.getInstance(account).processNewDifferenceParams(-1, res.pts, -1, res.pts_count);
                }
                if (internal) FileLog.d("GhostMode: Read-after-send request completed.");
                // Go offline after sending
                if (AyuGhostConfig.isSendOfflinePacketAfterOnline(account) && !internal) {
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
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            if (error == null) {
                if (response instanceof TLRPC.TL_messages_affectedMessages res) {
                    messagesController.processNewDifferenceParams(-1, res.pts, -1, res.pts_count);
                }
                if (internal) FileLog.d("GhostMode: Read-after-send request completed.");
                // Go offline after sending
                if (AyuGhostConfig.isSendOfflinePacketAfterOnline(account) && !internal) {
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

        ConnectionsManager.getInstance(account).sendRequest(offlineRequest, (response, error) -> FileLog.d("GhostMode: Status request completed."));
    }


    public static InterceptResult interceptRequest(TLObject object, RequestDelegate onCompleteOrig) {
        return interceptRequest(object, onCompleteOrig, UserConfig.selectedAccount);
    }


    public static InterceptResult interceptRequest(TLObject object, RequestDelegate onCompleteOrig, int account) {
        Long dialogId = extractDialogId(object);
        boolean readExcluded = dialogId != null && AyuGhostPreferences.getGhostModeReadExclusion(dialogId);
        boolean typingExcluded = dialogId != null && AyuGhostPreferences.getGhostModeTypingExclusion(dialogId);

        // Block typing if disabled
        if (!AyuGhostConfig.isSendUploadProgress(account) && (object instanceof TLRPC.TL_messages_setTyping || object instanceof TLRPC.TL_messages_setEncryptedTyping)) {
            if (!typingExcluded) {
                FileLog.d("GhostMode: Blocking typing status request.");
                return InterceptResult.Blocked(onCompleteOrig);
            }
        }

        // Block read receipts if disabled
        if (!AyuGhostConfig.isSendReadMessagePackets(account) && (isReadMessageRequest(object))) {
            if (!AyuState.getAllowReadPacket() && !readExcluded) {
                FileLog.d("GhostMode: Blocking read status request and sending fake response.");
                sendFakeReadResponse(onCompleteOrig);
                return InterceptResult.Blocked(onCompleteOrig);
            }
        }
        if (!AyuGhostConfig.isSendReadStoriesPackets(account) && isReadStoriesRequest(object)) {
            if (!readExcluded) {
                FileLog.d("GhostMode: Blocking story read request.");
                return InterceptResult.Blocked(onCompleteOrig);
            }
        }

        // Force offline if online status sending disabled
        if (!AyuGhostConfig.isSendOnlinePackets(account) && object instanceof TL_account.updateStatus updateStatus) {
            FileLog.d("GhostMode: Forcing offline status in updateStatus request.");
            updateStatus.offline = true;
        }

        // Handle Mark read after sending
        handleReadAfterSend(object, account);

        // Go offline after sending
        RequestDelegate effectiveOnComplete = handleOfflineAfterSend(object, onCompleteOrig, account);

        return InterceptResult.Proceed(effectiveOnComplete);
    }

    private static void handleReadAfterSend(TLObject object, int account) {
        if (AyuGhostConfig.isMarkReadAfterSend(account) && !AyuGhostConfig.isSendReadMessagePackets(account)) {
            TLRPC.InputPeer peer = extractPeerFromSendObject(object);

            if (peer != null) {
                var dialogId = AyuGhostUtils.getDialogId(peer);
                if (AyuGhostPreferences.getGhostModeReadExclusion(dialogId)) {
                    return;
                }
                MessagesStorage.getInstance(account).getStorageQueue().postRunnable(() ->
                    MessagesStorage.getInstance(account).getDialogMaxMessageId(dialogId, maxId ->
                        markReadOnServer(account, maxId, peer, true)
                    )
                );
            }
        }
    }

    private static RequestDelegate handleOfflineAfterSend(TLObject object, RequestDelegate onCompleteOrig, int account) {
        if (AyuGhostConfig.isSendOfflinePacketAfterOnline(account) && isMessageSendRequest(object)) {
            TLRPC.InputPeer peer = extractPeerFromSendObject(object);
            if (peer != null && AyuGhostPreferences.getGhostModeTypingExclusion(getDialogId(peer))) {
                return onCompleteOrig;
            }
            FileLog.d("GhostMode: Wrapping callback for offline-after-send via AyuWorker.");

            return (response, error) -> {
                if (onCompleteOrig != null) {
                    Utilities.stageQueue.postRunnable(() -> onCompleteOrig.run(response, error));
                }

                FileLog.d("GhostMode: Triggering AyuWorker periodic offline schedule.");
                AyuWorker.setOnline(account, true);
            };
        }
        return onCompleteOrig;
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
                object instanceof TLRPC.TL_messages_getMessagesViews obj && obj.increment;
    }

    private static boolean isReadStoriesRequest(TLObject object) {
        return object instanceof TL_stories.TL_stories_readStories ||
                object instanceof TL_stories.TL_stories_incrementStoryViews;
    }

    private static boolean isMessageSendRequest(TLObject object) {
        return object instanceof TLRPC.TL_messages_sendMessage ||
                object instanceof TLRPC.TL_messages_sendMedia ||
                object instanceof TLRPC.TL_messages_sendMultiMedia;
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
        if (!AyuGhostConfig.isSuggestGhostModeBeforeViewingStory(account)) {
            return false;
        }
        if (AyuGhostConfig.isGhostModeActive(account)) {
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
            AyuGhostConfig.setGhostMode(account, true);
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
