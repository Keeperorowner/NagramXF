package com.exteragram.messenger.feed;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;

import java.util.ArrayList;
import java.util.Calendar;

/**
 * Helpers for building feed rows (unread divider, date headers), remapping
 * synthetic feed ids back to real channel ids for API requests, and the set
 * of message menu actions allowed inside the feed.
 */
public abstract class FeedMessageUtils {
    public static boolean isAllowedDoubleTapAction(int action) {
        return action == 2 || action == 3 || action == 4 || action == 6 || action == 9;
    }

    public static boolean isAllowedFeedOption(int id) {
        return id == 2 || id == 3 || id == 4 || id == 6 || id == 7 || id == 8 || id == 10 || id == 16 || id == 22 || id == 29 || id == 36 || id == 200 || id == 203 || id == 206;
    }

    public static boolean isPostRow(MessageObject message) {
        return message != null && !message.isDateObject && message.type != MessageObject.TYPE_LOADING && !message.isSponsored();
    }

    /**
     * Channel whose post is being displayed inside the feed. Only real broadcast
     * channel posts qualify; anything else (previews, sponsored rows, joined-channel
     * recommendations) is not a feed post.
     */
    public static TLRPC.Chat resolveDisplayChannel(MessageObject message) {
        if (message == null || message.messageOwner == null || message.searchType != 4
                || message.preview || message.isSponsored()
                || message.type == MessageObject.TYPE_JOINED_CHANNEL) {
            return null;
        }
        TLRPC.Message owner = message.messageOwner;
        if (owner.post && owner.action == null && owner.peer_id != null && owner.peer_id.channel_id != 0) {
            TLRPC.Chat chat = MessagesController.getInstance(message.currentAccount).getChat(owner.peer_id.channel_id);
            if (ChatObject.isChannelAndNotMegaGroup(chat)) {
                return chat;
            }
        }
        return null;
    }

    /** Whether the displayed feed channel shows per-post author signatures. */
    public static boolean hasProfileSignature(MessageObject message) {
        TLRPC.Chat chat = resolveDisplayChannel(message);
        return chat != null && chat.signature_profiles;
    }

    public static MessageObject createUnreadDivider(int account, int stableId) {
        TLRPC.TL_message tl_message = new TLRPC.TL_message();
        tl_message.message = "";
        tl_message.id = 0;
        MessageObject message = new MessageObject(account, tl_message, false, false);
        message.type = MessageObject.TYPE_LOADING;
        message.contentType = 2;
        message.stableId = stableId;
        return message;
    }

    public static MessageObject createDateHeader(int account, MessageObject anchor, int stableId) {
        TLRPC.TL_message tl_message = new TLRPC.TL_message();
        tl_message.message = LocaleController.formatDateChat(anchor.messageOwner.date);
        tl_message.id = 0;
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(((long) anchor.messageOwner.date) * 1000);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        tl_message.date = (int) (calendar.getTimeInMillis() / 1000);
        MessageObject message = new MessageObject(account, tl_message, false, false);
        message.type = 10;
        message.contentType = 1;
        message.isDateObject = true;
        message.stableId = stableId;
        return message;
    }

    public static TLRPC.InputPeer getInputPeerForMessageRequest(MessagesController messagesController, long dialogId, boolean useMessageDialog, MessageObject message) {
        if (useMessageDialog && message != null) {
            dialogId = message.getDialogId();
        }
        return messagesController.getInputPeer(dialogId);
    }

    /**
     * Whether two message objects denote the same playback item. This keeps the player's own
     * identity rules (same account, same dialog, same event id kind and the same row id) and
     * adds one feed-only case: a feed row carries a synthetic row id for a real channel
     * message, so when at least one side is such a row and neither side is an admin log
     * event, the real channel message id is compared as well.
     */
    public static boolean isSamePlaybackMessage(int account, MessageObject first, MessageObject second) {
        if (first == null || second == null || first.currentAccount != account || second.currentAccount != account) {
            return false;
        }
        if (first.getDialogId() != second.getDialogId()) {
            return false;
        }
        if ((first.eventId == 0) != (second.eventId == 0)) {
            return false;
        }
        if (first.eventId != 0) {
            // Channel admin log rows keep the baseline row id comparison.
            return first.getId() == second.getId();
        }
        if (first.getId() == second.getId()) {
            return true;
        }
        return (isFeedRow(first) || isFeedRow(second)) && first.getFeedRealId() == second.getFeedRealId();
    }

    /** Feed rows expose a synthetic row id over the real channel message id. */
    private static boolean isFeedRow(MessageObject message) {
        return message.searchType == 4 && message.getRealId() != message.getId();
    }

    public static boolean matchesPlaybackNotification(int account, MessageObject message, int messageId) {
        return matchesPlaybackNotification(account, message, messageId, null);
    }

