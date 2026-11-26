package com.chomiii.planning.commitment;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Deliberately narrow grammar; no assistant text is interpreted. */
final class SlotChoiceParser {
    private static final String CLOCK = "(\\d{1,2})(?::([0-5]\\d))?\\s*(am|pm)";
    private static final String ACTIVITIES = "outing|trip|visit|walk|picnic|lunch|dinner|movie|museum|concert|meeting|meet up";
    private static final Pattern ACCEPT = Pattern.compile("(?i)^(?:yes[, ]+)?(?:i(?:['’]m| am) )?"
            + "(?:taking|i(?:['’]ll| will) take|i choose|choosing) (?:the )?" + CLOCK
            + " slot(?: for (?:the )?(" + ACTIVITIES + "))?"
            + "(?: (?:on )?(today|tomorrow|tmrw|tmr|\\d{4}-\\d{2}-\\d{2}))?"
            + "(?:[,;]?\\s+(?:(?:and|then)\\s+)*(?:show(?: me)?|list|check) (?:all(?: of)? )?(?:my |the )?(?:open )?to-?dos?)?[.!]?$");
    private static final Pattern DATE = Pattern.compile("(?i)\\b(today|tomorrow|tmrw|tmr|\\d{4}-\\d{2}-\\d{2})\\b");
    private static final Pattern ACTIVITY = Pattern.compile("(?i)\\b(" + ACTIVITIES + ")\\b");
    private static final Pattern UNSAFE = Pattern.compile("(?i)\\b(not|no|never|don['’]t|didn['’]t|instead|unless|if|"
            + "maybe|might|could|would|or|cancel(?:led)?|password|secret|diagnosis|medical|medication|"
            + "religion|politics|sexual|ethnicity|bank|account|system prompt|ignore previous)\\b");

    record Choice(String activity, LocalDate date, LocalTime time, ZoneId zone, Instant expiresAt) {}

    static boolean isSelection(String text) { return text != null && text.length() <= 300 && ACCEPT.matcher(text.strip()).matches(); }

    static Optional<Choice> parse(String selected, Instant selectedAt, String previous, Instant previousAt, String zoneId) {
        if (selected == null || selectedAt == null || zoneId == null
                || selected.length() > 300 || (previous != null && previous.length() > 1000)
                || !ZoneId.getAvailableZoneIds().contains(zoneId)
                || (previous != null && (previousAt == null || previousAt.isAfter(selectedAt)
                    || previousAt.isBefore(selectedAt.minusSeconds(86_400)) || UNSAFE.matcher(previous).find()))
                || UNSAFE.matcher(selected).find()) return Optional.empty();
        var selection = ACCEPT.matcher(selected.strip());
        if (!selection.matches()) return Optional.empty();
        int hour = Integer.parseInt(selection.group(1));
        if (hour < 1 || hour > 12) return Optional.empty();
        int minute = selection.group(2) == null ? 0 : Integer.parseInt(selection.group(2));
        hour = hour % 12 + (selection.group(3).equalsIgnoreCase("pm") ? 12 : 0);
        var activity = ACTIVITY.matcher(previous == null ? "" : previous);
        String name = selection.group(4);
        if (activity.find()) {
            String previousName = activity.group();
            if (activity.find() || (name != null && !name.equalsIgnoreCase(previousName))) return Optional.empty();
            if (name == null) name = previousName;
        }
        if (name == null) return Optional.empty();
        name = name.toLowerCase(Locale.ROOT);
        var dateMatch = DATE.matcher(previous == null ? "" : previous);
        String priorDate = dateMatch.find() ? dateMatch.group() : null;
        if (dateMatch.find()) return Optional.empty();
        ZoneId zone = ZoneId.of(zoneId);
        try {
            LocalDate fromPrior = priorDate == null ? null : date(priorDate, previousAt, zone);
            LocalDate fromSelection = selection.group(5) == null ? null : date(selection.group(5), selectedAt, zone);
            if (fromPrior != null && fromSelection != null && !fromPrior.equals(fromSelection)) return Optional.empty();
            LocalDate chosenDate = fromSelection == null ? fromPrior : fromSelection;
            LocalDate today = selectedAt.atZone(zone).toLocalDate();
            if (chosenDate == null || chosenDate.isBefore(today) || chosenDate.isAfter(today.plusDays(14))) return Optional.empty();
            return Optional.of(new Choice(name, chosenDate, LocalTime.of(hour, minute), zone,
                    chosenDate.plusDays(1).atStartOfDay(zone).toInstant()));
        } catch (DateTimeException invalid) {
            return Optional.empty();
        }
    }

    private static LocalDate date(String token, Instant instant, ZoneId zone) {
        LocalDate today = instant.atZone(zone).toLocalDate();
        return switch (token.toLowerCase(Locale.ROOT)) {
            case "today" -> today;
            case "tomorrow", "tmrw", "tmr" -> today.plusDays(1);
            default -> LocalDate.parse(token);
        };
    }
}
