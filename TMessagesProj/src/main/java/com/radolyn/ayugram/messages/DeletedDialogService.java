/*
 * This is the source code of AyuGram for Android.
 *
 * We do not and cannot prevent the use of our code,
 * but be respectful and credit the original author.
 *
 * Copyright @Radolyn, 2023
 */

package com.radolyn.ayugram.messages;

import android.text.TextUtils;
import android.util.LongSparseArray;

import com.radolyn.ayugram.database.AyuData;
import com.radolyn.ayugram.database.dao.DeletedDialogDao;
import com.radolyn.ayugram.database.entities.DeletedDialog;
import com.radolyn.ayugram.database.entities.DeletedMessageFull;
import com.radolyn.ayugram.utils.AyuMessageUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import xyz.nextalone.nagram.NaConfig;

public class DeletedDialogService {
    private final ConcurrentHashMap<Integer, ConcurrentHashMap<Long, MessageObject>> lastMessagesByAccount = new ConcurrentHashMap<>();

    public DeletedDialogService() {
    }

    private ConcurrentHashMap<Long, MessageObject> mapForAccount(int account) {
        return lastMessagesByAccount.computeIfAbsent(account, k -> new ConcurrentHashMap<>());
    }

    public MessageObject getLastMessageCached(int account, long dialogId) {
        ConcurrentHashMap<Long, MessageObject> map = lastMessagesByAccount.get(account);
        return map != null ? map.get(dialogId) : null;
    }

    public void putLastMessage(int account, long dialogId, MessageObject messageObject) {
        if (messageObject == null) {
            return;
        }
        ConcurrentHashMap<Long, MessageObject> map = mapForAccount(account);
        MessageObject existing = map.get(dialogId);
        if (existing == null || existing.messageOwner == null
                || existing.messageOwner.id < messageObject.messageOwner.id) {
            map.put(dialogId, messageObject);
        }
    }

    public void onDialogDeleted(int account, long dialogId) {
        if (!NaConfig.INSTANCE.getEnableSaveDeletedMessages().Bool()) {
            return;
        }
        try {
            MessagesController mc = MessagesController.getInstance(account);
            TLRPC.Dialog dialog = mc.getDialog(dialogId);
            if (dialog == null) {
                return;
            }
            long userId = UserConfig.getInstance(account).clientUserId;
            int entityCreateDate = ConnectionsManager.getInstance(account).getCurrentTime();
            DeletedDialog entity = mapDialogToEntity(dialog, userId, entityCreateDate);
            Utilities.globalQueue.postRunnable(() -> saveDeletedDialog(account, entity));
        } catch (Throwable e) {
            FileLog.e("onDialogDeleted", e);
        }
    }

    private void saveDeletedDialog(int account, DeletedDialog entity) {
        try {
            DeletedDialogDao dao = AyuData.getDeletedDialogDao();
            if (dao == null) {
                return;
            }
            DeletedDialog existing = dao.get(entity.userId, entity.dialogId);
            if (existing != null) {
                dao.delete(existing);
            }
            dao.insert(entity);
            AndroidUtilities.runOnUIThread(() -> {
                putDialog(account, entity);
                MessagesController mc = MessagesController.getInstance(account);
                mc.sortDialogs(null);
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
            }, 1500);
        } catch (Throwable e) {
            FileLog.e("saveDeletedDialog", e);
        }
    }

