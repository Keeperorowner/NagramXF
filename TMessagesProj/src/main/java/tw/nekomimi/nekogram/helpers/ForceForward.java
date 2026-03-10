
package tw.nekomimi.nekogram.helpers;

import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;

import tw.nekomimi.nekogram.NekoConfig;

public class ForceForward {

    private static final String FORCE_FORWARD_KEY = "ForceForward";

    private boolean isForceForwardMode = false;
    private ArrayList<MessageObject> forwardingMessages;
    private MessageObject forwardingMessage;
    private MessageObject.GroupedMessages forwardingMessageGroup;
    private final ChatActivity parentFragment;
    private final int currentAccount;
    
    public ForceForward(ChatActivity fragment, int account) {
        this.parentFragment = fragment;
        this.currentAccount = account;
    }

    public static boolean isEnabled() {
        return NekoConfig.getPreferences().getBoolean(FORCE_FORWARD_KEY, true);
    }

    public static void setEnabled(boolean enabled) {
        NekoConfig.getPreferences().edit().putBoolean(FORCE_FORWARD_KEY, enabled).apply();
    }

    public static void applyToMessage(TLRPC.Message message) {
        if (!isEnabled() || message == null) {
            return;
        }
        message.noforwards = false;
    }
    
    public boolean isForceForwardEnabled() {
        return isEnabled();
    }
    
    public boolean isForceForwardMode() {
        return isForceForwardMode;
    }
    
    public void setForceForwardMode(boolean mode) {
        this.isForceForwardMode = mode;
    }
    
    public void setForwardingMessage(MessageObject message) {
        this.forwardingMessage = message;
    }
    
    public void setForwardingMessageGroup(MessageObject.GroupedMessages group) {
        this.forwardingMessageGroup = group;
    }
    
    public MessageObject getForwardingMessage() {
        return forwardingMessage;
    }
    
    public MessageObject.GroupedMessages getForwardingMessageGroup() {
        return forwardingMessageGroup;
    }
    
    public void setForwardingMessages(ArrayList<MessageObject> messages) {
        this.forwardingMessages = messages;
    }
    
    public ArrayList<MessageObject> getForwardingMessages() {
        return forwardingMessages;
    }
    
    public void resetForceForwardMode() {
        isForceForwardMode = false;
        forwardingMessages = null;
        forwardingMessage = null;
        forwardingMessageGroup = null;
    }
    
    private CharSequence getMessageCaption(MessageObject mo) {
        return parentFragment.getMessageCaption(mo, parentFragment.getValidGroupedMessage(mo), null);
    }

    private String resolvePath(MessageObject mo) {
        if (mo.messageOwner == null) return null;
        return FileLoader.getInstance(currentAccount).getPathToMessage(mo.messageOwner).toString();
    }

    private boolean ensureDownloaded(MessageObject mo) {
        if (mo == null || mo.messageOwner == null) return false;
        
        String path = resolvePath(mo);
        if (!TextUtils.isEmpty(path) && new File(path).exists()) return true;

        if (mo.getDocument() != null) {
            FileLoader.getInstance(currentAccount).loadFile(mo.getDocument(), mo, FileLoader.PRIORITY_NORMAL, 0);
            return false;
        }
        
        if (mo.isPhoto()) {
            TLRPC.Photo photo = MessageObject.getPhoto(mo.messageOwner);
            if (photo != null) {
                TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, 1280);
                if (size != null) {
                    ImageLocation imageLocation = ImageLocation.getForObject(size, mo.messageOwner);
                    if (imageLocation != null) {
                        FileLoader.getInstance(currentAccount).loadFile(imageLocation, mo.messageOwner, "jpg", FileLoader.PRIORITY_NORMAL, 0);
                    }
                }
            }
            return false;
        }
        
