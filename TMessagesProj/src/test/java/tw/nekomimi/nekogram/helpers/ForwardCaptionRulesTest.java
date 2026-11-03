package tw.nekomimi.nekogram.helpers;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Coupling rules for the Hide Caption toggle next to a Show/Hide Sender Name
 * pair, mirroring Telegram's own forward-preview behavior:
 *
 * - checking Hide Caption also hides the sender name (remembering that the
 *   hide was automatic),
 * - unchecking Hide Caption restores the name only if it was auto-hidden,
 * - choosing "Show Sender Name" force-clears Hide Caption
 *   (caption without name is allowed; name without caption is not),
 * - re-choosing the name entry drops the auto-hide memory.
 */
public class ForwardCaptionRulesTest {

    @Test
    public void initialStateShowsNameAndCaption() {
        ForwardCaptionRules.State s = new ForwardCaptionRules.State();
        assertFalse(s.hideName);
        assertFalse(s.hideCaption);
        assertFalse(s.captionAutoHidName);
    }

    @Test
    public void checkingCaptionAlsoHidesName() {
        ForwardCaptionRules.State s = new ForwardCaptionRules.State();
        ForwardCaptionRules.toggleHideCaption(s);
        assertTrue(s.hideCaption);
        assertTrue(s.hideName);
        assertTrue(s.captionAutoHidName);
    }

    @Test
    public void uncheckingCaptionRestoresAutoHiddenName() {
        ForwardCaptionRules.State s = new ForwardCaptionRules.State();
        ForwardCaptionRules.toggleHideCaption(s);
        ForwardCaptionRules.toggleHideCaption(s);
        assertFalse(s.hideCaption);
        assertFalse(s.hideName);
        assertFalse(s.captionAutoHidName);
    }

    @Test
    public void manuallyHiddenNameStaysHiddenWhenCaptionTurnedOff() {
        ForwardCaptionRules.State s = new ForwardCaptionRules.State();
        // user picked Hide Sender Name first
        ForwardCaptionRules.chooseHideName(s, true);
        // then checked Hide Caption (name already hidden -> no auto-hide memory)
        ForwardCaptionRules.toggleHideCaption(s);
        assertTrue(s.hideCaption);
        assertTrue(s.hideName);
        assertFalse(s.captionAutoHidName);
        // then unchecked it: name must stay hidden (it was hidden manually)
        ForwardCaptionRules.toggleHideCaption(s);
        assertFalse(s.hideCaption);
        assertTrue(s.hideName);
    }

    @Test
    public void showingNameForceClearsHiddenCaption() {
        ForwardCaptionRules.State s = new ForwardCaptionRules.State();
        ForwardCaptionRules.toggleHideCaption(s); // caption + name hidden
        ForwardCaptionRules.chooseHideName(s, false); // user picks Show Sender Name
        assertFalse(s.hideName);
        assertFalse(s.hideCaption);
        assertFalse(s.captionAutoHidName);
    }

    @Test
    public void reChoosingHiddenNameDropsAutoHideMemory() {
        ForwardCaptionRules.State s = new ForwardCaptionRules.State();
        ForwardCaptionRules.toggleHideCaption(s); // auto-hid the name
        ForwardCaptionRules.chooseHideName(s, true); // user re-confirms Hide Sender Name
        assertFalse(s.captionAutoHidName);
        // unchecking caption now must NOT flip the name back to shown
        ForwardCaptionRules.toggleHideCaption(s);
        assertFalse(s.hideCaption);
        assertTrue(s.hideName);
    }

    @Test
    public void captionStaysHiddenAcrossNameReconfirmation() {
        ForwardCaptionRules.State s = new ForwardCaptionRules.State();
        ForwardCaptionRules.chooseHideName(s, true); // manual hide first
        ForwardCaptionRules.toggleHideCaption(s); // caption on
        // choosing Hide Sender Name again does not clear the caption
        ForwardCaptionRules.chooseHideName(s, true);
        assertTrue(s.hideCaption);
        assertTrue(s.hideName);
    }
}
