package com.exteragram.messenger.utils;

import org.telegram.messenger.UserConfig;

/**
 * Entry point for plugins that import the chat helpers from this package.
 * The implementation lives in {@link com.exteragram.messenger.utils.chats.ChatUtils}.
 */
public class ChatUtils extends com.exteragram.messenger.utils.chats.ChatUtils {
    private static final ChatUtils[] Instance = new ChatUtils[UserConfig.MAX_ACCOUNT_COUNT];

    public ChatUtils(int account) {
        super(account);
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
}
