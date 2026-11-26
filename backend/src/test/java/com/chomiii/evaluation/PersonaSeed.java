package com.chomiii.evaluation;

import com.chomiii.memory.MemoryService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Seeds the fictional persona's statements into a disposable, owner-scoped
 * memory store for recall tests that are independent of the scripted chat
 * learning path. Durable facts and goals are created the way a user would
 * create them in the product (active, {@code user_explicit}); ambiguous
 * statements are seeded the way uncertain chat input is learned
 * ({@code inferred}, low confidence, suggested until reviewed).
 */
final class PersonaSeed {
    /** A statement written to the store, with the memory ID it received. */
    record Seeded(String factId, java.util.UUID memoryId, String statement) {}

    private PersonaSeed() {}

    /** Seeds the durable everyday facts and both goals, in a fixed order. */
    static List<Seeded> durable(MemoryService memories, String owner) {
        return facts(memories, owner, FictionalPersona.durableAndGoals());
    }

    /** Seeds an explicit list of persona facts as active user-controlled memories. */
    static List<Seeded> facts(MemoryService memories, String owner, List<FictionalPersona.Fact> facts) {
        var seeded = new ArrayList<Seeded>();
        for (var fact : facts) {
            var memory = memories.create(owner, fact.statement());
            seeded.add(new Seeded(fact.id(), memory.id(), fact.statement()));
        }
        return List.copyOf(seeded);
    }

    /** Seeds one persona fact as an active user-controlled memory. */
    static Seeded fact(MemoryService memories, String owner, FictionalPersona.Fact fact) {
        var memory = memories.create(owner, fact.statement());
        return new Seeded(fact.id(), memory.id(), fact.statement());
    }

    /** Seeds the ambiguous statements as low-confidence inferred suggestions. */
    static List<Seeded> ambiguous(MemoryService memories, String owner, UUID conversation) {
        var seeded = new ArrayList<Seeded>();
        for (var ambiguity : FictionalPersona.ambiguous()) {
            memories.learn(owner, conversation, memories.revision(owner),
                    ambiguity.statement(), "inferred", 0.8);
            seeded.add(new Seeded(ambiguity.id(), idOf(memories, owner, ambiguity.statement()),
                    ambiguity.statement()));
        }
        return List.copyOf(seeded);
    }

    private static UUID idOf(MemoryService memories, String owner, String statement) {
        return memories.list(owner).stream()
                .filter(memory -> memory.content().equals(statement))
                .map(memory -> memory.id())
                .findFirst().orElseThrow();
    }
}