    /**
     * Matches a playback notification against a rendered row. The message the notification
     * belongs to is {@code source} when the payload carries it (the
     * {@code messagePlayingDidReset} payload appends the stopped message), otherwise the
     * current player item. A known source must match the notification id and the full
     * account + dialog + event id + row/real id identity; only when no identity is available
     * at all the plain row id comparison is kept.
     */
    public static boolean matchesPlaybackNotification(int account, MessageObject message, int messageId, MessageObject source) {
        if (message == null || message.currentAccount != account) {
            return false;
        }
        if (source != null) {
            if (source.currentAccount != account || source.getId() != messageId) {
                return false;
            }
            return isSamePlaybackMessage(account, message, source);
        }
        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (playing != null) {
            if (playing.currentAccount != account || playing.getId() != messageId) {
                return false;
            }
            return isSamePlaybackMessage(account, message, playing);
        }
        // No player identity available: keep the legacy row id comparison.
        return message.getId() == messageId;
    }

    /**
     * Id under which the current host renders the playing message. Feed hosts address rows
     * by their synthetic id, so a real chat message is mapped through the loaded feed row of
     * the same channel; when that row is not loaded no synthetic id is guessed.
     */
    public static int getPlaybackScrollMessageId(boolean feedMode, long dialogId, MessageObject message) {
        if (message == null) {
            return 0;
        }
        if (feedMode) {
            if (message.searchType == 4) {
                return message.getId();
            }
            FeedController feedController = FeedController.peekInstance(message.currentAccount);
            if (feedController != null) {
                MessageObject row = feedController.getMessage(message.getDialogId(), message.getRealId());
                if (row != null) {
                    return row.getId();
                }
            }
            return message.getId();
        }
        if (message.searchType == 4 && message.getDialogId() == dialogId) {
            return message.getRealId();
        }
        return message.getId();
    }

    public static MessageObject getForwardingMessageObject(int account, boolean feedMode, MessageObject message) {
        if (!feedMode || message == null || message.getId() == message.getRealId()) {
            return message;
        }
        TLRPC.TL_message copy = copyMessage(message.messageOwner);
        copy.id = message.getRealId();
        copy.realId = 0;
        copy.dialog_id = message.getDialogId();
        MessageObject result = new MessageObject(account, copy, message.replyMessageObject, null, null, null, null, false, true, 0L, false, false, false);
        result.isPrimaryGroupMessage = message.isPrimaryGroupMessage;
        result.localGroupId = message.localGroupId;
        result.copyStableParams(message);
        return result;
    }

    public static MessageObject createReplacement(int account, long dialogId, MessageObject message) {
        if (message == null) {
            return null;
        }
        FeedController feedController = FeedController.getInstance(account);
        MessageObject existing = feedController.getMessage(dialogId, message.getRealId());
        if (existing == null) {
            return null;
        }
        TLRPC.TL_message copy = copyMessage(message.messageOwner);
        copy.id = existing.getId();
        copy.realId = existing.getRealId();
        copy.dialog_id = existing.getDialogId();
        MessageObject result = new MessageObject(account, copy, existing.replyMessageObject, null, null, null, null, true, true, 0L, false, false, false, 4);
        result.isPrimaryGroupMessage = existing.isPrimaryGroupMessage;
        result.localGroupId = existing.localGroupId;
        result.copyStableParams(existing);
        feedController.replaceMessage(existing, result);
        return result;
    }

