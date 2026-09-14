package com.radolyn.ayugram.proprietary;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

public final class AyuHistoryPagination {
    public static final int PAGE_SIZE = 120;

    public enum Direction {
        BACKWARD,
        FORWARD,
        AROUND
    }

    public interface Source<T> {
        List<T> load(int minId, int maxId, int limit, boolean ascending);
    }

    public static final class Page<T> {
        public final ArrayList<T> messages;
        public final int minId;
        public final int maxId;
        public final int anchorId;
        public final boolean hasMoreOlder;
        public final boolean hasMoreNewer;
        private final boolean preserveLocalMessages;

        private Page(ArrayList<T> messages, int minId, int maxId, int anchorId, boolean hasMoreOlder, boolean hasMoreNewer, boolean preserveLocalMessages) {
            this.messages = messages;
            this.minId = minId;
            this.maxId = maxId;
            this.anchorId = anchorId;
            this.hasMoreOlder = hasMoreOlder;
            this.hasMoreNewer = hasMoreNewer;
            this.preserveLocalMessages = preserveLocalMessages;
        }

        /** Regular messages and album completion must stay within this page boundary. */
        public boolean contains(int messageId) {
            return messageId == 0 || (preserveLocalMessages && messageId < 0) || minId <= messageId && messageId <= maxId;
        }
    }

    private static final class Slice<T> {
        final ArrayList<T> messages = new ArrayList<>();
        boolean hasMore;
    }

    private AyuHistoryPagination() {
    }

    public static <T> Page<T> load(
            Direction direction, int anchorId, int minId, int maxId, int count,
            Source<T> source, ToIntFunction<T> messageId, Predicate<T> canDisplay
    ) {
        return load(direction, anchorId, minId, maxId, count, false, false, source, messageId, canDisplay);
    }

    public static <T> Page<T> loadEncrypted(
            Direction direction, int anchorId, int minId, int maxId, int count,
            Source<T> source, ToIntFunction<T> messageId, Predicate<T> canDisplay
    ) {
        return load(direction, anchorId, minId, maxId, count, true, false, source, messageId, canDisplay);
    }

    /** Standalone archive lists may also hold local negative ids of unsent messages. */
    public static <T> Page<T> loadArchive(
            Direction direction, int anchorId, int count,
            Source<T> source, ToIntFunction<T> messageId, Predicate<T> canDisplay
    ) {
        return load(direction, anchorId, Integer.MIN_VALUE, Integer.MAX_VALUE, count, false, true, source, messageId, canDisplay);
    }

