package com.exteragram.messenger.badges;

import tw.nekomimi.nekogram.helpers.remote.TrustedPluginsRemoteHelper;

import java.util.Collections;
import java.util.Set;

public final class BadgesController {
    public static final BadgesController INSTANCE = new BadgesController();
    private final TrustedPluginsRemoteHelper trustedPluginsRemoteHelper = TrustedPluginsRemoteHelper.getInstance();

    private static final Set<Long> TRUSTED_PLUGINS_DEFAULT = Collections.singleton(4422962060L);

    private BadgesController() {
    }

    public void init() {
        trustedPluginsRemoteHelper.preload();
    }

    public boolean isTrusted(long dialogId) {
        return trustedPluginsRemoteHelper.isTrusted(dialogId, TRUSTED_PLUGINS_DEFAULT);
    }
}
