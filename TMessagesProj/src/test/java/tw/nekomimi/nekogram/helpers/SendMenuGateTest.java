package tw.nekomimi.nekogram.helpers;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Gates for the five long-press send-button menu entries.
 * Each gate multiplies the user's visibility config with the
 * context condition the stock menu already applied.
 */
public class SendMenuGateTest {

    // Schedule message

    @Test
    public void scheduleShownWhenEnabledAndSchedulingAllowed() {
        assertTrue(SendMenuGate.showSchedule(true, true));
    }

    @Test
    public void scheduleHiddenWhenConfigDisabled() {
        assertFalse(SendMenuGate.showSchedule(false, true));
    }

    @Test
    public void scheduleHiddenWhenSchedulingNotAllowed() {
        assertFalse(SendMenuGate.showSchedule(true, false));
    }

    // Send when online (stock: requires scheduling capability, non-self chat, positive dialog id)

    @Test
    public void whenOnlineShownWhenAllConditionsMet() {
        assertTrue(SendMenuGate.showWhenOnline(true, true, false, true, true));
    }

    @Test
    public void whenOnlineHiddenWhenConfigDisabled() {
        assertFalse(SendMenuGate.showWhenOnline(false, true, false, true, true));
    }

    @Test
    public void whenOnlineHiddenWhenSchedulingNotAllowed() {
        assertFalse(SendMenuGate.showWhenOnline(true, false, false, true, true));
    }

    @Test
    public void whenOnlineHiddenInSelfChat() {
        assertFalse(SendMenuGate.showWhenOnline(true, true, true, true, true));
    }

    @Test
    public void whenOnlineHiddenForNonPositiveDialog() {
        assertFalse(SendMenuGate.showWhenOnline(true, true, false, false, true));
    }

    @Test
    public void whenOnlineHiddenWhenRecipientIsOnline() {
        // The row is only added when the recipient is away; if they are online
        // the row would be added and immediately hidden, defeating the
        // empty-menu guard.
        assertFalse(SendMenuGate.showWhenOnline(true, true, false, true, false));
    }

    // Send without sound (stock: hidden in self chat and during slow mode)

    @Test
    public void withoutSoundShownWhenEnabledAndEligible() {
        assertTrue(SendMenuGate.showWithoutSound(true, true));
    }

    @Test
    public void withoutSoundHiddenWhenConfigDisabled() {
        assertFalse(SendMenuGate.showWithoutSound(false, true));
    }

    @Test
    public void withoutSoundHiddenWhenNotEligible() {
        assertFalse(SendMenuGate.showWithoutSound(true, false));
    }

    // Translate message (stock: needs non-empty text)

    @Test
    public void translateShownWhenEnabledAndTextPresent() {
        assertTrue(SendMenuGate.showTranslate(true, true));
    }

    @Test
    public void translateHiddenWhenConfigDisabled() {
        assertFalse(SendMenuGate.showTranslate(false, true));
    }

    @Test
    public void translateHiddenWithoutText() {
        assertFalse(SendMenuGate.showTranslate(true, false));
    }

    // Link preview toggle (stock: only when the delegate reports preview handling > 0)

    @Test
    public void linkPreviewShownWhenEnabledAndStatusAvailable() {
        assertTrue(SendMenuGate.showLinkPreviewToggle(true, 1));
        assertTrue(SendMenuGate.showLinkPreviewToggle(true, 2));
    }

    @Test
    public void linkPreviewHiddenWhenConfigDisabled() {
        assertFalse(SendMenuGate.showLinkPreviewToggle(false, 1));
    }

    @Test
    public void linkPreviewHiddenWhenStatusUnavailable() {
        assertFalse(SendMenuGate.showLinkPreviewToggle(true, 0));
    }
}
