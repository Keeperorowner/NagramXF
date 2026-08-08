package com.exteragram.messenger.plugins.utils;

import android.content.Context;

import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_bots;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.HashMap;
import java.util.Map;

public class MenuContextBuilder {
    private final Map<String, Object> contextData = new HashMap<>();

    private MenuContextBuilder() {
    }

    public static MenuContextBuilder create() {
        return new MenuContextBuilder();
    }

    public static MenuContextBuilder from(BaseFragment fragment) {
        if (fragment == null) {
            return create();
        }
        return create().withCustom("fragment", fragment).withAccount(fragment.getCurrentAccount()).withContext(fragment.getParentActivity());
    }

    public MenuContextBuilder withAccount(int account) {
        contextData.put("account", account);
        return this;
    }

    public MenuContextBuilder withContext(Context context) {
        if (context != null) {
            contextData.put("context", context);
        }
        return this;
    }

    public MenuContextBuilder withEncryptedChat(TLRPC.EncryptedChat encryptedChat) {
        if (encryptedChat != null) {
            contextData.put("encryptedChat", encryptedChat);
        }
        return this;
    }

    public MenuContextBuilder withChat(TLRPC.Chat chat) {
        if (chat != null) {
            contextData.put("chat", chat);
            contextData.put("chatId", chat.id);
        }
        return this;
    }

    public MenuContextBuilder withChatFull(TLRPC.ChatFull chatFull) {
        if (chatFull != null) {
            contextData.put("chatFull", chatFull);
        }
        return this;
    }

    public MenuContextBuilder withUser(TLRPC.User user) {
        if (user != null) {
            contextData.put("user", user);
            contextData.put("userId", user.id);
        }
        return this;
    }

    public MenuContextBuilder withUserFull(TLRPC.UserFull userFull) {
        if (userFull != null) {
            contextData.put("userFull", userFull);
        }
        return this;
    }

    public MenuContextBuilder withBotInfo(TL_bots.BotInfo botInfo) {
        if (botInfo != null) {
            contextData.put("botInfo", botInfo);
        }
        return this;
    }

    public MenuContextBuilder withDialogId(long dialogId) {
        contextData.put("dialog_id", dialogId);
        return this;
    }

    public MenuContextBuilder withMessage(MessageObject message) {
        if (message != null) {
            contextData.put("message", message);
        }
        return this;
    }

    public MenuContextBuilder withGroupedMessage(MessageObject.GroupedMessages groupedMessages) {
        if (groupedMessages != null) {
            contextData.put("groupedMessages", groupedMessages);
        }
        return this;
    }

    public MenuContextBuilder withCustom(String key, Object value) {
        if (key != null && value != null) {
            contextData.put(key, value);
        }
        return this;
    }

    public Map<String, Object> build() {
        return contextData;
    }
}
