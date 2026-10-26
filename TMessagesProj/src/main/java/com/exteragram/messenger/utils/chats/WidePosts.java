package com.exteragram.messenger.utils.chats;

import android.graphics.Point;

import com.exteragram.messenger.feed.FeedMessageUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;

import xyz.nextalone.nagram.NaConfig;

import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

/**
 * Layout rules for channel posts rendered edge-to-edge ("wide posts").
 * <p>
 * A wide post keeps the regular {@link org.telegram.ui.Cells.ChatMessageCell} but re-uses the
 * full row width: bubble bounds are stretched to the row's right edge, grouped albums are
 * rescaled onto a 1000 unit grid, media is expanded and the author avatar/name may be embedded
 * into the bubble header (feed channel, comments channel or post author).
 * <p>
 * Whether a message is wide is decided per message, so normal messages can be mixed with wide
 * posts inside the same list.
 */
public abstract class WidePosts {

    /** Visual grid the grouped layouts are normalized to. */
    private static final int GROUP_GRID = 1000;
    /** Horizontal space reserved for the embedded author avatar. */
    private static final int AVATAR_OFFSET = 108;
    private static final int MIN_WIDTH_DP = 120;
    private static final int RIGHT_INSET_DP = 9;
    private static final int MEDIA_PADDING_DP = 100;

    private static final Map<MessageObject.GroupedMessagePosition, GroupPositionState> GROUP_POSITION_STATES = new WeakHashMap<>();
    private static final Map<MessageObject, CommentsPostContext> COMMENTS_POST_CONTEXTS = new WeakHashMap<>();

    /**
     * Resolves the author signature of a post that is shown in a comments (discussion) chat.
     * The signature lives on the original channel post, so it has to be fetched once and then
     * attached to every loaded comment of that post.
     */
    public static final class CommentsPostAuthorLoader {
        private final int currentAccount;
        private final TLRPC.Chat channel;
        private final ArrayList<MessageObject> messages = new ArrayList<>();
        private ChatActivity chatActivity;
        private MessageObject originalMessage;

        public CommentsPostAuthorLoader(int currentAccount, TLRPC.Chat channel) {
            this.currentAccount = currentAccount;
            this.channel = channel;
        }

        private void apply() {
            if (messages.isEmpty()) {
                return;
            }
            for (int i = 0; i < messages.size(); i++) {
                MessageObject message = messages.get(i);
                registerCommentsPostContext(message, channel, originalMessage);
                message.forceUpdate = true;
            }
            if (originalMessage == null || chatActivity == null) {
                return;
            }
            chatActivity.updateWidePostsCommentsAuthor(messages);
        }

        public MessageObject getOriginalMessage() {
            return originalMessage;
        }

        public int load(final int messageId, final BooleanSupplier cancelled) {
            TLRPC.TL_channels_getMessages request = new TLRPC.TL_channels_getMessages();
            request.channel = MessagesController.getInputChannel(channel);
            request.id.add(messageId);
            return ConnectionsManager.getInstance(currentAccount).sendRequest(request, (response, error) ->
                    AndroidUtilities.runOnUIThread(() -> handleResponse(cancelled, response, messageId)));
        }

        private void handleResponse(BooleanSupplier cancelled, TLObject response, int messageId) {
            if (cancelled != null && cancelled.getAsBoolean()) {
                return;
            }
            if (response instanceof TLRPC.messages_Messages) {
                TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                MessagesController.getInstance(currentAccount).putUsers(res.users, false);
                MessagesController.getInstance(currentAccount).putChats(res.chats, false);
                for (int i = 0; i < res.messages.size(); i++) {
                    TLRPC.Message message = res.messages.get(i);
                    if (message != null && message.id == messageId) {
                        originalMessage = new MessageObject(currentAccount, message, true, true);
                        break;
                    }
                }
            }
            NotificationCenter.getInstance(currentAccount).doOnIdle(this::apply);
        }

        public void setMessages(ArrayList<MessageObject> list, ChatActivity activity) {
            messages.clear();
            if (list != null) {
                messages.addAll(list);
            }
            chatActivity = activity;
            apply();
        }
    }

    /** Author signature of the original post, shared by all comments of a discussion. */
    public static final class CommentsPostContext {
        final long channelId;
        String postAuthor;

        CommentsPostContext(long channelId, String postAuthor) {
            this.channelId = channelId;
            this.postAuthor = postAuthor;
        }