    public static ArrayList<MessageObject> createReplacements(int account, long dialogId, ArrayList<MessageObject> messages) {
        ArrayList<MessageObject> result = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            MessageObject replacement = createReplacement(account, dialogId, messages.get(i));
            if (replacement != null) {
                result.add(replacement);
            }
        }
        return result;
    }

    public static void filterAllowedOptions(ArrayList<CharSequence> titles, ArrayList<Integer> ids, ArrayList<Integer> icons) {
        for (int i = ids.size() - 1; i >= 0; i--) {
            if (!isAllowedFeedOption(ids.get(i))) {
                icons.remove(i);
                titles.remove(i);
                ids.remove(i);
            }
        }
    }

    /**
     * Copies a feed post link for the channel that actually owns the post:
     * the request uses the real channel id and the real per-channel message id,
     * and channel access rules (public username, private link) stay server-side.
     */
    public static void copyFeedPostLink(ChatActivity chatActivity, MessageObject message) {
        if (chatActivity == null || message == null || message.getDialogId() >= 0) {
            return;
        }
        TLRPC.Chat chat = chatActivity.getMessagesController().getChat(Long.valueOf(-message.getDialogId()));
        if (ChatObject.isChannel(chat)) {
            TLRPC.TL_channels_exportMessageLink request = new TLRPC.TL_channels_exportMessageLink();
            request.id = message.getRealId();
            request.channel = MessagesController.getInputChannel(chat);
            chatActivity.getConnectionsManager().sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (response instanceof TLRPC.TL_exportedMessageLink) {
                    String link = ((TLRPC.TL_exportedMessageLink) response).link;
                    if (AndroidUtilities.addToClipboard(link) && BulletinFactory.canShowBulletin(chatActivity)) {
                        BulletinFactory.of(chatActivity).createCopyLinkBulletin(link.contains("/c/")).show();
                    }
                }
            }));
        }
    }

    public static void copyTranslationState(MessageObject from, MessageObject to) {
        if (from == null || to == null || from == to || from.messageOwner == null || to.messageOwner == null) {
            return;
        }
        TLRPC.Message source = from.messageOwner;
        TLRPC.Message target = to.messageOwner;
        target.translatedText = source.translatedText;
        target.translatedToLanguage = source.translatedToLanguage;
        target.translatedVoiceTranscription = source.translatedVoiceTranscription;
        target.translatedPoll = source.translatedPoll;
        target.summaryText = source.summaryText;
        target.summarizedOpen = source.summarizedOpen;
        target.translatedSummaryText = source.translatedSummaryText;
        target.translatedSummaryLanguage = source.translatedSummaryLanguage;
    }

    private static TLRPC.TL_message copyMessage(TLRPC.Message source) {
        TLRPC.TL_message copy = new TLRPC.TL_message();
        copy.id = source.id;
        copy.from_id = source.from_id;
        copy.from_boosts_applied = source.from_boosts_applied;
        copy.peer_id = source.peer_id;
        copy.saved_peer_id = source.saved_peer_id;
        copy.date = source.date;
        copy.expire_date = source.expire_date;
        copy.action = source.action;
        copy.message = source.message;
        copy.media = source.media;
        copy.flags = source.flags;
        copy.flags2 = source.flags2;
        copy.mentioned = source.mentioned;
        copy.media_unread = source.media_unread;
        copy.out = source.out;
        copy.unread = source.unread;
        copy.entities = source.entities;
        copy.via_bot_name = source.via_bot_name;
        copy.reply_markup = source.reply_markup;
        copy.views = source.views;
        copy.forwards = source.forwards;
        copy.replies = source.replies;
        copy.edit_date = source.edit_date;
        copy.silent = source.silent;
        copy.post = source.post;
        copy.from_scheduled = source.from_scheduled;
        copy.legacy = source.legacy;
        copy.edit_hide = source.edit_hide;
        copy.pinned = source.pinned;
        copy.fwd_from = source.fwd_from;
        copy.via_bot_id = source.via_bot_id;
        copy.via_business_bot_id = source.via_business_bot_id;
        copy.reply_to = source.reply_to;
        copy.post_author = source.post_author;
        copy.grouped_id = source.grouped_id;
        copy.reactions = source.reactions;
        copy.restriction_reason = source.restriction_reason;
        copy.ttl_period = source.ttl_period;
        copy.quick_reply_shortcut_id = source.quick_reply_shortcut_id;
        copy.effect = source.effect;
        copy.noforwards = source.noforwards;
        copy.invert_media = source.invert_media;
        copy.offline = source.offline;
        copy.factcheck = source.factcheck;
        copy.send_state = source.send_state;
        copy.fwd_msg_id = source.fwd_msg_id;
        copy.params = source.params;
        copy.random_id = source.random_id;
        copy.local_id = source.local_id;
        copy.attachPath = source.attachPath;
        copy.dialog_id = source.dialog_id;
        copy.ttl = source.ttl;
        copy.destroyTime = source.destroyTime;
        copy.destroyTimeMillis = source.destroyTimeMillis;
        copy.layer = source.layer;
        copy.seq_in = source.seq_in;
        copy.seq_out = source.seq_out;
        copy.with_my_score = source.with_my_score;
        copy.replyMessage = source.replyMessage;
        copy.reqId = source.reqId;
        copy.realId = source.realId;
        copy.stickerVerified = source.stickerVerified;
        copy.isThreadMessage = source.isThreadMessage;
        copy.voiceTranscription = source.voiceTranscription;
        copy.voiceTranscriptionOpen = source.voiceTranscriptionOpen;
        copy.voiceTranscriptionRated = source.voiceTranscriptionRated;
        copy.voiceTranscriptionFinal = source.voiceTranscriptionFinal;
        copy.voiceTranscriptionForce = source.voiceTranscriptionForce;
        copy.voiceTranscriptionId = source.voiceTranscriptionId;
        copy.premiumEffectWasPlayed = source.premiumEffectWasPlayed;
        copy.originalLanguage = source.originalLanguage;
        copy.translatedToLanguage = source.translatedToLanguage;
        copy.translatedText = source.translatedText;
        copy.replyStory = source.replyStory;
        copy.quick_reply_shortcut = source.quick_reply_shortcut;
        return copy;
    }
}
