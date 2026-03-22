package org.telegram.messenger;

import xyz.nextalone.nagram.NaConfig;

public final class AvatarCornerHelper {

    private static final float BASE_AVATAR_SIZE_DP = 56.0f;
    private static final float STORY_RADIUS_COMPENSATION = 2.5f;
    private static final int FORUM_RADIUS_MULTIPLIER = 42;
    private static final int FORUM_RADIUS_SHIFT = 6;

    private AvatarCornerHelper() {
    }

    public static int getAvatarRoundRadius(float sizeDp) {
        return getAvatarRoundRadiusInternal(sizeDp, false, NaConfig.INSTANCE.getAvatarCorners().Float(), false, false, NaConfig.INSTANCE.getSingleCornerRadius().Bool());
    }

    public static int getAvatarRoundRadius(float sizeDp, boolean forum) {
        return getAvatarRoundRadiusInternal(sizeDp, false, NaConfig.INSTANCE.getAvatarCorners().Float(), forum, false, NaConfig.INSTANCE.getSingleCornerRadius().Bool());
    }

    public static int getAvatarRoundRadius(float sizeDp, boolean forum, boolean storyCompensation) {
        return getAvatarRoundRadiusInternal(sizeDp, false, NaConfig.INSTANCE.getAvatarCorners().Float(), forum, storyCompensation, NaConfig.INSTANCE.getSingleCornerRadius().Bool());
    }

    public static int getAvatarRoundRadius(float sizeDp, float avatarCorners, boolean forum, boolean storyCompensation, boolean singleCornerRadius) {
        return getAvatarRoundRadiusInternal(sizeDp, false, avatarCorners, forum, storyCompensation, singleCornerRadius);
    }

    public static int getAvatarRoundRadiusPx(float sizePx) {
        return getAvatarRoundRadiusInternal(sizePx, true, NaConfig.INSTANCE.getAvatarCorners().Float(), false, false, NaConfig.INSTANCE.getSingleCornerRadius().Bool());
    }

    public static int getAvatarRoundRadiusPx(float sizePx, boolean forum) {
        return getAvatarRoundRadiusInternal(sizePx, true, NaConfig.INSTANCE.getAvatarCorners().Float(), forum, false, NaConfig.INSTANCE.getSingleCornerRadius().Bool());
    }

    public static int getAvatarRoundRadiusPx(float sizePx, boolean forum, boolean storyCompensation) {
        return getAvatarRoundRadiusInternal(sizePx, true, NaConfig.INSTANCE.getAvatarCorners().Float(), forum, storyCompensation, NaConfig.INSTANCE.getSingleCornerRadius().Bool());
    }

    private static int getAvatarRoundRadiusInternal(float size, boolean sizeIsPx, float avatarCorners, boolean forum, boolean storyCompensation, boolean singleCornerRadius) {
        if (avatarCorners == 0.0f) {
            return 0;
        }
        float radius = (avatarCorners * size) / BASE_AVATAR_SIZE_DP;
        if (storyCompensation) {
            radius -= STORY_RADIUS_COMPENSATION;
        }
        if (!sizeIsPx) {
            radius = AndroidUtilities.dp(radius);
        }
        if (forum && !singleCornerRadius) {
            radius = (((int) radius) * FORUM_RADIUS_MULTIPLIER) >> FORUM_RADIUS_SHIFT;
        }
        return (int) Math.ceil(radius);
    }
}
