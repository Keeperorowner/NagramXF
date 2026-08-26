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
        return getAvatarRoundRadiusInternal(sizeDp, false, NaConfig.INSTANCE.getAvatarCorners().Float(), false, false, false, NaConfig.INSTANCE.getSingleCornerRadius().Bool());
    }

    public static int getAvatarRoundRadius(float sizeDp, boolean forum) {
        return getAvatarRoundRadiusInternal(sizeDp, false, NaConfig.INSTANCE.getAvatarCorners().Float(), forum, false, false, NaConfig.INSTANCE.getSingleCornerRadius().Bool());
    }

    public static int getAvatarRoundRadius(float sizeDp, boolean forum, boolean storyCompensation) {
        return getAvatarRoundRadiusInternal(sizeDp, false, NaConfig.INSTANCE.getAvatarCorners().Float(), forum, storyCompensation, false, NaConfig.INSTANCE.getSingleCornerRadius().Bool());
    }

    public static int getAvatarRoundRadius(float sizeDp, boolean forum, boolean storyCompensation, boolean community) {
        return getAvatarRoundRadiusInternal(sizeDp, false, NaConfig.INSTANCE.getAvatarCorners().Float(), forum, storyCompensation, community, NaConfig.INSTANCE.getSingleCornerRadius().Bool());
    }

    public static int getAvatarRoundRadius(float sizeDp, float avatarCorners, boolean forum, boolean storyCompensation, boolean singleCornerRadius) {
        return getAvatarRoundRadiusInternal(sizeDp, false, avatarCorners, forum, storyCompensation, false, singleCornerRadius);
    }

    public static int getAvatarRoundRadiusPx(float sizePx) {
        return getAvatarRoundRadiusInternal(sizePx, true, NaConfig.INSTANCE.getAvatarCorners().Float(), false, false, false, NaConfig.INSTANCE.getSingleCornerRadius().Bool());
    }

    public static int getAvatarRoundRadiusPx(float sizePx, boolean forum) {
        return getAvatarRoundRadiusInternal(sizePx, true, NaConfig.INSTANCE.getAvatarCorners().Float(), forum, false, false, NaConfig.INSTANCE.getSingleCornerRadius().Bool());
    }

    public static int getAvatarRoundRadiusPx(float sizePx, boolean forum, boolean storyCompensation) {
        return getAvatarRoundRadiusInternal(sizePx, true, NaConfig.INSTANCE.getAvatarCorners().Float(), forum, storyCompensation, false, NaConfig.INSTANCE.getSingleCornerRadius().Bool());
    }

    private static int getAvatarRoundRadiusInternal(float size, boolean sizeIsPx, float avatarCorners, boolean forum, boolean storyCompensation, boolean community, boolean singleCornerRadius) {
        if (avatarCorners == 0.0f) {
            return 0;
        }
        float radius = (avatarCorners * size) / BASE_AVATAR_SIZE_DP;
        if (storyCompensation) {
            radius -= sizeIsPx ? AndroidUtilities.dpf2(STORY_RADIUS_COMPENSATION) : STORY_RADIUS_COMPENSATION;
        }
        if (!sizeIsPx) {
            radius = AndroidUtilities.dp(radius);
        }
        if (!singleCornerRadius) {
            if (community) {
                radius = (radius * 40.0f) / 72.0f;
            } else if (forum) {
                radius = (((int) radius) * FORUM_RADIUS_MULTIPLIER) >> FORUM_RADIUS_SHIFT;
            }
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

    public static int getOnlineDotOuterRadius() {
        return AndroidUtilities.dp((getAvatarSquareness() * 2.0f) + 7.0f);
    }

    public static int getOnlineDotInnerRadius() {
        return AndroidUtilities.dp(getAvatarSquareness() + 5.0f);
    }
}
