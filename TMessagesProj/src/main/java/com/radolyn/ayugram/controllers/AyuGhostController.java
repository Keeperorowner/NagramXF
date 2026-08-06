package com.radolyn.ayugram.controllers;

import com.radolyn.ayugram.AyuGhostConfig;

import org.telegram.messenger.BaseController;
import org.telegram.messenger.UserConfig;

public class AyuGhostController extends BaseController {

    private static volatile AyuGhostController[] Instance = new AyuGhostController[UserConfig.MAX_ACCOUNT_COUNT];
    private static final Object[] lockObjects = new Object[UserConfig.MAX_ACCOUNT_COUNT];

    static {
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            lockObjects[i] = new Object();
        }
    }

    public static AyuGhostController getInstance(int num) {
        AyuGhostController localInstance = Instance[num];
        if (localInstance == null) {
            synchronized (lockObjects[num]) {
                localInstance = Instance[num];
                if (localInstance == null) {
                    Instance[num] = localInstance = new AyuGhostController(num);
                }
            }
        }
        return localInstance;
    }

    private AyuGhostController(int account) {
        super(account);
    }

    public boolean isGhostModeActive() {
        return AyuGhostConfig.isGhostModeActive(currentAccount);
    }

    public void setGhostMode(boolean enabled) {
        AyuGhostConfig.setGhostMode(currentAccount, enabled);
    }

    public void toggleGhostMode() {
        AyuGhostConfig.toggleGhostMode(currentAccount);
    }

    public void applySideEffects(boolean enabled) {
        AyuGhostConfig.applyGhostModeSideEffects(currentAccount, enabled);
    }

    public boolean isSendReadMessagePackets() {
        return AyuGhostConfig.isSendReadMessagePackets(currentAccount);
    }

    public void setSendReadMessagePackets(boolean v) {
        AyuGhostConfig.setSendReadMessagePackets(currentAccount, v);
    }

    public boolean isSendReadStoriesPackets() {
        return AyuGhostConfig.isSendReadStoriesPackets(currentAccount);
    }

    public void setSendReadStoriesPackets(boolean v) {
        AyuGhostConfig.setSendReadStoriesPackets(currentAccount, v);
    }

    public boolean isSendOnlinePackets() {
        return AyuGhostConfig.isSendOnlinePackets(currentAccount);
    }

    public void setSendOnlinePackets(boolean v) {
        AyuGhostConfig.setSendOnlinePackets(currentAccount, v);
    }

    public boolean isSendUploadProgress() {
        return AyuGhostConfig.isSendUploadProgress(currentAccount);
    }

    public void setSendUploadProgress(boolean v) {
        AyuGhostConfig.setSendUploadProgress(currentAccount, v);
    }

    public boolean isSendOfflinePacketAfterOnline() {
        return AyuGhostConfig.isSendOfflinePacketAfterOnline(currentAccount);
    }

    public void setSendOfflinePacketAfterOnline(boolean v) {
        AyuGhostConfig.setSendOfflinePacketAfterOnline(currentAccount, v);
    }

    public boolean isMarkReadAfterSend() {
        return AyuGhostConfig.isMarkReadAfterSend(currentAccount);
    }

    public void setMarkReadAfterSend(boolean v) {
        AyuGhostConfig.setMarkReadAfterSend(currentAccount, v);
    }

    public boolean isUseScheduledMessages() {
        return AyuGhostConfig.isUseScheduledMessages(currentAccount);
    }

    public void setUseScheduledMessages(boolean v) {
        AyuGhostConfig.setUseScheduledMessages(currentAccount, v);
    }

    public boolean isSendWithoutSound() {
        return AyuGhostConfig.isSendWithoutSound(currentAccount);
    }

    public int getSendWithoutSoundState() {
        return AyuGhostConfig.getSendWithoutSoundState(currentAccount);
    }

    public void setSendWithoutSoundState(int state) {
        AyuGhostConfig.setSendWithoutSoundState(currentAccount, state);
    }

    public boolean isSuggestGhostModeBeforeViewingStory() {
        return AyuGhostConfig.isSuggestGhostModeBeforeViewingStory(currentAccount);
    }

    public void setSuggestGhostModeBeforeViewingStory(boolean v) {
        AyuGhostConfig.setSuggestGhostModeBeforeViewingStory(currentAccount, v);
    }

    public boolean isSendReadMessagePacketsLocked() {
        return AyuGhostConfig.isSendReadMessagePacketsLocked(currentAccount);
    }

    public void setSendReadMessagePacketsLocked(boolean v) {
        AyuGhostConfig.setSendReadMessagePacketsLocked(currentAccount, v);
    }

    public boolean isSendReadStoriesPacketsLocked() {
        return AyuGhostConfig.isSendReadStoriesPacketsLocked(currentAccount);
    }

    public void setSendReadStoriesPacketsLocked(boolean v) {
        AyuGhostConfig.setSendReadStoriesPacketsLocked(currentAccount, v);
    }

    public boolean isSendOnlinePacketsLocked() {
        return AyuGhostConfig.isSendOnlinePacketsLocked(currentAccount);
    }

    public void setSendOnlinePacketsLocked(boolean v) {
        AyuGhostConfig.setSendOnlinePacketsLocked(currentAccount, v);
    }

    public boolean isSendUploadProgressLocked() {
        return AyuGhostConfig.isSendUploadProgressLocked(currentAccount);
    }

    public void setSendUploadProgressLocked(boolean v) {
        AyuGhostConfig.setSendUploadProgressLocked(currentAccount, v);
    }

    public boolean isSendOfflinePacketAfterOnlineLocked() {
        return AyuGhostConfig.isSendOfflinePacketAfterOnlineLocked(currentAccount);
    }

    public void setSendOfflinePacketAfterOnlineLocked(boolean v) {
        AyuGhostConfig.setSendOfflinePacketAfterOnlineLocked(currentAccount, v);
    }
}
