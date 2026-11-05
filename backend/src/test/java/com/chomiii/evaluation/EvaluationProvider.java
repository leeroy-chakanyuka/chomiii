package com.chomiii.evaluation;

import com.chomiii.provider.ChatProvider;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Scripted provider for offline pipeline checks. No network or keys are used. */
final class EvaluationProvider implements ChatProvider {
    record Call(String kind, String text, ChatInput input, String output, long durationNanos) {}
    private final Map<String, String> extractions;
    private final Map<String, String> answers;
    private final List<Call> calls = new CopyOnWriteArrayList<>();

    EvaluationProvider(Map<String, String> extractions, Map<String, String> answers) {
        this.extractions = Map.copyOf(extractions);
        this.answers = Map.copyOf(answers);
    }

    @Override public String id() { return "gemini"; }

    @Override public Generation open(ChatInput input, String apiKey) {
        boolean extraction = input.systemInstruction().contains("memory-extraction classifier");
        String text = input.messages().getLast().text();
        String output = extraction ? extractions.getOrDefault(text, "{\"memories\":[]}")
                : answers.getOrDefault(text, "Understood.");
        return new Generation() {
            @Override public void stream(Consumer<String> delta) {
                long start = System.nanoTime();
                delta.accept(output);
                calls.add(new Call(extraction ? "extraction" : "chat", text, input, output,
                        System.nanoTime() - start));
            }
            @Override public void close() { }
        };
    }

    List<Call> calls() { return List.copyOf(calls); }
}
