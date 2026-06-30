package xyz.nextalone.nagram.nowplaying;

public final class ServiceEmoji {

    public static final long MUSIC_DOC_ID = 5271627010681108586L;
    public static final long SPOTIFY_DOC_ID = 5271857023359681001L;
    public static final long TELEGRAM_DOC_ID = 5325674462522144646L;
    public static final long LASTFM_DOC_ID = 5271627010681108586L;

    private ServiceEmoji() {}

    public static long fromString(String platform) {
        if (platform == null) {
            return MUSIC_DOC_ID;
        }
        switch (platform.toUpperCase()) {
            case "SPOTIFY":
                return SPOTIFY_DOC_ID;
            case "TELEGRAM":
                return TELEGRAM_DOC_ID;
            case "LAST_FM":
                return LASTFM_DOC_ID;
            default:
                return MUSIC_DOC_ID;
        }
    }
}
