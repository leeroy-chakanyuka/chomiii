package com.chomiii.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class IndependentJudgeTest {
    @Test
    void sendsBlindEvaluationToAnotherProviderAndCountsTheCall() throws Exception {
        var fixture = new FixtureLoader().load(Path.of("..", "evals", "fixtures", "m2", "explicit-facts", "appointments.json"));
        var actual = List.of(new MemoryEvaluationRunner.StoredMemory("m1", "Appointments after ten", "conversation_fact", "active", 0.95, null));
        var json = new ObjectMapper();
        String content = """
                {"fixtureId":"m2-explicit-appointments-001","matches":[{"expectedId":"appointment-time",
                "actualId":"m1","verdict":"supported","evidence":"I prefer appointments after ten",
                "reason":"The preference is directly stated."}],"unsupported":[],"missingExpected":[],
                "contradictions":[],"wrongPersonAttributions":[],"overInferences":[],"needsHumanReview":[],"answers":[],"summary":"Supported"}
                """;
        byte[] body = json.writeValueAsBytes(Map.of("choices", List.of(Map.of("message", Map.of("content", content))),
                "usage", Map.of("prompt_tokens", 12, "completion_tokens", 8)));
        var received = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/judge", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            var env = Map.of("EVAL_JUDGE_URL", "http://127.0.0.1:" + server.getAddress().getPort() + "/judge",
                    "EVAL_JUDGE_MODEL", "other-model", "EVAL_JUDGE_FAMILY", "other-family", "EVAL_JUDGE_API_KEY", "fake-private-key");
            var budget = new LiveBudget(true, 1);
            var judge = new IndependentJudge(IndependentJudge.Config.from(env, 5), env.get("EVAL_JUDGE_API_KEY"), budget);
            var judgment = judge.judge(fixture, actual, List.of());
            assertThat(judgment.matches()).hasSize(1);
            assertThat(judge.usage()).containsExactly(new IndependentJudge.Usage(12L, 8L));
            assertThat(budget.counts()).containsEntry(LiveBudget.Kind.JUDGE, 1);
            assertThat(received.get()).contains("sourceTurnIds", "actualMemories").doesNotContain("fake-private-key", "truePositives");
            assertThatThrownBy(() -> judge.judge(fixture, actual, List.of())).isInstanceOf(LiveBudget.Exhausted.class);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsAGeminiJudgeAsNonIndependent() {
        var env = Map.of("EVAL_JUDGE_URL", "https://generativelanguage.googleapis.com/v1/chat/completions",
                "EVAL_JUDGE_MODEL", "gemini-example", "EVAL_JUDGE_FAMILY", "google", "EVAL_JUDGE_API_KEY", "fake");
        assertThatThrownBy(() -> IndependentJudge.Config.from(env, 5))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("different");
    }
}
