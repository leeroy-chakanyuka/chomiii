package com.chomiii.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * Runs the same versioned migration the PostgreSQL suite proves, against the
 * locally installed MySQL, without Docker. Invoked explicitly with
 * {@code mvn verify -Plocal-mysql} using the disposable database supplied by
 * the environment (never creating or mutating anything itself):
 * <pre>
 *   CHOMIII_DB_URL=e.g. jdbc:mysql://localhost:3306/chomiii
 *   CHOMIII_DB_USER=...
 *   CHOMIII_DB_PASSWORD=...
 * </pre>
 * Kept as plain JDBC + Flyway (no Spring context) to stay cheap on this
 * machine. Missing environment variables fail loudly instead of skipping.
 */
class BackendMySqlLocalIT {

    private static final LocalDatabaseEnvironment CONFIG = LocalDatabaseEnvironment.system();

    @Test
    void connectsOverJdbcAndRunsSelectOne() throws Exception {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("select 1")) {

            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void flywayRecordsBaselineMigrationAsSuccessful() throws Exception {
        Flyway.configure()
                .dataSource(CONFIG.required("CHOMIII_DB_URL"),
                        CONFIG.required("CHOMIII_DB_USER"),
                        CONFIG.password())
                .load()
                .migrate();

        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            try (ResultSet history = statement.executeQuery(
                    "select version, description, success from flyway_schema_history order by installed_rank")) {

                assertThat(history.next()).isTrue();
                assertThat(history.getString("version")).isEqualTo("1");
                assertThat(history.getString("description")).isEqualTo("establish baseline");
                assertThat(history.getInt("success")).isEqualTo(1);
                assertThat(history.next()).isTrue();
                assertThat(history.getString("version")).isEqualTo("2");
                assertThat(history.getString("description")).isEqualTo("conversations");
                assertThat(history.getInt("success")).isEqualTo(1);
                assertThat(history.next()).isFalse();
            }
        }
    }

    private static Connection openConnection() throws Exception {
        return DriverManager.getConnection(
                CONFIG.required("CHOMIII_DB_URL"),
                CONFIG.required("CHOMIII_DB_USER"),
                CONFIG.password());
    }
}
