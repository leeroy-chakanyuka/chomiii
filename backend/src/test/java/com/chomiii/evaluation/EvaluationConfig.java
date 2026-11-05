package com.chomiii.evaluation;

import java.util.Map;
import java.util.Properties;

record EvaluationConfig(String mode, String fixture, String category, int repetitions,
        int maxFixtures, int maxCalls, int timeoutSeconds, String model, boolean judge) {
    static EvaluationConfig from(Properties properties, Map<String, String> environment) {
        String mode = properties.getProperty("eval.mode", "offline");
        if (!java.util.Set.of("offline", "integration", "live").contains(mode)) {
            throw new IllegalArgumentException("Unknown evaluation mode");
        }
        boolean live = "live".equals(mode);
        boolean judge = "true".equals(environment.get("EVAL_JUDGE"));
        if (live && !"true".equals(environment.get("EVAL_LIVE"))) {
            throw new IllegalArgumentException("Live evaluation requires EVAL_LIVE=true");
        }
        if (live && (environment.get("GEMINI_API_KEY") == null || environment.get("GEMINI_API_KEY").isBlank())) {
            throw new IllegalArgumentException("Live evaluation requires GEMINI_API_KEY");
        }
        if ((live || judge) && !environment.containsKey("EVAL_MAX_PROVIDER_CALLS")) {
            throw new IllegalArgumentException("Provider evaluation requires EVAL_MAX_PROVIDER_CALLS");
        }
        int maxCalls = live || judge ? integer(environment.get("EVAL_MAX_PROVIDER_CALLS"), 1, 1000, "call budget") : 0;
        int maxFixtures = integer(environment.getOrDefault("EVAL_MAX_FIXTURES", live || judge ? "10" : "10000"), 1, 10000, "fixture limit");
        int repetitions = integer(properties.getProperty("eval.repetitions",
                environment.getOrDefault("EVAL_REPETITIONS", "1")), 1, 100, "repetitions");
        int timeout = integer(environment.getOrDefault("EVAL_TIMEOUT_SECONDS", "60"), 1, 300, "timeout");
        String model = environment.getOrDefault("EVAL_MODEL", "gemini-3.5-flash-lite");
        if (model.isBlank()) throw new IllegalArgumentException("Model must not be blank");
        return new EvaluationConfig(mode, properties.getProperty("eval.fixture"),
                properties.getProperty("eval.category"), repetitions, maxFixtures, maxCalls, timeout, model, judge);
    }

    private static int integer(String value, int min, int max, String name) {
        try {
            int result = Integer.parseInt(value);
            if (result >= min && result <= max) return result;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("Invalid " + name);
    }
}
