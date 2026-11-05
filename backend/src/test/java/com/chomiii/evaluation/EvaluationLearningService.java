package com.chomiii.evaluation;

import com.chomiii.memory.MemoryLearningService;
import com.chomiii.memory.MemoryService;
import com.chomiii.provider.ChatProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class EvaluationLearningService extends MemoryLearningService {
    record Submission(boolean submitted, boolean rejected, boolean revisionAvailable, long scheduledNanos) {}
    private final CountingExecutor worker;
    private final BlockingQueue<Submission> submissions = new java.util.concurrent.LinkedBlockingQueue<>();

    EvaluationLearningService(MemoryService memories, ObjectMapper json, CountingExecutor worker,
            ScheduledExecutorService timer) {
        super(memories, json, worker, timer);
        this.worker = worker;
    }

    @Override public void schedule(String owner, UUID conversation, Long revision, String text,
            ChatProvider provider, String model, String key) {
        int before = worker.submitted.get();
        int rejected = worker.rejected.get();
        try {
            super.schedule(owner, conversation, revision, text, provider, model, key);
        } finally {
            submissions.add(new Submission(worker.submitted.get() > before, worker.rejected.get() > rejected, revision != null,
                    System.nanoTime()));
        }
    }

    Submission awaitSubmission() throws InterruptedException {
        Submission result = submissions.poll(15, TimeUnit.SECONDS);
        if (result == null) throw new IllegalStateException("Chat did not finish and schedule learning");
        return result;
    }

    static final class CountingExecutor extends ThreadPoolExecutor {
        private final AtomicInteger submitted = new AtomicInteger();
        private final AtomicInteger rejected = new AtomicInteger();

        CountingExecutor() {
            super(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16), new AbortPolicy());
        }

        @Override public void execute(Runnable task) {
            try {
                super.execute(task);
                submitted.incrementAndGet();
            } catch (java.util.concurrent.RejectedExecutionException full) {
                rejected.incrementAndGet();
                throw full;
            }
        }
    }
}
