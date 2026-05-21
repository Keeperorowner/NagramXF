package com.exteragram.messenger.ai.data;

import com.exteragram.messenger.ai.AiConfig;

import java.io.Serializable;

public class Service implements Serializable {

    public static final int PROTOCOL_OPENAI = 0;
    public static final int PROTOCOL_CLAUDE = 1;
    public static final int PROTOCOL_GEMINI = 2;

    private String url;
    private String model;
    private String key;
    private int protocol;

    public Service(String url, String model, String key) {
        this(url, model, key, PROTOCOL_OPENAI);
    }

    public Service(String url, String model, String key, int protocol) {
        this.url = url;
        this.model = model;
        this.key = key;
        this.protocol = protocol;
    }

    public String getUrl() {
        return url;
    }

    public String getModel() {
        return model;
    }

    public String getShortModel() {
        String[] parts = model.split("/");
        String last = parts[parts.length - 1];
        int colonIdx = last.indexOf(':');
        return colonIdx != -1 ? last.substring(0, colonIdx) : last;
    }

    public String getKey() {
        return key;
    }

    public int getProtocol() {
        return protocol;
    }

    public void setProtocol(int protocol) {
        this.protocol = protocol;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        return hashCode() == obj.hashCode();
    }

    @Override
    public int hashCode() {
        return (url + model + key).hashCode();
    }

    public boolean isSelected() {
        return Integer.valueOf(AiConfig.getSelectedService()).equals(Integer.valueOf(hashCode()));
    }
}
