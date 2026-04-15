package tw.nekomimi.nekogram.ui.components;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.View;

import com.exteragram.messenger.components.ActionRow;

import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;

import java.util.ArrayList;
import java.util.List;

import xyz.nextalone.nagram.NaConfig;

@SuppressLint("ViewConstructor")
public class GroupedIconsView extends ActionRow {
    private static final int OPTION_DELETE = 1;
    private static final int OPTION_FORWARD = 2;
    private static final int OPTION_FORWARD_NOQUOTE = 2011;
    private static final int OPTION_COPY = 3;
    private static final int OPTION_COPY_PHOTO = 150;
    private static final int OPTION_COPY_PHOTO_AS_STICKER = 151;
    private static final int OPTION_COPY_LINK = 22;
    private static final int OPTION_COPY_LINK_PM = 2025;
    private static final int OPTION_REPLY = 8;
    private static final int OPTION_REPLY_PM = 2033;
    private static final int OPTION_EDIT = 12;

    public GroupedIconsView(Context context, ChatActivity chatActivity, Theme.ResourcesProvider resourcesProvider, MessageObject messageObject,
                            boolean allowReply, boolean allowReplyPm,
                            boolean allowEdit, boolean allowDelete, boolean allowForward,
                            boolean allowCopy, boolean allowCopyPhoto,
                            boolean allowCopyLink, boolean allowCopyLinkPm) {
        super(context, resourcesProvider, buildItems(chatActivity, messageObject, allowReply, allowReplyPm, allowEdit, allowDelete, allowForward, allowCopy, allowCopyPhoto, allowCopyLink, allowCopyLinkPm));
    }

    public static boolean useGroupedIcons() {
        return NaConfig.INSTANCE.getGroupedMessageMenu().Bool();
    }

    private static List<ActionRow.ActionItem> buildItems(ChatActivity chatActivity, MessageObject messageObject,
                                                         boolean allowReply, boolean allowReplyPm,
                                                         boolean allowEdit, boolean allowDelete, boolean allowForward,
                                                         boolean allowCopy, boolean allowCopyPhoto,
                                                         boolean allowCopyLink, boolean allowCopyLinkPm) {
        List<ActionRow.ActionItem> items = new ArrayList<>();

        items.add(new ActionRow.ActionItem(
                R.drawable.menu_reply,
                allowReply,
                v -> chatActivity.processSelectedOption(OPTION_REPLY),
                allowReplyPm ? v -> {
                    chatActivity.processSelectedOption(OPTION_REPLY_PM);
                    return true;
                } : null
        ));

        if (allowCopy) {
            if (!allowCopyPhoto && messageObject != null && messageObject.isPhoto() && messageObject.isWebpage()) {
                items.add(copyItem(chatActivity, R.drawable.msg_copy, OPTION_COPY, OPTION_COPY_PHOTO, true));
            } else if (allowCopyLink) {
                items.add(copyItem(chatActivity, R.drawable.msg_copy, OPTION_COPY, OPTION_COPY_LINK, true));
            } else if (allowCopyLinkPm) {
                items.add(copyItem(chatActivity, R.drawable.msg_copy, OPTION_COPY, OPTION_COPY_LINK_PM, true));
            } else {
                items.add(copyItem(chatActivity, R.drawable.msg_copy, OPTION_COPY, null, true));
            }
        } else if (allowCopyPhoto) {
            if (messageObject != null && !messageObject.isSticker()) {
                items.add(copyItem(chatActivity, R.drawable.msg_copy_photo, OPTION_COPY_PHOTO, OPTION_COPY_PHOTO_AS_STICKER, true));
            } else if (allowCopyLink) {
                items.add(copyItem(chatActivity, R.drawable.msg_copy_photo, OPTION_COPY_PHOTO, OPTION_COPY_LINK, true));
            } else if (allowCopyLinkPm) {
                items.add(copyItem(chatActivity, R.drawable.msg_copy_photo, OPTION_COPY_PHOTO, OPTION_COPY_LINK_PM, true));
            } else {
                items.add(copyItem(chatActivity, R.drawable.msg_copy_photo, OPTION_COPY_PHOTO, null, true));
            }
        } else if (allowCopyLink && allowDelete) {
            items.add(copyItem(chatActivity, R.drawable.msg_link, OPTION_COPY_LINK, null, true));
        } else if (allowCopyLinkPm) {
            items.add(copyItem(chatActivity, R.drawable.msg_link, OPTION_COPY_LINK_PM, null, true));
        } else {
            items.add(copyItem(chatActivity, R.drawable.msg_copy, OPTION_COPY, null, false));
        }

        if (allowDelete) {
            items.add(simpleItem(chatActivity, R.drawable.msg_delete, OPTION_DELETE, true));
        } else if (allowCopy && allowCopyPhoto) {
            items.add(copyItem(chatActivity, R.drawable.msg_copy_photo, OPTION_COPY_PHOTO, OPTION_COPY_PHOTO_AS_STICKER, true));
        } else {
            items.add(simpleItem(chatActivity, R.drawable.msg_link, OPTION_COPY_LINK, allowCopyLink));
        }

        if (allowEdit) {
            items.add(simpleItem(chatActivity, R.drawable.msg_edit, OPTION_EDIT, true));
        } else {
            items.add(new ActionRow.ActionItem(
                    R.drawable.msg_forward_noquote,
                    allowForward,
                    v -> chatActivity.processSelectedOption(OPTION_FORWARD),
                    allowForward ? v -> {
                        chatActivity.processSelectedOption(OPTION_FORWARD_NOQUOTE);
                        return true;
                    } : null
            ));
        }

        return items;
    }

    private static ActionRow.ActionItem simpleItem(ChatActivity chatActivity, int icon, int option, boolean enabled) {
        View.OnClickListener clickListener = v -> chatActivity.processSelectedOption(option);
        return new ActionRow.ActionItem(icon, enabled, clickListener);
    }

    private static ActionRow.ActionItem copyItem(ChatActivity chatActivity, int icon, int option, Integer longOption, boolean enabled) {
        View.OnClickListener clickListener = v -> chatActivity.processSelectedOption(option);
        View.OnLongClickListener longClickListener = longOption == null ? null : v -> {
            chatActivity.processSelectedOption(longOption);
            return true;
        };
        return new ActionRow.ActionItem(icon, enabled, clickListener, longClickListener);
    }
}
