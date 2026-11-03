package com.chomiii.database;

import java.util.function.Function;

/**
 * Resolves the CHOMIII_DB_* contract for the local MySQL integration suite.
 * Values may come from environment variables or JVM system properties
 * (environment wins). Never logs or prints resolved values.
 */
final class LocalDatabaseEnvironment {

    private final Function<String, String> environment;
    private final Function<String, String> properties;

    LocalDatabaseEnvironment(Function<String, String> environment, Function<String, String> properties) {
        this.environment = environment;
        this.properties = properties;
    }

    static LocalDatabaseEnvironment system() {
        return new LocalDatabaseEnvironment(System::getenv, System::getProperty);
    }

    String required(String name) {
        String value = resolve(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    name + " must be provided for the local MySQL suite");
        }
        return value;
    }

    /**
     * The password is the only value that may legitimately be empty, for an
     * explicitly configured isolated test service. Only absence fails loudly.
     */
    String password() {
        String value = resolve("CHOMIII_DB_PASSWORD");
        if (value == null) {
            throw new IllegalStateException(
                    "CHOMIII_DB_PASSWORD must be provided for the local MySQL suite "
                            + "(an empty value is allowed for an explicitly configured isolated test service)");
        }
        return value;
    }

    private String resolve(String name) {
        String value = environment.apply(name);
        return value != null ? value : properties.apply(name);
    }
}
