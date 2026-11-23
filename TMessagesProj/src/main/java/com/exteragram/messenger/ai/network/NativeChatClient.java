package com.exteragram.messenger.ai.network;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import com.exteragram.messenger.ai.data.Service;

import org.telegram.messenger.FileLog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import tw.nekomimi.nekogram.llm.net.OpenAICompatClient;
import tw.nekomimi.nekogram.llm.utils.ReasoningContentFilter;
import tw.nekomimi.nekogram.utils.HttpClient;

/**
 * Speaks the native Anthropic and Google protocols, which do not share the OpenAI chat
 * completions shape: different endpoints, different auth headers and different JSON bodies.
 */
public final class NativeChatClient {

    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private NativeChatClient() {
    }

    /** Native messages endpoint for Anthropic; generateContent endpoint for Google. */
    public static Call newCall(OkHttpClient client, int protocol, String baseUrl, String apiKey,
                               String model, String requestJson, boolean stream) {
        if (TextUtils.isEmpty(baseUrl) || TextUtils.isEmpty(apiKey) || TextUtils.isEmpty(model)) {
            return null;
        }
        Request.Builder builder;
        if (protocol == Service.PROTOCOL_CLAUDE) {
            builder = new Request.Builder()
                    .url(baseUrl + "/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION);
        } else if (protocol == Service.PROTOCOL_GEMINI) {
            String action = stream ? ":streamGenerateContent?alt=sse" : ":generateContent";
            builder = new Request.Builder()
                    .url(baseUrl + "/models/" + model + action)
                    .header("x-goog-api-key", apiKey);
        } else {
            return null;
        }
        RequestBody body = RequestBody.create(requestJson, HttpClient.MEDIA_TYPE_JSON);
        return client.newCall(builder.post(body).build());
    }

    /** Blocking request; returns the concatenated text of the first candidate. */
    public static OpenAICompatClient.LlmResponse<String> execute(Call call, int protocol) {
        long start = System.currentTimeMillis();
        try (Response response = call.execute()) {
            ResponseBody responseBody = response.body();
            String body = responseBody != null ? responseBody.string() : "";
            long duration = System.currentTimeMillis() - start;
            int code = response.code();
            if (!response.isSuccessful()) {
                return new OpenAICompatClient.LlmResponse<>(null, formatError(code, body), duration, code);
            }
            String content = parseContent(body, protocol);
            if (TextUtils.isEmpty(content)) {
                return new OpenAICompatClient.LlmResponse<>(null, "Empty content: " + truncate(body), duration, code);
            }
            return new OpenAICompatClient.LlmResponse<>(
                    ReasoningContentFilter.stripReasoningMarkup(content), null, duration, code);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            return new OpenAICompatClient.LlmResponse<>(null, e.toString(), duration, 0);
        }
    }

    /**
     * Streams both protocols through their server-sent events. Anthropic emits typed events with
     * incremental text deltas; Google emits one full candidate per event.
     */
    public static void stream(Call call, int protocol, int minChunkSize, OpenAICompatClient.StreamCallback callback) {
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (!call.isCanceled()) {
                    callback.onError(0, e.toString());
                }
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (ResponseBody body = response.body()) {
                    if (!response.isSuccessful()) {
                        String raw = body != null ? body.string() : "";
                        callback.onError(response.code(), formatError(response.code(), raw));
                        return;
                    }
                    if (body == null) {
                        callback.onError(response.code(), "Empty response body");
                        return;
                    }
                    StringBuilder fullResponse = new StringBuilder();
                    ReasoningContentFilter filter = new ReasoningContentFilter();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(body.byteStream()));
                    String line;
                    boolean sawThinking = false;
                    int chunkSize = 0;
                    while ((line = reader.readLine()) != null) {
                        if (call.isCanceled()) {
                            return;
                        }
                        if (!line.startsWith("data:")) {
                            continue;
                        }
                        String payload = line.substring(5).trim();
                        if (payload.isEmpty()) {
                            continue;
                        }
                        if ("[DONE]".equals(payload)) {
                            String remaining = filter.flush();
                            if (!TextUtils.isEmpty(remaining)) {
                                fullResponse.append(remaining);
                            }
                            if (chunkSize > 0) {
                                callback.onChunk(fullResponse.toString());
                            }
                            break;
                        }
                        JSONObject event;
                        try {
                            event = new JSONObject(payload);
                        } catch (Exception e) {
                            continue;
                        }
                        if (!sawThinking && isThinkingEvent(event, protocol)) {
                            sawThinking = true;
                            callback.onThinking();
                        }
                        String delta = parseStreamDelta(event, protocol);
                        if (delta == null) {
                            continue;
                        }
                        String filtered = filter.filter(delta);
                        if (filter.consumeReasoningSignal() && !sawThinking) {
                            sawThinking = true;
                            callback.onThinking();
                        }
                        if (TextUtils.isEmpty(filtered)) {
                            continue;
                        }
                        fullResponse.append(filtered);
                        chunkSize += filtered.length();
                        if (chunkSize >= minChunkSize) {
                            callback.onChunk(fullResponse.toString());
                            chunkSize = 0;
                        }
                    }
                    if (call.isCanceled()) {
                        return;
                    }
                    String full = ReasoningContentFilter.stripReasoningMarkup(fullResponse.toString().trim());
                    callback.onComplete(full);
                } catch (Exception e) {
                    FileLog.e("AI native stream error: ", e);
                    callback.onError(0, e.toString());
                }
            }
        });
    }

    /**
     * Lists the models each native protocol exposes: Anthropic returns data[].id, Google returns
     * models[].name with a "models/" prefix that the request path adds itself.
     */
    public static OpenAICompatClient.LlmResponse<List<String>> fetchModels(int protocol, String baseUrl, String apiKey) {
        if (TextUtils.isEmpty(baseUrl) || TextUtils.isEmpty(apiKey)) {
            return new OpenAICompatClient.LlmResponse<>(null, "Empty base URL or API key", 0, 0);
        }
        long start = System.currentTimeMillis();
        Request.Builder builder = new Request.Builder().url(baseUrl + "/models").get();
        if (protocol == Service.PROTOCOL_CLAUDE) {
            builder.header("x-api-key", apiKey).header("anthropic-version", ANTHROPIC_VERSION);
        } else if (protocol == Service.PROTOCOL_GEMINI) {
            builder.header("x-goog-api-key", apiKey);
        } else {
            return new OpenAICompatClient.LlmResponse<>(null, "Not a native protocol", 0, 0);
        }
        try (Response response = plainClient().newCall(builder.build()).execute()) {
            ResponseBody responseBody = response.body();
            String body = responseBody != null ? responseBody.string() : "";
            long duration = System.currentTimeMillis() - start;
            int code = response.code();
            if (!response.isSuccessful()) {
                return new OpenAICompatClient.LlmResponse<>(null, formatError(code, body), duration, code);
            }
            List<String> models = parseModelIds(body, protocol);
            if (models.isEmpty()) {
                return new OpenAICompatClient.LlmResponse<>(null, "No models found: " + truncate(body), duration, code);
            }
            return new OpenAICompatClient.LlmResponse<>(models, null, duration, code);
        } catch (Exception e) {
            return new OpenAICompatClient.LlmResponse<>(null, e.toString(), System.currentTimeMillis() - start, 0);
        }
    }

    private static List<String> parseModelIds(String body, int protocol) {
        List<String> models = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(body);
            JSONArray array = protocol == Service.PROTOCOL_CLAUDE
                    ? root.optJSONArray("data") : root.optJSONArray("models");
            if (array == null) return models;
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String id = protocol == Service.PROTOCOL_CLAUDE
                        ? item.optString("id") : item.optString("name");
                if (id.startsWith("models/")) {
                    id = id.substring("models/".length());
                }
                if (!TextUtils.isEmpty(id)) {
                    models.add(id);
                }
            }
        } catch (Exception ignored) {
        }
        return models;
    }

    /** Text of the first message for Anthropic, first candidate for Google. */
    public static String parseContent(String body, int protocol) {
        try {
            if (protocol == Service.PROTOCOL_CLAUDE) {
                JSONArray blocks = new JSONObject(body).optJSONArray("content");
                if (blocks == null) return null;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < blocks.length(); i++) {
                    JSONObject block = blocks.optJSONObject(i);
                    if (block == null || !"text".equals(block.optString("type"))) continue;
                    String text = block.optString("text", "");
                    if (!text.isEmpty()) sb.append(text);
                }
                return sb.length() > 0 ? sb.toString() : null;
            }
            return parseGeminiCandidate(new JSONObject(body));
        } catch (Exception e) {
            return null;
        }
    }

    private static String parseGeminiCandidate(JSONObject root) {
        JSONArray candidates = root.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) return null;
        JSONObject candidate = candidates.optJSONObject(0);
        if (candidate == null) return null;
        JSONObject content = candidate.optJSONObject("content");
        if (content == null) return null;
        JSONArray parts = content.optJSONArray("parts");
        if (parts == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.optJSONObject(i);
            if (part == null) continue;
            String text = part.optString("text", "");
            if (!text.isEmpty()) sb.append(text);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private static String parseStreamDelta(JSONObject event, int protocol) {
        if (protocol == Service.PROTOCOL_CLAUDE) {
            String type = event.optString("type");
            if ("content_block_delta".equals(type)) {
                JSONObject delta = event.optJSONObject("delta");
                if (delta == null) return null;
                String text = delta.optString("text", "");
                return text.isEmpty() ? null : text;
            }
            if ("content_block_start".equals(type)) {
                JSONObject block = event.optJSONObject("content_block");
                if (block == null) return null;
                String text = block.optString("text", "");
                return text.isEmpty() ? null : text;
            }
            return null;
        }
        try {
            return parseGeminiCandidate(event);
        } catch (Exception e) {
            return null;
        }
    }

    /** Anthropic announces a reasoning block before any text arrives. */
    private static boolean isThinkingEvent(JSONObject event, int protocol) {
        if (protocol != Service.PROTOCOL_CLAUDE) {
            return false;
        }
        if (!"content_block_start".equals(event.optString("type"))) {
            return false;
        }
        JSONObject block = event.optJSONObject("content_block");
        return block != null && "thinking".equals(block.optString("type"));
    }

    private static String formatError(int code, String body) {
        String detail = body;
        try {
            JSONObject json = new JSONObject(body);
            JSONObject error = json.optJSONObject("error");
            if (error != null) {
                String message = error.optString("message");
                if (!message.isEmpty()) detail = message;
            }
        } catch (Exception ignored) {
        }
        return "HTTP " + code + " : " + truncate(detail);
    }

    private static String truncate(String value) {
        if (value == null) return "";
        return value.length() > 4096 ? value.substring(0, 4096) : value;
    }

    static OkHttpClient streamClient() {
        return HttpClient.INSTANCE.getLlmStreamInstance();
    }

    static OkHttpClient plainClient() {
        return HttpClient.INSTANCE.getLlmInstance();
    }

    static OkHttpClient testClient() {
        return plainClient().newBuilder()
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .build();
    }
}