        if (mo.isVideo()) {
            if (mo.getDocument() == null && mo.messageOwner.media instanceof TLRPC.TL_messageMediaVideo_old) {
                TLRPC.TL_messageMediaVideo_old videoMedia = (TLRPC.TL_messageMediaVideo_old) mo.messageOwner.media;
                 if (videoMedia.video_unused != null && videoMedia.video_unused.thumb != null) {
                     ImageLocation imageLocation = ImageLocation.getForObject(videoMedia.video_unused.thumb, mo.messageOwner);
                     if (imageLocation != null) {
                         FileLoader.getInstance(currentAccount).loadFile(imageLocation, mo.messageOwner, "jpg", FileLoader.PRIORITY_NORMAL, 0);
                     }
                 }
            }
            return false;
        }
        
        return false;
    }

    private void sendMediaBatch(ArrayList<SendMessagesHelper.SendingMediaInfo> list, long targetDialogId, boolean isDocument, boolean isGrouping) {
        SendMessagesHelper.prepareSendingMedia(
                parentFragment.getAccountInstance(),
                list,
                targetDialogId,
                null,
                null,
                null,
                null,
                isDocument,
                isGrouping,
                null,
                true,
                0,
                0,
                parentFragment.getChatMode(),
                false,
                null,
                parentFragment.quickReplyShortcut,
                parentFragment.getQuickReplyId(),
                0,
                false,
                0,
                parentFragment.getSendMonoForumPeerId(),
                parentFragment.getSendMessageSuggestionParams()
        );
    }

    private void addToGroup(long gid,
                            SendMessagesHelper.SendingMediaInfo info,
                            HashMap<Long, ArrayList<SendMessagesHelper.SendingMediaInfo>> map,
                            HashMap<Long, Integer> remain,
                            boolean document,
                            long targetDialogId) {
        ArrayList<SendMessagesHelper.SendingMediaInfo> list = map.computeIfAbsent(gid, k -> new ArrayList<>());
        list.add(info);
        Integer r = remain.get(gid);
        if (r != null) {
            r = r - 1;
            if (r <= 0) {
                map.remove(gid);
                remain.remove(gid);
                sendMediaBatch(new ArrayList<>(list), targetDialogId, document, !document);
            } else {
                remain.put(gid, r);
            }
        }
    }
    
    public void runForceForward(ArrayList<MessageObject> messagesToSend, long targetDialogId, boolean showUndo) {
        if (messagesToSend == null || messagesToSend.isEmpty() || parentFragment.getParentActivity() == null) return;

        try {
            HashMap<Long, ArrayList<SendMessagesHelper.SendingMediaInfo>> albumMap = new HashMap<>();
            HashMap<Long, ArrayList<SendMessagesHelper.SendingMediaInfo>> docAlbumMap = new HashMap<>();
            HashMap<Long, Integer> albumRemain = new HashMap<>();
            ArrayList<SendMessagesHelper.SendingMediaInfo> singles = new ArrayList<>();

            for (MessageObject mo : messagesToSend) {
                long gid = mo.getGroupId();
                if (gid == 0) continue;
                
                boolean groupedMedia = mo.isPhoto() || mo.isVideo() || mo.isGif();
                boolean groupedDoc = mo.getDocument() != null && !mo.isVideo() && !MessageObject.isGifMessage(mo.messageOwner) && !mo.isSticker() && !mo.isAnimatedSticker();
                
                if (groupedMedia || groupedDoc) {
                    albumRemain.put(gid, albumRemain.getOrDefault(gid, 0) + 1);
                }
            }

            for (MessageObject mo : messagesToSend) {
                CharSequence captionCs = getMessageCaption(mo);
                String caption = captionCs != null ? captionCs.toString() : null;
                boolean hasMessage = mo.messageOwner != null && !TextUtils.isEmpty(mo.messageOwner.message);

                if (mo.type == MessageObject.TYPE_TEXT || mo.isAnimatedEmoji()) {
                    sendTextMessage(mo, caption, targetDialogId);
                    continue;
                }
                
                long gid = mo.getGroupId();

                if (mo.isPhoto() || mo.isVideo() || mo.isGif()) {
                    if (!ensureDownloaded(mo)) continue;
                    
                    SendMessagesHelper.SendingMediaInfo info = createMediaInfo(mo, caption);
                    info.isVideo = mo.isVideo() || mo.isGif();
                    
                    if (gid != 0) {
                        addToGroup(gid, info, albumMap, albumRemain, false, targetDialogId);
                    } else {
                        singles.add(info);
                    }
                    continue;
                }

                if (mo.getDocument() != null && !mo.isSticker() && !mo.isAnimatedSticker()) {
                    if (!ensureDownloaded(mo)) continue;
                    
                    if (gid != 0) {
                        SendMessagesHelper.SendingMediaInfo info = createMediaInfo(mo, caption);
                        addToGroup(gid, info, docAlbumMap, albumRemain, true, targetDialogId);
                    } else {
                        String filePath = resolvePath(mo);
                        SendMessagesHelper.prepareSendingDocument(
                                parentFragment.getAccountInstance(),
                                filePath,
                                filePath,
                                null,
                                caption,
                                null,
                                targetDialogId,
                                null,
                                null,
                                null,
                                null,
                                null,
                                true,
                                0,
                                null,
                                parentFragment.quickReplyShortcut,
                                parentFragment.getQuickReplyId(),
                                false
                        );
                    }
                    continue;
                }

                if (mo.isSticker() || mo.isAnimatedSticker()) {
                    if (mo.getDocument() != null) {
                        parentFragment.getSendMessagesHelper().sendSticker(mo.getDocument(), null, targetDialogId, null, null, null, null, null, true, 0, 0, false, null, parentFragment.quickReplyShortcut, parentFragment.getQuickReplyId());
                    }
                    continue;
                }
                
                if (hasMessage) {
                     sendTextMessage(mo, null, targetDialogId);
                }
            }

            for (ArrayList<SendMessagesHelper.SendingMediaInfo> group : albumMap.values()) {
                sendMediaBatch(new ArrayList<>(group), targetDialogId, false, true);
            }
            for (ArrayList<SendMessagesHelper.SendingMediaInfo> group : docAlbumMap.values()) {
                sendMediaBatch(new ArrayList<>(group), targetDialogId, true, false);
            }

            for (SendMessagesHelper.SendingMediaInfo info : singles) {
                ArrayList<SendMessagesHelper.SendingMediaInfo> one = new ArrayList<>();
                one.add(info);
                sendMediaBatch(one, targetDialogId, false, false);
            }

        } catch (Exception e) {
            FileLog.e(e);
        }
    }
    
    private SendMessagesHelper.SendingMediaInfo createMediaInfo(MessageObject mo, String caption) {
        SendMessagesHelper.SendingMediaInfo info = new SendMessagesHelper.SendingMediaInfo();
        info.path = resolvePath(mo);
        info.caption = caption;
        info.entities = mo.messageOwner != null ? mo.messageOwner.entities : null;
        return info;
    }

    private void sendTextMessage(MessageObject mo, String captionOverride, long targetDialogId) {
        if (mo == null) return;
        
        String text = mo.messageOwner != null && !TextUtils.isEmpty(mo.messageOwner.message) 
                ? mo.messageOwner.message 
                : captionOverride;
                
        if (TextUtils.isEmpty(text)) return;
        
        ArrayList<TLRPC.MessageEntity> entities = mo.messageOwner != null && mo.messageOwner.entities != null && !mo.messageOwner.entities.isEmpty()
                ? mo.messageOwner.entities 
                : MediaDataController.getInstance(currentAccount).getEntities(new CharSequence[]{text}, true);
                
        SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(text, targetDialogId, null, null, null, true, entities, null, null, true, 0, 0, null, false);
        AndroidUtilities.runOnUIThread(() -> parentFragment.getSendMessagesHelper().sendMessage(params));
    }
}
