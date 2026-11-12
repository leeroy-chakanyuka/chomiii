package com.chomiii.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Conservative same-slot conflict gate for automatically learned personal
 * facts, domain-agnostic by construction. The candidate and each active
 * memory are decomposed into structured claims (slot, value, scope, change,
 * negation, coexistence, replaced-value references) and compared with one
 * uniform rule set; there are no per-domain conflict expressions.
 *
 * An uncertain candidate stays suggested; this class never edits or deletes
 * an existing user-controlled memory. An optional model-supplied topic label
 * (from the regular extraction pass) refines slot identity for corrections
 * that share no wording, but value incompatibility is always decided
 * deterministically.
 */
final class MemoryConflictDetector {

    private enum Relation { CONFLICT, KNOWN_COEXIST, UNRESOLVED }
    private static final Pattern UNCERTAIN = Pattern.compile(
            "(?i)\\b(might|maybe|perhaps|possibly|unsure|uncertain|considering|not\\s+sure|i\\s+think)\\b");
    private static final Pattern REPLACEMENT = Pattern.compile(
            "(?i)\\b(now|currently|presently|recently|moved|relocated|switched|changed|instead|rather|replaced|no\\s+longer)\\b");

    private MemoryConflictDetector() {}

    static boolean conflicts(String candidate, List<PersonalMemory> existing) {
        return conflicts(candidate, null, existing);
    }

    /** True when any active memory of this owner is an incompatible same-slot fact. */
    static boolean conflicts(String candidate, String topic, List<PersonalMemory> existing) {
        return relation(candidate, topic, existing) == Relation.CONFLICT;
    }

    /** True when an active memory already states the same fact in other words. */
    static boolean equivalent(String candidate, List<PersonalMemory> existing) {
        List<MemoryClaims.Claim> claims = MemoryClaims.parse(candidate);
        if (claims.isEmpty()) return false;
        return existing.stream()
                .filter(memory -> "active".equals(memory.status()))
                .filter(memory -> !sameText(memory.content(), candidate))
                .anyMatch(memory -> duplicate(claims, MemoryClaims.parse(memory.content())));
    }

    /**
     * True when a change-worded or negated candidate may be a correction in a
     * slot the deterministic gate cannot name: nothing conflicts, nothing is
     * known to coexist, and at least one other active fact exists. Such
     * candidates stay suggested for review instead of silently activating
     * beside an outdated fact.
     */
    static boolean ambiguous(String candidate, String topic, List<PersonalMemory> existing) {
        if (candidate != null && UNCERTAIN.matcher(candidate).find()) return true;
        List<MemoryClaims.Claim> claims = MemoryClaims.parse(candidate);
        if (claims.isEmpty() || !marked(claims)) return false;
        if (!REPLACEMENT.matcher(candidate).find() && claims.stream().noneMatch(MemoryClaims.Claim::negated)) return false;
        boolean hasOther = existing.stream()
                .filter(memory -> "active".equals(memory.status()))
                .anyMatch(memory -> !sameText(memory.content(), candidate));
        return hasOther && claims.stream()
                .filter(claim -> (claim.change() || claim.negated()) && !claim.coexist() && !claim.temporary())
                .anyMatch(claim -> relation(List.of(claim), candidate, topic, existing) == Relation.UNRESOLVED);
    }

    private static Relation relation(String candidate, String topic, List<PersonalMemory> existing) {
        return relation(MemoryClaims.parse(candidate), candidate, topic, existing);
    }

    private static Relation relation(List<MemoryClaims.Claim> candidate, String candidateText,
            String topic, List<PersonalMemory> existing) {
        Relation overall = Relation.UNRESOLVED;
        for (PersonalMemory memory : existing) {
            if (!"active".equals(memory.status()) || sameText(memory.content(), candidateText)) continue;
            for (MemoryClaims.Claim e : MemoryClaims.parse(memory.content())) {
                for (MemoryClaims.Claim c : candidate) {
                    Relation pair = relate(c, topic, e, memory.claimTopic());
                    if (pair == Relation.CONFLICT) return Relation.CONFLICT;
                    if (pair == Relation.KNOWN_COEXIST) overall = Relation.KNOWN_COEXIST;
                }
            }
        }
        return overall;
    }