    public void loadAndRestore(int account) {
        if (!NaConfig.INSTANCE.getEnableSaveDeletedMessages().Bool()) {
            return;
        }
        if (!UserConfig.getInstance(account).isClientActivated()) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            try {
                loadLastMessages(account);
                loadDeletedDialogs(account);
            } catch (Throwable e) {
                FileLog.e("loadAndRestore", e);
            }
        });
    }

    private void loadLastMessages(int account) {
        long userId = UserConfig.getInstance(account).clientUserId;
        if (userId == 0) {
            return;
        }
        List<DeletedMessageFull> list;
        try {
            list = AyuData.getDeletedMessageDao().getLastMessages(userId);
        } catch (Throwable e) {
            FileLog.e("loadLastMessages", e);
            return;
        }

        ConcurrentHashMap<Long, MessageObject> accountMap = mapForAccount(account);
        accountMap.clear();
        if (list == null || list.isEmpty()) {
            return;
        }

        ArrayList<Long> usersToLoad = new ArrayList<>();
        ArrayList<Long> chatsToLoad = new ArrayList<>();
        ConcurrentHashMap<Long, MessageObject> mapped = new ConcurrentHashMap<>();

        for (DeletedMessageFull full : list) {
            if (full == null || full.message == null || isEmpty(full.message)) {
                continue;
            }
            try {
                TLRPC.TL_message tlMessage = new TLRPC.TL_message();
                AyuMessageUtils.map(full.message, tlMessage, account);
                try {
                    AyuMessageUtils.mapMedia(full.message, tlMessage, account);
                } catch (Exception e) {
                    FileLog.e("Failed to map media for message " + full.message.messageId, e);
                }
                tlMessage.ayuDeleted = true;
                MessagesStorage.addUsersAndChatsFromMessage(tlMessage, usersToLoad, chatsToLoad, null);
                MessageObject messageObject = new MessageObject(account, tlMessage, false, false);
                if (!TextUtils.isEmpty(messageObject.messageText)) {
                    mapped.put(full.message.dialogId, messageObject);
                }
            } catch (Throwable e) {
                FileLog.e("loadLastMessages map", e);
            }
        }

        accountMap.putAll(mapped);

        ArrayList<TLRPC.User> users = new ArrayList<>();
        ArrayList<TLRPC.Chat> chats = new ArrayList<>();
        loadUsersAndChats(account, usersToLoad, chatsToLoad, users, chats);

        AndroidUtilities.runOnUIThread(() -> {
            MessagesController mc = MessagesController.getInstance(account);
            if (!users.isEmpty()) {
                mc.putUsers(users, true);
            }
            if (!chats.isEmpty()) {
                mc.putChats(chats, true);
            }
        });
    }

    private void loadDeletedDialogs(int account) {
        long userId = UserConfig.getInstance(account).clientUserId;
        if (userId == 0) {
            return;
        }
        List<DeletedDialog> all;
        try {
            all = AyuData.getDeletedDialogDao().getAll(userId);
        } catch (Throwable e) {
            FileLog.e("loadDeletedDialogs", e);
            return;
        }
        if (all == null || all.isEmpty()) {
            return;
        }

        ArrayList<Long> userIds = new ArrayList<>();
        ArrayList<Long> chatIds = new ArrayList<>();
        collectDialogIds(all, userIds, chatIds);

        LongSparseArray<Boolean> present = new LongSparseArray<>();
        ArrayList<TLRPC.User> users = new ArrayList<>();
        ArrayList<TLRPC.Chat> chats = new ArrayList<>();
        MessagesStorage storage = MessagesStorage.getInstance(account);

        for (int i = 0; i < userIds.size(); i++) {
            long id = userIds.get(i);
            TLRPC.User user = storage.getUser(id);
            present.put(id, user != null);
            if (user != null) {
                users.add(user);
            }
        }
        for (int i = 0; i < chatIds.size(); i++) {
            long id = chatIds.get(i);
            TLRPC.Chat chat = storage.getChat(id);
            present.put(id, chat != null);
            if (chat != null) {
                chats.add(chat);
            }
        }

        final List<DeletedDialog> dialogs = all;
        AndroidUtilities.runOnUIThread(() -> {
            MessagesController mc = MessagesController.getInstance(account);
            for (DeletedDialog deletedDialog : dialogs) {
                long dialogId = deletedDialog.dialogId;
                try {
                    if (DialogObject.isUserDialog(dialogId)) {
                        if (Boolean.TRUE.equals(present.get(dialogId, false))) {
                            putDialog(account, deletedDialog);
                        }
                    } else if (DialogObject.isChatDialog(dialogId)) {
                        if (Boolean.TRUE.equals(present.get(-dialogId, false))) {
                            putDialog(account, deletedDialog);
                        }
                    }
                } catch (Throwable e) {
                    FileLog.e("Failed to put deleted dialog " + dialogId, e);
                }
            }
            mc.sortDialogs(null);
            try {
                if (!users.isEmpty()) {
                    mc.putUsers(users, true);
                }
                if (!chats.isEmpty()) {
                    mc.putChats(chats, true);
                }
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
            } catch (Throwable e) {
                FileLog.e("Failed to reload dialogs after loading deleted dialogs", e);
            }
        });
    }

    private void collectDialogIds(List<DeletedDialog> list, ArrayList<Long> userIds, ArrayList<Long> chatIds) {
        for (DeletedDialog deletedDialog : list) {
            long dialogId = deletedDialog.dialogId;
            if (DialogObject.isUserDialog(dialogId)) {
                if (!userIds.contains(dialogId)) {
                    userIds.add(dialogId);
                }
            } else if (DialogObject.isChatDialog(dialogId)) {
                long chatId = -dialogId;
                if (!chatIds.contains(chatId)) {
                    chatIds.add(chatId);
                }
            }
        }
    }

    private void loadUsersAndChats(int account, ArrayList<Long> usersToLoad, ArrayList<Long> chatsToLoad,
                                   ArrayList<TLRPC.User> users, ArrayList<TLRPC.Chat> chats) {
        MessagesStorage storage = MessagesStorage.getInstance(account);
        MessagesController mc = MessagesController.getInstance(account);
        try {
            if (!usersToLoad.isEmpty()) {
                ArrayList<Long> missing = new ArrayList<>();
                for (Long id : usersToLoad) {
                    TLRPC.User user = mc.getUser(id);
                    if (user != null) {
                        users.add(user);
                    } else {
                        missing.add(id);
                    }
                }
                if (!missing.isEmpty()) {
                    storage.getUsersInternal(missing, users);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        try {
            if (!chatsToLoad.isEmpty()) {
                ArrayList<Long> missing = new ArrayList<>();
                for (Long id : chatsToLoad) {
                    TLRPC.Chat chat = mc.getChat(id);
                    if (chat != null) {
                        chats.add(chat);
                    } else {
                        missing.add(id);
                    }
                }
                if (!missing.isEmpty()) {
                    storage.getChatsInternal(TextUtils.join(",", missing), chats);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void putDialog(int account, DeletedDialog entity) {
        MessagesController mc = MessagesController.getInstance(account);
        if (mc.getDialog(entity.dialogId) != null) {
            return;
        }
        TLRPC.TL_dialog dialog = mapEntityToDialog(account, entity);
        mc.dialogs_dict.put(dialog.id, dialog);
        mc.getAllDialogs().add(dialog);
        MessageObject lastMessage = getLastMessageCached(account, entity.dialogId);
        if (lastMessage != null) {
            ArrayList<MessageObject> existing = mc.dialogMessage.get(dialog.id);
            if (existing == null || existing.isEmpty()
                    || existing.get(0) == null
                    || existing.get(0).messageOwner == null
                    || existing.get(0).messageOwner.id < lastMessage.messageOwner.id) {
                ArrayList<MessageObject> list = new ArrayList<>();
                list.add(lastMessage);
                mc.dialogMessage.put(dialog.id, list);
            }
        }
    }

    public static DeletedDialog mapDialogToEntity(TLRPC.Dialog dialog, long userId, int entityCreateDate) {
        DeletedDialog entity = new DeletedDialog();
        entity.userId = userId;
        entity.dialogId = dialog.id;
        entity.peerId = dialog.peer != null ? MessageObject.getPeerId(dialog.peer) : dialog.id;
        entity.folderId = dialog.folder_id;
        entity.topMessage = dialog.top_message;
        entity.lastMessageDate = dialog.last_message_date;
        entity.flags = dialog.flags;
        entity.entityCreateDate = entityCreateDate;
        return entity;
    }

    public static TLRPC.TL_dialog mapEntityToDialog(int account, DeletedDialog entity) {
        TLRPC.TL_dialog dialog = new TLRPC.TL_dialog();
        dialog.id = entity.dialogId;
        dialog.peer = MessagesController.getInstance(account).getPeer(entity.peerId != 0 ? entity.peerId : entity.dialogId);
        if (entity.folderId != null) {
            dialog.folder_id = entity.folderId;
        }
        dialog.top_message = entity.topMessage;
        dialog.last_message_date = entity.lastMessageDate;
        dialog.flags = entity.flags;
        return dialog;
    }

    public void updateDeletedDialogsFolder(int account, List<Long> dialogIds, int folderId) {
        if (dialogIds == null || dialogIds.isEmpty()) {
            return;
        }
        long userId = UserConfig.getInstance(account).clientUserId;
        if (userId == 0) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            try {
                DeletedDialogDao dao = AyuData.getDeletedDialogDao();
                if (dao != null) {
                    dao.updateDialogsFolder(userId, dialogIds, folderId);
                }
            } catch (Throwable e) {
                FileLog.e("updateDeletedDialogsFolder", e);
            }
        });
    }

    public void onOfficialDialogsLoaded(int account, List<Long> dialogIds) {
        if (dialogIds == null || dialogIds.isEmpty()) {
            return;
        }
        if (!NaConfig.INSTANCE.getEnableSaveDeletedMessages().Bool()) {
            return;
        }
        long userId = UserConfig.getInstance(account).clientUserId;
        if (userId == 0) {
            return;
        }
        ArrayList<Long> ids = new ArrayList<>(dialogIds);
        Utilities.globalQueue.postRunnable(() -> {
            try {
                DeletedDialogDao dao = AyuData.getDeletedDialogDao();
                if (dao != null) {
                    dao.deleteExisting(userId, ids);
                }
                ConcurrentHashMap<Long, MessageObject> map = lastMessagesByAccount.get(account);
                if (map != null) {
                    for (int i = 0; i < ids.size(); i++) {
                        map.remove(ids.get(i));
                    }
                }
            } catch (Throwable e) {
                FileLog.e("onOfficialDialogsLoaded", e);
            }
        });
    }

    public void deleteExistingDialogs(long userId, List<Long> dialogIds) {
        if (dialogIds == null || dialogIds.isEmpty()) {
            return;
        }
        try {
            DeletedDialogDao dao = AyuData.getDeletedDialogDao();
            if (dao != null) {
                dao.deleteExisting(userId, dialogIds);
            }
        } catch (Throwable e) {
            FileLog.e("deleteExistingDialogs", e);
        }
    }

    public void deleteDialogRecord(int account, long userId, long dialogId) {
        try {
            DeletedDialogDao dao = AyuData.getDeletedDialogDao();
            if (dao != null) {
                dao.delete(userId, dialogId);
            }
            ConcurrentHashMap<Long, MessageObject> map = lastMessagesByAccount.get(account);
            if (map != null) {
                map.remove(dialogId);
            }
        } catch (Throwable e) {
            FileLog.e("deleteDialogRecord", e);
        }
    }

    public void deleteDialogRecord(long userId, long dialogId) {
        deleteDialogRecord(UserConfig.selectedAccount, userId, dialogId);
    }

    private static boolean isEmpty(com.radolyn.ayugram.database.entities.AyuMessageBase message) {
        return TextUtils.isEmpty(message.text) && TextUtils.isEmpty(message.mediaPath) && message.documentSerialized == null;
    }
}
