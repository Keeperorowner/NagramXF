package xyz.nextalone.nagram.nowplaying;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.upstream.DefaultDataSource;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.PlayPauseDrawable;
import org.telegram.ui.Components.ScaleStateListAnimator;

import java.util.List;

public class NowPlayingCard extends FrameLayout {

    private final Theme.ResourcesProvider resourcesProvider;
    private final AudioManager audioManager;
    private final AudioManager.OnAudioFocusChangeListener audioFocusListener;
    private AudioFocusRequest audioFocusRequest;
    private final FrameLayout cardLayout;
    private final AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable emoji;
    private final BackupImageView imageView;
    private final TextView nameView;
    private final TextView artistView;
    private final TextView albumView;
    private final ImageView playPauseButton;
    private final PlayPauseDrawable playPauseDrawable;

    private NowPlayingCardData nowPlayingCardData;
    private ExoPlayer player;
    private boolean isPlaying;
    private boolean resumeOnFocusGain;
    private long currentDocId = -1L;
    private String currentPreviewUrl;

    protected void onSavedMusicClick() {}

    public NowPlayingCard(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        this.audioFocusListener = this::onAudioFocusChange;
        setClickable(false);
        setWillNotDraw(false);

        FrameLayout frameLayout = new FrameLayout(context) {
            @Override
            protected void dispatchDraw(Canvas canvas) {
                if (nowPlayingCardData != null) {
                    Integer accent = nowPlayingCardData.getAccentColor();
                    if (accent == null) {
                        accent = getThemedColor(Theme.key_windowBackgroundWhiteBlackText);
                    }
                    emoji.setColor(accent);
                    float alpha = nowPlayingCardData.getCoverBitmap() == null ? 0.4f : 1.0f;
                    NowPlayingUIUtil.INSTANCE.drawNowPlayingPattern(canvas, emoji, getWidth(), getHeight(), alpha);
                }
                super.dispatchDraw(canvas);
            }
        };
        GradientDrawable gradientDrawable = new GradientDrawable() {
            @Override
            protected void onBoundsChange(Rect r) {
                super.onBoundsChange(r);
                setGradientRadius(r.width() * 2.0f);
            }
        };
        gradientDrawable.setCornerRadius(AndroidUtilities.dpf2(16.0f));
        frameLayout.setBackground(gradientDrawable);
        frameLayout.setClickable(true);
        ScaleStateListAnimator.apply(frameLayout, 0.035f, 1.5f);
        this.cardLayout = frameLayout;
        addView(frameLayout, LayoutHelper.createFrame(-1, -2.0f));

        this.emoji = new AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable(frameLayout, false, AndroidUtilities.dp(20.0f), 13);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        frameLayout.addView(row, LayoutHelper.createLinear(-1, -2, 119, 12, 12, 12, 12));

        BackupImageView cover = new BackupImageView(context);
        cover.setRoundRadius(AndroidUtilities.dp(8.0f));
        cover.setClipToOutline(true);
        cover.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), AndroidUtilities.dpf2(8.0f));
            }
        });
        this.imageView = cover;
        row.addView(cover, LayoutHelper.createLinear(68, 68, 51, 0, 0, 12, 0));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        row.addView(texts, LayoutHelper.createLinear(0, -2, 1.0f, 16));

        TextUtils.TruncateAt end = TextUtils.TruncateAt.END;

        TextView name = new TextView(context);
        name.setGravity(Gravity.START);
        name.setTextColor(-1);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.0f);
        name.setSingleLine(true);
        name.setEllipsize(end);
        name.setTypeface(AndroidUtilities.bold());
        org.telegram.messenger.NotificationCenter.listenEmojiLoading(name);
        this.nameView = name;
        texts.addView(name, LayoutHelper.createLinear(-1, -2));

        TextView artist = new TextView(context);
        artist.setGravity(Gravity.START);
        artist.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.0f);
        artist.setSingleLine(true);
        artist.setEllipsize(end);
        artist.setTextColor(-1);
        artist.setAlpha(0.6f);
        org.telegram.messenger.NotificationCenter.listenEmojiLoading(artist);
        this.artistView = artist;
        texts.addView(artist, LayoutHelper.createLinear(-1, -2, 0.0f, 2.0f, 0.0f, 0.0f));

        TextView album = new TextView(context);
        album.setGravity(Gravity.START);
        album.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.0f);
        album.setSingleLine(true);
        album.setEllipsize(end);
        album.setTextColor(-1);
        album.setAlpha(0.6f);
        org.telegram.messenger.NotificationCenter.listenEmojiLoading(album);
        this.albumView = album;
        texts.addView(album, LayoutHelper.createLinear(-1, -2, 0.0f, 2.0f, 0.0f, 0.0f));

        PlayPauseDrawable pp = new PlayPauseDrawable(16);
        pp.setPause(false);
        pp.setColor(-1);
        this.playPauseDrawable = pp;
        ImageView ppButton = new ImageView(context);
        ScaleStateListAnimator.apply(ppButton);
        ppButton.setScaleType(ImageView.ScaleType.CENTER);
        ppButton.setImageDrawable(pp);
        ppButton.setOnClickListener(v -> togglePlayPause());
        this.playPauseButton = ppButton;
        row.addView(ppButton, LayoutHelper.createLinear(32, 32, 16, 8, 0, 8, 0));
    }

    private void onAudioFocusChange(int focusChange) {
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
                || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            if (player == null || !player.isPlaying()) return;
            resumeOnFocusGain = true;
            if (player != null) player.pause();
        } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
            resumeOnFocusGain = false;
            if (player != null) player.pause();
        } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN && resumeOnFocusGain) {
            if (player != null) player.play();
            resumeOnFocusGain = false;
        }
    }

    public void set(NowPlayingCardData cardData) {
        this.nowPlayingCardData = cardData;
        NowPlayingDTO dto = cardData.getNowPlayingDTO();
        artistView.setText(null);
        nameView.setText(null);
        albumView.setText(null);

        List<String> artists = dto.getArtists();
        if (artists == null || artists.isEmpty()) {
            artistView.setText(LocaleController.getString(R.string.AudioUnknownArtist));
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < artists.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(artists.get(i));
            }
            artistView.setText(sb.toString());
        }
        nameView.setText(Emoji.replaceEmoji(dto.getTrackName(), nameView.getPaint().getFontMetricsInt(), false));

        String albumName = dto.getAlbumName();
        boolean showAlbum = albumName != null && albumName.length() != 0 && !TextUtils.equals(dto.getTrackName(), albumName);
        albumView.setVisibility(showAlbum ? View.VISIBLE : View.GONE);
        if (albumView.getVisibility() == View.VISIBLE) {
            albumView.setText(Emoji.replaceEmoji(albumName, albumView.getPaint().getFontMetricsInt(), false));
        }

        Drawable background = cardLayout.getBackground();
        if (background instanceof GradientDrawable) {
            GradientDrawable gd = (GradientDrawable) background;
            gd.mutate();
            gd.setDither(true);
            gd.setGradientType(GradientDrawable.RADIAL_GRADIENT);
            gd.setGradientCenter(1.0f, 0.5f);
            Integer bgColor = cardData.getBackgroundColor();
            int bg = bgColor != null ? bgColor : getThemedColor(Theme.key_windowBackgroundWhite);
            int bgEnd = cardData.getBackgroundColor() != null
                ? NowPlayingUIUtil.INSTANCE.adjustHsl(bg, 1.5f)
                : bg;
            gd.setColors(new int[]{bg, bgEnd});
        }

        String previewUrl = dto.getPreviewUrl();
        boolean showPlay = previewUrl != null && previewUrl.length() != 0 && !"TELEGRAM".equals(dto.getPlatform());
        playPauseButton.setVisibility(showPlay ? View.VISIBLE : View.GONE);
        Integer accent = cardData.getAccentColor();
        playPauseButton.setBackground(Theme.createCircleDrawable(AndroidUtilities.dp(32.0f),
            accent != null ? accent : getThemedColor(Theme.key_featuredStickers_addButton)));

        long docId = ServiceEmoji.fromString(dto.getPlatform());
        if (cardData.getUserEmoji() > 0 && "TELEGRAM".equals(dto.getPlatform())) {
            docId = cardData.getUserEmoji();
        }
        if (docId != currentDocId) {
            currentDocId = docId;
            emoji.set(docId, true);
        }

        final NowPlayingDTO finalDto = dto;
        cardLayout.setOnClickListener(v -> {
            if ("TELEGRAM".equals(finalDto.getPlatform())) {
                onSavedMusicClick();
            } else {
                Browser.openUrl(cardLayout.getContext(), finalDto.getSongUrl());
            }
        });
        cardLayout.setOnLongClickListener(v -> {
            if ("TELEGRAM".equals(finalDto.getPlatform())) {
                onSavedMusicClick();
            } else {
                Browser.openUrl(cardLayout.getContext(), finalDto.getSongUrl());
            }
            return true;
        });

        if (cardData.getImageLocation() != null) {
            BitmapDrawable bd = null;
            if (cardData.getCoverBitmap() != null) {
                bd = new BitmapDrawable(getContext().getResources(), cardData.getCoverBitmap());
            }
            imageView.setImage(cardData.getImageLocation(), null, bd, 0, null);
            if (cardData.getCoverBitmap() != null) {
                artistView.setTextColor(-1);
                nameView.setTextColor(-1);
                albumView.setTextColor(-1);
            } else {
                int c = getThemedColor(Theme.key_windowBackgroundWhiteBlackText);
                artistView.setTextColor(c);
                nameView.setTextColor(c);
                albumView.setTextColor(c);
            }
        } else {
            imageView.setImageResource(R.drawable.nocover_big, getThemedColor(Theme.key_player_button));
            int c = getThemedColor(Theme.key_windowBackgroundWhiteBlackText);
            artistView.setTextColor(c);
            nameView.setTextColor(c);
            albumView.setTextColor(c);
        }

        String newPreview = dto.getPreviewUrl();
        if (!TextUtils.equals(newPreview, currentPreviewUrl)) {
            currentPreviewUrl = newPreview;
            initializePlayer();
        }
        invalidate();
    }

    private void initializePlayer() {
        releasePlayer();
        if (nowPlayingCardData == null) return;
        String previewUrl = nowPlayingCardData.getNowPlayingDTO().getPreviewUrl();
        if (previewUrl == null) return;
        ExoPlayer exo = new ExoPlayer.Builder(getContext()).build();
        exo.setMediaSource(new ProgressiveMediaSource.Factory(new DefaultDataSource.Factory(getContext()))
            .createMediaSource(MediaItem.fromUri(Uri.parse(previewUrl))));
        exo.prepare();
        exo.addListener(new Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean playing) {
                isPlaying = playing;
                updatePlayPauseButton();
                if (!playing) abandonAudioFocus();
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) {
                    abandonAudioFocus();
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {}
        });
        this.player = exo;
    }

    private void togglePlayPause() {
        if (player == null) return;
        if (player.isPlaying()) {
            player.pause();
            abandonAudioFocus();
        } else if (requestAudioFocus()) {
            if (player.getPlaybackState() == Player.STATE_ENDED) {
                player.seekTo(0L);
            }
            player.play();
        }
    }

    private boolean requestAudioFocus() {
        if (Build.VERSION.SDK_INT < 26) {
            return audioManager.requestAudioFocus(audioFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
        AudioFocusRequest request = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener(audioFocusListener)
            .build();
        audioFocusRequest = request;
        return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            if (audioFocusRequest != null) {
                audioManager.abandonAudioFocusRequest(audioFocusRequest);
                audioFocusRequest = null;
            }
        } else {
            audioManager.abandonAudioFocus(audioFocusListener);
        }
    }

    private void updatePlayPauseButton() {
        playPauseDrawable.setPause(isPlaying);
    }

    private void releasePlayer() {
        if (player != null) {
            player.release();
        }
        player = null;
        isPlaying = false;
        updatePlayPauseButton();
        abandonAudioFocus();
    }

    private int getThemedColor(int key) {
        return Theme.getColor(key, resourcesProvider);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        emoji.attach();
        if (player == null && nowPlayingCardData != null) {
            initializePlayer();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        emoji.detach();
        releasePlayer();
    }
}
