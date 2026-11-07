package com.chomiii.planning;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Parses the model proposal and derives all capability state in Java. */
public final class PlanParser {
    private static final int MAX_OUTPUT = 20_000;
    private static final java.util.regex.Pattern COMPLETION_CLAIM = java.util.regex.Pattern.compile(
            "(?i)\\b(?:(?:i|we|chomiii|the assistant)\\s+(?:(?:have|has)\\s+)?(?:already\\s+)?"
            + "|(?:has|have|was|were)\\s+(?:already\\s+)?(?:been\\s+)?)"
            + "(?:sent|emailed|scheduled|booked|ordered|purchased|created|updated|changed|cancelled|canceled|rescheduled)\\b");
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();

    public record Draft(String goal, String summary, List<DraftMemory> memoryReferences,
            List<String> constraints, List<DraftQuestion> missingInformation, List<DraftStep> steps) {}
    public record DraftMemory(String id, String relevance) {}
    public record DraftQuestion(String id, String question, String kind, List<String> blocks) {}
    public record DraftStep(String id, String title, String capability, List<String> dependsOn) {}

    public Plan parse(String output, Set<UUID> authorizedMemoryIds) {
        return parse(output, authorizedMemoryIds, CapabilityCatalog.all().stream()
                .map(CapabilityCatalog.Capability::id).collect(java.util.stream.Collectors.toSet()));
    }

    public Plan parse(String output, Set<UUID> authorizedMemoryIds, Set<String> allowedCapabilities) {
        if (output == null || output.length() > MAX_OUTPUT) throw invalid();
        try {
            Draft draft = JSON.readValue(output, Draft.class);
            return validate(draft, Set.copyOf(authorizedMemoryIds), Set.copyOf(allowedCapabilities));
        } catch (com.fasterxml.jackson.core.JsonProcessingException | NullPointerException invalid) {
            throw invalid();
        }
    }

    private Plan validate(Draft draft, Set<UUID> authorizedMemoryIds, Set<String> allowedCapabilities) {
        String goal = text(draft.goal(), 300);
        String summary = text(draft.summary(), 1_000);
        limit(draft.memoryReferences(), 5);
        limit(draft.constraints(), 10);
        limit(draft.missingInformation(), 5);
        limit(draft.steps(), 12);
        if (draft.steps().isEmpty() && draft.missingInformation().isEmpty()) throw invalid();

        var references = new ArrayList<Plan.MemoryReference>();
        var seenMemories = new HashSet<UUID>();
        for (DraftMemory memory : draft.memoryReferences()) {
            UUID id = UUID.fromString(memory.id());
            if (!authorizedMemoryIds.contains(id) || !seenMemories.add(id)) throw invalid();
            references.add(new Plan.MemoryReference(id, text(memory.relevance(), 300)));
        }
        List<String> constraints = draft.constraints().stream().map(value -> text(value, 300)).toList();
        Map<String, DraftStep> steps = new java.util.LinkedHashMap<>();
        for (DraftStep step : draft.steps()) {
            id(step.id());
            text(step.title(), 300);
            CapabilityCatalog.get(step.capability());
            if (!allowedCapabilities.contains(step.capability())) throw invalid();
            limit(step.dependsOn(), 10);
            if (steps.putIfAbsent(step.id(), step) != null) throw invalid();
        }
        for (DraftStep step : steps.values()) {
            validateReferences(step.dependsOn(), steps.keySet());
        }
        var visiting = new HashSet<String>();
        var done = new HashSet<String>();
        var ordered = new ArrayList<String>();
        for (String id : steps.keySet()) visit(id, steps, visiting, done, ordered);

        var questions = new ArrayList<Plan.Clarification>();
        var seenQuestions = new HashSet<String>();
        var blocked = new HashSet<String>();
        for (DraftQuestion question : draft.missingInformation()) {
            id(question.id());
            if (!seenQuestions.add(question.id()) || !Set.of("planning", "execution", "optional", "future_lookup")
                    .contains(question.kind())) throw invalid();
            limit(question.blocks(), 12);
            validateReferences(question.blocks(), steps.keySet());
            if ("optional".equals(question.kind()) && !question.blocks().isEmpty()) throw invalid();
            if (!"optional".equals(question.kind())) blocked.addAll(question.blocks());
            if ("planning".equals(question.kind()) && question.blocks().isEmpty()) blocked.addAll(steps.keySet());
            questions.add(new Plan.Clarification(question.id(), text(question.question(), 500),
                    question.kind(), question.blocks()));
        }
        for (DraftStep step : steps.values()) {
            if (!"available_for_planning".equals(CapabilityCatalog.get(step.capability()).availability())) blocked.add(step.id());
        }
        boolean changed;
        do {
            changed = false;
            for (DraftStep step : steps.values()) {
                if (step.dependsOn().stream().anyMatch(blocked::contains)) changed |= blocked.add(step.id());
            }
        } while (changed);
        var resultSteps = new ArrayList<Plan.Step>();
        for (String stepId : ordered) {
            DraftStep step = steps.get(stepId);
            var capability = CapabilityCatalog.get(step.capability());
            resultSteps.add(new Plan.Step(step.id(), step.title().strip(), capability.id(), step.dependsOn(),
                    capability.approvalHint(), capability.availability(), blocked.contains(step.id()) ? "blocked" : "planned"));
        }
        return new Plan(UUID.randomUUID(), goal, summary, references, constraints, questions, resultSteps, false);
    }

    private static void visit(String id, Map<String, DraftStep> steps, Set<String> visiting, Set<String> done,
            List<String> ordered) {
        if (done.contains(id)) return;
        if (!visiting.add(id)) throw invalid();
        for (String dependency : steps.get(id).dependsOn()) visit(dependency, steps, visiting, done, ordered);
        visiting.remove(id);
        done.add(id);
        ordered.add(id);
    }

    private static void validateReferences(List<String> references, Set<String> valid) {
        var unique = new HashSet<String>();
        for (String reference : references) {
            if (!valid.contains(reference) || !unique.add(reference)) throw invalid();
        }
    }

    private static void id(String value) {
        if (value == null || !value.matches("[A-Za-z][A-Za-z0-9_-]{0,31}")) throw invalid();
    }

    private static String text(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) throw invalid();
        String clean = value.strip();
        if (COMPLETION_CLAIM.matcher(clean).find()) throw invalid();
        return clean;
    }

    private static void limit(List<?> values, int max) {
        if (values == null || values.size() > max) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid planning response");
    }
}
