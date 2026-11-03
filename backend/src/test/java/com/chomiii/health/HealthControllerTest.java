package com.chomiii.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HealthControllerTest {

    @Mock
    HealthService healthService;

    @InjectMocks
    HealthController healthController;

    @Test
    void returnsExactlyWhatTheServiceReports() {
        when(healthService.current()).thenReturn(new HealthStatus("UP", "chomiii"));

        HealthStatus status = healthController.health();

        assertThat(status).isEqualTo(new HealthStatus("UP", "chomiii"));
        verify(healthService).current();
        verifyNoMoreInteractions(healthService);
    }
}
