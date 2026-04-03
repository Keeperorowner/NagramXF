package tw.nekomimi.nekogram.helpers;

import static org.telegram.messenger.LocaleController.getString;

import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;

import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.TranslateController;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.ColoredImageSpan;

import java.util.Locale;
import java.util.Objects;

import tw.nekomimi.nekogram.ui.icons.IconsResources;
import xyz.nextalone.nagram.NaConfig;

public class TimeStringHelper {
    public static final int[] DELETED_COLORS = {
            0xFFFF0000,
            0xFFDC2626,
            0xFFDB2777,
            0xFFC026D3,
            0xFF9333EA,
            0xFF4F46E5,
            0xFF2563EB
    };
    public static SpannableStringBuilder deletedSpan;
    public static Drawable deletedDrawable;
    public static SpannableStringBuilder editedSpan;
    public static Drawable editedDrawable;
    public static SpannableStringBuilder channelLabelSpan;
    public static Drawable channelLabelDrawable;
    public static SpannableStringBuilder translatedSpan;
    public static Drawable translatedDrawable;
    public static Drawable bookmarkDrawable;
    public static SpannableStringBuilder arrowSpan;
    public static Drawable arrowDrawable;
    public static SpannableStringBuilder forwardsSpan;
    public static Drawable forwardsDrawable;
    public ChatActivity.ThemeDelegate themeDelegate;
    private static int cachedDeletedStyle = Integer.MIN_VALUE;
    private static int cachedDeletedColor = Integer.MIN_VALUE;

    public static int getDeletedIconStyle() {
        int style = NaConfig.INSTANCE.getDeletedIconStyle().Int();
        if (style >= 0) {
            return style;
        }
        return NaConfig.INSTANCE.getUseDeletedIcon().Bool() ? 1 : 0;
    }

    public static boolean isDeletedIconEnabled() {
        return getDeletedIconStyle() != 0;
    }

    public static String getDeletedStringLabel() {
        String deletedStr = NaConfig.INSTANCE.getCustomDeletedMark().String();
        return deletedStr.isEmpty() ? getString(R.string.DeletedMessage) : deletedStr;
    }

    public static void invalidateDeletedStyle() {
        deletedDrawable = null;
        deletedSpan = null;
        cachedDeletedStyle = Integer.MIN_VALUE;
        cachedDeletedColor = Integer.MIN_VALUE;
    }

    public static Drawable getDeletedPreviewDrawable() {
        Drawable drawable = createDeletedDrawable(true);
        if (drawable != null) {
            drawable.mutate();
        }
        return drawable;
    }

    private static int getDeletedColorValue(int colorId) {
        if (colorId <= 0 || colorId > DELETED_COLORS.length) {
            return 0;
        }
        return DELETED_COLORS[colorId - 1];
    }

    private static int getDeletedDrawableTint(boolean preview, int colorId) {
        int tintColor = getDeletedColorValue(colorId);
        if (tintColor != 0) {
            return tintColor;
        }
        return preview ? Theme.getColor(Theme.key_chat_inTimeText) : 0;
    }

    private static int getDeletedDrawableResId(boolean preview) {
        return switch (getDeletedIconStyle()) {
            case 1 -> preview ? R.drawable.ayu_trash_bin_preview : R.drawable.ayu_trash_bin;
            case 2 -> preview ? R.drawable.ayu_cross_preview : R.drawable.ayu_cross;
            case 3 -> preview ? R.drawable.ayu_eye_crossed_preview : R.drawable.ayu_eye_crossed;
            default -> 0;
        };
    }

    private static Drawable createDeletedDrawable(boolean preview) {
        int resId = getDeletedDrawableResId(preview);
        if (resId == 0) {
            return null;
        }
        Drawable drawable = Objects.requireNonNull(ContextCompat.getDrawable(ApplicationLoader.applicationContext, resId)).mutate();
        int tintColor = getDeletedDrawableTint(preview, NaConfig.INSTANCE.getDeletedIconColor().Int());
        if (tintColor != 0) {
            drawable.setTint(tintColor);
        }
        return drawable;
    }

    private static void ensureDeletedSpan() {
        int style = getDeletedIconStyle();
        int colorId = NaConfig.INSTANCE.getDeletedIconColor().Int();
        if (deletedSpan != null && deletedDrawable != null && cachedDeletedStyle == style && cachedDeletedColor == colorId) {
            return;
        }
        cachedDeletedStyle = style;
        cachedDeletedColor = colorId;
        deletedDrawable = createDeletedDrawable(false);
        deletedSpan = null;
        if (deletedDrawable != null) {
            deletedSpan = new SpannableStringBuilder("\u200B");
            ColoredImageSpan span = new ColoredImageSpan(deletedDrawable, true);
            if (colorId > 0) {
                span.recolorDrawable = false;
            }
            if (style == 3) {
                span.setTranslateX(AndroidUtilities.dp(-1));
            }
            deletedSpan.setSpan(span, 0, 1, 0);
        }
    }

