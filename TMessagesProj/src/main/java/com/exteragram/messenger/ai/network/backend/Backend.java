package com.exteragram.messenger.ai.network.backend;

/**
 * Generation backend: turns a request into model output and reports it to the sink.
 * Limits describe what the backend can accept so callers can trim history and scale sliders.
 */
public interface Backend {
    String execute(BackendRequest request, BackendSink sink);

    int getHistoryMessagesLimit();

    int getHistoryCharsLimit();

    int getMaxOutputTokens();

    float getMaxTemperature();

    boolean supportsImages();
}
