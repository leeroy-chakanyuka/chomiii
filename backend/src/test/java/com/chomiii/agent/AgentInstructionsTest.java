package com.chomiii.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AgentInstructionsTest {
    @Test
    void loadsShortGeneralPurposeIdentityWithoutSkillRouting() {
        var identity = new AgentInstructions();
        assertThat(identity.systemPrompt()).contains("Chomiii", "warm", "no tools");
        assertThat(identity.systemPrompt()).doesNotContain("debugging", "Activated skill", "/skill");
        assertThat(identity.systemPrompt().length()).isLessThanOrEqualTo(2_000);
        assertThat(identity.systemPrompt()).isEqualTo(identity.systemPrompt());
    }

    @Test
    void failsClosedWhenThePackagedIdentityIsMissing() {
        ClassLoader missing = new ClassLoader(AgentInstructions.class.getClassLoader()) {
            @Override
            public InputStream getResourceAsStream(String name) {
                return "agent/SYSTEM.md".equals(name) ? null : super.getResourceAsStream(name);
            }
        };
        assertThatThrownBy(() -> new AgentInstructions(missing))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Missing agent resource");
    }

    @Test
    void refusesAnUnboundedOrEmptyIdentity() {
        for (String value : new String[] {" ", "x".repeat(2_001)}) {
            ClassLoader invalid = new ClassLoader(AgentInstructions.class.getClassLoader()) {
                @Override
                public InputStream getResourceAsStream(String name) {
                    if ("agent/SYSTEM.md".equals(name)) {
                        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
                    }
                    return super.getResourceAsStream(name);
                }
            };
            assertThatThrownBy(() -> new AgentInstructions(invalid))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("Invalid agent identity size");
        }
    }
}
