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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SharedConfig;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Call;
import tw.nekomimi.nekogram.llm.net.OpenAICompatClient;
import tw.nekomimi.nekogram.llm.utils.LlmModelUtil;
import tw.nekomimi.nekogram.llm.utils.LlmUrlNormalizer;
import tw.nekomimi.nekogram.llm.utils.ReasoningContentFilter;
import tw.nekomimi.nekogram.utils.HttpClient;

public class Client {

    private static final int STREAM_SYMBOLS_LIMIT = SharedConfig.getDevicePerformanceClass() >= 1 ? 10 : 20;
    private static final int MAX_IMAGE_SIZE = 4 * 1024 * 1024;
    private static final int MAX_HISTORY_MESSAGES = 32;
    private static final int MAX_HISTORY_CHARS = 24000;

    private final Service serviceOverride;
    private final Role roleOverride;
    private final AtomicBoolean isGenerating = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, ExecutorService> activeRequests = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Call> activeCalls = new ConcurrentHashMap<>();

    private Client(Builder builder) {
        this.serviceOverride = builder.serviceOverride;
        this.roleOverride = builder.roleOverride;
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
            String baseUrl = resolveBaseUrl(service);
            ArrayList<Message> conversationHistory = new ArrayList<>();
            if (useHistory) {
                conversationHistory.addAll(trimConversationHistory(AiConfig.getConversationHistory()));
            }

            Message userMessage = new Message("user", prompt, imageData, mimeType);
            String requestJson = createRequestJson(service, baseUrl, userMessage, stream, useHistory, conversationHistory);
            if (baseUrl == null || requestJson == null) {
                notifyErrorAndFinish(requestId, callback, 500, "Failed to create request body");
                return;
            }

            Call call = OpenAICompatClient.newChatCompletionsCall(HttpClient.INSTANCE.getLlmStreamInstance(), baseUrl, service.getKey(), requestJson);
            if (call == null) {
                notifyErrorAndFinish(requestId, callback, 500, "Failed to create request");
                return;
            }
            activeCalls.put(requestId, call);

            if (stream) {
                streamResponse(call, requestId, useHistory, conversationHistory, callback);
            } else {
                OpenAICompatClient.LlmResponse<String> response = OpenAICompatClient.executeChatCompletions(call);
                if (!activeRequests.containsKey(requestId)) return;
                if (response.isSuccess()) {
                    String content = ReasoningContentFilter.stripReasoningMarkup(response.data());
                    if (content == null || content.trim().isEmpty()) {
                        notifyErrorAndFinish(requestId, callback, 500, "Failed to parse response");
                    } else {
                        if (useHistory) {
                            conversationHistory.add(new Message("assistant", content));
                            AiConfig.saveConversationHistory(conversationHistory);
                        }
                        notifyResponseAndFinish(requestId, callback, content.trim());
                    }
                } else {
                    FileLog.e("AI Error: " + response.error());
                    notifyErrorAndFinish(requestId, callback, response.httpCode() != 0 ? response.httpCode() : 500, response.error());
                }
            }
        } catch (Exception e) {
            FileLog.e("AI Error: ", e);
            notifyErrorAndFinish(requestId, callback, 500, e.getMessage() != null ? e.getMessage() : "Unknown error");
        }
    }

    private void streamResponse(Call call, String requestId, boolean useHistory,
                                ArrayList<Message> conversationHistory, GenerationCallback callback) {
        OpenAICompatClient.streamChatCompletions(call, STREAM_SYMBOLS_LIMIT, new OpenAICompatClient.StreamCallback() {
            @Override
            public void onChunk(String chunk) {
                sendStreamChunk(requestId, chunk, callback);
            }

            @Override
            public void onThinking() {
                notifyThinking(requestId, callback);
            }

            @Override
            public void onComplete(String fullContent) {
                if (TextUtils.isEmpty(fullContent)) {
                    finishRequest(requestId);
                    return;
                }
                if (useHistory) {
                    conversationHistory.add(new Message("assistant", fullContent));
                    AiConfig.saveConversationHistory(conversationHistory);
                }
                notifyResponseAndFinish(requestId, callback, fullContent);
            }

            @Override
            public void onError(int code, String error) {
                FileLog.e("AI Stream Error: " + error);
                notifyErrorAndFinish(requestId, callback, code != 0 ? code : 500, error);
            }
        });
    }


    private static String resolveBaseUrl(Service service) {
        String url = service.getUrl();
        if (TextUtils.isEmpty(url)) return null;
        if (url.contains("generativelanguage.googleapis")) {
            url = "https://generativelanguage.googleapis.com/v1beta/openai";
        }
        String normalized = LlmUrlNormalizer.normalizeBaseUrl(url);
        return TextUtils.isEmpty(normalized) ? null : normalized;
    }

    private String createRequestJson(Service service, String baseUrl, Message userMessage, boolean stream, boolean useHistory, ArrayList<Message> conversationHistory) {
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

            String model = service.getModel();
            json.put("model", model);
            json.put("messages", messages);
            json.put("stream", stream);
            if (LlmModelUtil.supportsTemperature(model)) {
                json.put("temperature", AiConfig.temperature / 10.0f);
            }
            json.put("max_tokens", 4096);
            if (!service.isReasoningEnabled()) {
                LlmModelUtil.applyReasoningParameters(json, baseUrl, model);
            }
            return json.toString();
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
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