    private static <T> Page<T> load(
            Direction direction, int anchorId, int minId, int maxId, int count, boolean encrypted, boolean archive,
            Source<T> source, ToIntFunction<T> messageId, Predicate<T> canDisplay
    ) {
        int pageSize = Math.max(PAGE_SIZE, count);
        ArrayList<T> messages = new ArrayList<>();
        int domainMin = encrypted || archive ? Integer.MIN_VALUE : 1;
        int domainMax = encrypted ? -1 : Integer.MAX_VALUE;
        int pageMinId = domainMin;
        int pageMaxId = domainMax;
        boolean hasAnchor = archive ? anchorId != 0 : encrypted ? anchorId < 0 : anchorId > 0;
        boolean hasMoreOlder = false;
        boolean hasMoreNewer = false;

        if (direction == Direction.AROUND && hasAnchor) {
            Slice<T> older = loadSlice(
                    encrypted ? Math.max(minId, anchorId) : minId,
                    encrypted ? maxId : Math.min(maxId, anchorId),
                    pageSize / 2, encrypted, domainMin, domainMax, source, messageId, canDisplay);
            Slice<T> newer = loadSlice(
                    encrypted ? minId : Math.max((long) minId, (long) anchorId + 1),
                    encrypted ? Math.min((long) maxId, (long) anchorId - 1) : maxId,
                    pageSize - pageSize / 2, !encrypted, domainMin, domainMax, source, messageId, canDisplay);
            messages.addAll(older.messages);
            messages.addAll(newer.messages);
            hasMoreOlder = older.hasMore;
            hasMoreNewer = newer.hasMore;
            if (hasMoreOlder) {
                int boundary = messageId.applyAsInt(older.messages.get(older.messages.size() - 1));
                if (encrypted) pageMaxId = boundary;
                else pageMinId = boundary;
            }
            if (hasMoreNewer) {
                int boundary = messageId.applyAsInt(newer.messages.get(newer.messages.size() - 1));
                if (encrypted) pageMinId = boundary;
                else pageMaxId = boundary;
            }
        } else if (direction == Direction.FORWARD) {
            if (hasAnchor && (encrypted ? anchorId > domainMin : anchorId < domainMax)) {
                if (encrypted) pageMaxId = anchorId - 1;
                else pageMinId = anchorId + 1;
                Slice<T> newer = loadSlice(Math.max(minId, pageMinId), Math.min(maxId, pageMaxId),
                        pageSize, !encrypted, domainMin, domainMax, source, messageId, canDisplay);
                messages.addAll(newer.messages);
                hasMoreNewer = newer.hasMore;
                if (hasMoreNewer) {
                    int boundary = messageId.applyAsInt(newer.messages.get(newer.messages.size() - 1));
                    if (encrypted) pageMinId = boundary;
                    else pageMaxId = boundary;
                }
            }
        } else {
            if (hasAnchor) {
                if (encrypted) pageMinId = anchorId + 1;
                else if (anchorId == Integer.MIN_VALUE) {
                    pageMinId = Integer.MIN_VALUE + 1;
                    pageMaxId = Integer.MIN_VALUE;
                } else pageMaxId = anchorId - 1;
            }
            Slice<T> older = loadSlice(Math.max(minId, pageMinId), Math.min(maxId, pageMaxId),
                    pageSize, encrypted, domainMin, domainMax, source, messageId, canDisplay);
            messages.addAll(older.messages);
            hasMoreOlder = older.hasMore;
            if (hasMoreOlder) {
                int boundary = messageId.applyAsInt(older.messages.get(older.messages.size() - 1));
                if (encrypted) pageMaxId = boundary;
                else pageMinId = boundary;
            }
        }

        messages.sort((a, b) -> encrypted
                ? Integer.compare(messageId.applyAsInt(a), messageId.applyAsInt(b))
                : Integer.compare(messageId.applyAsInt(b), messageId.applyAsInt(a)));
        return new Page<>(messages, pageMinId, pageMaxId, anchorId, hasMoreOlder, hasMoreNewer, !encrypted && !archive);
    }

    private static <T> Slice<T> loadSlice(
            long minId, long maxId, int limit, boolean ascending, int domainMin, int domainMax,
            Source<T> source, ToIntFunction<T> messageId, Predicate<T> canDisplay
    ) {
        Slice<T> result = new Slice<>();
        Set<Integer> seenIds = new HashSet<>();
        minId = Math.max(domainMin, minId);
        maxId = Math.min(domainMax, maxId);
        int batchSize = Math.max(PAGE_SIZE, limit == Integer.MAX_VALUE ? limit : limit + 1);

        while (minId <= maxId) {
            List<T> batch = source.load((int) minId, (int) maxId, batchSize, ascending);
            if (batch == null || batch.isEmpty()) {
                break;
            }
            for (T message : batch) {
                int id = messageId.applyAsInt(message);
                if (id == 0 || id < minId || id > maxId || !seenIds.add(id) || !canDisplay.test(message)) {
                    continue;
                }
                if (result.messages.size() == limit) {
                    result.hasMore = true;
                    return result;
                }
                result.messages.add(message);
            }

            // Advance the database cursor even past filtered or unrestorable rows.
            int lastId = messageId.applyAsInt(batch.get(batch.size() - 1));
            if (ascending) {
                long nextId = (long) lastId + 1;
                if (nextId <= minId) {
                    break;
                }
                minId = nextId;
            } else {
                long nextId = (long) lastId - 1;
                if (nextId >= maxId) {
                    break;
                }
                maxId = nextId;
            }
            if (batch.size() < batchSize) {
                break;
            }
        }
        return result;
    }
}