    public static CharSequence createBookmarkedString(MessageObject messageObject, int senderNameColor) {
        createSpan();
        SpannableStringBuilder spannableStringBuilder = new SpannableStringBuilder();
        spannableStringBuilder
                .append(messageObject.messageOwner.post_author != null ? " " : "")
                .append(createBookmarkSpan(senderNameColor))
                .append("  ")
                .append(LocaleController.getInstance().getFormatterDay().format((long) (messageObject.messageOwner.date) * 1000));
        return spannableStringBuilder;
    }

    public static CharSequence createDeletedString(MessageObject messageObject, boolean isEdited, boolean isTranslated, int senderNameColor) {
        return createDeletedString(messageObject, isEdited, isTranslated, false, senderNameColor);
    }

    public static CharSequence createDeletedString(MessageObject messageObject, boolean isEdited, boolean isTranslated, boolean isBookmarked, int senderNameColor) {
        String editedStr = NaConfig.INSTANCE.getCustomEditedMessage().String();
        String editedStrFin = editedStr.isEmpty() ? getString(R.string.EditedMessage) : editedStr;
        String deletedStrFin = getDeletedStringLabel();

        createSpan();
        SpannableStringBuilder spannableStringBuilder = new SpannableStringBuilder();

        spannableStringBuilder
                .append(messageObject.messageOwner.post_author != null ? " " : "")
                .append(isDeletedIconEnabled() && deletedSpan != null ? deletedSpan : deletedStrFin)
                .append("  ")
                .append(isEdited ? (NaConfig.INSTANCE.getUseEditedIcon().Bool() ? editedSpan : editedStrFin) : "")
                .append(isEdited ? "  " : "")
                .append(isTranslated ? createTranslatedString(messageObject, true, isBookmarked, senderNameColor) : "")
                .append(isTranslated ? "  " : "")
                .append(!isTranslated && isBookmarked ? createBookmarkSpan(senderNameColor) : "")
                .append(!isTranslated && isBookmarked ? "  " : "")
                .append(LocaleController.getInstance().getFormatterDay().format((long) (messageObject.messageOwner.date) * 1000));
        return spannableStringBuilder;
    }

    public static CharSequence createEditedString(MessageObject messageObject, boolean isTranslated, int senderNameColor) {
        return createEditedString(messageObject, isTranslated, false, senderNameColor);
    }

    public static CharSequence createEditedString(MessageObject messageObject, boolean isTranslated, boolean isBookmarked, int senderNameColor) {
        String editedStr = NaConfig.INSTANCE.getCustomEditedMessage().String();
        String editedStrFin = editedStr.isEmpty() ? getString(R.string.EditedMessage) : editedStr;

        createSpan();
        SpannableStringBuilder spannableStringBuilder = new SpannableStringBuilder();

        spannableStringBuilder
                .append(messageObject.messageOwner.post_author != null ? " " : "")
                .append(NaConfig.INSTANCE.getUseEditedIcon().Bool() ? editedSpan : editedStrFin)
                .append("  ")
                .append(isTranslated ? createTranslatedString(messageObject, true, isBookmarked, senderNameColor) : "")
                .append(isTranslated ? "  " : "")
                .append(!isTranslated && isBookmarked ? createBookmarkSpan(senderNameColor) : "")
                .append(!isTranslated && isBookmarked ? "  " : "")
                .append(LocaleController.getInstance().getFormatterDay().format((long) (messageObject.messageOwner.date) * 1000));
        return spannableStringBuilder;
    }

    public static CharSequence createTranslatedString(MessageObject messageObject, boolean internal, int senderNameColor) {
        return createTranslatedString(messageObject, internal, false, senderNameColor);
    }

    public static CharSequence createTranslatedString(MessageObject messageObject, boolean internal, boolean isBookmarked, int senderNameColor) {
        createSpan();
        SpannableStringBuilder spannableStringBuilder = new SpannableStringBuilder();

        if (canShowLanguage(messageObject)) {
            spannableStringBuilder
                    .append(Locale.forLanguageTag(messageObject.messageOwner.originalLanguage).getDisplayName())
                    .append(" ")
                    .append(arrowSpan)
                    .append(" ")
                    .append(Locale.forLanguageTag(messageObject.messageOwner.translatedToLanguage).getDisplayName());
            if (isBookmarked) {
                spannableStringBuilder
                        .append("  ")
                        .append(createBookmarkSpan(senderNameColor));
            }
            spannableStringBuilder
                    .append(internal ? "" : "  ")
                    .append(internal ? "" : LocaleController.getInstance().getFormatterDay().format((long) (messageObject.messageOwner.date) * 1000));
        } else {
            spannableStringBuilder
                    .append(internal || messageObject.messageOwner.post_author == null ? "" : " ")
                    .append(translatedSpan);
            if (isBookmarked) {
                spannableStringBuilder
                        .append("  ")
                        .append(createBookmarkSpan(senderNameColor));
            }
            spannableStringBuilder
                    .append(internal ? "" : "  ")
                    .append(internal ? "" : LocaleController.getInstance().getFormatterDay().format((long) (messageObject.messageOwner.date) * 1000));
        }
        return spannableStringBuilder;
    }

