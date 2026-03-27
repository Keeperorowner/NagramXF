package xyz.nextalone.nagram.nowplaying;

import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import xyz.nextalone.nagram.NaConfig;

public class LocalNowPlayingController {

    public static final int SERVICE_NONE = 0;
    public static final int SERVICE_LAST_FM = 1;

    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build();

    public interface Callback {
        void onTrackLoaded(Track track);
    }

    public static class Track {
        public final String title;
        public final String artist;
        public final String album;
        public final String url;
        public final String imageUrl;

        public Track(String title, String artist, String album, String url, String imageUrl) {
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.url = url;
            this.imageUrl = imageUrl;
        }
    }

    public static int getServiceType() {
        return NaConfig.INSTANCE.getNowPlayingServiceType().Int();
    }

    public static String getLastFmUsername() {
        return NaConfig.INSTANCE.getNowPlayingLastFmUsername().String().trim();
    }

    public static String getLastFmApiKey() {
        return NaConfig.INSTANCE.getNowPlayingLastFmApiKey().String().trim();
    }

    public static boolean isEnabled() {
        return getServiceType() == SERVICE_LAST_FM
            && !TextUtils.isEmpty(getLastFmUsername())
            && !TextUtils.isEmpty(getLastFmApiKey());
    }

    public static String getProfileUrl() {
        if (TextUtils.isEmpty(getLastFmUsername())) {
            return "https://www.last.fm/";
        }
        return "https://www.last.fm/user/" + Uri.encode(getLastFmUsername());
    }

    public static void getCurrentTrack(Callback callback) {
        if (callback == null) {
            return;
        }
        if (!isEnabled()) {
            AndroidUtilities.runOnUIThread(() -> callback.onTrackLoaded(null));
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            Track track = null;
            try {
                track = fetchLastFmTrack();
            } catch (Exception e) {
                FileLog.e(e);
            }
            Track finalTrack = track;
            AndroidUtilities.runOnUIThread(() -> callback.onTrackLoaded(finalTrack));
        });
    }

    private static Track fetchLastFmTrack() throws Exception {
        Uri uri = Uri.parse("https://ws.audioscrobbler.com/2.0/").buildUpon()
            .appendQueryParameter("method", "user.getrecenttracks")
            .appendQueryParameter("user", getLastFmUsername())
            .appendQueryParameter("api_key", getLastFmApiKey())
            .appendQueryParameter("format", "json")
            .appendQueryParameter("limit", "1")
            .build();

        Request request = new Request.Builder()
            .url(uri.toString())
            .get()
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return null;
            }

            JSONObject root = new JSONObject(response.body().string());
            JSONObject recentTracks = root.optJSONObject("recenttracks");
            if (recentTracks == null) {
                return null;
            }

            JSONObject trackObject = null;
            Object trackValue = recentTracks.opt("track");
            if (trackValue instanceof JSONArray) {
                JSONArray tracksArray = (JSONArray) trackValue;
                if (tracksArray.length() > 0) {
                    trackObject = tracksArray.optJSONObject(0);
                }
            } else if (trackValue instanceof JSONObject) {
                trackObject = (JSONObject) trackValue;
            }

            if (trackObject == null) {
                return null;
            }

            JSONObject attr = trackObject.optJSONObject("@attr");
            if (attr == null || !"true".equalsIgnoreCase(attr.optString("nowplaying"))) {
                return null;
            }

            String title = trackObject.optString("name");
            String url = trackObject.optString("url");

            JSONObject artistObject = trackObject.optJSONObject("artist");
            String artist = artistObject != null ? artistObject.optString("#text") : null;

            JSONObject albumObject = trackObject.optJSONObject("album");
            String album = albumObject != null ? albumObject.optString("#text") : null;

            String imageUrl = null;
            JSONArray images = trackObject.optJSONArray("image");
            if (images != null) {
                for (int i = images.length() - 1; i >= 0; i--) {
                    JSONObject imageObject = images.optJSONObject(i);
                    if (imageObject == null) {
                        continue;
                    }
                    String candidate = imageObject.optString("#text");
                    if (!TextUtils.isEmpty(candidate)) {
                        imageUrl = candidate;
                        break;
                    }
                }
            }

            if (TextUtils.isEmpty(title) && TextUtils.isEmpty(artist)) {
                return null;
            }

            return new Track(title, artist, album, url, imageUrl);
        }
    }
}
