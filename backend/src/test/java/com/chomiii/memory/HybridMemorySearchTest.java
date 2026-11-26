package com.chomiii.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chomiii.memory.embedding.EmbeddingProvider;
import com.chomiii.memory.embedding.EmbeddingStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HybridMemorySearchTest {
    private static final class FakeProvider implements EmbeddingProvider {
        final AtomicInteger calls = new AtomicInteger();
        boolean fail;
        @Override public Descriptor descriptor() { return new Descriptor("fake", 768, "search-v1"); }
        @Override public float[] document(String text, String key) { return vector(0); }
        @Override public float[] query(String text, String key) {
            calls.incrementAndGet();
            if (fail) throw new IllegalStateException("quota");
            return vector(0);
        }
    }

    private static float[] vector(int index) {
        float[] vector = new float[768];
        vector[index] = 1;
        return vector;
    }

    private static PersonalMemory memory(String content) {
        return new PersonalMemory(UUID.randomUUID(), content, "user_explicit", Instant.now(), Instant.now());
    }

    @Test
    void semanticSearchUsesOneQueryVectorAndCachesOnlyForTheSameOwner() {
        var memories = mock(MemoryService.class);
        var store = mock(EmbeddingStore.class);
        var provider = new FakeProvider();
        var tacos = memory("I like tacos");
        var cameras = memory("I collect cameras");
        when(memories.search("a", "supper", null, 20)).thenReturn(List.of());
        when(memories.list("a")).thenReturn(List.of(tacos, cameras));
        when(store.vectors("a", provider.descriptor())).thenReturn(Map.of(tacos.id(), vector(0), cameras.id(), vector(1)));
        var search = new HybridMemorySearch(memories, store, provider, true);

        assertThat(search.search("a", "supper", null, 5, "key")).containsExactly(tacos);
        assertThat(search.search("a", "supper", null, 5, "key")).containsExactly(tacos);
        assertThat(provider.calls).hasValue(1);

        when(memories.search("b", "supper", null, 20)).thenReturn(List.of());
        when(memories.list("b")).thenReturn(List.of(cameras));
        when(store.vectors("b", provider.descriptor())).thenReturn(Map.of(cameras.id(), vector(1)));
        assertThat(search.search("b", "supper", null, 5, "key")).isEmpty();
        assertThat(provider.calls).hasValue(2);
    }

    @Test
    void missingVectorsDisabledModeAndProviderFailureKeepLexicalResults() {
        var memories = mock(MemoryService.class);
        var store = mock(EmbeddingStore.class);
        var provider = new FakeProvider();
        var lexical = memory("I like soup for dinner");
        when(memories.search("a", "dinner", null, 20)).thenReturn(List.of(lexical));
        when(store.vectors("a", provider.descriptor())).thenReturn(Map.of());
        var enabled = new HybridMemorySearch(memories, store, provider, true);
        assertThat(enabled.search("a", "dinner", null, 5, "key")).containsExactly(lexical);
        assertThat(provider.calls).hasValue(0);

        var disabled = new HybridMemorySearch(memories, store, provider, false);
        assertThat(disabled.search("a", "dinner", null, 5, "key")).containsExactly(lexical);
        assertThat(provider.calls).hasValue(0);

        when(store.vectors("a", provider.descriptor())).thenReturn(Map.of(lexical.id(), vector(0)));
        provider.fail = true;
        assertThat(enabled.search("a", "dinner", null, 5, "key")).containsExactly(lexical);
        assertThat(provider.calls).hasValue(1);
    }

    @Test
    void suggestedMemoriesAreExcludedEvenIfAVectorExists() {
        var memories = mock(MemoryService.class);
        var store = mock(EmbeddingStore.class);
        var provider = new FakeProvider();
        var suggestion = new PersonalMemory(UUID.randomUUID(), "I might like tacos", "inferred",
                Instant.now(), Instant.now(), "suggested", 0.75, null);
        when(memories.search("a", "supper", null, 20)).thenReturn(List.of());
        when(memories.list("a")).thenReturn(List.of(suggestion));
        when(store.vectors("a", provider.descriptor())).thenReturn(Map.of(suggestion.id(), vector(0)));

        assertThat(new HybridMemorySearch(memories, store, provider, true)
                .search("a", "supper", null, 5, "key")).isEmpty();
    }
}
