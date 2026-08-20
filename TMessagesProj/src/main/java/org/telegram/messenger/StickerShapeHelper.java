package org.telegram.messenger;

import xyz.nextalone.nagram.NaConfig;

// 贴纸形状：0 原始方形，1 统一圆角，2 跟随消息气泡圆角（尾巴一侧小圆角）
public final class StickerShapeHelper {

    public static final int SHAPE_DEFAULT = 0;
    public static final int SHAPE_ROUNDED = 1;
    public static final int SHAPE_ROUNDED_AS_MESSAGE = 2;

    public static final float ROUNDED_RADIUS_DP = 6.0f;
    // 表情面板预览的圆角
    public static final float PICKER_RADIUS_DP = 4.0f;

    private StickerShapeHelper() {
    }

    public static int getStickerShape() {
        return NaConfig.INSTANCE.getStickerShape().Int();
    }

    public static boolean isRoundedAsMessage() {
        return getStickerShape() == SHAPE_ROUNDED_AS_MESSAGE;
    }

    public static int getPickerRoundRadius() {
        return getStickerShape() == SHAPE_DEFAULT ? 0 : AndroidUtilities.dp(PICKER_RADIUS_DP);
    }
}
