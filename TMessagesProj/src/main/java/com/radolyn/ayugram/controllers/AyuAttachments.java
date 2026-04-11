package com.radolyn.ayugram.controllers;

import android.text.TextUtils;

import com.radolyn.ayugram.utils.AyuMessageUtils;
import com.radolyn.ayugram.utils.seq.DummyFileDownloadWaiter;

import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;

public class AyuAttachments {

    public static final class MediaPreparationResult {
        public int missingMediaCount;
        public int activeDownloadCount;
        public long downloadedBytes;
        public boolean cancelledByUser;
        public boolean hasUndownloadedAyuDeletedMedia;
    }

    private final int currentAccount;
    private final DummyFileDownloadWaiter downloadWaiter;

    public AyuAttachments(int currentAccount, DummyFileDownloadWaiter downloadWaiter) {
        this.currentAccount = currentAccount;
        this.downloadWaiter = downloadWaiter;
    }

    public String resolvePath(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return null;
        }
        return FileLoader.getInstance(currentAccount).getPathToMessage(messageObject.messageOwner).toString();
    }

    public void cancelPendingDownloads() {
        downloadWaiter.cancelPendingDownloads();
    }

    public void resetDownloadCancellationFlags(ArrayList<MessageObject> messagesToSend) {
        downloadWaiter.resetDownloadCancellationFlags(messagesToSend);
    }

    public int countMediaToPrepare(ArrayList<MessageObject> messagesToSend) {
        if (messagesToSend == null || messagesToSend.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < messagesToSend.size(); i++) {
            if (needsLocalCopy(messagesToSend.get(i))) {
                count++;
            }
        }
        return count;
    }

    public MediaPreparationResult prepareMedia(ArrayList<MessageObject> messagesToSend) {
        MediaPreparationResult result = new MediaPreparationResult();
        for (int i = 0; i < messagesToSend.size(); i++) {
            MessageObject messageObject = messagesToSend.get(i);
            if (!needsLocalCopy(messageObject)) {
                continue;
            }
            if (messageObject.loadingCancelled) {
                result.cancelledByUser = true;
                return result;
            }
            if (messageObject.isAyuDeleted() && !AyuMessageUtils.hasLocalForwardCopy(messageObject)) {
                result.hasUndownloadedAyuDeletedMedia = true;
                return result;
            }
            if (!ensureDownloaded(messageObject)) {
                result.missingMediaCount++;
                String trackingKey = getDownloadTrackingKey(messageObject);
                if (!TextUtils.isEmpty(trackingKey)) {
                    downloadWaiter.trackPendingDownload(trackingKey);
                    if (FileLoader.getInstance(currentAccount).isLoadingFile(trackingKey)) {
                        result.activeDownloadCount++;
                    }
                    long[] progress = ImageLoader.getInstance().getFileProgressSizes(trackingKey);
                    if (progress != null) {
                        result.downloadedBytes += progress[0];
                    }
                }
            }
        }
        return result;
    }

    private boolean ensureDownloaded(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null || messageObject.loadingCancelled) {
            return false;
        }

        String path = resolvePath(messageObject);
        if (!TextUtils.isEmpty(path) && new File(path).exists()) {
            return true;
        }
        if (messageObject.isAyuDeleted()) {
            return false;
        }

        if (messageObject.getDocument() != null) {
            String fileName = FileLoader.getAttachFileName(messageObject.getDocument());
            downloadWaiter.trackPendingDownload(fileName);
            if (!FileLoader.getInstance(currentAccount).isLoadingFile(fileName)) {
                FileLoader.getInstance(currentAccount).loadFile(messageObject.getDocument(), messageObject, FileLoader.PRIORITY_NORMAL, 0);
            }
            return false;
        }

        if (messageObject.isPhoto()) {
            TLRPC.Photo photo = MessageObject.getPhoto(messageObject.messageOwner);
            if (photo != null) {
                TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, 1280);
                if (size != null) {
                    String fileName = FileLoader.getAttachFileName(size);
                    downloadWaiter.trackPendingDownload(fileName);
                    if (!FileLoader.getInstance(currentAccount).isLoadingFile(fileName)) {
                        ImageLocation imageLocation = ImageLocation.getForObject(size, messageObject.messageOwner);
                        if (imageLocation != null) {
                            FileLoader.getInstance(currentAccount).loadFile(imageLocation, messageObject.messageOwner, "jpg", FileLoader.PRIORITY_NORMAL, 0);
                        }
                    }
                }
            }
            return false;
        }

        if (messageObject.isVideo() && messageObject.getDocument() == null && messageObject.messageOwner.media instanceof TLRPC.TL_messageMediaVideo_old) {
            TLRPC.TL_messageMediaVideo_old videoMedia = (TLRPC.TL_messageMediaVideo_old) messageObject.messageOwner.media;
            if (videoMedia.video_unused != null && videoMedia.video_unused.thumb != null) {
                String fileName = FileLoader.getAttachFileName(videoMedia.video_unused.thumb);
                downloadWaiter.trackPendingDownload(fileName);
                if (!FileLoader.getInstance(currentAccount).isLoadingFile(fileName)) {
                    ImageLocation imageLocation = ImageLocation.getForObject(videoMedia.video_unused.thumb, messageObject.messageOwner);
                    if (imageLocation != null) {
                        FileLoader.getInstance(currentAccount).loadFile(imageLocation, messageObject.messageOwner, "jpg", FileLoader.PRIORITY_NORMAL, 0);
                    }
                }
            }
        }
        return false;
    }

    private String getDownloadTrackingKey(MessageObject messageObject) {
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
            TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, 1280);
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

    private boolean needsLocalCopy(MessageObject messageObject) {
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
