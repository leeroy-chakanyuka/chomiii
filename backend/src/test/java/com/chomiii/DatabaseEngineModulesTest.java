package com.chomiii;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

/**
 * The backend runs against two engines: MySQL locally, PostgreSQL in CI and
 * production. Both JDBC drivers and both Flyway database modules must exist
 * on the classpath so the same versioned migrations run on either engine.
 */
class DatabaseEngineModulesTest {

    @Test
    void providesPostgreSqlDriverAndFlywayModule() {
        assertThatCode(() -> Class.forName("org.postgresql.Driver")).doesNotThrowAnyException();
        assertThatCode(() -> Class.forName("org.flywaydb.database.postgresql.PostgreSQLDatabase"))
                .doesNotThrowAnyException();
    }

    @Test
    void providesMySqlDriverAndFlywayModule() {
        assertThatCode(() -> Class.forName("com.mysql.cj.jdbc.Driver")).doesNotThrowAnyException();
        assertThatCode(() -> Class.forName("org.flywaydb.database.mysql.MySQLDatabase"))
                .doesNotThrowAnyException();
    }
}
