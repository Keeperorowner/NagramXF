package com.radolyn.ayugram.controllers;

import android.text.TextUtils;

import com.radolyn.ayugram.messages.AyuMessagesController;
import com.radolyn.ayugram.utils.AyuMessageUtils;
import com.radolyn.ayugram.utils.seq.AyuSequentialUtils;
import com.radolyn.ayugram.utils.seq.DummyFileDownloadWaiter;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.secretmedia.EncryptedFileInputStream;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;

import xyz.nextalone.nagram.NaConfig;

public class AyuAttachments {

    private static final AyuAttachments[] INSTANCES = new AyuAttachments[16];

    public static final class MediaPreparationResult {
        public int missingMediaCount;
        public int activeDownloadCount;
        public long downloadedBytes;
        public boolean cancelledByUser;
        public boolean hasUndownloadedAyuDeletedMedia;
    }

    private final int currentAccount;
    private final DummyFileDownloadWaiter downloadWaiter;

    public static AyuAttachments getInstance(int currentAccount) {
        AyuAttachments instance = INSTANCES[currentAccount];
        if (instance != null) {
            return instance;
        }
        synchronized (AyuAttachments.class) {
            instance = INSTANCES[currentAccount];
            if (instance == null) {
                instance = new AyuAttachments(currentAccount);
                INSTANCES[currentAccount] = instance;
            }
        }
        return instance;
    }

    public AyuAttachments(int currentAccount) {
        this(currentAccount, null);
    }

    public AyuAttachments(int currentAccount, DummyFileDownloadWaiter downloadWaiter) {
        this.currentAccount = currentAccount;
        this.downloadWaiter = downloadWaiter;
    }

    public String getExistingPath(TLRPC.Message message, boolean forceLoad) {
        if (message == null) {
            return "/";
        }
        return getExistingPath(new MessageObject(currentAccount, message, false, true), forceLoad);
    }

    public String getExistingPath(MessageObject messageObject) {
        return getExistingPath(messageObject, true);
    }

