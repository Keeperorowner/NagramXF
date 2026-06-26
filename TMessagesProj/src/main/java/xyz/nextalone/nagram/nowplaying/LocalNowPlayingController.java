package xyz.nextalone.nagram.nowplaying;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import xyz.nextalone.nagram.NaConfig;

public class LocalNowPlayingController {

    public static final int SERVICE_NONE = 0;
    public static final int SERVICE_LAST_FM = 1;

    public static final String PLATFORM_LAST_FM = "LAST_FM";
    private static final String WORKER_URL = "https://lastfm-nowplaying.chenhai0731.workers.dev";

    private static final String CACHE_PREFS = "nowplaying_cache";
    private static final String CACHE_KEY_DTO = "last_dto";

    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build();

    private static volatile NowPlayingDTO cachedDto;

    public interface Callback {
        void onTrackLoaded(NowPlayingDTO dto);
    }

    public static int getServiceType() {
        return NaConfig.INSTANCE.getNowPlayingServiceType().Int();
    }

    public static String getLastFmUsername() {
        return NaConfig.INSTANCE.getNowPlayingLastFmUsername().String().trim();
    }

    public static boolean isEnabled() {
        return getServiceType() == SERVICE_LAST_FM
            && !TextUtils.isEmpty(getLastFmUsername());
    }

    public static String getProfileUrl() {
        if (TextUtils.isEmpty(getLastFmUsername())) {
            return "https://www.last.fm/";
        }
        return "https://www.last.fm/user/" + Uri.encode(getLastFmUsername());
    }

    public static NowPlayingDTO getCachedTrack() {
        if (cachedDto == null) {
            cachedDto = loadCachedDto();
        }
        return cachedDto;
    }

    public static void getCurrentTrack(Callback callback) {
        if (callback == null) {
            return;
        }
        if (!isEnabled()) {
            AndroidUtilities.runOnUIThread(() -> callback.onTrackLoaded(null));
            return;
        }
        final NowPlayingDTO cached = getCachedTrack();
        Utilities.globalQueue.postRunnable(() -> {
            NowPlayingDTO dto = null;
            try {
                dto = fetchFromWorker();
            } catch (Exception e) {
                FileLog.e(e);
            }
            final NowPlayingDTO result = dto;
            if (result != null && result.isPlaying()) {
                cachedDto = result;
                persistCachedDto(result);
            }
            AndroidUtilities.runOnUIThread(() -> callback.onTrackLoaded(result != null ? result : cached));
        });
    }

    private static NowPlayingDTO fetchFromWorker() throws Exception {
        String base = WORKER_URL;
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        Uri uri = Uri.parse(base).buildUpon()
            .appendPath("now-playing")
            .appendQueryParameter("user", getLastFmUsername())
            .build();

        Request request = new Request.Builder()
            .url(uri.toString())
            .get()
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return null;
            }
            return parseDtoFromJson(response.body().string());
        }
    }

    private static NowPlayingDTO parseDtoFromJson(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        boolean isPlaying = o.optBoolean("isPlaying", false);
        if (!isPlaying) {
            return null;
        }
        String trackName = o.optString("trackName", null);
        if (TextUtils.isEmpty(trackName)) {
            return null;
        }
        JSONArray arr = o.optJSONArray("artists");
        List<String> artists = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String a = arr.optString(i, null);
                if (!TextUtils.isEmpty(a)) {
                    artists.add(a);
                }
            }
        }
        String albumName = optStringOrNull(o, "albumName");
        String coverUrl = optStringOrNull(o, "coverUrl");
        String previewUrl = optStringOrNull(o, "previewUrl");
        String songUrl = optStringOrNull(o, "songUrl");
        String deviceName = optStringOrNull(o, "deviceName");
        String platform = optStringOrNull(o, "platform");
        if (platform == null) {
            platform = PLATFORM_LAST_FM;
        }
        Long duration = null;
        if (o.has("duration") && !o.isNull("duration")) {
            duration = o.optLong("duration");
        }
        return new NowPlayingDTO(trackName, artists, albumName, coverUrl, previewUrl, songUrl, true, deviceName, platform, duration);
    }

    private static String optStringOrNull(JSONObject o, String key) {
        if (!o.has(key) || o.isNull(key)) {
            return null;
        }
        String s = o.optString(key, null);
        return TextUtils.isEmpty(s) ? null : s;
    }

    private static NowPlayingDTO loadCachedDto() {
        try {
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx == null) return null;
            SharedPreferences prefs = ctx.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE);
            String json = prefs.getString(CACHE_KEY_DTO, null);
            if (TextUtils.isEmpty(json)) return null;
            JSONObject o = new JSONObject(json);
            String trackName = o.optString("trackName", null);
            JSONArray arr = o.optJSONArray("artists");
            List<String> artists = new ArrayList<>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String a = arr.optString(i, null);
                    if (!TextUtils.isEmpty(a)) artists.add(a);
                }
            }
            String albumName = optStringOrNull(o, "albumName");
            String coverUrl = optStringOrNull(o, "coverUrl");
            String previewUrl = optStringOrNull(o, "previewUrl");
            String songUrl = optStringOrNull(o, "songUrl");
            boolean isPlaying = o.optBoolean("isPlaying", false);
            String deviceName = optStringOrNull(o, "deviceName");
            String platform = optStringOrNull(o, "platform");
            Long duration = null;
            if (o.has("duration") && !o.isNull("duration")) {
                duration = o.optLong("duration");
            }
            if (TextUtils.isEmpty(trackName)) return null;
            return new NowPlayingDTO(trackName, artists, albumName, coverUrl, previewUrl, songUrl, isPlaying, deviceName, platform, duration);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private static void persistCachedDto(NowPlayingDTO dto) {
        try {
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx == null) return;
            JSONObject o = new JSONObject();
            o.put("trackName", dto.trackName);
            if (dto.artists != null) {
                JSONArray arr = new JSONArray();
                for (String a : dto.artists) arr.put(a);
                o.put("artists", arr);
            }
            o.put("albumName", dto.albumName == null ? "" : dto.albumName);
            o.put("coverUrl", dto.coverUrl == null ? "" : dto.coverUrl);
            o.put("previewUrl", dto.previewUrl == null ? "" : dto.previewUrl);
            o.put("songUrl", dto.songUrl == null ? "" : dto.songUrl);
            o.put("isPlaying", dto.isPlaying);
            o.put("deviceName", dto.deviceName == null ? "" : dto.deviceName);
            o.put("platform", dto.platform == null ? "" : dto.platform);
            if (dto.duration != null) {
                o.put("duration", dto.duration);
            }
            SharedPreferences prefs = ctx.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE);
            prefs.edit().putString(CACHE_KEY_DTO, o.toString()).apply();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}
