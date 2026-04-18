package com.radolyn.ayugram.utils.seq;

import android.text.TextUtils;

import com.radolyn.ayugram.controllers.AyuAttachments;
import com.radolyn.ayugram.utils.AyuMessageUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;

public abstract class AyuSequentialUtils {

    @FunctionalInterface
    public interface DispatchAction {
        void dispatch();
    }

    private AyuSequentialUtils() {
    }

    public static boolean loadDocumentsSync(int currentAccount, ArrayList<MessageObject> messages) {
        if (messages == null || messages.isEmpty()) {
            return true;
        }

        FileLoader fileLoader = FileLoader.getInstance(currentAccount);
        DummyFileDownloadWaiter waiter = new DummyFileDownloadWaiter(currentAccount);
        ArrayList<Runnable> loadActions = new ArrayList<>();

        for (int i = 0; i < messages.size(); i++) {
            MessageObject messageObject = messages.get(i);
            if (!AyuMessageUtils.isMediaDownloadable(messageObject, false) || hasLocalCopy(currentAccount, messageObject)) {
                continue;
            }

            String trackingKey = getDownloadTrackingKey(messageObject);
            if (TextUtils.isEmpty(trackingKey)) {
                continue;
            }

            waiter.trackPendingDownload(trackingKey);
            if (fileLoader.isLoadingFile(trackingKey)) {
                continue;
            }

            Runnable loadAction = createLoadAction(currentAccount, messageObject);
            if (loadAction != null) {
                loadActions.add(loadAction);
            } else {
                waiter.clearPendingDownload(trackingKey);
            }
        }

        if (!waiter.hasPendingDownloads()) {
            return true;
        }

        waiter.subscribe();
        for (int i = 0; i < loadActions.size(); i++) {
            AndroidUtilities.runOnUIThread(loadActions.get(i));
        }
        waiter.await();

        return allLocalCopiesReady(currentAccount, messages);
    }

    public static boolean dispatchSendSync(int currentAccount, long targetDialogId, String uploadPath, boolean waitForMessage, boolean waitForUpload, DispatchAction action) {
        DummyMessageWaiter messageWaiter = waitForMessage ? new DummyMessageWaiter(currentAccount) : null;
        DummyFileUploadWaiter uploadWaiter = waitForUpload && !TextUtils.isEmpty(uploadPath) ? new DummyFileUploadWaiter(currentAccount, uploadPath) : null;
        long dialogId = 0L;
        ArrayList<Integer> existingSendingIds = null;

        if (messageWaiter != null) {
            dialogId = resolveDialogId(currentAccount, targetDialogId);
            existingSendingIds = SendMessagesHelper.getInstance(currentAccount).getSendingMessageIds(dialogId);
            messageWaiter.subscribe();
        }
        if (uploadWaiter != null) {
            uploadWaiter.subscribe();
        }

        AndroidUtilities.runOnUIThread(action::dispatch);

        if (messageWaiter != null) {
            messageWaiter.trySetSendingId(dialogId, existingSendingIds);
        }
        if (uploadWaiter != null) {
            if (messageWaiter != null) {
                uploadWaiter.setMessageId(messageWaiter.sendingId);
            }
            uploadWaiter.await();
        }
        if (messageWaiter != null) {
            messageWaiter.await();
        }

        return true;
    }

    private static long resolveDialogId(int currentAccount, long targetDialogId) {
        TLRPC.InputPeer inputPeer = MessagesController.getInstance(currentAccount).getInputPeer(targetDialogId);
        return inputPeer != null ? DialogObject.getPeerDialogId(inputPeer) : targetDialogId;
    }

    private static Runnable createLoadAction(int currentAccount, MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return null;
        }

        FileLoader fileLoader = FileLoader.getInstance(currentAccount);
        if (messageObject.getDocument() != null) {
            TLRPC.Document document = messageObject.getDocument();
            return () -> fileLoader.loadFile(document, messageObject, FileLoader.PRIORITY_NORMAL, 0);
        }

        if (messageObject.isPhoto()) {
            TLRPC.Photo photo = MessageObject.getPhoto(messageObject.messageOwner);
            if (photo == null) {
                return null;
            }
            TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, AndroidUtilities.getPhotoSize(true));
            ImageLocation imageLocation = size != null ? ImageLocation.getForPhoto(size, photo) : null;
            if (imageLocation == null) {
                return null;
            }
            return () -> fileLoader.loadFile(imageLocation, messageObject, null, FileLoader.PRIORITY_NORMAL, 0);
        }

        if (messageObject.isVideo() && messageObject.messageOwner.media instanceof TLRPC.TL_messageMediaVideo_old) {
            TLRPC.TL_messageMediaVideo_old videoMedia = (TLRPC.TL_messageMediaVideo_old) messageObject.messageOwner.media;
            if (videoMedia.video_unused == null || videoMedia.video_unused.thumb == null) {
                return null;
            }
            ImageLocation imageLocation = ImageLocation.getForObject(videoMedia.video_unused.thumb, messageObject.messageOwner);
            if (imageLocation == null) {
                return null;
            }
            return () -> fileLoader.loadFile(imageLocation, messageObject.messageOwner, "jpg", FileLoader.PRIORITY_NORMAL, 0);
        }

        return null;
    }

    private static boolean hasLocalCopy(int currentAccount, MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return false;
        }
        String path = AyuAttachments.getInstance(currentAccount).getExistingPath(messageObject, false);
        if (TextUtils.isEmpty(path) || "/".equals(path)) {
            return false;
        }
        File file = new File(path);
        return file.exists() && !file.isDirectory();
    }

    private static boolean allLocalCopiesReady(int currentAccount, ArrayList<MessageObject> messages) {
        if (messages == null) {
            return true;
        }
        for (int i = 0; i < messages.size(); i++) {
            MessageObject messageObject = messages.get(i);
            if (AyuMessageUtils.isMediaDownloadable(messageObject, false) && !hasLocalCopy(currentAccount, messageObject)) {
                return false;
            }
        }
        return true;
    }

    private static String getDownloadTrackingKey(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return null;
        }
        if (messageObject.getDocument() != null) {
            return FileLoader.getAttachFileName(messageObject.getDocument());
        }
        if (messageObject.isPhoto()) {
            TLRPC.Photo photo = MessageObject.getPhoto(messageObject.messageOwner);
            if (photo == null) {
                return null;
            }
            TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, AndroidUtilities.getPhotoSize(true));
            return size != null ? FileLoader.getAttachFileName(size) : null;
        }
        if (messageObject.isVideo() && messageObject.messageOwner.media instanceof TLRPC.TL_messageMediaVideo_old) {
            TLRPC.TL_messageMediaVideo_old videoMedia = (TLRPC.TL_messageMediaVideo_old) messageObject.messageOwner.media;
            if (videoMedia.video_unused != null && videoMedia.video_unused.thumb != null) {
                return FileLoader.getAttachFileName(videoMedia.video_unused.thumb);
            }
        }
        return null;
    }

    private static boolean needsLocalCopy(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return false;
        }
        if (messageObject.type == MessageObject.TYPE_TEXT || messageObject.isAnimatedEmoji()) {
            return false;
        }
        if (messageObject.isPhoto() || messageObject.isVideo() || messageObject.isGif()) {
            return true;
        }
        if (messageObject.isAyuDeleted()) {
            return messageObject.getDocument() != null;
        }
        return messageObject.getDocument() != null && !messageObject.isSticker() && !messageObject.isAnimatedSticker();
    }
}
