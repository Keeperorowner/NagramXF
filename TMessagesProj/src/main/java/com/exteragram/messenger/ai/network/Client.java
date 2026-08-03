package com.exteragram.messenger.ai.network;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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

import tw.nekomimi.nekogram.utils.DnsFactory;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Call;
import okhttp3.Dns;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class Client {

    private static final int STREAM_SYMBOLS_LIMIT = SharedConfig.getDevicePerformanceClass() >= 1 ? 10 : 20;
    private static final int MAX_IMAGE_SIZE = 4 * 1024 * 1024;
    private static final int MAX_HISTORY_MESSAGES = 32;
    private static final int MAX_HISTORY_CHARS = 24000;
    private static final MediaType JSON_TYPE = MediaType.parse("application/json");

    private final OkHttpClient httpClient;
    private final Service serviceOverride;
    private final Role roleOverride;
    private final AtomicBoolean isGenerating = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, ExecutorService> activeRequests = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Call> activeCalls = new ConcurrentHashMap<>();

    private Client(Builder builder) {
        this.serviceOverride = builder.serviceOverride;
        this.roleOverride = builder.roleOverride;
        this.httpClient = ExteraHttpClient.INSTANCE.getClient().newBuilder()
                .dns(hostname -> {
                    List<InetAddress> addresses = DnsFactory.lookup(hostname);
                    return addresses.isEmpty() ? Dns.SYSTEM.lookup(hostname) : addresses;
                })
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
        ExecutorService executor = Executors.newSingleThreadExecutor();
        activeRequests.put(requestId, executor);
        executor.execute(() -> executeRequest(prompt, stream, useHistory, imagePath, requestId, callback));
        return requestId;
    }

    private void executeRequest(String prompt, boolean stream, boolean useHistory, String imagePath, String requestId, GenerationCallback callback) {
        isGenerating.set(true);
        try {
            byte[] imageData = null;
            String mimeType = null;
            if (AiController.canSendImage(imagePath)) {
                ImagePayload payload = loadImagePayload(imagePath);
                if (payload != null) {
                    imageData = payload.data;
                    mimeType = payload.mimeType;
                }
            }

            Service service = getSelectedService();
            ArrayList<Message> conversationHistory = new ArrayList<>();
            if (useHistory) {
                conversationHistory.addAll(trimConversationHistory(AiConfig.getConversationHistory()));
            }

            Message userMessage = new Message("user", prompt, imageData, mimeType);
            Request request = createRequest(service, userMessage, stream, useHistory, conversationHistory);
            if (request == null) {
                notifyErrorAndFinish(requestId, callback, 500, "Failed to create request body");
                return;
            }

            Call call = httpClient.newCall(request);
            activeCalls.put(requestId, call);

            try (Response response = call.execute()) {
                if (!activeRequests.containsKey(requestId)) return;

                if (!response.isSuccessful()) {
                    if (response.body() != null) {
                        try {
                            FileLog.e("AI_ERROR_RESPONSE_BODY (" + response.code() + "): " + response.body().string());
                        } catch (IOException e) {
                            FileLog.e("AI_ERROR_READING_RESPONSE_BODY: ", e);
                        }
                    }
                    notifyErrorAndFinish(requestId, callback, response.code(),
                            response.message().toLowerCase(Locale.ROOT));
                    return;
                }

                ResponseBody body = response.body();
                if (body == null) {
                    notifyErrorAndFinish(requestId, callback, 500, "Response body is null");
                    return;
                }

                if (stream) {
                    handleStreamResponse(body, requestId, userMessage, useHistory, conversationHistory, service, callback);
                } else {
                    String content = parseResponseContent(body.string());
                    if (content == null || content.trim().isEmpty()) {
                        notifyErrorAndFinish(requestId, callback, 500, "Failed to parse response");
                    } else {
                        if (useHistory) {
                            conversationHistory.add(new Message("assistant", content));
                            AiConfig.saveConversationHistory(conversationHistory);
                        }
                        notifyResponseAndFinish(requestId, callback, content.trim());
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e("AI Error: ", e);
            notifyErrorAndFinish(requestId, callback, 500, e.getMessage() != null ? e.getMessage() : "Unknown error");
        }
    }


    private Request createRequest(Service service, Message userMessage, boolean stream, boolean useHistory, ArrayList<Message> conversationHistory) {
        String url = service.getUrl();
        if (TextUtils.isEmpty(url)) return null;

        if (url.contains("generativelanguage.googleapis")) {
            url = "https://generativelanguage.googleapis.com/v1beta/openai/";
        }
        String endpoint = url.endsWith("/") ? url + "chat/completions" : url + "/chat/completions";

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
                    messages.put(createMessageObject(msg));
                }
                conversationHistory.add(userMessage);
            }
            messages.put(createMessageObject(userMessage));

            json.put("model", service.getModel());
            json.put("messages", messages);
            json.put("stream", stream);
            json.put("temperature", AiConfig.temperature / 10.0f);
            json.put("max_tokens", 4096);
            applyReasoningConfig(json, service, url);

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

    private void applyReasoningConfig(JSONObject json, Service service, String url) throws JSONException {
        if (service.isReasoningEnabled()) return;

        String model = service.getModel() != null ? service.getModel().toLowerCase(Locale.ROOT) : "";
        String urlLower = url != null ? url.toLowerCase(Locale.ROOT) : "";

        if (urlLower.contains("openrouter.ai")) {
            json.put("reasoning", new JSONObject().put("effort", "none"));
            return;
        }

        if (urlLower.contains("generativelanguage.googleapis") && isGeminiReasoningModel(model)) {
            json.put("reasoning_effort", getGeminiReasoningEffort(model));
        } else if (urlLower.contains("api.openai.com") && isOpenAiReasoningModel(model)) {
            json.put("reasoning_effort", getOpenAiReasoningEffort(model));
        }
    }

    private boolean isGeminiReasoningModel(String model) {
        String m = stripProviderPrefix(model);
        return m.startsWith("gemini-2.5") || m.startsWith("gemini-3") || m.contains("thinking");
    }

    private String getGeminiReasoningEffort(String model) {
        if (model.contains("gemini-2.5") && !model.contains("pro")) return "none";
        return "minimal";
    }

    private boolean isOpenAiReasoningModel(String model) {
        String m = stripProviderPrefix(model);
        if (m.contains("gpt-5-chat")) return false;
        return m.startsWith("gpt-5") || m.startsWith("o1") || m.startsWith("o3") || m.startsWith("o4");
    }

    private String getOpenAiReasoningEffort(String model) {
        String m = stripProviderPrefix(model);
        if (m.startsWith("gpt-5.1") || m.startsWith("gpt-5.2") || m.startsWith("gpt-5.3")
                || m.startsWith("gpt-5.4") || m.startsWith("gpt-5.5")) {
            return "none";
        }
        return "minimal";
    }

    private String stripProviderPrefix(String model) {
        int idx = model.indexOf('/');
        return idx >= 0 ? model.substring(idx + 1) : model;
    }

    private JSONObject createMessageObject(Message message) throws JSONException {
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


    private void handleStreamResponse(ResponseBody body, String requestId, Message userMessage, boolean useHistory,
                                      ArrayList<Message> conversationHistory, Service service, GenerationCallback callback) {
        StringBuilder fullResponse = new StringBuilder();
        ReasoningContentFilter reasoningFilter = new ReasoningContentFilter();
        boolean reasoningNotified = false;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body.byteStream()))) {
            int chunkSize = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                if (!activeRequests.containsKey(requestId)) break;
                if (TextUtils.isEmpty(line)) continue;
                if (!line.startsWith("data: ")) continue;

                String data = line.substring(6).trim();
                if (data.equals("[DONE]")) {
                    String remaining = reasoningFilter.flush();
                    if (!TextUtils.isEmpty(remaining)) {
                        fullResponse.append(remaining);
                    }
                    if (chunkSize > 0) {
                        sendStreamChunk(requestId, fullResponse.toString(), callback);
                    }
                    break;
                }

                StreamResponsePart part = parseStreamResponsePart(data);
                if (part.hasReasoning && !reasoningNotified) {
                    reasoningNotified = true;
                    notifyThinking(requestId, callback);
                }

                String content = part.content;
                if (!TextUtils.isEmpty(content)) {
                    String filtered = reasoningFilter.filter(content);
                    if (reasoningFilter.consumeReasoningSignal() && !reasoningNotified) {
                        reasoningNotified = true;
                        notifyThinking(requestId, callback);
                    }
                    if (!TextUtils.isEmpty(filtered)) {
                        fullResponse.append(filtered);
                        chunkSize += filtered.length();
                        if (chunkSize >= STREAM_SYMBOLS_LIMIT) {
                            sendStreamChunk(requestId, fullResponse.toString(), callback);
                            chunkSize = 0;
                        }
                    }
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
            notifyResponseAndFinish(requestId, callback, finalResponse);
        } else {
            finishRequest(requestId);
        }
    }

    private StreamResponsePart parseStreamResponsePart(String data) {
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

    private boolean hasReasoning(JSONObject delta) {
        return hasValue(delta, "reasoning") || hasValue(delta, "reasoning_content") || hasValue(delta, "reasoning_details");
    }

    private boolean hasValue(JSONObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.isNull(key)) return false;
        Object val = obj.opt(key);
        if (val instanceof String) return !TextUtils.isEmpty((String) val);
        if (val instanceof JSONArray) return ((JSONArray) val).length() > 0;
        return val != null && val != JSONObject.NULL;
    }


    private String parseResponseContent(String body) {
        try {
            JSONObject json = new JSONObject(body);
            JSONArray choices = json.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                JSONObject msgObj = choices.getJSONObject(0).optJSONObject("message");
                if (msgObj != null && msgObj.has("content") && !msgObj.isNull("content")) {
                    Object content = msgObj.opt("content");
                    if (content != null) {
                        return stripReasoningMarkup(content.toString());
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return null;
    }

    private String stripReasoningMarkup(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        String lower = text.toLowerCase(Locale.ROOT);
        int i = 0;
        while (i < text.length()) {
            int openIdx = lower.indexOf("<think>", i);
            if (openIdx < 0) {
                sb.append(text, i, text.length());
                break;
            }
            sb.append(text, i, openIdx);
            int closeIdx = lower.indexOf("</think>", openIdx + 7);
            if (closeIdx < 0) break;
            i = closeIdx + 8;
        }
        return trimLeading(sb.toString());
    }


    private ArrayList<Message> trimConversationHistory(ArrayList<Message> history) {
        ArrayList<Message> trimmed = new ArrayList<>();
        int charCount = 0;
        for (int i = history.size() - 1; i >= 0 && trimmed.size() < MAX_HISTORY_MESSAGES; i--) {
            Message msg = history.get(i);
            if (msg == null || TextUtils.isEmpty(msg.role()) || TextUtils.isEmpty(msg.content())) continue;
            charCount += msg.content().length();
            if (charCount > MAX_HISTORY_CHARS && !trimmed.isEmpty()) break;
            trimmed.add(0, new Message(msg.role(), msg.content()));
        }
        while (!trimmed.isEmpty() && "assistant".equals(trimmed.get(0).role())) {
            trimmed.remove(0);
        }
        return trimmed;
    }


    private static ImagePayload loadImagePayload(String path) {
        if (TextUtils.isEmpty(path)) return null;
        File file = new File(path);
        if (!file.exists() || !file.isFile() || file.length() == 0) return null;

        if (file.length() > MAX_IMAGE_SIZE) {
            return compressImage(path);
        }

        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.min(file.length(), MAX_IMAGE_SIZE))) {
            byte[] buf = new byte[4096];
            int read;
            while ((read = fis.read(buf)) != -1) {
                bos.write(buf, 0, read);
            }
            return new ImagePayload(bos.toByteArray(), getMimeType(path));
        } catch (IOException e) {
            FileLog.e("Error loading image: " + path, e);
            return null;
        }
    }

    private static ImagePayload compressImage(String path) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / opts.inSampleSize > 2048 || bounds.outHeight / opts.inSampleSize > 2048) {
                opts.inSampleSize *= 2;
            }

            Bitmap bitmap = BitmapFactory.decodeFile(path, opts);
            if (bitmap == null) return null;

            try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                int quality = 85;
                do {
                    bos.reset();
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, bos);
                    quality -= 10;
                } while (bos.size() > MAX_IMAGE_SIZE && quality >= 55);

                if (bos.size() <= MAX_IMAGE_SIZE) {
                    return new ImagePayload(bos.toByteArray(), "image/jpeg");
                }
                return null;
            } finally {
                bitmap.recycle();
            }
        } catch (Exception e) {
            FileLog.e("Error compressing image: " + path, e);
            return null;
        }
    }


    private String trimLeading(String str) {
        int i = 0;
        while (i < str.length() && Character.isWhitespace(str.charAt(i))) i++;
        return str.substring(i);
    }

    private void sendStreamChunk(String requestId, String chunk, GenerationCallback callback) {
        if (TextUtils.isEmpty(chunk)) return;
        AndroidUtilities.runOnUIThread(() -> {
            if (activeRequests.containsKey(requestId) && callback != null) {
                callback.onChunk(chunk);
            }
        });
    }

    private void notifyThinking(String requestId, GenerationCallback callback) {
        AndroidUtilities.runOnUIThread(() -> {
            if (activeRequests.containsKey(requestId) && callback != null) {
                callback.onThinking();
            }
        });
    }

    private void notifyResponseAndFinish(String requestId, GenerationCallback callback, String response) {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                if (activeRequests.containsKey(requestId) && callback != null) {
                    callback.onResponse(response);
                }
            } finally {
                finishRequest(requestId);
            }
        });
    }

    private void notifyErrorAndFinish(String requestId, GenerationCallback callback, int code, String message) {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                if (activeRequests.containsKey(requestId) && callback != null) {
                    callback.onError(code, message);
                }
            } finally {
                finishRequest(requestId);
            }
        });
    }

    public boolean isGenerating() {
        return isGenerating.get();
    }

    private void finishRequest(String requestId) {
        activeCalls.remove(requestId);
        ExecutorService executor = activeRequests.remove(requestId);
        if (executor != null) {
            executor.shutdown();
        }
        if (activeRequests.isEmpty()) {
            isGenerating.set(false);
        }
    }

    public void stopRequest(String requestId) {
        if (TextUtils.isEmpty(requestId)) return;
        Call call = activeCalls.remove(requestId);
        if (call != null) {
            call.cancel();
        }
        ExecutorService executor = activeRequests.remove(requestId);
        if (executor != null) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
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


    private static class ImagePayload {
        final byte[] data;
        final String mimeType;

        ImagePayload(byte[] data, String mimeType) {
            this.data = data;
            this.mimeType = mimeType;
        }
    }

    private static class StreamResponsePart {
        final String content;
        final boolean hasReasoning;

        StreamResponsePart(String content, boolean hasReasoning) {
            this.content = content;
            this.hasReasoning = hasReasoning;
        }
    }

    public static class ReasoningContentFilter {
        private boolean inReasoning;
        private String pending = "";
        private boolean reasoningSignal;

        public String filter(String input) {
            if (TextUtils.isEmpty(input)) return null;
            String text = pending + input;
            pending = "";
            StringBuilder sb = new StringBuilder(text.length());
            int i = 0;
            while (i < text.length()) {
                String lower = text.toLowerCase(Locale.ROOT);
                if (inReasoning) {
                    reasoningSignal = true;
                    int closeIdx = lower.indexOf("</think>", i);
                    if (closeIdx < 0) {
                        pending = getCloseTagPrefix(text, i);
                        return sb.toString();
                    }
                    i = closeIdx + 8;
                    inReasoning = false;
                } else {
                    int openIdx = lower.indexOf("<think>", i);
                    if (openIdx < 0) {
                        pending = getOpenTagPrefix(text, i);
                        reasoningSignal = !sb.toString().isEmpty();
                        int end = text.length() - pending.length();
                        if (end > i) sb.append(text, i, end);
                        return sb.toString();
                    }
                    sb.append(text, i, openIdx);
                    i = openIdx + 7;
                    inReasoning = true;
                    reasoningSignal = true;
                }
            }
            return sb.toString();
        }

        public boolean consumeReasoningSignal() {
            boolean signal = reasoningSignal;
            reasoningSignal = false;
            return signal;
        }

        public String flush() {
            String result = inReasoning ? "" : pending;
            pending = "";
            return result;
        }

        private String getOpenTagPrefix(String text, int from) {
            String lower = text.toLowerCase(Locale.ROOT);
            for (int len = Math.min(6, text.length() - from); len > 0; len--) {
                if ("<think>".startsWith(lower.substring(text.length() - len))) {
                    return text.substring(text.length() - len);
                }
            }
            return "";
        }

        private String getCloseTagPrefix(String text, int from) {
            String lower = text.toLowerCase(Locale.ROOT);
            for (int len = Math.min(7, text.length() - from); len > 0; len--) {
                if ("</think>".startsWith(lower.substring(text.length() - len))) {
                    return text.substring(text.length() - len);
                }
            }
            return "";
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
