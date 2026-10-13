package com.techcoder.sqlperf.workflow;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Which optimization agent answers new runs, so the UI can wait for it or ask for a pasted answer. */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final WorkflowService workflow;

    public AgentController(WorkflowService workflow) {
        this.workflow = workflow;
    }

    @GetMapping
    public WorkflowService.AgentInfo info() {
        return workflow.agentInfo();
    }
}
