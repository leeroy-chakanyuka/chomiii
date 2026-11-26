package com.chomiii.memory.embedding;

import jakarta.annotation.PreDestroy;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Durable jobs hold no credentials; a completed chat supplies a temporary key. */
@Service
public class EmbeddingIndexer {
    private final EmbeddingStore store;
    private final EmbeddingProvider provider;
    private final boolean enabled;
    private final Set<String> activeOwners = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), Thread.ofPlatform().daemon().name("memory-index-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());

    public EmbeddingIndexer(EmbeddingStore store, EmbeddingProvider provider,
            @Value("${chomiii.memory.embeddings.enabled:false}") boolean enabled) {
        this.store = store;
        this.provider = provider;
        this.enabled = enabled;
    }

    public boolean enabled() { return enabled; }

    public void schedule(String owner, String apiKey) {
        if (!enabled || apiKey == null || apiKey.isBlank() || !activeOwners.add(owner)) return;
        try {
            executor.execute(() -> {
                try {
                    drain(owner, apiKey);
                } finally {
                    activeOwners.remove(owner);
                }
            });
        } catch (RejectedExecutionException full) {
            activeOwners.remove(owner);
        }
    }

    private void drain(String owner, String apiKey) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        for (int count = 0; count < 8 && System.nanoTime() < deadline && !Thread.currentThread().isInterrupted(); count++) {
            EmbeddingStore.Job job;
            try {
                var claimed = store.claim(owner);
                if (claimed.isEmpty()) return;
                job = claimed.get();
            } catch (RuntimeException unavailable) {
                return;
            }
            try {
                float[] vector = provider.document(job.content(), apiKey);
                store.complete(job, vector);
            } catch (RuntimeException unavailable) {
                try { store.retry(job); } catch (RuntimeException ignored) { }
                return;
            }
        }
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}
