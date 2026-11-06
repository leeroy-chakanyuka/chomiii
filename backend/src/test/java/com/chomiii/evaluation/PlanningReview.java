package com.chomiii.evaluation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Applies explicit reviewer decisions. Missing decisions never become passing scores. */
final class PlanningReview {
    enum Metric {
        PRIMARY_GOAL, SECONDARY_GOALS, NECESSARY_STEPS, STEP_PRECISION, DEPENDENCIES,
        MEMORY_RELEVANCE, MEMORY_FACTUALITY, CLARIFICATION, CAPABILITY_VALIDITY,
        APPROVAL, OVERPLANNING, SAFETY, FALSE_COMPLETION
    }
    record Grade(Metric metric, int correct, int total, String reason, List<String> evidence) {}
    record Decision(String fixtureId, int repetition, String condition, List<Grade> grades) {}
    record Review(String reviewer, boolean independent, List<Decision> decisions) {}
    record Score(int correct, int total, Double value) {}
    record Result(String reviewer, boolean declaredIndependent, Map<String, Map<Metric, Score>> scores) {}

    Result apply(PlanningEvaluationSuite.Run run, Review review) {
        if (review == null || review.reviewer() == null || review.reviewer().isBlank()
                || review.decisions() == null) throw invalid();
        Map<String, PlanningEvaluationSuite.Case> cases = new HashMap<>();
        for (var item : run.cases()) cases.put(key(item.fixtureId(), item.repetition(), item.condition()), item);
        Map<String, Decision> decisions = new HashMap<>();
        for (var decision : review.decisions()) {
            if (decision == null || decision.grades() == null) throw invalid();
            String key = key(decision.fixtureId(), decision.repetition(), decision.condition());
            var item = cases.get(key);
            if (item == null || decisions.putIfAbsent(key, decision) != null || "skipped".equals(item.status())) throw invalid();
            var seen = new HashSet<Metric>();
            String evidenceSource = item.expectations().toString() + " " + item.selectedMemories() + " "
                    + item.plan() + " " + item.providerOutput();
            for (var grade : decision.grades()) {
                if (grade == null || grade.metric() == null || !seen.add(grade.metric())
                        || grade.total() < 0 || grade.correct() < 0 || grade.correct() > grade.total()
                        || grade.reason() == null || grade.reason().isBlank() || grade.evidence() == null
                        || grade.total() > 0 && grade.evidence().isEmpty()) throw invalid();
                for (String quote : grade.evidence()) {
                    if (quote == null || quote.length() < 4 || !evidenceSource.contains(quote)) throw invalid();
                }
            }
        }
        var byCondition = new HashMap<String, Map<Metric, Score>>();
        for (String condition : List.of("oracle", "actual_m2")) {
            var relevant = run.cases().stream().filter(item -> condition.equals(item.condition())).toList();
            var scores = new java.util.EnumMap<Metric, Score>(Metric.class);
            for (Metric metric : Metric.values()) {
                boolean complete = review.independent() && !relevant.isEmpty();
                int correct = 0;
                int total = 0;
                for (var item : relevant) {
                    Decision decision = decisions.get(key(item.fixtureId(), item.repetition(), item.condition()));
                    var grade = decision == null ? null : decision.grades().stream()
                            .filter(value -> value.metric() == metric).findFirst().orElse(null);
                    if (grade == null) { complete = false; continue; }
                    correct += grade.correct();
                    total += grade.total();
                }
                scores.put(metric, new Score(correct, total, complete && total > 0 ? (double) correct / total : null));
            }
            byCondition.put(condition, Map.copyOf(scores));
        }
        return new Result(review.reviewer().strip(), review.independent(), Map.copyOf(byCondition));
    }

    static Review template(PlanningEvaluationSuite.Run run) {
        var decisions = new ArrayList<Decision>();
        for (var item : run.cases()) {
            if (!"skipped".equals(item.status())) {
                decisions.add(new Decision(item.fixtureId(), item.repetition(), item.condition(), List.of()));
            }
        }
        return new Review("", false, List.copyOf(decisions));
    }

    private static String key(String fixtureId, int repetition, String condition) {
        if (fixtureId == null || repetition < 1 || condition == null
                || !Set.of("oracle", "actual_m2").contains(condition)) throw invalid();
        return fixtureId + "#" + repetition + "/" + condition;
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid planning review"); }
}
