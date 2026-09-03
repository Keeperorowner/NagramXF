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

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import xyz.nextalone.nagram.NaConfig;

public class LocalNowPlayingController {

    public static final int SERVICE_NONE = 0;
    public static final int SERVICE_LAST_FM = 1;
    public static final int SERVICE_STATS_FM = 2;

    public static final String PLATFORM_LAST_FM = "LAST_FM";
    public static final String PLATFORM_STATS_FM = "STATS_FM";
    private static final String WORKER_URL = "https://nowplaying.nagramxf.com";

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

    public interface WhitelistStatusCallback {
        void onStatusChecked(boolean whitelisted);
    }

    public interface BindCallback {
        void onBindResult(boolean success, String message);
    }

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    public static int getServiceType() {
        return NaConfig.INSTANCE.getNowPlayingServiceType().Int();
    }

    public static String getLastFmUsername() {
        return NaConfig.INSTANCE.getNowPlayingLastFmUsername().String().trim();
    }

    public static String getStatsFmUsername() {
        return NaConfig.INSTANCE.getNowPlayingStatsFmUsername().String().trim();
    }

    public static String getUsername() {
        if (getServiceType() == SERVICE_STATS_FM) {
            return getStatsFmUsername();
        }
        return getLastFmUsername();
    }

    public static boolean isEnabled() {
        if (getServiceType() == SERVICE_NONE) {
            return false;
        }
        return !TextUtils.isEmpty(getUsername());
    }

    public static String getProfileUrl() {
        String username = getUsername();
        if (TextUtils.isEmpty(username)) {
            return getServiceType() == SERVICE_STATS_FM ? "https://stats.fm/" : "https://www.last.fm/";
        }
        if (getServiceType() == SERVICE_STATS_FM) {
            return "https://stats.fm/" + Uri.encode(username);
        }
        return "https://www.last.fm/user/" + Uri.encode(username);
    }

    public static void checkWhitelistStatus(long tgUid, WhitelistStatusCallback callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                Request request = new Request.Builder()
                    .url(WORKER_URL + "/whitelist-status?tgUid=" + tgUid)
                    .get()
                    .build();
                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        JSONObject r = new JSONObject(response.body().string());
                        boolean whitelisted = r.optBoolean("whitelisted", false);
                        AndroidUtilities.runOnUIThread(() -> callback.onStatusChecked(whitelisted));
                    } else {
                        AndroidUtilities.runOnUIThread(() -> callback.onStatusChecked(false));
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> callback.onStatusChecked(false));
            }
        });
    }

    public static void bind(long tgUid, BindCallback callback) {
        if (!isEnabled()) {
            callback.onBindResult(false, "Feature not enabled");
            return;
        }
        String service = getServiceType() == SERVICE_STATS_FM ? "statsfm" : "lastfm";
        String username = getUsername();
        JSONObject body = new JSONObject();
        try {
            body.put("tgUid", String.valueOf(tgUid));
            body.put("service", service);
            body.put("username", username);
        } catch (Exception e) {
            callback.onBindResult(false, e.getMessage());
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            try {
                Request request = new Request.Builder()
                    .url(WORKER_URL + "/bind")
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();
                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        AndroidUtilities.runOnUIThread(() -> callback.onBindResult(true, "OK"));
                    } else {
                        String msg = "HTTP " + response.code();
                        if (response.body() != null) {
                            try {
                                JSONObject r = new JSONObject(response.body().string());
                                msg = r.optString("error", msg);
                            } catch (Exception ignore) {}
                        }
                        final String errMsg = msg;
                        AndroidUtilities.runOnUIThread(() -> callback.onBindResult(false, errMsg));
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> callback.onBindResult(false, e.getMessage()));
            }
        });
    }

    public static void getNowPlayingByUid(long tgUid, Callback callback) {
        if (callback == null) return;
        Utilities.globalQueue.postRunnable(() -> {
            NowPlayingDTO dto = null;
            try {
                dto = fetchNowPlayingByUid(tgUid);
            } catch (Exception e) {
                FileLog.e(e);
            }
            final NowPlayingDTO result = dto;
            AndroidUtilities.runOnUIThread(() -> callback.onTrackLoaded(result));
        });
    }

    private static NowPlayingDTO fetchNowPlayingByUid(long tgUid) throws Exception {
        Request request = new Request.Builder()
            .url(WORKER_URL + "/now-playing?uid=" + tgUid)
            .get()
            .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            return parseDtoFromJson(response.body().string());
        }
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
        String service = getServiceType() == SERVICE_STATS_FM ? "statsfm" : "lastfm";
        Uri uri = Uri.parse(base).buildUpon()
            .appendPath("now-playing")
            .appendQueryParameter("user", getUsername())
            .appendQueryParameter("service", service)
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
