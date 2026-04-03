package tw.nekomimi.nekogram.llm.net;

import static org.telegram.messenger.LocaleController.getString;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import tw.nekomimi.nekogram.llm.utils.LlmModelUtil;
import tw.nekomimi.nekogram.llm.utils.ReasoningContentFilter;
import tw.nekomimi.nekogram.utils.HttpClient;

public final class OpenAICompatClient {

    private static final OkHttpClient httpClient = HttpClient.INSTANCE.getLlmInstance();
    private static final OkHttpClient testHttpClient = httpClient.newBuilder()
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build();

    private OpenAICompatClient() {
    }

    public record LlmResponse<T>(T data, String error, long durationMs, int httpCode) {

        public boolean isSuccess() {
            return error == null;
        }
    }

    public interface StreamCallback {
        void onChunk(String accumulatedContent);

        void onThinking();

        void onComplete(String fullContent);

        void onError(int httpCode, String error);
    }

    private static String validateRequest(String baseUrl, String apiKey) {
        String requestBaseUrl = baseUrl != null ? baseUrl.trim() : "";
        if (requestBaseUrl.isEmpty()) {
            return "Empty base URL";
        }
        String key = apiKey != null ? apiKey.trim() : "";
        if (key.isEmpty()) {
            return getString(R.string.ApiKeyNotSet);
        }
        if (key.indexOf('\r') >= 0 || key.indexOf('\n') >= 0) {
            return "Invalid API key";
        }
        return null;
    }

    public static LlmResponse<List<String>> fetchModels(String baseUrl, String apiKey) {
        String validationError = validateRequest(baseUrl, apiKey);
        if (validationError != null) {
            return new LlmResponse<>(null, validationError, 0, 0);
        }
        String requestBaseUrl = baseUrl.trim();
        String key = apiKey.trim();

        long start = System.currentTimeMillis();

        try (Response response = httpClient.newCall(new Request.Builder()
                .url(requestBaseUrl + "/models")
                .header("Authorization", "Bearer " + key)
                .get()
                .build()).execute()) {
            String body = response.body().string();
            long duration = System.currentTimeMillis() - start;
            int code = response.code();
            if (!response.isSuccessful()) {
                return new LlmResponse<>(null, formatHttpError(code, body), duration, code);
            }
            List<String> models;
            try {
                models = parseModelIds(body);
            } catch (Exception e) {
                return new LlmResponse<>(null, "Parse error: " + e + " ; raw=" + truncate(body), duration, code);
            }
            if (isGeminiModelsEndpoint(requestBaseUrl)) {
                models = LlmModelUtil.stripModelsPrefix(models);
            }
            if (models.isEmpty()) {
                return new LlmResponse<>(null, "No models found: " + truncate(body), duration, code);
            }
            return new LlmResponse<>(models, null, duration, code);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            return new LlmResponse<>(null, e.toString(), duration, 0);
        }
    }

    public static LlmResponse<String> testChatCompletions(String baseUrl, String apiKey, String model) {
        String modelName = model != null ? model.trim() : "";
        if (modelName.isEmpty()) {
            return new LlmResponse<>(null, "Model is empty", 0, 0);
        }

        JSONObject requestJson;
        try {
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject()
                    .put("role", "user")
                    .put("content", "This is a test. Reply with a single word: OK"));
            requestJson = new JSONObject()
                    .put("model", modelName)
                    .put("messages", messages);
            LlmModelUtil.applyReasoningParameters(requestJson, baseUrl, modelName);
        } catch (Exception e) {
            return new LlmResponse<>(null, e.toString(), 0, 0);
        }