    private static boolean canShowLanguage(MessageObject messageObject) {
        String fromCode = messageObject.messageOwner.originalLanguage;
        String toCode = messageObject.messageOwner.translatedToLanguage;
        if (TextUtils.isEmpty(fromCode) || TextUtils.isEmpty(toCode)) {
            return false;
        }
        if (messageObject.messageOwner.originalLanguage.equals(TranslateController.UNKNOWN_LANGUAGE)) {
            return false;
        }
        if (messageObject.messageOwner.post_author != null) {
            return false;
        }
        return MessagesController.getInstance(UserConfig.selectedAccount).getTranslateController().isManualTranslated(messageObject);
    }

    private static void createSpan() {
        if (editedDrawable == null) {
            editedDrawable = Objects.requireNonNull(ContextCompat.getDrawable(ApplicationLoader.applicationContext, R.drawable.msg_edit_solar)).mutate();
        }
        if (editedSpan == null) {
            editedSpan = new SpannableStringBuilder("\u200B");
            editedSpan.setSpan(new ColoredImageSpan(editedDrawable, true), 0, 1, 0);
        }

        ensureDeletedSpan();

        if (translatedDrawable == null) {
            if (NaConfig.INSTANCE.getIconReplacements().Int() == IconsResources.ICON_REPLACE_SOLAR) {
                translatedDrawable = Objects.requireNonNull(ContextCompat.getDrawable(ApplicationLoader.applicationContext, R.drawable.msg_translate_solar_12)).mutate();
            } else {
                translatedDrawable = Objects.requireNonNull(ContextCompat.getDrawable(ApplicationLoader.applicationContext, R.drawable.msg_translate_12)).mutate();
            }
        }
        if (translatedSpan == null) {
            translatedSpan = new SpannableStringBuilder("\u200B");
            translatedSpan.setSpan(new ColoredImageSpan(translatedDrawable, true), 0, 1, 0);
        }

        if (bookmarkDrawable == null) {
            bookmarkDrawable = Objects.requireNonNull(ContextCompat.getDrawable(ApplicationLoader.applicationContext, R.drawable.msg_fave_solar_12)).mutate();
        }

        if (arrowDrawable == null) {
            arrowDrawable = Objects.requireNonNull(ContextCompat.getDrawable(ApplicationLoader.applicationContext, R.drawable.search_arrow));
        }
        if (arrowSpan == null) {
            arrowSpan = new SpannableStringBuilder("\u200B");
            arrowSpan.setSpan(new ColoredImageSpan(arrowDrawable, true), 0, 1, 0);
        }

        if (forwardsDrawable == null) {
            forwardsDrawable = Objects.requireNonNull(ContextCompat.getDrawable(ApplicationLoader.applicationContext, R.drawable.forwards_solar)).mutate();
        }
        if (forwardsSpan == null) {
            forwardsSpan = new SpannableStringBuilder("\u200B");
            forwardsSpan.setSpan(new ColoredImageSpan(forwardsDrawable, true), 0, 1, 0);
        }
    }

    private static SpannableStringBuilder createBookmarkSpan(int senderNameColor) {
        if (bookmarkDrawable == null) {
            bookmarkDrawable = Objects.requireNonNull(ContextCompat.getDrawable(ApplicationLoader.applicationContext, R.drawable.msg_fave_solar_12)).mutate();
        }
        SpannableStringBuilder spannableStringBuilder = new SpannableStringBuilder("\u200B");
        ColoredImageSpan imageSpan = new ColoredImageSpan(bookmarkDrawable, true);
        imageSpan.setTopOffset(-1);
        imageSpan.setOverrideColor(senderNameColor);
        spannableStringBuilder.setSpan(imageSpan, 0, 1, 0);
        return spannableStringBuilder;
    }

    public static SpannableStringBuilder getChannelLabelSpan() {
        if (channelLabelDrawable == null) {
            channelLabelDrawable = Objects.requireNonNull(ContextCompat.getDrawable(ApplicationLoader.applicationContext, R.drawable.channel_label_solar)).mutate();
        }
        if (channelLabelSpan == null) {
            channelLabelSpan = new SpannableStringBuilder("\u200B");
            channelLabelSpan.setSpan(new ColoredImageSpan(channelLabelDrawable, true), 0, 1, 0);
        }
        return channelLabelSpan;
    }

}
