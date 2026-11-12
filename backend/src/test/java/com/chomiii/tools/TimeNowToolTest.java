package com.chomiii.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TimeNowToolTest {
    private final TimeNowTool time = new TimeNowTool(
            Clock.fixed(Instant.parse("2026-11-12T08:00:00Z"), ZoneOffset.UTC));

    @Test
    void realUtcClockIsExposedThroughTheProviderNeutralRegistry() {
        var turn = new ToolTurn(List.of(time), false);
        assertThat(turn.definitions()).extracting(spec -> spec.name())
                .containsExactly("time.now");
        assertThat(turn.invoke("time.now", Map.of("zone", "Africa/Johannesburg")).toString())
                .contains("2026-11-12", "10:00", "+02:00", "THURSDAY");
    }

    @Test
    void unknownZoneAndUntrustedExtraArgumentsAreRejected() {
        var turn = new ToolTurn(List.of(time), false);
        assertThat(turn.invoke("time.now", Map.of("zone", "Nonexistent/Zone"))).containsKey("error");
        assertThat(turn.invoke("time.now", Map.of("zone", "UTC", "owner", "someone-else")))
                .containsKey("error");
    }

    @Test
    void noLocationIsInferredWithoutATimeZone() {
        var turn = new ToolTurn(List.of(time), false);
        assertThat(turn.invoke("time.now", Map.of()).toString())
                .contains("UTC", "08:00");
    }
}