        void updatePostAuthor(String value) {
            if (value != null) {
                postAuthor = value;
            }
        }
    }

    /** Original grouped layout values, so a scaled album can be restored exactly. */
    public static final class GroupPositionState {
        final int width;
        final int spanSize;
        final int leftSpanOffset;
        private int appliedWidth;
        private int appliedSpanSize;
        private int appliedLeftSpanOffset;
        private int scaledForWidth;

        GroupPositionState(MessageObject.GroupedMessagePosition position) {
            width = position.pw;
            spanSize = position.spanSize;
            leftSpanOffset = position.leftSpanOffset;
            appliedWidth = width;
            appliedSpanSize = spanSize;
            appliedLeftSpanOffset = leftSpanOffset;
        }

        boolean apply(MessageObject.GroupedMessagePosition position) {
            boolean changed = appliedWidth != position.pw
                    || appliedSpanSize != position.spanSize
                    || appliedLeftSpanOffset != position.leftSpanOffset;
            appliedWidth = position.pw;
            appliedSpanSize = position.spanSize;
            appliedLeftSpanOffset = position.leftSpanOffset;
            return changed;
        }

        void restore(MessageObject.GroupedMessagePosition position) {
            position.pw = width;
            position.spanSize = spanSize;
            position.leftSpanOffset = leftSpanOffset;
        }
    }

    public static void copyCommentsPostContext(MessageObject from, MessageObject to) {
        if (from == null || to == null) {
            return;
        }
        CommentsPostContext context = COMMENTS_POST_CONTEXTS.get(from);
        if (context != null) {
            COMMENTS_POST_CONTEXTS.put(to, context);
        }
    }

    private static int getBaseGroupVisualWidth(MessageObject.GroupedMessages grouped) {
        int maxRow = 0;
        int maxY = 0;
        for (MessageObject.GroupedMessagePosition position : grouped.posArray) {
            if (position != null) {
                maxY = Math.max(maxY, position.maxY);
            }
        }
        for (int row = 0; row <= maxY; row++) {
            int sum = 0;
            for (MessageObject.GroupedMessagePosition position : grouped.posArray) {
                if (position != null && position.minY <= row && position.maxY >= row) {
                    sum += position.pw;
                }
            }
            maxRow = Math.max(maxRow, sum);
        }
        return Math.min(GROUP_GRID, maxRow);
    }

    private static TLRPC.Chat getBroadcastChannel(MessageObject message) {
        long channelId = getChannelId(message);
        return channelId != 0 ? MessagesController.getInstance(message.currentAccount).getChat(channelId) : null;
    }

    public static int getBubbleRight(int width) {
        return Math.max(AndroidUtilities.dp(MIN_WIDTH_DP), width - AndroidUtilities.dp(RIGHT_INSET_DP));
    }

    private static long getChannelId(MessageObject message) {
        if (message == null || message.messageOwner == null) {
            return 0;
        }
        long commentsChannelId = getCommentsChannelId(message);
        if (commentsChannelId != 0) {
            return commentsChannelId;
        }
        TLRPC.Peer peer = message.messageOwner.peer_id;
        long channelId = peer != null ? peer.channel_id : 0;
        if (channelId != 0) {
            return channelId;
        }
        long dialogId = message.getDialogId();
        return dialogId < 0 ? -dialogId : 0;
    }

    private static long getCommentsChannelId(MessageObject message) {
        if (message == null || message.messageOwner == null) {
            return 0;
        }
        CommentsPostContext context = getCommentsPostContext(message);
        return context != null ? context.channelId : 0;
    }

    public static String getCommentsPostAuthor(MessageObject message) {
        CommentsPostContext context = getCommentsPostContext(message);
        return context != null ? context.postAuthor : null;
    }

    private static CommentsPostContext getCommentsPostContext(MessageObject message) {
        return message == null ? null : COMMENTS_POST_CONTEXTS.get(message);
    }

    public static int getDefaultGroupWidth() {
        Point size = AndroidUtilities.displaySize;
        int width = size.x;
        if (AndroidUtilities.isInMultiwindow || !AndroidUtilities.isTablet()) {
            return width;
        }
        if (!AndroidUtilities.isSmallTablet() || width > size.y) {
            return width - Math.max(width * 35 / 100, AndroidUtilities.dp(320));
        }
        return width;
    }

