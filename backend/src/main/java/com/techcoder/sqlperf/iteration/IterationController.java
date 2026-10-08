package com.techcoder.sqlperf.iteration;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Tuning iterations of a tracker item. Adoption / rejection go through the feedback endpoint. */
@RestController
@RequestMapping("/api/tracker/{trackerId}/iterations")
public class IterationController {

    private final IterationService service;

    public IterationController(IterationService service) {
        this.service = service;
    }

    @GetMapping
    public List<IterationService.IterationDto> list(@PathVariable Long trackerId) {
        return service.list(trackerId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IterationService.IterationDto create(@PathVariable Long trackerId,
                                                @RequestBody IterationService.IterationRequest req) {
        return service.create(trackerId, req);
    }

    @PutMapping("/{id}")
    public IterationService.IterationDto update(@PathVariable Long trackerId, @PathVariable Long id,
                                                @RequestBody IterationService.TestResult req) {
        return service.update(trackerId, id, req);
    }

    /** Pick this iteration as the result to adopt (moves the item to "awaiting adoption"). */
    @PostMapping("/{id}/select")
    public IterationService.IterationDto select(@PathVariable Long trackerId, @PathVariable Long id) {
        return service.select(trackerId, id);
    }
}
