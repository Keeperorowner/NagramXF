package com.exteragram.messenger.utils.chats;

import android.net.Uri;
import android.text.TextUtils;

import com.radolyn.ayugram.controllers.AyuAttachments;

import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.VideoPlayer;

import java.io.File;

public class ChatUtils {
    private static final ChatUtils[] Instance = new ChatUtils[16];

    private final int selectedAccount;

    public ChatUtils(int account) {
        this.selectedAccount = account;
    }

    public static ChatUtils getInstance() {
        return getInstance(UserConfig.selectedAccount);
    }

    public static ChatUtils getInstance(int account) {
        ChatUtils instance = Instance[account];
        if (instance == null) {
            synchronized (Instance) {
                instance = Instance[account];
                if (instance == null) {
                    instance = new ChatUtils(account);
                    Instance[account] = instance;
                }
            }
        }
        return instance;
    }

    public MessagesController getMessagesController() {
        return MessagesController.getInstance(selectedAccount);
    }

    /** Returns the text that stands for the message when it is exported or copied. */
    public CharSequence getMessageText(MessageObject messageObject, MessageObject.GroupedMessages groupedMessages) {
        if (messageObject == null) {
            return null;
        }
        return messageObject.getMessageTextToTranslate(groupedMessages, null);
    }

    /** Resolves the file that currently holds the message media, or null when nothing is downloaded. */
    public String getPathToMessage(MessageObject messageObject) {
        if (messageObject == null) {
            return null;
        }
        FileLoader fileLoader = FileLoader.getInstance(messageObject.currentAccount);
        TLRPC.Message message = messageObject.messageOwner;
        if (message != null && !TextUtils.isEmpty(message.attachPath)) {
            String attachPath = message.attachPath;
            if (fileExists(attachPath)) {
                return attachPath;
            }
        }
        if (message != null) {
            String path = fileLoader.getPathToMessage(message).toString();
            if (fileExists(path)) {
                return path;
            }
        }
        if (messageObject.getDocument() != null) {
            String path = fileLoader.getPathToAttach(messageObject.getDocument(), false).toString();
            if (fileExists(path)) {
                return path;
            }
            path = fileLoader.getPathToAttach(messageObject.getDocument(), true).toString();
            if (fileExists(path)) {
                return path;
            }
        }
        VideoPlayer.VideoUri cachedQuality = messageObject.cachedQuality;
        if (cachedQuality != null && cachedQuality.isCached()) {
            Uri uri = cachedQuality.uri;
            if (uri != null && fileExists(uri.getPath())) {
                return uri.getPath();
            }
        }
        TLRPC.Document document = messageObject.qualityToSave;
        if (document != null) {
            String path = fileLoader.getPathToAttach(document, null, false, true).toString();
            if (fileExists(path)) {
                return path;
            }
        }
        String existingPath = AyuAttachments.getInstance(selectedAccount).getExistingPath(messageObject, false);
        if (fileExists(existingPath)) {
            return existingPath;
        }
        return null;
    }

    private static boolean fileExists(String path) {
        if (TextUtils.isEmpty(path)) {
            return false;
        }
        File file = new File(path);
        return file.exists() && file.isFile();
    }

    public void resolveChannel(String username, final Utilities.Callback<TLRPC.Chat> callback) {
        getMessagesController().getUserNameResolver().resolve(username, peerId -> {
            if (peerId != null && peerId < 0) {
                callback.run(getMessagesController().getChat(-peerId));
            } else {
                callback.run(null);
            }
        });
    }
}
