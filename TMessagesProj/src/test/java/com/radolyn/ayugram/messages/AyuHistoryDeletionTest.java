package com.radolyn.ayugram.messages;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;

public class AyuHistoryDeletionTest {
    @Test
    public void onlyMessagesInsideTheCapturedRangeAreAffected() {
        AyuHistoryDeletion deletion = new AyuHistoryDeletion(-100L, 1000, 10, 30,
                Arrays.asList(10, 20, 30), new ArrayList<>(), true);

        assertTrue(deletion.affects(10));
        assertTrue(deletion.affects(20));
        assertTrue(deletion.affects(30));
        assertFalse(deletion.affects(9));
        assertFalse(deletion.affects(31));
        assertFalse(deletion.affects(0));
    }

    @Test
    public void negativeRangeCoversEncryptedClear() {
        AyuHistoryDeletion deletion = new AyuHistoryDeletion(-210000L, 1000, -1010, -1000,
                Arrays.asList(-1010, -1005, -1000), new ArrayList<>(), true);

        assertTrue(deletion.affects(-1010));
        assertTrue(deletion.affects(-1005));
        assertTrue(deletion.affects(-1000));
        assertTrue(deletion.affects(-1009));
        assertFalse(deletion.affects(-999));
    }

    @Test
    public void savedIdsMustAlsoBeInsideTheRange() {
        AyuHistoryDeletion deletion = new AyuHistoryDeletion(-100L, 1000, 10, 20,
                Arrays.asList(10, 15), Arrays.asList(10, 15, 99), true);

        assertTrue(deletion.isSaved(10));
        assertTrue(deletion.isSaved(15));
        assertFalse(deletion.isSaved(99));
        assertFalse(deletion.isSaved(12));
    }

    @Test
    public void notCapturedDeletionNeverAffectsOrSaves() {
        AyuHistoryDeletion deletion = new AyuHistoryDeletion(-100L, 1000, 1, 50,
                Arrays.asList(1, 2), Arrays.asList(1, 2), false);

        assertFalse(deletion.affects(1));
        assertFalse(deletion.isSaved(1));
    }

    @Test
    public void capturedMessageIdsAreImmutable() {
        AyuHistoryDeletion deletion = new AyuHistoryDeletion(-100L, 1000, 1, 50,
                Arrays.asList(1, 2), new ArrayList<>(), true);

        try {
            deletion.messageIds.add(3);
            fail("messageIds must be immutable");
        } catch (UnsupportedOperationException expected) {
        }
    }
}
