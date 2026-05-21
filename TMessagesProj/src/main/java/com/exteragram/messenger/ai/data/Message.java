package com.exteragram.messenger.ai.data;

import java.util.Objects;

public final class Message {
    private final String role;
    private final String content;
    private transient byte[] imageData;
    private transient String mimeType;

    public Message(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public Message(String role, String content, byte[] imageData, String mimeType) {
        this.role = role;
        this.content = content;
        this.imageData = imageData;
        this.mimeType = mimeType;
    }

    public String role() {
        return role;
    }

    public String content() {
        return content;
    }

    public byte[] getImageData() {
        return imageData;
    }

    public String getMimeType() {
        return mimeType;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        return content.equals(((Message) obj).content);
    }

    @Override
    public int hashCode() {
        return Objects.hash(role, content);
    }
}
