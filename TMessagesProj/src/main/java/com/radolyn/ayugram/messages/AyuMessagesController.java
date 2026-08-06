/*
 * This is the source code of AyuGram for Android.
 *
 * We do not and cannot prevent the use of our code,
 * but be respectful and credit the original author.
 *
 * Copyright @Radolyn, 2023
 */

package com.radolyn.ayugram.messages;

import android.os.Environment;
import android.text.TextUtils;

import com.radolyn.ayugram.AyuConstants;
import com.radolyn.ayugram.AyuUtils;
import com.radolyn.ayugram.database.AyuData;
import com.radolyn.ayugram.database.dao.DeletedMessageDao;
import com.radolyn.ayugram.database.dao.EditedMessageDao;
import com.radolyn.ayugram.database.entities.DeletedMessage;
import com.radolyn.ayugram.database.entities.DeletedMessageFull;
import com.radolyn.ayugram.database.entities.DeletedMessageReaction;
import com.radolyn.ayugram.database.entities.EditedMessage;
import com.radolyn.ayugram.utils.AyuMessageUtils;
import com.radolyn.ayugram.utils.LastSeenHelper;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_iv;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;


import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.utils.FileUtil;
import xyz.nextalone.nagram.NaConfig;

public class AyuMessagesController {
    public static final String attachmentsSubfolder = "Saved Attachments";
    public static File attachmentsPath = getDefaultAttachmentsPath();
    public static final long[] ATTACHMENT_SIZE_LIMIT_PRESETS = new long[]{
            300L * 1024L * 1024L,
            1024L * 1024L * 1024L,
            2L * 1024L * 1024L * 1024L,
            5L * 1024L * 1024L * 1024L,
            16L * 1024L * 1024L * 1024L,
            Long.MAX_VALUE
    };
    private static AyuMessagesController instance;
    private static final String ATTACHMENTS_MAINTENANCE_KEY = "ayuLastAttachmentsMaintenance";
    private static final long MAINTENANCE_INTERVAL_MS = 24L * 60L * 60L * 1000L;
    private final DeletedDialogService deletedDialogService;

    private AyuMessagesController() {
        initializeAttachmentsFolder();
        AyuSavePreferences.loadAllExclusions();

        deletedDialogService = new DeletedDialogService();
        scheduleRestoreDeletedDialogs();
        scheduleAttachmentsMaintenance();
    }

