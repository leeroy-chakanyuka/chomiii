package com.chomiii.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Trusted, classpath-backed instructions. Skill selection is deterministic and local:
 * no additional model request, external lookup, or per-turn filesystem access.
 */
public final class AgentInstructions {
    private static final String ROOT = "agent/";
    private static final Pattern EXPLICIT_SKILL = Pattern.compile("^/skill\\s+([a-z0-9-]+)(?:\\s|$)");
    private final Map<String, Skill> skills;
    private final String basePrompt;

    public AgentInstructions() {
        this(AgentInstructions.class.getClassLoader());
    }

    AgentInstructions(ClassLoader loader) {
        String identity = read(loader, ROOT + "SYSTEM.md").strip();
        if (identity.isEmpty()) throw new IllegalStateException("Agent identity is empty");
        Map<String, Skill> found = new LinkedHashMap<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver(loader)
                    .getResources("classpath*:agent/skills/*/SKILL.md");
            Arrays.sort(resources, Comparator.comparing(Resource::getDescription));
            for (Resource resource : resources) {
                Skill skill = parse(read(resource));
                if (!resource.getURL().toExternalForm().replace('\\', '/').endsWith("/" + skill.name() + "/SKILL.md")) {
                    throw new IllegalStateException("Skill name must match its directory: " + skill.name());
                }
                if (found.putIfAbsent(skill.name(), skill) != null) {
                    throw new IllegalStateException("Duplicate agent skill: " + skill.name());
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load agent skills", exception);
        }
        if (found.isEmpty()) throw new IllegalStateException("No agent skills were packaged");
        skills = Collections.unmodifiableMap(new LinkedHashMap<>(found));
        StringBuilder catalog = new StringBuilder(identity)
                .append("\n\n## Available guidance (not executable tools)\n");
        found.values().forEach(skill -> catalog.append("- ")
                .append(skill.name()).append(": ").append(skill.description()).append('\n'));
        catalog.append("\nA skill's full instructions are included only when selected. ")
                .append("These skills do not grant network, filesystem, memory, scheduling, or action tools.");
        basePrompt = catalog.toString();
        if (basePrompt.length() > 5000) throw new IllegalStateException("Agent base prompt exceeded its budget");
    }

    public List<String> skillNames() {
        return skills.keySet().stream().sorted().toList();
    }

    public String forMessage(String userText) {
        Skill match = choose(userText == null ? "" : userText);
        if (match == null) return basePrompt;
        return basePrompt + "\n\n## Activated skill: " + match.name() + "\n" + match.instructions();
    }

    private Skill choose(String message) {
        String normalized = message.toLowerCase(Locale.ROOT).strip();
        Matcher explicit = EXPLICIT_SKILL.matcher(normalized);
        if (explicit.find()) return skills.get(explicit.group(1));

        String words = " " + normalize(normalized) + " ";
        Skill best = null;
        int highest = 0;
        for (Skill skill : skills.values()) {
            for (String trigger : skill.triggers()) {
                String phrase = " " + normalize(trigger) + " ";
                if (phrase.length() > 2 && words.contains(phrase) && phrase.length() > highest) {
                    best = skill;
                    highest = phrase.length();
                }
            }
        }
        return best;
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    private static Skill parse(String source) {
        String normalized = source.replace("\r\n", "\n").strip();
        if (!normalized.startsWith("---\n")) {
            throw new IllegalStateException("SKILL.md must start with YAML frontmatter");
        }
        int end = normalized.indexOf("\n---\n", 4);
        if (end < 0) throw new IllegalStateException("SKILL.md is missing closing frontmatter");
        String header = normalized.substring(4, end);
        String name = field(header, "name");
        if (!name.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw new IllegalStateException("Invalid skill name: " + name);
        }
        String description = field(header, "description");
        if (description.length() > 240) throw new IllegalStateException("Skill description too long: " + name);
        String triggers = field(header, "triggers");
        String instructions = normalized.substring(end + 5).strip();
        if (instructions.isBlank() || instructions.length() > 5000) {
            throw new IllegalStateException("Skill instruction size is invalid: " + name);
        }
        List<String> phrases = Arrays.stream(triggers.split(","))
                .map(String::strip).filter(value -> !value.isEmpty()).toList();
        if (phrases.isEmpty() || phrases.size() > 20 || phrases.stream().anyMatch(value -> value.length() > 64)) {
            throw new IllegalStateException("Skill triggers are invalid: " + name);
        }
        return new Skill(name, description, phrases, instructions);
    }

    private static String field(String header, String key) {
        return Arrays.stream(header.split("\n"))
                .filter(line -> line.startsWith(key + ": "))
                .map(line -> line.substring(key.length() + 2).strip())
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing skill metadata: " + key));
    }

    private static String read(ClassLoader loader, String path) {
        try (InputStream stream = loader.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("Missing agent resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read agent resource: " + path, exception);
        }
    }

    private static String read(Resource resource) {
        try (InputStream stream = resource.getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read " + resource.getDescription(), exception);
        }
    }

    private record Skill(String name, String description, List<String> triggers, String instructions) {}
}
