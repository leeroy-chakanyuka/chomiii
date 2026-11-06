package com.chomiii.planning;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Describes possible future work. No entry can execute an operation. */
public final class CapabilityCatalog {
    public record Capability(String id, String description, String availability, String approvalHint) {}

    private static final List<Capability> ITEMS = List.of(
            new Capability("user.prepare", "Prepare a draft or checklist from supplied information; cannot fetch current data", "available_for_planning", "not_required"),
            new Capability("user.decide", "Ask the person to choose or provide information", "available_for_planning", "not_required"),
            new Capability("calendar.search", "Find calendar events", "not_connected", "not_required"),
            new Capability("calendar.availability", "Check calendar availability", "not_connected", "not_required"),
            new Capability("calendar.update", "Change a calendar event", "not_connected", "required"),
            new Capability("gmail.search", "Find messages or recipients", "not_connected", "not_required"),
            new Capability("gmail.send", "Send a message", "not_connected", "required"),
            new Capability("web.search", "Look up current information", "not_connected", "not_required"),
            new Capability("shopping.order", "Place an order", "not_connected", "required"),
            new Capability("reminder.create", "Create a reminder", "not_connected", "required"));
    private static final Map<String, Capability> BY_ID = ITEMS.stream()
            .collect(Collectors.toUnmodifiableMap(Capability::id, Function.identity()));

    private CapabilityCatalog() {}

    public static List<Capability> all() { return ITEMS; }

    public static Capability get(String id) {
        Capability capability = BY_ID.get(id);
        if (capability == null) throw new IllegalArgumentException("Unknown planning capability");
        return capability;
    }
}
