package tw.nekomimi.nekogram.helpers;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_iv;

import java.util.ArrayList;

public class TranslationEntityHandlingTest {

    @Test
    public void nullEntitiesProduceNoStyleRuns() {
        assertTrue(MediaDataController.getTextStyleRuns(null, "message", -1).isEmpty());
    }

    @Test
    public void translatedRichMessageWithNoEntitiesProducesNoStyleRuns() {
        MessageObject messageObject = createTranslatedRichMessage("original", null);
        ArrayList<TLRPC.MessageEntity> entities = MessageHelper.getEntitiesForText(
            messageObject,
            new StringBuilder("original"),
            false
        );

        assertNull(entities);
        assertTrue(MediaDataController.getTextStyleRuns(entities, "original", -1).isEmpty());
    }

    @Test
    public void translatedRichMessageKeepsEntitiesForEquivalentOriginalText() {
        ArrayList<TLRPC.MessageEntity> entities = new ArrayList<>();
        TLRPC.TL_messageEntityBold bold = new TLRPC.TL_messageEntityBold();
        bold.offset = 0;
        bold.length = 8;
        entities.add(bold);
        MessageObject messageObject = createTranslatedRichMessage("original", entities);

        assertSame(
            entities,
            MessageHelper.getEntitiesForText(messageObject, new StringBuilder("original"), false)
        );
    }

    @Test
    public void translatedRichMessageDoesNotReuseEntitiesForDifferentText() {
        ArrayList<TLRPC.MessageEntity> entities = new ArrayList<>();
        entities.add(new TLRPC.TL_messageEntityBold());
        MessageObject messageObject = createTranslatedRichMessage("original", entities);

        assertNull(MessageHelper.getEntitiesForText(messageObject, "different", false));
    }

    private static MessageObject createTranslatedRichMessage(
        String originalText,
        ArrayList<TLRPC.MessageEntity> entities
    ) {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.message = originalText;
        message.entities = entities;
        message.translatedRichMessage = new TL_iv.RichMessage();

        MessageObject messageObject = new MessageObject(
            0,
            message,
            originalText,
            null,
            null,
            false,
            false,
            false,
            false
        );
        messageObject.translated = true;
        return messageObject;
    }
}
