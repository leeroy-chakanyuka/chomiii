package com.chomiii.memory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic decomposition of everyday personal statements into structured
 * claims for the conservative conflict gate.
 *
 * A claim records which part of life a clause is about (slot), what it asserts
 * (value), when it applies (scope, including temporary windows), and change,
 * negation and coexistence markers, plus values the statement explicitly
 * replaces ("instead of the bus", "switched from Python"). Comparison is
 * uniform for every domain: the small word lists below are generic English
 * vocabulary (function words, change and negation markers, time partitions,
 * light inflection and synonym normalization), not domain-specific conflict
 * rules. The reconciliation policy lives in {@link MemoryConflictDetector}.
 */
final class MemoryClaims {

    /**
     * One clause of one statement. {@code slot} carries the head plus parameter
     * words (for example destination or object), {@code params} the parameter
     * words alone, {@code value} the asserted value, and {@code references} the
     * replaced values named by the statement itself.
     */
    record Claim(Set<String> slot, Set<String> params, Set<String> value, Set<String> scope,
            Set<String> references, boolean temporary, boolean negated, boolean change,
            boolean coexist) {}

    private static final Set<String> STOP = Set.of(
            "i", "im", "ive", "id", "ill", "my", "me", "mine", "myself", "we", "our", "ours", "us",
            "you", "your", "yours", "it", "its", "this", "that", "these", "those", "there", "here",
            "they", "them", "their", "am", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "having", "do", "does", "did", "will", "would", "shall", "should",
            "can", "could", "may", "might", "must", "and", "or", "but", "because", "since", "while",
            "although", "though", "however", "so", "than", "then", "if", "when", "what", "which",
            "who", "whom", "how", "why", "where", "a", "an", "the", "of", "very", "really", "quite",
            "just", "still", "usually", "often", "sometimes", "frequently", "generally", "typically",
            "normally", "mostly", "mainly", "regularly", "rarely", "seldom", "occasionally",
            "daily", "weekly", "monthly", "yearly", "always");

    private static final Set<String> PREPOSITIONS = Set.of(
            "to", "by", "for", "on", "at", "in", "into", "from", "with", "about", "after", "before",
            "during", "near", "via", "using", "over", "around", "per", "within", "until", "till");

    private static final Set<String> CHANGE = Set.of(
            "now", "currently", "presently", "lately", "recently", "moved", "relocated", "switched",
            "changed", "instead", "rather", "replaced", "upgraded", "converted", "renamed",
            "finished", "completed", "started", "began", "quit", "left");

    private static final Set<String> NEGATION = Set.of(
            "not", "never", "dont", "doesnt", "didnt", "wont", "cant", "cannot", "stopped", "anymore");

    private static final Set<String> COEXIST = Set.of(
            "also", "additionally", "besides", "plus", "another", "second", "both", "too");

    /** Generic day, day-part and occasion partitions that scope a claim. */
    private static final Set<String> SCOPE = Set.of(
            "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
            "weekday", "weekend", "workday", "morning", "afternoon", "evening", "night", "noon",
            "midnight", "breakfast", "brunch", "lunch", "dinner", "january", "february", "march",
            "april", "june", "july", "august", "september", "october", "november", "december",
            "spring", "summer", "autumn", "winter");

    private static final Set<String> DURATION_UNITS = Set.of(
            "day", "week", "month", "year", "weekend", "fortnight", "semester", "quarter", "hour");

    private static final Set<String> CONJUNCTIONS = Set.of(
            "and", "or", "but", "because", "when", "while", "since", "although", "so", "then");

    /** Light synonym normalization in stemmed space; no domain knowledge. */
    private static final Map<String, String> SYNONYMS = Map.of(
            "travel", "commut", "resid", "liv", "dwell", "liv", "bicycl", "bik", "cycl", "bik",
            "foot", "walk", "driv", "car", "supper", "dinner", "job", "work");

    private MemoryClaims() {}

    static List<Claim> parse(String content) {
        if (content == null || content.isBlank()) return List.of();
        List<Claim> claims = new ArrayList<>();
        for (String sentence : content.split("[.;!?\n]+")) {
            List<String> tokens = tokenize(sentence);
            int start = 0;
            for (int i = 0; i <= tokens.size(); i++) {
                boolean boundary = i == tokens.size()
                        || "and".equals(tokens.get(i)) || "but".equals(tokens.get(i));
                if (!boundary) continue;
                Claim claim = parseClause(tokens.subList(start, i));
                if (claim != null) claims.add(claim);
                start = i + 1;
            }
        }
        return List.copyOf(claims);
    }

    /** Light stemming plus a fixed synonym map, applied identically to both sides. */
    static String normalize(String token) {
        if (PREPOSITIONS.contains(token) || STOP.contains(token) || SCOPE.contains(token)
                || DURATION_UNITS.contains(token)) return token;
        String stemmed = stem(token);
        return SYNONYMS.getOrDefault(stemmed, stemmed);
    }

