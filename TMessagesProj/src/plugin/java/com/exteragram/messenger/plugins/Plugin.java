package com.exteragram.messenger.plugins;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

import java.util.ArrayList;
import java.util.List;

public class Plugin {
    public transient PluginsController.PluginsEngine cachedEngine;

    private String engine;
    private final String id;
    private final String name;
    private String pack;
    private int index = -1;
    private volatile Throwable error;
    private volatile boolean notResponding;
    private List<String> requirements = new ArrayList<>();
    private String version = "1.0";
    private String minVersion = "12.2.10";
    private String appVersion;
    private String sdkVersion = "v" + PythonPluginsEngine.SDK_VERSION;
    private String description = LocaleController.getString(R.string.PluginNoDescription);
    private String author = LocaleController.getString(R.string.PluginNoAuthor);
    private volatile boolean enabled;

    public Plugin(String id, String name) {
        this.id = id;
        this.name = name;
    }

    private static boolean isIconValid(String icon) {
        return icon != null && icon.matches("^[a-zA-Z][a-zA-Z0-9_]*/\\d+$");
    }

    public String getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getEngine() {
        return engine;
    }

    public void setEngine(String engine) {
        this.engine = engine;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getName() {
        return name;
    }

    public boolean isEnabled() {
        return !hasError() && enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled && !hasError();
    }

    public Throwable getError() {
        return error;
    }

    public void setError(Throwable error) {
        this.error = error;
        if (hasError()) {
            enabled = false;
        }
    }

    public boolean hasError() {
        return error != null;
    }

    public boolean isNotResponding() {
        return notResponding;
    }

    public void setNotResponding(boolean notResponding) {
        this.notResponding = notResponding;
    }

    public String getPack() {
        return pack;
    }

    public int getIndex() {
        return index;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getMinVersion() {
        return minVersion;
    }

    public void setMinVersion(String minVersion) {
        this.minVersion = minVersion;
    }

    public String getAppVersion() {
        return appVersion;
    }

    public void setAppVersion(String appVersion) {
        this.appVersion = appVersion;
    }

    public String getSdkVersion() {
        return sdkVersion;
    }

    public void setSdkVersion(String sdkVersion) {
        this.sdkVersion = sdkVersion;
    }

    public List<String> getRequirements() {
        return requirements;
    }

    public void setRequirements(List<String> requirements) {
        this.requirements = requirements != null ? new ArrayList<>(requirements) : new ArrayList<>();
    }

    public String getIcon() {
        if (pack == null || index < 0) {
            return null;
        }
        return pack + "/" + index;
    }

    public void setIcon(String icon) {
        if (!isIconValid(icon)) {
            return;
        }
        int slash = icon.lastIndexOf('/');
        if (slash == -1) {
            return;
        }
        pack = icon.substring(0, slash);
        index = Integer.parseInt(icon.substring(slash + 1));
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Plugin other) {
            return other.id.equals(id);
        }
        return super.equals(obj);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
