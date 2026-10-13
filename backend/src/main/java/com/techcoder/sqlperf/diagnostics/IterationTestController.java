package com.techcoder.sqlperf.diagnostics;

import com.techcoder.sqlperf.iteration.IterationService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Runs an iteration's SQL on the engine and records the test result (step 10). */
@RestController
@RequestMapping("/api/tracker/{trackerId}/iterations/{id}/test")
public class IterationTestController {

    private final IterationTestService tests;
    private final IterationService iterations;

    public IterationTestController(IterationTestService tests, IterationService iterations) {
        this.tests = tests;
        this.iterations = iterations;
    }

    /** @return the item's iterations with the new test result (and the new best). */
    @PostMapping
    public java.util.List<IterationService.IterationDto> test(@PathVariable Long trackerId, @PathVariable Long id)
            throws Exception {
        tests.test(trackerId, id);
        return iterations.list(trackerId);
    }
}
