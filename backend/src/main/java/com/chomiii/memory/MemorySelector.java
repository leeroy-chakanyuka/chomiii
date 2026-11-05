package com.chomiii.memory;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Bounded local retrieval, no provider API or embedding call before streaming.
 * False negatives are possible; this intentionally avoids pretending to have
 * semantic search. Retrieval is a relevance hint, not user authorization.
 */
public final class MemorySelector {
    private static final int MAX_RESULTS = 3;
    private static final Set<String> STOPWORDS = Set.of(
            "what", "which", "where", "when", "have", "with", "that",
            "this", "your", "mine", "about", "could", "would", "should",
            "please", "tell", "from", "remember", "know", "into",
            "does", "been", "they", "them", "there", "their", "just");

    private MemorySelector() {}

    public static List<PersonalMemory> select(List<PersonalMemory> available, String query) {
        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT);
        if (normalized.contains("what do you remember")
                || normalized.contains("what have you saved")
                || normalized.contains("what do you know about me")) {
            return available.stream().limit(MAX_RESULTS).toList();
        }
        Set<String> keywords = words(normalized);
        if (keywords.isEmpty()) return List.of();
        return available.stream()
                .map(memory -> new Match(memory, relevance(words(memory.content()), keywords)))
                .filter(match -> match.score() > 0)
                .sorted(Comparator.comparingInt(Match::score).reversed()
                        .thenComparing(match -> match.memory().updatedAt(), Comparator.reverseOrder()))
                .limit(MAX_RESULTS).map(Match::memory).toList();
    }

    private static int relevance(Set<String> content, Set<String> query) {
        return (int) query.stream().filter(content::contains).count();
    }

    private static Set<String> words(String input) {
        return Arrays.stream(input.toLowerCase(Locale.ROOT)
                        .split("[^\\p{L}\\p{N}]+"))
                .map(MemorySelector::canonical)
                .filter(word -> word.length() >= 4 && !STOPWORDS.contains(word))
                .collect(Collectors.toSet());
    }

    private static String canonical(String word) {
        return switch (word) {
            case "groceries", "grocery" -> "grocery";
            case "favourite", "favorite", "favourites", "favorites" -> "favorite";
            case "usually", "usual" -> "usual";
            default -> word.endsWith("s") && word.length() > 5
                    ? word.substring(0, word.length() - 1) : word;
        };
    }

    private record Match(PersonalMemory memory, int score) {}
}
