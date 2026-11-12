package com.chomiii.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Model output is untrusted. Validate confidence, evidence and sensitive terms. */
public final class MemoryCandidateParser {
    private static final int MAX_RESPONSE_CHARS = 4_000;
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)\\b(password|passphrase|api[ -]?key|secret[ -]?key|otp|pin|cvv|credit[ -]?card|"
            + "bank[ -]?account|account[ -]?number|identity[ -]?number|passport|diagnos(?:is|ed)|"
            + "medication|medical|religion|religious|politic(?:s|al)|sexual|ethnic(?:ity)?|"
            + "racial|pregnan(?:t|cy)|cancer|adhd|depression|suicide|"
            + "ignore[ -]previous|system[ -]prompt|developer[ -]instructions)\\b");

    public record Candidate(String content, String source, double confidence, String topic) {}

    private MemoryCandidateParser() {}

    public static List<Candidate> parse(String response, String userMessage, ObjectMapper mapper) {
        if (response == null || response.length() > MAX_RESPONSE_CHARS || userMessage == null
                || SENSITIVE.matcher(userMessage).find()) return List.of();
        String raw = response.strip();
        String fence = Character.toString((char) 96).repeat(3);
        if (raw.startsWith(fence)) {
            int firstLine = raw.indexOf('\n');
            int end = raw.lastIndexOf(fence);
            if (firstLine < 0 || end <= firstLine) return List.of();
            raw = raw.substring(firstLine + 1, end).strip();
        }
        try {
            JsonNode root = mapper.readTree(raw);
            JsonNode items = root.path("memories");
            if (!items.isArray() || items.size() > 2) return List.of();
            List<Candidate> found = new ArrayList<>();
            for (JsonNode item : items) {
                String content = item.path("content").asText("").strip();
                String evidence = item.path("evidence").asText("").strip();
                String kind = item.path("kind").asText("");
                JsonNode confidenceNode = item.path("confidence");
                if (!confidenceNode.isNumber()) continue;
                double confidence = confidenceNode.asDouble();
                if (!Double.isFinite(confidence) || confidence < 0.70 || confidence > 1.0
                        || content.length() < 8 || content.length() > 500
                        || evidence.length() < 8
                        || !userMessage.toLowerCase(Locale.ROOT)
                                .contains(evidence.toLowerCase(Locale.ROOT))
                        || SENSITIVE.matcher(content).find()) continue;
                if (!"fact".equals(kind) && !"inference".equals(kind)) continue;
                found.add(new Candidate(content,
                        "fact".equals(kind) ? "conversation_fact" : "inferred", confidence,
                        sanitizedTopic(item.path("topic").asText(""))));
            }
            return List.copyOf(found);
        } catch (Exception invalid) {
            return List.of();
        }
    }

    /**
     * An optional general subject label from the regular extraction pass.
     * Unusable labels are dropped, never the grounded candidate itself.
     */
    private static String sanitizedTopic(String raw) {
        String topic = raw == null ? "" : raw.replaceAll("\\s+", " ").strip();
        if (topic.isEmpty() || topic.length() > 60) return null;
        if (SENSITIVE.matcher(topic).find()) return null;
        return topic;
    }
}
