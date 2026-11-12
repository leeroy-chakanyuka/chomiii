package com.chomiii.memory;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservative same-slot conflict gate for automatically learned personal facts.
 * It is intentionally NOT an open-domain semantic contradiction classifier:
 * only explicit first-person commute, residence, and meal-preference changes
 * are recognized.
 * An uncertain candidate stays suggested; this class never edits or deletes
 * an existing user-controlled memory.
 */
final class MemoryConflictDetector {
    // Different locations or transport modes may coexist; an explicit change
    // marker is required before one fact can block another.
    private static final Pattern CHANGE = Pattern.compile(
            "(?i)\\b(now|currently|moved|switched|instead|no\\s+longer|changed)\\b");
    private static final Pattern COMMUTE = Pattern.compile(
            "(?i)\\bi\\s+(?:(now|currently|usually|no\\s+longer)\\s+)?(?:commute|travel)" +
            "(?:\\s+to\\s+(work|school|university))?\\s+(?:by|on)\\s+(?:the\\s+|a\\s+)?([\\p{L}]+)" +
            "(?:\\s+to\\s+(work|school|university))?");
    private static final Pattern TAKE_TO_WORK = Pattern.compile(
            "(?i)\\bi\\s+(?:(now|currently|usually|no\\s+longer)\\s+)?take\\s+(?:the\\s+|a\\s+)?" +
            "(bus|train|taxi|tram|subway)\\s+to\\s+(work|school|university)\\b");
    private static final Pattern RESIDENCE = Pattern.compile(
            "(?i)\\bi\\s+(?:(?:now|currently)\\s+)?(?:live\\s+in|reside\\s+in|moved\\s+to)" +
            "\\s+([\\p{L}][\\p{L} '-]{1,60})");
    private static final Pattern MEAL_PREFERENCE = Pattern.compile(
            "(?i)\\bi\\s+(?:(now|currently|usually|no\\s+longer)\\s+)?(prefer|like|love)\\s+" +
            "([\\p{L}][\\p{L} '-]{0,60}?)\\s+for\\s+(breakfast|lunch|dinner|supper)\\b");
    private static final Pattern WEEKDAY = Pattern.compile("(?i)\\b(weekdays?|monday|tuesday|wednesday|thursday|friday)\\b");
    private static final Pattern WEEKEND = Pattern.compile("(?i)\\b(weekends?|saturday|sunday)\\b");

    private record Commute(String mode, String destination, boolean negative, boolean simple) {}
    private record Preference(String meal, String value, boolean negative, boolean exclusive) {}

    private MemoryConflictDetector() {}

    static boolean conflicts(String candidate, List<PersonalMemory> existing) {
        if (candidate == null || !CHANGE.matcher(candidate).find()) return false;
        Commute candidateCommute = commute(candidate);
        String candidateResidence = residence(candidate);
        Preference candidatePreference = preference(candidate);
        if (candidateCommute == null && candidateResidence == null && candidatePreference == null) return false;
        return existing.stream().filter(memory -> "active".equals(memory.status()))
                .anyMatch(memory -> {
                    if (differentSchedules(candidate, memory.content())) return false;
                    Commute oldCommute = commute(memory.content());
                    String oldResidence = residence(memory.content());
                    Preference oldPreference = preference(memory.content());
                    return conflicts(candidateCommute, oldCommute)
                            || candidateResidence != null && oldResidence != null
                                    && !candidateResidence.equals(oldResidence)
                            || conflicts(candidatePreference, oldPreference);
                });
    }

    /** Treat differently phrased facts about the same known slot as duplicates. */
    static boolean equivalent(String candidate, List<PersonalMemory> existing) {
        Commute commute = commute(candidate);
        String location = residence(candidate);
        return existing.stream().filter(memory -> "active".equals(memory.status()))
                .anyMatch(memory -> equivalent(commute, commute(memory.content()))
                        || location != null && simpleResidence(candidate) && simpleResidence(memory.content())
                                && location.equals(residence(memory.content())));
    }

    private static Commute commute(String text) {
        if (text == null) return null;
        Matcher matcher = COMMUTE.matcher(text);
        String mode;
        String destination;
        if (!matcher.find()) {
            matcher = TAKE_TO_WORK.matcher(text);
            if (!matcher.find()) return null;
            mode = matcher.group(2);
            destination = matcher.group(3);
        } else {
            mode = matcher.group(3);
            destination = matcher.group(2) == null ? matcher.group(4) : matcher.group(2);
        }
        String value = switch (mode.toLowerCase(Locale.ROOT)) {
            case "bike", "bicycle", "cycling", "cycle" -> "bicycle";
            case "car", "driving", "drive" -> "car";
            case "walking", "foot", "walk" -> "walking";
            default -> mode.toLowerCase(Locale.ROOT);
        };
        boolean negative = negative(matcher.group(1));
        boolean simple = text.substring(0, matcher.start()).isBlank()
                && text.substring(matcher.end()).matches("[\\s.!?]*");
        return new Commute(value, destination == null ? null : destination.toLowerCase(Locale.ROOT), negative, simple);
    }

    private static boolean equivalent(Commute left, Commute right) {
        return left != null && right != null && left.simple() && right.simple()
                && !left.negative() && !right.negative() && left.mode().equals(right.mode())
                && java.util.Objects.equals(left.destination(), right.destination());
    }

    private static boolean conflicts(Commute left, Commute right) {
        if (left == null || right == null || left.destination() != null && right.destination() != null
                && !left.destination().equals(right.destination())) return false;
        return left.negative() || right.negative()
                ? left.mode().equals(right.mode()) && left.negative() != right.negative()
                : !left.mode().equals(right.mode());
    }

    private static Preference preference(String text) {
        Matcher matcher = MEAL_PREFERENCE.matcher(text);
        if (!matcher.find()) return null;
        String meal = matcher.group(4).toLowerCase(Locale.ROOT);
        return new Preference("supper".equals(meal) ? "dinner" : meal,
                matcher.group(3).toLowerCase(Locale.ROOT).strip(),
                negative(matcher.group(1)), "prefer".equalsIgnoreCase(matcher.group(2)));
    }

    private static boolean conflicts(Preference left, Preference right) {
        if (left == null || right == null || !left.meal().equals(right.meal())) return false;
        return left.negative() || right.negative()
                ? left.value().equals(right.value()) && left.negative() != right.negative()
                : left.exclusive() && right.exclusive() && !left.value().equals(right.value());
    }

    private static boolean negative(String modifier) {
        return modifier != null && modifier.replaceAll("\\s+", " ").equalsIgnoreCase("no longer");
    }

    private static boolean differentSchedules(String left, String right) {
        return WEEKDAY.matcher(left).find() && WEEKEND.matcher(right).find()
                || WEEKEND.matcher(left).find() && WEEKDAY.matcher(right).find();
    }

    private static boolean simpleResidence(String text) {
        Matcher matcher = RESIDENCE.matcher(text);
        return matcher.find() && text.substring(0, matcher.start()).isBlank()
                && text.substring(matcher.end()).matches("[\\s.!?]*")
                && !matcher.group(1).matches("(?i).*\\s+(but|and|because|since|while|although)\\s+.*");
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
