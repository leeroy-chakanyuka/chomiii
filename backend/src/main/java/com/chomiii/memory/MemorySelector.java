package com.chomiii.memory;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Local hybrid lexical/topic retrieval: no embedding request on the chat critical path.
 * Topic matching covers simple paraphrases; it is not general semantic understanding.
 */
public final class MemorySelector {
    private static final int MAX_CHAT_RESULTS = 3;
    private static final int MAX_PLANNING_RESULTS = 6;
    private static final Set<String> STOPWORDS = Set.of(
            "what", "which", "where", "when", "have", "with", "that",
            "this", "your", "mine", "about", "could", "would", "should",
            "please", "tell", "from", "remember", "know", "into",
            "does", "been", "they", "them", "there", "their", "just");

    private MemorySelector() {}

    public static List<PersonalMemory> select(List<PersonalMemory> available, String query) {
        return rank(available, query, MAX_CHAT_RESULTS);
    }

    public static List<PersonalMemory> selectForPlanning(List<PersonalMemory> available, String query) {
        return rank(available, query, MAX_PLANNING_RESULTS);
    }

    private static List<PersonalMemory> rank(List<PersonalMemory> available, String query, int limit) {
        if (available == null || available.isEmpty()) return List.of();
        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT);
        if (normalized.contains("what do you remember")
                || normalized.contains("what have you saved")
                || normalized.contains("what do you know about me")) {
            return available.stream().sorted(Comparator.comparing(PersonalMemory::updatedAt).reversed())
                    .limit(limit).toList();
        }
        Set<String> keywords = words(normalized);
        Set<MemoryTopics.Topic> interests = MemoryTopics.detect(normalized);
        if (keywords.isEmpty() && interests.isEmpty()) return List.of();
        return available.stream()
                .map(memory -> new Match(memory, relevance(memory, keywords, interests)))
                .filter(match -> match.score() > 0)
                .sorted(Comparator.comparingInt(Match::score).reversed()
                        .thenComparing(match -> match.memory().updatedAt(), Comparator.reverseOrder()))
                .limit(limit).map(Match::memory).toList();
    }

    private static int relevance(PersonalMemory memory, Set<String> query,
            Set<MemoryTopics.Topic> interests) {
        Set<String> saved = words(memory.content());
        int exact = (int) query.stream().filter(saved::contains).count();
        Set<MemoryTopics.Topic> savedTopics = MemoryTopics.detect(memory.content());
        long topic = interests.stream().filter(savedTopics::contains)
                .filter(item -> item != MemoryTopics.Topic.PERSONAL).count();
        // A topic match may recall "I like tacos" for "find dinner", even with zero shared words.
        // Personal-preference-only matches are too broad and remain lexical.
        return exact * 10 + (int) topic * 3;
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
