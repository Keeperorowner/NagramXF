package com.exteragram.messenger.plugins.utils;

import android.content.Context;

import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_bots;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.Collections;
import java.util.Map;

/**
 * No-op stand-in for the plugin runtime in the normal flavor.
 */
public class MenuContextBuilder {

    public static MenuContextBuilder create() {
        return new MenuContextBuilder();
    }

    public static MenuContextBuilder from(BaseFragment fragment) {
        return new MenuContextBuilder();
    }

    public MenuContextBuilder withAccount(int account) {
        return this;
    }

    public MenuContextBuilder withContext(Context context) {
        return this;
    }

    public MenuContextBuilder withEncryptedChat(TLRPC.EncryptedChat encryptedChat) {
        return this;
    }

    public MenuContextBuilder withChat(TLRPC.Chat chat) {
        return this;
    }

    public MenuContextBuilder withChatFull(TLRPC.ChatFull chatFull) {
        return this;
    }

    public MenuContextBuilder withUser(TLRPC.User user) {
        return this;
    }

    public MenuContextBuilder withUserFull(TLRPC.UserFull userFull) {
        return this;
    }

    public MenuContextBuilder withBotInfo(TL_bots.BotInfo botInfo) {
        return this;
    }

    public MenuContextBuilder withDialogId(long dialogId) {
        return this;
    }

    public MenuContextBuilder withMessage(MessageObject message) {
        return this;
    }

    public MenuContextBuilder withGroupedMessage(MessageObject.GroupedMessages groupedMessages) {
        return this;
    }

    public MenuContextBuilder withCustom(String key, Object value) {
        return this;
    }

    public Map<String, Object> build() {
        return Collections.emptyMap();
    }
}
