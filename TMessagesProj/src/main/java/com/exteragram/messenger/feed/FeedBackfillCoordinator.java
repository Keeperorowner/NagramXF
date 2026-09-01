package com.exteragram.messenger.feed;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * Requests older channel history from the network for channels whose local
 * cache is exhausted or incomplete, so the feed timeline keeps filling gaps.
 */
final class FeedBackfillCoordinator {
    private final int currentAccount;
    private int loadIndex;
    private final Runnable onRoundFinished;
    private int roundId;
    private boolean running;
    private final int guid = ConnectionsManager.generateClassGuid();
    private final HashSet<Long> pending = new HashSet<>();
    private final HashSet<Long> exhausted = new HashSet<>();

    public FeedBackfillCoordinator(int account, Runnable onRoundFinished) {
        this.currentAccount = account;
        this.onRoundFinished = onRoundFinished;
    }

    public HashSet<Long> getExhaustedSnapshot() {
        return new HashSet<>(exhausted);
    }

    public void clearExhausted() {
        exhausted.clear();
    }

    public void cancel() {
        running = false;
        roundId++;
        pending.clear();
        ConnectionsManager.getInstance(currentAccount).cancelRequestsForGuid(guid);
    }

    public void startRound(ArrayList<long[]> candidates) {
        running = true;
        final int currentRound = ++roundId;
        pending.clear();
        int count = Math.min(4, candidates.size());
        for (int i = 0; i < count; i++) {
            pending.add(candidates.get(i)[0]);
        }
        MessagesController messagesController = MessagesController.getInstance(currentAccount);
        for (int i = 0; i < count; i++) {
            long dialogId = candidates.get(i)[0];
            int maxId = (int) candidates.get(i)[1];
            int loadIndex = this.loadIndex++;
            messagesController.loadMessages(dialogId, 0L, false, 20, maxId, 0, false, 0, guid, 0, 0, 0, 0L, 0, loadIndex, false);
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (currentRound == roundId && running) {
                exhausted.addAll(pending);
                finishRound();
            }
        }, 10000L);
    }

    public void onMessagesDidLoad(Object... args) {
        if (((Integer) args[10]).intValue() != guid) {
            return;
        }
        Long dialogId = (Long) args[0];
        if (((ArrayList) args[2]).size() < 20) {
            exhausted.add(dialogId);
        }
        onResult(dialogId);
    }

    public void onLoadingMessagesFailed(Object... args) {
        if (((Integer) args[0]).intValue() != guid) {
            return;
        }
        Object request = args[1];
        long dialogId = 0;
        if (request instanceof TLRPC.TL_messages_getHistory) {
            TLRPC.InputPeer peer = ((TLRPC.TL_messages_getHistory) request).peer;
            if (peer != null) {
                long peerId = peer.channel_id;
                if (peerId == 0) {
                    peerId = peer.chat_id;
                }
                dialogId = -peerId;
            }
        }
        if (dialogId != 0) {
            exhausted.add(dialogId);
        }
        onResult(dialogId);
    }

    private void onResult(long dialogId) {
        if (running && pending.remove(dialogId) && pending.isEmpty()) {
            finishRound();
        }
    }

    private void finishRound() {
        running = false;
        roundId++;
        pending.clear();
        onRoundFinished.run();
    }
}
