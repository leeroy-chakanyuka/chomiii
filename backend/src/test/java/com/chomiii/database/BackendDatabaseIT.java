package com.chomiii.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves real PostgreSQL connectivity and that Flyway migrations actually apply
 * against a real database. Runs via `mvn verify` (Failsafe); requires Docker.
 *
 * <p>Activates the "ci" profile explicitly so the default "local" profile
 * (with its MySQL datasource placeholders) is not evaluated here and the
 * @ServiceConnection container is the single source of connection details.
 */
@SpringBootTest
@ActiveProfiles("ci")
@Testcontainers
class BackendDatabaseIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void connectsToRealPostgres() {
        Integer one = jdbcTemplate.queryForObject("select 1", Integer.class);

        assertThat(one).isEqualTo(1);
    }

    @Test
    void appliesFlywayBaselineMigration() {
        List<Map<String, Object>> history =
                jdbcTemplate.queryForList("select version, description, success from flyway_schema_history order by installed_rank");

        assertThat(history).hasSize(1);
        assertThat(history.getFirst().get("version")).isEqualTo("1");
        assertThat(history.getFirst().get("description")).isEqualTo("establish baseline");
        assertThat(history.getFirst().get("success")).isEqualTo(true);
    }
}