    public String getExistingPath(MessageObject messageObject, boolean forceLoad) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return "/";
        }

        FileLoader fileLoader = FileLoader.getInstance(messageObject.currentAccount);
        long messageSize = MessageObject.getMessageSize(messageObject.messageOwner);
        String path = messageObject.messageOwner.attachPath;
        if (!TextUtils.isEmpty(path)) {
            File file = new File(path);
            if (!file.exists() || file.isDirectory() || (messageSize > 0 && file.length() != messageSize)) {
                path = null;
            }
        }

        if (TextUtils.isEmpty(path)) {
            String pathToMessage = fileLoader.getPathToMessage(messageObject.messageOwner).toString();
            File file = new File(pathToMessage);
            if (!file.exists() || file.isDirectory() || (messageSize > 0 && file.length() != messageSize)) {
                path = null;
            }
            if (!TextUtils.isEmpty(pathToMessage)) {
                File decrypted = tryEncrypted(file, new File(pathToMessage), !forceLoad);
                if (!"/".equals(decrypted.getAbsolutePath())) {
                    return decrypted.getAbsolutePath();
                }
            }
        }

        if (TextUtils.isEmpty(path) && messageObject.getDocument() != null) {
            path = fileLoader.getPathToAttach(messageObject.getDocument(), null, false).toString();
            File file = new File(path);
            if (!file.exists() || file.isDirectory() || (messageSize > 0 && file.length() != messageSize)) {
                path = null;
            }
        }

        if (TextUtils.isEmpty(path) && messageObject.getDocument() != null) {
            path = fileLoader.getPathToAttach(messageObject.getDocument(), null, true).toString();
            File file = new File(path);
            if (!file.exists() || file.isDirectory() || (messageSize > 0 && file.length() != messageSize)) {
                path = null;
            }
        }

        if (TextUtils.isEmpty(path) && messageObject.messageOwner.media != null && messageObject.messageOwner.media.photo != null) {
            path = fileLoader.getPathToAttach(messageObject.messageOwner.media.photo, null, false).toString();
            File file = new File(path);
            if (!file.exists() || file.isDirectory()) {
                path = null;
            }
        }

        if (TextUtils.isEmpty(path) && messageObject.messageOwner.media != null && messageObject.messageOwner.media.photo != null) {
            path = fileLoader.getPathToAttach(messageObject.messageOwner.media.photo, null, true).toString();
            File file = new File(path);
            if (!file.exists() || file.isDirectory()) {
                path = null;
            }
        }

        TLObject media = MessageObject.getMedia(messageObject.messageOwner);
        if (TextUtils.isEmpty(path) && media != null) {
            path = fileLoader.getPathToAttach(media, null, false).toString();
            File file = new File(path);
            if (!file.exists() || file.isDirectory()) {
                path = null;
            }
        }

        if (forceLoad && TextUtils.isEmpty(path)) {
            if (Thread.currentThread() == ApplicationLoader.applicationHandler.getLooper().getThread()) {
                return "/";
            }
            if (messageSize > 0) {
                long limit = ApplicationLoader.isConnectedToWiFi()
                        ? NaConfig.INSTANCE.getSaveMediaOnWiFiLimit().Long()
                        : NaConfig.INSTANCE.getSaveMediaOnCellularDataLimit().Long();
                if (messageSize > limit) {
                    return "/";
                }
            }
            ArrayList<MessageObject> messages = new ArrayList<>(1);
            messages.add(messageObject);
            AyuSequentialUtils.loadDocumentsSync(messageObject.currentAccount, messages);
            return getExistingPath(messageObject, false);
        }

        if (TextUtils.isEmpty(path) || new File(path).isDirectory()) {
            path = fileLoader.getPathToMessage(messageObject.messageOwner).toString();
        }
        if ((TextUtils.isEmpty(path) || new File(path).isDirectory()) && messageObject.getDocument() != null) {
            path = fileLoader.getPathToAttach(messageObject.getDocument(), null, false).toString();
        }
        if ((TextUtils.isEmpty(path) || new File(path).isDirectory()) && messageObject.getDocument() != null) {
            path = fileLoader.getPathToAttach(messageObject.getDocument(), null, true).toString();
        }
        if ((TextUtils.isEmpty(path) || new File(path).isDirectory()) && media != null) {
            path = fileLoader.getPathToAttach(media, null, false).toString();
        }
        return (TextUtils.isEmpty(path) || new File(path).isDirectory()) ? "/" : path;
    }

    public String resolvePath(MessageObject messageObject) {
        return getExistingPath(messageObject, false);
    }

    public void cancelPendingDownloads() {
        if (downloadWaiter != null) {
            downloadWaiter.cancelPendingDownloads();
        }
    }

    public void resetDownloadCancellationFlags(ArrayList<MessageObject> messagesToSend) {
        if (downloadWaiter != null) {
            downloadWaiter.resetDownloadCancellationFlags(messagesToSend);
        }
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
                if (downloadWaiter != null && !TextUtils.isEmpty(trackingKey)) {
                    downloadWaiter.trackPendingDownload(trackingKey);
                }
                if (!TextUtils.isEmpty(trackingKey) && FileLoader.getInstance(currentAccount).isLoadingFile(trackingKey)) {
                    result.activeDownloadCount++;
                }
                long[] progress = ImageLoader.getInstance().getFileProgressSizes(trackingKey);
                if (progress != null) {
                    result.downloadedBytes += progress[0];
                }
            }
        }
        return result;
    }

    private boolean ensureDownloaded(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null || messageObject.loadingCancelled) {
            return false;
        }

        String path = getExistingPath(messageObject, false);
        if (!TextUtils.isEmpty(path) && !"/".equals(path)) {
            File file = new File(path);
            if (file.exists() && !file.isDirectory()) {
                return true;
            }
        }
        if (messageObject.isAyuDeleted()) {
            return false;
        }

        if (messageObject.getDocument() != null) {
            String fileName = FileLoader.getAttachFileName(messageObject.getDocument());
            if (downloadWaiter != null) {
                downloadWaiter.trackPendingDownload(fileName);
            }
            if (!FileLoader.getInstance(currentAccount).isLoadingFile(fileName)) {
                FileLoader.getInstance(currentAccount).loadFile(messageObject.getDocument(), messageObject, FileLoader.PRIORITY_NORMAL, 0);
            }
            return false;
        }

        if (messageObject.isPhoto()) {
            TLRPC.Photo photo = MessageObject.getPhoto(messageObject.messageOwner);
            if (photo != null) {
                TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, AndroidUtilities.getPhotoSize(true));
                if (size != null) {
                    String fileName = FileLoader.getAttachFileName(size);
                    if (downloadWaiter != null) {
                        downloadWaiter.trackPendingDownload(fileName);
                    }
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
                if (downloadWaiter != null) {
                    downloadWaiter.trackPendingDownload(fileName);
                }
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

    private File tryEncrypted(File source, File target, boolean sizeGuard) {
        File encryptedFile = new File(FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE), source.getName() + ".enc");
        if (!encryptedFile.exists() || (sizeGuard && encryptedFile.length() > 8 * 1024 * 1024L)) {
            return new File("/");
        }
        File keyFile = new File(FileLoader.getInternalCacheDir(), encryptedFile.getName() + ".key");
        if (!keyFile.exists()) {
            return new File("/");
        }
        try (EncryptedFileInputStream inputStream = new EncryptedFileInputStream(encryptedFile, keyFile);
             FileOutputStream outputStream = new FileOutputStream(target)) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, read);
            }
            return target;
        } catch (Exception ignored) {
            return new File("/");
        }
    }
}
