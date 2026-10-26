package com.exteragram.messenger.ai.network.backend;

/** Receives streamed and final output of a generation request. */
public interface BackendSink {
    void onChunk(String chunk);

    void onResponse(String response);

    void onError(int code, String message);

    default void onThinking() {
    }
}
