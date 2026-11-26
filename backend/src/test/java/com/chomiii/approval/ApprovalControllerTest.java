package com.chomiii.approval;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chomiii.session.AnonymousSessionFilter;
import com.chomiii.planning.execution.PlanRunner;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ApprovalControllerTest {
    @Test
    void decisionsUseSessionOwnerAndForeignApprovalsAreNotFound() throws Exception {
        var facade = mock(ApprovalFacade.class);
        var runner = mock(PlanRunner.class);
        var mvc = MockMvcBuilders.standaloneSetup(new ApprovalController(facade, runner)).build();
        UUID conversation = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        var view = new ApprovalView(id, UUID.randomUUID(), "todo.create", "Create task: \"Milk\"",
                Instant.parse("2027-01-01T00:00:00Z"), "pending", null, null);
        when(facade.list("owner", conversation)).thenReturn(List.of(view));
        UUID planId = UUID.randomUUID();
        when(facade.approveAndPlanToResume("owner", conversation, id))
                .thenReturn(new ApprovalFacade.ApprovalDecision(view, planId));
        when(facade.reject("other", conversation, id)).thenThrow(new IllegalArgumentException());
        String path = "/api/conversations/" + conversation + "/approvals";
        mvc.perform(get(path).requestAttr(AnonymousSessionFilter.OWNER_ATTRIBUTE, "owner"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].preview").value(view.preview()))
                .andExpect(jsonPath("$[0].owner").doesNotExist()).andExpect(jsonPath("$[0].argumentsJson").doesNotExist());
        mvc.perform(post(path + "/" + id + "/approve").requestAttr(AnonymousSessionFilter.OWNER_ATTRIBUTE, "owner")
                .contentType("application/json").content("{\"owner\":\"other\",\"tool\":\"bad\"}"))
                .andExpect(status().isOk());
        verify(facade).approveAndPlanToResume("owner", conversation, id);
        verify(runner).run("owner", conversation, planId);
        mvc.perform(post(path + "/" + id + "/reject").requestAttr(AnonymousSessionFilter.OWNER_ATTRIBUTE, "other"))
                .andExpect(status().isNotFound());
    }
}
