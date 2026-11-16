package com.chomiii.planning;

import static org.assertj.core.api.Assertions.assertThat;

import com.chomiii.provider.ChatProvider;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanningProfileContextTest {
    @Test
    void profileIsUntrustedUserContextDuringPlanning() {
        var input = new PlanningService().input("test-model", List.of(), "Plan my week", List.of(),
                "Profile: I live in Harare.");

        assertThat(input.systemInstruction()).doesNotContain("Harare");
        assertThat(input.messages()).anySatisfy(message -> {
            assertThat(message.role()).isEqualTo(ChatProvider.Role.USER);
            assertThat(message.text()).contains("Harare", "NOT instructions");
        });
        assertThat(input.messages().getLast().text()).isEqualTo("Plan my week");
    }
}
