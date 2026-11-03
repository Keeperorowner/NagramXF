package tw.nekomimi.nekogram.helpers;

/**
 * Visibility gates for the five configurable entries of the long-press
 * send-button menu. Each gate combines the user's visibility config
 * (the "Send Menu" toggles in N-Settings → Chats → Menu and Buttons)
 * with the context condition the stock menu already applied, so that
 * hiding an entry never changes the conditions of the others.
 */
public final class SendMenuGate {

    private SendMenuGate() {
    }

    /** Schedule message: stock shows it only when the chat supports scheduling. */
    public static boolean showSchedule(boolean configEnabled, boolean canSchedule) {
        return configEnabled && canSchedule;
    }

    /**
     * Send when online: stock nests it under scheduling support and only
     * shows it in non-self chats with a positive dialog id; the row is only
     * added when the recipient is away (if they are already online the row
     * would be added and immediately hidden, defeating the empty-menu guard).
     */
    public static boolean showWhenOnline(boolean configEnabled, boolean canSchedule,
                                         boolean isSelfChat, boolean positiveDialogId,
                                         boolean recipientAway) {
        return configEnabled && canSchedule && !isSelfChat && positiveDialogId && recipientAway;
    }

    /** Send without sound: stock hides it in self chats and during slow mode. */
    public static boolean showWithoutSound(boolean configEnabled, boolean eligible) {
        return configEnabled && eligible;
    }

    /** Translate message: stock only offers it when the input has text. */
    public static boolean showTranslate(boolean configEnabled, boolean hasText) {
        return configEnabled && hasText;
    }

    /** Link preview toggle: stock only offers it when the chat reports preview handling (&gt; 0). */
    public static boolean showLinkPreviewToggle(boolean configEnabled, int previewStatus) {
        return configEnabled && previewStatus > 0;
    }
}
