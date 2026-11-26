package com.chomiii.planning;

import com.chomiii.planning.execution.PlanExecution;
import java.util.stream.Collectors;

/** User-visible truth from saved execution state, not the model's completion claims. */
public final class PlanReply {
    private PlanReply() {}

    public static boolean needsClarification(Plan plan) { return !questions(plan).isEmpty(); }

    public static String prepared(Plan plan) {
        String questions = questions(plan);
        return questions.isEmpty() ? "I'm checking the requested steps. No to-do changes have been made." : questions;
    }

    public static String result(PlanExecution execution) {
        if (execution.state() == PlanExecution.State.CANCELLED) return "Stopped. No further steps will run.";
        if (execution.steps().stream().anyMatch(step -> step.state() == PlanExecution.State.AWAITING_APPROVAL)) {
            return "I prepared the to-do for your approval.";
        }
        if (execution.state() == PlanExecution.State.COMPLETED) {
            var observation = execution.steps().reversed().stream().filter(step -> step.observation() != null)
                    .findFirst().map(PlanExecution.Step::observation).orElse(null);
            if (observation == null) return "The requested to-do steps are complete.";
            if (observation.todos().isEmpty()) return "No to-dos matched that check.";
            String rows = observation.todos().stream().map(todo -> "- " + plain(todo.title()) + " (" + todo.status() + ")")
                    .collect(Collectors.joining("\n"));
            return "Here are the to-dos from that check:\n" + rows
                    + (observation.truncated() ? "\nThere may be more; this is a limited list." : "");
        }
        String questions = questions(execution.proposal());
        if (!questions.isEmpty()) return questions;
        if (execution.steps().stream().anyMatch(step -> step.state() == PlanExecution.State.BLOCKED
                && step.capability().equals("todo.complete"))) {
            return "I couldn't identify a single matching open to-do. Which task did you mean?";
        }
        return "I couldn't finish the requested steps. No further action is confirmed; please clarify what you'd like to do next.";
    }

    private static String questions(Plan plan) {
        return plan.missingInformation().stream()
                .filter(question -> question.kind().equals("planning") || question.kind().equals("execution"))
                .map(question -> plain(question.question())).collect(Collectors.joining("\n"));
    }

    private static String plain(String text) {
        return text.replace("\\", "\\\\").replace("*", "\\*").replace("_", "\\_")
                .replace("[", "\\[").replace("]", "\\]").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\r", " ").replace("\n", " ");
    }
}
