package com.chomiii.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

final class FixtureLoader {
    private final ObjectMapper json = new ObjectMapper();

    EvaluationFixture load(Path path) throws IOException {
        EvaluationFixture fixture = json.readValue(path.toFile(), EvaluationFixture.class);
        validate(fixture);
        return fixture;
    }

    List<EvaluationFixture> loadAll(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            List<Path> files = paths.filter(path -> path.toString().endsWith(".json")).sorted().toList();
            var fixtures = new java.util.ArrayList<EvaluationFixture>();
            Set<String> ids = new HashSet<>();
            for (Path file : files) {
                EvaluationFixture fixture = load(file);
                require(ids.add(fixture.id()), "duplicate fixture id: " + fixture.id());
                fixtures.add(fixture);
            }
            return List.copyOf(fixtures);
        }
    }

    private static void validate(EvaluationFixture fixture) {
        require(fixture != null, "fixture is required");
        require(fixture.version() == 1, "unsupported fixture version");
        require(Boolean.TRUE.equals(fixture.synthetic()), "fixture must be synthetic");
        text(fixture.id(), "fixture id");
        text(fixture.description(), "description");
        require(oneOf(fixture.category(), "explicit-facts", "long-messages", "conversations", "corrections",
                "inferences", "negative", "lifecycle"), "invalid category");
        require(oneOf(fixture.difficulty(), "easy", "medium", "difficult"), "invalid difficulty");
        require(oneOf(fixture.inputType(), "single_message", "conversation", "pasted_transcript"),
                "invalid input type");
        require(fixture.turns() != null && !fixture.turns().isEmpty(), "turns are required");
        Map<String, String> turns = new HashMap<>();
        for (var turn : fixture.turns()) {
            require(turn != null, "turn is required");
            text(turn.id(), "turn id");
            text(turn.content(), "turn content");
            require(oneOf(turn.role(), "user", "assistant"), "invalid turn role");
            if ("user".equals(turn.role())) {
                require(oneOf(turn.expectedEligibility(), "eligible", "too_short", "too_long",
                        "no_personal_language", "chat_rejected"), "invalid eligibility expectation");
            }
            require(turns.putIfAbsent(turn.id(), turn.role()) == null, "duplicate turn id");
        }
        require(turns.containsValue("user"), "at least one user turn is required");
        if ("single_message".equals(fixture.inputType())) {
            require(fixture.turns().size() == 1 && "user".equals(fixture.turns().getFirst().role()),
                    "single message requires one user turn");
        }
        require(fixture.expectedMemories() != null && fixture.forbiddenMemories() != null
                && fixture.retrievalChecks() != null && fixture.lifecycle() != null,
                "expected, forbidden, retrieval and lifecycle lists are required");
        Set<String> facts = new HashSet<>();
        for (var fact : fixture.expectedMemories()) {
            require(fact != null, "expected memory is required");
            text(fact.id(), "expected memory id");
            text(fact.statement(), "expected memory statement");
            require(facts.add(fact.id()), "duplicate expected memory id");
            require(fact.aliases() != null, "aliases are required");
            fact.aliases().forEach(alias -> text(alias, "alias"));
            require(fact.sourceTurnIds() != null && !fact.sourceTurnIds().isEmpty(), "source turn is required");
            for (String id : fact.sourceTurnIds()) {
                require("user".equals(turns.get(id)), "source turn must identify a user turn");
            }
            require(oneOf(fact.kind(), "explicit_fact", "inference"), "invalid expected kind");
            require(oneOf(fact.importance(), "high", "medium", "low"), "invalid importance");
            require(oneOf(fact.expectedStatus(), "active", "suggested"), "invalid expected status");
            require(fact.inScope() != null, "capacity label is required");
        }
        for (var forbidden : fixture.forbiddenMemories()) {
            require(forbidden != null, "forbidden memory is required");
            text(forbidden.statement(), "forbidden statement");
            text(forbidden.reason(), "forbidden reason");
            text(forbidden.category(), "forbidden category");
        }
        for (var check : fixture.retrievalChecks()) {
            require(check != null, "retrieval check is required");
            text(check.query(), "retrieval query");
            require(check.expectedMemoryIds() != null && facts.containsAll(check.expectedMemoryIds()),
                    "retrieval references an unknown expected memory");
        }
        for (var action : fixture.lifecycle()) {
            require(action != null && "user".equals(turns.get(action.afterTurnId())),
                    "lifecycle action needs a user turn");
            require(oneOf(action.operation(), "approve", "edit", "delete", "clear", "other_owner",
                    "delete_while_pending"), "invalid lifecycle operation");
            if (action.memoryId() != null) require(facts.contains(action.memoryId()), "unknown lifecycle memory");
        }
    }

    private static void text(String value, String name) {
        require(value != null && !value.isBlank(), name + " is required");
    }

    private static boolean oneOf(String value, String... allowed) {
        return value != null && List.of(allowed).contains(value);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
