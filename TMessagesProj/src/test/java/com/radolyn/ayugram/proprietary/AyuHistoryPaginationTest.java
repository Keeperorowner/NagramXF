package com.radolyn.ayugram.proprietary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.IntStream;

public class AyuHistoryPaginationTest {
    @Test
    public void clearedHistoryLoadsAllThousandMessagesWithoutRepeatingPages() {
        List<Integer> deleted = ids(1, 1000);
        Set<Integer> seen = new HashSet<>();
        int anchor = 0;

        for (int request = 0; request < 20; request++) {
            AyuHistoryPagination.Page<Integer> page = load(AyuHistoryPagination.Direction.BACKWARD, anchor, deleted);
            assertFalse(page.messages.isEmpty());
            assertTrue(page.messages.size() <= 120);
            assertDescending(page.messages);
            for (int id : page.messages) {
                assertTrue("Message repeated: " + id, seen.add(id));
                assertTrue(anchor == 0 || id < anchor);
            }
            anchor = page.messages.get(page.messages.size() - 1);
            if (!page.hasMoreOlder) {
                break;
            }
        }

        assertEquals(new HashSet<>(deleted), seen);
        assertTrue(load(AyuHistoryPagination.Direction.BACKWARD, anchor, deleted).messages.isEmpty());
    }

    @Test
    public void olderLiveMessageDoesNotSkipAThousandMessageDeletedGap() {
        List<Integer> deleted = ids(2, 1001);
        Set<Integer> seen = new HashSet<>();
        int anchor = 0;

        for (int request = 0; request < 20; request++) {
            AyuHistoryPagination.Page<Integer> page = load(AyuHistoryPagination.Direction.BACKWARD, anchor, deleted);
            List<Integer> server = anchor == 0 ? List.of(1002, 1) : List.of(1);
            if (page.hasMoreOlder) {
                assertFalse(page.contains(1));
            }
            ArrayList<Integer> merged = merge(server, page);
            for (int id : merged) {
                assertTrue("Message repeated: " + id, seen.add(id));
            }
            anchor = merged.get(merged.size() - 1);
            if (!page.hasMoreOlder) {
                break;
            }
        }

        assertEquals(new HashSet<>(ids(1, 1002)), seen);
    }

    @Test
    public void forwardPagesKeepNewerLiveMessagesBehindTheDeletedGap() {
        List<Integer> deleted = ids(2, 1001);
        Set<Integer> seen = new HashSet<>();
        seen.add(1);
        int anchor = 1;

        for (int request = 0; request < 20; request++) {
            AyuHistoryPagination.Page<Integer> page = load(AyuHistoryPagination.Direction.FORWARD, anchor, deleted);
            assertDescending(page.messages);
            if (page.hasMoreNewer) {
                assertFalse(page.contains(1002));
            }
            ArrayList<Integer> merged = merge(List.of(1002), page);
            for (int id : merged) {
                assertTrue(id > anchor);
                assertTrue("Message repeated: " + id, seen.add(id));
            }
            anchor = merged.get(0);
            if (!page.hasMoreNewer) {
                break;
            }
        }

        assertEquals(new HashSet<>(ids(1, 1002)), seen);
    }

    @Test
    public void jumpingToDeletedMessageKeepsBothPagingDirections() {
        AyuHistoryPagination.Page<Integer> page = load(AyuHistoryPagination.Direction.AROUND, 500, ids(1, 1000));

        assertEquals(ids(441, 560), page.messages.reversed());
        assertEquals(500, page.anchorId);
        assertTrue(page.hasMoreOlder);
        assertTrue(page.hasMoreNewer);
        assertTrue(page.contains(500));
        assertFalse(page.contains(440));
        assertFalse(page.contains(561));
    }

    @Test
    public void unavailableMessagesDoNotStopAtAnEmptyDatabaseBatch() {
        List<Integer> deleted = ids(1, 600);
        AtomicInteger requests = new AtomicInteger();
        AyuHistoryPagination.Source<Integer> source = (min, max, limit, ascending) -> {
            requests.incrementAndGet();
            return source(deleted).load(min, max, limit, ascending);
        };
        AyuHistoryPagination.Page<Integer> page = AyuHistoryPagination.load(
                AyuHistoryPagination.Direction.BACKWARD, 0, 1, Integer.MAX_VALUE, 50,
                source, id -> id, id -> id <= 150);

        assertTrue(requests.get() > 1);
        assertEquals(ids(31, 150), page.messages.reversed());
        assertTrue(page.hasMoreOlder);
    }

    @Test
    public void allUnavailableMessagesReachTheEnd() {
        AyuHistoryPagination.Page<Integer> page = AyuHistoryPagination.load(
                AyuHistoryPagination.Direction.BACKWARD, 0, 1, Integer.MAX_VALUE, 50,
                source(ids(1, 600)), id -> id, id -> false);

        assertTrue(page.messages.isEmpty());
        assertFalse(page.hasMoreOlder);
        assertFalse(page.hasMoreNewer);
    }

