package com.radolyn.ayugram.proprietary;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.List;

public class AyuHistoryHookMergeTest {
    @Test
    public void pendingMessageStaysOnTopWithoutInjectedMessages() {
        assertEquals(List.of(-5, 50, 40), merge(page(m(-5, 100), m(50, 90), m(40, 80)), page(), false));
    }

    @Test
    public void pendingMessageStaysOnTopWithInjectedMessages() {
        assertEquals(List.of(-5, 60, 50, 45, 40), merge(page(m(-5, 100), m(50, 90), m(40, 80)), page(m(45, 85), m(60, 95)), false));
    }

    @Test
    public void injectedMessageNewerThanPendingGoesAboveIt() {
        assertEquals(List.of(60, -5, 50), merge(page(m(-5, 100), m(50, 90)), page(m(60, 101)), false));
    }

    @Test
    public void pendingMessageStaysOnTopOfAnInjectedMessageWithTheSameDate() {
        assertEquals(List.of(-5, 60, 50), merge(page(m(-5, 100), m(50, 90)), page(m(60, 100)), false));
    }

    @Test
    public void injectedMessagesOutsideThePageGoToItsEdges() {
        assertEquals(List.of(70, 50, 40, 10), merge(page(m(50, 90), m(40, 80)), page(m(10, 1), m(70, 99)), false));
    }

    @Test
    public void severalPendingMessagesKeepTheirOrder() {
        assertEquals(List.of(-7, -6, 55, 50), merge(page(m(-7, 101), m(-6, 100), m(50, 90)), page(m(55, 92)), false));
    }

    @Test
    public void injectedAlbumIsSortedByIdDescending() {
        assertEquals(List.of(50, 45, 44, 43, 40), merge(page(m(50, 90), m(40, 80)), page(m(43, 85), m(45, 85), m(44, 85)), false));
    }

    @Test
    public void injectedMessageDoesNotJumpAheadOfANewerServerId() {
        // A server message with an older date than a local one, as after an import.
        assertEquals(List.of(-2, -1, 200, 100, 80), merge(page(m(-2, 40), m(-1, 20), m(200, 10), m(80, 5)), page(m(100, 30)), false));
    }

    @Test
    public void secretChatIsSortedByIdAscending() {
        assertEquals(List.of(-3, -2, -1), merge(page(m(-3, 10), m(-1, 30)), page(m(-2, 20)), true));
    }

    private static List<Integer> merge(ArrayList<TLRPC.Message> loaded, ArrayList<TLRPC.Message> injected, boolean encrypted) {
        AyuHistoryHook.mergeInjected(loaded, injected, encrypted);
        List<Integer> ids = new ArrayList<>();
        for (TLRPC.Message message : loaded) {
            ids.add(message.id);
        }
        return ids;
    }

    private static ArrayList<TLRPC.Message> page(TLRPC.Message... messages) {
        return new ArrayList<>(List.of(messages));
    }

    private static TLRPC.Message m(int id, int date) {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = id;
        message.date = date;
        return message;
    }
}