        LlmResponse<String> response = chatCompletions(baseUrl, apiKey, requestJson.toString(), testHttpClient);
        if (!response.isSuccess()) {
            return response;
        }
        return new LlmResponse<>(
                LlmModelUtil.sanitizeResponse(modelName, response.data()),
                null,
                response.durationMs(),
                response.httpCode()
        );
    }

    public static LlmResponse<String> chatCompletions(String baseUrl, String apiKey, String requestJson) {
        return chatCompletions(baseUrl, apiKey, requestJson, httpClient);
    }

    private static LlmResponse<String> chatCompletions(String baseUrl, String apiKey, String requestJson, OkHttpClient client) {
        String validationError = validateRequest(baseUrl, apiKey);
        if (validationError != null) {
            return new LlmResponse<>(null, validationError, 0, 0);
        }
        return executeChatCompletions(newChatCompletionsCall(client, baseUrl, apiKey, requestJson));
    }

    public static Call newChatCompletionsCall(OkHttpClient client, String baseUrl, String apiKey, String requestJson) {
        if (validateRequest(baseUrl, apiKey) != null) {
            return null;
        }
        RequestBody requestBody = RequestBody.create(requestJson, HttpClient.MEDIA_TYPE_JSON);
        Request request = new Request.Builder()
                .url(baseUrl.trim() + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey.trim())
                .post(requestBody)
                .build();
        return client.newCall(request);
    }

    public static LlmResponse<String> executeChatCompletions(Call call) {
        long start = System.currentTimeMillis();
        try (Response response = call.execute()) {
            ResponseBody responseBody = response.body();
            String body = responseBody != null ? responseBody.string() : "";
            long duration = System.currentTimeMillis() - start;
            int code = response.code();
            if (!response.isSuccessful()) {
                return new LlmResponse<>(null, formatHttpError(code, body), duration, code);
            }
            String content = parseFirstMessageContent(body);
            if (content == null || content.trim().isEmpty()) {
                return new LlmResponse<>(null, "Empty content: " + truncate(body), duration, code);
            }
            return new LlmResponse<>(content.trim(), null, duration, code);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            return new LlmResponse<>(null, e.toString(), duration, 0);
        }
    }

    public static void streamChatCompletions(Call call, int minChunkSize, StreamCallback callback) {
        int chunkLimit = Math.max(1, minChunkSize);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (!call.isCanceled()) {
                    callback.onError(0, e.toString());
                }
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (Response ignored = response) {
                    if (!response.isSuccessful()) {
                        ResponseBody errorBody = response.body();
                        callback.onError(response.code(), formatHttpError(response.code(), errorBody != null ? errorBody.string() : ""));
                        return;
                    }
                    ResponseBody body = response.body();
                    if (body == null) {
                        callback.onError(response.code(), "Empty response body");
                        return;
                    }
                    readStream(call, body, chunkLimit, callback);
                } catch (Exception e) {
                    if (!call.isCanceled()) {
                        callback.onError(0, e.toString());
                    }
                }
            }
        });
    }

    private static void readStream(Call call, ResponseBody body, int chunkLimit, StreamCallback callback) {
        StringBuilder fullResponse = new StringBuilder();
        ReasoningContentFilter reasoningFilter = new ReasoningContentFilter();
        boolean reasoningNotified = false;
        Exception streamError = null;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body.byteStream()))) {
            int chunkSize = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                if (call.isCanceled()) return;
                if (line.isEmpty() || !line.startsWith("data: ")) continue;

                String data = line.substring(6).trim();
                if (data.equals("[DONE]")) {
                    String remaining = reasoningFilter.flush();
                    if (!TextUtils.isEmpty(remaining)) {
                        fullResponse.append(remaining);
                    }
                    if (chunkSize > 0) {
                        callback.onChunk(fullResponse.toString());
                    }
                    break;
                }

                StreamResponsePart part = parseStreamResponsePart(data);
                if (part.hasReasoning && !reasoningNotified) {
                    reasoningNotified = true;
                    callback.onThinking();
                }

                String content = part.content;
                if (!TextUtils.isEmpty(content)) {
                    String filtered = reasoningFilter.filter(content);
                    if (reasoningFilter.consumeReasoningSignal() && !reasoningNotified) {
                        reasoningNotified = true;
                        callback.onThinking();
                    }
                    if (!TextUtils.isEmpty(filtered)) {
                        fullResponse.append(filtered);
                        chunkSize += filtered.length();
                        if (chunkSize >= chunkLimit) {
                            callback.onChunk(fullResponse.toString());
                            chunkSize = 0;
                        }
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
            streamError = e;
        }

        if (call.isCanceled()) return;
        String finalResponse = fullResponse.toString().trim();
        if (!finalResponse.isEmpty()) {
            callback.onComplete(finalResponse);
        } else if (streamError != null) {
            callback.onError(0, streamError.toString());
        } else {
            callback.onComplete("");
        }
    }

    private static StreamResponsePart parseStreamResponsePart(String data) {
        try {
            JSONObject json = new JSONObject(data);
            JSONArray choices = json.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                if (delta == null) return new StreamResponsePart("", false);
                Object content = delta.opt("content");
                String text = (content == null || content == JSONObject.NULL) ? "" : content.toString();
                return new StreamResponsePart(text, hasReasoning(delta));
            }
        } catch (Exception ignored) {
        }
        return new StreamResponsePart("", false);
    }

    private static boolean hasReasoning(JSONObject delta) {
        return hasValue(delta, "reasoning") || hasValue(delta, "reasoning_content") || hasValue(delta, "reasoning_details");
    }

    private static boolean hasValue(JSONObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.isNull(key)) return false;
        Object val = obj.opt(key);
        if (val instanceof String) return !TextUtils.isEmpty((String) val);
        if (val instanceof JSONArray) return ((JSONArray) val).length() > 0;
        return val != null && val != JSONObject.NULL;
    }

    private static class StreamResponsePart {
        final String content;
        final boolean hasReasoning;

        StreamResponsePart(String content, boolean hasReasoning) {
            this.content = content;
            this.hasReasoning = hasReasoning;
        }
    }

    private static String formatHttpError(int code, String body) {
        return String.format(Locale.ROOT, "HTTP %d : %s", code, truncate(body));
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        final int limit = 4096;
        if (s.length() <= limit) {
            return s;
        }
        return s.substring(0, limit) + "\n…(truncated)";
    }

    private static String parseFirstMessageContent(String body) {
        try {
            JSONObject json = new JSONObject(body);
            JSONArray choices = json.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                return null;
            }
            JSONObject first = choices.getJSONObject(0);
            JSONObject message = first.optJSONObject("message");
            if (message == null) {
                return null;
            }
            return message.optString("content", null);
        } catch (Exception ignore) {
            return null;
        }
    }

    private static List<String> parseModelIds(String body) throws Exception {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        String trimmed = body != null ? body.trim() : "";
        if (trimmed.isEmpty()) {
            return new ArrayList<>();
        }

        if (trimmed.startsWith("[")) {
            JSONArray array = new JSONArray(trimmed);
            extractModelIdsFromArray(array, out);
        } else {
            JSONObject json = new JSONObject(trimmed);
            if (json.has("data") && json.get("data") instanceof JSONArray) {
                extractModelIdsFromArray(json.getJSONArray("data"), out);
            } else if (json.has("models") && json.get("models") instanceof JSONArray) {
                extractModelIdsFromArray(json.getJSONArray("models"), out);
            } else if (json.has("data") && json.get("data") instanceof JSONObject) {
                JSONObject data = json.getJSONObject("data");
                if (data.has("id")) {
                    String id = data.optString("id", "").trim();
                    if (!id.isEmpty()) out.add(id);
                }
            }
        }

        return new ArrayList<>(out);
    }

    private static void extractModelIdsFromArray(JSONArray array, LinkedHashSet<String> out) {
        for (int i = 0; i < array.length(); i++) {
            Object item = array.opt(i);
            if (item instanceof JSONObject obj) {
                String id = obj.optString("id", "").trim();
                if (!id.isEmpty()) {
                    out.add(id);
                }
            } else if (item instanceof String s) {
                String id = s.trim();
                if (!id.isEmpty()) {
                    out.add(id);
                }
            }
        }
    }

    private static boolean isGeminiModelsEndpoint(String baseUrl) {
        return baseUrl != null && baseUrl.toLowerCase(Locale.ROOT).contains("generativelanguage.googleapis.com");
    }
}
