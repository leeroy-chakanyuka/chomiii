package com.chomiii.tools;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.zone.ZoneRulesException;
import java.util.List;
import java.util.Map;

/** First independent read-only utility tool: actual time in an IANA time zone. */
public final class TimeNowTool implements InternalTool {
    private static final ToolDescriptor DEFINITION = new ToolDescriptor(
            "time.now",
            "Get the current date, local time and UTC offset in an IANA time zone. Use for current-time questions; do not guess the user's location.",
            Map.of("type", "object", "properties", Map.of(
                    "zone", Map.of("type", "string", "description",
                            "IANA zone, e.g. Africa/Johannesburg. Defaults to UTC.", "maxLength", 64)),
                    "required", List.of()),
            true);
    private final Clock clock;

    public TimeNowTool(Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock);
    }

    @Override
    public ToolDescriptor definition() {
        return DEFINITION;
    }

    @Override
    public Result execute(Map<String, Object> arguments) {
        if (!arguments.keySet().stream().allMatch(key -> "zone".equals(key))) {
            throw new IllegalArgumentException("Unexpected time tool arguments");
        }
        Object supplied = arguments.getOrDefault("zone", "UTC");
        if (!(supplied instanceof String zoneName) || zoneName.isBlank() || zoneName.length() > 64) {
            throw new IllegalArgumentException("Invalid time zone");
        }
        try {
            ZoneId zone = ZoneId.of(zoneName);
            ZonedDateTime now = clock.instant().atZone(zone);
            return new Result(Map.of(
                    "zone", zone.getId(),
                    "instant", now.toInstant().toString(),
                    "localDate", now.toLocalDate().toString(),
                    "localTime", now.toLocalTime().toString(),
                    "dayOfWeek", now.getDayOfWeek().toString(),
                    "utcOffset", now.getOffset().toString()),
                    () -> {});
        } catch (ZoneRulesException invalid) {
            throw new IllegalArgumentException("Unknown time zone", invalid);
        }
    }
}