    private static Relation relate(MemoryClaims.Claim c, String candidateTopic,
            MemoryClaims.Claim e, String existingTopic) {
        if (c.coexist() || e.coexist() || c.temporary()) return Relation.KNOWN_COEXIST;
        if (disjointScopes(c, e)) return Relation.KNOWN_COEXIST;
        boolean flip = c.negated() != e.negated();
        if (!c.change() && !flip) return Relation.KNOWN_COEXIST;
        if (references(c, e)) return Relation.CONFLICT;
        boolean slotMatch = sameTopic(candidateTopic, existingTopic)
                || sameHead(c, e);
        if (!slotMatch) return Relation.UNRESOLVED;
        if (differingParameters(c, e)) return Relation.KNOWN_COEXIST;
        if (!compatibleScopes(c, e)) return Relation.UNRESOLVED;
        if (flip) return intersectingValues(c, e) ? Relation.CONFLICT : Relation.UNRESOLVED;
        return disjointValues(c, e) ? Relation.CONFLICT : Relation.UNRESOLVED;
    }

    /**
     * A change statement that names the replaced value is a definite correction.
     * Shared words count only when the candidate's subject is among them, so
     * the same location in otherwise different facts is not a replacement.
     */
    private static boolean references(MemoryClaims.Claim c, MemoryClaims.Claim e) {
        if (!c.change() && !c.negated()) return false;
        if (!c.references().isEmpty()) {
            var words = new java.util.HashSet<>(e.slot());
            words.addAll(e.value());
            if (words.containsAll(c.references())) return true;
        }
        return !Collections.disjoint(c.slot(), e.value())
                && Stream.concat(c.value().stream(), c.slot().stream())
                .filter(e.value()::contains).count() >= 2;
    }

    private static boolean duplicate(List<MemoryClaims.Claim> candidate,
            List<MemoryClaims.Claim> existing) {
        if (candidate.size() != existing.size()) return false;
        List<MemoryClaims.Claim> remaining = new ArrayList<>(existing);
        for (MemoryClaims.Claim claim : candidate) {
            MemoryClaims.Claim match = null;
            for (MemoryClaims.Claim other : remaining) {
                if (sameSignature(claim, other)) {
                    match = other;
                    break;
                }
            }
            if (match == null) return false;
            remaining.remove(match);
        }
        return remaining.isEmpty();
    }

    private static boolean sameSignature(MemoryClaims.Claim left, MemoryClaims.Claim right) {
        return left.slot().equals(right.slot()) && left.params().equals(right.params())
                && left.value().equals(right.value()) && left.scope().equals(right.scope())
                && left.negated() == right.negated();
    }

    private static boolean marked(List<MemoryClaims.Claim> claims) {
        return claims.stream().anyMatch(claim ->
                claim.change() || claim.negated() || claim.coexist() || claim.temporary());
    }

    private static boolean disjointScopes(MemoryClaims.Claim c, MemoryClaims.Claim e) {
        return !c.scope().isEmpty() && !e.scope().isEmpty()
                && Collections.disjoint(c.scope(), e.scope());
    }

    private static boolean compatibleScopes(MemoryClaims.Claim c, MemoryClaims.Claim e) {
        return (c.scope().isEmpty() && e.scope().isEmpty())
                || !Collections.disjoint(c.scope(), e.scope());
    }

    private static boolean differingParameters(MemoryClaims.Claim c, MemoryClaims.Claim e) {
        return !c.params().isEmpty() && !e.params().isEmpty()
                && Collections.disjoint(c.params(), e.params());
    }

    private static boolean intersectingValues(MemoryClaims.Claim c, MemoryClaims.Claim e) {
        return (c.value().isEmpty() && e.value().isEmpty())
                || !Collections.disjoint(c.value(), e.value());
    }

    private static boolean disjointValues(MemoryClaims.Claim c, MemoryClaims.Claim e) {
        return !c.value().isEmpty() && !e.value().isEmpty()
                && Collections.disjoint(c.value(), e.value());
    }

    private static boolean sameTopic(String left, String right) {
        if (left == null || right == null) return false;
        return left.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip()
                .equals(right.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip());
    }

    private static boolean sameHead(MemoryClaims.Claim left, MemoryClaims.Claim right) {
        return !left.slot().isEmpty() && !right.slot().isEmpty()
                && left.slot().iterator().next().equals(right.slot().iterator().next());
    }

    private static boolean sameText(String left, String right) {
        return left != null && right != null
                && left.strip().equalsIgnoreCase(right.strip());
    }
}
