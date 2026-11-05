package com.chomiii.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.core.type.TypeReference;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class EvaluationReportWriter {
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)\\b(password|passphrase|api[ -]?key|secret[ -]?key|credit[ -]?card|cvv|otp)\\b");
    private final ObjectMapper json = new ObjectMapper();

    Path write(EvaluationSuite.Run run, Path root, List<String> secrets) throws IOException {
        return write(run, root, secrets, null);
    }

    Path write(EvaluationSuite.Run run, Path root, List<String> secrets, Path previous) throws IOException {
        Path directory = root.resolve(run.metadata().runId()).normalize();
        if (!directory.startsWith(root.normalize())) throw new IllegalArgumentException("Invalid report path");
        Files.createDirectories(directory);
        writeJson(directory.resolve("run-metadata.json"), json.valueToTree(run.metadata()), secrets);
        writeJson(directory.resolve("results.json"), json.valueToTree(run.cases()), secrets);
        writeJson(directory.resolve("manual-review-template.json"), reviewTemplate(run), secrets);
        Files.writeString(directory.resolve("summary.md"), redactLines(summary(run) + comparison(run, previous), secrets));
        Files.writeString(directory.resolve("failures.md"), redactLines(EvaluationFailureReport.render(run), secrets));
        Files.writeString(directory.resolve("manual-review.md"), redactLines(manualReview(run), secrets));
        return directory;
    }

    private String comparison(EvaluationSuite.Run current, Path previous) {
        if (previous == null) return "";
        try {
            var prior = json.readValue(previous.resolve("run-metadata.json").toFile(), EvaluationSuite.Metadata.class);
            List<EvaluationSuite.Case> priorCases = json.readValue(previous.resolve("results.json").toFile(), new TypeReference<>() {});
            var now = current.metadata();
            if (!prior.datasetSha256().equals(now.datasetSha256()) || !prior.mode().equals(now.mode())
                    || !prior.provider().equals(now.provider()) || !prior.model().equals(now.model())
                    || !prior.judge().equals(now.judge()) || !prior.database().equals(now.database())
                    || prior.timeoutSeconds() != now.timeoutSeconds() || prior.maxProviderCalls() != now.maxProviderCalls()
                    || prior.workingTreeDirty() || now.workingTreeDirty()) {
                return "\n## Historical comparison\n\nNot comparable: dataset, mode, model, judge, or working-tree state differs.\n";
            }
            if (prior.failed() != 0 || prior.skipped() != 0 || now.failed() != 0 || now.skipped() != 0
                    || prior.selectedFixtures() != now.selectedFixtures() || prior.repetitions() != now.repetitions()
                    || !priorCases.stream().map(item -> item.fixtureId() + "#" + item.repetition()).toList().equals(
                            current.cases().stream().map(item -> item.fixtureId() + "#" + item.repetition()).toList())) {
                return "\n## Historical comparison\n\nNot comparable: coverage or execution outcomes differ.\n";
            }
            int previousTp = priorCases.stream().mapToInt(item -> item.observation().score().truePositives()).sum();
            int currentTp = current.cases().stream().mapToInt(item -> item.observation().score().truePositives()).sum();
            return "\n## Historical comparison\n\nMatched final-memory facts: prior " + previousTp
                    + ", current " + currentTp + ". Compare individual results before interpreting the difference.\n";
        } catch (Exception unavailable) {
            return "\n## Historical comparison\n\nUnavailable: the prior report could not be read.\n";
        }
    }

    private ArrayNode reviewTemplate(EvaluationSuite.Run run) {
        ArrayNode rows = json.createArrayNode();
        for (var item : run.cases()) {
            if (item.observation() == null) continue;
            ObjectNode row = rows.addObject();
            row.put("fixtureId", item.fixtureId());
            row.put("repetition", item.repetition());
            for (String name : List.of("matches", "unsupported", "missingExpected", "contradictions",
                    "wrongPersonAttributions", "overInferences", "answers")) row.putArray(name);
            ArrayNode review = row.putArray("needsHumanReview");
            item.observation().memories().forEach(memory -> review.add(memory.id()));
            row.put("summary", "Human review pending");
        }
        return rows;
    }

    private static String manualReview(EvaluationSuite.Run run) {
        var output = new StringBuilder("# Manual review\n\n")
                .append("Read each synthetic fixture by ID. Compare its source turns, expected and forbidden facts, ")
                .append("and the recorded memories and answers in `results.json`.\n")
                .append("Edit `manual-review-template.json`; leave unresolved memory IDs in `needsHumanReview`. ")
                .append("A positive match needs a verbatim source quote or an explicit user edit.\n\n");
        for (var item : run.cases()) {
            if (item.observation() == null) continue;
            output.append("## ").append(item.fixtureId()).append(" (repetition ").append(item.repetition()).append(")\n\n")
                    .append("- Expected IDs: ").append(item.expected().stream().map(EvaluationFixture.ExpectedMemory::id).toList()).append('\n')
                    .append("- Stored IDs: ").append(item.observation().memories().stream().map(MemoryEvaluationRunner.StoredMemory::id).toList()).append('\n')
                    .append("- Follow-up IDs: ").append(item.observation().retrievals().stream().map(MemoryEvaluationRunner.RetrievalObservation::id).toList()).append("\n\n");
        }
        return output.toString();
    }

    private void writeJson(Path path, JsonNode value, List<String> secrets) throws IOException {
        json.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), scrub(value, secrets));
    }

    private JsonNode scrub(JsonNode node, List<String> secrets) {
        if (node.isTextual()) return TextNode.valueOf(redact(node.asText(), secrets));
        if (node.isArray()) {
            ArrayNode result = json.createArrayNode();
            node.forEach(item -> result.add(scrub(item, secrets)));
            return result;
        }
        if (node.isObject()) {
            ObjectNode result = json.createObjectNode();
            node.fields().forEachRemaining(entry -> result.set(entry.getKey(), scrub(entry.getValue(), secrets)));
            return result;
        }
        return node;
    }

    static String redact(String value, List<String> secrets) {
        if (value == null) return null;
        String result = value;
        for (String secret : secrets) {
            if (secret != null && !secret.isBlank()) result = result.replace(secret, "[REDACTED]");
        }
        if (SENSITIVE.matcher(result).find()) return "[REDACTED: sensitive content]";
        return result.replaceAll("AIza[0-9A-Za-z_-]{20,}", "[REDACTED]");
    }

    private static String redactLines(String text, List<String> secrets) {
        return java.util.Arrays.stream(text.split("\n", -1)).map(line -> redact(line, secrets))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static String summary(EvaluationSuite.Run run) {
        var metadata = run.metadata();
        var result = new StringBuilder("# M2 evaluation report\n\n")
                .append("- Commit: ").append(metadata.commit()).append('\n')
                .append("- Working tree dirty: ").append(metadata.workingTreeDirty()).append('\n')
                .append("- Dataset: ").append(metadata.datasetVersion()).append(" (")
                .append(metadata.datasetSha256()).append(")\n")
                .append("- Mode: ").append(metadata.mode()).append('\n')
                .append("- Provider: ").append(metadata.provider()).append(" / ").append(metadata.model()).append('\n')
                .append("- Judge: ").append(metadata.judge()).append('\n')
                .append("- Database: ").append(metadata.database()).append('\n')
                .append("- Selected fixtures: ").append(metadata.selectedFixtures()).append('\n')
                .append("- Attempts: ").append(metadata.attempted()).append(", completed: ")
                .append(metadata.completed()).append(", skipped: ").append(metadata.skipped())
                .append(", failed: ").append(metadata.failed()).append("\n\n");
        if (!"live".equals(metadata.mode())) {
            result.append("Scripted responses test the application pipeline. These numbers do not measure model quality.\n\n");
        }
        var completed = run.cases().stream().filter(item -> "completed".equals(item.status())).toList();
        int tp = completed.stream().mapToInt(item -> item.observation().score().truePositives()).sum();
        int fp = completed.stream().mapToInt(item -> item.observation().score().falsePositives()).sum();
        int fn = completed.stream().mapToInt(item -> item.observation().score().falseNegatives()).sum();
        int unknown = completed.stream().mapToInt(item -> item.observation().score().unreviewed()).sum();
        int active = completed.stream().mapToInt(item -> item.observation().score().activeMatches()).sum();
        int capacityMatched = completed.stream().mapToInt(item -> item.observation().score().inScopeMatches()).sum();
        int capacityExpected = completed.stream().mapToInt(item -> item.observation().score().inScopeExpected()).sum();
        int statusCorrect = completed.stream().mapToInt(item -> item.observation().score().statusCorrect()).sum();
        int kindCorrect = completed.stream().mapToInt(item -> item.observation().score().kindCorrect()).sum();
        boolean graded = unknown == 0 && metadata.failed() == 0 && metadata.skipped() == 0;
        boolean kindGraded = graded && completed.stream().allMatch(item -> item.observation().score().truePositives() == 0
                || item.observation().score().kindAccuracy() != null);
        result.append("## Final-memory metrics\n\n")
                .append("- Precision: ").append(metric(graded, tp, tp + fp)).append('\n')
                .append("- Recall: ").append(metric(graded, tp, tp + fn)).append('\n')
                .append("- Active-memory recall: ").append(metric(graded, active, tp + fn)).append('\n')
                .append("- Within-capacity recall: ").append(metric(graded, capacityMatched, capacityExpected)).append('\n')
                .append("- Correct final status among matched facts: ").append(metric(graded, statusCorrect, tp)).append('\n')
                .append("- Correct source kind among matched facts: ").append(metric(kindGraded, kindCorrect, tp)).append('\n')
                .append("- F1: ").append(f1(graded, tp, fp, fn)).append('\n')
                .append("- Unreviewed memories: ").append(unknown).append("\n\n");
        var eligibleCases = completed.stream().filter(item -> item.observation().turns().stream()
                .anyMatch(turn -> "eligible".equals(turn.observedEligibility()))).toList();
        int eligibleTp = eligibleCases.stream().mapToInt(item -> item.observation().score().truePositives()).sum();
        int eligibleFp = eligibleCases.stream().mapToInt(item -> item.observation().score().falsePositives()).sum();
        int eligibleFn = eligibleCases.stream().mapToInt(item -> item.observation().score().falseNegatives()).sum();
        int eligibleUnknown = eligibleCases.stream().mapToInt(item -> item.observation().score().unreviewed()).sum();
        result.append("- Eligible-case precision: ").append(metric(graded && eligibleUnknown == 0, eligibleTp, eligibleTp + eligibleFp)).append('\n')
                .append("- Eligible-case recall: ").append(metric(graded && eligibleUnknown == 0, eligibleTp, eligibleTp + eligibleFn))
                .append(" (cases with at least one eligible user turn)\n\n");
        int extracted = completed.stream().mapToInt(item -> item.observation().extractionScore().truePositives()).sum();
        int extractMissing = completed.stream().mapToInt(item -> item.observation().extractionScore().falseNegatives()).sum();
        int extractUnknown = completed.stream().mapToInt(item -> item.observation().extractionScore().unreviewed()).sum();
        int extractFalse = completed.stream().mapToInt(item -> item.observation().extractionScore().falsePositives()).sum();
        int extractInScope = completed.stream().mapToInt(item -> item.observation().extractionScore().inScopeExpected()).sum();
        int extractInScopeHits = completed.stream().mapToInt(item -> item.observation().extractionScore().inScopeMatches()).sum();
        var extractionCategories = new java.util.TreeMap<String, Integer>();
        completed.forEach(item -> item.observation().extractionScore().falsePositiveCategories()
                .forEach((category, count) -> extractionCategories.merge(category, count, Integer::sum)));
        int overInference = extractionCategories.getOrDefault("over_inference", 0)
                + extractionCategories.getOrDefault("overInferences", 0);
        result.append("## Extraction and retrieval\n\n")
                .append("- Accepted-candidate precision: ")
                .append(metric(graded && extractUnknown == 0, extracted, extracted + extractFalse)).append('\n')
                .append("- Accepted-candidate recall: ")
                .append(metric(graded && extractUnknown == 0, extracted, extracted + extractMissing)).append('\n')
                .append("- Accepted-candidate within-capacity recall: ")
                .append(metric(graded && extractUnknown == 0, extractInScopeHits, extractInScope)).append('\n')
                .append("- Known over-inference rate among accepted candidates: ")
                .append(metric(graded && extractUnknown == 0, overInference, extracted + extractFalse)).append('\n')
                .append("- Candidate issue categories: ").append(extractionCategories).append('\n')
                .append("- Retrieval precision and recall: ").append(retrievalSummary(completed)).append('\n')
                .append("- Literal evidence for learned stored memories: ").append(literalGrounding(completed)).append('\n')
                .append("- Grounded-answer accuracy: ").append(answerAccuracy(completed)).append('\n')
                .append("- Semantic grounding: ").append(metadata.judge().equals("not independently graded")
                        ? "not independently graded" : metadata.judge().startsWith("human review")
                        ? "human-reviewed where decisions were supplied" : "judge-assisted; not ground truth").append('\n')
                .append("- Literal evidence matching is not proof that a memory is supported.\n\n")
                .append("## Pipeline and usage\n\n");
        long attempted = completed.stream().flatMap(item -> item.observation().turns().stream())
                .filter(MemoryEvaluationRunner.TurnObservation::extractionAttempted).count();
        long skipped = completed.stream().flatMap(item -> item.observation().turns().stream())
                .filter(turn -> !"eligible".equals(turn.observedEligibility())).count();
        var eligibilityCounts = completed.stream().flatMap(item -> item.observation().turns().stream())
                .collect(java.util.stream.Collectors.groupingBy(MemoryEvaluationRunner.TurnObservation::observedEligibility,
                        java.util.TreeMap::new, java.util.stream.Collectors.counting()));
        var validationCounts = completed.stream().flatMap(item -> item.observation().turns().stream())
                .collect(java.util.stream.Collectors.groupingBy(MemoryEvaluationRunner.TurnObservation::validationOutcome,
                        java.util.TreeMap::new, java.util.stream.Collectors.counting()));
        result.append("- Extraction attempts: ").append(attempted).append('\n')
                .append("- Pre-extraction skips or rejected chats: ").append(skipped).append('\n')
                .append("- Eligibility outcomes: ").append(eligibilityCounts).append('\n')
                .append("- Validation outcomes: ").append(validationCounts).append('\n')
                .append("- Provider call reservations (may fail before network): ").append(metadata.providerCalls()).append('\n')
                .append("- Total evaluation time: ").append(metadata.elapsedMillis()).append(" ms\n")
                .append("- Token usage: ").append(metadata.tokenUsage()).append('\n')
                .append("- Judge token usage: ").append(metadata.judgeTokenUsage()).append('\n')
                .append("- Cost: ").append(metadata.cost()).append('\n')
                .append("- First visible browser text: not measured\n")
                .append("- Latency percentiles: ").append("live".equals(metadata.mode())
                        ? percentileSummary(completed) : "not measured for scripted responses").append('\n')
                .append("- Actual validation and persistence durations: not exposed by the application.\n\n");
        result.append("## Critical observations\n\n")
                .append("- Follow-up context checks: ")
                .append(completed.stream().mapToInt(item -> item.observation().retrievals().size()).sum())
                .append("; memory in system instructions: ")
                .append(completed.stream().flatMap(item -> item.observation().retrievals().stream())
                        .filter(MemoryEvaluationRunner.RetrievalObservation::memoryInSystem).count()).append('\n')
                .append("- Cross-owner checks exercised: ")
                .append(completed.stream().flatMap(item -> item.observation().lifecycle().stream())
                        .filter(action -> "other_owner".equals(action.operation()) && !"not_exercised".equals(action.outcome())).count())
                .append("; failed: ")
                .append(completed.stream().flatMap(item -> item.observation().lifecycle().stream())
                        .filter(action -> "other_owner".equals(action.operation()) && "failed".equals(action.outcome())).count()).append('\n')
                .append("- Stale-deletion checks exercised: ")
                .append(completed.stream().flatMap(item -> item.observation().lifecycle().stream())
                        .filter(action -> "delete_while_pending".equals(action.operation()) && !"not_exercised".equals(action.outcome())).count())
                .append("; failed: ")
                .append(completed.stream().flatMap(item -> item.observation().lifecycle().stream())
                        .filter(action -> "delete_while_pending".equals(action.operation()) && "failed".equals(action.outcome())).count()).append('\n')
                .append("- Known unsupported active memories: ").append(unsupportedActive(completed)).append('\n')
                .append("- Known sensitive retained memories: ")
                .append(completed.stream().mapToInt(item -> item.observation().score().falsePositiveCategories()
                        .getOrDefault("sensitive", 0)).sum()).append('\n')
                .append("- Tool execution: not applicable; M2 has no tool executor.\n")
                .append("- These counts cover only exercised cases; they are not a security certification.\n\n");
        if (metadata.repetitions() > 1) result.append(repeatability(run.cases()));
        result.append(EvaluationQualityGates.render(run, completed))
                .append("See `failures.md` and `results.json` for individual outcomes.\n");
        return result.toString();
    }

    private static long unsupportedActive(List<EvaluationSuite.Case> cases) {
        long count = 0;
        for (var item : cases) {
            var observation = item.observation();
            for (var falsePositive : observation.score().classifiedFalsePositives()) {
                if ("duplicate".equals(falsePositive.category()) || "outdated_or_deleted".equals(falsePositive.category())) continue;
                if (observation.memories().stream().anyMatch(memory -> memory.id().equals(falsePositive.actualId())
                        && "active".equals(memory.status()))) count++;
            }
            for (var match : observation.score().matches()) {
                boolean shouldSuggest = item.expected().stream().anyMatch(fact -> fact.id().equals(match.expectedId())
                        && "suggested".equals(fact.expectedStatus()));
                if (shouldSuggest && observation.memories().stream().anyMatch(memory -> memory.id().equals(match.actualId())
                        && "active".equals(memory.status()))) count++;
            }
        }
        return count;
    }

    private static String answerAccuracy(List<EvaluationSuite.Case> cases) {
        int total = 0;
        int correct = 0;
        for (var item : cases) {
            if (item.judgment() == null || item.judgment().answers().size() != item.observation().retrievals().size()) {
                if (!item.observation().retrievals().isEmpty()) return "not graded";
                continue;
            }
            for (var answer : item.judgment().answers()) {
                if ("needs_review".equals(answer.verdict())) return "not graded: human review required";
                total++;
                if ("correct".equals(answer.verdict())) correct++;
            }
        }
        return metric(true, correct, total);
    }

    private static String repeatability(List<EvaluationSuite.Case> cases) {
        var groups = cases.stream().collect(java.util.stream.Collectors.groupingBy(EvaluationSuite.Case::fixtureId,
                java.util.TreeMap::new, java.util.stream.Collectors.toList()));
        var output = new StringBuilder("## Repetitions\n\nSmall samples do not establish production reliability.\n\n");
        groups.forEach((id, values) -> {
            var completed = values.stream().filter(item -> "completed".equals(item.status()) && item.observation() != null).toList();
            var recalls = completed.stream().map(item -> item.observation().score().recall())
                    .filter(java.util.Objects::nonNull).sorted().toList();
            long extractionRuns = completed.stream().filter(item -> item.observation().turns().stream()
                    .anyMatch(MemoryEvaluationRunner.TurnObservation::extractionAttempted)).count();
            long extractionSuccesses = completed.stream().filter(item -> item.observation().turns().stream()
                    .anyMatch(turn -> turn.acceptedCandidates() > 0)).count();
            var statuses = completed.stream().map(item -> item.observation().memories().stream()
                    .map(memory -> memory.source() + ":" + memory.status()).sorted().toList()).toList();
            var inferenceIssues = completed.stream().map(item -> item.observation().extractionScore()
                    .falsePositiveCategories().getOrDefault("over_inference", 0)).toList();
            output.append("- ").append(id).append(": completed ").append(completed.size()).append('/')
                    .append(values.size()).append(", failed ")
                    .append(values.stream().filter(item -> "failed".equals(item.status())).count())
                    .append(", extraction attempts ").append(extractionRuns).append(", with accepted candidates ")
                    .append(extractionSuccesses).append("; recall ");
            if (recalls.size() != values.size() || recalls.isEmpty()) output.append("not fully graded");
            else output.append(String.format(Locale.ROOT, "mean %.3f, min %.3f, max %.3f, range %.3f",
                    recalls.stream().mapToDouble(Double::doubleValue).average().orElseThrow(),
                    recalls.getFirst(), recalls.getLast(), recalls.getLast() - recalls.getFirst()));
            output.append("; status variants ").append(statuses.stream().distinct().count())
                    .append(", known over-inference counts ").append(inferenceIssues);
            output.append('\n');
        });
        return output.append('\n').toString();
    }

    private static String literalGrounding(List<EvaluationSuite.Case> cases) {
        int learned = 0;
        int evidenced = 0;
        for (var item : cases) {
            var observation = item.observation();
            for (var memory : observation.memories()) {
                if ("user_explicit".equals(memory.source())) continue;
                learned++;
                var candidate = observation.turns().stream().flatMap(turn -> turn.candidates().stream())
                        .filter(value -> value.accepted() && memory.content().equals(value.content())).findFirst();
                if (candidate.isEmpty() || candidate.get().literalEvidenceFound() == null) return "not measured";
                if (candidate.get().literalEvidenceFound()) evidenced++;
            }
        }
        return metric(true, evidenced, learned);
    }

    private static String retrievalSummary(List<EvaluationSuite.Case> cases) {
        int relevant = 0;
        int returned = 0;
        int hit = 0;
        int unavailable = 0;
        for (var item : cases) {
            var observation = item.observation();
            var matches = observation.score().matches();
            for (var check : observation.retrievals()) {
                for (String expected : check.expectedMemoryIds()) {
                    var match = matches.stream().filter(value -> value.expectedId().equals(expected)).findFirst();
                    if (match.isEmpty()) { unavailable++; continue; }
                    boolean active = observation.memories().stream().anyMatch(memory ->
                            memory.id().equals(match.get().actualId()) && "active".equals(memory.status()));
                    if (!active) { unavailable++; continue; }
                    relevant++;
                    if (check.selectedMemoryIds().contains(match.get().actualId())) hit++;
                }
                returned += check.selectedMemoryIds().size();
                for (String id : check.selectedMemoryIds()) {
                    boolean knownMatch = matches.stream().anyMatch(match -> match.actualId().equals(id));
                    boolean knownFalse = observation.score().classifiedFalsePositives().stream()
                            .anyMatch(falsePositive -> falsePositive.actualId().equals(id));
                    if (!knownMatch && !knownFalse) {
                        return "not graded: at least one retrieved memory needs semantic review";
                    }
                }
            }
        }
        return "precision " + metric(true, hit, returned) + ", recall " + metric(true, hit, relevant)
                + "; " + unavailable + " expected facts unavailable before retrieval";
    }

    private static String percentileSummary(List<EvaluationSuite.Case> cases) {
        var turns = cases.stream().flatMap(item -> item.observation().turns().stream()).toList();
        var retrievals = cases.stream().flatMap(item -> item.observation().retrievals().stream()).toList();
        return "request processing " + percentiles(turns.stream().map(MemoryEvaluationRunner.TurnObservation::requestProcessingMillis).toList())
                + "; first provider token " + percentiles(turns.stream().map(MemoryEvaluationRunner.TurnObservation::firstProviderTokenMillis).toList())
                + "; main provider stream " + percentiles(turns.stream().map(MemoryEvaluationRunner.TurnObservation::mainCompletionMillis).toList())
                + "; scheduling delay " + percentiles(turns.stream().map(MemoryEvaluationRunner.TurnObservation::schedulingDelayMillis).toList())
                + "; extraction stream " + percentiles(turns.stream().map(MemoryEvaluationRunner.TurnObservation::extractionMillis).toList())
                + "; retrieval " + percentiles(retrievals.stream().map(MemoryEvaluationRunner.RetrievalObservation::retrievalMillis).toList());
    }

    private static String percentiles(List<Long> values) {
        var durations = new ArrayList<>(values.stream().filter(java.util.Objects::nonNull).toList());
        durations.sort(Long::compareTo);
        if (durations.size() < 5) return "not enough samples (n=" + durations.size() + ")";
        String p50 = durations.get((int) Math.ceil(0.50 * durations.size()) - 1) + " ms";
        String p95 = durations.size() >= 20 ? durations.get((int) Math.ceil(0.95 * durations.size()) - 1) + " ms"
                : "not enough samples";
        return "p50 " + p50 + ", p95 " + p95 + " (n=" + durations.size() + ")";
    }

    private static String metric(boolean graded, int numerator, int denominator) {
        return !graded || denominator == 0 ? "not measured" : String.format(Locale.ROOT, "%.3f (%d/%d)",
                (double) numerator / denominator, numerator, denominator);
    }

    private static String f1(boolean graded, int tp, int fp, int fn) {
        if (!graded || tp == 0 || tp + fp == 0 || tp + fn == 0) return "not measured";
        return String.format(Locale.ROOT, "%.3f", (double) (2 * tp) / (2 * tp + fp + fn));
    }
}
