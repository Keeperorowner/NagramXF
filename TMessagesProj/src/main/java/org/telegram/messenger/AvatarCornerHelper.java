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

    public static float getAvatarSquareness() {
        float avatarCorners = NaConfig.INSTANCE.getAvatarCorners().Float();
        float squareness = 1.0f - (avatarCorners / 28.0f);
        if (squareness < 0.0f) {
            squareness = 0.0f;
        } else if (squareness > 1.0f) {
            squareness = 1.0f;
        }
        return squareness;
    }

    public static float getOnlineDotOffset(float dotOffset, float radius) {
        return dotOffset + (((float) (radius / Math.sqrt(2.0)) - dotOffset) * getAvatarSquareness());
    }
}
