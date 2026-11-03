package tw.nekomimi.nekogram.helpers;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import xyz.nextalone.nagram.NaConfig;

/**
 * The five send-menu visibility configs must exist and default to
 * ON so the long-press menu is unchanged out of the box.
 */
public class SendMenuConfigTest {

    @Test
    public void allFiveKeysAreRegistered() {
        assertTrue(NaConfig.INSTANCE.getAllKeys().contains("SendMenuWithoutSound"));
        assertTrue(NaConfig.INSTANCE.getAllKeys().contains("SendMenuWhenOnline"));
        assertTrue(NaConfig.INSTANCE.getAllKeys().contains("SendMenuSchedule"));
        assertTrue(NaConfig.INSTANCE.getAllKeys().contains("SendMenuTranslate"));
        assertTrue(NaConfig.INSTANCE.getAllKeys().contains("SendMenuLinkPreview"));
    }

    @Test
    public void allFiveDefaultToVisible() {
        assertTrue(NaConfig.INSTANCE.getSendMenuWithoutSound().Bool());
        assertTrue(NaConfig.INSTANCE.getSendMenuWhenOnline().Bool());
        assertTrue(NaConfig.INSTANCE.getSendMenuSchedule().Bool());
        assertTrue(NaConfig.INSTANCE.getSendMenuTranslate().Bool());
        assertTrue(NaConfig.INSTANCE.getSendMenuLinkPreview().Bool());
    }
}