    public static long getEmbeddedProfileDialogId(MessageObject message) {
        if (shouldEmbedFeedChannelProfileAvatar(message)) {
            TLRPC.Chat chat = FeedMessageUtils.resolveDisplayChannel(message);
            return chat != null ? -chat.id : 0;
        }
        if (shouldEmbedCommentsChannelProfileAvatar(message)) {
            long commentsChannelId = getCommentsChannelId(message);
            return commentsChannelId != 0 ? -commentsChannelId : 0;
        }
        if (!shouldEmbedAuthorProfileAvatar(message)) {
            return 0;
        }
        TLRPC.Peer fromId = message.messageOwner.from_id;
        return fromId != null ? DialogObject.getPeerDialogId(fromId) : 0;
    }

    public static String getFeedPostAuthor(MessageObject message) {
        return FeedMessageUtils.hasProfileSignature(message) ? message.messageOwner.post_author : null;
    }

    public static long getFeedSignatureProfileDialogId(MessageObject message) {
        if (!FeedMessageUtils.hasProfileSignature(message)) {
            return 0;
        }
        TLRPC.Peer fromId = message.messageOwner.from_id;
        if (fromId == null) {
            return 0;
        }
        long authorId = DialogObject.getPeerDialogId(fromId);
        if (authorId != DialogObject.getPeerDialogId(message.messageOwner.peer_id)) {
            return authorId;
        }
        return 0;
    }

    /**
     * Height of an expanded media item: keeps the source aspect ratio but clamps it between a
     * minimum and the largest allowed photo size.
     */
    public static int getImageHeight(int sourceWidth, int sourceHeight, int minHeight, int targetWidth) {
        if (sourceWidth <= 0) {
            return AndroidUtilities.dp(MIN_WIDTH_DP);
        }
        float ratio = (float) sourceHeight / sourceWidth;
        int height = Math.round(targetWidth * ratio);
        int upper = Math.max(minHeight, Math.min(AndroidUtilities.getPhotoSize(), targetWidth + AndroidUtilities.dp(MEDIA_PADDING_DP)));
        return Math.max(AndroidUtilities.dp(MIN_WIDTH_DP), Math.min(height, upper));
    }

    private static MessageObject getPrimaryGroupMessage(MessageObject.GroupedMessages grouped) {
        if (grouped == null || grouped.messages == null) {
            return null;
        }
        int mask = grouped.reversed ? 10 : 5;
        for (int i = 0; i < grouped.messages.size(); i++) {
            MessageObject message = grouped.messages.get(i);
            MessageObject.GroupedMessagePosition position = grouped.getPosition(message);
            if (position != null && (position.flags & mask) == mask) {
                return message;
            }
        }
        return grouped.messages.isEmpty() ? null : grouped.messages.get(0);
    }

    /** Width the current grouped layout has been scaled for, or 0 if it is untouched. */
    public static int getScaledGroupWidth(MessageObject.GroupedMessages grouped) {
        if (grouped == null || grouped.posArray == null || grouped.posArray.isEmpty()) {
            return 0;
        }
        GroupPositionState state = GROUP_POSITION_STATES.get(grouped.posArray.get(0));
        return state != null ? state.scaledForWidth : 0;
    }

    public static boolean hasSameEmbeddedProfileHeader(MessageObject first, MessageObject second) {
        return shouldEmbedFeedChannelProfileAvatar(first) == shouldEmbedFeedChannelProfileAvatar(second)
                && getEmbeddedProfileDialogId(first) == getEmbeddedProfileDialogId(second);
    }

    private static boolean isChannelPostOrSponsored(MessageObject message) {
        if (message == null || message.messageOwner == null || message.preview
                || message.type == MessageObject.TYPE_JOINED_CHANNEL) {
            return false;
        }
        if (message.isSponsored()) {
            return true;
        }
        long channelId = getCommentsChannelId(message);
        if (channelId == 0 && message.messageOwner.peer_id == null) {
            return false;
        }
        if (channelId == 0) {
            channelId = getChannelId(message);
        }
        if (channelId == 0) {
            return false;
        }
        TLRPC.Chat chat = MessagesController.getInstance(message.currentAccount).getChat(channelId);
        return chat != null ? ChatObject.isChannelAndNotMegaGroup(chat)
                : message.messageOwner.post || channelId != 0;
    }

    public static boolean isCommentsChannelPost(MessageObject message) {
        return getCommentsChannelId(message) != 0;
    }

