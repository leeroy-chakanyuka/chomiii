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
        List<EvaluationFixture> fixtures = read(path);
        require(fixtures.size() == 1, "expected one fixture in " + path);
        return fixtures.getFirst();
    }

    List<EvaluationFixture> loadAll(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            List<Path> files = paths.filter(path -> path.toString().endsWith(".json")).sorted().toList();
            var fixtures = new java.util.ArrayList<EvaluationFixture>();
            Set<String> ids = new HashSet<>();
            for (Path file : files) {
                for (EvaluationFixture fixture : read(file)) {
                    require(ids.add(fixture.id()), "duplicate fixture id: " + fixture.id());
                    fixtures.add(fixture);
                }
            }
            return List.copyOf(fixtures);
        }
    }

    private List<EvaluationFixture> read(Path path) throws IOException {
        var root = json.readTree(path.toFile());
        require(root != null, "empty fixture file");
        var nodes = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        if (root.isArray()) root.forEach(nodes::add);
        else nodes.add(root);
        var fixtures = new java.util.ArrayList<EvaluationFixture>();
        for (var node : nodes) {
            EvaluationFixture fixture = expand(json.treeToValue(node, EvaluationFixture.class));
            validate(fixture);
            fixtures.add(fixture);
        }
        return List.copyOf(fixtures);
    }

    private static EvaluationFixture expand(EvaluationFixture fixture) {
        if (fixture == null || fixture.turns() == null) return fixture;
        var turns = fixture.turns().stream().map(turn -> {
            if (turn == null || turn.targetChars() == null) return turn;
            require(turn.content() != null && turn.targetChars() >= turn.content().length()
                    && turn.targetChars() <= 12_000, "invalid target length");
            text(turn.paddingText(), "padding text");
            var content = new StringBuilder(turn.content());
            while (content.length() < turn.targetChars()) {
                int remaining = turn.targetChars() - content.length();
                content.append(turn.paddingText(), 0, Math.min(remaining, turn.paddingText().length()));
            }
            if (turn.targetChars() > turn.content().length()
                    && Character.isWhitespace(content.charAt(content.length() - 1))) {
                content.setCharAt(content.length() - 1, '.');
            }
            return new EvaluationFixture.Turn(turn.id(), turn.role(), content.toString(), turn.expectedEligibility(),
                    turn.scriptedExtraction(), turn.targetChars(), turn.paddingText());
        }).toList();
        return new EvaluationFixture(fixture.id(), fixture.version(), fixture.description(), fixture.category(),
                fixture.difficulty(), fixture.importance(), fixture.inputType(), fixture.synthetic(), turns, fixture.expectedMemories(),
                fixture.forbiddenMemories(), fixture.retrievalChecks(), fixture.lifecycle(), fixture.planning());
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
        require(oneOf(fixture.importance(), "high", "medium", "low"), "invalid fixture importance");
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
            require(turn.targetChars() == null || "user".equals(turn.role()), "padding applies only to user turns");
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
            require(oneOf(fact.expectedStatus(), "active", "suggested", "absent"), "invalid expected status");
            require(fact.inScope() != null, "capacity label is required");
            require(oneOf(fact.origin(), "conversation", "manual_edit"), "invalid expected origin");
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
            if (oneOf(action.operation(), "approve", "edit", "delete")) {
                require(action.memoryId() != null, "lifecycle memory is required");
            }
            if ("edit".equals(action.operation())) text(action.content(), "edit content");
        }
        for (var fact : fixture.expectedMemories()) {
            if ("manual_edit".equals(fact.origin())) {
                require(fixture.lifecycle().stream().anyMatch(action -> "edit".equals(action.operation())
                        && fact.statement().equals(action.content())), "manual fact requires a matching edit");
            }
        }
        if (fixture.planning() != null && !fixture.planning().isNull()) PlanningContract.validate(fixture.planning(), facts);
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
