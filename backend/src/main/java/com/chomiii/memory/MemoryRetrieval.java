package com.chomiii.memory;

import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Local, bounded retrieval. Topic matches are hints, not semantic embeddings. */
final class MemoryRetrieval {
    private static final Set<String> STOPWORDS = Set.of(
            "about", "after", "before", "could", "does", "from", "have", "help", "into", "just",
            "know", "like", "make", "mine", "myself", "please", "remember", "should", "tell", "that",
            "their", "them", "there", "these", "they", "this", "what", "when", "where", "which",
            "with", "would", "your");

    private enum Topic { FOOD, WORK, SCHEDULES, EDUCATION, LOCATION, PROJECTS, PREFERENCES, OTHER }
    private record Match(PersonalMemory memory, int relevance) {}

    private MemoryRetrieval() {}

    static List<PersonalMemory> select(List<PersonalMemory> available, String query, String topic, int limit) {
        String cleanQuery = query == null ? "" : query.toLowerCase(Locale.ROOT);
        Set<String> queryWords = words(cleanQuery);
        Set<Topic> requestedTopics = topics(cleanQuery);
        Topic required = topic == null || topic.isBlank() ? null : parseTopic(topic);
        boolean overview = cleanQuery.contains("what do you remember")
                || cleanQuery.contains("what have you saved") || cleanQuery.contains("what do you know about me");
        Set<String> seen = new HashSet<>();
        return available.stream()
                .filter(memory -> "active".equals(memory.status()))
                .map(memory -> new Match(memory, score(memory.content(), queryWords, requestedTopics, required, overview)))
                .filter(match -> match.relevance() > 0)
                .sorted(Comparator.comparingInt(Match::relevance).reversed()
                        .thenComparing(match -> "user_explicit".equals(match.memory().source()) ? 0 : 1)
                        .thenComparing(match -> match.memory().confidence(), Comparator.reverseOrder())
                        .thenComparing(match -> match.memory().updatedAt(), Comparator.reverseOrder()))
                .filter(match -> seen.add(match.memory().content().strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT)))
                .limit(Math.max(0, limit))
                .map(Match::memory).toList();
    }

    private static int score(String content, Set<String> queryWords, Set<Topic> queryTopics,
            Topic required, boolean overview) {
        Set<Topic> memoryTopics = topics(content);
        if (required != null && !memoryTopics.contains(required)) return 0;
        long overlap = words(content).stream().filter(queryWords::contains).count();
        long topicOverlap = memoryTopics.stream().filter(queryTopics::contains).count();
        return (int) (overlap * 5 + topicOverlap * 2 + (overview ? 1 : 0));
    }

    private static Set<String> words(String text) {
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .map(MemoryRetrieval::canonical)
                .filter(word -> word.length() >= 4 && !STOPWORDS.contains(word))
                .collect(Collectors.toSet());
    }

    private static String canonical(String word) {
        return switch (word) {
            case "groceries", "grocery" -> "grocery";
            case "favourite", "favourites", "favorites" -> "favorite";
            case "usually" -> "usual";
            default -> word.endsWith("s") && word.length() > 4 ? word.substring(0, word.length() - 1) : word;
        };
    }

    private static Set<Topic> topics(String text) {
        Set<String> words = Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .collect(Collectors.toSet());
        var topics = EnumSet.noneOf(Topic.class);
        if (contains(words, "food", "meal", "meals", "dinner", "supper", "lunch", "breakfast", "cook",
                "cooking", "eat", "eating", "groceries", "grocery", "taco", "tacos", "salsa", "rice",
                "beans", "fish", "soup", "recipe", "restaurant")) topics.add(Topic.FOOD);
        if (contains(words, "work", "works", "working", "job", "career", "office", "engineer", "developer",
                "software", "interview", "employer", "meeting", "meetings", "shift", "shifts")) topics.add(Topic.WORK);
        if (contains(words, "schedule", "calendar", "busy", "available", "availability", "appointment", "appointments",
                "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "weekday",
                "weekend", "morning", "mornings", "evening", "evenings", "week", "interview")) topics.add(Topic.SCHEDULES);
        if (contains(words, "school", "university", "college", "study", "studying", "exam", "course", "degree")) topics.add(Topic.EDUCATION);
        if (contains(words, "live", "lives", "city", "town", "location", "commute", "address", "suburb")) topics.add(Topic.LOCATION);
        if (contains(words, "project", "projects", "startup", "app", "website", "building", "prototype")) topics.add(Topic.PROJECTS);
        ProfileCategory category = ProfileCategory.of(text);
        if (category == ProfileCategory.PREFERENCES) topics.add(Topic.PREFERENCES);
        if (category == ProfileCategory.OTHER) topics.add(Topic.OTHER);
        return topics;
    }

    private static boolean contains(Set<String> words, String... candidates) {
        return Arrays.stream(candidates).anyMatch(words::contains);
    }

    private static Topic parseTopic(String topic) {
        try {
            return Topic.valueOf(topic.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Unknown memory topic");
        }
    }
}
