package com.chomiii.provider.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chomiii.agent.AgentInstructions;
import com.chomiii.memory.MemorySearchTool;
import com.chomiii.memory.MemoryService;
import com.chomiii.memory.PersonalMemory;
import com.chomiii.provider.ChatProvider;
import com.chomiii.tools.ToolTurn;
import com.chomiii.tools.ToolDefinition;
import com.chomiii.tools.ToolRegistry;
import com.chomiii.tools.ToolExecutionContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "CHOMIII_RUN_TOOL_SMOKE", matches = "true")
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class GeminiMemoryToolLiveTest {
    @Test
    void geminiCanInvokeMemorySearchAndContinueWithinTwoRequests() {
        var memories = mock(MemoryService.class);
        var fact = new PersonalMemory(UUID.randomUUID(), "I prefer tacos for dinner", "user_explicit",
                Instant.now(), Instant.now());
        when(memories.search(eq("synthetic-owner"), anyString(), nullable(String.class), anyInt()))
                .thenReturn(List.of(fact));
        var tool = new ToolTurn(new ToolRegistry(List.of(new MemorySearchTool(memories, null))),
                new ToolExecutionContext("synthetic-owner", UUID.randomUUID(), UUID.randomUUID(), null, true, ignored -> {}), false);
        var continuations = new AtomicInteger();
        var invocations = new AtomicInteger();
        ChatProvider.Tools bounded = new ChatProvider.Tools() {
            @Override public List<ToolDefinition> definitions() { return tool.definitions(); }
            @Override public Map<String, Object> invoke(String name, Map<String, Object> arguments) {
                invocations.incrementAndGet();
                return tool.invoke(name, arguments);
            }
            @Override public void beforeContinuation(long inputBytes) {
                if (continuations.incrementAndGet() > 1) throw new IllegalStateException("Live probe budget exhausted");
            }
        };
        var input = new ChatProvider.ChatInput("gemini-3.5-flash-lite", new AgentInstructions().systemPrompt(),
                List.of(new ChatProvider.Message(ChatProvider.Role.USER,
                        "This is a fictional test. Use memory.search to find my dinner preference, then name the food.")));
        var answer = new StringBuilder();
        try (var generation = new GeminiProvider().open(input, System.getenv("GEMINI_API_KEY"), bounded)) {
            generation.stream(answer::append);
            assertThat(invocations.get()).isGreaterThan(0);
            assertThat(continuations.get()).isEqualTo(1);
            assertThat(answer.toString()).containsIgnoringCase("taco");
            generation.usage().ifPresent(usage -> System.out.println("Live tool probe usage: " + usage));
        }
    }
}