    private static boolean isEmbeddedProfileAvatarMessage(MessageObject message) {
        if (message == null || message.messageOwner == null) {
            return false;
        }
        if (!shouldExpand(message) && (!isEnabledFor(message) || !isEmbeddedProfileAvatarMessageWithoutBackground(message))) {
            return false;
        }
        if (message.isOutOwner() || message.isSponsored() || message.isExpiredStory() || !message.needDrawAvatar()) {
            return false;
        }
        TLRPC.MessageFwdHeader fwdHeader = message.messageOwner.fwd_from;
        if (fwdHeader != null && fwdHeader.psa_type != null && !fwdHeader.psa_type.isEmpty()) {
            return false;
        }
        return getChannelId(message) != 0;
    }

    public static boolean isEmbeddedProfileAvatarMessageWithoutBackground(MessageObject message) {
        if (message == null) {
            return false;
        }
        int type = message.type;
        return type == MessageObject.TYPE_ROUND_VIDEO || type == MessageObject.TYPE_STICKER
                || type == MessageObject.TYPE_ANIMATED_STICKER || type == MessageObject.TYPE_EMOJIS;
    }

    public static boolean isEnabledFor(MessageObject message) {
        if (message == null || (!NaConfig.INSTANCE.getWidePostsInFeed().Bool() && !NaConfig.INSTANCE.getWidePostsInChannels().Bool())) {
            return false;
        }
        return isEnabledFor(message.searchType == 4, isChannelPostOrSponsored(message));
    }

    public static boolean isEnabledFor(boolean inFeed, boolean channelPostOrSponsored) {
        if (!channelPostOrSponsored) {
            return false;
        }
        return inFeed ? NaConfig.INSTANCE.getWidePostsInFeed().Bool() : NaConfig.INSTANCE.getWidePostsInChannels().Bool();
    }

    public static boolean isExpandableMedia(MessageObject message) {
        int type = message.type;
        return type == MessageObject.TYPE_PHOTO || type == MessageObject.TYPE_EXTENDED_MEDIA_PREVIEW
                || type == MessageObject.TYPE_VIDEO || type == MessageObject.TYPE_GIF;
    }

    private static boolean isGroupedLayout(MessageObject.GroupedMessages grouped) {
        if (grouped == null) {
            return false;
        }
        return (grouped.posArray != null && grouped.posArray.size() > 1)
                || (grouped.messages != null && grouped.messages.size() > 1);
    }

    private static boolean isPeerResolved(int account, long dialogId) {
        if (dialogId > 0) {
            return MessagesController.getInstance(account).getUser(dialogId) != null;
        }
        return dialogId >= 0 || MessagesController.getInstance(account).getChat(-dialogId) != null;
    }

    public static boolean isProfileResolved(MessageObject message) {
        if (message == null || !shouldEmbedProfileAvatar(message)) {
            return true;
        }
        return isPeerResolved(message.currentAccount, getEmbeddedProfileDialogId(message))
                && isPeerResolved(message.currentAccount, getFeedSignatureProfileDialogId(message));
    }

    public static boolean isTextMessage(MessageObject message) {
        int type = message.type;
        return type == MessageObject.TYPE_TEXT || type == MessageObject.TYPE_ARTICLE || type == MessageObject.TYPE_STORY_MENTION;
    }

    public static boolean isWideGroupedMedia(MessageObject.GroupedMessages grouped) {
        return isGroupedLayout(grouped) && !grouped.isDocuments;
    }

    /**
     * Fills every row of a scaled album: the last position of the row (preferring the one flagged
     * as edge) absorbs the difference between the row width and the target width.
     */
    private static void normalizeGroupRows(MessageObject.GroupedMessages grouped, int target, boolean withAvatar) {
        int maxY = 0;
        for (MessageObject.GroupedMessagePosition position : grouped.posArray) {
            if (position != null) {
                maxY = Math.max(maxY, position.maxY);
            }
        }
        for (int row = 0; row <= maxY; row++) {
            int rowWidth = 0;
            MessageObject.GroupedMessagePosition last = null;
            for (MessageObject.GroupedMessagePosition position : grouped.posArray) {
                if (position == null || position.minY > row || position.maxY < row) {
                    continue;
                }
                rowWidth += position.pw;
                boolean edge = (position.flags & 2) != 0;
                boolean lastIsEdge = last != null && (last.flags & 2) != 0;
                if (last == null || (edge && !lastIsEdge) || (edge == lastIsEdge && position.maxX > last.maxX)) {
                    last = position;
                }
            }
            if (last == null) {
                continue;
            }
            last.pw = Math.max(1, last.pw + target - rowWidth);
            GroupPositionState state = GROUP_POSITION_STATES.get(last);
            if (withAvatar) {
                if (last.leftSpanOffset > 0) {
                    last.leftSpanOffset = Math.max(0, target - last.pw);
                }
            } else if (state != null && state.leftSpanOffset > 0) {
                last.leftSpanOffset = Math.max(0, target - last.pw);
            }
        }
    }

