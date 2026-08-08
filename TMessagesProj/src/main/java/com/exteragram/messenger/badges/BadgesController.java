package com.exteragram.messenger.badges;

import tw.nekomimi.nekogram.helpers.remote.TrustedPluginsRemoteHelper;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class BadgesController {
    public static final BadgesController INSTANCE = new BadgesController();
    private final TrustedPluginsRemoteHelper trustedPluginsRemoteHelper = TrustedPluginsRemoteHelper.getInstance();

    private static final Set<Long> OFFICIAL_CHANNELS_DEFAULT = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            1233768168L,
            1524581881L,
            1571726392L,
            1632728092L,
            1172503281L,
            1877362358L
    )));

    private static final Set<Long> TRUSTED_PLUGINS_DEFAULT = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            2562664432L,
            2329949330L,
            2554450010L,
            2189715950L,
            2521243181L,
            2672610568L,
            2618027830L,
            2467705421L,
            2674814323L,
            2340771630L,
            2863704830L,
            2349438816L,
            2782987927L,
            2813564336L,
            2443058177L,
            2475313399L,
            3490813925L
    )));

    private BadgesController() {
    }

    public void init() {
        trustedPluginsRemoteHelper.preload();
    }

    public boolean isTrusted(long dialogId) {
        return trustedPluginsRemoteHelper.isTrusted(dialogId, TRUSTED_PLUGINS_DEFAULT);
    }

    public boolean isExtera(long dialogId) {
        return OFFICIAL_CHANNELS_DEFAULT.contains(dialogId);
    }
}