    @Test
    public void duplicateRowsDoNotUseUpTheVisiblePage() {
        ArrayList<Integer> deleted = new ArrayList<>(ids(1, 240));
        deleted.addAll(ids(1, 240));
        AyuHistoryPagination.Page<Integer> page = load(AyuHistoryPagination.Direction.BACKWARD, 0, deleted);

        assertEquals(ids(121, 240), page.messages.reversed());
        assertTrue(page.hasMoreOlder);
    }

    @Test
    public void databaseQueriesStayInsideTheCoveredHistoryWindow() {
        AyuHistoryPagination.Page<Integer> page = AyuHistoryPagination.load(
                AyuHistoryPagination.Direction.AROUND, 500, 495, 505, 50,
                source(ids(1, 1000)), id -> id, id -> true);

        assertEquals(ids(495, 505), page.messages.reversed());
        assertFalse(page.hasMoreOlder);
        assertFalse(page.hasMoreNewer);
    }

    @Test
    public void integerBoundaryCursorsCannotWrapOrReloadTheWholeHistory() {
        AyuHistoryPagination.Source<Integer> unexpectedQuery = (min, max, limit, ascending) -> {
            throw new AssertionError("No history can exist beyond this cursor");
        };
        Predicate<Integer> visible = id -> true;
        assertTrue(AyuHistoryPagination.load(AyuHistoryPagination.Direction.FORWARD,
                Integer.MAX_VALUE, 1, Integer.MAX_VALUE, 50, unexpectedQuery, id -> id, visible).messages.isEmpty());
        assertTrue(AyuHistoryPagination.load(AyuHistoryPagination.Direction.FORWARD,
                0, 1, Integer.MAX_VALUE, 50, unexpectedQuery, id -> id, visible).messages.isEmpty());
        assertTrue(AyuHistoryPagination.load(AyuHistoryPagination.Direction.BACKWARD,
                1, 1, Integer.MAX_VALUE, 50, unexpectedQuery, id -> id, visible).messages.isEmpty());

        AyuHistoryPagination.Page<Integer> last = load(AyuHistoryPagination.Direction.AROUND,
                Integer.MAX_VALUE, List.of(Integer.MAX_VALUE - 1, Integer.MAX_VALUE));
        assertEquals(List.of(Integer.MAX_VALUE, Integer.MAX_VALUE - 1), last.messages);
        assertFalse(last.hasMoreNewer);
    }

    @Test
    public void albumCompletionCannotJumpOutsideTheCurrentPage() {
        AyuHistoryPagination.Page<Integer> older = load(AyuHistoryPagination.Direction.BACKWARD, 0, ids(1, 1000));
        assertTrue(older.contains(881));
        assertFalse(older.contains(880));
        assertTrue(older.contains(-1));

        AyuHistoryPagination.Page<Integer> next = load(AyuHistoryPagination.Direction.BACKWARD, 881, ids(1, 1000));
        assertTrue(next.contains(880));
        assertFalse(next.contains(881));
    }

    @Test
    public void encryptedClearedHistoryPaginatesThroughEveryNegativeId() {
        List<Integer> deleted = idsRange(-1001, -1);
        Set<Integer> seen = new HashSet<>();
        int anchor = 0;

        for (int request = 0; request < 30; request++) {
            AyuHistoryPagination.Page<Integer> page = AyuHistoryPagination.loadEncrypted(
                    AyuHistoryPagination.Direction.BACKWARD, anchor, Integer.MIN_VALUE, -1, 50,
                    source(deleted), id -> id, id -> true);
            assertFalse(page.messages.isEmpty());
            assertTrue(page.messages.size() <= AyuHistoryPagination.PAGE_SIZE);
            for (int id : page.messages) {
                assertTrue("Message repeated: " + id, seen.add(id));
                assertTrue(anchor == 0 || id > anchor);
            }
            anchor = page.messages.get(page.messages.size() - 1);
            if (!page.hasMoreOlder) {
                break;
            }
        }

        assertEquals(new HashSet<>(deleted), seen);
        assertTrue(AyuHistoryPagination.loadEncrypted(AyuHistoryPagination.Direction.BACKWARD,
                anchor, Integer.MIN_VALUE, -1, 50, source(deleted), id -> id, id -> true).messages.isEmpty());
    }

    @Test
    public void encryptedEmptyHistoryWithoutAnchorStillReturnsTheWholeDeletedRange() {
        List<Integer> deleted = idsRange(-200, -1);
        AyuHistoryPagination.Page<Integer> page = AyuHistoryPagination.loadEncrypted(
                AyuHistoryPagination.Direction.BACKWARD, 0, Integer.MIN_VALUE, -1, 50,
                source(deleted), id -> id, id -> true);

        assertEquals(idsRange(-200, -81), page.messages);
        assertTrue(page.hasMoreOlder);
        assertTrue(page.contains(-200));
        assertTrue(page.contains(-81));
        assertFalse(page.contains(-80));
    }

