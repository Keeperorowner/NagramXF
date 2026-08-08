package com.exteragram.messenger.utils.chats;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

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
