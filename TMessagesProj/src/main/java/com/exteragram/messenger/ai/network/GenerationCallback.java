package com.exteragram.messenger.ai.network;

public interface GenerationCallback {
    void onResponse(String response);
    void onChunk(String chunk);
    void onError(int code, String message);
}
