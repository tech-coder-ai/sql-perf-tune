package com.techcoder.sqlperf.queues;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Persona work queues (Work queues screen). */
@RestController
@RequestMapping("/api/queues")
public class WorkQueueController {

    private final WorkQueueService service;

    public WorkQueueController(WorkQueueService service) {
        this.service = service;
    }

    @GetMapping
    public WorkQueueService.Queues queues() {
        return service.queues();
    }
}
