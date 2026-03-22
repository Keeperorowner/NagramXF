
package tw.nekomimi.nekogram.helpers;

import android.os.SystemClock;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;

public class ForceForward {

    private static class GroupedMediaItem {
        final MessageObject messageObject;
        final String caption;

        GroupedMediaItem(MessageObject messageObject, String caption) {
            this.messageObject = messageObject;
            this.caption = caption;
        }
    }

    public interface CompletionCallback {
        void onComplete(boolean shouldContinue);
    }

    private static final long MEDIA_RETRY_DELAY_MS = 750L;
    private static final long MAX_MEDIA_WAIT_MS = 300_000L;
    private static final long MAX_MEDIA_STALL_TIMEOUT_MS = 30_000L;
    private static final int MAX_GROUP_BATCH_SIZE = 10;
    private static final int STATUS_IDLE = 0;
    private static final int STATUS_LOADING = 1;
    private static final int STATUS_FORWARDING = 2;
    private static final int STATUS_STOPPING = 3;

    private final ChatActivity parentFragment;
    private final int currentAccount;
    private long activeTaskId;
    private int currentStatus = STATUS_IDLE;
    private int totalMessages;
    private int sentMessages;
    private int pendingMedia;
    private String currentStatusDetail;
    private String lastFailureReason;
    private boolean stopRequested;
    private long mediaWaitStartTime;
    private long mediaLastProgressTime;
    private long mediaLastObservedBytes;
    private int mediaLastMissingCount;
    private final HashSet<String> pendingDownloadKeys = new HashSet<>();
    private boolean disposed;

    private static class MediaPreparationResult {
        int missingMediaCount;
        int activeDownloadCount;
        long downloadedBytes;
        boolean cancelledByUser;
    }
    
    public ForceForward(ChatActivity fragment, int account) {
        this.parentFragment = fragment;
        this.currentAccount = account;
    }

    public static boolean isChatNoForwards(MessageObject messageObject) {
        if (messageObject == null || messageObject.currentAccount < 0) {
            return false;
        }
        long chatId = messageObject.getChatId();
        if (chatId < 0) {
            chatId = -chatId;
        }
        if (chatId == 0) {
            return false;
        }
        MessagesController controller = MessagesController.getInstance(messageObject.currentAccount);
        TLRPC.Chat chat = controller.getChat(chatId);
        if (chat == null) {
            return false;
        }
        if (chat.migrated_to != null) {
            TLRPC.Chat migratedTo = controller.getChat(chat.migrated_to.channel_id);
            if (migratedTo != null) {
                return migratedTo.noforwards;
            }
        }
        return chat.noforwards;
    }

