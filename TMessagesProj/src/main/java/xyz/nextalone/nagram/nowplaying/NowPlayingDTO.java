package xyz.nextalone.nagram.nowplaying;

import java.util.List;

public final class NowPlayingDTO {

    public final String trackName;
    public final List<String> artists;
    public final String albumName;
    public final String coverUrl;
    public final String previewUrl;
    public final String songUrl;
    public final boolean isPlaying;
    public final String deviceName;
    public final String platform;
    public final Long duration;

    public NowPlayingDTO(String trackName, List<String> artists, String albumName, String coverUrl,
                         String previewUrl, String songUrl, boolean isPlaying,
                         String deviceName, String platform, Long duration) {
        this.trackName = trackName;
        this.artists = artists;
        this.albumName = albumName;
        this.coverUrl = coverUrl;
        this.previewUrl = previewUrl;
        this.songUrl = songUrl;
        this.isPlaying = isPlaying;
        this.deviceName = deviceName;
        this.platform = platform;
        this.duration = duration;
    }

    public String getTrackName() { return trackName; }
    public List<String> getArtists() { return artists; }
    public String getAlbumName() { return albumName; }
    public String getCoverUrl() { return coverUrl; }
    public String getPreviewUrl() { return previewUrl; }
    public String getSongUrl() { return songUrl; }
    public boolean isPlaying() { return isPlaying; }
    public String getDeviceName() { return deviceName; }
    public String getPlatform() { return platform; }
    public Long getDuration() { return duration; }
}
