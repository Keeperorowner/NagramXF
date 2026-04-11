package com.radolyn.ayugram.controllers;

import android.text.TextUtils;

import org.telegram.messenger.MessageObject;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;

public class AyuMapper {
    private final int currentAccount;

    public AyuMapper(int currentAccount) {
        this.currentAccount = currentAccount;
    }

    public int getMessageTtl(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null || messageObject.messageOwner.media == null) {
            return 0;
        }
        return messageObject.messageOwner.media.ttl_seconds;
    }

    public TLRPC.TL_photo mapPhoto(MessageObject messageObject, String filePath) {
        if (messageObject == null || messageObject.messageOwner == null || TextUtils.isEmpty(filePath)) {
            return null;
        }
        TLRPC.Photo source = MessageObject.getPhoto(messageObject.messageOwner);
        if (source == null) {
            return null;
        }
        TLRPC.TL_photo mapped = new TLRPC.TL_photo();
        mapped.flags = source.flags;
        mapped.has_stickers = source.has_stickers;
        mapped.date = (int) (System.currentTimeMillis() / 1000);
        mapped.dc_id = Integer.MIN_VALUE;
        mapped.user_id = source.user_id;
        mapped.geo = source.geo;
        mapped.caption = source.caption;
        mapped.video_sizes = new ArrayList<>(source.video_sizes);
        mapped = SendMessagesHelper.getInstance(currentAccount).generatePhotoSizes(mapped, filePath, null, false);
        if (mapped == null || mapped.sizes == null || mapped.sizes.isEmpty()) {
            return null;
        }
        mapped.id = 0;
        mapped.access_hash = 0;
        mapped.file_reference = new byte[0];
        return mapped;
    }

    public TLRPC.TL_document mapDocument(MessageObject messageObject, String localPath) {
        TLRPC.Document source = messageObject != null ? messageObject.getDocument() : null;
        if (source == null) {
            return null;
        }
        boolean hasLocalFile = !TextUtils.isEmpty(localPath) && new File(localPath).exists();

        TLRPC.TL_document mapped = new TLRPC.TL_document();
        mapped.flags = source.flags;
        mapped.id = 0;
        mapped.access_hash = 0;
        mapped.file_reference = new byte[0];
        mapped.user_id = source.user_id;
        mapped.date = (int) (System.currentTimeMillis() / 1000);
        mapped.file_name = source.file_name;
        mapped.mime_type = source.mime_type;
        mapped.size = hasLocalFile ? (int) new File(localPath).length() : source.size;
        mapped.thumbs = new ArrayList<>(source.thumbs);
        mapped.video_thumbs = new ArrayList<>(source.video_thumbs);
        mapped.version = source.version;
        mapped.dc_id = hasLocalFile ? source.dc_id : Integer.MIN_VALUE;
        mapped.key = null;
        mapped.iv = null;
        mapped.attributes = new ArrayList<>(source.attributes);
        mapped.file_name_fixed = source.file_name_fixed;
        mapped.localPath = hasLocalFile ? localPath : source.localPath;
        mapped.localThumbPath = source.localThumbPath;
        if (messageObject != null && messageObject.isGif()) {
            mapped.mime_type = "video/mp4";
        }
        return mapped;
    }

    public HashMap<String, String> createGroupedParams(long groupId, boolean isFinal) {
        HashMap<String, String> params = new HashMap<>();
        if (groupId != 0) {
            params.put("groupId", String.valueOf(groupId));
        }
        if (isFinal) {
            params.put("final", "1");
        }
        return params.isEmpty() ? null : params;
    }
}
