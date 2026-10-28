package com.radolyn.ayugram;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class AyuSaveExclusionsTest {
    @Test
    public void legacyIdMatchesBothUserAndChatDialogs() {
        Map<String, Object> preferences = new HashMap<>();
        preferences.put("saveDeletedExclusion_1001234567890", true);
        preferences.put("saveDeletedExclusion_777", true);

        assertEquals(Set.of("1001234567890", "-1001234567890", "777", "-777"), migrate(preferences, Set.of(), Set.of(), false));
    }

    @Test
    public void disabledAndUnrelatedEntriesAreSkipped() {
        Map<String, Object> preferences = new HashMap<>();
        preferences.put("saveDeletedExclusion_5", false);
        preferences.put("saveDeletedExclusion_6", "true");
        preferences.put("saveDeletedExclusion_", true);
        preferences.put("saveDeletedExclusion_abc", true);
        preferences.put("saveDeletedExclusion_99999999999999999999", true);
        preferences.put("ghostReadExclusion_7", true);

        assertEquals(Set.of(), migrate(preferences, Set.of(), Set.of(), false));
    }

    @Test
    public void repairKeepsRulesChangedAfterTheFirstMigration() {
        Map<String, Object> preferences = new HashMap<>();
        preferences.put("saveDeletedExclusion_1", true); // still excluded
        preferences.put("saveDeletedExclusion_2", true); // removed or reset since
        preferences.put("saveDeletedExclusion_3", true); // its chat moved to always

        assertEquals(Set.of("1", "-1", "3"), migrate(preferences, Set.of("1", "3"), Set.of("-3"), true));
    }

    private static Set<String> migrate(Map<String, Object> preferences, Set<String> never, Set<String> always, boolean repair) {
        Set<String> result = new HashSet<>(never);
        AyuSaveExclusions.addLegacyIds(preferences, result, always, repair);
        return result;
    }
}
