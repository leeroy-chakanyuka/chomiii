package com.chomiii;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.MapPropertySource;

/**
 * Pins the database configuration contract: MySQL with a default local URL
 * for the "local" profile, explicitly-required PostgreSQL URL in "prod", and
 * credentials always supplied through CHOMIII_DB_* environment variables —
 * never baked into source.
 */
class ApplicationConfigTest {

    YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

    MapPropertySource load(String name) throws IOException {
        return (MapPropertySource) loader
                .load(name, new ClassPathResource(name))
                .getFirst();
    }

    @Test
    void localProfileTargetsMySqlWithOnlyTheUrlDefaulted() throws IOException {
        MapPropertySource local = load("application-local.yml");

        assertThat(local.getProperty("spring.datasource.url"))
                .isEqualTo("${CHOMIII_DB_URL:jdbc:mysql://localhost:3306/chomiii}");
        assertThat(local.getProperty("spring.datasource.username")).isEqualTo("${CHOMIII_DB_USER}");
        assertThat(local.getProperty("spring.datasource.password")).isEqualTo("${CHOMIII_DB_PASSWORD}");
    }

    @Test
    void prodProfileRequiresTheExplicitPostgreSqlUrl() throws IOException {
        MapPropertySource prod = load("application-prod.yml");

        assertThat(prod.getProperty("spring.datasource.url")).isEqualTo("${CHOMIII_DB_URL}");
        assertThat(prod.getProperty("spring.datasource.username")).isEqualTo("${CHOMIII_DB_USER}");
        assertThat(prod.getProperty("spring.datasource.password")).isEqualTo("${CHOMIII_DB_PASSWORD}");
    }

    @Test
    void baseConfigurationDefaultsToLocalAndNeverCarriesCredentials() throws IOException {
        MapPropertySource base = load("application.yml");

        assertThat(base.getProperty("spring.profiles.default")).isEqualTo("local");
        assertThat(base.getProperty("spring.datasource.url")).isNull();
        assertThat(base.getProperty("spring.datasource.username")).isNull();
        assertThat(base.getProperty("spring.datasource.password")).isNull();
    }
}
