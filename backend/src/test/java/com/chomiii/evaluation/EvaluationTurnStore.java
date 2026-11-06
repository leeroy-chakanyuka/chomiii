package com.chomiii.evaluation;

import com.chomiii.chat.ChatTurnStore;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Observes terminal chat state while retaining the delegate's real transaction boundary. */
final class EvaluationTurnStore extends ChatTurnStore {
    record Completion(UUID request, String status, long finishedNanos) {}
    private final ChatTurnStore delegate;
    private final BlockingQueue<Completion> completed = new LinkedBlockingQueue<>();

    EvaluationTurnStore(ChatTurnStore delegate) {
        super(null, null);
        this.delegate = delegate;
    }

    @Override public Pending begin(UUID conversation, String owner, UUID request, String content) {
        return delegate.begin(conversation, owner, request, content);
    }

    @Override public void finish(UUID conversation, String owner, UUID request, UUID assistant,
            String content, String status) {
        delegate.finish(conversation, owner, request, assistant, content, status);
        completed.add(new Completion(request, status, System.nanoTime()));
    }

    Completion awaitCompletion(long timeoutSeconds) throws InterruptedException {
        Completion completion = completed.poll(timeoutSeconds, TimeUnit.SECONDS);
        if (completion == null) throw new IllegalStateException("Chat did not finish within the evaluation timeout");
        return completion;
    }
}
