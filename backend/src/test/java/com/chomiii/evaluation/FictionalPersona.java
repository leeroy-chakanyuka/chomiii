package com.chomiii.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * A deterministic, entirely fictional everyday persona for exercising Chomiii's
 * memory, planning and tools without any real user data: "Vera Aldwyn", a
 * dental hygienist in the invented city of Fairhaven. Every name, employer,
 * street and date below is invented and stable at compile time; nothing is
 * random and nothing describes a real person, business or place.
 *
 * <p>The persona is reusable in two ways through the existing harness:
 * <ul>
 *   <li>{@link #fixture()} builds a harness-compatible {@link EvaluationFixture}
 *   (validated by {@link FixtureLoader} and {@link PlanningContract}) so the
 *   persona can replay through {@link MemoryEvaluationRunner} and the
 *   evaluation suites like any other dataset fixture.</li>
 *   <li>{@link PersonaSeed} writes the same statements into a disposable,
 *   owner-scoped store for seeded-recall tests that are independent of the
 *   scripted chat learning path.</li>
 * </ul>
 *
 * <p>Life areas covered: employment and career, education, projects,
 * preferences, location and transport, routines, appointments and deadlines,
 * short- and long-term goals, changed facts with corrections, ambiguous
 * statements, and distractors that must never be stored for this user.
 */
final class FictionalPersona {
    static final String ID = "stage-a-persona-001";
    static final String NAME = "Vera Aldwyn";

    /** One invented personal statement. The topic is one of the memory.search topics. */
    record Fact(String id, String statement, String topic) {}

    /** A changed fact: the outdated statement, its correction, and how the user says it. */
    record Correction(String id, String topic, String outdated, String corrected, String correctionPhrase) {
        Fact outdatedFact() { return new Fact(id + "-outdated", outdated, topic); }
        Fact correctedFact() { return new Fact(id + "-corrected", corrected, topic); }
    }

    /** A deliberately ambiguous statement plus the information Chomiii must ask for. */
    record Ambiguity(String id, String statement, String missingInformation) {}

    /** A statement that must never become one of this user's memories. */
    record Distractor(String statement, String reason, String category) {}

    private FictionalPersona() {}

    /** Durable everyday facts in a fixed order; each maps to a derived profile area. */
    static List<Fact> durable() {
        return List.of(
            new Fact("career-employer", "I work as a dental hygienist at the Brightwater Clinic.", "work"),
            new Fact("career-shifts", "I work the morning shift from Monday to Thursday.", "work"),
            new Fact("location-home", "I live in the Northgate neighborhood of Fairhaven.", "location"),
            new Fact("location-commute", "My commute is the 21 bus from Northgate to the clinic.", "location"),
            new Fact("education-diploma", "I finished my dental hygiene diploma at Colton Community College in 2019.", "education"),
            new Fact("education-course", "I am studying for a workplace training certificate this semester.", "education"),
            new Fact("education-exams", "My certificate exams are next week and I study on Saturday afternoons.", "education"),
            new Fact("projects-website", "I am building a small recipe-sharing website with my cousin.", "projects"),
            new Fact("projects-garden", "My community garden project has a public website.", "projects"),
            new Fact("preferences-books", "I love crime novels and I collect old maps.", "preferences"),
            new Fact("routines-jog", "I jog along the canal on Tuesday and Thursday mornings.", "schedules"),
            new Fact("food-dinners", "I cook vegetarian dinners on most weeknights.", "food"),
            new Fact("appointments-car", "I have a car service appointment on Friday afternoon.", "schedules"),
            new Fact("projects-deadline", "The demo deadline for my recipe website is the 30th of this month.", "projects"));
    }

    /** Short- and long-term goals, in a fixed order. */
    static List<Fact> goals() {
        return List.of(
            new Fact("goal-short-website", "This month I want to finish the sign-up page for my recipe website.", "projects"),
            new Fact("goal-long-teaching", "Within two years I want to teach short courses at the community college.", "education"));
    }

    static List<Fact> durableAndGoals() {
        var facts = new ArrayList<Fact>(durable());
        facts.addAll(goals());
        return List.copyOf(facts);
    }

    /**
     * Facts that changed and how the persona states the change. Each corrected
     * statement deliberately conflicts with the active outdated one under
     * {@link com.chomiii.memory.MemoryConflictDetector} rules, so tests can
     * exercise both the user-edit path and the learned-suggestion path.
     */
    static List<Correction> corrections() {
        return List.of(
            new Correction("correction-commute", "location", "I take the train to work.",
                    "I now take the bus to work instead of the train.",
                    "Actually I now take the bus to work instead of the train."),
            new Correction("correction-residence", "location", "I live in the Old Dock flats of Fairhaven.",
                    "I moved to the Millbrook Court flat in September.",
                    "By the way, I moved to the Millbrook Court flat in September."),
            new Correction("correction-dinner", "food", "I prefer rice and beans for dinner.",
                    "I no longer prefer rice and beans for dinner.",
                    "Actually, I no longer prefer rice and beans for dinner."));
    }

    /** Statements that are true but underspecified; Chomiii should ask, not assume. */
    static List<Ambiguity> ambiguous() {
        return List.of(
            new Ambiguity("ambiguous-meeting", "My meeting with Sam keeps getting moved around.",
                    "Which meeting and which Sam"),
            new Ambiguity("ambiguous-sister", "My sister also works at the clinic now.",
                    "Which sister and in what role"),
            new Ambiguity("ambiguous-deadline", "I think the demo deadline is Thursday, or maybe Friday.",
                    "Which day the deadline falls on"));
    }

    /** Statements that must never be stored for this persona. */
    static List<Distractor> distractors() {
        return List.of(
            new Distractor("Priya from the clinic said she hates the smell of coffee.",
                    "A quoted preference of a colleague, not the user's own.", "wrong_person"),
            new Distractor("Sam is moving to Riverton next month.",
                    "Another person's relocation is not this user's fact.", "wrong_person"),
            new Distractor("I never work Fridays.",
                    "Never stated; the persona works Monday to Thursday.", "over_inference"),
            new Distractor("My bus was twenty minutes late this morning.",
                    "One-time trivia, not a durable fact.", "temporary"),
            new Distractor("The clinic's alarm code is 4821.",
                    "A workplace credential, never save it.", "sensitive_data"));
    }

    /** The planning instruction shared by the fixture and the seed-based tests. */
    static String planningQuestion() {
        return "Help me plan my week: I have a car service appointment on Friday afternoon "
                + "and the recipe website demo deadline on the 30th.";
    }

    /**
     * The persona as a harness fixture: a multi-turn conversation whose scripted
     * extractions stand in for the model, lifecycle edits standing in for the
     * user's corrections, and retrieval checks for seeded-style recall. Passes
     * {@link FixtureLoader} validation unchanged.
     */
    static EvaluationFixture fixture() {
        var json = new ObjectMapper();
        var turns = new ArrayList<EvaluationFixture.Turn>();
        var expected = new ArrayList<EvaluationFixture.ExpectedMemory>();
        var lifecycle = new ArrayList<EvaluationFixture.LifecycleAction>();
        int index = 1;
        for (var fact : durableAndGoals()) {
            String turnId = "t" + index++;
            turns.add(turn(json, turnId, fact.statement(), "fact", 0.95));
            expected.add(new EvaluationFixture.ExpectedMemory(fact.id(), fact.statement(), List.of(),
                    List.of(turnId), "explicit_fact", importance(fact.id()), "active", true));
        }
        for (var correction : corrections()) {
            String turnId = "t" + index++;
            turns.add(turn(json, turnId, correction.outdated(), "fact", 0.95));
            expected.add(new EvaluationFixture.ExpectedMemory(correction.id() + "-outdated",
                    correction.outdated(), List.of(), List.of(turnId), "explicit_fact", "medium",
                    "absent", true));
            expected.add(new EvaluationFixture.ExpectedMemory(correction.id() + "-corrected",
                    correction.corrected(), List.of(), List.of(turnId), "explicit_fact", "high",
                    "active", true, "manual_edit"));
            lifecycle.add(new EvaluationFixture.LifecycleAction(turnId, "edit",
                    correction.id() + "-outdated", correction.corrected()));
        }
        for (var ambiguity : ambiguous()) {
            String turnId = "t" + index++;
            turns.add(turn(json, turnId, ambiguity.statement(), "inference", 0.8));
            expected.add(new EvaluationFixture.ExpectedMemory(ambiguity.id(), ambiguity.statement(),
                    List.of(), List.of(turnId), "inference", "low", "suggested", true));
        }
        var forbidden = distractors().stream()
                .map(item -> new EvaluationFixture.ForbiddenMemory(item.statement(), item.reason(), item.category()))
                .toList();
        var checks = List.of(
                new EvaluationFixture.RetrievalCheck("Where do I live?", List.of("location-home")),
                new EvaluationFixture.RetrievalCheck("Do I take the bus or the train to work?",
                        List.of("location-commute", "correction-commute-corrected")),
                new EvaluationFixture.RetrievalCheck("Which novels do I love?", List.of("preferences-books")),
                new EvaluationFixture.RetrievalCheck("When is my car service appointment?",
                        List.of("appointments-car")),
                new EvaluationFixture.RetrievalCheck("What am I studying this semester?",
                        List.of("education-course")));
        return new EvaluationFixture(ID, 1,
                "Deterministic fictional everyday persona (" + NAME + " of Fairhaven); no real data.",
                "conversations", "medium", "high", "conversation", true,
                List.copyOf(turns), List.copyOf(expected), List.copyOf(forbidden), checks,
                List.copyOf(lifecycle), planning());
    }

    /**
     * Planning expectations for the persona: an ordinary weekly conflict between
     * an appointment and a project deadline, validated by {@link PlanningContract}.
     */
    static JsonNode planning() {
        var json = new ObjectMapper();
        var planning = json.createObjectNode();
        planning.put("instruction", planningQuestion());
        planning.putArray("oracleMemoryIds").add("appointments-car").add("projects-deadline");
        planning.putArray("requiredGoals")
                .add("Resolve the clash between the Friday car service and the work shift")
                .add("Keep the recipe website demo on track for the 30th");
        planning.putArray("toolsAvailable")
                .add("calendar.search").add("calendar.update").add("reminder.create");
        planning.putArray("missingInformation")
                .add("The exact time of the car service appointment")
                .add("Whether the demo deadline can move");
        planning.putArray("forbiddenActions").add("Change the appointment or demo without asking");
        var steps = planning.putArray("requiredSteps");
        var find = steps.addObject();
        find.put("id", "find").put("operation", "calendar.search")
                .put("requiresApproval", false);
        find.putArray("dependsOn");
        var remind = steps.addObject();
        remind.put("id", "remind").put("operation", "reminder.create").put("requiresApproval", true);
        remind.putArray("dependsOn").add("find");
        var reschedule = steps.addObject();
        reschedule.put("id", "reschedule").put("operation", "calendar.update")
                .put("requiresApproval", true);
        reschedule.putArray("dependsOn").add("find");
        return planning;
    }

    private static EvaluationFixture.Turn turn(ObjectMapper json, String id, String statement,
            String kind, double confidence) {
        var extraction = json.createObjectNode();
        var item = extraction.putArray("memories").addObject();
        item.put("content", statement).put("kind", kind).put("confidence", confidence)
                .put("evidence", statement);
        return new EvaluationFixture.Turn(id, "user", statement, "eligible", extraction);
    }

    private static String importance(String factId) {
        return factId.startsWith("career-") || factId.startsWith("location-") ? "high" : "medium";
    }
}