    public static void registerCommentsPostContext(MessageObject message, TLRPC.Chat chat, MessageObject originalMessage) {
        if (message == null || message.messageOwner == null || !ChatObject.isChannelAndNotMegaGroup(chat)) {
            return;
        }
        TLRPC.Message owner = message.messageOwner;
        TLRPC.MessageFwdHeader fwdHeader = owner.fwd_from;
        long fromChannelId = owner.from_id != null ? owner.from_id.channel_id : 0;
        long peerChannelId = owner.peer_id != null ? owner.peer_id.channel_id : 0;
        long savedFromChannelId = fwdHeader != null && fwdHeader.saved_from_peer != null ? fwdHeader.saved_from_peer.channel_id : 0;
        long channelId = chat.id;
        boolean reposted = fromChannelId == channelId && peerChannelId != 0 && peerChannelId != channelId;
        boolean linked = owner.reply_to == null && fwdHeader != null
                && (owner.isThreadMessage || savedFromChannelId == channelId
                || fwdHeader.channel_post != 0 || owner.replies != null);
        if (!reposted || !linked) {
            return;
        }
        String author = originalMessage != null && originalMessage.messageOwner != null
                && originalMessage.getDialogId() == -chat.id ? originalMessage.messageOwner.post_author : null;
        CommentsPostContext context = COMMENTS_POST_CONTEXTS.get(message);
        if (context == null || context.channelId != chat.id) {
            COMMENTS_POST_CONTEXTS.put(message, new CommentsPostContext(chat.id, author));
        } else {
            context.updatePostAuthor(author);
        }
    }

    /**
     * Reserved space for the embedded author avatar is removed from the layout before scaling:
     * edge positions lose it from both the bubble width and the span, inner "last" positions get
     * it added back to their span, inner positions with a left offset shift the offset.
     */
    private static void removeGroupAvatarOffset(MessageObject.GroupedMessagePosition position) {
        if (position.edge) {
            position.pw = Math.max(1, position.pw - AVATAR_OFFSET);
            if (position.spanSize != GROUP_GRID) {
                position.spanSize = Math.max(1, position.spanSize - AVATAR_OFFSET);
            }
            return;
        }
        if ((position.flags & 2) == 0) {
            return;
        }
        if (position.spanSize != GROUP_GRID) {
            position.spanSize = position.spanSize + AVATAR_OFFSET;
            return;
        }
        if (position.leftSpanOffset != 0) {
            position.leftSpanOffset = Math.max(0, position.leftSpanOffset - AVATAR_OFFSET);
        }
    }

