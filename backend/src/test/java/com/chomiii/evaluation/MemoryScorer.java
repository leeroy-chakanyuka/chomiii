package com.chomiii.evaluation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class MemoryScorer {
    record Actual(String id, String content, String status, String evidence, String source) {
        Actual(String id, String content, String status) { this(id, content, status, null, null); }
        Actual(String id, String content, String status, String evidence) { this(id, content, status, evidence, null); }
    }
    record Match(String expectedId, String actualId, String method, String evidence) {}
    record FalsePositive(String actualId, String category) {}
    record Score(int truePositives, int falsePositives, int falseNegatives,
            int activeMatches, int inScopeExpected, int inScopeMatches, int unreviewed,
            Double precision, Double recall, Double activeRecall, Double withinCapacityRecall,
            Double f1, int statusCorrect, Double statusAccuracy, int kindCorrect, Double kindAccuracy,
            Map<String, Integer> falsePositiveCategories, List<FalsePositive> classifiedFalsePositives,
            List<String> disagreements, List<Match> matches) {}

    Score score(EvaluationFixture fixture, List<Actual> actual) {
        return score(fixture, actual, null);
    }

    Score score(EvaluationFixture fixture, List<Actual> actual, JudgeAdapter.Judgment judgment) {
        List<EvaluationFixture.ExpectedMemory> expected = fixture.expectedMemories().stream()
                .filter(fact -> !"absent".equals(fact.expectedStatus())).toList();
        List<String> disagreements = new ArrayList<>();
        if (judgment != null) {
            for (var issue : judgment.issues()) {
                actual.stream().filter(memory -> memory.id().equals(issue.actualId())).findFirst().ifPresent(memory -> {
                    if (expected.stream().anyMatch(fact -> matches(fact, normalize(memory.content())))) {
                        disagreements.add(memory.id());
                    }
                });
            }
            for (String id : judgment.needsHumanReview()) {
                actual.stream().filter(memory -> memory.id().equals(id)).findFirst().ifPresent(memory -> {
                    if (expected.stream().anyMatch(fact -> matches(fact, normalize(memory.content())))) {
                        disagreements.add(id);
                    }
                });
            }
            for (var match : judgment.matches()) {
                actual.stream().filter(memory -> memory.id().equals(match.actualId())).findFirst().ifPresent(memory -> {
                    var direct = expected.stream().filter(fact -> matches(fact, normalize(memory.content()))).toList();
                    if (!direct.isEmpty() && direct.stream().noneMatch(fact -> fact.id().equals(match.expectedId()))) {
                        disagreements.add(memory.id());
                    }
                });
            }
        }
        int[] owner = new int[expected.size()];
        Arrays.fill(owner, -1);
        for (int i = 0; i < actual.size(); i++) {
            if (!disagreements.contains(actual.get(i).id())) {
                assign(i, actual, expected, owner, new boolean[expected.size()], judgment, disagreements);
            }
        }
        boolean[] matchedActual = new boolean[actual.size()];
        List<Match> matches = new ArrayList<>();
        int active = 0;
        int inScope = 0;
        int inScopeMatches = 0;
        int statusCorrect = 0;
        int kindCorrect = 0;
        int kindUnknown = 0;
        for (int i = 0; i < expected.size(); i++) {
            var fact = expected.get(i);
            if (fact.inScope()) inScope++;
            if (owner[i] < 0) continue;
            var found = actual.get(owner[i]);
            matchedActual[owner[i]] = true;
            if ("active".equals(found.status())) active++;
            if (fact.expectedStatus().equals(found.status())) statusCorrect++;
            if (found.source() == null) kindUnknown++;
            else if (kindMatches(fact, found.source())) kindCorrect++;
            if (fact.inScope()) inScopeMatches++;
            var judgeMatch = judgment == null ? java.util.Optional.<JudgeAdapter.Match>empty() : judgment.matches().stream()
                    .filter(match -> match.expectedId().equals(fact.id()) && match.actualId().equals(found.id())).findFirst();
            String method = normalize(fact.statement()).equals(normalize(found.content())) ? "normalized_exact"
                    : matches(fact, normalize(found.content())) ? "human_alias"
                    : judgment != null && "manual".equals(judgment.family()) ? "human_confirmed" : "independent_judge";
            matches.add(new Match(fact.id(), found.id(), method,
                    judgeMatch.map(JudgeAdapter.Match::evidence).orElse(found.evidence())));
        }
        int falsePositives = 0;
        int unreviewed = 0;
        Map<String, Integer> categories = new java.util.TreeMap<>();
        List<FalsePositive> classified = new ArrayList<>();
        for (int i = 0; i < actual.size(); i++) {
            if (matchedActual[i]) continue;
            var unmatched = actual.get(i);
            if (disagreements.contains(unmatched.id())) { unreviewed++; continue; }
            String content = normalize(unmatched.content());
            boolean duplicate = expected.stream().anyMatch(fact -> matches(fact, content));
            var forbidden = fixture.forbiddenMemories().stream()
                    .filter(fact -> normalize(fact.statement()).equals(content)).findFirst();
            boolean shouldBeAbsent = fixture.expectedMemories().stream()
                    .anyMatch(fact -> "absent".equals(fact.expectedStatus()) && (matches(fact, content)
                            || judgment != null && judgment.matches().stream().anyMatch(match ->
                                    match.actualId().equals(unmatched.id()) && match.expectedId().equals(fact.id()))));
            var judgedIssue = judgment == null ? java.util.Optional.<JudgeAdapter.Issue>empty() : judgment.issues().stream()
                    .filter(issue -> issue.actualId().equals(unmatched.id())).findFirst();
            if (duplicate || forbidden.isPresent() || shouldBeAbsent || judgedIssue.isPresent()) {
                falsePositives++;
                String category = forbidden.map(EvaluationFixture.ForbiddenMemory::category)
                        .orElseGet(() -> judgedIssue.map(JudgeAdapter.Issue::category)
                                .orElse(shouldBeAbsent ? "outdated_or_deleted" : "duplicate"));
                categories.merge(category, 1, Integer::sum);
                classified.add(new FalsePositive(unmatched.id(), category));
            } else unreviewed++;
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
                precision, recall, activeRecall, withinCapacity, f1, statusCorrect,
                unreviewed == 0 ? ratio(statusCorrect, tp) : null, kindCorrect,
                unreviewed == 0 && kindUnknown == 0 ? ratio(kindCorrect, tp) : null,
                Map.copyOf(categories), List.copyOf(classified),
                List.copyOf(disagreements), List.copyOf(matches));
    }

    private static boolean assign(int actualIndex, List<Actual> actual,
            List<EvaluationFixture.ExpectedMemory> expected, int[] owner, boolean[] visited,
            JudgeAdapter.Judgment judgment, List<String> disagreements) {
        String content = normalize(actual.get(actualIndex).content());
        for (int i = 0; i < expected.size(); i++) {
            var fact = expected.get(i);
            boolean judged = judgment != null && judgment.matches().stream().anyMatch(match ->
                    match.actualId().equals(actual.get(actualIndex).id()) && match.expectedId().equals(fact.id()));
            if (visited[i] || (!matches(fact, content) && !judged)) continue;
            visited[i] = true;
            if (owner[i] < 0 || (!disagreements.contains(actual.get(owner[i]).id())
                    && assign(owner[i], actual, expected, owner, visited, judgment, disagreements))) {
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

    private static boolean kindMatches(EvaluationFixture.ExpectedMemory fact, String source) {
        if ("manual_edit".equals(fact.origin())) return "user_explicit".equals(source);
        if ("inference".equals(fact.kind())) return "inferred".equals(source);
        return "conversation_fact".equals(source) || "user_explicit".equals(source);
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    private static Double ratio(int numerator, int denominator) {
        return denominator == 0 ? null : (double) numerator / denominator;
    }
}