    @Test
    public void encryptedAroundAnchorSplitsBothPagingDirections() {
        List<Integer> deleted = idsRange(-1000, -1);
        AyuHistoryPagination.Page<Integer> page = AyuHistoryPagination.loadEncrypted(
                AyuHistoryPagination.Direction.AROUND, -500, Integer.MIN_VALUE, -1, 50,
                source(deleted), id -> id, id -> true);

        assertEquals(idsRange(-560, -441), page.messages);
        assertTrue(page.hasMoreOlder);
        assertTrue(page.hasMoreNewer);
        assertTrue(page.contains(-500));
        assertFalse(page.contains(-561));
        assertFalse(page.contains(-440));
    }

    @Test
    public void encryptedForwardPagesNewerMessagesOnly() {
        List<Integer> deleted = idsRange(-100, -1);
        AyuHistoryPagination.Page<Integer> page = AyuHistoryPagination.loadEncrypted(
                AyuHistoryPagination.Direction.FORWARD, -95, Integer.MIN_VALUE, -1, 50,
                source(deleted), id -> id, id -> true);

        assertEquals(idsRange(-100, -96), page.messages);
        assertFalse(page.hasMoreNewer);
        assertTrue(page.contains(-96));
        assertFalse(page.contains(-95));
    }

    @Test
    public void encryptedQueriesStayInsideTheRequestedWindow() {
        List<Integer> deleted = idsRange(-1000, -1);
        int[] queryMin = {Integer.MAX_VALUE};
        int[] queryMax = {Integer.MIN_VALUE};
        AyuHistoryPagination.Source<Integer> source = (min, max, limit, ascending) -> {
            queryMin[0] = Math.min(queryMin[0], min);
            queryMax[0] = Math.max(queryMax[0], max);
            return source(deleted).load(min, max, limit, ascending);
        };
        AyuHistoryPagination.Page<Integer> page = AyuHistoryPagination.loadEncrypted(
                AyuHistoryPagination.Direction.AROUND, -500, -550, -450, 50,
                source, id -> id, id -> true);

        assertEquals(idsRange(-550, -450), page.messages);
        assertFalse(page.hasMoreOlder);
        assertFalse(page.hasMoreNewer);
        assertTrue(queryMin[0] >= -550 && queryMax[0] <= -450);
        assertTrue(page.contains(-450));
    }

    @Test
    public void archivePagesIncludeLocalNegativeIdsAfterPositiveOnes() {
        ArrayList<Integer> deleted = new ArrayList<>(ids(1, 100));
        deleted.addAll(idsRange(-5, -1));
        Set<Integer> seen = new HashSet<>();
        int anchor = 0;

        for (int request = 0; request < 10; request++) {
            AyuHistoryPagination.Page<Integer> page = AyuHistoryPagination.loadArchive(
                    AyuHistoryPagination.Direction.BACKWARD, anchor, 50,
                    source(deleted), id -> id, id -> true);
            if (page.messages.isEmpty()) {
                break;
            }
            for (int id : page.messages) {
                assertTrue("Message repeated: " + id, seen.add(id));
            }
            anchor = page.messages.get(page.messages.size() - 1);
            if (!page.hasMoreOlder) {
                break;
            }
        }

        assertEquals(new HashSet<>(deleted), seen);
    }

    @Test
    public void archivePageBoundariesExcludeIdsOutsideTheRange() {
        AyuHistoryPagination.Page<Integer> page = AyuHistoryPagination.loadArchive(
                AyuHistoryPagination.Direction.BACKWARD, 150, 50,
                source(ids(1, 200)), id -> id, id -> true);

        assertEquals(ids(30, 149).reversed(), page.messages);
        assertTrue(page.hasMoreOlder);
        assertTrue(page.contains(30));
        assertFalse(page.contains(29));
        assertFalse(page.contains(-1));
    }

    private static AyuHistoryPagination.Page<Integer> load(AyuHistoryPagination.Direction direction, int anchor, List<Integer> deleted) {
        return AyuHistoryPagination.load(direction, anchor, 1, Integer.MAX_VALUE, 50,
                source(deleted), id -> id, id -> true);
    }

    private static List<Integer> idsRange(int first, int last) {
        List<Integer> result = new ArrayList<>(last - first + 1);
        for (int id = first; id <= last; id++) {
            result.add(id);
        }
        return result;
    }

    private static AyuHistoryPagination.Source<Integer> source(List<Integer> deleted) {
        return (min, max, limit, ascending) -> deleted.stream()
                .filter(id -> min <= id && id <= max)
                .sorted(ascending ? Comparator.naturalOrder() : Comparator.reverseOrder())
                .limit(limit)
                .toList();
    }

    private static ArrayList<Integer> merge(List<Integer> server, AyuHistoryPagination.Page<Integer> page) {
        ArrayList<Integer> result = new ArrayList<>(page.messages);
        for (int id : server) {
            if (page.contains(id) && !result.contains(id)) {
                result.add(id);
            }
        }
        result.sort(Comparator.reverseOrder());
        return result;
    }

    private static List<Integer> ids(int first, int last) {
        return IntStream.rangeClosed(first, last).boxed().toList();
    }

    private static void assertDescending(List<Integer> messages) {
        for (int i = 1; i < messages.size(); i++) {
            assertTrue(messages.get(i - 1) > messages.get(i));
        }
    }
}
