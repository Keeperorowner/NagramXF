package tw.nekomimi.nekogram.settings;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;

import com.radolyn.ayugram.AyuSaveExclusions;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;

/** The chat kinds offered by the save-exclusion picker. */
final class AyuSaveExclusionCategory {
    static final AyuSaveExclusionCategory[] ALL = {
            new AyuSaveExclusionCategory(AyuSaveExclusions.FLAG_USER, R.string.AyuSaveExclusionsUsers, AvatarDrawable.AVATAR_TYPE_FILTER_CONTACTS, -1, 0, 1f),
            new AyuSaveExclusionCategory(AyuSaveExclusions.FLAG_SECRET, R.string.AyuSaveExclusionsSecret, 0, 3, R.drawable.msg_filled_lockedrecord, .85f),
            new AyuSaveExclusionCategory(AyuSaveExclusions.FLAG_BOT_USER, R.string.AyuSaveExclusionsBotUsers, AvatarDrawable.AVATAR_TYPE_FILTER_BOTS, -1, 0, 1f),
            new AyuSaveExclusionCategory(AyuSaveExclusions.FLAG_PRIVATE_GROUP, R.string.AyuSaveExclusionsPrivateGroups, AvatarDrawable.AVATAR_TYPE_FILTER_GROUPS, 4, 0, 1f),
            new AyuSaveExclusionCategory(AyuSaveExclusions.FLAG_PUBLIC_GROUP, R.string.AyuSaveExclusionsPublicGroups, AvatarDrawable.AVATAR_TYPE_FILTER_GROUPS, -1, 0, 1f),
            new AyuSaveExclusionCategory(AyuSaveExclusions.FLAG_PRIVATE_CHANNEL, R.string.AyuSaveExclusionsPrivateChannels, AvatarDrawable.AVATAR_TYPE_FILTER_CHANNELS, 6, 0, 1f),
            new AyuSaveExclusionCategory(AyuSaveExclusions.FLAG_PUBLIC_CHANNEL, R.string.AyuSaveExclusionsPublicChannels, AvatarDrawable.AVATAR_TYPE_FILTER_CHANNELS, -1, 0, 1f),
            new AyuSaveExclusionCategory(AyuSaveExclusions.FLAG_BOT_CHAT, R.string.AyuSaveExclusionsBotsInGroups, 0, 2, R.drawable.filled_premium_bots, 1f)
    };

    final int flag;
    final int title;
    final int avatarType;
    final int colorIndex;
    final int icon;
    final float iconScale;

    private AyuSaveExclusionCategory(int flag, int title, int avatarType, int colorIndex, int icon, float iconScale) {
        this.flag = flag;
        this.title = title;
        this.avatarType = avatarType;
        this.colorIndex = colorIndex;
        this.icon = icon;
        this.iconScale = iconScale;
    }

    String getTitle() {
        return LocaleController.getString(title);
    }

    AvatarDrawable createAvatar(Context context, Theme.ResourcesProvider resourcesProvider) {
        AvatarDrawable avatar = new AvatarDrawable();
        configureAvatar(context, avatar, resourcesProvider);
        return avatar;
    }

    void configureAvatar(Context context, AvatarDrawable avatar, Theme.ResourcesProvider resourcesProvider) {
        avatar.setAvatarType(avatarType);
        if (colorIndex >= 0) {
            avatar.setColor(Theme.getColor(Theme.keys_avatar_background[colorIndex], resourcesProvider),
                    Theme.getColor(Theme.keys_avatar_background2[colorIndex], resourcesProvider));
        }
        Drawable customIcon = null;
        if (icon != 0) {
            Drawable drawable = context.getResources().getDrawable(icon).mutate();
            drawable.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_avatar_text, resourcesProvider), PorterDuff.Mode.SRC_IN));
            customIcon = iconScale == 1f ? drawable : new Drawable() {
                @Override
                public void draw(android.graphics.Canvas canvas) {
                    drawable.setBounds(getBounds());
                    int save = canvas.save();
                    canvas.scale(iconScale, iconScale, getBounds().exactCenterX(), getBounds().exactCenterY());
                    drawable.draw(canvas);
                    canvas.restoreToCount(save);
                }

                @Override
                public void setAlpha(int alpha) {
                    drawable.setAlpha(alpha);
                }

                @Override
                public void setColorFilter(android.graphics.ColorFilter colorFilter) {
                    drawable.setColorFilter(colorFilter);
                }

                @Override
                public int getOpacity() {
                    return android.graphics.PixelFormat.TRANSLUCENT;
                }

                @Override
                public int getIntrinsicWidth() {
                    return drawable.getIntrinsicWidth();
                }

                @Override
                public int getIntrinsicHeight() {
                    return drawable.getIntrinsicHeight();
                }
            };
        }
        avatar.setCustomIcon(customIcon);
    }
}