    public static boolean isUnforwardable(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return false;
        }
        TLRPC.Message message = messageObject.messageOwner;
        if (messageObject.isAyuDeleted()) {
            return true;
        }
        if (message.noforwards) {
            return true;
        }
        if (message instanceof TLRPC.TL_message_secret || messageObject.isSecretMedia()) {
            return true;
        }
        if (message.ttl != 0) {
            return true;
        }
        if (message.media != null && message.media.ttl_seconds != 0) {
            return true;
        }
        return messageObject.type == MessageObject.TYPE_PAID_MEDIA || message.media instanceof TLRPC.TL_messageMediaPaidMedia;
    }

    public static boolean isForceForwardNeeded(MessageObject messageObject) {
        return isChatNoForwards(messageObject) || isUnforwardable(messageObject);
    }

    private void clearRunState() {
        activeTaskId = 0L;
        currentStatus = STATUS_IDLE;
        totalMessages = 0;
        sentMessages = 0;
        pendingMedia = 0;
        currentStatusDetail = null;
        stopRequested = false;
        mediaWaitStartTime = 0L;
        mediaLastProgressTime = 0L;
        mediaLastObservedBytes = 0L;
        mediaLastMissingCount = 0;
        pendingDownloadKeys.clear();
    }

    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        cancelPendingDownloads();
        clearRunState();
        lastFailureReason = null;
    }

    public boolean isForwarding() {
        return activeTaskId != 0L;
    }

    public String consumeLastFailureReason() {
        String reason = lastFailureReason;
        lastFailureReason = null;
        return reason;
    }

    public boolean stopCurrentRun() {
        if (!isForwarding()) {
            return false;
        }
        stopRequested = true;
        currentStatus = STATUS_STOPPING;
        currentStatusDetail = LocaleController.getString(R.string.ForceForwardStatusStoppingCurrentBatch);
        lastFailureReason = null;
        cancelPendingDownloads();
        notifyStatusChanged();
        return true;
    }

    public String getForwardingStatus() {
        if (!isForwarding()) {
            return null;
        }
        if (currentStatus == STATUS_LOADING) {
            String progress = Math.max(totalMessages - pendingMedia, 0) + "/" + totalMessages;
            return TextUtils.isEmpty(currentStatusDetail) ? LocaleController.getString(R.string.ForceForwardStatusPreparingMedia) + " " + progress : currentStatusDetail + " " + progress;
        }
        if (currentStatus == STATUS_FORWARDING) {
            String progress = sentMessages + "/" + totalMessages;
            return TextUtils.isEmpty(currentStatusDetail) ? LocaleController.getString(R.string.ForceForwardStatusForwarding) + " " + progress : currentStatusDetail + " " + progress;
        }
        if (currentStatus == STATUS_STOPPING) {
            return TextUtils.isEmpty(currentStatusDetail) ? LocaleController.getString(R.string.ForceForwardStatusStopping) : currentStatusDetail;
        }
        return null;
    }

    private void notifyStatusChanged() {
        if (disposed || parentFragment.getParentActivity() == null) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> parentFragment.refreshForceForwardStatus());
    }

    private long startRun(int messageCount) {
        activeTaskId++;
        currentStatus = STATUS_LOADING;
        totalMessages = Math.max(messageCount, 0);
        sentMessages = 0;
        pendingMedia = totalMessages;
        currentStatusDetail = LocaleController.getString(R.string.ForceForwardStatusPreparingMedia);
        lastFailureReason = null;
        stopRequested = false;
        mediaWaitStartTime = 0L;
        mediaLastProgressTime = 0L;
        mediaLastObservedBytes = 0L;
        mediaLastMissingCount = 0;
        notifyStatusChanged();
        return activeTaskId;
    }

    private boolean isTaskActive(long taskId) {
        return activeTaskId == taskId;
    }

    private void updateLoadingState(int missingMedia) {
        currentStatus = STATUS_LOADING;
        pendingMedia = Math.max(missingMedia, 0);
        currentStatusDetail = missingMedia > 0 ? LocaleController.getString(R.string.ForceForwardStatusWaitingDownloads) : LocaleController.getString(R.string.ForceForwardStatusMediaReady);
        notifyStatusChanged();
    }

    private void updateForwardingState() {
        updateForwardingState(null);
    }

    private void updateForwardingState(String detail) {
        currentStatus = STATUS_FORWARDING;
        pendingMedia = 0;
        currentStatusDetail = detail;
        notifyStatusChanged();
    }

    private void setFailureReason(String failureReason) {
        lastFailureReason = failureReason;
    }

    private void failRun(long taskId, String failureReason, CompletionCallback onComplete) {
        setFailureReason(failureReason);
        finishRun(taskId, false, onComplete);
    }

    private void onMessageSent() {
        sentMessages++;
        notifyStatusChanged();
    }

    private void onMessagesSent(int count) {
        if (count <= 0) {
            return;
        }
        sentMessages += count;
        notifyStatusChanged();
    }

    private void onMessageSkipped() {
        if (totalMessages > 0) {
            totalMessages--;
        }
        pendingMedia = Math.min(pendingMedia, totalMessages);
        sentMessages = Math.min(sentMessages, totalMessages);
        notifyStatusChanged();
    }

    private void finishRun(long taskId, boolean shouldContinue, CompletionCallback onComplete) {
        if (disposed) {
            return;
        }
        boolean isCurrentTask = activeTaskId == taskId;
        if (isCurrentTask) {
            clearRunState();
            notifyStatusChanged();
        }
        if (shouldContinue) {
            lastFailureReason = null;
        }
        if (onComplete != null) {
            onComplete.onComplete(isCurrentTask && shouldContinue);
        }
    }
    
    private CharSequence getMessageCaption(MessageObject mo) {
        return parentFragment.getMessageCaption(mo, parentFragment.getValidGroupedMessage(mo), null);
    }

    private CharSequence getForwardCaption(MessageObject mo) {
        if (mo == null) {
            return null;
        }
        if (mo.getGroupId() != 0) {
            return ChatActivity.getMessageCaption(mo, null, null);
        }
        return getMessageCaption(mo);
    }

    private String resolvePath(MessageObject mo) {
        if (mo.messageOwner == null) return null;
        return FileLoader.getInstance(currentAccount).getPathToMessage(mo.messageOwner).toString();
    }

    private void trackPendingDownload(String fileName) {
        if (!TextUtils.isEmpty(fileName)) {
            pendingDownloadKeys.add(fileName);
        }
    }

    private void clearPendingDownload(String fileName) {
        if (!TextUtils.isEmpty(fileName)) {
            pendingDownloadKeys.remove(fileName);
        }
    }

    private void cancelPendingDownloads() {
        if (pendingDownloadKeys.isEmpty()) {
            return;
        }
        FileLoader.getInstance(currentAccount).cancelLoadFiles(new ArrayList<>(pendingDownloadKeys));
        pendingDownloadKeys.clear();
    }

    private void resetDownloadCancellationFlags(ArrayList<MessageObject> messagesToSend) {
        if (messagesToSend == null) {
            return;
        }
        for (int i = 0; i < messagesToSend.size(); i++) {
            MessageObject messageObject = messagesToSend.get(i);
            if (messageObject != null) {
                messageObject.loadingCancelled = false;
            }
        }
    }

    private boolean ensureDownloaded(MessageObject mo) {
        if (mo == null || mo.messageOwner == null) return false;
        if (mo.loadingCancelled) return false;
        
        String path = resolvePath(mo);
        if (!TextUtils.isEmpty(path) && new File(path).exists()) return true;

        if (mo.getDocument() != null) {
            String fileName = FileLoader.getAttachFileName(mo.getDocument());
            trackPendingDownload(fileName);
            if (!FileLoader.getInstance(currentAccount).isLoadingFile(fileName)) {
                FileLoader.getInstance(currentAccount).loadFile(mo.getDocument(), mo, FileLoader.PRIORITY_NORMAL, 0);
            }
            return false;
        }
        
        if (mo.isPhoto()) {
            TLRPC.Photo photo = MessageObject.getPhoto(mo.messageOwner);
            if (photo != null) {
                TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, 1280);
                if (size != null) {
                    String fileName = FileLoader.getAttachFileName(size);
                    trackPendingDownload(fileName);
                    if (!FileLoader.getInstance(currentAccount).isLoadingFile(fileName)) {
                        ImageLocation imageLocation = ImageLocation.getForObject(size, mo.messageOwner);
                        if (imageLocation != null) {
                            FileLoader.getInstance(currentAccount).loadFile(imageLocation, mo.messageOwner, "jpg", FileLoader.PRIORITY_NORMAL, 0);
                        }
                    }
                }
            }
            return false;
        }
        
        if (mo.isVideo()) {
            if (mo.getDocument() == null && mo.messageOwner.media instanceof TLRPC.TL_messageMediaVideo_old) {
                TLRPC.TL_messageMediaVideo_old videoMedia = (TLRPC.TL_messageMediaVideo_old) mo.messageOwner.media;
                 if (videoMedia.video_unused != null && videoMedia.video_unused.thumb != null) {
                     String fileName = FileLoader.getAttachFileName(videoMedia.video_unused.thumb);
                     trackPendingDownload(fileName);
                     if (!FileLoader.getInstance(currentAccount).isLoadingFile(fileName)) {
                         ImageLocation imageLocation = ImageLocation.getForObject(videoMedia.video_unused.thumb, mo.messageOwner);
                         if (imageLocation != null) {
                             FileLoader.getInstance(currentAccount).loadFile(imageLocation, mo.messageOwner, "jpg", FileLoader.PRIORITY_NORMAL, 0);
                         }
                     }
                 }
            }
            return false;
        }
        
        return false;
    }

    private String getDownloadTrackingKey(MessageObject mo) {
        if (mo == null || mo.messageOwner == null) {
            return null;
        }
        if (mo.getDocument() != null) {
            return FileLoader.getAttachFileName(mo.getDocument());
        }
        if (mo.isPhoto()) {
            TLRPC.Photo photo = MessageObject.getPhoto(mo.messageOwner);
            if (photo == null) {
                return null;
            }
            TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, 1280);
            return size != null ? FileLoader.getAttachFileName(size) : null;
        }
        if (mo.isVideo() && mo.messageOwner.media instanceof TLRPC.TL_messageMediaVideo_old) {
            TLRPC.TL_messageMediaVideo_old videoMedia = (TLRPC.TL_messageMediaVideo_old) mo.messageOwner.media;
            if (videoMedia.video_unused != null && videoMedia.video_unused.thumb != null) {
                return FileLoader.getAttachFileName(videoMedia.video_unused.thumb);
            }
        }
        return null;
    }

    private boolean needsLocalCopy(MessageObject mo) {
        if (mo == null || mo.messageOwner == null) {
            return false;
        }
        if (mo.isPhoto() || mo.isVideo() || mo.isGif()) {
            return true;
        }
        return mo.getDocument() != null && !mo.isSticker() && !mo.isAnimatedSticker();
    }

    private MediaPreparationResult prepareMedia(ArrayList<MessageObject> messagesToSend) {
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
            if (!ensureDownloaded(messageObject)) {
                result.missingMediaCount++;
                String trackingKey = getDownloadTrackingKey(messageObject);
                if (!TextUtils.isEmpty(trackingKey)) {
                    trackPendingDownload(trackingKey);
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

    private void sendMediaBatch(ArrayList<SendMessagesHelper.SendingMediaInfo> list, long targetDialogId, boolean isDocument, boolean isGrouping, boolean notify, int scheduleDate, long payStars) {
        MessageObject replyToMsg = parentFragment.getThreadMessage();
        MessageObject replyToTopMsg = parentFragment.getThreadMessage();
        SendMessagesHelper.prepareSendingMedia(
                parentFragment.getAccountInstance(),
                list,
                targetDialogId,
                replyToMsg,
                replyToTopMsg,
                null,
                null,
                isDocument,
                isGrouping,
                null,
                notify,
                scheduleDate,
                0,
                parentFragment.getChatMode(),
                false,
                null,
                parentFragment.quickReplyShortcut,
                parentFragment.getQuickReplyId(),
                0,
                false,
                payStars,
                parentFragment.getSendMonoForumPeerId(),
                parentFragment.getSendMessageSuggestionParams()
        );
    }

    private void applySendContext(SendMessagesHelper.SendMessageParams params, long payStars) {
        if (params == null) {
            return;
        }
        MessageObject replyToMsg = parentFragment.getThreadMessage();
        if (params.replyToMsg == null) {
            params.replyToMsg = replyToMsg;
        }
        if (params.replyToTopMsg == null) {
            params.replyToTopMsg = replyToMsg;
        }
        params.quick_reply_shortcut = parentFragment.quickReplyShortcut;
        params.quick_reply_shortcut_id = parentFragment.getQuickReplyId();
        params.payStars = payStars;
        params.monoForumPeer = parentFragment.getSendMonoForumPeerId();
        params.suggestionParams = parentFragment.getSendMessageSuggestionParams();
    }

    private int getMessageTtl(MessageObject mo) {
        if (mo == null || mo.messageOwner == null || mo.messageOwner.media == null) {
            return 0;
        }
        return mo.messageOwner.media.ttl_seconds;
    }

    private TLRPC.TL_photo mapPhoto(MessageObject mo, String filePath) {
        if (mo == null || mo.messageOwner == null || TextUtils.isEmpty(filePath)) {
            return null;
        }
        TLRPC.Photo source = MessageObject.getPhoto(mo.messageOwner);
        if (source == null) {
            return null;
        }
        TLRPC.TL_photo mapped = new TLRPC.TL_photo();
        mapped.flags = source.flags;
        mapped.has_stickers = source.has_stickers;
        mapped.date = source.date;
        mapped.dc_id = source.dc_id;
        mapped.user_id = source.user_id;
        mapped.geo = source.geo;
        mapped.caption = source.caption;
        mapped.video_sizes = new ArrayList<>(source.video_sizes);
        mapped = parentFragment.getSendMessagesHelper().generatePhotoSizes(mapped, filePath, null, false);
        if (mapped == null || mapped.sizes == null || mapped.sizes.isEmpty()) {
            return null;
        }
        mapped.id = 0;
        mapped.access_hash = 0;
        mapped.file_reference = new byte[0];
        return mapped;
    }

    private TLRPC.TL_document mapDocument(MessageObject mo) {
        TLRPC.Document source = mo != null ? mo.getDocument() : null;
        if (source == null) {
            return null;
        }
        TLRPC.TL_document mapped = new TLRPC.TL_document();
        mapped.flags = source.flags;
        mapped.id = 0;
        mapped.access_hash = 0;
        mapped.file_reference = new byte[0];
        mapped.user_id = source.user_id;
        mapped.date = source.date;
        mapped.file_name = source.file_name;
        mapped.mime_type = source.mime_type;
        mapped.size = source.size;
        mapped.thumbs = new ArrayList<>(source.thumbs);
        mapped.video_thumbs = new ArrayList<>(source.video_thumbs);
        mapped.version = source.version;
        mapped.dc_id = source.dc_id;
        mapped.key = null;
        mapped.iv = null;
        mapped.attributes = new ArrayList<>(source.attributes);
        mapped.file_name_fixed = source.file_name_fixed;
        mapped.localPath = source.localPath;
        mapped.localThumbPath = source.localThumbPath;
        return mapped;
    }

    private HashMap<String, String> createGroupedParams(long groupId, boolean isFinal) {
        HashMap<String, String> params = new HashMap<>();
        if (groupId != 0) {
            params.put("groupId", String.valueOf(groupId));
        }
        if (isFinal) {
            params.put("final", "1");
        }
        return params.isEmpty() ? null : params;
    }

    private SendMessagesHelper.SendMessageParams buildMappedPhotoParams(MessageObject mo, String caption, long targetDialogId, boolean notify, int scheduleDate, HashMap<String, String> extraParams) {
        String filePath = resolvePath(mo);
        TLRPC.TL_photo photo = mapPhoto(mo, filePath);
        if (photo == null) {
            return null;
        }
        ArrayList<TLRPC.MessageEntity> entities = TextUtils.isEmpty(caption) || mo == null || mo.messageOwner == null ? null : mo.messageOwner.entities;
        HashMap<String, String> params = extraParams != null ? new HashMap<>(extraParams) : null;
        return SendMessagesHelper.SendMessageParams.of(photo, filePath, targetDialogId, null, null, caption, entities, null, params, notify, scheduleDate, 0, getMessageTtl(mo), mo, false, mo != null && mo.hasMediaSpoilers());
    }

    private SendMessagesHelper.SendMessageParams buildMappedDocumentParams(MessageObject mo, String caption, long targetDialogId, boolean notify, int scheduleDate, HashMap<String, String> extraParams) {
        String filePath = resolvePath(mo);
        if (TextUtils.isEmpty(filePath)) {
            return null;
        }
        TLRPC.TL_document document = mapDocument(mo);
        if (document == null) {
            return null;
        }
        ArrayList<TLRPC.MessageEntity> entities = TextUtils.isEmpty(caption) || mo == null || mo.messageOwner == null ? null : mo.messageOwner.entities;
        HashMap<String, String> params = extraParams != null ? new HashMap<>(extraParams) : null;
        return SendMessagesHelper.SendMessageParams.of(document, null, filePath, targetDialogId, null, null, caption, entities, null, params, notify, scheduleDate, 0, getMessageTtl(mo), mo, null, false, mo != null && mo.hasMediaSpoilers());
    }

    private void sendMappedParams(SendMessagesHelper.SendMessageParams params, long payStars) {
        if (params == null) {
            return;
        }
        applySendContext(params, payStars);
        parentFragment.getSendMessagesHelper().sendMessage(params);
    }

    private boolean sendMappedPhoto(MessageObject mo, String caption, long targetDialogId, boolean notify, int scheduleDate, long payStars) {
        SendMessagesHelper.SendMessageParams params = buildMappedPhotoParams(mo, caption, targetDialogId, notify, scheduleDate, null);
        if (params == null) {
            return false;
        }
        sendMappedParams(params, payStars);
        return true;
    }

    private boolean sendMappedDocument(MessageObject mo, String caption, long targetDialogId, boolean notify, int scheduleDate, long payStars) {
        SendMessagesHelper.SendMessageParams params = buildMappedDocumentParams(mo, caption, targetDialogId, notify, scheduleDate, null);
        if (params == null) {
            return false;
        }
        sendMappedParams(params, payStars);
        return true;
    }

    private ArrayList<SendMessagesHelper.SendingMediaInfo> createMediaInfoList(ArrayList<GroupedMediaItem> items) {
        ArrayList<SendMessagesHelper.SendingMediaInfo> list = new ArrayList<>();
        if (items == null) {
            return list;
        }
        for (int i = 0; i < items.size(); i++) {
            GroupedMediaItem item = items.get(i);
            SendMessagesHelper.SendingMediaInfo info = createMediaInfo(item.messageObject, item.caption);
            if (item.messageObject != null) {
                info.isVideo = item.messageObject.isVideo() || item.messageObject.isGif();
            }
            list.add(info);
        }
        return list;
    }

    private void sendMappedGroup(ArrayList<GroupedMediaItem> group, long targetDialogId, boolean notify, int scheduleDate, long payStars, boolean documentFallback) {
        if (group == null || group.isEmpty()) {
            return;
        }

        if (group.size() > MAX_GROUP_BATCH_SIZE) {
            for (int start = 0; start < group.size(); start += MAX_GROUP_BATCH_SIZE) {
                int end = Math.min(start + MAX_GROUP_BATCH_SIZE, group.size());
                sendMappedGroup(new ArrayList<>(group.subList(start, end)), targetDialogId, notify, scheduleDate, payStars, documentFallback);
            }
            return;
        }

        String groupLabel = documentFallback ? LocaleController.getString(R.string.ForceForwardStatusDocumentGroup) : LocaleController.getString(R.string.ForceForwardStatusMediaGroup);
        updateForwardingState(groupLabel);

        if (group.size() == 1) {
            GroupedMediaItem item = group.get(0);
            MessageObject messageObject = item.messageObject;
            updateForwardingState(groupLabel + " 1/1");
            boolean sent = messageObject != null && messageObject.isPhoto()
                    ? sendMappedPhoto(messageObject, item.caption, targetDialogId, notify, scheduleDate, payStars)
                    : sendMappedDocument(messageObject, item.caption, targetDialogId, notify, scheduleDate, payStars);
            if (!sent) {
                updateForwardingState(documentFallback ? LocaleController.getString(R.string.ForceForwardStatusDocumentFallback) : LocaleController.getString(R.string.ForceForwardStatusMediaFallback));
                sendMediaBatch(createMediaInfoList(group), targetDialogId, documentFallback, false, notify, scheduleDate, payStars);
            }
            onMessageSent();
            return;
        }

        long groupId;
        do {
            groupId = Utilities.random.nextLong();
        } while (groupId == 0);

        ArrayList<SendMessagesHelper.SendMessageParams> paramsList = new ArrayList<>(group.size());
        for (int i = 0; i < group.size(); i++) {
            GroupedMediaItem item = group.get(i);
            MessageObject messageObject = item.messageObject;
            HashMap<String, String> groupParams = createGroupedParams(groupId, i == group.size() - 1);
            SendMessagesHelper.SendMessageParams params = messageObject != null && messageObject.isPhoto()
                    ? buildMappedPhotoParams(messageObject, item.caption, targetDialogId, notify, scheduleDate, groupParams)
                    : buildMappedDocumentParams(messageObject, item.caption, targetDialogId, notify, scheduleDate, groupParams);
            if (params == null) {
                updateForwardingState(documentFallback ? LocaleController.getString(R.string.ForceForwardStatusDocumentGroupFallback) : LocaleController.getString(R.string.ForceForwardStatusMediaGroupFallback));
                sendMediaBatch(createMediaInfoList(group), targetDialogId, documentFallback, !documentFallback, notify, scheduleDate, payStars);
                onMessagesSent(group.size());
                return;
            }
            paramsList.add(params);
        }

        for (int i = 0; i < paramsList.size(); i++) {
            updateForwardingState(groupLabel + " " + (i + 1) + "/" + paramsList.size());
            sendMappedParams(paramsList.get(i), payStars);
            onMessageSent();
        }
    }

    private void sendDocumentFallback(MessageObject mo, String caption, long targetDialogId, boolean notify, int scheduleDate) {
        String filePath = resolvePath(mo);
        MessageObject replyToMsg = parentFragment.getThreadMessage();
        MessageObject replyToTopMsg = parentFragment.getThreadMessage();
        SendMessagesHelper.prepareSendingDocument(
                parentFragment.getAccountInstance(),
                filePath,
                filePath,
                null,
                caption,
                null,
                targetDialogId,
                replyToMsg,
                replyToTopMsg,
                null,
                null,
                null,
                notify,
                scheduleDate,
                null,
                parentFragment.quickReplyShortcut,
                parentFragment.getQuickReplyId(),
                false
        );
    }

    private void sendLeftoverGroupAsSingles(ArrayList<GroupedMediaItem> group, long targetDialogId, boolean notify, int scheduleDate, long payStars, boolean documentFallback) {
        if (group == null || group.isEmpty()) {
            return;
        }
        FileLog.w("ForceForward: degraded leftover " + (documentFallback ? "document" : "media") + " group to singles");
        for (int i = 0; i < group.size(); i++) {
            GroupedMediaItem item = group.get(i);
            MessageObject messageObject = item.messageObject;
            if (messageObject == null) {
                onMessageSkipped();
                continue;
            }
            String copyLabel;
            String fallbackLabel;
            boolean sent;
            if (documentFallback) {
                copyLabel = LocaleController.getString(R.string.ForceForwardStatusDocumentCopy);
                fallbackLabel = LocaleController.getString(R.string.ForceForwardStatusDocumentFallback);
                updateForwardingState(copyLabel);
                sent = sendMappedDocument(messageObject, item.caption, targetDialogId, notify, scheduleDate, payStars);
                if (!sent) {
                    updateForwardingState(fallbackLabel);
                    sendDocumentFallback(messageObject, item.caption, targetDialogId, notify, scheduleDate);
                }
            } else {
                copyLabel = messageObject.isPhoto()
                        ? LocaleController.getString(R.string.ForceForwardStatusPhotoCopy)
                        : LocaleController.getString(R.string.ForceForwardStatusMediaCopy);
                fallbackLabel = LocaleController.getString(R.string.ForceForwardStatusMediaFallback);
                updateForwardingState(copyLabel);
                sent = messageObject.isPhoto()
                        ? sendMappedPhoto(messageObject, item.caption, targetDialogId, notify, scheduleDate, payStars)
                        : sendMappedDocument(messageObject, item.caption, targetDialogId, notify, scheduleDate, payStars);
                if (!sent) {
                    updateForwardingState(fallbackLabel);
                    ArrayList<SendMessagesHelper.SendingMediaInfo> one = new ArrayList<>(1);
                    SendMessagesHelper.SendingMediaInfo info = createMediaInfo(messageObject, item.caption);
                    info.isVideo = messageObject.isVideo() || messageObject.isGif();
                    one.add(info);
                    sendMediaBatch(one, targetDialogId, false, false, notify, scheduleDate, payStars);
                }
            }
            onMessageSent();
        }
    }

    private void addToGroup(long gid,
                            GroupedMediaItem item,
                            HashMap<Long, ArrayList<GroupedMediaItem>> map,
                            HashMap<Long, Integer> remain,
                            boolean document,
                            long targetDialogId,
                            boolean notify,
                            int scheduleDate,
                            long payStars) {
        ArrayList<GroupedMediaItem> list = map.computeIfAbsent(gid, k -> new ArrayList<>());
        list.add(item);
        Integer r = remain.get(gid);
        if (r != null) {
            r = r - 1;
            if (r <= 0) {
                map.remove(gid);
                remain.remove(gid);
                sendMappedGroup(new ArrayList<>(list), targetDialogId, notify, scheduleDate, payStars, document);
            } else {
                remain.put(gid, r);
            }
        }
    }
    
    public void runForceForward(ArrayList<MessageObject> messagesToSend, long targetDialogId, boolean showUndo, boolean hideCaption, boolean notify, int scheduleDate, long payStars, CompletionCallback onComplete) {
        if (disposed || messagesToSend == null || messagesToSend.isEmpty() || parentFragment.getParentActivity() == null) {
            setFailureReason(null);
            if (onComplete != null) {
                onComplete.onComplete(false);
            }
            return;
        }
        resetDownloadCancellationFlags(messagesToSend);
        long taskId = startRun(messagesToSend.size());
        runForceForward(messagesToSend, targetDialogId, showUndo, hideCaption, notify, scheduleDate, payStars, taskId, 0, onComplete);
    }

    private void runForceForward(ArrayList<MessageObject> messagesToSend, long targetDialogId, boolean showUndo, boolean hideCaption, boolean notify, int scheduleDate, long payStars, long taskId, int retryCount, CompletionCallback onComplete) {
        if (disposed) {
            return;
        }
        if (!isTaskActive(taskId)) {
            finishRun(taskId, false, onComplete);
            return;
        }
        if (stopRequested) {
            finishRun(taskId, false, onComplete);
            return;
        }

        MediaPreparationResult mediaState = prepareMedia(messagesToSend);
        int missingMediaCount = mediaState.missingMediaCount;
        updateLoadingState(missingMediaCount);
        if (!isTaskActive(taskId)) {
            finishRun(taskId, false, onComplete);
            return;
        }

        if (missingMediaCount > 0) {
            if (shouldAbortMediaWait(mediaState, taskId, onComplete)) {
                return;
            }
            AndroidUtilities.runOnUIThread(() -> runForceForward(messagesToSend, targetDialogId, showUndo, hideCaption, notify, scheduleDate, payStars, taskId, retryCount + 1, onComplete), MEDIA_RETRY_DELAY_MS);
            return;
        }

        updateForwardingState(LocaleController.getString(R.string.ForceForwardStatusStarting));
        try {
            HashMap<Long, ArrayList<GroupedMediaItem>> albumMap = new HashMap<>();
            HashMap<Long, ArrayList<GroupedMediaItem>> docAlbumMap = new HashMap<>();
            HashMap<Long, Integer> albumRemain = new HashMap<>();
            ArrayList<SendMessagesHelper.SendingMediaInfo> singles = new ArrayList<>();

            for (MessageObject mo : messagesToSend) {
                long gid = mo.getGroupId();
                if (gid == 0) continue;
                
                boolean groupedMedia = mo.isPhoto() || mo.isVideo() || mo.isGif();
                boolean groupedDoc = mo.getDocument() != null && !mo.isVideo() && !MessageObject.isGifMessage(mo.messageOwner) && !mo.isSticker() && !mo.isAnimatedSticker();
                
                if (groupedMedia || groupedDoc) {
                    albumRemain.put(gid, albumRemain.getOrDefault(gid, 0) + 1);
                }
            }

            for (MessageObject mo : messagesToSend) {
                if (!isTaskActive(taskId) || stopRequested) {
                    finishRun(taskId, false, onComplete);
                    return;
                }
                CharSequence captionCs = hideCaption ? null : getForwardCaption(mo);
                String caption = captionCs != null ? captionCs.toString() : null;
                boolean hasMessage = mo.messageOwner != null && !TextUtils.isEmpty(mo.messageOwner.message);

                if (mo.type == MessageObject.TYPE_TEXT || mo.isAnimatedEmoji()) {
                    updateForwardingState(LocaleController.getString(R.string.ForceForwardStatusTextCopy));
                    sendTextMessage(mo, caption, targetDialogId, notify, scheduleDate, payStars);
                    onMessageSent();
                    continue;
                }
                
                long gid = mo.getGroupId();

                if (mo.isPhoto() || mo.isVideo() || mo.isGif()) {
                    if (!ensureDownloaded(mo)) {
                        onMessageSkipped();
                        continue;
                    }

                    if (gid != 0) {
                        addToGroup(gid, new GroupedMediaItem(mo, caption), albumMap, albumRemain, false, targetDialogId, notify, scheduleDate, payStars);
                    } else {
                        updateForwardingState(mo.isPhoto() ? LocaleController.getString(R.string.ForceForwardStatusPhotoCopy) : LocaleController.getString(R.string.ForceForwardStatusMediaCopy));
                        boolean sent = mo.isPhoto()
                                ? sendMappedPhoto(mo, caption, targetDialogId, notify, scheduleDate, payStars)
                                : sendMappedDocument(mo, caption, targetDialogId, notify, scheduleDate, payStars);
                        if (sent) {
                            onMessageSent();
                        } else {
                            SendMessagesHelper.SendingMediaInfo info = createMediaInfo(mo, caption);
                            info.isVideo = mo.isVideo() || mo.isGif();
                            singles.add(info);
                        }
                    }
                    continue;
                }

                if (mo.getDocument() != null && !mo.isSticker() && !mo.isAnimatedSticker()) {
                    if (!ensureDownloaded(mo)) {
                        onMessageSkipped();
                        continue;
                    }
                    
                    if (gid != 0) {
                        addToGroup(gid, new GroupedMediaItem(mo, caption), docAlbumMap, albumRemain, true, targetDialogId, notify, scheduleDate, payStars);
                    } else {
                        updateForwardingState(LocaleController.getString(R.string.ForceForwardStatusDocumentCopy));
                        if (!sendMappedDocument(mo, caption, targetDialogId, notify, scheduleDate, payStars)) {
                            updateForwardingState(LocaleController.getString(R.string.ForceForwardStatusDocumentFallback));
                            sendDocumentFallback(mo, caption, targetDialogId, notify, scheduleDate);
                        }
                        onMessageSent();
                    }
                    continue;
                }

                if (mo.isSticker() || mo.isAnimatedSticker()) {
                    if (mo.getDocument() != null) {
                        updateForwardingState(LocaleController.getString(R.string.ForceForwardStatusStickerCopy));
                        MessageObject replyToMsg = parentFragment.getThreadMessage();
                        parentFragment.getSendMessagesHelper().sendSticker(mo.getDocument(), null, targetDialogId, replyToMsg, replyToMsg, null, null, null, notify, scheduleDate, 0, false, null, parentFragment.quickReplyShortcut, parentFragment.getQuickReplyId(), payStars, parentFragment.getSendMonoForumPeerId(), parentFragment.getSendMessageSuggestionParams());
                        onMessageSent();
                    } else {
                        onMessageSkipped();
                    }
                    continue;
                }
                
                if (hasMessage) {
                     updateForwardingState(LocaleController.getString(R.string.ForceForwardStatusTextFallback));
                     sendTextMessage(mo, null, targetDialogId, notify, scheduleDate, payStars);
                     onMessageSent();
                } else {
                    onMessageSkipped();
                }
            }

            if (!isTaskActive(taskId) || stopRequested) {
                finishRun(taskId, false, onComplete);
                return;
            }

            for (ArrayList<GroupedMediaItem> group : albumMap.values()) {
                sendLeftoverGroupAsSingles(new ArrayList<>(group), targetDialogId, notify, scheduleDate, payStars, false);
            }
            for (ArrayList<GroupedMediaItem> group : docAlbumMap.values()) {
                sendLeftoverGroupAsSingles(new ArrayList<>(group), targetDialogId, notify, scheduleDate, payStars, true);
            }

            if (!isTaskActive(taskId) || stopRequested) {
                finishRun(taskId, false, onComplete);
                return;
            }

            for (SendMessagesHelper.SendingMediaInfo info : singles) {
                ArrayList<SendMessagesHelper.SendingMediaInfo> one = new ArrayList<>();
                one.add(info);
                updateForwardingState(LocaleController.getString(R.string.ForceForwardStatusMediaFallback));
                sendMediaBatch(one, targetDialogId, false, false, notify, scheduleDate, payStars);
                onMessageSent();
            }
            finishRun(taskId, true, onComplete);
        } catch (Exception e) {
            FileLog.e(e);
            failRun(taskId, LocaleController.getString(R.string.ForceForwardFailed), onComplete);
        }
    }
    
    private SendMessagesHelper.SendingMediaInfo createMediaInfo(MessageObject mo, String caption) {
        SendMessagesHelper.SendingMediaInfo info = new SendMessagesHelper.SendingMediaInfo();
        info.path = resolvePath(mo);
        info.caption = caption;
        info.entities = TextUtils.isEmpty(caption) || mo.messageOwner == null ? null : mo.messageOwner.entities;
        if (mo.messageOwner != null && mo.messageOwner.media != null) {
            info.ttl = mo.messageOwner.media.ttl_seconds;
        }
        info.hasMediaSpoilers = mo.hasMediaSpoilers();
        return info;
    }

    private void sendTextMessage(MessageObject mo, String captionOverride, long targetDialogId, boolean notify, int scheduleDate, long payStars) {
        if (mo == null) return;
        
        String text = mo.messageOwner != null && !TextUtils.isEmpty(mo.messageOwner.message) 
                ? mo.messageOwner.message 
                : captionOverride;
                
        if (TextUtils.isEmpty(text)) return;
        
        ArrayList<TLRPC.MessageEntity> entities = mo.messageOwner != null && mo.messageOwner.entities != null && !mo.messageOwner.entities.isEmpty()
                ? mo.messageOwner.entities 
                : MediaDataController.getInstance(currentAccount).getEntities(new CharSequence[]{text}, true);
                
        SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(text, targetDialogId, null, null, null, true, entities, null, null, notify, scheduleDate, 0, null, false);
        applySendContext(params, payStars);
        parentFragment.getSendMessagesHelper().sendMessage(params);
    }

    private boolean shouldAbortMediaWait(MediaPreparationResult mediaState, long taskId, CompletionCallback onComplete) {
        if (mediaState.cancelledByUser) {
            setFailureReason(null);
            finishRun(taskId, false, onComplete);
            return true;
        }

        long now = SystemClock.elapsedRealtime();
        if (mediaWaitStartTime == 0L) {
            mediaWaitStartTime = now;
            mediaLastProgressTime = now;
            mediaLastObservedBytes = mediaState.downloadedBytes;
            mediaLastMissingCount = mediaState.missingMediaCount;
            return false;
        }

        boolean progressAdvanced = mediaState.missingMediaCount < mediaLastMissingCount || mediaState.downloadedBytes > mediaLastObservedBytes;
        if (progressAdvanced) {
            mediaLastProgressTime = now;
            mediaLastObservedBytes = mediaState.downloadedBytes;
            mediaLastMissingCount = mediaState.missingMediaCount;
            return false;
        }

        if (now - mediaWaitStartTime >= MAX_MEDIA_WAIT_MS) {
            FileLog.w("ForceForward: media wait exceeded hard timeout");
            failRun(taskId, LocaleController.getString(R.string.ForceForwardMediaTimedOut), onComplete);
            return true;
        }

        if (now - mediaLastProgressTime >= MAX_MEDIA_STALL_TIMEOUT_MS) {
            String failureReason = mediaState.activeDownloadCount > 0
                    ? LocaleController.getString(R.string.ForceForwardMediaStalled)
                    : LocaleController.getString(R.string.ForceForwardMediaDidNotStart);
            FileLog.w("ForceForward: " + failureReason);
            failRun(taskId, failureReason, onComplete);
            return true;
        }

        return false;
    }

}
