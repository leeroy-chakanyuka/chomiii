package com.chomiii.tools.trace;

import com.chomiii.session.AnonymousSessionFilter;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

/** Read-only activity log for the owning browser session. */
@RestController
public class ToolTraceController {
    private final ToolTraceService service;

    public ToolTraceController(ToolTraceService service) {
        this.service = service;
    }

    @GetMapping("/api/conversations/{conversationId}/tool-traces")
    public List<ToolTraceDto.Trace> list(@PathVariable UUID conversationId,
            @RequestAttribute(AnonymousSessionFilter.OWNER_ATTRIBUTE) String owner) {
        return service.list(conversationId, owner);
    }
}
