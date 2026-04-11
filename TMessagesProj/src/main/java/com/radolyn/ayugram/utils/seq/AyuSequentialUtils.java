package com.radolyn.ayugram.utils.seq;

import org.telegram.messenger.SendMessagesHelper;

import java.util.ArrayList;

public abstract class AyuSequentialUtils {

    public static final class DispatchResult {
        public final int expectedCompletions;
        public final ArrayList<String> uploadPaths;

        private DispatchResult(int expectedCompletions, ArrayList<String> uploadPaths) {
            this.expectedCompletions = expectedCompletions;
            this.uploadPaths = uploadPaths;
        }

        public static DispatchResult none() {
            return new DispatchResult(0, null);
        }

        public static DispatchResult dispatched(int expectedCompletions, ArrayList<String> uploadPaths) {
            return new DispatchResult(expectedCompletions, uploadPaths);
        }
    }

    @FunctionalInterface
    public interface SendStep {
        DispatchResult dispatch();
    }

    private AyuSequentialUtils() {
    }

    public static void prepareWait(DummyMessageWaiter messageWaiter, DummyFileUploadWaiter uploadWaiter, SendMessagesHelper sendMessagesHelper, long taskId, long targetDialogId) {
        messageWaiter.prepare(taskId, targetDialogId, sendMessagesHelper);
        uploadWaiter.clear();
    }

    public static void applyDispatchResult(DummyMessageWaiter messageWaiter, DummyFileUploadWaiter uploadWaiter, DispatchResult dispatchResult) {
        if (dispatchResult == null) {
            messageWaiter.setExpectedCount(0);
            return;
        }
        messageWaiter.setExpectedCount(dispatchResult.expectedCompletions);
        uploadWaiter.addUploadPaths(dispatchResult.uploadPaths);
    }
}
