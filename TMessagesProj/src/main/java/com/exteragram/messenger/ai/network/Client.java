package com.exteragram.messenger.ai.network;

import android.text.TextUtils;
import android.util.Base64;

import com.exteragram.messenger.ai.AiConfig;
import com.exteragram.messenger.ai.AiController;
import com.exteragram.messenger.ai.data.Message;
import com.exteragram.messenger.ai.data.Role;
import com.exteragram.messenger.ai.data.Service;
import com.exteragram.messenger.utils.network.ExteraHttpClient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SharedConfig;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class Client {

    private static final int STREAM_SYMBOLS_LIMIT = SharedConfig.getDevicePerformanceClass() >= 1 ? 10 : 20;
    private static final MediaType JSON_TYPE = MediaType.parse("application/json");

    private static final ExecutorService SHARED_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "AiClientWorker");
        t.setDaemon(true);
        return t;
    });

    private final OkHttpClient httpClient;
    private final Service serviceOverride;
    private final Role roleOverride;
    private final AtomicBoolean isGenerating = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, Boolean> activeRequests = new ConcurrentHashMap<>();

    private Client(Builder builder) {
        this.serviceOverride = builder.serviceOverride;
        this.roleOverride = builder.roleOverride;
        this.httpClient = ExteraHttpClient.INSTANCE.getClient().newBuilder()
                .connectTimeout(1, TimeUnit.MINUTES)
                .readTimeout(5, TimeUnit.MINUTES)
                .build();
    }

    private Service getSelectedService() {
        return serviceOverride != null ? serviceOverride : AiController.getInstance().getSelected();
    }

    public String getResponse(String prompt, GenerationCallback callback) {
        return getResponse(prompt, false, false, null, callback);
    }

    public String getResponse(String prompt, boolean useHistory, boolean stream, String imagePath, GenerationCallback callback) {
        String requestId = UUID.randomUUID().toString();
        activeRequests.put(requestId, true);
        SHARED_EXECUTOR.execute(() -> executeRequest(prompt, stream, useHistory, imagePath, requestId, callback));
        return requestId;
    }

    private void executeRequest(String prompt, boolean stream, boolean useHistory, String imagePath, String requestId, GenerationCallback callback) {
        isGenerating.set(true);
        try {
            byte[] imageData = null;
            String mimeType = null;
            if (AiController.canSendImage(imagePath)) {
                imageData = loadImageToByteArray(imagePath);
                mimeType = getMimeType(imagePath);
            }

            Service service = getSelectedService();
            ArrayList<Message> conversationHistory = useHistory
                    ? new ArrayList<>(AiConfig.getConversationHistory())
                    : new ArrayList<>();

            Request request = createRequest(service, prompt, stream, conversationHistory, imageData, mimeType);
            if (request == null) {
                stopRequest(requestId);
                AndroidUtilities.runOnUIThread(() -> callback.onError(500, "Failed to create request body"));
                return;
            }

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    if (response.body() != null) {
                        try {
                            FileLog.e("AI_ERROR_RESPONSE_BODY (" + response.code() + "): " + response.body().string());
                        } catch (IOException e) {
                            FileLog.e("AI_ERROR_READING_RESPONSE_BODY: ", e);
                        }
                    }
                    stopRequest(requestId);
                    final int code = response.code();
                    final String msg = response.message().toLowerCase(Locale.ROOT);
                    AndroidUtilities.runOnUIThread(() -> callback.onError(code, msg));
                    return;
                }

                ResponseBody body = response.body();
                if (body == null) {
                    stopRequest(requestId);
                    AndroidUtilities.runOnUIThread(() -> callback.onError(500, "Response body is null"));
                    return;
                }

                if (stream) {
                    handleStreamResponse(body, service, requestId, useHistory, conversationHistory, callback);
                } else {
                    String content = parseResponseContent(body.string(), false, service.getProtocol());
                    if (content == null || content.trim().isEmpty()) {
                        stopRequest(requestId);
                        AndroidUtilities.runOnUIThread(() -> callback.onError(500, "Failed to parse response"));
                    } else {
                        if (useHistory) {
                            conversationHistory.add(new Message("assistant", content));
                            AiConfig.saveConversationHistory(conversationHistory);
                        }
                        final String result = content.trim();
                        AndroidUtilities.runOnUIThread(() -> {
                            callback.onResponse(result);
                            stopRequest(requestId);
                        });
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e("AI Error: ", e);
            stopRequest(requestId);
            AndroidUtilities.runOnUIThread(() -> callback.onError(500, e.getMessage() != null ? e.getMessage() : "Unknown error"));
        }
    }


    private Request createRequest(Service service, String prompt, boolean stream, ArrayList<Message> conversationHistory, byte[] imageData, String mimeType) {
        switch (service.getProtocol()) {
            case Service.PROTOCOL_CLAUDE:
                return createClaudeRequest(service, prompt, stream, conversationHistory, imageData, mimeType);
            case Service.PROTOCOL_GEMINI:
                return createGeminiRequest(service, prompt, stream, conversationHistory, imageData, mimeType);
            default:
                return createOpenAIRequest(service, prompt, stream, conversationHistory, imageData, mimeType);
        }
    }


    private Request createOpenAIRequest(Service service, String prompt, boolean stream, ArrayList<Message> conversationHistory, byte[] imageData, String mimeType) {
        String url = service.getUrl();
        if (TextUtils.isEmpty(url)) return null;

        String endpoint = url.endsWith("/") ? url + "chat/completions" : url + "/chat/completions";
        boolean useHistory = !conversationHistory.isEmpty();

        try {
            JSONObject json = new JSONObject();
            JSONArray messages = new JSONArray();

            Role selectedRole = roleOverride;
            if (selectedRole == null) {
                selectedRole = serviceOverride == null ? AiController.getInstance().getSelectedRole() : null;
            }
            if (selectedRole != null && !TextUtils.isEmpty(selectedRole.getPrompt())) {
                messages.put(new JSONObject().put("role", "system").put("content", selectedRole.getPrompt()));
            }

            if (useHistory) {
                for (Message msg : conversationHistory) {
                    messages.put(createOpenAIMessageObject(msg));
                }
                Message userMsg = new Message("user", prompt, imageData, mimeType);
                conversationHistory.add(userMsg);
                messages.put(createOpenAIMessageObject(userMsg));
            } else {
                messages.put(createOpenAIMessageObject(new Message("user", prompt, imageData, mimeType)));
            }

            json.put("model", service.getModel());
            json.put("messages", messages);
            json.put("stream", stream);
            json.put("temperature", 1.0);
            json.put("max_tokens", 4096);

            return new Request.Builder()
                    .url(endpoint)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Authorization", "Bearer " + service.getKey())
                    .post(RequestBody.create(json.toString(), JSON_TYPE))
                    .build();
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private JSONObject createOpenAIMessageObject(Message message) throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("role", message.role());
        if (message.getImageData() != null && !TextUtils.isEmpty(message.getMimeType())) {
            JSONArray content = new JSONArray();
            if (!TextUtils.isEmpty(message.content())) {
                content.put(new JSONObject().put("type", "text").put("text", message.content()));
            }
            content.put(new JSONObject()
                    .put("type", "image_url")
                    .put("image_url", new JSONObject()
                            .put("url", "data:" + message.getMimeType() + ";base64," + Base64.encodeToString(message.getImageData(), Base64.NO_WRAP))));
            obj.put("content", content);
        } else {
            obj.put("content", message.content());
        }
        return obj;
    }


    private Request createClaudeRequest(Service service, String prompt, boolean stream, ArrayList<Message> conversationHistory, byte[] imageData, String mimeType) {
        String url = service.getUrl();
        if (TextUtils.isEmpty(url)) return null;

        String endpoint = url.endsWith("/") ? url + "messages" : url + "/messages";
        boolean useHistory = !conversationHistory.isEmpty();

        try {
            JSONObject json = new JSONObject();
            JSONArray messages = new JSONArray();

            Role selectedRole = roleOverride;
            if (selectedRole == null) {
                selectedRole = serviceOverride == null ? AiController.getInstance().getSelectedRole() : null;
            }
            if (selectedRole != null && !TextUtils.isEmpty(selectedRole.getPrompt())) {
                json.put("system", selectedRole.getPrompt());
            }

            if (useHistory) {
                for (Message msg : conversationHistory) {
                    messages.put(createClaudeMessageObject(msg));
                }
                Message userMsg = new Message("user", prompt, imageData, mimeType);
                conversationHistory.add(userMsg);
                messages.put(createClaudeMessageObject(userMsg));
            } else {
                messages.put(createClaudeMessageObject(new Message("user", prompt, imageData, mimeType)));
            }

            json.put("model", service.getModel());
            json.put("messages", messages);
            json.put("max_tokens", 4096);
            if (stream) {
                json.put("stream", true);
            }

            return new Request.Builder()
                    .url(endpoint)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("x-api-key", service.getKey())
                    .addHeader("anthropic-version", "2023-06-01")
                    .post(RequestBody.create(json.toString(), JSON_TYPE))
                    .build();
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private JSONObject createClaudeMessageObject(Message message) throws JSONException {
        JSONObject obj = new JSONObject();
        String role = message.role();
        if ("system".equals(role)) role = "user";
        obj.put("role", role);

        if (message.getImageData() != null && !TextUtils.isEmpty(message.getMimeType())) {
            JSONArray content = new JSONArray();
            content.put(new JSONObject()
                    .put("type", "image")
                    .put("source", new JSONObject()
                            .put("type", "base64")
                            .put("media_type", message.getMimeType())
                            .put("data", Base64.encodeToString(message.getImageData(), Base64.NO_WRAP))));
            if (!TextUtils.isEmpty(message.content())) {
                content.put(new JSONObject().put("type", "text").put("text", message.content()));
            }
            obj.put("content", content);
        } else {
            obj.put("content", message.content());
        }
        return obj;
    }


    private Request createGeminiRequest(Service service, String prompt, boolean stream, ArrayList<Message> conversationHistory, byte[] imageData, String mimeType) {
        String url = service.getUrl();
        if (TextUtils.isEmpty(url)) return null;

        String baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        String endpoint;
        if (stream) {
            endpoint = baseUrl + "/models/" + service.getModel() + ":streamGenerateContent?alt=sse";
        } else {
            endpoint = baseUrl + "/models/" + service.getModel() + ":generateContent";
        }

        String key = service.getKey();
        if (!TextUtils.isEmpty(key)) {
            endpoint += (endpoint.contains("?") ? "&" : "?") + "key=" + key;
        }

        boolean useHistory = !conversationHistory.isEmpty();

        try {
            JSONObject json = new JSONObject();
            JSONArray contents = new JSONArray();

            Role selectedRole = roleOverride;
            if (selectedRole == null) {
                selectedRole = serviceOverride == null ? AiController.getInstance().getSelectedRole() : null;
            }
            if (selectedRole != null && !TextUtils.isEmpty(selectedRole.getPrompt())) {
                json.put("systemInstruction", new JSONObject()
                        .put("parts", new JSONArray().put(new JSONObject().put("text", selectedRole.getPrompt()))));
            }

            if (useHistory) {
                for (Message msg : conversationHistory) {
                    contents.put(createGeminiContentObject(msg));
                }
                Message userMsg = new Message("user", prompt, imageData, mimeType);
                conversationHistory.add(userMsg);
                contents.put(createGeminiContentObject(userMsg));
            } else {
                contents.put(createGeminiContentObject(new Message("user", prompt, imageData, mimeType)));
            }

            json.put("contents", contents);
            json.put("generationConfig", new JSONObject().put("maxOutputTokens", 4096).put("temperature", 1.0));

            return new Request.Builder()
                    .url(endpoint)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(json.toString(), JSON_TYPE))
                    .build();
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private JSONObject createGeminiContentObject(Message message) throws JSONException {
        JSONObject obj = new JSONObject();
        String role = "assistant".equals(message.role()) ? "model" : "user";
        obj.put("role", role);

        JSONArray parts = new JSONArray();
        if (message.getImageData() != null && !TextUtils.isEmpty(message.getMimeType())) {
            parts.put(new JSONObject()
                    .put("inlineData", new JSONObject()
                            .put("mimeType", message.getMimeType())
                            .put("data", Base64.encodeToString(message.getImageData(), Base64.NO_WRAP))));
        }
        if (!TextUtils.isEmpty(message.content())) {
            parts.put(new JSONObject().put("text", message.content()));
        }
        obj.put("parts", parts);
        return obj;
    }


    private void handleStreamResponse(ResponseBody body, Service service, String requestId, boolean useHistory, ArrayList<Message> conversationHistory, GenerationCallback callback) {
        StringBuilder fullResponse = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body.byteStream()))) {
            int chunkSize = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                if (!activeRequests.containsKey(requestId)) break;
                if (TextUtils.isEmpty(line)) continue;

                if (line.startsWith("event: ")) continue;

                if (!line.startsWith("data: ")) continue;

                String data = line.substring(6).trim();
                if (data.equals("[DONE]")) {
                    if (chunkSize > 0) {
                        sendStreamChunk(fullResponse.toString(), callback);
                    }
                    break;
                }

                String content = parseStreamChunk(data, service.getProtocol());
                if (TextUtils.isEmpty(content)) {
                    if (isClaudeStopEvent(data, service.getProtocol())) {
                        if (chunkSize > 0) {
                            sendStreamChunk(fullResponse.toString(), callback);
                        }
                        break;
                    }
                    continue;
                }

                fullResponse.append(content);
                chunkSize += content.length();
                if (chunkSize >= STREAM_SYMBOLS_LIMIT) {
                    sendStreamChunk(fullResponse.toString(), callback);
                    chunkSize = 0;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }

        String finalResponse = fullResponse.toString().trim();
        if (!TextUtils.isEmpty(finalResponse)) {
            if (useHistory) {
                conversationHistory.add(new Message("assistant", finalResponse));
                AiConfig.saveConversationHistory(conversationHistory);
            }
            AndroidUtilities.runOnUIThread(() -> {
                callback.onResponse(finalResponse);
                stopRequest(requestId);
            });
        } else {
            stopRequest(requestId);
        }
    }

    private String parseStreamChunk(String data, int protocol) {
        switch (protocol) {
            case Service.PROTOCOL_CLAUDE:
                return parseClaudeStreamChunk(data);
            case Service.PROTOCOL_GEMINI:
                return parseGeminiContent(data);
            default:
                return parseResponseContent(data, true, Service.PROTOCOL_OPENAI);
        }
    }

    private boolean isClaudeStopEvent(String data, int protocol) {
        if (protocol != Service.PROTOCOL_CLAUDE) return false;
        try {
            JSONObject json = new JSONObject(data);
            String type = json.optString("type");
            return "message_stop".equals(type) || "message_delta".equals(type);
        } catch (Exception e) {
            return false;
        }
    }

    private String parseClaudeStreamChunk(String data) {
        try {
            JSONObject json = new JSONObject(data);
            String type = json.optString("type");
            if ("content_block_delta".equals(type)) {
                JSONObject delta = json.optJSONObject("delta");
                if (delta != null) {
                    return delta.optString("text", null);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return null;
    }

    private String parseGeminiContent(String data) {
        try {
            JSONObject json = new JSONObject(data);
            JSONArray candidates = json.optJSONArray("candidates");
            if (candidates != null && candidates.length() > 0) {
                JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
                if (content != null) {
                    JSONArray parts = content.optJSONArray("parts");
                    if (parts != null && parts.length() > 0) {
                        return parts.getJSONObject(0).optString("text", null);
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return null;
    }


    private String parseResponseContent(String body, boolean isStream, int protocol) {
        switch (protocol) {
            case Service.PROTOCOL_CLAUDE:
                return parseClaudeResponse(body);
            case Service.PROTOCOL_GEMINI:
                return parseGeminiContent(body);
            default:
                return parseOpenAIResponse(body, isStream);
        }
    }

    private String parseOpenAIResponse(String body, boolean isStream) {
        try {
            JSONObject json = new JSONObject(body);
            JSONArray choices = json.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                JSONObject msgObj = choices.getJSONObject(0).optJSONObject(isStream ? "delta" : "message");
                if (msgObj != null && msgObj.has("content")) {
                    return msgObj.optString("content", null);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return null;
    }

    private String parseClaudeResponse(String body) {
        try {
            JSONObject json = new JSONObject(body);
            JSONArray content = json.optJSONArray("content");
            if (content != null) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < content.length(); i++) {
                    JSONObject block = content.getJSONObject(i);
                    if ("text".equals(block.optString("type"))) {
                        sb.append(block.optString("text", ""));
                    }
                }
                return sb.toString();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return null;
    }


    private void sendStreamChunk(String chunk, GenerationCallback callback) {
        if (TextUtils.isEmpty(chunk)) return;
        AndroidUtilities.runOnUIThread(() -> callback.onChunk(chunk));
    }

    public boolean isGenerating() {
        return isGenerating.get();
    }

    public void stopRequest(String requestId) {
        activeRequests.remove(requestId);
        if (activeRequests.isEmpty()) {
            isGenerating.set(false);
        }
    }

    public static String getMimeType(String path) {
        if (TextUtils.isEmpty(path)) return null;
        String lower = path.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".heic") || lower.endsWith(".heif")) return "image/heic";
        return "image/jpeg";
    }

    public static byte[] loadImageToByteArray(String path) {
        if (TextUtils.isEmpty(path)) return null;
        File file = new File(path);
        if (!file.exists() || !file.isFile() || file.length() == 0) return null;
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int read;
            while ((read = fis.read(buf)) != -1) {
                bos.write(buf, 0, read);
            }
            return bos.toByteArray();
        } catch (IOException e) {
            FileLog.e("Error loading image: " + path, e);
            return null;
        }
    }


    public static class Builder {
        private Service serviceOverride;
        private Role roleOverride;

        public Builder serviceOverride(Service service) {
            this.serviceOverride = service;
            return this;
        }

        public Builder roleOverride(Role role) {
            this.roleOverride = role;
            return this;
        }

        public Client build() {
            return new Client(this);
        }
    }
}
