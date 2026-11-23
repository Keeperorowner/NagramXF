package com.radolyn.ayugram;

/**
 * Entry point that plugins use to coordinate with the ghost mode state.
 * It forwards to the single state holder so plugins and the app share one source of truth.
 */
public final class AyuState {

    private AyuState() {
    }

    /**
     * Marks a message as deletable without saving a local copy, which is what plugins call
     * right before they delete a message on behalf of the user.
     */
    public static void permitDeleteMessage(long dialogId, int messageId) {
        com.radolyn.ayugram.utils.AyuState.permitDeleteMessage(dialogId, messageId);
    }

    public static boolean getAllowDeleteDialogs() {
        return com.radolyn.ayugram.utils.AyuState.getAllowDeleteDialogs();
    }

    public static void setAllowDeleteDialogs(boolean value, int account) {
        com.radolyn.ayugram.utils.AyuState.setAllowDeleteDialogs(value, account);
    }

    public static boolean getAutomaticallyScheduled() {
        return com.radolyn.ayugram.utils.AyuState.getAutomaticallyScheduled();
    }

    public static void setAutomaticallyScheduled(boolean value, int account) {
        com.radolyn.ayugram.utils.AyuState.setAutomaticallyScheduled(value, account);
    }

    public static boolean isMessageBurned(int account, long dialogId, int messageId) {
        return com.radolyn.ayugram.utils.AyuState.isMessageBurned(account, dialogId, messageId);
    }

    public static void setMessageBurned(int account, long dialogId, int messageId) {
        com.radolyn.ayugram.utils.AyuState.setMessageBurned(account, dialogId, messageId);
    }

    public static void resetGhostMode() {
        com.radolyn.ayugram.utils.AyuState.resetGhostMode();
    }
}
