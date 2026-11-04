package com.chomiii.provider;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProviderCatalogController {
    private final ProviderCatalog catalog;

    public ProviderCatalogController(ProviderCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/api/chat/providers")
    public List<ProviderCatalog.Provider> providers() {
        return catalog.providers();
    }
}
