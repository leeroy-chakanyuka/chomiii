package com.chomiii.memory;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservative same-slot conflict gate for automatically learned personal facts.
 * It is intentionally NOT an open-domain semantic contradiction classifier:
 * only explicit first-person commute-mode and residence changes are recognized.
 * An uncertain candidate stays suggested; this class never edits or deletes
 * an existing user-controlled memory.
 */
final class MemoryConflictDetector {
    private static final Pattern COMMUTE = Pattern.compile(
            "(?i)\\bi\\s+(?:(?:now|currently|usually)\\s+)?(?:commute|travel)" +
            "(?:\\s+to\\s+(?:work|school|university))?\\s+(?:by|on)\\s+(?:the\\s+|a\\s+)?([\\p{L}]+)");
    private static final Pattern TAKE_TO_WORK = Pattern.compile(
            "(?i)\\bi\\s+(?:(?:now|currently|usually)\\s+)?take\\s+(?:the\\s+|a\\s+)?" +
            "(bus|train|taxi|tram|subway)\\s+to\\s+(?:work|school|university)\\b");
    private static final Pattern RESIDENCE = Pattern.compile(
            "(?i)\\bi\\s+(?:(?:now|currently)\\s+)?(?:live\\s+in|reside\\s+in|moved\\s+to)" +
            "\\s+([\\p{L}][\\p{L} '-]{1,60})");

    private MemoryConflictDetector() {}

    static boolean conflicts(String candidate, List<PersonalMemory> existing) {
        String candidateMode = commuteMode(candidate);
        String candidateResidence = residence(candidate);
        if (candidateMode == null && candidateResidence == null) return false;
        return existing.stream().filter(memory -> "active".equals(memory.status()))
                .anyMatch(memory -> {
                    String oldMode = commuteMode(memory.content());
                    String oldResidence = residence(memory.content());
                    return candidateMode != null && oldMode != null && !candidateMode.equals(oldMode)
                            || candidateResidence != null && oldResidence != null
                                    && !candidateResidence.equals(oldResidence);
                });
    }

    private static String commuteMode(String text) {
        if (text == null) return null;
        Matcher matcher = COMMUTE.matcher(text);
        if (!matcher.find()) matcher = TAKE_TO_WORK.matcher(text);
        if (!matcher.find(0)) return null;
        String value = matcher.group(1).toLowerCase(Locale.ROOT);
        return switch (value) {
            case "bike", "bicycle", "cycling", "cycle" -> "bicycle";
            case "car", "driving", "drive" -> "car";
            case "walking", "foot", "walk" -> "walking";
            default -> value;
        };
    }

    private static String residence(String text) {
        if (text == null) return null;
        Matcher matcher = RESIDENCE.matcher(text);
        if (!matcher.find()) return null;
        String location = matcher.group(1).toLowerCase(Locale.ROOT).strip();
        // Stop at common sentence or clause boundaries.
        location = location.split("(?i)\\s+(?:but|and|because|since|while|although|for\\s+work)\\s+", 2)[0].strip();
        return location.isEmpty() ? null : location;
    }
}