    private static Claim parseClause(List<String> raw) {
        List<String> tokens = new ArrayList<>(raw);
        boolean temporary = stripPhrase(tokens, "for now");
        boolean negated = stripPhrase(tokens, "no longer") | stripPhrase(tokens, "not anymore")
                | stripPhrase(tokens, "no more");
        boolean change = stripPhrase(tokens, "these days");
        boolean coexist = stripPhrase(tokens, "as well");

        // "instead of the bus" / "rather than X" name the replaced value.
        List<String> refs = new ArrayList<>();
        int index = 0;
        while (index < tokens.size()) {
            String token = tokens.get(index);
            if (!"instead".equals(token) && !"rather".equals(token)) {
                index++;
                continue;
            }
            change = true;
            int start = index + 1;
            if (start < tokens.size()
                    && ("of".equals(tokens.get(start)) || "than".equals(tokens.get(start)))) start++;
            int end = referenceEnd(tokens, start);
            collectReferences(tokens, start, end, refs);
            tokens.subList(index, end).clear();
        }

        negated |= tokens.removeIf(NEGATION::contains);
        change |= tokens.removeIf(CHANGE::contains);
        coexist |= tokens.removeIf(COEXIST::contains);

        // "switched from Python" names the replaced value in a change context.
        if (change) {
            index = 0;
            while (index < tokens.size()) {
                if (!"from".equals(tokens.get(index))) {
                    index++;
                    continue;
                }
                int start = index + 1;
                int end = referenceEnd(tokens, start);
                collectReferences(tokens, start, end, refs);
                tokens.subList(index, end).clear();
            }
        }

        List<String> norm = new ArrayList<>();
        for (String token : tokens) norm.add(normalize(token));
        temporary |= markTemporary(norm);

        Set<String> scope = new LinkedHashSet<>();
        List<String> work = new ArrayList<>();
        for (String token : norm) {
            if (SCOPE.contains(token)) {
                scope.add(token);
                if (!work.isEmpty() && PREPOSITIONS.contains(work.get(work.size() - 1))) {
                    work.remove(work.size() - 1);
                }
            } else {
                work.add(token);
            }
        }

        Set<String> slot = new LinkedHashSet<>();
        Set<String> params = new LinkedHashSet<>();
        Set<String> value = new LinkedHashSet<>();
        int firstPrep = -1;
        int lastPrep = -1;
        for (int i = 0; i < work.size(); i++) {
            if (PREPOSITIONS.contains(work.get(i))) {
                if (firstPrep < 0) firstPrep = i;
                lastPrep = i;
            }
        }
        for (int i = 0; i < work.size(); i++) {
            String token = work.get(i);
            if (STOP.contains(token) || PREPOSITIONS.contains(token)) continue;
            if (firstPrep < 0) {
                if (slot.isEmpty()) slot.add(token); else value.add(token);
            } else if (i < firstPrep) {
                slot.add(token);
            } else if (i > lastPrep) {
                value.add(token);
            } else {
                slot.add(token);
                params.add(token);
            }
        }
        if (slot.isEmpty() && value.isEmpty()) return null;
        return new Claim(slot, params, value, scope, Set.copyOf(refs),
                temporary, negated, change, coexist);
    }

    private static List<String> tokenize(String text) {
        String cleaned = text.toLowerCase(Locale.ROOT).replace("'", "").replace("’", "");
        return new ArrayList<>(Arrays.stream(cleaned.split("[^\\p{L}\\p{N}]+"))
                .filter(token -> !token.isEmpty()).toList());
    }

    private static boolean stripPhrase(List<String> tokens, String phrase) {
        String[] parts = phrase.split(" ");
        for (int i = 0; i + parts.length <= tokens.size(); i++) {
            boolean matched = true;
            for (int j = 0; j < parts.length; j++) {
                if (!parts[j].equals(tokens.get(i + j))) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                tokens.subList(i, i + parts.length).clear();
                return true;
            }
        }
        return false;
    }

    /** The exclusive end (not included) of a referenced value span. */
    private static int referenceEnd(List<String> tokens, int start) {
        int end = start;
        while (end < tokens.size() && !CONJUNCTIONS.contains(tokens.get(end))
                && !PREPOSITIONS.contains(tokens.get(end))) end++;
        return end;
    }

    private static void collectReferences(List<String> tokens, int start, int end, List<String> refs) {
        for (int i = start; i < end && i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (STOP.contains(token) || PREPOSITIONS.contains(token)) continue;
            refs.add(normalize(token));
        }
    }

    /** Temporary windows ("for the next two weeks") never erase standing facts. */
    private static boolean markTemporary(List<String> norm) {
        boolean temporary = norm.removeIf(token ->
                "temporarily".equals(token) || "temporary".equals(token));
        int index = 0;
        while (index < norm.size()) {
            String token = norm.get(index);
            if ("next".equals(token) || "coming".equals(token)
                    || "following".equals(token) || "this".equals(token)) {
                Integer unit = findWithin(norm, index + 1, index + 3, DURATION_UNITS);
                if (unit != null) {
                    temporary = true;
                    norm.subList(index, unit + 1).clear();
                    continue;
                }
            }
            if ("for".equals(token) || "during".equals(token)
                    || "over".equals(token) || "within".equals(token)) {
                Integer unit = findWithin(norm, index + 1, index + 4, DURATION_UNITS);
                if (unit != null) {
                    temporary = true;
                    norm.subList(index, unit + 1).clear();
                    continue;
                }
            }
            index++;
        }
        return temporary;
    }

    private static Integer findWithin(List<String> tokens, int from, int to, Set<String> wanted) {
        for (int i = from; i <= to && i < tokens.size(); i++) {
            if (wanted.contains(tokens.get(i))) return i;
        }
        return null;
    }

    private static String stem(String token) {
        if (token.length() > 4 && token.endsWith("ies")) return token.substring(0, token.length() - 3) + "y";
        if (token.length() > 4 && token.endsWith("es")) return token.substring(0, token.length() - 2);
        if (token.length() > 3 && token.endsWith("s") && !token.endsWith("ss") && !token.endsWith("us")) {
            return token.substring(0, token.length() - 1);
        }
        if (token.length() > 5 && token.endsWith("ing")) return token.substring(0, token.length() - 3);
        if (token.length() > 4 && token.endsWith("ed")) return token.substring(0, token.length() - 2);
        if (token.length() > 3 && token.endsWith("e")) return token.substring(0, token.length() - 1);
        return token;
    }
}
