package com.chomiii.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins the CHOMIII_DB_* resolution contract of the local MySQL suite:
 * URL and user must be present and non-blank, while the password
 * distinguishes absent (fail loudly) from present-but-empty (valid for an
 * explicitly configured isolated test service). Synthetic maps isolate these
 * checks from the real environment and its credentials.
 */
class LocalDatabaseEnvironmentTest {

    private final Map<String, String> environment = new HashMap<>();
    private final Map<String, String> properties = new HashMap<>();
    private final LocalDatabaseEnvironment config = new LocalDatabaseEnvironment(environment::get, properties::get);

    @Test
    void absentUrlFailsLoudlyMentioningTheVariable() {
        IllegalStateException failure = catchThrowableOfType(
                () -> config.required("CHOMIII_DB_URL"),
                IllegalStateException.class);

        assertThat(failure).hasMessageContaining("CHOMIII_DB_URL");
    }

    @Test
    void blankUserFailsLoudly() {
        properties.put("CHOMIII_DB_USER", "   ");

        IllegalStateException failure = catchThrowableOfType(
                () -> config.required("CHOMIII_DB_USER"),
                IllegalStateException.class);

        assertThat(failure).hasMessageContaining("CHOMIII_DB_USER");
    }

    @Test
    void absentPasswordFailsLoudlyMentioningTheVariable() {
        IllegalStateException failure = catchThrowableOfType(
                config::password,
                IllegalStateException.class);

        assertThat(failure).hasMessageContaining("CHOMIII_DB_PASSWORD");
    }

    @Test
    void presentButEmptyPasswordIsAcceptedForAnIsolatedTestService() {
        environment.put("CHOMIII_DB_PASSWORD", "");

        assertThat(config.password()).isEmpty();
    }

    @Test
    void presentPasswordIsReturnedUnmodified() {
        properties.put("CHOMIII_DB_PASSWORD", "test-password");

        assertThat(config.password()).isEqualTo("test-password");
    }

    @Test
    void environmentTakesPrecedenceOverSystemProperties() {
        environment.put("CHOMIII_DB_USER", "environment-user");
        properties.put("CHOMIII_DB_USER", "property-user");

        assertThat(config.required("CHOMIII_DB_USER")).isEqualTo("environment-user");
    }
}
