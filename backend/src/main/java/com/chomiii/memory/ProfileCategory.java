package com.chomiii.memory;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Rule-based classification of canonical memory content into a fixed set of
 * profile categories. Deterministic by construction: the first category whose
 * keyword matches wins, categories are checked in declaration order, and no
 * model inference is involved. {@link #OTHER} is the fallback so every approved
 * active memory can be cited by exactly one paragraph.
 */
public enum ProfileCategory {
    FOOD("Food", "food", "meal", "meals", "eat", "eats", "eating", "grocery", "groceries",
            "cook", "cooks", "cooking", "bake", "baking", "recipe", "breakfast", "lunch",
            "dinner", "supper", "snack", "vegetarian", "vegan", "pescatarian", "gluten",
            "dairy", "coffee", "espresso", "latte", "cappuccino", "flat white", "flat whites",
            "tea", "milk", "oat", "oats", "oatmeal", "bread", "rice", "pasta", "noodles",
            "fruit", "vegetable", "vegetables", "allergic", "allergy", "intolerant",
            "cuisine", "restaurant", "takeout", "takeaway", "spicy"),
    LOCATION("Location", "live in", "lives in", "live at", "lives at", "moved to", "move to",
            "moving to", "city", "town", "village", "neighborhood", "neighbourhood", "suburb",
            "address", "hometown", "based in", "commute", "commuting", "apartment", "flat",
            "house", "rent", "landlord", "district", "province", "country"),
    WORK("Work", "work", "works", "working", "job", "jobs", "employer", "employee", "manager",
            "colleague", "colleagues", "coworker", "coworkers", "office", "shift", "shifts",
            "meeting", "meetings", "standup", "client", "clients", "deadline", "deadlines",
            "salary", "team", "teams", "career", "boss", "workplace", "freelance",
            "freelancing", "internship", "overtime", "resign", "promotion"),
    EDUCATION("Education", "school", "schools", "study", "studies", "studying", "university",
            "college", "degree", "diploma", "class", "classes", "course", "courses",
            "semester", "exam", "exams", "homework", "assignment", "teacher", "professor",
            "lecturer", "student", "lecture", "lectures", "graduation", "graduate", "thesis",
            "dissertation", "tuition", "campus"),
    SCHEDULES("Schedules", "schedule", "schedules", "scheduling", "routine", "monday",
            "mondays", "tuesday", "tuesdays", "wednesday", "wednesdays", "thursday",
            "thursdays", "friday", "fridays", "saturday", "saturdays", "sunday", "sundays",
            "weekday", "weekdays", "weekend", "weekends", "daily", "weekly", "monthly",
            "every morning", "every evening", "every night", "every day", "morning",
            "afternoon", "evening", "night", "wake", "wakes", "bedtime", "gym", "jog",
            "jogging", "run", "runs", "running", "yoga", "appointment", "appointments",
            "calendar", "reminder", "reminders"),
    PROJECTS("Projects", "project", "projects", "prototype", "startup", "launch", "launching",
            "website", "webapp", "web app", "app", "apps", "coding", "code", "programming",
            "develop", "developing", "developer", "building", "roadmap", "backlog", "sprint",
            "release", "deploy", "deploying", "hackathon", "portfolio", "side project", "demo"),
    PREFERENCES("Preferences", "like", "likes", "love", "loves", "prefer", "prefers",
            "favorite", "favourite", "hate", "hates", "dislike", "dislikes", "enjoy",
            "enjoys", "fan of", "keen on", "avoid", "avoids"),
    OTHER("Other");

    private final String label;
    private final List<Pattern> keywords;

    ProfileCategory(String label, String... keywords) {
        this.label = label;
        this.keywords = List.of(keywords).stream()
                .map(keyword -> Pattern.compile("\\b" + Pattern.quote(keyword) + "\\b"))
                .toList();
    }

    /** The lowercase key persisted in derived rows and exposed by the API. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Human-readable label used in the composed snapshot text. */
    public String label() {
        return label;
    }

    public static ProfileCategory of(String content) {
        String normalized = content.toLowerCase(Locale.ROOT);
        for (ProfileCategory category : values()) {
            for (Pattern keyword : category.keywords) {
                if (keyword.matcher(normalized).find()) return category;
            }
        }
        return OTHER;
    }
}
