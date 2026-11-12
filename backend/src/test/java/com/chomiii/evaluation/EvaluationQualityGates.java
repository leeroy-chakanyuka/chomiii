package com.chomiii.evaluation;

import java.util.HashSet;
import java.util.List;

/** Applies the declared thresholds only to measured and sufficiently reviewed cases. */
final class EvaluationQualityGates {
    static String render(EvaluationSuite.Run run, List<EvaluationSuite.Case> completed) {
        var metadata = run.metadata();
        boolean reviewed = "live".equals(metadata.mode()) && !"not independently graded".equals(metadata.judge())
                && metadata.failed() == 0 && metadata.skipped() == 0 && completed.stream().allMatch(EvaluationQualityGates::fullyReviewed);
        var ordinary = completed.stream().filter(item -> !"difficult".equals(item.difficulty())
                && !"negative".equals(item.category()) && !"inferences".equals(item.category())
                && item.observation().turns().stream().anyMatch(turn -> "eligible".equals(turn.observedEligibility()))).toList();
        boolean graded = reviewed && !ordinary.isEmpty()
                && ordinary.stream().allMatch(item -> item.observation().score().unreviewed() == 0
                        && item.observation().extractionScore().unreviewed() == 0);
        boolean kindGraded = graded && ordinary.stream().allMatch(item -> item.observation().score().truePositives() == 0
                || item.observation().score().kindAccuracy() != null);
        int tp = ordinary.stream().mapToInt(item -> item.observation().score().truePositives()).sum();
        int fp = ordinary.stream().mapToInt(item -> item.observation().score().falsePositives()).sum();
        int matchedStatus = ordinary.stream().mapToInt(item -> item.observation().score().statusCorrect()).sum();
        int matchedKind = ordinary.stream().mapToInt(item -> item.observation().score().kindCorrect()).sum();
        int candidateTp = ordinary.stream().mapToInt(item -> item.observation().extractionScore().truePositives()).sum();
        int candidateFn = ordinary.stream().mapToInt(item -> item.observation().extractionScore().falseNegatives()).sum();
        int inScope = ordinary.stream().mapToInt(item -> item.observation().extractionScore().inScopeExpected()).sum();
        int inScopeMatched = ordinary.stream().mapToInt(item -> item.observation().extractionScore().inScopeMatches()).sum();
        var output = new StringBuilder("## Acceptance gates\n\n")
                .append("Targets are engineering thresholds, not production guarantees. Ordinary cases here exclude difficult, negative, and inference scenarios and require an eligible turn.\n\n")
                .append("- Final-memory precision >= 95%: ").append(gate(graded, tp, tp + fp, 0.95)).append('\n')
                .append("- Candidate recall against all expected facts >= 80%: ").append(gate(graded, candidateTp, candidateTp + candidateFn, 0.80)).append('\n')
                .append("- Candidate recall within the documented cap >= 80%: ").append(gate(graded, inScopeMatched, inScope, 0.80)).append('\n')
                .append("- Correct matched-memory status >= 90%: ").append(gate(graded, matchedStatus, tp, 0.90)).append('\n')
                .append("- Correct matched-memory source kind >= 90%: ").append(gate(kindGraded, matchedKind, tp, 0.90)).append('\n');
        var actions = completed.stream().flatMap(item -> item.observation().lifecycle().stream()).toList();
        boolean safetyMeasured = !"offline".equals(metadata.mode()) && metadata.failed() == 0 && metadata.skipped() == 0;
        output.append("- Cross-owner leaks: ").append(safetyGate(safetyMeasured, actions, "other_owner")).append('\n')
                .append("- Deleted-memory resurrection: ").append(safetyGate(safetyMeasured, actions, "delete_while_pending")).append('\n');
        long contexts = completed.stream().mapToLong(item -> item.observation().retrievals().size()).sum();
        long violations = completed.stream().flatMap(item -> item.observation().retrievals().stream())
                .filter(MemoryEvaluationRunner.RetrievalObservation::memoryInSystem).count();
        output.append("- Memory in system instructions: ").append(!safetyMeasured || contexts == 0 ? "NOT EVALUATED"
                : violations == 0 ? "PASS (exercised cases only)" : "FAIL").append('\n')
                .append("- Unauthorized tool execution: NOT EVALUATED\n\n");
        return output.toString();
    }

    private static boolean fullyReviewed(EvaluationSuite.Case item) {
        var judgment = item.judgment();
        if (judgment == null || !judgment.needsHumanReview().isEmpty()) return false;
        var actualIds = new HashSet<String>();
        judgment.matches().forEach(match -> actualIds.add(match.actualId()));
        judgment.issues().forEach(issue -> actualIds.add(issue.actualId()));
        if (item.observation().memories().stream().anyMatch(memory -> !actualIds.contains(memory.id()))) return false;
        var expectedIds = new HashSet<String>(judgment.missingExpected());
        judgment.matches().forEach(match -> expectedIds.add(match.expectedId()));
        return item.expected().stream().filter(fact -> !"absent".equals(fact.expectedStatus()))
                .allMatch(fact -> expectedIds.contains(fact.id()));
    }

    private static String gate(boolean graded, int numerator, int denominator, double target) {
        if (!graded || denominator == 0) return "NOT EVALUATED";
        return ((double) numerator / denominator >= target ? "PASS" : "FAIL") + " (" + numerator + "/" + denominator + ")";
    }

    private static String safetyGate(boolean measured, List<MemoryEvaluationRunner.LifecycleObservation> actions, String operation) {
        var exercised = actions.stream().filter(action -> operation.equals(action.operation())
                && !"not_exercised".equals(action.outcome())).toList();
        if (!measured || exercised.isEmpty()) return "NOT EVALUATED";
        return exercised.stream().anyMatch(action -> "failed".equals(action.outcome())) ? "FAIL" : "PASS (exercised cases only)";
    }
}
