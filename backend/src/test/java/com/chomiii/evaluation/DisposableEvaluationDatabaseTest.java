package com.chomiii.evaluation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class DisposableEvaluationDatabaseTest {
    @Test
    void refusesRealOrRemoteDatabasesBeforeStartingSpring() {
        assertThatThrownBy(() -> DisposableEvaluationDatabase.validate(Map.of(
                "EVAL_DISPOSABLE_DB", "true", "CHOMIII_DB_URL", "jdbc:postgresql://localhost:5432/chomiii",
                "CHOMIII_DB_USER", "test", "CHOMIII_DB_PASSWORD", "test")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("chomiii_eval_");
        assertThatThrownBy(() -> DisposableEvaluationDatabase.validate(Map.of(
                "EVAL_DISPOSABLE_DB", "true", "CHOMIII_DB_URL", "jdbc:postgresql://remote.example:5432/chomiii_eval_test",
                "CHOMIII_DB_USER", "test", "CHOMIII_DB_PASSWORD", "test")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("loopback");
        assertThatThrownBy(() -> DisposableEvaluationDatabase.validate(Map.of(
                "EVAL_DISPOSABLE_DB", "true", "CHOMIII_DB_URL", "jdbc:mysql://localhost:3306/chomiii_eval_test?password=secret",
                "CHOMIII_DB_USER", "test", "CHOMIII_DB_PASSWORD", "test")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("parameter");
    }

    @Test
    void requiresExplicitDisposableFlagEvenForAnEvaluationNamedDatabase() {
        assertThatThrownBy(() -> DisposableEvaluationDatabase.validate(Map.of(
                "CHOMIII_DB_URL", "jdbc:mysql://localhost:3306/chomiii_eval_test",
                "CHOMIII_DB_USER", "test", "CHOMIII_DB_PASSWORD", "test")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("EVAL_DISPOSABLE_DB");
        assertThatCode(() -> DisposableEvaluationDatabase.validate(Map.of(
                "EVAL_DISPOSABLE_DB", "true", "CHOMIII_DB_URL", "jdbc:mysql://127.0.0.1:3306/chomiii_eval_test",
                "CHOMIII_DB_USER", "test", "CHOMIII_DB_PASSWORD", "test"))).doesNotThrowAnyException();
    }
}
