package tw.nekomimi.nekogram.helpers;

import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;

/** Wire format for OpenAI-compatible speech recognition endpoints. */
final class OpenAiTranscriptionApi {
    private OpenAiTranscriptionApi() {}

    static String endpoint(String base, boolean transcriptions) {
        HttpUrl url = HttpUrl.get(base.trim());
        String path = url.encodedPath().replaceAll("/+$", "")
                .replaceAll("/(chat/completions|audio/transcriptions)$", "");
        return url.newBuilder().encodedPath(path + (transcriptions
                ? "/audio/transcriptions" : "/chat/completions")).build().toString();
    }

    static RequestBody body(File audio, boolean video, String model) {
        // Telegram voice messages are Ogg/Opus; extracted round-video audio is MPEG-4/AAC.
        // Do not label the bytes as WAV or send Chat Completions-only fields to ASR models.
        return new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", model)
                .addFormDataPart("file", video ? "audio.m4a" : "audio.ogg",
                        RequestBody.create(audio, MediaType.get(video ? "audio/mp4" : "audio/ogg")))
                .build();
    }

    static String text(String body) throws IOException {
        try {
            var json = JsonParser.parseString(body).getAsJsonObject();
            var text = json.get("text");
            if (text != null && text.isJsonPrimitive() && text.getAsJsonPrimitive().isString()
                    && !text.getAsString().trim().isEmpty()) {
                return text.getAsString().trim();
            }
        } catch (RuntimeException e) {
            throw new IOException("Invalid Audio Transcriptions API response", e);
        }
        throw new IOException("Audio Transcriptions API response contains no transcription text");
    }
}
