package com.chomiii.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class IndependentJudgeSuiteTest {
    @Test
    void judgeCanGradeAScriptedRunWithoutEnablingGemini() throws Exception {
        var json = new ObjectMapper();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/judge", exchange -> {
            var request = json.readTree(exchange.getRequestBody());
            var data = json.readTree(request.path("messages").path(1).path("content").asText());
            String id = data.path("actualMemories").path(0).path("id").asText();
            String retrievalId = data.path("followUpAnswers").path(0).path("retrievalId").asText();
            var decision = Map.of("fixtureId", "m2-explicit-appointments-001",
                    "matches", List.of(Map.of("expectedId", "appointment-time", "actualId", id,
                            "verdict", "supported", "evidence", "I prefer appointments after ten",
                            "reason", "The user stated the preference.")),
                    "unsupported", List.of(), "missingExpected", List.of(), "contradictions", List.of(),
                    "wrongPersonAttributions", List.of(), "overInferences", List.of(), "needsHumanReview", List.of(),
                    "answers", List.of(Map.of("retrievalId", retrievalId, "verdict", "incorrect",
                            "evidence", "", "reason", "The scripted reply does not answer.")),
                    "summary", "Memory supported; scripted answer is not informative.");
            byte[] response = json.writeValueAsBytes(Map.of("choices", List.of(Map.of("message",
                    Map.of("content", json.writeValueAsString(decision)))),
                    "usage", Map.of("prompt_tokens", 10, "completion_tokens", 4)));
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            var env = Map.of("EVAL_JUDGE", "true", "EVAL_MAX_PROVIDER_CALLS", "1", "EVAL_MAX_FIXTURES", "1",
                    "EVAL_JUDGE_URL", "http://127.0.0.1:" + server.getAddress().getPort() + "/judge",
                    "EVAL_JUDGE_MODEL", "other-model", "EVAL_JUDGE_FAMILY", "other-family", "EVAL_JUDGE_API_KEY", "fake-key");
            var properties = new Properties();
            properties.setProperty("eval.fixture", "m2-explicit-appointments-001");
            var run = new EvaluationSuite().run(EvaluationConfig.from(properties, env), env);
            assertThat(run.metadata().failed()).isZero();
            assertThat(run.metadata().providerCalls()).containsEntry(LiveBudget.Kind.JUDGE, 1)
                    .containsEntry(LiveBudget.Kind.CHAT, 0).containsEntry(LiveBudget.Kind.EXTRACTION, 0);
            assertThat(run.cases().getFirst().judgment().answers().getFirst().verdict()).isEqualTo("incorrect");
        } finally {
            server.stop(0);
        }
    }
}
