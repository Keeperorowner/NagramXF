package com.exteragram.messenger.plugins.hooks;

import org.telegram.messenger.SendMessagesHelper;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

public interface PluginsHooks {
    TLObject executePreRequestHook(String eventName, int account, TLObject request);

    PostRequestResult executePostRequestHook(String eventName, int account, TLObject response, TLRPC.TL_error error);

    TLRPC.Update executeUpdateHook(String eventName, int account, TLRPC.Update update);

    TLRPC.Updates executeUpdatesHook(String eventName, int account, TLRPC.Updates updates);

    SendMessagesHelper.SendMessageParams executeSendMessageHook(int account, SendMessagesHelper.SendMessageParams params);

    class PostRequestResult {
        public TLObject response;
        public TLRPC.TL_error error;

        public PostRequestResult(TLObject response, TLRPC.TL_error error) {
            this.response = response;
            this.error = error;
        }
    }
}
