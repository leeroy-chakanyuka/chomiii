package com.chomiii.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class EvaluationConfigTest {
    @Test
    void defaultsToOfflineAndNeverRequiresAKey() {
        var config = EvaluationConfig.from(new Properties(), Map.of());
        assertThat(config.mode()).isEqualTo("offline");
        assertThat(config.maxCalls()).isZero();
        assertThat(config.judge()).isFalse();
    }

    @Test
    void liveRequiresExplicitEnablementKeyAndBudget() {
        var properties = new Properties();
        properties.setProperty("eval.mode", "live");
        assertThatThrownBy(() -> EvaluationConfig.from(properties, Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("EVAL_LIVE");
        assertThatThrownBy(() -> EvaluationConfig.from(properties, Map.of("EVAL_LIVE", "true", "GEMINI_API_KEY", "fake")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("EVAL_MAX_PROVIDER_CALLS");
        var config = EvaluationConfig.from(properties, Map.of("EVAL_LIVE", "true", "GEMINI_API_KEY", "fake-secret-value",
                "EVAL_MAX_PROVIDER_CALLS", "3"));
        assertThat(config.maxCalls()).isEqualTo(3);
        assertThat(config.toString()).doesNotContain("fake-secret-value");
    }

    @Test
    void independentJudgeCanBeEnabledWithoutEnablingGemini() {
        assertThatThrownBy(() -> EvaluationConfig.from(new Properties(), Map.of("EVAL_JUDGE", "true")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("EVAL_MAX_PROVIDER_CALLS");
        var config = EvaluationConfig.from(new Properties(), Map.of("EVAL_JUDGE", "true", "EVAL_MAX_PROVIDER_CALLS", "2"));
        assertThat(config.mode()).isEqualTo("offline");
        assertThat(config.judge()).isTrue();
        assertThat(config.maxCalls()).isEqualTo(2);
    }
}
