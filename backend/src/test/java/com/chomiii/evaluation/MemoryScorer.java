package com.chomiii.evaluation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

final class MemoryScorer {
    record Actual(String id, String content, String status) {}
    record Match(String expectedId, String actualId, String method) {}
    record Score(int truePositives, int falsePositives, int falseNegatives,
            int activeMatches, int inScopeExpected, int inScopeMatches, int unreviewed,
            Double precision, Double recall, Double activeRecall, Double withinCapacityRecall,
            Double f1, List<Match> matches) {}

    Score score(EvaluationFixture fixture, List<Actual> actual) {
        List<EvaluationFixture.ExpectedMemory> expected = fixture.expectedMemories();
        int[] owner = new int[expected.size()];
        Arrays.fill(owner, -1);
        for (int i = 0; i < actual.size(); i++) {
            assign(i, actual, expected, owner, new boolean[expected.size()]);
        }
        boolean[] matchedActual = new boolean[actual.size()];
        List<Match> matches = new ArrayList<>();
        int active = 0;
        int inScope = 0;
        int inScopeMatches = 0;
        for (int i = 0; i < expected.size(); i++) {
            var fact = expected.get(i);
            if (fact.inScope()) inScope++;
            if (owner[i] < 0) continue;
            var found = actual.get(owner[i]);
            matchedActual[owner[i]] = true;
            if ("active".equals(found.status())) active++;
            if (fact.inScope()) inScopeMatches++;
            matches.add(new Match(fact.id(), found.id(),
                    normalize(fact.statement()).equals(normalize(found.content())) ? "normalized_exact" : "human_alias"));
        }
        int falsePositives = 0;
        int unreviewed = 0;
        for (int i = 0; i < actual.size(); i++) {
            if (matchedActual[i]) continue;
            String content = normalize(actual.get(i).content());
            boolean duplicate = expected.stream().anyMatch(fact -> matches(fact, content));
            boolean forbidden = fixture.forbiddenMemories().stream()
                    .anyMatch(fact -> normalize(fact.statement()).equals(content));
            if (duplicate || forbidden) falsePositives++;
            else unreviewed++;
        }
        int tp = matches.size();
        int fn = expected.size() - tp;
        // An unrecognized paraphrase needs review; it must not become an invented score.
        Double precision = unreviewed == 0 ? ratio(tp, tp + falsePositives) : null;
        Double recall = unreviewed == 0 ? ratio(tp, expected.size()) : null;
        Double activeRecall = unreviewed == 0 ? ratio(active, expected.size()) : null;
        Double withinCapacity = unreviewed == 0 ? ratio(inScopeMatches, inScope) : null;
        Double f1 = precision != null && recall != null && precision + recall > 0
                ? 2 * precision * recall / (precision + recall) : null;
        return new Score(tp, falsePositives, fn, active, inScope, inScopeMatches, unreviewed,
                precision, recall, activeRecall, withinCapacity, f1, List.copyOf(matches));
    }

    private static boolean assign(int actualIndex, List<Actual> actual,
            List<EvaluationFixture.ExpectedMemory> expected, int[] owner, boolean[] visited) {
        String content = normalize(actual.get(actualIndex).content());
        for (int i = 0; i < expected.size(); i++) {
            if (visited[i] || !matches(expected.get(i), content)) continue;
            visited[i] = true;
            if (owner[i] < 0 || assign(owner[i], actual, expected, owner, visited)) {
                owner[i] = actualIndex;
                return true;
            }
        }
        return false;
    }

    private static boolean matches(EvaluationFixture.ExpectedMemory fact, String content) {
        return normalize(fact.statement()).equals(content)
                || fact.aliases().stream().anyMatch(alias -> normalize(alias).equals(content));
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    private static Double ratio(int numerator, int denominator) {
        return denominator == 0 ? null : (double) numerator / denominator;
    }
}
