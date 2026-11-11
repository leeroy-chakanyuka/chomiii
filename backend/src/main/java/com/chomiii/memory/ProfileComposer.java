package com.chomiii.memory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Deterministic composition of the derived profile: approved active canonical
 * memory texts are grouped by rule-based category and each category becomes one
 * bounded, human-readable paragraph built directly from the memory text, with
 * every included fact joined as its own complete sentence. No
 * model inference and no text generation: the paragraph contains only the
 * approved memory contents themselves, and each paragraph keeps the canonical
 * memory IDs it was composed from.
 */
final class ProfileComposer {
    static final int MAX_PARAGRAPH_CHARS = 600;
    static final int MAX_SNAPSHOT_CHARS = 1_400;

    /**
     * Credential-style assignments ("api_key is sk-live-abc12345"), where the
     * value is a long, unspaced token containing digits; and PEM private key
     * blocks. Deliberately narrow so ordinary sentences (for example "the
     * secret ingredient is butter") are kept. Best-effort, deterministic.
     */
    private static final Pattern SECRET = Pattern.compile(
            "(?i)\\b(api[_-]?key|secret|password|passwd|token|credential|private[_-]?key)\\b"
                    + "\\s*(?:[:=]|\\bis\\b)\\s*(\\S{8,})|-----BEGIN [A-Z ]+PRIVATE KEY-----");
    private static final int MIN_SECRET_TOKEN_DIGITS = 2;

    private ProfileComposer() {}

    /** True when content is deliberately left out of derived paragraphs. */
    static boolean excluded(String content) {
        String text = collapsed(content);
        if (text.isEmpty()) return true;
        var match = SECRET.matcher(text);
        while (match.find()) {
            if (match.group(1) == null) return true; // PEM private key block
            String value = match.group(2);
            if (value == null) continue;
            long digits = value.chars().filter(Character::isDigit).count();
            if (digits >= MIN_SECRET_TOKEN_DIGITS) return true;
        }
        return false;
    }

    /** One bounded paragraph per category that has approved active memories. */
    static List<ProfileParagraph> paragraphs(List<PersonalMemory> activeMemories) {
        Map<ProfileCategory, List<Cited>> grouped = new LinkedHashMap<>();
        for (PersonalMemory memory : activeMemories) {
            String text = collapsed(memory.content());
            if (excluded(text)) continue;
            grouped.computeIfAbsent(ProfileCategory.of(text), ignored -> new ArrayList<>())
                    .add(new Cited(text, memory.id()));
        }
        List<ProfileParagraph> paragraphs = new ArrayList<>();
        for (ProfileCategory category : ProfileCategory.values()) {
            List<Cited> items = grouped.get(category);
            if (items == null) continue;
            Composed composed = boundedJoin(items, MAX_PARAGRAPH_CHARS);
            paragraphs.add(new ProfileParagraph(category.key(),
                    composed.text(), composed.sourceIds()));
        }
        return List.copyOf(paragraphs);
    }

    /** Snapshot text plus exactly the paragraphs included in that text. */
    record SnapshotSelection(String text, List<ProfileParagraph> included) {}

    /**
     * Labeled lines ("Food: ...") in the given (composer declaration) order,
     * bounded for chat context. The returned selection makes the snapshot
     * budget cut explicit so citations can cover exactly the paragraphs the
     * text actually contains.
     */
    static SnapshotSelection select(List<ProfileParagraph> paragraphs) {
        StringBuilder output = new StringBuilder();
        List<ProfileParagraph> included = new ArrayList<>();
        for (ProfileParagraph paragraph : paragraphs) {
            String line = label(paragraph.category()) + ": " + paragraph.text() + "\n";
            if (output.length() + line.length() > MAX_SNAPSHOT_CHARS) break;
            output.append(line);
            included.add(paragraph);
        }
        return new SnapshotSelection(output.toString().strip(), List.copyOf(included));
    }

    static String snapshot(List<ProfileParagraph> paragraphs) {
        return select(paragraphs).text();
    }

    /** The lowercase category key, exposed for tests and API symmetry. */
    static String categoryOf(String content) {
        return ProfileCategory.of(collapsed(content)).key();
    }

    private static String label(String categoryKey) {
        return ProfileCategory.valueOf(categoryKey.toUpperCase(java.util.Locale.ROOT)).label();
    }

    private record Cited(String text, UUID memoryId) {}

    /** Paragraph text plus exactly the memories whose text contributed to it. */
    private record Composed(String text, List<UUID> sourceIds) {}

    private static Composed boundedJoin(List<Cited> items, int maxChars) {
        StringBuilder text = new StringBuilder();
        List<UUID> cited = new ArrayList<>();
        for (Cited item : items) {
            String sentence = sentence(item.text());
            String candidate = text.isEmpty() ? sentence : text + " " + sentence;
            if (candidate.length() > maxChars) {
                if (text.isEmpty()) {
                    // A single oversized fact is truncated at a word boundary
                    // and still terminated as its own sentence; its memory is
                    // still the source of the text kept.
                    text.append(sentence(truncateAtWord(item.text(), maxChars - 1)));
                    cited.add(item.memoryId());
                }
                break;
            }
            text.setLength(0);
            text.append(candidate);
            cited.add(item.memoryId());
        }
        return new Composed(text.toString(), List.copyOf(cited));
    }

    /** A fact rendered as a complete sentence, respecting existing punctuation. */
    private static String sentence(String fact) {
        char last = fact.charAt(fact.length() - 1);
        boolean terminated = last == '.' || last == '!' || last == '?';
        return terminated ? fact : fact + ".";
    }

    private static String truncateAtWord(String sentence, int maxChars) {
        int cut = sentence.lastIndexOf(' ', maxChars - 1);
        if (cut <= 0) cut = maxChars - 1;
        return sentence.substring(0, cut).strip();
    }

    private static String collapsed(String content) {
        return content == null ? "" : content.replaceAll("\\s+", " ").strip();
    }
}
