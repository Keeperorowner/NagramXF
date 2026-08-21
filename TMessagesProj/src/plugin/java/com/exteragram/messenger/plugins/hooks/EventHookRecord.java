package com.exteragram.messenger.plugins.hooks;

import java.util.Objects;

public class EventHookRecord implements HookRecord {
    private final String pluginId;
    private final String hookName;
    private final boolean matchSubstring;
    private final int priority;

    public EventHookRecord(String pluginId, String hookName, boolean matchSubstring, int priority) {
        this.pluginId = pluginId;
        this.hookName = hookName;
        this.matchSubstring = matchSubstring;
        this.priority = priority;
    }

    public String getPluginId() {
        return pluginId;
    }

    public String getHookName() {
        return hookName;
    }

    public int getPriority() {
        return priority;
    }

    public boolean isMatchSubstring() {
        return matchSubstring;
    }

    @Override
    public void cleanup() {
    }

    @Override
    public boolean matches(Object obj) {
        if (!(obj instanceof String value) || hookName == null) {
            return false;
        }
        return matchSubstring ? (!hookName.isEmpty() && value.contains(hookName)) : hookName.equals(value);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof EventHookRecord other)) {
            return false;
        }
        return matchSubstring == other.matchSubstring && Objects.equals(pluginId, other.pluginId) && Objects.equals(hookName, other.hookName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pluginId, hookName, matchSubstring);
    }
}
