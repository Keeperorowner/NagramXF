package com.exteragram.messenger.ai.network.backend;

import com.exteragram.messenger.ai.network.Client;
import com.exteragram.messenger.ai.network.GenerationCallback;

/**
 * Backend for OpenAI compatible HTTP services. History limits match the JSON body budget used by
 * {@link Client}; the temperature slider is scaled onto the protocol range.
 */
public final class RestBackend implements Backend {

    private static final int HISTORY_MESSAGES_LIMIT = 32;
    private static final int HISTORY_CHARS_LIMIT = 24000;
    private static final int MAX_OUTPUT_TOKENS = 4096;
    private static final float MAX_TEMPERATURE = 2f;

    @Override
    public String execute(BackendRequest request, BackendSink sink) {
        Client.Builder builder = new Client.Builder();
        if (request.getService() != null) {
            builder.serviceOverride(request.getService());
        }
        if (request.getRole() != null) {
            builder.roleOverride(request.getRole());
        }
        return builder.build().getResponse(request.getPrompt(), request.isUseHistory(), request.isStream(), request.getImagePath(), new GenerationCallback() {
            @Override
            public void onResponse(String response) {
                sink.onResponse(response);
            }

            @Override
            public void onChunk(String chunk) {
                sink.onChunk(chunk);
            }

            @Override
            public void onError(int code, String message) {
                sink.onError(code, message);
            }

            @Override
            public void onThinking() {
                sink.onThinking();
            }
        });
    }

    @Override
    public int getHistoryMessagesLimit() {
        return HISTORY_MESSAGES_LIMIT;
    }

    @Override
    public int getHistoryCharsLimit() {
        return HISTORY_CHARS_LIMIT;
    }

    @Override
    public int getMaxOutputTokens() {
        return MAX_OUTPUT_TOKENS;
    }

    @Override
    public float getMaxTemperature() {
        return MAX_TEMPERATURE;
    }

    @Override
    public boolean supportsImages() {
        return true;
    }
}
