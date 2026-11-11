package com.chomiii.memory.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class EmbeddingIndexerTest {
    private static final class FakeEmbeddingProvider implements EmbeddingProvider {
        private final boolean fail;
        private int calls;
        FakeEmbeddingProvider(boolean fail) { this.fail = fail; }
        @Override public Descriptor descriptor() { return new Descriptor("fake", 768, "search-v1"); }
        @Override public float[] document(String content, String apiKey) {
            calls++;
            if (fail) throw new IllegalStateException("quota exhausted");
            float[] value = new float[768];
            value[0] = 1;
            return value;
        }
        @Override public float[] query(String content, String apiKey) { return document(content, apiKey); }
    }

    @Test
    void disabledIndexingMakesNoProviderCalls() {
        var store = mock(EmbeddingStore.class);
        var provider = new FakeEmbeddingProvider(false);
        var indexer = new EmbeddingIndexer(store, provider, false);
        try {
            indexer.schedule("owner", "key");
            assertThat(provider.calls).isZero();
            verify(store, never()).claim(any());
        } finally {
            indexer.close();
        }
    }

    @Test
    void providerFailureLeavesDurableWorkForRetry() throws Exception {
        var store = mock(EmbeddingStore.class);
        var provider = new FakeEmbeddingProvider(true);
        var job = new EmbeddingStore.Job("owner", UUID.randomUUID(), "I prefer tacos", "hash", "lease",
                provider.descriptor(), 1);
        when(store.claim("owner")).thenReturn(Optional.of(job));
        var retried = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(call -> { retried.countDown(); return null; }).when(store).retry(job);
        var indexer = new EmbeddingIndexer(store, provider, true);
        try {
            indexer.schedule("owner", "request-key");
            assertThat(retried.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(provider.calls).isEqualTo(1);
            verify(store, never()).complete(any(), any());
        } finally {
            indexer.close();
        }
    }
}
