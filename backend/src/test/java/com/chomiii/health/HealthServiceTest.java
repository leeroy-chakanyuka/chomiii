package com.chomiii.health;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HealthServiceTest {

    HealthService healthService = new HealthService();

    @Test
    void reportsUpStatusForChomiii() {
        HealthStatus status = healthService.current();

        assertThat(status.status()).isEqualTo("UP");
        assertThat(status.service()).isEqualTo("chomiii");
    }
}
