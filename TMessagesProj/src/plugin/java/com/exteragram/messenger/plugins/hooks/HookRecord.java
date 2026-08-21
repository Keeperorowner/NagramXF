package com.exteragram.messenger.plugins.hooks;

public interface HookRecord {
    void cleanup();

    boolean matches(Object obj);
}
