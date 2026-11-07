package tw.nekomimi.nekogram.helpers;

/**
 * Coupling between the "Hide Caption" toggle and a Show/Hide Sender Name pair
 * in forward/share menus, mirroring Telegram's own forward-preview behavior
 * (MessagePreviewView): a message may be shared with caption but without the
 * sender's name, but never without the caption while keeping the name.
 *
 * Surfaces keep a {@link State} per menu session and mirror its fields into
 * their own storage (instance fields for the share sheet, ChatActivity statics
 * for the forward-to screen).
 */
public final class ForwardCaptionRules {

    private ForwardCaptionRules() {
    }

    public static final class State {
        /** Sender name hidden ("Hide Sender Name" selected). */
        public boolean hideName;
        /** Original media caption hidden ("Hide Caption" checked). */
        public boolean hideCaption;
        /** The name was hidden automatically by checking Hide Caption. */
        public boolean captionAutoHidName;
    }

    /** Toggles Hide Caption; checking it also hides the name (remembering that it was automatic). */
    public static void toggleHideCaption(State s) {
        s.hideCaption = !s.hideCaption;
        if (s.hideCaption) {
            if (!s.hideName) {
                s.hideName = true;
                s.captionAutoHidName = true;
            }
        } else if (s.captionAutoHidName) {
            // Only a name hidden BY the caption toggle is restored when it is unchecked;
            // a manually hidden name stays hidden.
            s.hideName = false;
            s.captionAutoHidName = false;
        }
    }

    /**
     * The user picked one of the Show/Hide Sender Name entries.
     * Re-picking any name entry drops the auto-hide memory, and showing the
     * name force-clears Hide Caption (name without caption is not allowed).
     */
    public static void chooseHideName(State s, boolean hideName) {
        s.captionAutoHidName = false;
        s.hideName = hideName;
        if (!hideName) {
            s.hideCaption = false;
        }
    }
}
