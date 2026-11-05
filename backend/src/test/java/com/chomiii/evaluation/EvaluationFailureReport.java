package com.chomiii.evaluation;

import java.util.HashSet;
import java.util.stream.Collectors;

final class EvaluationFailureReport {
    static String render(EvaluationSuite.Run run) {
        var result = new StringBuilder("# Evaluation findings\n\n");
        for (var item : run.cases()) {
            if (item.observation() == null) {
                result.append("- ").append(item.fixtureId()).append(" [").append(item.status())
                        .append("]: ").append(item.error()).append('\n');
                continue;
            }
            var observation = item.observation();
            boolean unexpected = observation.score().falsePositives() > 0 || observation.score().falseNegatives() > 0
                    || observation.score().unreviewed() > 0 || !"completed".equals(item.status())
                    || observation.extractionScore().falsePositives() > 0 || observation.extractionScore().falseNegatives() > 0
                    || observation.retrievals().stream().anyMatch(retrieval -> retrieval.memoryInSystem()
                            || !retrieval.contextAsUser() || retrievalMismatch(observation, retrieval))
                    || observation.lifecycle().stream().anyMatch(action -> !"passed".equals(action.outcome()))
                    || observation.turns().stream().anyMatch(turn -> !turn.expectedEligibility().equals(turn.observedEligibility())
                            || (turn.extractionAttempted() && !"accepted_or_empty".equals(turn.validationOutcome())));
            if (!unexpected) continue;
            result.append("\n## ").append(item.fixtureId()).append(" (repetition ").append(item.repetition()).append(")\n\n")
                    .append("- Execution: ").append(item.status()).append('\n')
                    .append("- Expected facts: ").append(item.expected().stream().map(EvaluationFixture.ExpectedMemory::statement).toList()).append('\n')
                    .append("- Forbidden facts: ").append(item.forbidden().stream().map(EvaluationFixture.ForbiddenMemory::statement).toList()).append('\n')
                    .append("- Stored memories: ").append(observation.memories().stream().map(MemoryEvaluationRunner.StoredMemory::content).toList()).append('\n')
                    .append("- False positives: ").append(observation.score().falsePositiveCategories()).append('\n')
                    .append("- Extraction issues: ").append(observation.extractionScore().falsePositiveCategories())
                    .append(", missing ").append(observation.extractionScore().falseNegatives()).append('\n')
                    .append("- Missing expected facts: ").append(observation.score().falseNegatives()).append('\n')
                    .append("- Unreviewed memories: ").append(observation.score().unreviewed()).append('\n')
                    .append("- Judge disagreements: ").append(observation.score().disagreements()).append('\n')
                    .append("- Eligibility: ").append(observation.turns().stream().map(MemoryEvaluationRunner.TurnObservation::observedEligibility).toList()).append('\n')
                    .append("- Validation: ").append(observation.turns().stream().map(MemoryEvaluationRunner.TurnObservation::validationOutcome).toList()).append('\n')
                    .append("- Retrieval: ").append(observation.retrievals()).append('\n')
                    .append("- Lifecycle: ").append(observation.lifecycle()).append('\n');
        }
        return result.toString();
    }

    private static boolean retrievalMismatch(MemoryEvaluationRunner.Result result,
            MemoryEvaluationRunner.RetrievalObservation retrieval) {
        var selectedFacts = result.score().matches().stream()
                .filter(match -> retrieval.selectedMemoryIds().contains(match.actualId()))
                .map(MemoryScorer.Match::expectedId).collect(Collectors.toSet());
        return !selectedFacts.equals(new HashSet<>(retrieval.expectedMemoryIds()))
                || retrieval.selectedMemoryIds().size() != selectedFacts.size();
    }
}
