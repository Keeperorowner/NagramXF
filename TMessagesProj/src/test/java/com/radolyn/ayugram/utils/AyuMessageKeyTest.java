package com.radolyn.ayugram.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class AyuMessageKeyTest {
    @Test
    public void keysWithSameDialogAndMessageAreEqual() {
        assertEquals(new AyuMessageKey(-1001234567890L, 77), new AyuMessageKey(-1001234567890L, 77));
        assertEquals(new AyuMessageKey(-1001234567890L, 77).hashCode(), new AyuMessageKey(-1001234567890L, 77).hashCode());
    }

    @Test
    public void sameMessageIdInDifferentDialogsDoesNotCollide() {
        AyuMessageKey first = new AyuMessageKey(-1001234567890L, 5);
        AyuMessageKey second = new AyuMessageKey(-1009876543210L, 5);

        assertNotEquals(first, second);
        Map<AyuMessageKey, String> map = new HashMap<>();
        map.put(first, "first");
        map.put(second, "second");
        assertEquals(2, map.size());
        assertEquals("first", map.get(first));
        assertEquals("second", map.get(second));
    }

    @Test
    public void negativeAndPositiveDialogsStayDistinct() {
        AyuMessageKey user = new AyuMessageKey(42L, 5);
        AyuMessageKey encrypted = new AyuMessageKey(-210000L, 5);

        assertNotEquals(user, encrypted);
        assertFalse(user.equals(encrypted));
        assertTrue(user.equals(new AyuMessageKey(42L, 5)));
    }
}
