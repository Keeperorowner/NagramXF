package com.exteragram.messenger.plugins.hooks;

import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

/**
 * No-op stand-in for the plugin runtime in the normal flavor.
 */
public interface PluginsHooks {

    class PostRequestResult {
        public TLObject response;
        public TLRPC.TL_error error;

        public PostRequestResult(TLObject response, TLRPC.TL_error error) {
            this.response = response;
            this.error = error;
        }
    }
}