    private static File getDefaultAttachmentsPath() {
        return new File(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), AyuConstants.APP_NAME), attachmentsSubfolder);
    }

    private static File resolveConfiguredAttachmentsPath() {
        String configuredPath = NaConfig.INSTANCE.getAttachmentFolderPath().String();
        if (TextUtils.isEmpty(configuredPath)) {
            return getDefaultAttachmentsPath();
        }
        return new File(configuredPath);
    }

    public static synchronized void syncAttachmentsPathWithConfig() {
        attachmentsPath = resolveConfiguredAttachmentsPath();
    }

    public static synchronized void setAttachmentFolderPath(File path) {
        String newPath = path == null ? "" : path.getAbsolutePath();
        NaConfig.INSTANCE.getAttachmentFolderPath().setConfigString(newPath);
        syncAttachmentsPathWithConfig();
        initializeAttachmentsFolder();
        AyuData.loadSizes(null);
    }

    public static boolean isManagedAttachmentPath(String path) {
        if (TextUtils.isEmpty(path)) {
            return false;
        }
        syncAttachmentsPathWithConfig();
        try {
            String folderPath = attachmentsPath.getCanonicalPath();
            String filePath = new File(path).getCanonicalPath();
            return filePath.equals(folderPath) || filePath.startsWith(folderPath + File.separator);
        } catch (Exception e) {
            FileLog.e("isManagedAttachmentPath", e);
            String folderPath = attachmentsPath.getAbsolutePath();
            return path.equals(folderPath) || path.startsWith(folderPath + File.separator);
        }
    }

    private static void clearAttachmentPathReferences(String mediaPath) {
        if (TextUtils.isEmpty(mediaPath)) {
            return;
        }
        try {
            deletedMessageDao().clearMediaPath(mediaPath);
            editedMessageDao().clearMediaPath(mediaPath);
        } catch (Exception e) {
            FileLog.e("clearAttachmentPathReferences", e);
        }
    }

    /**
     * DAO 一律现取，不缓存。{@link AyuData} 返回的是读锁包装代理，永不为 null，
     * 数据库重建期间调用会等待而非拿到失效引用。
     */
    private static DeletedMessageDao deletedMessageDao() {
        return AyuData.getDeletedMessageDao();
    }

    private static EditedMessageDao editedMessageDao() {
        return AyuData.getEditedMessageDao();
    }

    private void scheduleRestoreDeletedDialogs() {
        AndroidUtilities.runOnUIThread(() -> {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (UserConfig.getInstance(a).isClientActivated()) {
                    deletedDialogService.loadAndRestore(a);
                }
            }
        }, 2000);
    }

    public void onDialogDeleted(int account, long dialogId) {
        deletedDialogService.onDialogDeleted(account, dialogId);
    }

    public MessageObject getLastMessageCached(int account, long dialogId) {
        return deletedDialogService.getLastMessageCached(account, dialogId);
    }

    public MessageObject getLastMessageCached(long dialogId) {
        return getLastMessageCached(UserConfig.selectedAccount, dialogId);
    }

    public DeletedDialogService getDeletedDialogService() {
        return deletedDialogService;
    }

    public void onOfficialDialogsLoaded(int account, ArrayList<Long> dialogIds) {
        deletedDialogService.onOfficialDialogsLoaded(account, dialogIds);
    }

    public void updateDeletedDialogsFolder(int account, ArrayList<Long> dialogIds, int folderId) {
        deletedDialogService.updateDeletedDialogsFolder(account, dialogIds, folderId);
    }

    public void deleteDialogRecord(int account, long userId, long dialogId) {
        deletedDialogService.deleteDialogRecord(account, userId, dialogId);
    }

    public void deleteDialogRecord(long userId, long dialogId) {
        int account = UserConfig.selectedAccount;
        long selfUserId = UserConfig.getInstance(account).clientUserId;
        if (userId != selfUserId) {
            outer:
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (UserConfig.getInstance(a).isClientActivated()
                        && UserConfig.getInstance(a).clientUserId == userId) {
                    account = a;
                    break outer;
                }
            }
        }
        deletedDialogService.deleteDialogRecord(account, userId, dialogId);
    }

    /**
     * DAO 调用失败时重试一次。DAO 现已改为每次现取（见 {@link #deletedMessageDao()}），
     * 所以这里不需要再刷新缓存字段——重试本身就会拿到新的数据库实例。
     */
    private <T> T withDaoRetry(String tag, Callable<T> callable) {
        try {
            return callable.call();
        } catch (Exception e) {
            FileLog.e(tag, e);
        }

        try {
            return callable.call();
        } catch (Exception e) {
            FileLog.e(tag, e);
        }

        return null;
    }

    private static void initializeAttachmentsFolder() {
        try {
            syncAttachmentsPathWithConfig();
            File nomediaFile = new File(attachmentsPath, ".nomedia");
            if (attachmentsPath.exists() || attachmentsPath.mkdirs()) {
                AndroidUtilities.createEmptyFile(nomediaFile);
            }
            if (!nomediaFile.exists()) {
                File randomFile = new File(attachmentsPath, AyuUtils.generateRandomString(4));
                AndroidUtilities.createEmptyFile(randomFile);
                if (!randomFile.renameTo(nomediaFile)) {
                    if (!randomFile.delete()) {
                        randomFile.deleteOnExit();
                    }
                    FileLog.e("Failed to rename random .nomedia file to the correct name");
                } else {
                    FileLog.d("Created .nomedia file in attachments folder by renaming a random file");
                }
            } else {
                FileLog.d(".nomedia file already exists in attachments folder");
            }
        } catch (Exception e) {
            FileLog.e("initializeAttachmentsFolder", e);
        }
    }

    public static synchronized AyuMessagesController getInstance() {
        if (instance == null) {
            instance = new AyuMessagesController();
        }
        return instance;
    }

    public static int clampAttachmentSizeLimitPreset(int preset) {
        return Math.max(0, Math.min(preset, ATTACHMENT_SIZE_LIMIT_PRESETS.length - 1));
    }

    public static long getConfiguredAttachmentSizeLimit() {
        int preset = clampAttachmentSizeLimitPreset(NaConfig.INSTANCE.getAttachmentFolderSizeLimitPreset().Int());
        if (preset != NaConfig.INSTANCE.getAttachmentFolderSizeLimitPreset().Int()) {
            NaConfig.INSTANCE.getAttachmentFolderSizeLimitPreset().setConfigInt(preset);
        }
        return ATTACHMENT_SIZE_LIMIT_PRESETS[preset];
    }

    public static void refreshAfterDatabaseChange() {
        // DAO 无需刷新（每次现取），只要把会话快照重新载入
        if (instance != null) {
            instance.scheduleRestoreDeletedDialogs();
        }
    }

    public static long trimAttachmentsFolderToLimit() {
        return trimAttachmentsFolderToLimit(null);
    }

    public static synchronized long trimAttachmentsFolderToLimit(File keepFile) {
        try {
            initializeAttachmentsFolder();
            long limit = getConfiguredAttachmentSizeLimit();
            if (limit == Long.MAX_VALUE) {
                return 0L;
            }

            File[] attachmentFiles = attachmentsPath.listFiles(file ->
                    file != null && file.isFile() && !".nomedia".equals(file.getName()));
            if (attachmentFiles == null || attachmentFiles.length == 0) {
                return 0L;
            }

            Arrays.sort(attachmentFiles, Comparator.comparingLong(File::lastModified));

            long currentSize = 0L;
            for (File file : attachmentFiles) {
                currentSize += Math.max(0L, file.length());
            }

            String keepPath = keepFile == null ? null : keepFile.getAbsolutePath();
            long deletedSize = 0L;
            for (File file : attachmentFiles) {
                if (currentSize <= limit) {
                    break;
                }
                if (keepPath != null && keepPath.equals(file.getAbsolutePath())) {
                    continue;
                }

                long fileLength = Math.max(0L, file.length());
                if (!file.exists()) {
                    currentSize -= fileLength;
                    continue;
                }
                if (file.delete()) {
                    currentSize -= fileLength;
                    deletedSize += fileLength;
                    clearAttachmentPathReferences(file.getAbsolutePath());
                } else {
                    FileLog.e("Failed to delete old attachment " + file.getAbsolutePath());
                }
            }

            if (deletedSize > 0L) {
                AyuData.loadSizes(null);
            }
            return deletedSize;
        } catch (Exception e) {
            FileLog.e("trimAttachmentsFolderToLimit", e);
            return 0L;
        }
    }

    public void onMessageEdited(AyuSavePreferences prefs, TLRPC.Message newMessage) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                onMessageEditedInner(prefs, newMessage, false);
            } catch (Exception e) {
                FileLog.e("onMessageEdited", e);
            }
        });
    }

    public void onMessageEditedForce(AyuSavePreferences prefs) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                onMessageEditedInner(prefs, prefs.getMessage(), true);
            } catch (Exception e) {
                FileLog.e("onMessageEditedForce", e);
            }
        });
    }

    private void onMessageEditedInner(AyuSavePreferences prefs, TLRPC.Message newMessage, boolean force) {
        var oldMessage = prefs.getMessage();

        boolean sameMedia = isSameMedia(newMessage, force, oldMessage);

        if (!shouldSaveEdit(oldMessage, newMessage, sameMedia)) {
            return;
        }

        var revision = new EditedMessage();
        AyuMessageUtils.map(prefs, revision);
        AyuMessageUtils.mapMedia(prefs, revision, !sameMedia);

        if (!sameMedia && !TextUtils.isEmpty(revision.mediaPath)) {
            var lastRevision = withDaoRetry(
                    "onMessageEditedInner#getLastRevision",
                    () -> editedMessageDao().getLastRevision(prefs.getUserId(), prefs.getDialogId(), prefs.getMessageId())
            );

            if (lastRevision != null && !TextUtils.equals(revision.mediaPath, lastRevision.mediaPath) && lastRevision.mediaPath != null && !isManagedAttachmentPath(lastRevision.mediaPath)) {
                // update previous revisions to reflect media change
                // like, there's no previous file, so replace it with one we copied before...
                withDaoRetry(
                        "onMessageEditedInner#updateAttachmentForRevisionsBetweenDates",
                        () -> {
                            editedMessageDao().updateAttachmentForRevisionsBetweenDates(prefs.getUserId(), prefs.getDialogId(), prefs.getMessageId(), lastRevision.mediaPath, revision.mediaPath);
                            return null;
                        }
                );
            }
        }

        withDaoRetry(
                "onMessageEditedInner#insert",
                () -> {
                    editedMessageDao().insert(revision);
                    return null;
                }
        );

        if (NaConfig.INSTANCE.getSaveLocalLastSeen().Bool() && newMessage.from_id != null) {
            long fromUserId = MessageObject.getPeerId(newMessage.from_id);
            if (fromUserId > 0) {
                int ts = newMessage.edit_date != 0 ? newMessage.edit_date : newMessage.date;
                if (ts <= 0) {
                    ts = ConnectionsManager.getInstance(prefs.getAccountId()).getCurrentTime();
                }
                LastSeenHelper.saveLastSeen(prefs.getAccountId(), fromUserId, ts);
            }
        }

        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(prefs.getAccountId()).postNotificationName(AyuConstants.MESSAGE_EDITED_NOTIFICATION, prefs.getDialogId(), prefs.getMessageId()));
    }

    public static boolean shouldSaveEdit(TLRPC.Message oldMessage, TLRPC.Message newMessage, boolean sameMedia) {
        if (oldMessage == null || newMessage == null) {
            return false;
        }
        if (!sameMedia) {
            return true;
        }
        if (!TextUtils.equals(oldMessage.message, newMessage.message)) {
            return true;
        }
        if (!isSameEntities(oldMessage, newMessage)) {
            return true;
        }
        return !isSameRichMessage(oldMessage, newMessage);
    }

    private static boolean isSameEntities(TLRPC.Message oldMessage, TLRPC.Message newMessage) {
        ArrayList<TLRPC.MessageEntity> oldEntities = oldMessage.entities;
        ArrayList<TLRPC.MessageEntity> newEntities = newMessage.entities;
        boolean oldEmpty = oldEntities == null || oldEntities.isEmpty();
        boolean newEmpty = newEntities == null || newEntities.isEmpty();
        if (oldEmpty && newEmpty) {
            return true;
        }
        if (oldEmpty != newEmpty) {
            return false;
        }
        if (oldEntities.size() != newEntities.size()) {
            return false;
        }
        return Arrays.equals(AyuMessageUtils.serializeMultiple(oldEntities), AyuMessageUtils.serializeMultiple(newEntities));
    }

    private static boolean isSameRichMessage(TLRPC.Message oldMessage, TLRPC.Message newMessage) {
        TL_iv.RichMessage oldRich = oldMessage.rich_message;
        TL_iv.RichMessage newRich = newMessage.rich_message;
        if (oldRich == newRich) {
            return true;
        }
        if (oldRich == null || newRich == null) {
            return false;
        }
        NativeByteBuffer bufOld = null;
        NativeByteBuffer bufNew = null;
        try {
            bufOld = new NativeByteBuffer(oldRich.getObjectSize());
            bufNew = new NativeByteBuffer(newRich.getObjectSize());
            oldRich.serializeToStream(bufOld);
            newRich.serializeToStream(bufNew);
            bufOld.rewind();
            bufNew.rewind();
            if (bufOld.remaining() != bufNew.remaining()) {
                return false;
            }
            byte[] bytesOld = new byte[bufOld.remaining()];
            byte[] bytesNew = new byte[bufNew.remaining()];
            bufOld.buffer.get(bytesOld);
            bufNew.buffer.get(bytesNew);
            return Arrays.equals(bytesOld, bytesNew);
        } catch (Exception e) {
            FileLog.e("isSameRichMessage", e);
            return false;
        } finally {
            if (bufOld != null) {
                bufOld.reuse();
            }
            if (bufNew != null) {
                bufNew.reuse();
            }
        }
    }

    private static boolean isSameMedia(TLRPC.Message newMessage, boolean force, TLRPC.Message oldMessage) {
        boolean sameMedia = oldMessage.media == newMessage.media ||
                (oldMessage.media != null && newMessage.media != null && oldMessage.media.getClass() == newMessage.media.getClass());
        if (oldMessage.media instanceof TLRPC.TL_messageMediaPhoto && newMessage.media instanceof TLRPC.TL_messageMediaPhoto && oldMessage.media.photo != null && newMessage.media.photo != null) {
            sameMedia = oldMessage.media.photo.id == newMessage.media.photo.id;
        } else if (oldMessage.media instanceof TLRPC.TL_messageMediaDocument && newMessage.media instanceof TLRPC.TL_messageMediaDocument && oldMessage.media.document != null && newMessage.media.document != null) {
            sameMedia = oldMessage.media.document.id == newMessage.media.document.id;
        }

        if (force) {
            sameMedia = false;
        }
        return sameMedia;
    }

    public void onMessageDeleted(AyuSavePreferences prefs) {
        onMessageDeleted(prefs, true);
    }

    public void onMessageDeleted(AyuSavePreferences prefs, boolean useQueue) {
        if (prefs.getMessage() == null) {
            return;
        }
        try {
            if (useQueue) {
                Utilities.globalQueue.postRunnable(() -> onMessageDeletedInner(prefs));
            } else {
                onMessageDeletedInner(prefs);
            }
        } catch (Exception e) {
            FileLog.e("onMessageDeleted", e);
        }
    }

    private void onMessageDeletedInner(AyuSavePreferences prefs) {
        if (!AyuSavePreferences.saveDeletedMessageFor(prefs.getAccountId(), prefs.getDialogId(), prefs.getFromUserId())) {
            return;
        }

        Boolean exists = withDaoRetry(
                "onMessageDeletedInner#exists",
                () -> deletedMessageDao().exists(prefs.getUserId(), prefs.getDialogId(), prefs.getTopicId(), prefs.getMessageId())
        );

        if (exists == null || exists) {
            return;
        }

        var deletedMessage = new DeletedMessage();
        deletedMessage.userId = prefs.getUserId();
        deletedMessage.dialogId = prefs.getDialogId();
        deletedMessage.messageId = prefs.getMessageId();
        deletedMessage.entityCreateDate = prefs.getRequestCatchTime();

        var msg = prefs.getMessage();

        FileLog.d("saving message " + prefs.getMessageId() + " for " + prefs.getDialogId() + " with topic " + prefs.getTopicId());

        AyuMessageUtils.map(prefs, deletedMessage);
        AyuMessageUtils.mapMedia(prefs, deletedMessage, true);

        Long fakeMsgId = withDaoRetry(
                "onMessageDeletedInner#insert",
                () -> deletedMessageDao().insert(deletedMessage)
        );

        if (fakeMsgId == null) {
            return;
        }

        if (msg != null && msg.reactions != null) {
            processDeletedReactions(fakeMsgId, msg.reactions);
        }

        updateLastMessageCache(prefs, msg);
    }

    private void updateLastMessageCache(AyuSavePreferences prefs, TLRPC.Message msg) {
        if (msg == null) {
            return;
        }
        int account = prefs.getAccountId();
        long dialogId = prefs.getDialogId();
        MessageObject existing = deletedDialogService.getLastMessageCached(account, dialogId);
        // 不能直接比 id：密聊 id 为负且越新越小
        if (existing != null && existing.messageOwner != null
                && AyuMessageUtils.compareMessages(msg, existing.messageOwner) >= 0) {
            return;
        }
        try {
            DeletedMessage dm = new DeletedMessage();
            AyuMessageUtils.map(prefs, dm);
            AyuMessageUtils.mapMedia(prefs, dm, false);
            TLRPC.TL_message tl = new TLRPC.TL_message();
            AyuMessageUtils.map(dm, tl, account);
            AyuMessageUtils.mapMedia(dm, tl, account);
            tl.ayuDeleted = true;
            MessageObject mo = new MessageObject(account, tl, false, false);
            if (!android.text.TextUtils.isEmpty(mo.messageText)) {
                deletedDialogService.putLastMessage(account, dialogId, mo);
            }
        } catch (Throwable e) {
            FileLog.e("updateLastMessageCache", e);
        }
    }

    private void processDeletedReactions(long fakeMessageId, TLRPC.TL_messageReactions reactions) {
        for (var reaction : reactions.results) {
            if (reaction.reaction instanceof TLRPC.TL_reactionEmpty) {
                continue;
            }

            var deletedReaction = new DeletedMessageReaction();
            deletedReaction.deletedMessageId = fakeMessageId;
            deletedReaction.count = reaction.count;
            deletedReaction.selfSelected = reaction.chosen;

            if (reaction.reaction instanceof TLRPC.TL_reactionEmoji) {
                deletedReaction.emoticon = ((TLRPC.TL_reactionEmoji) reaction.reaction).emoticon;
            } else if (reaction.reaction instanceof TLRPC.TL_reactionCustomEmoji) {
                deletedReaction.documentId = ((TLRPC.TL_reactionCustomEmoji) reaction.reaction).document_id;
                deletedReaction.isCustom = true;
            } else if (reaction.reaction instanceof TLRPC.TL_reactionPaid) {
                deletedReaction.isPaid = true;
            } else {
                continue;
            }

            withDaoRetry(
                    "processDeletedReactions#insertReaction",
                    () -> {
                        deletedMessageDao().insertReaction(deletedReaction);
                        return null;
                    }
            );
        }
    }

    public boolean hasAnyRevisions(long userId, long dialogId, int messageId) {
        return editedMessageDao().hasAnyRevisions(userId, dialogId, messageId);
    }

    public List<EditedMessage> getRevisions(long userId, long dialogId, int messageId) {
        return editedMessageDao().getAllRevisions(userId, dialogId, messageId);
    }

    public DeletedMessageFull getMessage(long userId, long dialogId, int messageId) {
        return deletedMessageDao().getMessage(userId, dialogId, messageId);
    }

    public List<DeletedMessageFull> getMessages(long userId, long dialogId, long startId, long endId, int limit) {
        return deletedMessageDao().getMessages(userId, dialogId, startId, endId, limit);
    }

    public List<DeletedMessageFull> getTopicMessages(long userId, long dialogId, long topicId, long startId, long endId, int limit) {
        return deletedMessageDao().getTopicMessages(userId, dialogId, topicId, startId, endId, limit);
    }

    public List<DeletedMessageFull> getThreadMessages(long userId, long dialogId, long threadMessageId, long startId, long endId, int limit) {
        return deletedMessageDao().getThreadMessages(userId, dialogId, threadMessageId, startId, endId, limit);
    }

    public List<DeletedMessageFull> getMessagesGroupedIn(long userId, long dialogId, List<Long> groupedIds) {
        if (groupedIds == null || groupedIds.isEmpty()) {
            return new ArrayList<>();
        }
        return deletedMessageDao().getMessagesGroupedIn(userId, dialogId, groupedIds);
    }

    public List<Integer> getExistingMessageIds(long userId, long dialogId, List<Integer> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return new ArrayList<>();
        }
        return deletedMessageDao().getExistingMessageIds(userId, dialogId, messageIds);
    }

    public List<DeletedMessageFull> getMessagesByIds(long userId, long dialogId, List<Integer> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return new ArrayList<>();
        }
        return deletedMessageDao().getMessagesByIds(userId, dialogId, messageIds);
    }

    public List<DeletedMessageFull> searchByText(long userId, long dialogId, String query, int limit) {
        if (TextUtils.isEmpty(query)) {
            return new ArrayList<>();
        }
        return deletedMessageDao().searchByText(userId, dialogId, query, limit);
    }

    public void delete(long userId, long dialogId, int messageId) {
        var msg = getMessage(userId, dialogId, messageId);
        if (msg == null) {
            return;
        }

        deletedMessageDao().delete(userId, dialogId, messageId);

        if (!TextUtils.isEmpty(msg.message.mediaPath)) {
            var p = new File(msg.message.mediaPath);
            try {
                if (p.exists() && !p.delete()) {
                    p.deleteOnExit();
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    public void deleteMessages(long userId, long dialogId, List<Integer> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return;
        }

        List<DeletedMessageFull> messages = deletedMessageDao().getMessagesByIds(userId, dialogId, messageIds);
        List<String> mediaPaths = new ArrayList<>();
        if (messages != null) {
            for (DeletedMessageFull msg : messages) {
                if (msg != null && msg.message != null && !TextUtils.isEmpty(msg.message.mediaPath)) {
                    mediaPaths.add(msg.message.mediaPath);
                }
            }
        }

        deletedMessageDao().deleteMessages(userId, dialogId, messageIds);
        editedMessageDao().deleteByDialogIdAndMessageIds(dialogId, messageIds);

        for (String mediaPath : mediaPaths) {
            var p = new File(mediaPath);
            try {
                if (p.exists() && !p.delete()) {
                    p.deleteOnExit();
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    public void deleteRevision(long fakeId) {
        String mediaPath = editedMessageDao().getMediaPathByFakeId(fakeId);
        int deleted = editedMessageDao().deleteByFakeId(fakeId);
        if (deleted == 0) {
            return;
        }
        if (!TextUtils.isEmpty(mediaPath)) {
            File p = new File(mediaPath);
            try {
                if (p.exists() && !p.delete()) {
                    p.deleteOnExit();
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
    }

    public void deleteCurrent(long dialogId, long mergeDialogId, Runnable callback) {
        List<DeletedMessageFull> messages = deletedMessageDao().getMessagesByDialog(dialogId);

        if (mergeDialogId != 0) {
            List<DeletedMessageFull> mergeMessages = deletedMessageDao().getMessagesByDialog(mergeDialogId);
            messages.addAll(mergeMessages);
        }

        // Delete messages and their edit history from database
        deletedMessageDao().delete(dialogId);
        editedMessageDao().delete(dialogId);

        if (mergeDialogId != 0) {
            deletedMessageDao().delete(mergeDialogId);
            editedMessageDao().delete(mergeDialogId);
        }

        long userId = UserConfig.getInstance(UserConfig.selectedAccount).clientUserId;
        deleteDialogRecord(userId, dialogId);
        if (mergeDialogId != 0) {
            deleteDialogRecord(userId, mergeDialogId);
        }

        // Clean up media files
        for (DeletedMessageFull msg : messages) {
            if (msg.message.mediaPath != null && !msg.message.mediaPath.isEmpty()) {
                File mediaFile = new File(msg.message.mediaPath);
                try {
                    if (mediaFile.exists() && !mediaFile.delete()) {
                        mediaFile.deleteOnExit();
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
        }

        if (callback != null) {
            callback.run();
        }
    }

    public boolean isAyuDeletedMessageId(long userId, long dialogId, int messageId) {
        if (userId == 0 || dialogId == 0 || messageId == 0) {
            return false;
        }
        return AyuMessagesController.getInstance().getMessage(userId, dialogId, messageId) != null;
    }

    public int getDeletedCount(long userId, long dialogId) {
        return deletedMessageDao().countByDialog(userId, dialogId);
    }

    public List<DeletedMessageFull> getLatestMessages(long userId, long dialogId, int limit) {
        return deletedMessageDao().getLatestMessages(userId, dialogId, limit);
    }

    public List<DeletedMessageFull> getOlderMessagesBefore(long userId, long dialogId, int before, int limit) {
        return deletedMessageDao().getOlderMessagesBefore(userId, dialogId, before, limit);
    }

    public void updateMediaPath(long userId, long dialogId, int messageId, String newPath) {
        deletedMessageDao().updateMediaPathIfEmpty(userId, dialogId, messageId, newPath);
    }

    public void clean() {
        clearDatabase();
        clearAttachments();
        instance = null;
    }

    public static synchronized void clearDatabase() {
        AyuData.clean();
        AyuData.create();
        refreshAfterDatabaseChange();
    }

    public static synchronized void clearAttachments() {
        syncAttachmentsPathWithConfig();
        FileUtil.deleteDirectory(attachmentsPath);
        initializeAttachmentsFolder();
        // 文件都没了，库里的 mediaPath 必须一起清掉，
        // 否则这些记录会渲染成点不开的空附件气泡
        try {
            deletedMessageDao().clearAllMediaPaths();
            editedMessageDao().clearAllMediaPaths();
        } catch (Exception e) {
            FileLog.e("clearAttachments#clearMediaPaths", e);
        }
        AyuData.loadSizes(null);
    }

    /**
     * 对账库记录与实际文件：清掉指向已不存在文件的 mediaPath。
     *
     * <p>裁剪逻辑自己删文件时会同步清引用，但文件也可能因外部删除、换存储目录、
     * 系统清理等原因消失，这些记录只能靠这里回收。
     *
     * @return 清理掉的记录数
     */
    public static int reconcileAttachmentReferences() {
        int cleared = 0;
        try {
            Set<String> paths = new HashSet<>();
            List<String> deletedPaths = deletedMessageDao().getAllMediaPaths();
            if (deletedPaths != null) {
                paths.addAll(deletedPaths);
            }
            List<String> editedPaths = editedMessageDao().getAllMediaPaths();
            if (editedPaths != null) {
                paths.addAll(editedPaths);
            }

            for (String path : paths) {
                if (TextUtils.isEmpty(path)) {
                    continue;
                }
                try {
                    if (new File(path).exists()) {
                        continue;
                    }
                } catch (Throwable ignored) {
                    // 路径不合法，同样按缺失处理
                }
                clearAttachmentPathReferences(path);
                cleared++;
            }
        } catch (Exception e) {
            FileLog.e("reconcileAttachmentReferences", e);
        }
        if (cleared > 0) {
            FileLog.d("reconcileAttachmentReferences: cleared " + cleared + " stale media references");
        }
        return cleared;
    }

    /**
     * 附件维护：裁剪超限文件 + 回收失效的库引用。每 24 小时最多跑一次。
     *
     * <p>之前只在保存附件时顺手裁剪一次大小，没有任何地方做库与文件的对账，
     * 文件被外部删掉后记录会一直残留。
     */
    public static void scheduleAttachmentsMaintenance() {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                long now = System.currentTimeMillis();
                long last = NekoConfig.getPreferences().getLong(ATTACHMENTS_MAINTENANCE_KEY, 0L);
                if (last != 0L && now - last < MAINTENANCE_INTERVAL_MS) {
                    return;
                }
                NekoConfig.getPreferences().edit().putLong(ATTACHMENTS_MAINTENANCE_KEY, now).apply();

                trimAttachmentsFolderToLimit();
                reconcileAttachmentReferences();
                AyuData.loadSizes(null);
            } catch (Exception e) {
                FileLog.e("scheduleAttachmentsMaintenance", e);
            }
        }, 10_000);
    }

    private void cleanAttachmentsFolder() {
        clearAttachments();
    }
}
