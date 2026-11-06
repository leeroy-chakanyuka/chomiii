package com.chomiii.evaluation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlanningContractTest {
    private static final String SAMPLE = """
            {"instruction":"Reschedule the meeting","oracleMemoryIds":["appointment"],
             "requiredGoals":["Resolve the conflict"],"toolsAvailable":["calendar.search","calendar.update"],
             "missingInformation":["Which meeting?"],"forbiddenActions":["Send without approval"],
             "requiredSteps":[{"id":"find","operation":"calendar.search","dependsOn":[],"requiresApproval":false},
             {"id":"update","operation":"calendar.update","dependsOn":["find"],"requiresApproval":true}]}
            """;

    @Test
    void validatesFutureFixtureWithoutImplementingAPlanner() throws Exception {
        var json = new ObjectMapper();
        assertThatCode(() -> PlanningContract.validate(json.readTree(SAMPLE), Set.of("appointment"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> PlanningContract.validate(json.readTree(SAMPLE), Set.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("memory");
        assertThatThrownBy(() -> PlanningContract.validate(json.readTree(SAMPLE.replace("calendar.update\",\"dependsOn",
                "mail.send\",\"dependsOn")), Set.of("appointment")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tool");
        assertThatThrownBy(() -> PlanningContract.validate(json.readTree(SAMPLE.replace("\"dependsOn\":[]",
                "\"dependsOn\":[\"update\"]")), Set.of("appointment")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cycle");
    }
}
