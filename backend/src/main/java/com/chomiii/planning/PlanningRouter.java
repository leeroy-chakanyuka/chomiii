package com.chomiii.planning;

import java.util.Locale;
import java.util.regex.Pattern;

/** Cheap routing for explicit planning language; a user can also request planning in the composer. */
public final class PlanningRouter {
    private static final Pattern PLANNING = Pattern.compile(
            "\\b(plan|organize|organise|coordinate|reschedule|rearrange|arrange)\\b|\\bsort\\b.{0,40}\\bout\\b");
    private static final Pattern REQUEST = Pattern.compile(
            "\\b(help|please|can you|could you|would you|need to|want to|let's|sort out|reschedule|rearrange|organize|organise|coordinate|arrange|plan my|plan the)\\b");
    private static final Pattern AMBIGUOUS = Pattern.compile(
            "\\b(everything|tomorrow|week|schedule|appointments)\\b.*\\b(fix|sort|organize|organise)\\b");
    private static final Pattern EXPLANATION = Pattern.compile(
            "\\b(explain|define|what is|what does)\\b.*\\b(plan|planning|reschedule|organize|organise)\\b");

    private PlanningRouter() {}

    public static boolean matches(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (EXPLANATION.matcher(lower).find()) return false;
        return PLANNING.matcher(lower).find() && REQUEST.matcher(lower).find() || AMBIGUOUS.matcher(lower).find();
    }
}
