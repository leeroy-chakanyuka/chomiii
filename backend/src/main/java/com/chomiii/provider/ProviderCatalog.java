package com.chomiii.provider;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ProviderCatalog {
    private final List<Model> models;

    public ProviderCatalog(@Value("${chomiii.gemini.models:gemini-3.5-flash-lite,gemini-3.6-flash}") List<String> ids) {
        models = ids.stream().map(String::trim).filter(id -> !id.isEmpty())
                .map(id -> new Model(id, id)).toList();
        if (models.isEmpty()) {
            throw new IllegalArgumentException("At least one Gemini model must be configured");
        }
    }

    public List<Provider> providers() {
        return List.of(new Provider("gemini", "Gemini", models.getFirst().id(), models));
    }

    public boolean supports(String provider, String model) {
        return "gemini".equals(provider) && models.stream().anyMatch(candidate -> candidate.id().equals(model));
    }

    public record Provider(String id, String name, String defaultModel, List<Model> models) {}
    public record Model(String id, String name) {}
}
