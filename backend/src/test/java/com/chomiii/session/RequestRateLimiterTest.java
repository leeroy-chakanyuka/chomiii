package com.chomiii.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RequestRateLimiterTest {
    @Test
    void limitsOneClientWithoutBlockingOthersAndResetsAfterOneMinute() {
        var limiter = new RequestRateLimiter(2, 100);
        Instant now = Instant.parse("2026-11-04T00:00:00Z");

        assertThat(limiter.allow("first", now)).isTrue();
        assertThat(limiter.allow("first", now)).isTrue();
        assertThat(limiter.allow("first", now)).isFalse();
        assertThat(limiter.allow("second", now)).isTrue();
        assertThat(limiter.allow("first", now.plusSeconds(60))).isTrue();
    }

    @Test
    void boundsTrackedClientsWithoutDiscardingActiveLimits() {
        var limiter = new RequestRateLimiter(1, 2);
        Instant now = Instant.parse("2026-11-04T00:00:00Z");

        assertThat(limiter.allow("first", now)).isTrue();
        assertThat(limiter.allow("second", now)).isTrue();
        assertThat(limiter.allow("third", now)).isFalse();
        assertThat(limiter.allow("first", now)).isFalse();
        assertThat(limiter.allow("third", now.plusSeconds(60))).isTrue();
    }
}