    private static void restoreGroupedLayout(MessageObject.GroupedMessages grouped) {
        if (grouped == null || grouped.posArray == null || grouped.posArray.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (MessageObject.GroupedMessagePosition position : grouped.posArray) {
            if (position == null) {
                continue;
            }
            GroupPositionState state = GROUP_POSITION_STATES.remove(position);
            if (state != null) {
                state.restore(position);
                changed = true;
            }
        }
        if (changed) {
            grouped.cachedWidthForCaption = -1;
        }
    }

    public static boolean shouldDrawBackground(MessageObject message) {
        if (message == null) {
            return false;
        }
        if (message.shouldDrawWithoutBackground()) {
            return message.isRoundVideo() && message.isVoiceTranscriptionOpen();
        }
        return true;
    }

    public static boolean shouldDrawFeedAuthorSignature(MessageObject message) {
        return FeedMessageUtils.hasProfileSignature(message);
    }

    public static boolean shouldEmbedAuthorProfileAvatar(MessageObject message) {
        if (!isEmbeddedProfileAvatarMessage(message) || message.searchType == 4) {
            return false;
        }
        TLRPC.Chat channel = getBroadcastChannel(message);
        return channel != null && (channel.signature_profiles || message.currentEvent != null)
                && ChatObject.isChannelAndNotMegaGroup(channel);
    }

    private static boolean shouldEmbedCommentsChannelProfileAvatar(MessageObject message) {
        if (!isEmbeddedProfileAvatarMessage(message)) {
            return false;
        }
        long commentsChannelId = getCommentsChannelId(message);
        if (commentsChannelId == 0) {
            return false;
        }
        TLRPC.Chat chat = MessagesController.getInstance(message.currentAccount).getChat(commentsChannelId);
        return getCommentsPostContext(message) != null || ChatObject.isChannelAndNotMegaGroup(chat);
    }

    private static boolean shouldEmbedFeedChannelProfileAvatar(MessageObject message) {
        return isEmbeddedProfileAvatarMessage(message) && FeedMessageUtils.resolveDisplayChannel(message) != null;
    }

    public static boolean shouldEmbedProfileAvatar(MessageObject message) {
        return shouldEmbedFeedChannelProfileAvatar(message)
                || shouldEmbedCommentsChannelProfileAvatar(message)
                || shouldEmbedAuthorProfileAvatar(message);
    }

    private static boolean shouldExpand(MessageObject message) {
        return isEnabledFor(message) && !message.shouldDrawWithoutBackground();
    }

    public static boolean shouldHideAuxiliaryActions(MessageObject message) {
        return isEnabledFor(message) && !message.isSponsored();
    }

    public static boolean shouldSuppressExternalAvatar(MessageObject message) {
        return isEnabledFor(message) && message.searchType == 4 && message.isSponsored();
    }

    /**
     * Rescales a grouped album so that its rows span the wide bubble and restores the original
     * layout when the message stops being wide. Safe to call on every layout pass.
     */
    public static void updateGroupedLayout(MessageObject.GroupedMessages grouped, int parentWidth) {
        if (!isWideGroupedMedia(grouped) || parentWidth <= 0) {
            restoreGroupedLayout(grouped);
            return;
        }
        MessageObject primary = getPrimaryGroupMessage(grouped);
        if (!shouldExpand(primary)) {
            restoreGroupedLayout(grouped);
            return;
        }
        boolean withAvatar = shouldEmbedProfileAvatar(primary) || shouldSuppressExternalAvatar(primary);
        for (MessageObject.GroupedMessagePosition position : grouped.posArray) {
            if (position == null) {
                continue;
            }
            GroupPositionState state = GROUP_POSITION_STATES.get(position);
            if (state == null) {
                state = new GroupPositionState(position);
                GROUP_POSITION_STATES.put(position, state);
            }
            state.restore(position);
            if (withAvatar) {
                removeGroupAvatarOffset(position);
            }
        }
        int baseWidth = getBaseGroupVisualWidth(grouped);
        if (baseWidth <= 0) {
            restoreGroupedLayout(grouped);
            return;
        }
        int target = Utilities.clamp(
                Math.round(Math.max(AndroidUtilities.dp(MIN_WIDTH_DP), parentWidth - AndroidUtilities.dp(RIGHT_INSET_DP))
                        * (float) GROUP_GRID / parentWidth),
                GROUP_GRID, 1);
        float scale = (float) target / baseWidth;
        for (MessageObject.GroupedMessagePosition position : grouped.posArray) {
            if (position == null || GROUP_POSITION_STATES.get(position) == null) {
                continue;
            }
            boolean hadOffset = position.leftSpanOffset != 0;
            int scaledWidth = Math.max(1, Math.round(position.pw * scale));
            position.pw = scaledWidth;
            position.leftSpanOffset = hadOffset ? Math.max(0, target - scaledWidth) : 0;
        }
        normalizeGroupRows(grouped, target, withAvatar);
        int remainder = GROUP_GRID - target;
        boolean changed = false;
        for (MessageObject.GroupedMessagePosition position : grouped.posArray) {
            if (position == null) {
                continue;
            }
            GroupPositionState state = GROUP_POSITION_STATES.get(position);
            if (state == null) {
                continue;
            }
            position.spanSize = position.pw;
            if (withAvatar && (position.flags & 2) != 0) {
                position.spanSize = position.pw + remainder;
            }
            state.scaledForWidth = parentWidth;
            changed |= state.apply(position);
        }
        if (changed) {
            grouped.cachedWidthForCaption = -1;
        }
    }
}
